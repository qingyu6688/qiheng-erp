package cn.erp.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 主库水位随复制进入备库，报表展示同一读取事务内的保守截止时间。 */
@Configuration(proxyBeanMethods=false)
@EnableScheduling
@ConditionalOnProperty(name="erp.worker.enabled",havingValue="true",matchIfMissing=true)
class ReportWatermark {
    private static final Logger LOG=LoggerFactory.getLogger(ReportWatermark.class);
    private final JdbcTemplate primary;
    private final ReportReadDatabase reports;

    ReportWatermark(JdbcTemplate primary,ReportReadDatabase reports) {
        this.primary=primary;
        this.reports=reports;
    }

    @Scheduled(fixedDelay=5000)
    public void refresh() {
        if (!reports.replicaEnabled()) return;
        try {
            primary.update("UPDATE report_snapshot_watermark SET observed_at=UTC_TIMESTAMP(3) WHERE id=1");
        } catch (org.springframework.dao.DataAccessException failure) {
            LOG.warn("报表数据水位更新失败，请检查主库连接与权限",failure);
        }
    }
}
