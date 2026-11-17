package cn.erp.opening;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SupplyPermissionsHttpIT extends OpeningTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    private long income;

    @AfterEach void cleanupSupply(){
        jdbc.update("DELETE FROM stock_misc_line WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM stock_misc_doc WHERE tenant_id=?",tenant);
        if(income!=0)jdbc.update("DELETE FROM income_expense_item WHERE tenant_id=? AND id=?",tenant,income);
    }

    @Test void warehouseAndPermissionsApplyToDraftAndQualityEndpoints() throws Exception {
        assertThat(http("GET","/qc/inspections",null,null,company).statusCode()).isEqualTo(401);
        var login=http("POST","/auth/login","{\"tenantCode\":\"OPENING"+tenant+"\",\"username\":\"operator\",\"password\":\""+PASSWORD+"\"}",null,company);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(http("GET","/qc/inspections",null,null,company).statusCode()).isEqualTo(200);
        revoke("qc_inspection:view");
        assertThat(http("GET","/qc/inspections",null,null,company).statusCode()).isEqualTo(403);
        assertThat(http("POST","/qc/items","{\"code\":\"CHK\",\"name\":\"外观\",\"type\":\"qualitative\"}",key(),company).statusCode()).isEqualTo(403);
        income=Ids.next();
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(?,?,'income','MISC','赠品')",income,tenant);
        String body="""
                {"direction":"in","purpose":"gift","warehouseId":"%s","incomeExpenseItemId":"%s","businessDate":"%s",
                 "lines":[{"skuId":"%s","unitId":"%s","qty":"1","inputUnitCost":"5"}]}
                """.formatted(warehouse,income,DAY,sku,unit);
        var created=http("POST","/stock-misc-docs",body,key(),company);
        assertThat(created.statusCode()).isEqualTo(201);
        String path="/stock-misc-docs/"+json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("GET",path,null,null,company).statusCode()).isEqualTo(200);
        assertThat(http("GET",path,null,null,otherCompany).statusCode()).isEqualTo(404);
        revoke("cost:view");
        assertThat(http("GET",path,null,null,company).body()).doesNotContain("inputUnitCost","unitCost","amount");
        revoke("stock_misc_doc:submit");
        assertThat(http("POST",path+"/submit","{\"version\":0}",key(),company).statusCode()).isEqualTo(403);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?",tenant,user,company,warehouse);
        assertThat(http("GET",path,null,null,company).statusCode()).isEqualTo(404);
    }

    private void revoke(String permission){
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code=?)",tenant,role,permission);
    }

    private HttpResponse<String> http(String method,String path,String body,String key,long targetCompany) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(targetCompany));
        if(body!=null)builder.header("Content-Type","application/json");
        if(key!=null){
            builder.header("Idempotency-Key",key);
            builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        }
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
