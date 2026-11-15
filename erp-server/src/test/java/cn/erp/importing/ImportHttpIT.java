package cn.erp.importing;

import cn.erp.ErpApplication;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ImportHttpIT extends ImportTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    private final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
    @Test void multipartReturns202TemplateHasBomAndErrorsAreActualPersistedRows() throws Exception {
        login("operator");
        var template=client.send(request("/import-templates/partner").GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertThat(template.statusCode()).isEqualTo(200);assertThat(template.body()).isEqualTo(ImportCsv.template("partner"));
        var created=upload("partner","A,成功单位,,,customer\r\nB,错误角色,,,other",key(),true);
        assertThat(created.statusCode()).isEqualTo(202);String id=json.readTree(created.body()).path("data").path("id").asText();
        jdbc.update("""
                UPDATE import_job SET created_at='2026-11-15 13:47:00.123'
                WHERE tenant_id=? AND company_id=? AND id=?
                """, tenant, company, id);
        processor.processNext();var result=get("/import-jobs/"+id);
        assertThat(result.statusCode()).isEqualTo(200);assertThat(json.readTree(result.body()).path("data").path("status").asText()).isEqualTo("partial");
        var list = get("/import-jobs");
        assertThat(list.statusCode()).isEqualTo(200);
        var row = json.readTree(list.body()).path("data").path("items").get(0);
        assertThat(row.path("id").asText()).isEqualTo(id);
        assertThat(row.path("createdAt").asText()).isEqualTo("2026-11-15T13:47:00.123Z");
        for (String field : new String[]{"startedAt", "finishedAt"}) {
            assertThat(row.path(field).asText()).endsWith("Z");
            assertThat(Instant.parse(row.path(field).asText())).isNotNull();
        }
        var errors=get("/import-jobs/"+id+"/errors");assertThat(errors.statusCode()).isEqualTo(200);
        assertThat(json.readTree(errors.body()).path("data").path("items").get(0).path("rowNo").asInt()).isEqualTo(3);
        assertThat(result.body()).doesNotContain("storageKey","file_fingerprint","options_json");
    }
    @Test void uploadChecksCsrfIdempotencyOriginalOperatorAndCompany() throws Exception {
        login("operator");
        assertThat(upload("partner","A,单位,,,customer",key(),false).statusCode()).isEqualTo(403);
        assertThat(upload("partner","A,单位,,,customer",null,true).statusCode()).isEqualTo(400);
        String key=key();var first=upload("partner","A,单位,,,customer",key,true);
        assertThat(first.statusCode()).isEqualTo(202);String id=json.readTree(first.body()).path("data").path("id").asText();
        assertThat(upload("partner","B,另一单位,,,supplier",key,true).statusCode()).isEqualTo(409);
        var foreign=client.send(request("/import-jobs/"+id).setHeader("X-Company-Id",Long.toString(otherCompany)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(foreign.statusCode()).isEqualTo(404);
        cookies.getCookieStore().removeAll();login("reviewer");assertThat(get("/import-jobs/"+id).statusCode()).isEqualTo(404);
    }
    @Test void malformedCsvIsRejectedBeforeAnyJobOrAttachmentMetadataIsCreated() throws Exception {
        login("operator");assertThat(upload("partner","A,\"未闭合",key(),true).statusCode()).isEqualTo(400);
        assertThat(count("import_job")).isZero();assertThat(count("attachment")).isZero();assertThat(count("business_partner")).isZero();
    }
    @Test void retryEndpointUsesCsrfOriginalOwnerAndIdempotencyKey() throws Exception {
        login("operator");var created=upload("partner","P,HTTP恢复单位,,,supplier",key(),true);
        String id=json.readTree(created.body()).path("data").path("id").asText();
        String storage=jdbc.queryForObject("SELECT a.storage_key FROM import_job j JOIN attachment a ON a.id=j.attachment_id WHERE j.id=?",String.class,id);
        byte[] source=files.read(storage);files.delete(storage);
        for(int attempt=0;attempt<3;attempt++){jdbc.update("UPDATE import_job SET next_attempt_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",id);processor.processNext();}
        assertThat(json.readTree(get("/import-jobs/"+id).body()).path("data").path("canRetry").asBoolean()).isTrue();
        files.put(storage,source,"text/csv");assertThat(retry(id,key(),false).statusCode()).isEqualTo(403);
        String key=key();assertThat(retry(id,key,true).statusCode()).isEqualTo(202);assertThat(retry(id,key,true).statusCode()).isEqualTo(202);
        processor.processNext();assertThat(count("business_partner")).isEqualTo(1);
        assertThat(retry(id,key(),true).statusCode()).isEqualTo(409);
        cookies.getCookieStore().removeAll();login("reviewer");assertThat(retry(id,key,true).statusCode()).isEqualTo(404);
    }
    private void login(String username) throws Exception {
        var response=client.send(request("/auth/login").header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"tenantCode\":\"IMPORT"+tenant+"\",\"username\":\""+username+"\",\"password\":\""+PASSWORD+"\"}")).build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private HttpRequest.Builder request(String path){return HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("X-Company-Id",Long.toString(company));}
    private HttpResponse<String> get(String path) throws Exception{return client.send(request(path).GET().build(),HttpResponse.BodyHandlers.ofString());}
    private HttpResponse<String> retry(String id,String key,boolean csrf) throws Exception {
        var builder=request("/import-jobs/"+id+"/retry").header("Idempotency-Key",key);
        if(csrf)builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(builder.POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> upload(String type,String data,String key,boolean csrf) throws Exception {
        String boundary="ERP"+UUID.randomUUID().toString().replace("-","");
        String body="--"+boundary+"\r\nContent-Disposition: form-data; name=\"jobType\"\r\n\r\n"+type+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"input.csv\"\r\nContent-Type: text/csv\r\n\r\n"+new String(csv(type,data),StandardCharsets.UTF_8)+"\r\n--"+boundary+"--\r\n";
        var builder=request("/import-jobs").header("Content-Type","multipart/form-data; boundary="+boundary);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(csrf)builder.header("X-XSRF-TOKEN",cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow());
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
}
