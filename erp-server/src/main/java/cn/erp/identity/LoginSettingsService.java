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

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;

@Service
class LoginSettingsService {
    private static final List<String> METHODS = List.of("sms", "email", "wechat");
    private static final int SMTP_DEFAULT_PORT = 587;
    private static final int MAX_SECRET_BYTES = 1024;
    private static final String WECHAT_CALLBACK = "/api/v1/auth/wechat/callback";
    private static final String HOST_PATTERN = "(?i)[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
            + "(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)*";
    private static final TypeReference<Map<String, String>> CONFIG_TYPE = new TypeReference<>() {};

    record Provider(Map<String, String> config, String secret, long version) {
        @Override
        public String toString() { return "登录服务配置，版本 " + version; }
    }

    record Sms(Boolean enabled, String accessKeyId, String signName, String templateCode,
               String secret, Long version) {}
    record Email(Boolean enabled, String host, Integer port, String security, String username,
                 String from, String secret, Long version) {}
    record Wechat(Boolean enabled, String appId, String callbackUrl, String secret, Long version) {}
    record Update(Sms sms, Email email, Wechat wechat) {}

    private record Stored(boolean enabled, Map<String, String> config, String encrypted, long version) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LoginSettingsCipher cipher;
    private final AuditService audit;
    private final Set<String> smtpAllowedHosts;

    LoginSettingsService(JdbcTemplate jdbc, ObjectMapper json, LoginSettingsCipher cipher, AuditService audit,
                         @Value("${ERP_LOGIN_SMTP_ALLOWED_HOSTS:}") String smtpHosts) {
        this.jdbc = jdbc;
        this.json = json;
        this.cipher = cipher;
        this.audit = audit;
        this.smtpAllowedHosts = Arrays.stream(smtpHosts.split(","))
                .map(host -> host.strip().toLowerCase(Locale.ROOT))
                .filter(host -> !host.isEmpty()).collect(Collectors.toUnmodifiableSet());
    }

    Map<String, Boolean> methods(String tenantCode) {
        Map<String, Boolean> availability = new LinkedHashMap<>();
        METHODS.forEach(method -> availability.put(method, false));
        if (!cipher.configured()) return availability;
        jdbc.query("""
                SELECT p.method,p.enabled,p.config_json,p.secret_ciphertext,p.version
                FROM tenant_login_provider p JOIN tenant t ON t.id=p.tenant_id
                WHERE t.code=? AND t.status='active' AND p.enabled=1
                """, row -> {
            String method = row.getString("method");
            if (availability.containsKey(method)) {
                availability.put(method, ready(method, readRow(row)));
            }
        }, tenantCode);
        return availability;
    }

    Provider provider(long tenantId, String method) {
        if (!METHODS.contains(method) || !cipher.configured()) return null;
        var result = jdbc.query("""
                SELECT enabled,config_json,secret_ciphertext,version
                FROM tenant_login_provider WHERE tenant_id=? AND method=? AND enabled=1
                """, (row, index) -> readRow(row), tenantId, method);
        if (result.isEmpty() || !ready(method, result.getFirst())) return null;
        var stored = result.getFirst();
        return new Provider(stored.config(), cipher.decrypt(tenantId, method, stored.encrypted()), stored.version());
    }

    Map<String, Map<String, Object>> read(long tenantId) {
        return view(load(tenantId));
    }

