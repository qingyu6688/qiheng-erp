package cn.erp.documents;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import cn.erp.shared.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObjectFilesTest {
    @TempDir Path directory;

    @Test void localPrivateStorePersistsBytesAndRejectsTraversal() {
        var env = new MockEnvironment().withProperty("erp.storage.local-root", directory.toString());
        env.setActiveProfiles("test");
        var store = new ObjectFiles(env);
        byte[] bytes = "私有文件\n".getBytes(StandardCharsets.UTF_8);
        assertThat(store.put("tenant/company/file_1", bytes, "text/csv")).isEqualTo("tenant/company/file_1");
        assertThat(store.read("tenant/company/file_1")).isEqualTo(bytes);
        for (String invalid : List.of("../escape", "/absolute", "C:/escape", "a//b", "a/../b", "a\\b")) {
            assertThatThrownBy(() -> store.put(invalid, bytes, "text/plain")).isInstanceOf(ApiException.class);
        }
        store.delete("tenant/company/file_1");
        assertThatThrownBy(() -> store.read("tenant/company/file_1")).isInstanceOf(ApiException.class);
        store.close();
    }

    @Test void productionRequiresExplicitCompleteSharedStore() {
        var env = new MockEnvironment(); env.setActiveProfiles("prod");
        assertThatThrownBy(() -> new ObjectFiles(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("共享对象仓");
        env.setActiveProfiles("dev", "prod");
        assertThatThrownBy(() -> new ObjectFiles(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("共享对象仓");
        env.setActiveProfiles("test", "production");
        assertThatThrownBy(() -> new ObjectFiles(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("共享对象仓");
        env.withProperty("erp.storage.endpoint", "http://127.0.0.1:9000");
        assertThatThrownBy(() -> new ObjectFiles(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("不完整");
    }

    @Test void s3AdapterSignsRealHttpPutGetDeleteAndRoundTripsBytes() throws Exception {
        var objects = new ConcurrentHashMap<String, byte[]>();
        var requests = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var failures = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (!validSignature(exchange, body)) { failures.add("签名无效"); exchange.sendResponseHeaders(403, -1); return; }
                String path = exchange.getRequestURI().getRawPath(); requests.add(exchange.getRequestMethod() + " " + path);
                switch (exchange.getRequestMethod()) {
                    case "PUT" -> { objects.put(path, body); exchange.getResponseHeaders().add("ETag", "\"local-test\""); exchange.sendResponseHeaders(200, -1); }
                    case "GET" -> { byte[] value = objects.get(path); if (value == null) { exchange.sendResponseHeaders(404, -1); } else { exchange.sendResponseHeaders(200, value.length); exchange.getResponseBody().write(value); } }
                    case "DELETE" -> { objects.remove(path); exchange.sendResponseHeaders(204, -1); }
                    default -> exchange.sendResponseHeaders(405, -1);
                }
            } catch (Exception error) { failures.add(error.getClass().getSimpleName()); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        var env = new MockEnvironment().withProperty("erp.storage.endpoint", "http://127.0.0.1:" + server.getAddress().getPort())
                .withProperty("erp.storage.bucket", "private-test").withProperty("erp.storage.access-key", "test-access")
                .withProperty("erp.storage.secret-key", "test-secret");
        ObjectFiles store = new ObjectFiles(env);
        try {
            byte[] bytes = "编码,名称\nA,协议端点\n".getBytes(StandardCharsets.UTF_8);
            store.put("tenant/company/item_1", bytes, "text/csv");
            assertThat(store.read("tenant/company/item_1")).isEqualTo(bytes);
            store.delete("tenant/company/item_1");
            assertThat(objects).isEmpty(); assertThat(failures).isEmpty();
            assertThat(requests).containsExactly("PUT /private-test/tenant/company/item_1", "GET /private-test/tenant/company/item_1", "DELETE /private-test/tenant/company/item_1");
        } finally { store.close(); server.stop(0); }
    }

    private static boolean validSignature(HttpExchange exchange, byte[] body) throws Exception {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("AWS4-HMAC-SHA256 ")) { return false; }
        var parts = new LinkedHashMap<String, String>();
        for (String part : authorization.substring("AWS4-HMAC-SHA256 ".length()).split(",\\s*")) {
            String[] pair = part.split("=", 2); parts.put(pair[0], pair[1]);
        }
        String credential = parts.get("Credential"); String[] scope = credential.split("/");
        if (!scope[0].equals("test-access") || !scope[3].equals("s3")) { return false; }
        String signed = parts.get("SignedHeaders"); var headers = new StringBuilder();
        for (String name : signed.split(";")) {
            String value = String.join(",", exchange.getRequestHeaders().get(name));
            headers.append(name).append(':').append(value.trim().replaceAll("\\s+", " ")).append('\n');
        }
        String payloadHash = exchange.getRequestHeaders().getFirst("x-amz-content-sha256");
        if (!payloadHash.equals("UNSIGNED-PAYLOAD") && !payloadHash.equals(hash(body))) { return false; }
        String canonical = exchange.getRequestMethod() + "\n" + exchange.getRequestURI().getRawPath() + "\n"
                + (exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery()) + "\n"
                + headers + "\n" + signed + "\n" + payloadHash;
        String date = exchange.getRequestHeaders().getFirst("x-amz-date");
        String credentialScope = String.join("/", java.util.Arrays.copyOfRange(scope, 1, scope.length));
        String toSign = "AWS4-HMAC-SHA256\n" + date + "\n" + credentialScope + "\n" + hash(canonical.getBytes(StandardCharsets.UTF_8));
        byte[] signing = hmac(("AWS4" + "test-secret").getBytes(StandardCharsets.UTF_8), scope[1]);
        signing = hmac(signing, scope[2]); signing = hmac(signing, scope[3]); signing = hmac(signing, scope[4]);
        return MessageDigest.isEqual(HexFormat.of().formatHex(hmac(signing, toSign)).getBytes(StandardCharsets.US_ASCII),
                parts.get("Signature").getBytes(StandardCharsets.US_ASCII));
    }

    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private static byte[] hmac(byte[] key, String text) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(text.getBytes(StandardCharsets.UTF_8));
    }
}
