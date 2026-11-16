package cn.erp.observability;

import cn.erp.shared.Ids;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "observability"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0", "ERP_METRICS_TOKEN=observability-integration-test",
        "erp.observability.queue-interval-ms=3600000"
})
class ObservabilityIT {
    @LocalServerPort int port;
    @LocalManagementPort int managementPort;
    @Autowired JdbcTemplate jdbc;
    @Autowired QueueMetrics metrics;
    @Autowired MeterRegistry registry;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void scrapeRequiresSeparateCredentialAndDoesNotCreateBusinessSession() throws Exception {
        assertThat(get(managementPort, "/actuator/prometheus", null).statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", "Bearer wrong").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/prometheus", "Bearer ").statusCode()).isEqualTo(401);
        var scrape = get(managementPort, "/actuator/prometheus", "Bearer observability-integration-test");
        assertThat(scrape.statusCode()).isEqualTo(200);
        assertThat(scrape.body()).contains("jvm_memory_used_bytes", "erp_queue_jobs", "hikaricp_connections");
        assertThat(scrape.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(get(port, "/actuator/prometheus", "Bearer observability-integration-test").statusCode())
                .isEqualTo(404);
        assertThat(get(port, "/api/v1/auth/me", "Bearer observability-integration-test").statusCode())
                .isEqualTo(401);
        assertThat(get(managementPort, "/actuator/health", null).statusCode()).isEqualTo(200);
    }

    @Test
    @Transactional
    void backlogUsesRealQueueAgeAndScrapesReuseSnapshot() {
        metrics.refresh();
        double before = registry.get("erp.queue.jobs").tags("queue", "outbox", "status", "pending").gauge().value();
        long tenant = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'监控测试企业')", tenant, "METRIC" + tenant);
        jdbc.update("""
                INSERT INTO outbox_event(id,tenant_id,aggregate_type,aggregate_id,aggregate_version,event_type,payload,created_at)
                VALUES(?,?,'reconciliation',?,0,'reconciliation.requested','{}',DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 10 MINUTE))
                """, Ids.next(), tenant, Ids.next());
        assertThat(registry.get("erp.queue.jobs").tags("queue", "outbox", "status", "pending").gauge().value())
                .isEqualTo(before);

        metrics.refresh();

        assertThat(registry.get("erp.queue.jobs").tags("queue", "outbox", "status", "pending").gauge().value())
                .isEqualTo(before + 1);
        assertThat(registry.get("erp.queue.oldest.seconds").tag("queue", "outbox").gauge().value())
                .isGreaterThanOrEqualTo(600);
        assertThat(registry.get("erp.queue.collection.success").gauge().value()).isEqualTo(1);
        assertThat(registry.get("erp.queue.collection.timestamp.seconds").gauge().value()).isPositive();
    }

    private HttpResponse<String> get(int targetPort, String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
