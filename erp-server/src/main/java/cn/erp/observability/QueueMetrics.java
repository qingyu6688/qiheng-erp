package cn.erp.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 仅供受控运维端口使用的实例级队列摘要。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "erp.observability.enabled", havingValue = "true")
class QueueMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(QueueMetrics.class);
    private static final List<String> QUEUES = List.of("outbox", "import", "export");
    private final JdbcTemplate jdbc;
    private volatile Map<String, QueueState> snapshot = Map.of();
    private volatile double lastSuccess;
    private volatile double healthy;

    private record QueueState(long pending, long running, long failed, double oldestSeconds) {}

    QueueMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        for (String queue : QUEUES) {
            Gauge.builder("erp.queue.jobs", this, metrics -> metrics.state(queue).pending())
                    .tags("queue", queue, "status", "pending").register(registry);
            Gauge.builder("erp.queue.jobs", this, metrics -> metrics.state(queue).running())
                    .tags("queue", queue, "status", "running").register(registry);
            Gauge.builder("erp.queue.jobs", this, metrics -> metrics.state(queue).failed())
                    .tags("queue", queue, "status", "failed").register(registry);
            Gauge.builder("erp.queue.oldest.seconds", this, metrics -> metrics.state(queue).oldestSeconds())
                    .tag("queue", queue).register(registry);
        }
        Gauge.builder("erp.queue.collection.success", this, metrics -> metrics.healthy).register(registry);
        Gauge.builder("erp.queue.collection.timestamp.seconds", this, metrics -> metrics.lastSuccess).register(registry);
    }

    @Scheduled(fixedDelayString = "${erp.observability.queue-interval-ms:30000}")
    public void refresh() {
        try {
            Map<String, QueueState> collected = Map.of(
                    "outbox", collect("outbox_event", "processing", "dead"),
                    "import", collect("import_job", "running", "failed", "partial"),
                    "export", collect("export_job", "running", "failed"));
            snapshot = collected;
            lastSuccess = Instant.now().getEpochSecond();
            healthy = 1;
        } catch (DataAccessException failure) {
            healthy = 0;
            LOG.warn("队列指标采集失败，请检查数据库连接及查询权限", failure);
        }
    }

    private QueueState collect(String table, String running, String... failures) {
        String failureSlots = String.join(",", java.util.Collections.nCopies(failures.length, "?"));
        var parameters = new java.util.ArrayList<Object>();
        parameters.add(running);
        parameters.addAll(List.of(failures));
        parameters.add(running);
        parameters.add(running);
        parameters.addAll(List.of(failures));
        // 表名来自固定常量，状态索引排除已完成历史任务，抓取请求复用采集快照。
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(status='pending'),0) pending,
                       COALESCE(SUM(status=?),0) running,
                       COALESCE(SUM(status IN (%s)),0) failed,
                       COALESCE(MAX(IF(status IN ('pending',?),
                           GREATEST(0,TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP(3))),0)),0) oldest
                FROM %s WHERE status IN ('pending',?,%s)
                """.formatted(failureSlots, table, failureSlots),
                (row, index) -> new QueueState(row.getLong("pending"), row.getLong("running"),
                        row.getLong("failed"), row.getDouble("oldest")), parameters.toArray());
    }

    private QueueState state(String queue) {
        return snapshot.getOrDefault(queue, new QueueState(0, 0, 0, Double.NaN));
    }
}
