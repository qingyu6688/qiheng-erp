package cn.erp.salesfinance;

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
class M4HttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private long tenant,company,secondCompany,user,role,customer,account,warehouse,otherWarehouse,order,orderLine,unit,sku;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();secondCompany=Ids.next();user=Ids.next();role=Ids.next();customer=Ids.next();
        account=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();order=Ids.next();orderLine=Ids.next();unit=Ids.next();
        long category=Ids.next(),product=Ids.next();sku=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"M4HTTP"+tenant,"M4接口隔离测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'公司甲')",company,tenant,"A");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'公司乙')",secondCompany,tenant,"B");
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'operator','经办员',?)",user,tenant,passwords.encode("M4Test!2026"));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'M4','销售操作员')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for(String permission:List.of("receipt:view","receipt:create","receipt:confirm","shipment:view","shipment:create")) {grant(permission);}
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",warehouse,tenant,company,"W1","发货仓");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",otherWarehouse,tenant,company,"W2","备用仓");
        for(long wh:List.of(warehouse,otherWarehouse)) {
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,user,company,wh);
        }
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,secondCompany);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','客户')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'BANK','结算账户','bank')",account,tenant,company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'S','规格')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,status,revision,approved_at)
                VALUES(?,?,?,'SO-HTTP',?,'2026-11-01',1,'approved',1,UTC_TIMESTAMP(3))
                """,order,tenant,company,customer);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,
                amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,1,1,0,0,0,0,0,0,0)
                """,orderLine,tenant,company,order,sku,warehouse,unit);
    }

    @AfterEach void cleanup() {
        for(String table:List.of("idempotency_record","audit_log","receipt","shipment_line","shipment","sales_order_line","sales_order","document_sequence",
                "cash_account_balance","cash_account","accounting_period","unit_conversion","sku","product","product_category","unit","partner_role",
                "business_partner","sys_user_data_scope","sys_role_permission","sys_user_role","sys_role","sys_user","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void financeHttpRequiresAuthenticationCsrfPermissionsCompanyAndLegalState() throws Exception {
        assertThat(http("GET","/receipts",null,null,false,company).statusCode()).isEqualTo(401);
        login();
        assertThat(http("POST","/receipts",receiptBody(),"NO-CSRF",false,company).statusCode()).isEqualTo(403);
        String key=key();
        var created=http("POST","/receipts",receiptBody(),key,true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("GET","/receipts/"+id,null,null,false,secondCompany).statusCode()).isEqualTo(404);
        var invalid=http("POST","/receipts/"+id+"/confirm","{\"version\":0}",key(),true,company);
        assertThat(invalid.statusCode()).isEqualTo(409);
        assertThat(json.readTree(invalid.body()).path("errorCode").asText()).isEqualTo("STATE_CONFLICT");
        var result=http("GET","/command-results?operation=receipt.create&key="+key,null,null,false,company);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(json.readTree(result.body()).path("data").path("response").path("id").asText()).isEqualTo(id);
        revoke("receipt:create");
        assertThat(http("GET","/command-results?operation=receipt.create&key="+key,null,null,false,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/receipts",receiptBody(),key(),true,company).statusCode()).isEqualTo(403);
        assertThat(http("GET","/receipts/"+id,null,null,false,company).statusCode()).isEqualTo(200);
    }

    @Test void revokedWarehouseCannotReadCachedShipmentResponseOrReplayOriginalCommand() throws Exception {
        login();
        String key=key(),body="""
                {"customerId":"%s","warehouseId":"%s","businessDate":"2026-11-01","deliveryMethod":"delivery",
                 "lines":[{"orderLineId":"%s","unitId":"%s","qty":"1"}]}
                """.formatted(customer,warehouse,orderLine,unit);
        var created=http("POST","/shipments",body,key,true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(created.body()).doesNotContain("unitCost","costAmount");
        String resultPath="/command-results?operation=shipment.create&key="+key;
        assertThat(http("GET",resultPath,null,null,false,company).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?",tenant,user,company,warehouse);
        assertThat(http("GET","/shipments/"+id,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("GET",resultPath,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("POST","/shipments",body,key,true,company).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment WHERE tenant_id=?",Long.class,tenant)).isEqualTo(1);
    }

    @Test void B06CreateAndEditIgnoreForgedComputedAmountsAndPersistServerCalculation() throws Exception {
        grant("sales_order:create");grant("sales_order:update");grant("sales_order:view");login();
        var created=http("POST","/sales-orders",forgedOrderBody("4","60",null),key(),true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        var first=json.readTree(created.body()).path("data");
        long id=first.path("id").asLong();
        assertThat(first.path("totalAmount").asText()).isEqualTo("400.00");
        assertThat(first.path("goodsAmount").asText()).isEqualTo("460.00");
        assertThat(first.path("taxAmount").asText()).isEqualTo("46.02");
        assertThat(first.path("lines").get(0).path("amount").asText()).isEqualTo("460.00");
        assertThat(first.path("lines").get(0).path("receivableNet").asText()).isEqualTo("400.00");
        assertStoredCalculation(id,"460","400","46.02");
        var edited=http("PUT","/sales-orders/"+id,forgedOrderBody("2","30",first.path("version").asLong()),null,true,company);
        assertThat(edited.statusCode()).isEqualTo(200);
        var second=json.readTree(edited.body()).path("data");
        assertThat(second.path("totalAmount").asText()).isEqualTo("200.00");
        assertThat(second.path("goodsAmount").asText()).isEqualTo("230.00");
        assertThat(second.path("taxAmount").asText()).isEqualTo("23.01");
        assertThat(second.path("lines").get(0).path("amount").asText()).isEqualTo("230.00");
        assertThat(second.path("lines").get(0).path("receivableNet").asText()).isEqualTo("200.00");
        assertStoredCalculation(id,"230","200","23.01");
    }

    private String forgedOrderBody(String qty,String discount,Long version) {
        return """
                {"customerId":"%s","businessDate":"2026-11-01","priceTaxInclusive":true,"orderDiscountAmount":"0",
                 "totalAmount":"0","goodsAmount":"0","taxAmount":"0","untaxedAmount":"0","version":%s,
                 "lines":[{"skuId":"%s","warehouseId":"%s","unitId":"%s","qty":"%s","unitPrice":"115","lineDiscountAmount":"%s",
                 "amount":"0","lineNet":"0","receivableNet":"0","taxAmount":"0","untaxedAmount":"0","orderDiscountAlloc":"999"}],"fees":[]}
                """.formatted(customer,version,sku,warehouse,unit,qty,discount);
    }

    private void assertStoredCalculation(long orderId,String goods,String total,String tax) {
        var header=jdbc.queryForMap("SELECT goods_amount,total_amount,tax_amount,untaxed_amount FROM sales_order WHERE id=?",orderId);
        var line=jdbc.queryForMap("SELECT amount,receivable_net,tax_amount,untaxed_amount,order_discount_alloc FROM sales_order_line WHERE order_id=? AND is_deleted=0",orderId);
        assertThat((java.math.BigDecimal)header.get("goods_amount")).isEqualByComparingTo(goods);
        assertThat((java.math.BigDecimal)header.get("total_amount")).isEqualByComparingTo(total);
        assertThat((java.math.BigDecimal)header.get("tax_amount")).isEqualByComparingTo(tax);
        assertThat((java.math.BigDecimal)line.get("amount")).isEqualByComparingTo(goods);
        assertThat((java.math.BigDecimal)line.get("receivable_net")).isEqualByComparingTo(total);
        assertThat((java.math.BigDecimal)line.get("tax_amount")).isEqualByComparingTo(tax);
        assertThat((java.math.BigDecimal)line.get("order_discount_alloc")).isZero();
        assertThat(((java.math.BigDecimal)line.get("untaxed_amount")).add((java.math.BigDecimal)line.get("tax_amount"))).isEqualByComparingTo(total);
        assertThat(((java.math.BigDecimal)header.get("untaxed_amount")).add((java.math.BigDecimal)header.get("tax_amount"))).isEqualByComparingTo(total);
    }

    private void login() throws Exception {
        String body="{\"tenantCode\":\"M4HTTP"+tenant+"\",\"username\":\"operator\",\"password\":\"M4Test!2026\"}";
        assertThat(http("POST","/auth/login",body,null,false,company).statusCode()).isEqualTo(200);
    }
    private String receiptBody() {
        return "{\"receiptType\":\"customer\",\"partnerId\":\""+customer+"\",\"cashAccountId\":\""+account+"\",\"amount\":\"10.00\",\"businessDate\":\"2026-11-01\"}";
    }
    private void grant(String permission) {
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,permission);
    }
    private void revoke(String permission) {
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",tenant,role,permission);
    }
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long companyId) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(companyId));
        if(body!=null) {builder.header("Content-Type","application/json");}
        if(key!=null) {builder.header("Idempotency-Key",key);}
        if(csrf) {
            String token=cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
            builder.header("X-XSRF-TOKEN",token);
        }
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String key() {return UUID.randomUUID().toString();}
}