    @Transactional
    public Map<String, Map<String, Object>> save(CompanyAccess actor, Update update) {
        if (update == null || update.sms() == null && update.email() == null && update.wechat() == null) {
            throw invalid("请提供至少一种登录方式的配置");
        }
        long tenantId = actor.tenantId();
        // 企业行锁使首次插入也参与同一套并发控制。
        jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE", Long.class, tenantId);
        var current = load(tenantId);
        Map<String, Boolean> changed = new LinkedHashMap<>();
        if (update.sms() != null) {
            var sms = update.sms();
            var fields = Map.of("accessKeyId", token(sms.accessKeyId(), 128, "阿里云 AccessKey ID"),
                    "signName", field(sms.signName(), 128, "短信签名"),
                    "templateCode", token(sms.templateCode(), 64, "短信模板编号"));
            saveProvider(tenantId, "sms", sms.enabled(), fields, sms.secret(), sms.version(), current, changed);
        }
        if (update.email() != null) {
            var email = update.email();
            String host = field(email.host(), 253, "SMTP 主机").toLowerCase(Locale.ROOT);
            if (!host.isEmpty() && !host.matches(HOST_PATTERN)) throw invalid("SMTP 主机格式有误");
            int port = email.port() == null ? SMTP_DEFAULT_PORT : email.port();
            if (port < 1 || port > 65535) throw invalid("SMTP 端口必须在 1 至 65535 之间");
            String security = email.security() == null ? "starttls" : email.security();
            if (!Set.of("starttls", "tls").contains(security)) throw invalid("SMTP 必须使用 STARTTLS 或 TLS");
            String from = field(email.from(), 254, "发件邮箱");
            if (!from.isEmpty() && !validAddress(from)) throw invalid("发件邮箱格式有误");
            var fields = Map.of("host", host, "port", Integer.toString(port), "security", security,
                    "username", field(email.username(), 254, "SMTP 用户名"), "from", from);
            saveProvider(tenantId, "email", email.enabled(), fields, email.secret(), email.version(), current, changed);
        }
        if (update.wechat() != null) {
            var wechat = update.wechat();
            String callback = field(wechat.callbackUrl(), 500, "微信回调地址");
            if (!callback.isEmpty()) validateCallback(callback);
            var fields = Map.of("appId", token(wechat.appId(), 64, "微信 AppID"), "callbackUrl", callback);
            saveProvider(tenantId, "wechat", wechat.enabled(), fields, wechat.secret(), wechat.version(), current, changed);
        }
        if (!changed.isEmpty()) {
            // 审计仅记录变更的登录方式及开关，渠道账户和凭据留在受限配置中。
            audit.record(actor, "login_settings.update", "tenant", tenantId, null, changed);
        }
        return view(current);
    }

    private void saveProvider(long tenantId, String method, Boolean enabled, Map<String, String> fields,
                              String secret, Long expectedVersion, Map<String, Stored> current,
                              Map<String, Boolean> changed) {
        if (enabled == null) throw invalid("请选择是否启用登录方式");
        var previous = current.get(method);
        long version = previous == null ? 0 : previous.version();
        if (expectedVersion != null && expectedVersion != version) {
            throw new ApiException(409, "VERSION_CONFLICT", "登录设置已更新，请刷新后重试");
        }
        boolean replaceSecret = secret != null && !secret.isBlank();
        String encrypted = previous == null ? null : previous.encrypted();
        if (replaceSecret) {
            if (secret.getBytes(StandardCharsets.UTF_8).length > MAX_SECRET_BYTES
                    || secret.chars().anyMatch(Character::isISOControl)) {
                throw invalid("渠道密钥长度过长或包含控制字符");
            }
            if (!cipher.configured()) throw invalid("请先在服务端配置登录设置加密密钥");
            encrypted = cipher.encrypt(tenantId, method, secret);
        }
        if (enabled) {
            if (!cipher.configured()) throw invalid("请先在服务端配置登录设置加密密钥");
            if (encrypted == null || fields.values().stream().anyMatch(String::isEmpty)) {
                throw invalid("请完整填写渠道配置和密钥后再启用");
            }
            if ("email".equals(method) && !smtpAllowed(fields.get("host"))) {
                throw invalid("SMTP 主机未经运维授权，请检查服务端允许清单");
            }
            if (!replaceSecret) cipher.decrypt(tenantId, method, encrypted);
        }
        if (previous == null && !enabled && encrypted == null && defaults(method).equals(fields)) return;
        if (previous != null && previous.enabled() == enabled && previous.config().equals(fields)
                && !replaceSecret) return;
        String configJson = json.writeValueAsString(fields);
        if (previous == null) {
            jdbc.update("""
                    INSERT INTO tenant_login_provider(tenant_id,method,enabled,config_json,secret_ciphertext,version)
                    VALUES(?,?,?,?,?,1)
                    """, tenantId, method, enabled, configJson, encrypted);
        } else {
            jdbc.update("""
                    UPDATE tenant_login_provider SET enabled=?,config_json=?,secret_ciphertext=?,version=version+1
                    WHERE tenant_id=? AND method=?
                    """, enabled, configJson, encrypted, tenantId, method);
        }
        current.put(method, new Stored(enabled, fields, encrypted, version + 1));
        changed.put(method, enabled);
    }

