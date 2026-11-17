package cn.erp.identity;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import cn.erp.shared.Ids;
import cn.erp.sysadmin.ApiLogWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ERP_LOGIN_SETTINGS_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "ERP_LOGIN_SSO_ALLOWED_HOSTS=idp.example.test,ldap.example.test"
})
class SsoMfaIT {
    private static final String ISSUER="https://idp.example.test/realm/fixture";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired ApiLogWriter logs;
    @MockitoBean SsoWebClient oauth;
    @MockitoBean LdapLoginClient ldap;

    private final long tenant=Ids.next();
    private final long foreign=Ids.next();
    private final long adminId=Ids.next();
    private final long userId=Ids.next();
    private final long appId=Ids.next();
    private final long role=Ids.next();
    private final long foreignRole=Ids.next();
    private final long foreignUser=Ids.next();
    private final long company=Ids.next();
    private final String tenantCode="MFA"+tenant;
    private final String password=UUID.randomUUID().toString();

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'身份回归企业'),(?,?,'其他企业')",tenant,tenantCode,foreign,"OTHER"+foreign);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','身份验收公司')",company,tenant);
        String hash=passwords.encode(password);
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,user_type) VALUES
                (?,?,'admin','管理员',?,'human'),(?,?,'member','普通成员',?,'human'),
                (?,?,'integration','接口账号',?,'app'),(?,?,'outside','其他企业成员',?,'human')
                """,adminId,tenant,hash,userId,tenant,hash,appId,tenant,hash,foreignUser,foreign,hash);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'SYSTEM_ADMIN','系统管理员'),(?,?,'MEMBER','成员'),(?,?,'FOREIGN','外部角色')",
                adminId,tenant,role,tenant,foreignRole,foreign);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?),(?,?,?,?)",
                Ids.next(),tenant,adminId,adminId,Ids.next(),tenant,userId,role);
        for(long actor:List.of(adminId,userId)) jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)
                """,Ids.next(),tenant,actor,company);
        for(String permission:List.of("tenant:login_settings","tenant:security_policy")) jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?
                """,Ids.next(),tenant,adminId,permission);
        when(oauth.authorize(any(SsoSettings.Provider.class),anyString(),anyString(),anyString())).thenAnswer(invocation ->
                new SsoWebClient(json).authorize(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),invocation.getArgument(3)));
    }

    @AfterEach
    void clean() {
        logs.flush();
        for(String table:List.of("sys_api_access_log","sys_api_error_log","sys_login_log","audit_log","user_mfa","tenant_security_policy",
                "user_login_identity","tenant_login_provider","sys_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user","company"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id IN (?,?)",tenant,foreign);
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)",tenant,foreign);
    }

    @Test
    void pendingMfaCannotAccessApiOrReplayCodesAndResetRevokesBothAssuranceStates() throws Exception {
        var admin=new Browser();
        var member=new Browser();
        login(admin,"admin",200);
        login(member,"member",200);
        member.send("GET","/sys/security-policy",null,false,403);
        admin.send("PUT","/sys/security-policy",policy(List.of(Long.toString(foreignRole)),false,0),true,400);
        admin.send("PUT","/sys/security-policy",policy(List.of(Long.toString(role)),false,0),true,200);
        member.send("GET","/auth/me",null,false,401);

        var challenge=login(member,"member",403);
        assertThat(challenge.path("errorCode").asString()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        String pendingCookie=member.cookie("ERPSESSION");
        member.send("GET","/auth/me",null,false,401);
        assertThat(member.data("GET","/auth/mfa/status",null,false).path("step").asString()).isEqualTo("enroll");
        member.send("POST","/auth/mfa/setup",null,false,403);
        var setup=member.data("POST","/auth/mfa/setup",null,true);
        String seed=setup.path("secret").asString();
        String code=TotpCodes.code(seed,Instant.now().getEpochSecond()/30);
        var activated=member.data("POST","/auth/mfa/activate",Map.of("code",code),true);
        assertThat(activated.path("user").path("id").asString()).isEqualTo(Long.toString(userId));
        assertThat(member.cookie("ERPSESSION")).isNotEqualTo(pendingCookie);
        assertThat(activated.path("recoveryCodes").size()).isEqualTo(10);
        String recovery=activated.path("recoveryCodes").path(0).asString();
        var stored=jdbc.queryForMap("SELECT secret_cipher,recovery_codes_hash FROM user_mfa WHERE tenant_id=? AND user_id=?",tenant,userId);
        assertThat(stored.toString()).doesNotContain(seed,recovery,TotpCodes.canonicalRecovery(recovery));
        member.send("POST","/account/mfa/disable",Map.of("code",recovery),true,403);

        var fresh=new Browser();
        assertThat(login(fresh,"member",403).path("errorCode").asString()).isEqualTo("MFA_REQUIRED");
        fresh.send("POST","/auth/mfa/verify",Map.of("code",code),true,400);
        fresh.send("POST","/auth/mfa/verify",Map.of("code",recovery),true,200);
        var waiting=new Browser();
        login(waiting,"member",403);
        waiting.send("POST","/auth/mfa/verify",Map.of("code",recovery),true,400);
        admin.send("POST","/sys/users/"+foreignUser+"/mfa/reset",null,true,404);
        admin.send("POST","/sys/users/"+userId+"/mfa/reset",null,true,200);
        fresh.send("GET","/auth/me",null,false,401);
        waiting.send("GET","/auth/mfa/status",null,false,401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='mfa.reset'",Long.class,tenant)).isEqualTo(1);
    }

    @Test
    void oidcStateCannotCrossBrowserOrBeReplayedAndBoundSessionKeepsLocalPermissions() throws Exception {
        var admin=new Browser();
        var browser=new Browser();
        var other=new Browser();
        login(admin,"admin",200);
        JsonNode local=login(new Browser(),"member",200).path("data");
        enableOidc(admin,0);
        admin.send("PUT","/sys/users/"+foreignUser+"/login-identities/oidc",Map.of("subject","member-sub"),true,404);
        admin.send("PUT","/sys/users/"+appId+"/login-identities/oidc",Map.of("subject","member-sub"),true,404);
        login(new Browser(),"integration",401);
        bind(admin,userId,"oidc","member-sub");
        URI authorization=authorize(browser);
        String state=query(authorization,"state");
        String nonce=query(authorization,"nonce");
        String challenge=query(authorization,"code_challenge");
        when(oauth.exchange(any(SsoSettings.Provider.class),anyString(),anyString(),anyString())).thenAnswer(call -> {
            assertThat(call.<String>getArgument(2)).isEqualTo(nonce);
            String proof=call.getArgument(3);
            String digest=Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.US_ASCII)));
            assertThat(digest).isEqualTo(challenge);
            return new SsoWebClient.Identity(ISSUER+"|member-sub",null);
        });
        assertThat(callback(other,state)).isEqualTo("/login?sso=error");
        assertThat(callback(browser,state)).isEqualTo("/login?sso=success");
        assertThat(callback(browser,state)).isEqualTo("/login?sso=error");
        verify(oauth,times(1)).exchange(any(SsoSettings.Provider.class),anyString(),anyString(),anyString());
        assertThat(browser.data("GET","/auth/me",null,false)).isEqualTo(local);
        admin.send("DELETE","/sys/users/"+userId+"/login-identities/oidc",null,true,200);
        browser.send("GET","/auth/me",null,false,401);

        URI stale=authorize(other);
        enableOidc(admin,1);
        assertThat(callback(other,query(stale,"state"))).isEqualTo("/login?sso=error");
        verify(oauth,times(1)).exchange(any(SsoSettings.Provider.class),anyString(),anyString(),anyString());
    }

    @Test
    void ssoOnlyCannotUseLocalPasswordAndLdapCannotBypassRoleMfa() throws Exception {
        var admin=new Browser();
        login(admin,"admin",200);
        var config=Map.of("serverUrl","ldaps://ldap.example.test","baseDn","dc=example,dc=test",
                "bindDn","cn=fixture,dc=example,dc=test","userAttribute","uid");
        admin.send("PUT","/login-settings/sso/ldap",provider(config,0),true,200);
        admin.send("PUT","/sys/security-policy",policy(List.of(),true,0),true,400);
        bind(admin,adminId,"ldap","uid=admin,dc=example,dc=test");
        bind(admin,userId,"ldap","uid=member,dc=example,dc=test");
        admin.send("PUT","/sys/security-policy",policy(List.of(Long.toString(role)),true,0),true,200);
        admin.send("GET","/auth/me",null,false,401);
        var browser=new Browser();
        login(browser,"member",401);
        when(ldap.authenticate(any(SsoSettings.Provider.class),anyString(),anyString())).thenReturn("uid=member,dc=example,dc=test");
        var challenged=browser.send("POST","/auth/ldap/login",credentials("member"),false,403);
        assertThat(challenged.path("errorCode").asString()).isEqualTo("MFA_ENROLLMENT_REQUIRED");
        browser.send("GET","/auth/me",null,false,401);
        assertThat(browser.data("GET","/auth/methods?tenantCode="+tenantCode,null,false).path("password").asBoolean()).isFalse();
        verify(oauth,never()).exchange(any(SsoSettings.Provider.class),anyString(),anyString(),anyString());
    }

    @Test
    void verifiesLocalEmailLinksAndProvisionsUnmatchedExternalUsersDisabled() throws Exception {
        var admin=new Browser();
        var browser=new Browser();
        login(admin,"admin",200);
        enableOidc(admin,0);
        admin.send("PUT","/sys/security-policy",Map.of("mfaRequiredRoles",List.of(),"passwordLoginAllowed",true,
                "ssoAutoProvision",true,"ssoDefaultRoleId",Long.toString(role),"version",0),true,200);
        jdbc.update("INSERT INTO user_login_identity(id,tenant_id,user_id,method,subject) VALUES(?,?,?,'email',?),(?,?,?,'email',?)",
                Ids.next(),tenant,userId,"member@example.test",Ids.next(),foreign,foreignUser,"foreign@example.test");
        when(oauth.exchange(any(SsoSettings.Provider.class),anyString(),anyString(),anyString()))
                .thenReturn(new SsoWebClient.Identity(ISSUER+"|known-member","member@example.test"),
                        new SsoWebClient.Identity(ISSUER+"|new-person","foreign@example.test"));
        var known=new Browser();
        assertThat(callback(known,query(authorize(known),"state"))).isEqualTo("/login?sso=success");
        assertThat(known.data("GET","/auth/me",null,false).path("id").asString()).isEqualTo(Long.toString(userId));
        assertThat(callback(browser,query(authorize(browser),"state"))).isEqualTo("/login?sso=pending");
        assertThat(callback(browser,query(authorize(browser),"state"))).isEqualTo("/login?sso=pending");
        browser.send("GET","/auth/me",null,false,401);
        assertThat(jdbc.queryForList("""
                SELECT u.status,ur.role_id FROM user_login_identity i JOIN sys_user u ON u.tenant_id=i.tenant_id AND u.id=i.user_id
                JOIN sys_user_role ur ON ur.tenant_id=u.tenant_id AND ur.user_id=u.id WHERE i.tenant_id=? AND i.subject=?
                """,tenant,ISSUER+"|new-person")).hasSize(1).first().satisfies(record -> {
            assertThat(record.get("status")).isEqualTo("disabled");
            assertThat(((Number)record.get("role_id")).longValue()).isEqualTo(role);
        });
    }

    private void enableOidc(Browser admin,int version) throws Exception {
        admin.send("PUT","/login-settings/sso/oidc",provider(Map.of("issuer",ISSUER,"authorizationUrl",ISSUER+"/authorize",
                "tokenUrl",ISSUER+"/token","jwksUrl",ISSUER+"/keys","clientId","fixture-app",
                "callbackUrl","https://127.0.0.1:"+port+"/api/v1/auth/sso/callback"),version),true,200);
    }

    private Map<String,Object> provider(Map<String,String> config,int version) {
        return Map.of("config",config,"enabled",true,"secret",UUID.randomUUID().toString(),"version",version);
    }

    private void bind(Browser admin,long subject,String method,String value) throws Exception {
        admin.send("PUT","/sys/users/"+subject+"/login-identities/"+method,Map.of("subject",value),true,200);
    }

    private Map<String,Object> policy(List<String> roles,boolean onlySso,int version) {
        return Map.of("mfaRequiredRoles",roles,"passwordLoginAllowed",!onlySso,"ssoAutoProvision",false,"version",version);
    }

    private Map<String,String> credentials(String username) {return Map.of("tenantCode",tenantCode,"username",username,"password",password);}
    private JsonNode login(Browser browser,String username,int status) throws Exception {
        return browser.send("POST","/auth/login",credentials(username),false,status);
    }

    private URI authorize(Browser browser) throws Exception {
        var response=browser.request("GET","/auth/sso/oidc/start?tenantCode="+tenantCode,null,false);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(302);
        return URI.create(response.headers().firstValue("Location").orElseThrow());
    }

    private String callback(Browser browser,String state) throws Exception {
        var reply=browser.request("GET","/auth/sso/callback?code=fixture-code&state="+state,null,false);
        assertThat(reply.statusCode()).as(reply.body()).isEqualTo(303);
        return reply.headers().firstValue("Location").orElseThrow();
    }

    private static String query(URI uri,String name) {
        return Arrays.stream(uri.getRawQuery().split("&")).filter(pair -> pair.startsWith(name+"="))
                .map(pair -> URLDecoder.decode(pair.substring(name.length()+1),StandardCharsets.UTF_8)).findFirst().orElseThrow();
    }

    private final class Browser {
        private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();

        String cookie(String name) {
            return cookies.getCookieStore().getCookies().stream().filter(value -> name.equals(value.getName())).map(HttpCookie::getValue)
                    .findFirst().orElseThrow();
        }

        JsonNode data(String method,String path,Object body,boolean csrf) throws Exception {
            return send(method,path,body,csrf,200).path("data");
        }

        JsonNode send(String method,String path,Object body,boolean csrf,int expected) throws Exception {
            var response=request(method,path,body,csrf);
            assertThat(response.statusCode()).as("%s %s %s",method,path,response.body()).isEqualTo(expected);
            return json.readTree(response.body());
        }

        HttpResponse<String> request(String method,String path,Object body,boolean csrf) throws Exception {
            var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
                    .header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if (csrf) builder.header("X-XSRF-TOKEN",cookie("XSRF-TOKEN"));
            return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        }
    }
}
