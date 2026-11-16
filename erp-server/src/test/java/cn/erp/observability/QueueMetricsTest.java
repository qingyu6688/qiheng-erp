package cn.erp.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import static org.assertj.core.api.Assertions.assertThat;

class QueueMetricsTest {
    @Test
    void databaseFailureIsReportedWithoutPretendingThatAnEmptyQueueWasCollected() {
        var unavailable = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... arguments) {
                throw new DataAccessResourceFailureException("测试数据库不可用");
            }
        };
        var registry = new SimpleMeterRegistry();
        try {
            var metrics = new QueueMetrics(unavailable, registry);

            metrics.refresh();

            assertThat(registry.get("erp.queue.collection.success").gauge().value()).isZero();
            assertThat(registry.get("erp.queue.collection.timestamp.seconds").gauge().value()).isZero();
            assertThat(registry.get("erp.queue.oldest.seconds").tag("queue", "outbox").gauge().value()).isNaN();
        } finally {
            registry.close();
        }
    }
}
