package cn.erp.exporting;

import cn.erp.ErpApplication;
import cn.erp.documents.*;
import cn.erp.reporting.*;
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
class ReportExportHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ExportJobs jobs;
    @Autowired ExportAccess access;
    @Autowired ReportService reports;
    @Autowired ObjectFiles files;
    @Autowired ObjectCompensation compensation;
    @Autowired ObjectMapper json;
    private long tenant,company,otherCompany,user,role,warehouse,unit,category,product,sku;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    private static final String PASSWORD="ReportTest!2026";
    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();user=Ids.next();role=Ids.next();warehouse=Ids.next();unit=Ids.next();category=Ids.next();product=Ids.next();sku=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'报表接口验收')",tenant,"REPORTHTTP"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲'),(?,?,'B','公司乙')",company,tenant,otherCompany,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','报表验收仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'reporter','报表经办',?)",user,tenant,passwords.encode(PASSWORD));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'REPORT','报表角色')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(var type:ReportType.values()) { grant(type.permission());grant(type.exportPermission()); }grant("cost:view");
        for(long id:List.of(company,otherCompany)) { jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,id); }
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','分类','/CAT')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','报表商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'SKU','=SUM(1,2)')",sku,tenant,product);
        jdbc.update("INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id,on_hand_qty) VALUES(?,?,?,?,?,10)",Ids.next(),tenant,company,warehouse,sku);
        jdbc.update("INSERT INTO inventory_cost(id,tenant_id,company_id,warehouse_id,sku_id,qty,total_cost) VALUES(?,?,?,?,?,10,100)",Ids.next(),tenant,company,warehouse,sku);
    }
    @AfterEach void cleanup() {
        for(String key:jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?",String.class,tenant)) { files.delete(key); }
        for(String table:List.of("file_ticket","export_job","attachment","audit_log","idempotency_record","inventory_cost","inventory_balance","sku","product","product_category","unit","sys_user_data_scope","sys_role_permission","sys_user_role","sys_role","sys_user","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    @Test void reportHttpRequiresLoginCurrentTypePermissionAndCompanyScope() throws Exception {
        assertThat(http("GET","/reports/inventory-summary",null,null,false,company).statusCode()).isEqualTo(401);login();
        var stock=http("GET","/reports/inventory-summary",null,null,false,company);assertThat(stock.statusCode()).isEqualTo(200);
        assertThat(json.readTree(stock.body()).path("data").path("items").get(0).path("onHandQty").asText()).isEqualTo("10.000000");
        for(var type:ReportType.values()) { assertThat(http("GET","/reports/"+type.code().replace('_','-'),null,null,false,company).statusCode()).as(type.code()).isEqualTo(200); }
        assertThat(json.readTree(http("GET","/reports/inventory-summary",null,null,false,otherCompany).body()).path("data").path("items").size()).isZero();
        revoke("report:inventory_summary");assertThat(http("GET","/reports/inventory-summary",null,null,false,company).statusCode()).isEqualTo(403);
        grant("report:inventory_summary");revoke("cost:view");
        assertThat(http("GET","/reports/inventory-summary",null,null,false,company).body()).doesNotContain("costAmount");
        assertThat(http("GET","/reports/gross-margin",null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("GET","/reports/sales-summary?dateFrom=2020-01-01&dateTo=2026-11-15",null,null,false,company).statusCode()).isEqualTo(400);
    }
    @Test void exportHttpRechecksCsrfPermissionsTicketsCostsAndWarehouseScopeOnDownload() throws Exception {
        login();String input="{\"exportType\":\"inventory_summary\",\"query\":{}}",key=UUID.randomUUID().toString();
        assertThat(http("POST","/export-jobs",input,key,false,company).statusCode()).isEqualTo(403);
        var created=http("POST","/export-jobs",input,key,true,company);assertThat(created.statusCode()).isEqualTo(202);
        long id=Long.parseLong(json.readTree(created.body()).path("data").path("taskId").asText());
        assertThat(http("POST","/export-jobs",input,key,true,company).body()).contains(Long.toString(id));
        assertThat(http("GET","/export-jobs/"+id+"/download",null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/export-jobs/"+id+"/download-ticket","{}",null,true,company).statusCode()).isEqualTo(409);
        new ExportWorker(jobs,access,reports,files,json,compensation).runOne();
        var ticket=http("POST","/export-jobs/"+id+"/download-ticket","{}",null,true,company);
        assertThat(ticket.statusCode()).isEqualTo(200);String path=json.readTree(ticket.body()).path("data").path("url").asText().replace("/api/v1","");
        assertThat(http("GET","/export-jobs/"+id+"/download",null,null,false,company).statusCode()).isEqualTo(403);
        var csv=http("GET",path,null,null,false,company);
        assertThat(csv.statusCode()).isEqualTo(200);assertThat(csv.body()).startsWith("\uFEFF").contains("'=SUM(1,2)","'10.000000","'100.00");
        revoke("export:inventory_summary");assertThat(http("GET",path,null,null,false,company).statusCode()).isEqualTo(403);
        grant("export:inventory_summary");assertThat(http("GET",path,null,null,false,company).statusCode()).isEqualTo(200);
        revoke("cost:view");assertThat(http("GET",path,null,null,false,company).statusCode()).isEqualTo(403);grant("cost:view");
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        long otherWarehouse=Ids.next();jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W2','无权下载原仓')",otherWarehouse,tenant,company);
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,user,company,otherWarehouse);
        assertThat(http("GET",path,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("GET","/export-jobs/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        jdbc.update("UPDATE sys_user_data_scope SET scope_type='company',warehouse_id=NULL WHERE tenant_id=? AND user_id=? AND company_id=?",tenant,user,company);
        jdbc.update("UPDATE file_ticket SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE tenant_id=? AND purpose='export_job' AND resource_id=?",tenant,id);
        assertThat(http("GET",path,null,null,false,company).statusCode()).isEqualTo(403);
    }
    private void grant(String permission) { jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,permission); }
    private void revoke(String permission) { jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",tenant,role,permission); }
    private void login() throws Exception {
        var response=http("POST","/auth/login","{\"tenantCode\":\"REPORTHTTP"+tenant+"\",\"username\":\"reporter\",\"password\":\""+PASSWORD+"\"}",null,false,company);assertThat(response.statusCode()).isEqualTo(200);
        assertThat(http("GET","/auth/me",null,null,false,company).statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long companyId) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(companyId));
        if(body!=null) { builder.header("Content-Type","application/json"); }
        if(key!=null) { builder.header("Idempotency-Key",key); }
        if(csrf) { builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow()); }
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
