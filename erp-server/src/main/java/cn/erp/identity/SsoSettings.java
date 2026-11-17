package cn.erp.identity;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;

@Service
class SsoSettings {
    static final String PERMISSION="tenant:login_settings";
    static final String CALLBACK=IdentitySecurity.AUTH+"/sso/callback";
    private static final Map<String,String> LABELS=Map.of("oidc","企业单点登录","ldap","企业账号",
            "wecom","企业微信","dingtalk","钉钉","feishu","飞书");
    private static final List<String> METHODS=List.of("oidc","ldap","wecom","dingtalk","feishu");
    private static final TypeReference<Map<String,String>> CONFIG=new TypeReference<>() {};
    private static final Set<String> LDAP_ACCOUNT_KEYS=Set.of("uid","sAMAccountName","userPrincipalName");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SecretProtector secrets;
    private final AuditService audit;
    private final Set<String> allowedHosts;

    SsoSettings(JdbcTemplate jdbc,ObjectMapper json,SecretProtector secrets,AuditService audit,
                @Value("${ERP_LOGIN_SSO_ALLOWED_HOSTS:}") String allowedHosts) {
        this.jdbc=jdbc;
        this.json=json;
        this.secrets=secrets;
        this.audit=audit;
        this.allowedHosts=Arrays.stream(allowedHosts.split(",")).map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isBlank()).collect(Collectors.toUnmodifiableSet());
    }

    List<Method> available(long tenant) {
        if (!secrets.configured()) return List.of();
        return jdbc.query("""
                    SELECT method,enabled,config_json,secret_ciphertext,version FROM tenant_login_provider
                    WHERE tenant_id=? AND enabled=1 AND method IN ('oidc','ldap','wecom','dingtalk','feishu') ORDER BY method
                    """,(row,index) -> stored(row.getString("method"),row),tenant).stream().filter(this::ready)
                .map(item -> new Method(item.type(),item.type(),item.name())).toList();
    }

    List<View> view(CompanyAccess actor) {
        PlatformAccess.require(jdbc,actor,PERMISSION);
        return METHODS.stream().map(method -> load(actor.tenantId(),method).view()).toList();
    }

    Provider provider(long tenant,String method,boolean requireEnabled) {
        var stored=load(tenant,method);
        if ((requireEnabled && !stored.enabled()) || stored.cipher()==null) throw disabled();
        validate(method,stored.config());
        return new Provider(method,stored.config(),secrets.decrypt(tenant,"sso:"+method,stored.cipher()),stored.version());
    }

    Provider testProvider(CompanyAccess actor,String method) {
        PlatformAccess.require(jdbc,actor,PERMISSION);
        return provider(actor.tenantId(),method,false);
    }

    @Transactional
    public View save(CompanyAccess actor,String method,Update input) {
        requireMethod(method);
        PlatformAccess.lock(jdbc,actor,PERMISSION);
        if (input.enabled()==null || input.version()==null) throw invalid("请选择启用状态并提供配置版本");
        var previous=load(actor.tenantId(),method);
        if (input.version()!=previous.version()) throw new ApiException(409,"VERSION_CONFLICT","登录设置已更新，请重新加载");
        String name=input.name()==null || input.name().isBlank()? LABELS.get(method): field(input.name(),64,"方式名称");
        var keys=defaults(method).keySet();
        var supplied=input.config()==null?Map.<String,String>of():input.config();
        if (!keys.containsAll(supplied.keySet())) throw invalid("登录方式包含未支持的配置项");
        var config=new LinkedHashMap<>(defaults(method));
        supplied.forEach((key,value) -> {
            if (value!=null && !value.isBlank()) config.put(key,field(value,500,"登录方式配置"));
        });
        String cipher=previous.cipher();
        if (input.secret()!=null && !input.secret().isBlank()) {
            if (input.secret().getBytes(StandardCharsets.UTF_8).length>1024 || input.secret().chars().anyMatch(Character::isISOControl))
                throw invalid("登录服务密钥超出长度或包含控制字符");
            cipher=secrets.encrypt(actor.tenantId(),"sso:"+method,input.secret());
        }
        if (input.enabled()) {
            validate(method,config);
            if (cipher==null) throw invalid("启用前请填写登录服务密钥");
            secrets.decrypt(actor.tenantId(),"sso:"+method,cipher);
        }
        ensureRealmUnchanged(actor.tenantId(),method,previous.config(),config);
        if (previous.enabled() && !input.enabled() && !canDisable(actor,method))
            throw invalid("企业已关闭密码登录，请先保留至少一个可用的管理员登录渠道");
        config.put("name",name);
        jdbc.update("""
                INSERT INTO tenant_login_provider(tenant_id,method,enabled,config_json,secret_ciphertext,version)
                VALUES(?,?,?,?,?,1) AS new_provider
                ON DUPLICATE KEY UPDATE enabled=new_provider.enabled,config_json=new_provider.config_json,
                  secret_ciphertext=new_provider.secret_ciphertext,version=tenant_login_provider.version+1
                """,actor.tenantId(),method,input.enabled(),json.writeValueAsString(config),cipher);
        audit.record(actor,"login_settings.sso_update","tenant",actor.tenantId(),null,Map.of("method",method,"enabled",input.enabled()));
        return load(actor.tenantId(),method).view();
    }

    private boolean canDisable(CompanyAccess actor,String method) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT COALESCE((SELECT password_login_allowed FROM tenant_security_policy WHERE tenant_id=?),TRUE)
                    OR EXISTS(SELECT 1 FROM user_login_identity i JOIN tenant_login_provider p
                         ON p.tenant_id=i.tenant_id AND p.method=i.method AND p.enabled=1
                         WHERE i.tenant_id=? AND i.user_id=? AND i.method<>? AND i.method IN ('oidc','ldap','wecom','dingtalk','feishu'))
                """,Boolean.class,actor.tenantId(),actor.tenantId(),actor.userId(),method));
    }

    private Stored load(long tenant,String method) {
        requireMethod(method);
        return jdbc.query("""
                    SELECT enabled,config_json,secret_ciphertext,version FROM tenant_login_provider WHERE tenant_id=? AND method=?
                    """,(row,index) -> stored(method,row),tenant,method).stream().findFirst()
                .orElse(new Stored(method,LABELS.get(method),false,defaults(method),null,0));
    }

    private Stored stored(String method,java.sql.ResultSet row) throws java.sql.SQLException {
        var config=new LinkedHashMap<>(defaults(method));
        config.putAll(json.readValue(row.getString("config_json"),CONFIG));
        String name=config.remove("name");
        return new Stored(method,name==null?LABELS.get(method):name,row.getBoolean("enabled"),Map.copyOf(config),
                row.getString("secret_ciphertext"),row.getLong("version"));
    }

    private void ensureRealmUnchanged(long tenant,String method,Map<String,String> previous,Map<String,String> proposed) {
        var realmKeys=switch(method) {
            case "oidc" -> List.of("issuer","jwksUrl","clientId","tokenUrl","authorizationUrl");
            case "ldap" -> List.of("serverUrl","baseDn","userAttribute");
            case "wecom" -> List.of("corpId","agentId");
            default -> List.of("clientId");
        };
        if (realmKeys.stream().anyMatch(key -> !previous.get(key).equals(proposed.get(key)))
                && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM user_login_identity WHERE tenant_id=? AND method=?)",
                        Boolean.class,tenant,method))) throw invalid("登录身份来源已绑定用户，更换来源前请先解除对应绑定");
    }

    private boolean ready(Stored stored) {
        if (!stored.enabled() || stored.cipher()==null) return false;
        try { validate(stored.type(),stored.config()); return true; }
        catch (ApiException unavailable) { return false; }
    }

    private void validate(String method,Map<String,String> config) {
        if (config.values().stream().anyMatch(String::isBlank)) throw invalid("启用前请填写完整的登录方式配置");
        if ("ldap".equals(method)) {
            URI server=approved(config.get("serverUrl"),"ldaps",636);
            if (server.getRawPath()!=null && !server.getRawPath().isEmpty()) throw invalid("目录服务地址请单独配置主机，搜索范围填写到 Base DN");
            ldapName(config.get("baseDn"));
            ldapName(config.get("bindDn"));
            if (!LDAP_ACCOUNT_KEYS.contains(config.get("userAttribute"))) throw invalid("目录账号属性格式有误");
            return;
        }
        URI callback=uri(config.get("callbackUrl"),"https");
        if (!CALLBACK.equals(callback.getRawPath())) throw invalid("请使用本站 HTTPS 单点登录回调地址");
        if ("oidc".equals(method)) {
            for (String key:List.of("issuer","authorizationUrl","tokenUrl","jwksUrl")) approved(config.get(key),"https",443);
            if (!Set.of("client_secret_basic","client_secret_post").contains(config.get("clientAuth"))) throw invalid("OIDC 应用认证方式无效");
            return;
        }
        String id=config.get("wecom".equals(method)?"corpId":"clientId");
        if (!id.matches("[a-zA-Z0-9_-]{1,128}") || "wecom".equals(method) && !config.get("agentId").matches("[0-9]{1,18}"))
            throw invalid("应用标识格式不正确");
    }

    private URI approved(String value,String scheme,int defaultPort) {
        URI address=uri(value,scheme);
        String host=address.getHost().toLowerCase(Locale.ROOT);
        int port=address.getPort()<0?defaultPort:address.getPort();
        if (!allowedHosts.contains(host+":"+port) && !(port==defaultPort && allowedHosts.contains(host)))
            throw invalid("登录服务主机和端口未列入运维允许清单");
        return address;
    }

    static URI uri(String value,String scheme) {
        try {
            URI address=URI.create(value);
            if (scheme.equalsIgnoreCase(address.getScheme()) && address.getHost()!=null && address.getUserInfo()==null
                    && address.getRawQuery()==null && address.getRawFragment()==null
                    && (address.getPort()==-1 || address.getPort()>0 && address.getPort()<=65535)) return address;
        } catch (IllegalArgumentException malformed) {
            throw invalid("登录服务地址格式有误");
        }
        throw invalid("登录服务必须使用安全协议且地址不能包含凭据、查询项或片段");
    }

    static String ldapName(String value) {
        try { return new LdapName(value).getRdns().reversed().stream().map(rdn -> rdn.toString().toLowerCase(Locale.ROOT)).collect(Collectors.joining(",")); }
        catch (InvalidNameException failure) { throw invalid("目录 DN 格式有误"); }
    }

    static void requireMethod(String method) {
        if (method==null || !LABELS.containsKey(method)) throw invalid("不支持的单点登录方式");
    }

    private static Map<String,String> defaults(String method) {
        return switch(method) {
            case "oidc" -> Map.of("issuer","","authorizationUrl","","tokenUrl","","jwksUrl","","clientId","",
                    "callbackUrl","","clientAuth","client_secret_basic");
            case "ldap" -> Map.of("serverUrl","","baseDn","","userAttribute","uid","bindDn", "");
            case "wecom" -> Map.of("corpId","","agentId","","callbackUrl","");
            case "dingtalk","feishu" -> Map.of("clientId","","callbackUrl","");
            default -> throw invalid("不支持的单点登录方式");
        };
    }

    private static String field(String value,int size,String label) {
        if (value.length()>size || value.chars().anyMatch(Character::isISOControl)) throw invalid(label+"格式有误");
        return value.strip();
    }

    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    static ApiException disabled() { return new ApiException(404,"LOGIN_METHOD_DISABLED","当前企业未启用或未配置该登录方式"); }

    record Provider(String type,Map<String,String> config,String secret,long version) {
        @Override public String toString() {return "单点登录配置 "+type;}
    }
    record Method(String id,String type,String name) {}
    record View(String id,String type,String name,boolean enabled,Map<String,String> config,boolean secretConfigured,long version) {}
    record Update(Boolean enabled,String name,Map<String,String> config,String secret,Long version) {
        @Override public String toString() {return "单点登录配置变更";}
    }
    private record Stored(String type,String name,boolean enabled,Map<String,String> config,String cipher,long version) {
        View view() {return new View(type,type,name,enabled,config,cipher!=null,version);}
    }
}
