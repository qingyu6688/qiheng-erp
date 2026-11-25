package cn.erp.upgrade;

import java.math.BigDecimal;
import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 验证旧费用的进度映射，不重写原费用或往来事实。 */
@EnabledIfEnvironmentVariable(named="ERP_FEE_UPGRADE_DB",matches="erp_test_landed_upgrade_[a-z0-9]+")
class PurchaseFeeMigrationIT {
    @Test void upgradePreservesOldFeesAndPayablesAndSetsTheNewDefaults() {
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");
        assertThat(host).isIn("127.0.0.1","localhost");
        String url="jdbc:mysql://"+host+":"+System.getenv("DB_PORT")+"/"+Objects.requireNonNull(System.getenv("ERP_FEE_UPGRADE_DB"))+"?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var source=new DriverManagerDataSource(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("105").load().migrate();
        legacy(jdbc);
        String columns="id,amount,total_amount,untaxed_amount,tax_amount,recognized_receipt_id,version,updated_at";
        var fees=jdbc.queryForList("SELECT "+columns+" FROM purchase_order_fee ORDER BY id");
        var payables=jdbc.queryForList("SELECT * FROM payable ORDER BY id");
        Flyway.configure().dataSource(source).target("106").load().migrate();
        assertThat(jdbc.queryForList("SELECT "+columns+" FROM purchase_order_fee ORDER BY id")).isEqualTo(fees);
        assertThat(jdbc.queryForList("SELECT * FROM payable ORDER BY id")).isEqualTo(payables);
        assertThat(jdbc.queryForObject("SELECT recognized_amount FROM purchase_order_fee WHERE id=1",BigDecimal.class)).isEqualByComparingTo("113");
        assertThat(jdbc.queryForObject("SELECT recognized_tax FROM purchase_order_fee WHERE id=1",BigDecimal.class)).isEqualByComparingTo("13");
        assertThat(jdbc.queryForObject("SELECT recognized_amount FROM purchase_order_fee WHERE id=2",BigDecimal.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT purchase_fee_cost_enabled,purchase_fee_allocation_method FROM company_policy WHERE id=1"))
                .containsEntry("purchase_fee_cost_enabled",true).containsEntry("purchase_fee_allocation_method","amount");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission r JOIN sys_permission p ON p.id=r.permission_id WHERE r.role_id=1 AND p.code LIKE 'purchase_fee_doc:%'",Integer.class)).isEqualTo(7);
        rejectsCheck(()->jdbc.update("UPDATE purchase_order_fee SET recognized_amount=114 WHERE id=1"),"ck_purchase_order_fee_progress");
        jdbc.update("INSERT INTO purchase_fee_doc(id,tenant_id,company_id,document_no,partner_id,warehouse_id,business_date,allocation_method,reason,cost_tax_inclusive,amount,untaxed_amount,tax_amount,owner_user_id,created_by,updated_by) VALUES(1,1,1,'FEE',1,1,'2026-11-01','amount','迁移约束验证',0,113,100,13,1,1,1)");
        rejectsCheck(()->jdbc.update("UPDATE purchase_fee_doc SET term_type='days' WHERE id=1"),"ck_purchase_fee_doc_term");
        rejectsCheck(()->jdbc.update("UPDATE purchase_fee_doc SET status='posted',approved_by=2,approved_at=UTC_TIMESTAMP(3),posted_by=1,posted_at=UTC_TIMESTAMP(3),period_id=1 WHERE id=1"),"ck_purchase_fee_doc_posted_cost");
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(106);
    }

    private void rejectsCheck(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String constraint) {
        assertThatThrownBy(action).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->
                assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->{
                    assertThat(sql.getErrorCode()).isEqualTo(3819);assertThat(sql.getMessage()).contains(constraint);
                }));
    }

    private void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-FEE','采购费用升级演练')");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','费用历史公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(1,1,1,'W','历史仓')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史供应商')");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(1,1,1,'supplier')");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','费用升级管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
        jdbc.update("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive) VALUES(1,1,1,'OLD-PO',1,'2026-11-01',1)");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,period_id,posted_at) VALUES(1,1,1,'OLD-GR',1,1,'2026-11-01','posted',1,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO purchase_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,price_tax_inclusive,tax_rate,untaxed_amount,tax_amount,recognized_receipt_id) VALUES(1,1,1,1,'freight','buyer',113,1,.13,100,13,1),(2,1,1,1,'other','buyer',5,1,0,5,0,NULL)");
        jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id) VALUES(1,1,1,1,'charge','purchase_order_fee',1,1,113,100,13,'2026-11-01','2026-12-01',1)");
    }
}
