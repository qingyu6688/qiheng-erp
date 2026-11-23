package cn.erp.shared;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 核验通过的升级库启用新的审批和账务入口。 */
@Component
public class EnterpriseUpgradeMode {
    public static final int SCHEMA_VERSION = 105;
    // 已完成 2.0 核验的 V102 库可执行后续增量迁移。
    static final int MINIMUM_STARTUP_SCHEMA = 102;
    private final JdbcTemplate jdbc;
    private volatile boolean active;

    public EnterpriseUpgradeMode(JdbcTemplate jdbc, @Value("${erp.enterprise.enabled:false}") boolean enabled) {
        this.jdbc = jdbc;
        this.active = enabled;
    }

    public boolean active() {
        if (!active) {
            active = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM upgrade_log WHERE step='verified')", Boolean.class));
        }
        return active;
    }
}
