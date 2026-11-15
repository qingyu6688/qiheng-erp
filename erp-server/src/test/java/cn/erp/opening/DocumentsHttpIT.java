package cn.erp.opening;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ErpApplication;
import cn.erp.documents.ObjectCompensation;
import cn.erp.documents.ObjectFiles;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 复用期初真实MySQL夹具，验证附件和打印沿用原单据范围及成本权限。 */
@SpringBootTest(classes=ErpApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DocumentsHttpIT extends OpeningTestData {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ObjectFiles files;
    @Autowired ObjectCompensation compensation;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final byte[] PDF = "%PDF-1.7\n附件验收\n%%EOF".getBytes(StandardCharsets.UTF_8);

    @AfterEach void cleanupDocuments() {
        for (String storageKey : jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?", String.class, tenant)) {
            files.delete(storageKey);
        }
        for (String storageKey : jdbc.queryForList("SELECT storage_key FROM object_cleanup WHERE storage_key LIKE ?", String.class, tenant + "/%")) {
            files.delete(storageKey);
        }
        jdbc.update("DELETE FROM object_cleanup WHERE storage_key LIKE ?", tenant + "/%");
        jdbc.update("DELETE FROM file_ticket WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM attachment WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM shipment WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM goods_receipt WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM payment WHERE tenant_id=?", tenant);
        jdbc.update("DELETE FROM purchase_order WHERE tenant_id=?", tenant);
    }

    @Test void multipartIdempotencyPrivateDownloadAndSoftDeleteUseActualHttp() throws Exception {
        long owner = stockDocument(); login("operator");
        String uploadKey = key(); var created = upload(owner, "attachment.pdf", PDF, uploadKey);
        assertThat(created.statusCode()).isEqualTo(201);
        String id = dataId(created);
        assertThat(created.body()).doesNotContain("storageKey", "storage_key");
        assertThat(dataId(upload(owner, "attachment.pdf", PDF, uploadKey))).isEqualTo(id);
        assertThat(upload(owner, "attachment.pdf", "%PDF-1.7\n不同内容\n%%EOF".getBytes(StandardCharsets.UTF_8), uploadKey).statusCode()).isEqualTo(409);
        assertThat(count("attachment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM object_cleanup WHERE storage_key LIKE ?", Long.class, tenant + "/%" )).isZero();
        var listed = request("GET", "/attachments?ownerType=stock_adjustment&ownerId=" + owner, null, false, company);
        assertThat(listed.statusCode()).isEqualTo(200); assertThat(listed.body()).doesNotContain("storageKey", "storage_key");
        var redirect = request("GET", "/attachments/" + id + "/download", null, false, company);
        assertThat(redirect.statusCode()).isEqualTo(302);
        String location = redirect.headers().firstValue("Location").orElseThrow();
        assertThat(content(location).statusCode()).isEqualTo(200); assertThat(content(location).body()).isEqualTo(PDF);
        assertThat(request("DELETE", "/attachments/" + id, key(), true, company).statusCode()).isEqualTo(200);
        assertThat(content(location).statusCode()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT status FROM attachment WHERE id=?", String.class, id)).isEqualTo("deleted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='attachment.upload'", Long.class, tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND action='attachment.delete'", Long.class, tenant)).isEqualTo(1);
    }

    @Test void issuedTicketIsBoundToCurrentUserAndCurrentPermissions() throws Exception {
        long owner = stockDocument(); login("operator");
        String id = dataId(upload(owner, "attachment.pdf", PDF, key()));
        String location = request("GET", "/attachments/" + id + "/download", null, false, company).headers().firstValue("Location").orElseThrow();
        login("reviewer"); assertThat(content(location).statusCode()).isEqualTo(403);
        login("operator"); assertThat(content(location).statusCode()).isEqualTo(200);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_adjustment:view')", tenant, role);
        assertThat(content(location).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/attachments/" + id + "/download", null, false, company).statusCode()).isEqualTo(403);
    }

    @Test void ticketsExpireAndCannotEscapeCompanyOrChangedWarehouseScope() throws Exception {
        long owner = stockDocument(); login("operator");
        String id = dataId(upload(owner, "attachment.pdf", PDF, key()));
        String location = request("GET", "/attachments/" + id + "/download", null, false, company).headers().firstValue("Location").orElseThrow();
        assertThat(content(location.replace("companyId=" + company, "companyId=" + otherCompany)).statusCode()).isEqualTo(403);
        jdbc.update("UPDATE file_ticket SET expires_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE tenant_id=?", tenant);
        assertThat(content(location).statusCode()).isEqualTo(403);
        String fresh = request("GET", "/attachments/" + id + "/download", null, false, company).headers().firstValue("Location").orElseThrow();
        jdbc.update("DELETE FROM sys_user_data_scope WHERE tenant_id=? AND user_id=? AND company_id=? AND warehouse_id=?", tenant, user, company, warehouse);
        assertThat(content(fresh).statusCode()).isEqualTo(404);
        assertThat(request("GET", "/attachments?ownerType=stock_adjustment&ownerId=" + owner, null, false, company).statusCode()).isEqualTo(404);
    }

    @Test void printUsesBusinessSnapshotApprovalAndCurrentCostAndPrintPermissions() throws Exception {
        long owner = stockDocument(); login("operator");
        jdbc.update("UPDATE company SET address='打印地址',phone='010-12345678' WHERE id=?", company);
        var full = request("GET", "/print-data/stock_adjustment/" + owner, null, false, company);
        assertThat(full.statusCode()).isEqualTo(200);
        assertThat(full.body()).contains("打印地址", "approvalRecords", "50.00", "inputTotalCost");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='cost:view')", tenant, role);
        var hidden = request("GET", "/print-data/stock_adjustment/" + owner, null, false, company);
        assertThat(hidden.statusCode()).isEqualTo(200); assertThat(hidden.body()).doesNotContain("inputTotalCost", "unitCost", "costAmount");
        assertThat(request("GET", "/print-data/stock_adjustment/" + owner, null, false, otherCompany).statusCode()).isEqualTo(404);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='stock_adjustment:print')", tenant, role);
        assertThat(request("GET", "/print-data/stock_adjustment/" + owner, null, false, company).statusCode()).isEqualTo(403);
        assertThat(count("audit_log")).isGreaterThanOrEqualTo(3);
    }

    @Test void shipmentAndReceivingPrintHaveActualMakerNamesWithoutMergingUnmaskedHeader() throws Exception {
        long shipment = Ids.next(), receipt = Ids.next();
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,created_by,cost_amount) VALUES(?,?,?,'PRINT-SHIP',?,?,'2026-11-01',?,98765.43)",
                shipment, tenant, company, partner, warehouse, user);
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,created_by,cost_amount) VALUES(?,?,?,'PRINT-RECEIVE',?,?,'2026-11-01',?,98765.43)",
                receipt, tenant, company, partner, warehouse, user);
        jdbc.update("UPDATE sys_user SET display_name='打印复核员' WHERE id=?", reviewerId);
        jdbc.update("INSERT INTO approval_record(id,tenant_id,company_id,document_type,document_id,action,operator_id) VALUES(?,?,?,'shipment',?,'approve',?)",
                Ids.next(), tenant, company, shipment, reviewerId);
        login("operator");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='cost:view')", tenant, role);
        for (var item : java.util.Map.of("shipment", shipment, "goods_receipt", receipt).entrySet()) {
            var response = request("GET", "/print-data/" + item.getKey() + "/" + item.getValue(), null, false, company);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("data").path("document").path("createdByName").asText()).isEqualTo("期初验收用户");
            assertThat(response.body()).doesNotContain("98765.43", "costAmount", "signedCostAmount");
            if (item.getKey().equals("shipment")) {
                assertThat(json.readTree(response.body()).path("data").path("document").path("approvedByName").asText()).isEqualTo("打印复核员");
            }
        }
    }

    @Test void paymentPrintSourcesUseHumanDocumentNumbersAndCurrentSourceViewPermission() throws Exception {
        long order = Ids.next(), payment = Ids.next();
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)", Ids.next(), tenant, user, company);
        jdbc.update("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,created_by) VALUES(?,?,?,'PO-PRINT-SOURCE',?,'2026-11-01',1,?)",
                order, tenant, company, partner, user);
        jdbc.update("INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,amount,business_date,related_order_id,created_by) VALUES(?,?,?,'PAY-PRINT-SOURCE','supplier',?,?,10,'2026-11-01',?,?)",
                payment, tenant, company, partner, account, order, user);
        login("operator");
        var visible = request("GET", "/print-data/payment/" + payment, null, false, company);
        assertThat(visible.statusCode()).isEqualTo(200);
        assertThat(json.readTree(visible.body()).path("data").path("document").path("sourceDocuments").get(0).path("documentNo").asText()).isEqualTo("PO-PRINT-SOURCE");
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=(SELECT id FROM sys_permission WHERE code='purchase_order:view')", tenant, role);
        var hidden = request("GET", "/print-data/payment/" + payment, null, false, company);
        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(hidden.body()).doesNotContain("PO-PRINT-SOURCE");
        assertThat(json.readTree(hidden.body()).path("data").path("document").path("sourceDocuments").size()).isZero();
    }

    @Test void parallelUploadsAtomicallyStopAtTwentyAndSameKeyDoesNotDuplicate() throws Exception {
        long owner = stockDocument(); login("operator");
        try (var pool = Executors.newFixedThreadPool(21)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<Integer>>();
            for (int index = 0; index < 21; index++) {
                int number = index;
                futures.add(pool.submit(() -> { start.await(); return upload(owner, "文件" + number + ".pdf", PDF, key()).statusCode(); }));
            }
            start.countDown(); var codes = new ArrayList<Integer>(); for (var future : futures) { codes.add(future.get()); }
            assertThat(codes.stream().filter(code -> code == 201).count()).isEqualTo(20);
            assertThat(codes.stream().filter(code -> code == 409).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE tenant_id=? AND status='active'", Long.class, tenant)).isEqualTo(20);
    }

    @Test void parallelSameRequestCreatesOneAttachmentAndCleansUnusedCandidates() throws Exception {
        long owner = stockDocument(); login("operator"); String sharedKey = key();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (int index = 0; index < 4; index++) {
                futures.add(pool.submit(() -> { start.await(); var response = upload(owner, "同一文件.pdf", PDF, sharedKey); assertThat(response.statusCode()).isEqualTo(201); return dataId(response); }));
            }
            start.countDown(); var ids = new java.util.HashSet<String>(); for (var future : futures) { ids.add(future.get()); }
            assertThat(ids).hasSize(1);
        }
        assertThat(count("attachment")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM object_cleanup WHERE storage_key LIKE ?", Long.class, tenant + "/%" )).isZero();
    }

    @Test void abandonedObjectsHaveDurableCompensationAndJobOwnersAreRejected() throws Exception {
        login("operator");
        String storageKey = tenant + "/" + company + "/abandoned_" + key();
        compensation.stage(storageKey); files.put(storageKey, PDF, "application/pdf");
        jdbc.update("UPDATE object_cleanup SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 HOUR) WHERE storage_key=?", storageKey);
        compensation.retry();
        assertThatThrownBy(() -> files.read(storageKey)).isInstanceOf(ApiException.class);
        assertThat(request("GET", "/attachments?ownerType=import_job&ownerId=1", null, false, company).statusCode()).isEqualTo(400);
        assertThat(request("GET", "/attachments?ownerType=export_job&ownerId=1", null, false, company).statusCode()).isEqualTo(400);
    }

    @Test void deletedAttachmentIsEventuallyRemovedFromThePrivateStore() throws Exception {
        long owner = stockDocument(); login("operator");
        String id = dataId(upload(owner, "attachment.pdf", PDF, key()));
        String storageKey = jdbc.queryForObject("SELECT storage_key FROM attachment WHERE id=?", String.class, id);
        assertThat(request("DELETE", "/attachments/" + id, key(), true, company).statusCode()).isEqualTo(200);
        assertThat(files.read(storageKey)).isEqualTo(PDF);
        jdbc.update("UPDATE object_cleanup SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 HOUR) WHERE storage_key=?", storageKey);
        compensation.retry();
        assertThatThrownBy(() -> files.read(storageKey)).isInstanceOf(ApiException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM object_cleanup WHERE storage_key=?", Long.class, storageKey)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM attachment WHERE id=?", String.class, id)).isEqualTo("deleted");
    }

    @Test void actualFileStoreCleanupFailureKeepsDurableRetryRecord() {
        String storageKey = tenant + "/" + company + "/blocked_delete_" + key();
        String child = storageKey + "/child";
        files.put(child, PDF, "application/pdf"); compensation.stage(storageKey);
        jdbc.update("UPDATE object_cleanup SET created_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 2 HOUR) WHERE storage_key=?", storageKey);
        try {
            compensation.retry();
            assertThat(jdbc.queryForObject("SELECT attempts FROM object_cleanup WHERE storage_key=?", Integer.class, storageKey)).isEqualTo(1);
            assertThat(files.read(child)).isEqualTo(PDF);
        } finally {
            files.delete(child); files.delete(storageKey); jdbc.update("DELETE FROM object_cleanup WHERE storage_key=?", storageKey);
        }
    }

    private long stockDocument() { return id(service.createStock(maker, key(), stock(warehouse, "5", "50", null))); }

    private void login(String username) throws Exception {
        cookies.getCookieStore().removeAll();
        var body = "{\"tenantCode\":\"OPENING" + tenant + "\",\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}";
        var builder = HttpRequest.newBuilder(uri("/api/v1/auth/login")).header("Content-Type", "application/json");
        assertThat(client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> upload(long owner, String name, byte[] bytes, String key) throws Exception {
        String boundary = "ERP" + java.util.UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"ownerType\"\r\n\r\nstock_adjustment\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"ownerId\"\r\n\r\n" + owner + "\r\n--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: text/plain\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(bytes); body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var builder = HttpRequest.newBuilder(uri("/api/v1/attachments")).header("X-Company-Id", Long.toString(company))
                .header("Idempotency-Key", key).header("X-XSRF-TOKEN", csrf()).header("Content-Type", "multipart/form-data; boundary=" + boundary);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String path, String key, boolean csrf, long selectedCompany) throws Exception {
        var builder = HttpRequest.newBuilder(uri("/api/v1" + path)).header("X-Company-Id", Long.toString(selectedCompany));
        if (key != null) { builder.header("Idempotency-Key", key); }
        if (csrf) { builder.header("X-XSRF-TOKEN", csrf()); }
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> content(String location) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(location)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String csrf() { return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("XSRF-TOKEN")).map(HttpCookie::getValue).findFirst().orElseThrow(); }
    private String dataId(HttpResponse<String> response) { return json.readTree(response.body()).path("data").path("id").asText(); }
}
