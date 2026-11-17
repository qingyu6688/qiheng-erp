package cn.erp.period;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PeriodHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private long tenant,company,otherCompany,period,otherPeriod,warehouse,user,role;
    private static final String PASSWORD="PeriodTest!2026";
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    @BeforeEach void seed(){
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();period=Ids.next();otherPeriod=Ids.next();warehouse=Ids.next();user=Ids.next();role=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'期间接口验收')",tenant,"PERIODHTTP"+tenant);
        for(long id:List.of(company,otherCompany))jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",id,tenant,id==company?"A":"B","期间公司");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','期间仓库')",warehouse,tenant,company);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",otherPeriod,tenant,otherCompany);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'accountant','期间管理员',?)",user,tenant,passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'PERIOD','期间权限')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(String permission:List.of("period:view","period:close","period:reopen"))jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,permission);
        for(long id:List.of(company,otherCompany))jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,id);
    }
    @AfterEach void cleanup(){
        for(String table:List.of("audit_log","idempotency_record","ledger_projection_lock","org_user_data_scope","warehouse","accounting_period","sys_role_permission","sys_user_role","sys_role","sys_user","company"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    @Test void authenticationCsrfAndReadOnlyFiveItemPrecheck() throws Exception {
        assertThat(http("GET","/accounting-periods",null,null,false,company).statusCode()).isEqualTo(401);login();String path="/accounting-periods/"+period+"/close-check";
        assertThat(http("POST",path,null,null,false,company).statusCode()).isEqualTo(403);
        for(int repeat=0;repeat<2;repeat++){
            var response=http("POST",path,null,null,true,company);assertThat(response.statusCode()).isEqualTo(200);var data=json.readTree(response.body()).path("data");assertThat(data.path("passed").asBoolean()).isTrue();
            var names=new HashSet<String>();for(var item:data.path("items")){names.add(item.path("check").asText());assertThat(item.path("passed").asBoolean()).isTrue();assertThat(item.path("count").asLong()).isZero();}
            assertThat(names).containsExactlyInAnyOrder("unposted_documents","pending_money","inventory_reconciliation","money_reconciliation","cost_residue");
        }
        assertThat(jdbc.queryForObject("SELECT version FROM accounting_period WHERE id=?",Integer.class,period)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM idempotency_record WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action LIKE 'period.%'",Long.class,tenant)).isZero();
    }
    @Test void closeReopenAndOriginalKeyHistoryRecheckCurrentPermissions() throws Exception {
        login();String close="/accounting-periods/"+period+"/close",closeKey=key();var closed=http("POST",close,"{\"version\":0}",closeKey,true,company);assertThat(closed.statusCode()).isEqualTo(200);assertThat(json.readTree(closed.body()).path("data").path("status").asText()).isEqualTo("closed");
        assertThat(http("POST",close,"{\"version\":0}",closeKey,true,company).statusCode()).isEqualTo(200);
        String closeHistory="/command-results?operation=period.close&key="+closeKey;assertThat(http("GET",closeHistory,null,null,false,company).statusCode()).isEqualTo(200);revoke("period:close");
        assertThat(http("GET",closeHistory,null,null,false,company).statusCode()).isEqualTo(403);assertThat(http("POST",close,"{\"version\":0}",closeKey,true,company).statusCode()).isEqualTo(403);
        String reopen="/accounting-periods/"+period+"/reopen";assertThat(http("POST",reopen,"{\"version\":1,\"reason\":\" \"}",key(),true,company).statusCode()).isEqualTo(400);
        String reopenKey=key();var reopened=http("POST",reopen,"{\"version\":1,\"reason\":\"复核后补录\"}",reopenKey,true,company);assertThat(reopened.statusCode()).isEqualTo(200);assertThat(json.readTree(reopened.body()).path("data").path("status").asText()).isEqualTo("open");
        String reopenHistory="/command-results?operation=period.reopen&key="+reopenKey;assertThat(http("GET",reopenHistory,null,null,false,company).statusCode()).isEqualTo(200);revoke("period:reopen");assertThat(http("GET",reopenHistory,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT reopen_count FROM accounting_period WHERE id=?",Integer.class,period)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action IN('period.close','period.reopen')",Long.class,tenant)).isEqualTo(2);
    }
    @Test void crossCompanyAndWarehouseOnlyScopeCannotManageWholeCompanyPeriod() throws Exception {
        login();String path="/accounting-periods/"+period+"/close-check";assertThat(http("POST",path,null,null,true,otherCompany).statusCode()).isEqualTo(404);
        assertThat(http("POST","/accounting-periods/"+otherPeriod+"/close-check",null,null,true,company).statusCode()).isEqualTo(404);
        String closeKey=key();assertThat(http("POST","/accounting-periods/"+period+"/close","{\"version\":0}",closeKey,true,company).statusCode()).isEqualTo(200);
        assertThat(http("POST","/accounting-periods/"+period+"/reopen","{\"version\":1,\"reason\":\"复核数据范围\"}",key(),true,company).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM org_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,user,company,warehouse);
        assertThat(http("POST",path,null,null,true,company).statusCode()).isEqualTo(403);
        assertThat(http("GET","/command-results?operation=period.close&key="+closeKey,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/accounting-periods/"+period+"/close","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/accounting-periods/"+period+"/reopen","{\"version\":0,\"reason\":\"尝试重开\"}",key(),true,company).statusCode()).isEqualTo(403);
        assertThat(jdbc.queryForObject("SELECT status FROM accounting_period WHERE id=?",String.class,period)).isEqualTo("open");
    }
    private void revoke(String permission){jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",tenant,role,permission);}
    private void login() throws Exception {assertThat(http("POST","/auth/login","{\"tenantCode\":\"PERIODHTTP"+tenant+"\",\"username\":\"accountant\",\"password\":\""+PASSWORD+"\"}",null,false,company).statusCode()).isEqualTo(200);}
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long company) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));if(body!=null)request.header("Content-Type","application/json");if(key!=null)request.header("Idempotency-Key",key);
        if(csrf)request.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String key(){return UUID.randomUUID().toString();}
}
