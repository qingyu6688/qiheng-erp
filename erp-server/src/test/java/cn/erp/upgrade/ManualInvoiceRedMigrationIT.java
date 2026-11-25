package cn.erp.upgrade;

import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 原作废票保持原事实，迁移不得把它们自动标为已完成红字。 */
@EnabledIfEnvironmentVariable(named = "ERP_MANUAL_RED_UPGRADE_DB", matches = "erp_test_red_upgrade_[a-z0-9]+")
class ManualInvoiceRedMigrationIT {
    @Test void upgradeKeepsManualBlueFactsAndRequiresDistinctRedReviewer() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        assertThat(host).isIn("127.0.0.1", "localhost");
        String url = "jdbc:mysql://" + host + ":" + System.getenv("DB_PORT") + "/"
                + Objects.requireNonNull(System.getenv("ERP_MANUAL_RED_UPGRADE_DB")) + "?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var data = new DriverManagerDataSource(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        Flyway.configure().dataSource(data).target("107").load().migrate();
        legacy(jdbc);
        var blues = jdbc.queryForList("SELECT * FROM invoice_registration ORDER BY id");
        var lines = jdbc.queryForList("SELECT * FROM invoice_registration_line ORDER BY id");
        var receivables = jdbc.queryForList("SELECT * FROM receivable ORDER BY id");
        Flyway.configure().dataSource(data).target("108").load().migrate();
        assertThat(jdbc.queryForList("SELECT * FROM invoice_registration ORDER BY id")).isEqualTo(blues);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_registration_line ORDER BY id")).isEqualTo(lines);
        assertThat(jdbc.queryForList("SELECT * FROM receivable ORDER BY id")).isEqualTo(receivables);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM invoice_red_registration", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_invoice_scope_lock", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1 AND permission_id BETWEEN 18500 AND 18503", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2 AND permission_id BETWEEN 18500 AND 18503", Integer.class)).isZero();
        String insert = "INSERT INTO invoice_red_registration(id,tenant_id,company_id,invoice_id,red_invoice_no,red_invoice_date,amount,tax_amount,reason,source_version,created_by,updated_by)"
                + " VALUES(1,1,1,1,'RED-ONE','2026-11-23',100,11.50,'约束核验',7,1,1)";
        jdbc.update(insert);
        rejects(() -> jdbc.update(insert.replace("VALUES(1,", "VALUES(2,").replace("RED-ONE", "RED-TWO")), 1062);
        rejects(() -> jdbc.update("UPDATE invoice_red_registration SET status='confirmed',confirmed_by=1,confirmed_at=UTC_TIMESTAMP(3) WHERE id=1"), 3819);
        rejects(() -> jdbc.update("UPDATE invoice_red_registration SET tax_amount=101 WHERE id=1"), 3819);
        rejects(() -> jdbc.update(insert.replace("VALUES(1,1,1,1", "VALUES(2,1,2,1").replace("RED-ONE", "RED-FOREIGN")), 1452);
        jdbc.update("UPDATE invoice_red_registration SET status='cancelled' WHERE id=1");
        jdbc.update(insert.replace("VALUES(1,", "VALUES(2,").replace("RED-ONE", "RED-TWO"));
        rejects(() -> jdbc.update(insert.replace("VALUES(1,1,1,1", "VALUES(3,1,1,2")), 1062);
        jdbc.update("UPDATE invoice_red_registration SET status='confirmed',confirmed_by=2,confirmed_at=UTC_TIMESTAMP(3) WHERE id=2");
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(108);
    }

    private static void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-RED','手工红字升级'),(2,'UPGRADE-RED-LIMITED','受限角色')");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','历史公司'),(2,1,'B','其他公司')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史客户')");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,invoiced_amount,business_date,period_id,version) VALUES(1,1,1,1,'charge','opening_entry',1,1,113,100,13,100,'2026-11-01',1,9)");
        jdbc.update("INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,amount,tax_amount,tax_diff_amount,status,version,created_at,updated_at) VALUES"
                + "(1,1,1,'sales',1,'OLD-REGISTERED','2026-11-01',100,11.50,0.01,'registered',7,'2026-11-01 01:02:03.123','2026-11-02 01:02:03.456'),"
                + "(2,1,1,'sales',1,'OLD-VOIDED','2026-11-01',50,5.75,0,'voided',8,'2026-11-01 01:02:03.123','2026-11-03 01:02:03.456')");
        jdbc.update("INSERT INTO invoice_registration_line(id,tenant_id,company_id,invoice_id,receivable_id,amount) VALUES(1,1,1,1,1,100),(2,1,1,2,1,50)");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','完整管理员'),(2,2,'SYSTEM_ADMIN','受限管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
    }

    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable work, int code) {
        assertThatThrownBy(work).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                error -> assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                        sql -> assertThat(sql.getErrorCode()).isEqualTo(code)));
    }
}
