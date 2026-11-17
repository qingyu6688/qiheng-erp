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
class OpeningHttpIT extends OpeningTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();

    @Test void stockOpeningEnforcesCurrentPermissionWarehouseCompanyAndCostVisibility() throws Exception {
        assertThat(http("GET","/stock-adjustments",null,null,false,company).statusCode()).isEqualTo(401);
        login("operator");
        assertThat(http("POST","/stock-adjustments",stockBody(),key(),false,company).statusCode()).isEqualTo(403);
        String key=key();var created=http("POST","/stock-adjustments",stockBody(),key,true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(created.body()).doesNotContain("inputTotalCost","unitCost","costAmount");
        var full=http("GET","/stock-adjustments/"+id,null,null,false,company);
        assertThat(json.readTree(full.body()).path("data").path("lines").get(0).path("inputTotalCost").asText()).isEqualTo("50.00");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='cost:view')",tenant,role);
        var hidden=http("GET","/stock-adjustments/"+id,null,null,false,company);
        assertThat(hidden.statusCode()).isEqualTo(200);assertThat(hidden.body()).doesNotContain("inputTotalCost","unitCost","costAmount");
        String result="/command-results?operation=stock_adjustment.create&key="+key;
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(200);
        assertThat(http("GET","/stock-adjustments/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        jdbc.update("DELETE FROM org_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?",tenant,user,company,warehouse);
        assertThat(http("GET","/stock-adjustments/"+id,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(404);
        assertThat(http("POST","/stock-adjustments",stockBody(),key,true,company).statusCode()).isEqualTo(404);
        assertThat(count("stock_adjustment")).isEqualTo(1);assertThat(count("inventory_movement")).isZero();
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_adjustment:create')",tenant,role);
        assertThat(http("GET",result,null,null,false,company).statusCode()).isEqualTo(403);
    }

    @Test void openingEntryRequiresAnotherReviewerAndPostingRetryCreatesOneCashFact() throws Exception {
        login("operator");
        var created=http("POST","/opening-entries",cashBody(),key(),true,company);
        assertThat(created.statusCode()).isEqualTo(201);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        assertThat(http("POST","/opening-entries/"+id+"/post","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(http("POST","/opening-entries/"+id+"/approve","{\"version\":1}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(http("POST","/opening-entries/"+id+"/approve","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(422);
        assertThat(cash()).isEqualByComparingTo("0");
        login("reviewer");
        assertThat(http("POST","/opening-entries/"+id+"/approve","{\"version\":0}",key(),true,company).statusCode()).isEqualTo(200);
        String key=key();
        assertThat(http("POST","/opening-entries/"+id+"/post","{\"version\":1}",key,true,company).statusCode()).isEqualTo(200);
        assertThat(http("POST","/opening-entries/"+id+"/post","{\"version\":1}",key,true,company).statusCode()).isEqualTo(200);
        assertThat(http("POST","/opening-entries/"+id+"/post","{\"version\":2}",key(),true,company).statusCode()).isEqualTo(409);
        assertThat(cash()).isEqualByComparingTo("100");assertThat(count("journal")).isEqualTo(1);
        assertThat(count("partner_open_item")).isZero();
        assertThat(http("GET","/opening-entries/"+id,null,null,false,otherCompany).statusCode()).isEqualTo(404);
        assertZero();
    }

    private void login(String user) throws Exception {
        cookies.getCookieStore().removeAll();
        var response=http("POST","/auth/login","{\"tenantCode\":\"OPENING"+tenant+"\",\"username\":\""+user+"\",\"password\":\""+PASSWORD+"\"}",null,false,company);
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private String stockBody() {return "{\"adjustType\":\"opening\",\"warehouseId\":\""+warehouse+"\",\"businessDate\":\"2026-11-01\",\"reason\":\"期初验收\",\"lines\":[{\"skuId\":\""+sku+"\",\"unitId\":\""+unit+"\",\"qty\":\"5\",\"inputTotalCost\":\"50\"}]}";}
    private String cashBody() {return "{\"entryType\":\"cash\",\"cashAccountId\":\""+account+"\",\"direction\":\"D\",\"amount\":\"100\",\"businessDate\":\"2026-11-01\"}";}
    private HttpResponse<String> http(String method,String path,String body,String key,boolean csrf,long company) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));
        if(body!=null) {builder.header("Content-Type","application/json");}
        if(key!=null) {builder.header("Idempotency-Key",key);}
        if(csrf) {builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(cookie->cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());}
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
