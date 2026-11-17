package cn.erp.pricing;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.net.*;
import java.net.http.*;
import java.util.List;
import java.util.ArrayList;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
class PricingHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private long tenant,company,user,role,partner,sku,unit;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();

    @BeforeEach
    void seed() {
        tenant=Ids.next();company=Ids.next();user=Ids.next();role=Ids.next();
        partner=Ids.next();sku=Ids.next();unit=Ids.next();
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"HTTP"+tenant,"试算接口测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"A","公司");
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'pricing','试算员',?)",user,tenant,passwords.encode("PricingTest!2026"));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'PRICING','试算角色')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='sales_order:create'",Ids.next(),tenant,role);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code='purchase_order:create'",Ids.next(),tenant,role);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,?,?)",partner,tenant,"C","客户");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,partner);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),tenant,partner);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,?)",unit,tenant,"件");
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,?,?,?)",category,tenant,"C","分类","/");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,?,?,?)",product,tenant,category,"P","商品",unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price) VALUES(?,?,?,?,?,115)",sku,tenant,product,"S","规格");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
    }

    @AfterEach
    void cleanup() {
        for(String table:List.of("price_list_target","price_list_item","price_list","crm_customer_profile","idempotency_record","document_sequence",
                "sales_order_fee","sales_order_line","sales_order","purchase_order_fee","purchase_order_line","purchase_order",
                "unit_conversion","sku","product","product_category","unit","partner_role","business_partner",
                "org_user_data_scope","sys_role_permission","sys_user_role","sys_role","sys_user","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @ParameterizedTest
    @CsvSource({"sales_order,true","sales_order,false","purchase_order,true","purchase_order,false"})
    void previewSnapshotPersistsHeaderLinesAndFeesForBothQuotationModes(String documentType,boolean inclusive) throws Exception {
        assertThat(post("/auth/login","{\"tenantCode\":\"HTTP"+tenant+"\",\"username\":\"pricing\",\"password\":\"PricingTest!2026\"}",false,company).statusCode()).isEqualTo(200);
        String request="""
                {"documentType":"%s","customerId":"%s","supplierId":"%s","priceTaxInclusive":%s,
                 "lines":[{"skuId":"%s","unitId":"%s","qty":"2","unitPrice":"10","lineDiscountRatio":"0.10"}],
                 "fees":[{"feeType":"freight","bearer":"buyer","amount":"30"}],"orderDiscountAmount":"0"}
                """.formatted(documentType,partner,partner,inclusive,sku,unit);
        var response=post("/pricing/preview",request,true,company);
        assertThat(response.statusCode()).isEqualTo(200);
        var snapshot=json.readTree(response.body()).path("data");
        var line=snapshot.path("lines").get(0);
        var fee=snapshot.path("fees").get(0);
        assertThat(snapshot.path("pricingVersion").asInt()).isEqualTo(2);
        assertThat(line.path("priceTaxInclusive").asBoolean()).isEqualTo(inclusive);
        assertThat(fee.path("priceTaxInclusive").asBoolean()).isEqualTo(inclusive);
        assertThat(line.path("amount").asText()).isEqualTo("20.00");
        assertThat(line.path("lineDiscountAmount").asText()).isEqualTo("2.00");
        assertThat(line.path("receivableNet").asText()).isEqualTo(inclusive?"18.00":"20.34");
        long order=Ids.next();
        boolean sales=documentType.equals("sales_order");
        String header=sales?"sales_order":"purchase_order";
        insert(header,"id,tenant_id,company_id,order_no,"+(sales?"customer_id":"supplier_id")+
                ",business_date,price_tax_inclusive,pricing_version,goods_amount,line_discount_amount,order_discount_amount,fee_amount,total_amount,untaxed_amount,tax_amount",
                List.of(order,tenant,company,"HTTP-"+order,partner,"2026-11-15",inclusive,snapshot.path("pricingVersion").asInt(),
                        snapshot.path("goodsAmount").asText(),snapshot.path("lineDiscountAmount").asText(),snapshot.path("orderDiscountAmount").asText(),
                        snapshot.path("feeAmount").asText(),snapshot.path("totalAmount").asText(),snapshot.path("untaxedAmount").asText(),snapshot.path("taxAmount").asText()));
        String columns="id,tenant_id,company_id,order_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,unit_price,price_tax_inclusive,"+
                "amount,line_discount,line_net,order_discount_alloc,"+(sales?"receivable_net":"payable_net")+",tax_rate,untaxed_amount,tax_amount";
        List<Object> values=new ArrayList<>(List.of(Ids.next(),tenant,company,order,line.path("lineNo").asInt(),sku,unit,
                line.path("unitRate").asText(),line.path("qty").asText(),line.path("baseQty").asText(),line.path("unitPrice").asText(),
                line.path("priceTaxInclusive").asBoolean(),line.path("amount").asText(),line.path("lineDiscountAmount").asText(),
                line.path("lineNet").asText(),line.path("orderDiscountAlloc").asText(),line.path("receivableNet").asText(),
                line.path("taxRate").asText(),line.path("untaxedAmount").asText(),line.path("taxAmount").asText()));
        if(!sales) {columns+=",receive_limit_base_qty";values.add(line.path("baseQty").asText());}
        insert(header+"_line",columns,values);
        insert(header+"_fee","id,tenant_id,company_id,order_id,fee_type,bearer,amount,price_tax_inclusive,tax_rate,untaxed_amount,tax_amount",
                List.of(Ids.next(),tenant,company,order,fee.path("feeType").asText(),fee.path("bearer").asText(),fee.path("amount").asText(),
                        fee.path("priceTaxInclusive").asBoolean(),fee.path("taxRate").asText(),fee.path("untaxedAmount").asText(),fee.path("taxAmount").asText()));
        assertThat(jdbc.queryForObject("SELECT total_amount FROM "+header+" WHERE id=?",java.math.BigDecimal.class,order))
                .isEqualByComparingTo(snapshot.path("totalAmount").asText());
        assertThat(jdbc.queryForObject("SELECT total_amount FROM "+header+"_fee WHERE order_id=?",java.math.BigDecimal.class,order))
                .isEqualByComparingTo(fee.path("totalAmount").asText());
        assertThat(jdbc.queryForObject("SELECT "+(sales?"receivable_net":"payable_net")+" FROM "+header+"_line WHERE order_id=?",java.math.BigDecimal.class,order))
                .isEqualByComparingTo(line.path("totalAmount").asText());
    }

    @Test
    void legacyExclusiveHeaderKeepsItsOriginalAmountContract() {
        long order=Ids.next();
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,
                  goods_amount,total_amount,untaxed_amount,tax_amount) VALUES(?,?,?,?,?,'2026-11-15',0,100,100,90,10)
                """,order,tenant,company,"OLD-"+order,partner);
        assertThat(jdbc.queryForObject("SELECT pricing_version FROM sales_order WHERE id=?",Integer.class,order)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total_amount FROM sales_order WHERE id=?",java.math.BigDecimal.class,order)).isEqualByComparingTo("100");
    }

    private void insert(String table,String columns,List<Object> values) {
        String placeholders=String.join(",",java.util.Collections.nCopies(values.size(),"?"));
        jdbc.update("INSERT INTO "+table+"("+columns+") VALUES("+placeholders+")",values.toArray());
    }

    @Test
    void actualHttpPreviewIsAuthenticatedCsrfProtectedAndUsesStringAmounts() throws Exception {
        var login=post("/auth/login","{\"tenantCode\":\"HTTP"+tenant+"\",\"username\":\"pricing\",\"password\":\"PricingTest!2026\"}",false,company);
        assertThat(login.statusCode()).isEqualTo(200);
        String request="""
                {"documentType":"sales_order","customerId":"%s","lines":[
                  {"skuId":"%s","unitId":"%s","qty":"4","unitPrice":"115","lineDiscountAmount":"60"}],
                 "fees":[],"orderDiscountAmount":"0","totalAmount":"0"}
                """.formatted(partner,sku,unit);
        var noCsrf=post("/pricing/preview",request,false,company);
        assertThat(noCsrf.statusCode()).isEqualTo(403);
        var response=post("/pricing/preview",request,true,company);
        assertThat(response.statusCode()).isEqualTo(200);
        var body=json.readTree(response.body());
        assertThat(body.path("data").path("totalAmount").asText()).isEqualTo("400.00");
        assertThat(body.path("data").path("totalAmount").isTextual()).isTrue();
        assertThat(body.path("data").path("lines").get(0).path("taxAmount").asText()).isEqualTo("46.02");
        assertThat(post("/pricing/preview",request.replace("\"qty\":\"4\"","\"qty\":\"-4\""),true,company).statusCode()).isEqualTo(400);
        assertThat(post("/pricing/preview",request,true,Ids.next()).statusCode()).isEqualTo(403);
        String changedTax=request.replace("\"qty\":\"4\"","\"qty\":\"4\",\"taxRate\":\"0.09\"");
        var denied=post("/pricing/preview",changedTax,true,company);
        assertThat(denied.statusCode()).isEqualTo(422);
        assertThat(json.readTree(denied.body()).path("errorCode").asText()).isEqualTo("TAX_RATE_CHANGE_FORBIDDEN");
    }

    @Test
    void priceListsUseCompanyCustomerTiersAndPreserveTheOrderSnapshot() throws Exception {
        for (String permission: List.of("price_list:view","price_list:manage")) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",
                    Ids.next(),tenant,role,permission);
        }
        jdbc.update("INSERT INTO crm_customer_profile(tenant_id,company_id,partner_id,owner_user_id,level) VALUES(?,?,?,?,'gold')",
                tenant,company,partner,user);
        long other=Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','第二公司')",other,tenant);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,other);
        assertThat(post("/auth/login","{\"tenantCode\":\"HTTP"+tenant+"\",\"username\":\"pricing\",\"password\":\"PricingTest!2026\"}",false,company).statusCode()).isEqualTo(200);
        var any=post("/price-lists",priceList("通用价","all","","109",90,false),true,company);
        var level=post("/price-lists",priceList("级别价","customer_level","gold","88",50,false),true,company);
        var agreement=post("/price-lists",priceList("客户协议","customer",Long.toString(partner),"75",1,true),true,company);
        var higher=post("/price-lists",priceList("高优先协议","customer",Long.toString(partner),"70",3,true),true,company);
        for (var result:List.of(any,level,agreement,higher)) assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        String contractId=json.readTree(higher.body()).path("data").path("header").path("id").asText();
        assertThat(http("GET","/price-lists/"+contractId,null,false,other).statusCode()).isEqualTo(404);
        String quote="/pricing/default-price?partnerId="+partner+"&skuId="+sku+"&unitId="+unit+"&qty=11&businessDate=";
        assertThat(http("GET",quote+"2026-11-15",null,false,company).body()).contains("69.00000000","customer");
        assertThat(http("GET",quote+"2026-11-15&priceTaxInclusive=false",null,false,company).body()).contains("61.06194690");
        assertThat(http("GET",quote+"2025-11-15",null,false,company).body()).contains("115.00000000","reference");
        assertThat(http("GET",quote+"2026-11-15",null,false,other).body()).contains("115.00000000","reference");
        String orderBody="""
                {"customerId":"%s","businessDate":"2026-11-15","priceTaxInclusive":true,
                 "orderDiscountAmount":"0","lines":[{"skuId":"%s","unitId":"%s","qty":"11"}],"fees":[]}
                """.formatted(partner,sku,unit);
        var order=post("/sales-orders",orderBody,true,company);
        assertThat(order.statusCode()).as(order.body()).isEqualTo(201);
        String orderId=json.readTree(order.body()).path("data").path("id").asText();
        String disable=priceList("高优先协议","customer",Long.toString(partner),"70",3,true).replace("\"active\"","\"disabled\"");
        assertThat(http("PUT","/price-lists/"+contractId,disable,true,company).statusCode()).isEqualTo(200);
        assertThat(http("PUT","/price-lists/"+contractId,disable,true,company).statusCode()).isEqualTo(409);
        assertThat(http("GET",quote+"2026-11-15",null,false,company).body()).contains("74.00000000");
        assertThat(jdbc.queryForObject("SELECT unit_price FROM sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=?",
                java.math.BigDecimal.class,tenant,company,orderId)).isEqualByComparingTo("69");
    }

    private String priceList(String name,String type,String value,String price,int priority,boolean includesTax) {
        return """
                {"name":"%s","priceIncludesTax":%s,"validFrom":"2026-01-01","validTo":"2026-12-31",
                 "priority":%d,"status":"active","version":0,
                 "items":[{"skuId":"%s","unitId":"%s","minQty":"0","unitPrice":"%s"},
                          {"skuId":"%s","unitId":"%s","minQty":"10","unitPrice":"%s"}],
                 "targets":[{"targetType":"%s","targetValue":"%s"}]}
                """.formatted(name,includesTax,priority,sku,unit,price,sku,unit,new java.math.BigDecimal(price).subtract(java.math.BigDecimal.ONE).toPlainString(),type,value);
    }

    private HttpResponse<String> post(String path,String body,boolean csrf,long company) throws Exception {
        return http("POST",path,body,csrf,company);
    }

    private HttpResponse<String> http(String method,String path,String body,boolean csrf,long company) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path))
                .header("Content-Type","application/json").header("X-Company-Id",Long.toString(company));
        if ("POST".equals(method)&&"/sales-orders".equals(path)) builder.header("Idempotency-Key","crm-price-test-"+tenant);
        if(csrf) {
            String token=cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow();
            builder.header("X-XSRF-TOKEN",token);
        }
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
