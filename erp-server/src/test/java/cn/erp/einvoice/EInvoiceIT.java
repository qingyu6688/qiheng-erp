package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.invoicing.InvoiceService;
import cn.erp.documents.ObjectFiles;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import cn.erp.sysadmin.ApiLogWriter;
import java.math.BigDecimal;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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

import static cn.erp.einvoice.EInvoiceProvider.Outcome.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "ERP_LOGIN_SETTINGS_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=","erp.worker.enabled=false"})
class EInvoiceIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PasswordEncoder passwords;
    @Autowired EInvoiceService requests;
    @Autowired EInvoiceWorker worker;
    @Autowired EInvoiceFiles files;
    @Autowired EInvoiceInbound inbound;
    @Autowired EInvoiceConfig config;
    @Autowired EInvoiceSources sources;
    @Autowired InvoiceService legacy;
    @Autowired ObjectFiles objects;
    @Autowired ApiLogWriter logs;
    @MockitoBean EInvoiceProvider provider;
    @LocalServerPort int port;

    private final long tenant=Ids.next(),company=Ids.next(),otherCompany=Ids.next(),foreign=Ids.next(),foreignCompany=Ids.next();
    private final long warehouse=Ids.next(),otherWarehouse=Ids.next(),department=Ids.next(),maker=Ids.next(),checker=Ids.next();
    private final long viewer=Ids.next(),noPermission=Ids.next(),outsider=Ids.next();
    private final long sku=Ids.next(),unit=Ids.next(),partner=Ids.next(),period=Ids.next(),ar=Ids.next();
    private final long payable=Ids.next();
    private final LocalDate today=LocalDate.now(EInvoiceService.ZONE);
    private final String taxNo="TESTSELLER12345678";
    private final String token=UUID.randomUUID().toString();
    private final String password=UUID.randomUUID().toString();
    private final String tenantCode="EINV"+tenant,foreignCode="EINV"+foreign;
    private final CompanyAccess author=new CompanyAccess(tenant,maker,company,true,Set.of(),Set.of());
    private final CompanyAccess approver=new CompanyAccess(tenant,checker,company,true,Set.of(),Set.of("invoice_registration:void"));

    @BeforeEach
    void setup() {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'发票回归企业'),(?,?,'外部测试企业')",tenant,tenantCode,foreign,foreignCode);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','发票回归公司'),(?,?,'B','同企业其他公司'),(?,?,'A','外部企业公司')",
                company,tenant,otherCompany,tenant,foreignCompany,foreign);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'A','发票回归仓库'),(?,?,?,'B','其他仓库')",
                warehouse,tenant,company,otherWarehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name,tax_no) VALUES(?,?,'PARTNER','验收往来单位',?)",partner,tenant,taxNo);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','验收分类',?)",category,tenant,"/"+category+"/");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','验收商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,tax_category_code) VALUES(?,?,?,'SKU','验收商品','1010101010101010000')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        var month=YearMonth.from(today);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,company,month.format(DateTimeFormatter.ofPattern("uuuuMM")),month.atDay(1),month.atEndOfMonth());
        seedSignedShipment();
        seedPayable();
        String hash=passwords.encode(password);
        register(maker,tenant,company,"maker",hash,List.of("einv:view","einv:create","einv:submit"));
        register(checker,tenant,company,"checker",hash,List.of("einv:view","einv:submit","einv:red_issue","einv:download","einv:manage","einv:inbound","sku:view","sku:update"));
        register(viewer,tenant,company,"viewer",hash,List.of("einv:view"));
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,viewer,otherCompany);
        register(noPermission,tenant,company,"member",hash,List.of());
        register(outsider,foreign,foreignCompany,"outsider",hash,List.of("einv:view","einv:download","einv:inbound","einv:manage","sku:view","sku:update"));
        when(provider.available()).thenReturn(true);
        when(provider.query(any(),anyString())).thenReturn(new EInvoiceProvider.Result(NOT_FOUND,null,null));
        config.save(approver,new EInvoiceConfig.Input(taxNo,token,true,0L));
    }

    @AfterEach
    void cleanup() {
        logs.flush();
        jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?",String.class,tenant).forEach(objects::delete);
        for(String table:List.of("einv_file_task","einv_file","attachment","einv_inbound","einv_issue_line","einv_issue_request",
                "einv_provider_config","invoice_registration_line","invoice_registration","receivable","payable","goods_receipt","shipment_line","shipment",
                "sales_order_line","sales_order","unit_conversion","sku","product","product_category","unit","idempotency_record","audit_log",
                "sys_api_access_log","sys_api_error_log","sys_login_log","sys_user_data_scope","sys_user_role","sys_role_permission","sys_role","sys_user",
                "accounting_period","business_partner","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id IN (?,?)",tenant,foreign);
        }
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)",tenant,foreign);
    }

    @Test
    void issuedAfterAmbiguousTimeoutWithoutDuplicatePostThenRedReleasesArAndPrivatelyDownloads() throws Exception {
        var author=new Browser("maker",tenantCode,company);
        var finance=new Browser("checker",tenantCode,company);
        var readOnly=new Browser("viewer",tenantCode,company);
        var noAccess=new Browser("member",tenantCode,company);
        var otherCompanyView=new Browser("viewer",tenantCode,otherCompany);
        var external=new Browser("outsider",foreignCode,foreignCompany);
        assertThat(author.send("GET","/candidates?partnerId="+partner,null,200).get(0).path("canIssue").asBoolean()).isTrue();
        String id=create(author,"create-stable");
        assertThat(create(author,"create-stable")).isEqualTo(id);
        assertThat(finance.send("GET","/requests/"+id,null,200).path("amount").asString()).isEqualTo("113.00");
        assertThat(noAccess.send("GET","/requests/"+id,null,403).asString()).isEqualTo("FORBIDDEN");
        assertThat(external.send("GET","/requests/"+id,null,404).asString()).isEqualTo("NOT_FOUND");
        assertThat(otherCompanyView.send("GET","/requests/"+id,null,404).asString()).isEqualTo("NOT_FOUND");
        assertThat(readOnly.send("POST","/requests/"+id+"/submit",Map.of("version",0),403).asString()).isEqualTo("FORBIDDEN");
        assertThat(readOnly.send("GET","/provider",null,403).asString()).isEqualTo("FORBIDDEN");
        assertThat(external.send("GET","/provider",null,200).path("tokenConfigured").asBoolean()).isFalse();
        assertThat(external.send("PUT","/skus/"+sku+"/tax-category",Map.of("version",0,"taxCategoryCode","101010"),404).asString()).isEqualTo("NOT_FOUND");
        assertThat(author.send("POST","/requests/"+id+"/submit",Map.of("version",0),403).asString()).isEqualTo("EINV_SEPARATE_REVIEW");
        finance.send("POST","/requests/"+id+"/submit",Map.of("version",0),200);
        long registration=jdbc.queryForObject("SELECT invoice_registration_id FROM einv_issue_request WHERE id=?",Long.class,id);
        assertCode(()->legacy.voidInvoice(approver,registration,0,"manual-bypass"),"ELECTRONIC_INVOICE_REQUIRES_RED");
        assertThat(invoiced()).isEqualByComparingTo("113.00");
        var winner=new EInvoiceProvider.Result(ISSUED,"TESTBLUE001",today);
        when(provider.issue(any(),eq("issue-"+id),argThat(payload->payload.amount().compareTo(new BigDecimal("113"))==0
                &&payload.taxAmount().compareTo(new BigDecimal("13"))==0&&payload.lines().getFirst().unitPrice().compareTo(new BigDecimal("100"))==0)))
                .thenThrow(new ApiException(503,"EINV_PROVIDER_UNAVAILABLE","测试服务商未返回响应"));
        assertThat(worker.runOne(tenant)).isTrue();
        assertThat(status(id)).isEqualTo("submitted");
        assertThat(worker.runOne(tenant)).isFalse();
        assertThat(invoiced()).isEqualByComparingTo("113");
        due(id);
        when(provider.query(any(),eq("issue-"+id))).thenReturn(winner);
        assertThat(worker.runOne(tenant)).isTrue();
        verify(provider,times(1)).issue(any(),eq("issue-"+id),any());
        assertThat(status(id)).isEqualTo("issued");
        assertThat(legacy.get(approver,registration)).containsEntry("invoiceNo","TESTBLUE001").containsEntry("taxDiffAmount","0.00");

        byte[] pdf="%PDF-1.7\n受控测试文件\n%%EOF".getBytes(StandardCharsets.UTF_8);
        when(provider.download(any(),eq("TESTBLUE001"),eq("pdf"))).thenReturn(pdf);
        for(int format=0;format<3;format++)assertThat(files.runOne(tenant)).isTrue();
        assertThat(files.runOne(tenant)).isFalse();
        String file="/requests/"+id+"/files/issue/pdf";
        var download=finance.raw("GET",file,null);
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition").orElseThrow()).contains("TESTBLUE001.pdf");
        assertThat(download.body()).isEqualTo(pdf);
        assertThat(readOnly.raw("GET",file,null).statusCode()).isEqualTo(403);
        assertThat(external.raw("GET",file,null).statusCode()).isEqualTo(404);

        long version=finance.send("GET","/requests/"+id,null,200).path("version").asLong();
        finance.send("POST","/requests/"+id+"/red-issue",Map.of("version",version,"reason","客户资料变更"),200);
        when(provider.redIssue(any(),eq("red-"+id+"-"+(version+1)),any())).thenReturn(new EInvoiceProvider.Result(ISSUED,"TESTRED001",today));
        assertThat(worker.runOne(tenant)).isTrue();
        assertThat(status(id)).isEqualTo("red_issued");
        assertThat(legacy.get(approver,registration)).containsEntry("status","voided");
        assertThat(invoiced()).isEqualByComparingTo("0");
        assertThat(worker.runOne(tenant)).isFalse();
        assertThat(finance.send("GET","/provider",null,200).toString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT credentials_cipher FROM einv_provider_config WHERE tenant_id=? AND company_id=?",String.class,tenant,company))
                .doesNotContain(token);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND CAST(after_data AS CHAR) LIKE ?",Long.class,tenant,"%"+token+"%")).isZero();
    }

    @Test
    void warehouseAndDepartmentScopesIntersectAndDefiniteFailureReleasesRegistration() {
        var warehouseMismatch=new CompanyAccess(tenant,maker,company,false,Set.of(otherWarehouse),Set.of());
        var departmentMismatch=new CompanyAccess(tenant,maker,company,true,Set.of(),Set.of(),OrgScope.restricted(Set.of(Ids.next()),Set.of(),department));
        var matching=new CompanyAccess(tenant,maker,company,false,Set.of(warehouse),Set.of(),OrgScope.restricted(Set.of(department),Set.of(),department));
        assertThat(sources.candidates(warehouseMismatch,partner)).isEmpty();
        assertThat(sources.candidates(departmentMismatch,partner)).isEmpty();
        assertThat(sources.candidates(matching,partner)).hasSize(1);
        var draft=new EInvoiceService.Draft(partner,"normal",List.of(ar));
        assertCode(()->requests.create(warehouseMismatch,"scope-warehouse",draft),"NOT_FOUND");
        assertCode(()->requests.create(departmentMismatch,"scope-department",draft),"NOT_FOUND");
        long id=Long.parseLong(requests.create(author,"scope-author",draft).get("id").toString());
        assertThat(requests.list(warehouseMismatch,null,30)).isEmpty();
        assertThat(requests.list(departmentMismatch,null,30)).isEmpty();
        assertThat(requests.list(matching,null,30)).hasSize(1);
        assertCode(()->requests.detail(departmentMismatch,id),"NOT_FOUND");
        requests.submit(approver,id,"submit",new EInvoiceService.Revision(0L,null));
        long registration=jdbc.queryForObject("SELECT invoice_registration_id FROM einv_issue_request WHERE id=?",Long.class,id);
        assertCode(()->legacy.get(warehouseMismatch,registration),"NOT_FOUND");
        assertThat(legacy.list(warehouseMismatch,null,30).get("items")).asList().isEmpty();
        assertThat(legacy.get(new CompanyAccess(tenant,checker,company,false,Set.of(warehouse),Set.of()),registration).get("id"))
                .isEqualTo(Long.toString(registration));
        when(provider.issue(any(),eq("issue-"+id),any())).thenReturn(new EInvoiceProvider.Result(FAILED,null,null));
        assertThat(worker.runOne(tenant)).isTrue();
        assertThat(status(Long.toString(id))).isEqualTo("failed");
        assertThat(invoiced()).isEqualByComparingTo("0");
        assertThat(sources.candidates(author,partner)).hasSize(1);
    }

    @Test
    void inboundSyncAndMatchingAreCompanyScopedAndRedImportClearsLink() throws Exception {
        var finance=new Browser("checker",tenantCode,company);
        var readOnly=new Browser("viewer",tenantCode,company);
        var external=new Browser("outsider",foreignCode,foreignCompany);
        when(provider.downloadInbound(any(),eq(today),eq(today))).thenReturn(List.of(inbound("normal")));
        finance.send("POST","/inbound/sync",Map.of("from",today,"to",today),200);
        var invoice=finance.send("GET","/inbound",null,200).get(0);
        String id=invoice.path("id").asString();
        assertThat(invoice.path("checked").asBoolean()).isTrue();
        assertThat(external.send("GET","/inbound",null,200)).isEmpty();
        readOnly.send("GET","/inbound",null,403);
        external.send("GET","/inbound/"+id+"/payables",null,404);
        var candidates=finance.send("GET","/inbound/"+id+"/payables",null,200);
        assertThat(candidates.get(0).path("id").asString()).isEqualTo(Long.toString(payable));
        var match=finance.send("POST","/inbound/"+id+"/match",Map.of("version",0,"payableId",Long.toString(payable)),200);
        assertThat(match.path("payableId").asString()).isEqualTo(Long.toString(payable));
        var limited=new CompanyAccess(tenant,checker,company,false,Set.of(warehouse),Set.of());
        assertCode(()->inbound.list(limited,null,30),"FORBIDDEN");
        when(provider.downloadInbound(any(),eq(today),eq(today))).thenReturn(List.of(inbound("red_issued")));
        finance.send("POST","/inbound/sync",Map.of("from",today,"to",today),200);
        var updated=finance.send("GET","/inbound",null,200).get(0);
        assertThat(updated.path("status").asString()).isEqualTo("red_issued");
        assertThat(updated.path("payableId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT invoiced_amount FROM payable WHERE id=?",BigDecimal.class,payable)).isEqualByComparingTo("0");
    }

    private EInvoiceProvider.Inbound inbound(String status) {
        return new EInvoiceProvider.Inbound("TESTIN001","验收供应商",taxNo,today,new BigDecimal("113.00"),new BigDecimal("13.00"),status,true);
    }

    private void seedSignedShipment() {
        long order=Ids.next(),line=Ids.next(),shipment=Ids.next(),shipmentLine=Ids.next();
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive) VALUES(?,?,?,'ORDER',?,?,1)",
                order,tenant,company,partner,today);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,
                    unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,1,1,113,113,0,113,0,113,.13,100,13)
                """,line,tenant,company,order,sku,warehouse,unit);
        jdbc.update("""
                INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,receivable_amount,
                   untaxed_amount,tax_amount,period_id,posted_at,sign_date,sign_period_id,signed_at,owner_user_id,owner_department_id)
                VALUES(?,?,?,'SHIP',?,?,?,'signed',113,100,13,?,UTC_TIMESTAMP(3),?,?,UTC_TIMESTAMP(3),?,?)
                """,shipment,tenant,company,partner,warehouse,today,period,today,period,maker,department);
        jdbc.update("""
                INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,
                    receivable_amount,untaxed_amount,tax_amount,signed_base_qty)
                VALUES(?,?,?,?,1,?,?,?,1,1,1,113,100,13,1)
                """,shipmentLine,tenant,company,shipment,line,sku,unit);
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,'charge','shipment_line',?,?,113,100,13,?,?)
                """,ar,tenant,company,partner,shipment,shipmentLine,today,period);
    }

    private void seedPayable() {
        long receipt=Ids.next();
        jdbc.update("""
                INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,period_id,posted_at)
                VALUES(?,?,?,'RECEIPT',?,?,?,'posted',?,UTC_TIMESTAMP(3))
                """,receipt,tenant,company,partner,warehouse,today,period);
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES(?,?,?,?,'charge','goods_receipt_line',?,?,113,100,13,?,?)
                """,payable,tenant,company,partner,receipt,Ids.next(),today,period);
    }

    private void register(long user,long tenant,long company,String name,String hash,List<String> permissions) {
        long role=Ids.next();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",user,tenant,name,name,hash);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,?,?)",role,tenant,name,name);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        for(String permission:permissions)jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",
                Ids.next(),tenant,role,permission);
    }

    private String create(Browser author,String key) throws Exception {
        return author.send("POST","/requests",Map.of("partnerId",Long.toString(partner),"invoiceKind","special","receivableIds",List.of(Long.toString(ar))),200,key)
                .path("id").asString();
    }
    private BigDecimal invoiced() {return jdbc.queryForObject("SELECT invoiced_amount FROM receivable WHERE id=?",BigDecimal.class,ar);}
    private String status(String id) {return jdbc.queryForObject("SELECT status FROM einv_issue_request WHERE id=?",String.class,id);}
    private void due(String id) {jdbc.update("UPDATE einv_issue_request SET next_attempt_at=UTC_TIMESTAMP(3) WHERE id=?",id);}
    private void assertCode(Runnable work,String code) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,problem->assertThat(problem.getErrorCode()).isEqualTo(code));
    }

    private final class Browser {
        private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        private final long company;

        private Browser(String name,String code,long company) throws Exception {
            this.company=company;
            var login=HttpRequest.newBuilder(uri("/auth/login")).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("tenantCode",code,"username",name,"password",password)))).build();
            var response=client.send(login,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        }

        private JsonNode send(String method,String path,Object body,int status) throws Exception {
            return send(method,path,body,status,UUID.randomUUID().toString());
        }

        private JsonNode send(String method,String path,Object body,int status,String key) throws Exception {
            var response=client.send(request(method,path,body,key),HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("%s %s %s",method,path,response.body()).isEqualTo(status);
            return json.readTree(response.body()).path(status>=400?"errorCode":"data");
        }

        private HttpResponse<byte[]> raw(String method,String path,Object body) throws Exception {
            return client.send(request(method,path,body,UUID.randomUUID().toString()),HttpResponse.BodyHandlers.ofByteArray());
        }

        private HttpRequest request(String method,String path,Object body,String key) {
            var builder=HttpRequest.newBuilder(uri("/e-invoices"+path)).header("X-Company-Id",Long.toString(company))
                    .header("Content-Type","application/json")
                    .method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if(!method.equals("GET"))builder.header("Idempotency-Key",key).header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream()
                    .filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
            return builder.build();
        }
        private URI uri(String path) {return URI.create("http://127.0.0.1:"+port+"/api/v1"+path);}
    }
}
