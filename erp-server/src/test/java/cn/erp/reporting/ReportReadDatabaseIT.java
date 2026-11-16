package cn.erp.reporting;

import cn.erp.ErpApplication;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;

/** 独立连接验证复用测试库，不冒充真实主从复制验收。 */
@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class ReportReadDatabaseIT {
    @Autowired NamedParameterJdbcTemplate primary;
    @Autowired PlatformTransactionManager transactions;
    @Autowired Environment environment;

    @Test void configuredReadPoolUsesOwnReadOnlyTransactionAndWatermark() {
        var configuration=new MockEnvironment()
                .withProperty("ERP_REPORT_JDBC_URL",environment.getRequiredProperty("spring.datasource.url"))
                .withProperty("ERP_REPORT_DB_USER",environment.getRequiredProperty("spring.datasource.username"))
                .withProperty("ERP_REPORT_DB_PASSWORD",environment.getRequiredProperty("spring.datasource.password"));
        var database=new ReportReadDatabase(primary,transactions,configuration);
        try {
            assertThat(database.replicaEnabled()).isTrue();
            var primaryId=primary.queryForObject("SELECT CONNECTION_ID()",Map.of(),Long.class);
            database.read(()->{
                assertThat(database.jdbc().queryForObject("SELECT CONNECTION_ID()",Map.of(),Long.class)).isNotEqualTo(primaryId);
                assertThat(database.jdbc().queryForObject("SELECT @@transaction_read_only",Map.of(),Integer.class)).isEqualTo(1);
                assertThat(database.asOf()).endsWith("Z");
                return null;
            });
            assertThatThrownBy(()->database.read(()->database.jdbc().update(
                    "UPDATE report_snapshot_watermark SET observed_at=UTC_TIMESTAMP(3) WHERE id=1",Map.of())))
                    .isInstanceOf(org.springframework.dao.DataAccessException.class);
        } finally { database.close(); }
    }
}