    boolean smtpAllowed(String host) {
        return host != null && smtpAllowedHosts.contains(host);
    }

    private boolean ready(String method, Stored stored) {
        return stored.enabled() && stored.encrypted() != null
                && (!"email".equals(method) || smtpAllowed(stored.config().get("host")));
    }

    private Map<String, Stored> load(long tenantId) {
        Map<String, Stored> rows = new LinkedHashMap<>();
        jdbc.query("""
                SELECT method,enabled,config_json,secret_ciphertext,version FROM tenant_login_provider
                WHERE tenant_id=?
                """, row -> {
            rows.put(row.getString("method"), readRow(row));
        }, tenantId);
        return rows;
    }

    private Stored readRow(java.sql.ResultSet row) throws java.sql.SQLException {
        return new Stored(row.getBoolean("enabled"), json.readValue(row.getString("config_json"), CONFIG_TYPE),
                row.getString("secret_ciphertext"), row.getLong("version"));
    }

    private Map<String, Map<String, Object>> view(Map<String, Stored> rows) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (String method : METHODS) {
            var stored = rows.getOrDefault(method, new Stored(false, defaults(method), null, 0));
            Map<String, Object> fields = new LinkedHashMap<>();
            defaults(method).forEach((key, value) -> fields.put(key, stored.config().getOrDefault(key, value)));
            if ("email".equals(method)) fields.put("port", Integer.parseInt(fields.get("port").toString()));
            fields.put("enabled", stored.enabled());
            fields.put("secretConfigured", stored.encrypted() != null);
            fields.put("version", stored.version());
            result.put(method, fields);
        }
        return result;
    }

    private Map<String, String> defaults(String method) {
        return switch (method) {
            case "sms" -> Map.of("accessKeyId", "", "signName", "", "templateCode", "");
            case "email" -> Map.of("host", "", "port", Integer.toString(SMTP_DEFAULT_PORT), "security", "starttls",
                    "username", "", "from", "");
            case "wechat" -> Map.of("appId", "", "callbackUrl", "");
            default -> throw invalid("登录方式无效");
        };
    }

    private String field(String value, int length, String label) {
        if (value == null) return "";
        if (value.length() > length || value.chars().anyMatch(Character::isISOControl)) {
            throw invalid(label + "格式有误");
        }
        return value.strip();
    }

    private String token(String value, int length, String label) {
        var text = field(value, length, label);
        if (!text.isEmpty() && !text.matches("[a-zA-Z0-9_-]+")) throw invalid(label + "格式有误");
        return text;
    }

    static boolean validAddress(String value) {
        try {
            var address = new InternetAddress(value, true);
            address.validate();
            return value.equals(address.getAddress()) && value.chars().allMatch(ch -> ch >= 33 && ch <= 126);
        } catch (AddressException failure) {
            return false;
        }
    }

    private void validateCallback(String callback) {
        try {
            URI uri = URI.create(callback);
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getRawFragment() == null && uri.getRawQuery() == null
                    && WECHAT_CALLBACK.equals(uri.getRawPath())) return;
        } catch (IllegalArgumentException failure) {
            throw invalid("微信回调地址格式有误");
        }
        throw invalid("微信回调地址必须为本系统 HTTPS 登录回调地址");
    }

    private ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }
}
