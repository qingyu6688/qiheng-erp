package cn.erp.opening;

import cn.erp.ErpApplication;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class StockCountHttpIT extends OpeningTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();

    @Test void countCreationRecordingAndHistoricalResultsEnforceCurrentPermissionsAndWarehouse() throws Exception {
        assertThat(http("GET","/stock-counts",null,null,false).statusCode()).isEqualTo(401);login();
        assertThat(http("POST","/stock-counts",document(),key(),false).statusCode()).isEqualTo(403);
        String creation=key();var created=http("POST","/stock-counts",document(),creation,true);assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("POST","/stock-counts/"+id+"/start","{\"version\":0}",key(),true).statusCode()).isEqualTo(200);
        var details=json.readTree(http("GET","/stock-counts/"+id,null,null,false).body()).path("data");
        String line=details.path("lines").get(0).path("id").asText();
        String body="{\"version\":1,\"lines\":[{\"lineId\":\""+line+"\",\"countedQty\":\"2\",\"inputUnitCost\":\"15\"}]}";
        assertThat(http("PUT","/stock-counts/"+id+"/counts",body,null,true).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='cost:view')",tenant,role);
        assertThat(http("GET","/stock-counts/"+id,null,null,false).body()).doesNotContain("inputUnitCost","unitCost","costAmount");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_count:record_counts')",tenant,role);
        assertThat(http("PUT","/stock-counts/"+id+"/counts",body,null,true).statusCode()).isEqualTo(403);
        String result="/command-results?operation=stock_count.create&key="+creation;
        assertThat(http("GET",result,null,null,false).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?",tenant,user,company,warehouse);
        assertThat(http("GET","/stock-counts/"+id,null,null,false).statusCode()).isEqualTo(404);
        assertThat(http("GET",result,null,null,false).statusCode()).isEqualTo(404);
        assertThat(http("GET","/stock-counts?limit=201",null,null,false).statusCode()).isEqualTo(400);
    }

    @Test void adjustmentHttpAcceptsCostlessFreezeAndRejectsTypeChanges() throws Exception {
        login();String body="{\"adjustType\":\"block\",\"warehouseId\":\""+warehouse+"\",\"businessDate\":\"2026-11-01\",\"reason\":\"冻结待检\",\"lines\":[{\"skuId\":\""+sku+"\",\"unitId\":\""+unit+"\",\"qty\":\"1\"}]}";
        var created=http("POST","/stock-adjustments",body,key(),true);assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("GET","/stock-adjustments?adjustType=block",null,null,false).body()).contains(id);
        String changed=body.replace("\"block\"","\"loss\"").replace("\"reason\":","\"version\":0,\"reason\":");
        assertThat(http("PUT","/stock-adjustments/"+id,changed,null,true).statusCode()).isEqualTo(400);
        assertThat(http("GET","/stock-adjustments/"+id,null,null,false).body()).contains("\"adjustType\":\"block\"");
        assertThat(count("inventory_movement")).isZero();assertThat(count("journal")).isZero();assertZero();
    }

    private String document(){return "{\"warehouseId\":\""+warehouse+"\",\"businessDate\":\"2026-11-01\",\"countMode\":\"watermark\",\"lines\":[{\"skuId\":\""+sku+"\"}]}";}
    private void login() throws Exception {
        cookies.getCookieStore().removeAll();
        assertThat(http("POST","/auth/login","{\"tenantCode\":\"OPENING"+tenant+"\",\"username\":\"operator\",\"password\":\""+PASSWORD+"\"}",null,false).statusCode()).isEqualTo(200);
    }
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));
        if(body!=null)builder.header("Content-Type","application/json");if(key!=null)builder.header("Idempotency-Key",key);
        if(csrf)builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
