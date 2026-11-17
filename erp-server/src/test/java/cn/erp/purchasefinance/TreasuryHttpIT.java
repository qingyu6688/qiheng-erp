package cn.erp.purchasefinance;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.time.LocalDate;
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
class TreasuryHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired LedgerService ledger;
    private long tenant,company,otherCompany,user,role,partner,account,target,outside,period;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    private static final String PASSWORD="TreasuryTest!2026";

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();user=Ids.next();role=Ids.next();partner=Ids.next();account=Ids.next();target=Ids.next();outside=Ids.next();period=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'资金接口验收')",tenant,"TREASURYHTTP"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲')",company,tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','公司乙')",otherCompany,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id,separate_receipt_confirmer) VALUES(?,?,?,0)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'cashier','财务经办',?)",user,tenant,passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'TREASURY','资金权限')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(String permission:List.of("payment:view","payment:create","payment:submit","payment:confirm","account_transfer:view","account_transfer:create","account_transfer:submit","account_transfer:confirm")) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,permission);
        }
        for(long id:List.of(company,otherCompany)) {jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,id);}
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'VENDOR','供应商')",partner,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),tenant,partner);
        for(long id:List.of(account,target,outside)) {
            long companyId=id==outside?otherCompany:company;
            jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",id,tenant,companyId,"BANK"+id,"银行账户");
            jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,companyId,id);
        }
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        var scope=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of());
        ledger.post(scope,new LedgerService.Posting("opening_entry",Ids.next(),0,period,LocalDate.of(2026,11,1),List.of(
                new LedgerService.Line("FUNDS","D",new BigDecimal("100"),null,account,null,null,null,"资金接口期初"),
                new LedgerService.Line("OPENING_EQUITY","C",new BigDecimal("100"),null,null,null,null,null,"资金接口期初"))));
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item","payment","account_transfer","audit_log","idempotency_record","document_sequence","cash_account_balance","cash_account","accounting_period","partner_role","business_partner","org_user_data_scope","sys_role_permission","sys_user_role","sys_role","sys_user","company_policy","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void fundsHttpRequiresAuthenticationCsrfAndCurrentOperationPermission() throws Exception {
        assertThat(http("GET","/payments",null,null,false,company).statusCode()).isEqualTo(401);
        login();
        assertThat(http("POST","/account-transfers",transfer(target),key(),false,company).statusCode()).isEqualTo(403);
        String key=key();var created=http("POST","/account-transfers",transfer(target),key,true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(created.body()).doesNotContain("bankAccountNo","accountNo","passwordHash");
        assertThat(http("GET","/account-transfers/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        assertThat(http("POST","/account-transfers",transfer(outside),key(),true,company).statusCode()).isEqualTo(404);
        String query="/command-results?operation=account_transfer.create&key="+key;
        assertThat(http("GET",query,null,null,false,company).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='account_transfer:create')",tenant,role);
        assertThat(http("GET",query,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/account-transfers",transfer(target),key,true,company).statusCode()).isEqualTo(403);
    }

    @Test void payableReadRequiresSettlementPermissionAndUsesCurrentCompany() throws Exception {
        assertThat(http("GET","/payables",null,null,false,company).statusCode()).isEqualTo(401);
        login();
        assertThat(http("GET","/payables",null,null,false,company).statusCode()).isEqualTo(403);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='settlement:view'",Ids.next(),tenant,role);
        for(long companyId:List.of(company,otherCompany)) {
            var response=http("GET","/payables?supplierId="+partner+"&openOnly=true&limit=20",null,null,false,companyId);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("data").path("items").size()).isZero();
        }
        assertThat(http("GET","/payables?limit=0",null,null,false,company).statusCode()).isEqualTo(400);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='settlement:view')",tenant,role);
        assertThat(http("GET","/payables",null,null,false,company).statusCode()).isEqualTo(403);
    }

    @Test void paymentIllegalTransitionIs409AndInsufficientBalanceIsAtomic422() throws Exception {
        login();
        var created=http("POST","/payments",payment("100.01"),key(),true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("POST","/payments/"+id+"/confirm","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(http("POST","/payments/"+id+"/submit","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(200);
        var rejected=http("POST","/payments/"+id+"/confirm","{\"version\":1}",key(),true,company);
        assertThat(rejected.statusCode()).isEqualTo(422);
        assertThat(json.readTree(rejected.body()).path("errorCode").asText()).isEqualTo("CASH_BALANCE_INSUFFICIENT");
        assertThat(jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='payment'",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE id=?",String.class,id)).isEqualTo("pending_confirm");
    }

    private void login() throws Exception {
        var response=http("POST","/auth/login","{\"tenantCode\":\"TREASURYHTTP"+tenant+"\",\"username\":\"cashier\",\"password\":\""+PASSWORD+"\"}",null,false,company);
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private String transfer(long target) {return "{\"fromAccountId\":\""+account+"\",\"toAccountId\":\""+target+"\",\"amount\":\"10\",\"feeAmount\":\"0\",\"businessDate\":\"2026-11-01\"}";}
    private String payment(String amount) {return "{\"paymentType\":\"supplier\",\"partnerId\":\""+partner+"\",\"cashAccountId\":\""+account+"\",\"amount\":\""+amount+"\",\"businessDate\":\"2026-11-01\"}";}
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long company) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));
        if(body!=null) {builder.header("Content-Type","application/json");}
        if(key!=null) {builder.header("Idempotency-Key",key);}
        if(csrf) {builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());}
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String key() {return UUID.randomUUID().toString();}
}
