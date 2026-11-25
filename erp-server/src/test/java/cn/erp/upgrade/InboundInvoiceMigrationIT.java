package cn.erp.upgrade;

import java.math.BigDecimal;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 旧单值关系保留金额、版本和原时间；失效关系以带原因的历史保留。 */
@EnabledIfEnvironmentVariable(named="ERP_INVOICE_UPGRADE_DB", matches="erp_test_invoice_upgrade_[a-z0-9]+")
class InboundInvoiceMigrationIT {
    @Test void upgradeKeepsLegacyInvoicesAndPayablesAndSeparatesInvalidLinks() {
        String host = System.getenv().getOrDefault("DB_HOST", "127.0.0.1");
        assertThat(host).isIn("127.0.0.1", "localhost");
        String url = "jdbc:mysql://" + host + ":" + System.getenv("DB_PORT") + "/"
                + Objects.requireNonNull(System.getenv("ERP_INVOICE_UPGRADE_DB")) + "?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var source = new DriverManagerDataSource(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("106").load().migrate();
        legacy(jdbc);
        String columns = "id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,checked,payable_id,created_at,updated_at,version";
        var invoices = jdbc.queryForList("SELECT " + columns + " FROM einv_inbound ORDER BY id");
        var payables = jdbc.queryForList("SELECT * FROM payable ORDER BY id");
        Flyway.configure().dataSource(source).target("107").load().migrate();
        assertThat(jdbc.queryForList("SELECT " + columns + " FROM einv_inbound ORDER BY id")).isEqualTo(invoices);
        assertThat(jdbc.queryForList("SELECT * FROM payable ORDER BY id")).isEqualTo(payables);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM einv_inbound_match", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM einv_inbound_match WHERE released_at IS NULL", Integer.class)).isEqualTo(1);
        var valid = jdbc.queryForMap("SELECT * FROM einv_inbound_match WHERE invoice_id=1");
        assertThat((BigDecimal) valid.get("invoice_untaxed_amount")).isEqualByComparingTo("100");
        assertThat((BigDecimal) valid.get("payable_untaxed_amount")).isEqualByComparingTo("100");
        assertThat((BigDecimal) valid.get("difference_amount")).isZero();
        assertThat(valid.get("legacy_migrated")).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT match_notice FROM einv_inbound WHERE id=2", String.class)).contains("来源已失效");
        assertThat(jdbc.queryForObject("SELECT match_notice FROM einv_inbound WHERE id=3", String.class)).contains("金额已变化");
        assertThat(jdbc.queryForObject("SELECT match_notice FROM einv_inbound WHERE id=4", String.class)).contains("作废或红冲");
        assertThat(jdbc.queryForObject("SELECT invoice_match_tolerance FROM company_policy WHERE id=1", BigDecimal.class)).isEqualByComparingTo("1.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inbound_invoice_lock", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission r JOIN sys_permission p ON p.id=r.permission_id WHERE r.role_id=1 AND p.id BETWEEN 18400 AND 18403", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission r JOIN sys_permission p ON p.id=r.permission_id WHERE r.role_id=2 AND p.id BETWEEN 18400 AND 18403", Integer.class)).isZero();
        rejects(() -> jdbc.update("UPDATE company_policy SET invoice_match_tolerance=-0.01 WHERE id=1"), 3819);
        rejects(() -> jdbc.update("UPDATE einv_inbound_match SET invoice_tax_amount=-0.01 WHERE id=1"), 3819);
        rejects(() -> jdbc.update("UPDATE einv_inbound_match SET released_at=UTC_TIMESTAMP(3) WHERE id=1"), 3819);
        String insert = "INSERT INTO einv_inbound_match(id,tenant_id,company_id,invoice_id,payable_id,invoice_untaxed_amount,invoice_tax_amount,payable_untaxed_amount,payable_tax_amount,matched_date) VALUES(88,1,1,1,1,1,0,1,0,'2026-11-23')";
        rejects(() -> jdbc.update(insert), 1062);
        rejects(() -> jdbc.update(insert.replace("88,1,1,1,1", "88,1,2,1,1")), 1452);
        jdbc.update(insert.replace("88,1,1,1,1", "88,1,1,2,1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM einv_inbound_match WHERE payable_id=1 AND released_at IS NULL", Integer.class)).isEqualTo(2);
        jdbc.update("INSERT INTO einv_inbound_match(id,tenant_id,company_id,invoice_id,payable_id,invoice_untaxed_amount,invoice_tax_amount,payable_untaxed_amount,payable_tax_amount,matched_date,released_at,released_date,released_reason) VALUES(89,1,1,1,1,1,0,1,0,'2026-11-23',UTC_TIMESTAMP(3),'2026-11-23','历史关系保留')");
        rejects(() -> jdbc.update("INSERT INTO einv_inbound(id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,source) VALUES(90,1,1,'MANUAL','供应商','TEST12345678','2026-11-23',1,0,'normal','manual')"), 3819);
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1", Integer.class)).isEqualTo(107);
    }

    private static void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-INBOUND','发票升级演练'),(2,'UPGRADE-LIMITED','受限管理员演练')");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','发票历史公司'),(2,1,'B','其他公司'),(3,2,'A','受限历史公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(1,1,1,'W','历史仓')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name,tax_no) VALUES(1,1,'P','历史供应商','TEST12345678')");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','完整升级管理员'),(2,2,'SYSTEM_ADMIN','受限升级管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,period_id,posted_at) VALUES(1,1,1,'OLD-GR',1,1,'2026-11-01','posted',1,UTC_TIMESTAMP(3)),(2,1,1,'OLD-REVERSED',1,1,'2026-11-01','reversed',1,UTC_TIMESTAMP(3))");
        for (int id = 1; id <= 4; id++) {
            jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id) VALUES(?,1,1,1,'charge','goods_receipt_line',?,?,113,100,13,'2026-11-01',1)", id, id == 2 ? 2 : 1, id);
            jdbc.update("INSERT INTO einv_inbound(id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,payable_id,version,created_at,updated_at) VALUES(?,1,1,?,'历史供应商','TEST12345678','2026-11-01',?,13,?,?,7,'2026-11-02 01:02:03.123','2026-11-03 04:05:06.789')",
                    id, "OLD-IN-" + id, id == 3 ? 114 : 113, id == 4 ? "voided" : "normal", id);
        }
    }

    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,
                error -> assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,
                        sql -> assertThat(sql.getErrorCode()).isEqualTo(code)));
    }
}
