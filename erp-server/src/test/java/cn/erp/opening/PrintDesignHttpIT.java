package cn.erp.opening;

import cn.erp.ErpApplication;
import cn.erp.documents.ObjectCompensation;
import cn.erp.documents.ObjectFiles;
import cn.erp.exporting.ExportAccess;
import cn.erp.exporting.ExportJobs;
import cn.erp.exporting.ExportWorker;
import cn.erp.reporting.ReportService;
import cn.erp.shared.DocumentPrintExportPort;
import cn.erp.shared.Ids;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实单据、会话和导出队列复核模板、PDF 和撤权。 */
@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PrintDesignHttpIT extends OpeningTestData {
    private static final String LAYOUT="""
            {"paper":{"kind":"third","orientation":"portrait","margins":{"top":5,"right":5,"bottom":5,"left":5}},"locale":"en-US",
             "header":[{"type":"field","x":0,"y":0,"w":150,"h":9,"field":"documentNo","labelEn":"Shipment "},
                       {"type":"field","x":0,"y":10,"w":120,"h":9,"field":"costAmount","labelEn":"Cost "}],
             "footer":[{"type":"qrcode","x":0,"y":43,"w":20,"h":20,"field":"documentNo"},
                       {"type":"pageNumber","x":30,"y":55,"w":60,"h":12}],
             "table":{"x":0,"y":22,"rowHeight":5,"rowsPerPage":2,"repeatHeader":true,"showTotalRow":true,
                       "columns":[{"field":"lines.number","title":"行号","titleEn":"No.","w":25},
                                  {"field":"lines.skuCode","title":"编码","titleEn":"SKU","w":55},
                                  {"field":"lines.qty","title":"数量","titleEn":"Qty","w":25,"total":true}]}}
            """;
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ExportJobs jobs;
    @Autowired ExportAccess access;
    @Autowired ReportService reports;
    @Autowired ObjectFiles files;
    @Autowired ObjectCompensation compensation;
    @Autowired DocumentPrintExportPort printing;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();

    @AfterEach void cleanupPrinting() {
        for(String path:jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?",String.class,tenant)) files.delete(path);
        jdbc.update("DELETE FROM object_cleanup WHERE storage_key LIKE ?",tenant+"/%");
        for(String table:List.of("print_log","print_template","file_ticket","export_job","attachment","shipment_line","shipment","sales_order_line","sales_order","payment"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
    }

    @Test void threePartShipmentPreviewAndQueuedPdfShareContentsAndRecheckCurrentAccess() throws Exception {
        fullCompany();login();
        long first=shipment("SHIP-TEST-001"), second=shipment("SHIP-TEST-002");
        addLines(first);
        var template=http("POST","/print-templates",company,template(false,LAYOUT),null,true);
        assertThat(template.statusCode()).as(template.body()).isEqualTo(200);
        String id=json.readTree(template.body()).path("data").path("id").asText();
        assertThat(http("GET","/print-templates/"+id,otherCompany,null,null,false).statusCode()).isEqualTo(404);
        assertThat(http("GET","/print-templates/output/shipment/"+first,otherCompany,null,null,false).statusCode()).isEqualTo(404);
        var preview=http("GET","/print-templates/output/shipment/"+first,company,null,null,false);
        var model=json.readTree(preview.body()).path("data").path("model");
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(model.path("width").asDouble()).isEqualTo(241);
        assertThat(model.path("height").asDouble()).isEqualTo(93);
        assertThat(model.path("pages").size()).isEqualTo(2);
        assertThat(model.toString()).contains("Shipment SHIP-TEST-001","Cost 98,765.43","data:image/png;base64,","Page 1 / 2","SKU","5.25");
        assertThat(http("GET","/print-templates/documents/shipment?keyword=SHIP-TEST-001",company,null,null,false).body())
                .contains("SHIP-TEST-001").doesNotContain("SHIP-TEST-002");
        assertThat(http("POST","/print-templates/output/shipment/"+first+"/log",company,
                "{\"templateId\":\""+id+"\",\"version\":0,\"copies\":1}",key(),true).statusCode()).isEqualTo(200);
        String payload="{\"exportType\":\"print-pdf\",\"financeQuery\":{\"documentType\":\"shipment\",\"templateId\":\""+id
                +"\",\"documentIds\":[\""+first+"\",\""+second+"\"],\"copies\":2}}";
        var created=http("POST","/export-jobs",company,payload,key(),true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(202);
        String job=json.readTree(created.body()).path("data").path("taskId").asText();
        var worker=new ExportWorker(jobs,access,reports,files,json,compensation);
        ReflectionTestUtils.invokeMethod(worker,"printing",printing);
        assertThat(worker.runOne(tenant)).isTrue();
        var metadata=http("GET","/export-jobs/"+job,company,null,null,false);
        assertThat(metadata.body()).contains("succeeded");
        var ticket=http("POST","/export-jobs/"+job+"/download-ticket",company,"{}",null,true);
        assertThat(ticket.statusCode()).as(ticket.body()).isEqualTo(200);
        String url=json.readTree(ticket.body()).path("data").path("url").asText();
        var pdf=fetch(url,company,HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        try(var document=Loader.loadPDF(pdf.body())) {
            assertThat(document.getNumberOfPages()).isEqualTo(6);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isCloseTo(241*72f/25.4f,org.assertj.core.data.Offset.offset(.01f));
            assertThat(new PDFTextStripper().getText(document)).contains("Shipment SHIP-TEST-001","Shipment SHIP-TEST-002","Cost 98,765.43","Page 1 / 2","5.25");
        }
        assertThat(count("print_log")).isEqualTo(3);
        revoke("cost:view");
        assertThat(fetch(url,company,HttpResponse.BodyHandlers.ofByteArray()).statusCode()).isEqualTo(403);
        assertThat(http("GET","/export-jobs/"+job,company,null,null,false).statusCode()).isEqualTo(403);
        var masked=http("GET","/print-templates/output/shipment/"+first,company,null,null,false);
        assertThat(masked.body()).contains("Shipment SHIP-TEST-001").doesNotContain("98,765.43","Cost ");
        revoke("shipment:print");
        assertThat(http("GET","/print-templates/output/shipment/"+first,company,null,null,false).statusCode()).isEqualTo(403);
    }

    @Test void tenantSharedTemplateRequiresAllCompaniesAndCannotPreviewForeignCompanyDocument() throws Exception {
        long doc=shipment("PRINT-SCOPE");login();
        assertThat(http("POST","/print-templates",company,template(true,LAYOUT),null,true).statusCode()).isEqualTo(403);
        fullCompany();
        var saved=http("POST","/print-templates",company,template(true,LAYOUT),null,true);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        String id=json.readTree(saved.body()).path("data").path("id").asText();
        assertThat(http("GET","/print-templates/"+id,otherCompany,null,null,false).statusCode()).isEqualTo(200);
        assertThat(http("GET","/print-templates/output/shipment/"+doc+"?templateId="+id,otherCompany,null,null,false).statusCode()).isEqualTo(404);
        assertThat(http("PUT","/print-templates/"+id,company,template(true,LAYOUT),null,true).statusCode()).isEqualTo(409);
        String unapproved=LAYOUT.replace("costAmount","internalToken");
        assertThat(http("POST","/print-templates",company,template(false,unapproved),null,true).statusCode()).isEqualTo(400);
        String escaped=LAYOUT.replace("\"x\":0,\"y\":0","\"x\":250,\"y\":0");
        assertThat(http("POST","/print-templates",company,template(false,escaped),null,true).statusCode()).isEqualTo(400);
        long foreignTenant=Ids.next(), foreignTemplate=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'打印隔离验收')",foreignTenant,"PRINT"+foreignTenant);
        try {
            jdbc.update("INSERT INTO print_template(id,tenant_id,document_type,name,layout_json,status,created_by,updated_by) VALUES(?,?,'shipment','隔离模板',?,'active',?,?)",
                    foreignTemplate,foreignTenant,LAYOUT,user,user);
            assertThat(http("GET","/print-templates/"+foreignTemplate,company,null,null,false).statusCode()).isEqualTo(404);
        } finally {
            jdbc.update("DELETE FROM print_template WHERE tenant_id=?",foreignTenant);jdbc.update("DELETE FROM tenant WHERE id=?",foreignTenant);
        }
        var legacy=http("GET","/print-data/shipment/"+doc,company,null,null,false);
        assertThat(legacy.statusCode()).isEqualTo(200);
    }

    @Test void bankAccountFieldIsBlankImmediatelyAfterPermissionIsRevoked() throws Exception {
        fullCompany();login();
        long payment=Ids.next();
        jdbc.update("UPDATE cash_account SET account_no='1000000000091' WHERE tenant_id=? AND company_id=? AND id=?",tenant,company,account);
        jdbc.update("""
                INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,amount,business_date,created_by)
                VALUES(?,?,?,'PAY-PRINT','supplier',?,?,10,'2026-11-01',?)
                """,payment,tenant,company,partner,account,user);
        String layout="""
                {"paper":{"kind":"A4","orientation":"portrait","margins":{"top":5,"right":5,"bottom":5,"left":5}},"locale":"zh-CN",
                 "header":[{"type":"field","x":0,"y":0,"w":140,"h":9,"field":"accountNo","label":"银行账号："}],"footer":[]}
                """;
        var saved=http("POST","/print-templates",company,template(false,layout).replace("\"shipment\"","\"payment\""),null,true);
        assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
        assertThat(http("GET","/print-templates/output/payment/"+payment,company,null,null,false).body()).contains("1000000000091");
        revoke("cash_account_no:view");
        assertThat(http("GET","/print-templates/output/payment/"+payment,company,null,null,false).body())
                .doesNotContain("1000000000091","银行账号");
    }

    private String template(boolean shared,String layout) {
        return "{\"documentType\":\"shipment\",\"name\":\"三等分发货校验\",\"shared\":"+shared+",\"status\":\"active\",\"isDefault\":true,\"layout\":"+layout+"}";
    }

    private void fullCompany(){jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);}
    private long shipment(String number) {
        long id=Ids.next();
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,created_by,cost_amount) VALUES(?,?,?,?,?,?,'2026-11-01',?,98765.43)",id,tenant,company,number,partner,warehouse,user);
        return id;
    }
    private void addLines(long shipment) {
        long order=Ids.next();
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,created_by)
                VALUES(?,?,?,'PRINT-ORDER',?,'2026-11-01',1,?)
                """,order,tenant,company,partner,user);
        for(int index=1;index<=3;index++) {
            long line=Ids.next();
            jdbc.update("""
                    INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,
                      base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount)
                    VALUES(?,?,?,?,?,?,?,?,1,10,10,10,100,100,100,0,100,0)
                    """,line,tenant,company,order,index,sku,warehouse,unit);
            jdbc.update("""
                    INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty)
                    VALUES(?,?,?,?,?,?,?,?,1,1.75,1.75)
                    """,Ids.next(),tenant,company,shipment,index,line,sku,unit);
        }
    }
    private void revoke(String grant) {
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",tenant,role,grant);
    }
    private void login() throws Exception {
        assertThat(http("POST","/auth/login",company,"{\"tenantCode\":\"OPENING"+tenant+"\",\"username\":\"operator\",\"password\":\""+PASSWORD+"\"}",null,false).statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> http(String method,String path,long selected,String body,String key,boolean csrf) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(selected));
        if(body!=null) request.header("Content-Type","application/json");
        if(key!=null) request.header("Idempotency-Key",key);
        if(csrf) request.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private <T> HttpResponse<T> fetch(String path,long selected,HttpResponse.BodyHandler<T> body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("X-Company-Id",Long.toString(selected)).GET().build(),body);
    }
}
