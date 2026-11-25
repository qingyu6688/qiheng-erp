package cn.erp.upgrade;

import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** 仅使用新建空库演练，不清空或修复已经使用过的迁移库。 */
@EnabledIfEnvironmentVariable(named="ERP_PRICE_UPGRADE_DB",matches="erp_test_price_upgrade_[a-z0-9]+")
class PriceAdjustmentMigrationIT {
    @Test void upgradePreservesLegacyReceiptAmountsCostsReturnsAndDueDates() {
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");
        assertThat(host).isIn("127.0.0.1","localhost");
        String url="jdbc:mysql://"+host+":"+System.getenv("DB_PORT")+"/"+Objects.requireNonNull(System.getenv("ERP_PRICE_UPGRADE_DB"))+"?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var source=new DriverManagerDataSource(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("104").load().migrate();
        legacy(jdbc);
        String fields="id,payable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,returned_base_qty,debited_amount,debited_tax,version";
        var before=jdbc.queryForList("SELECT "+fields+" FROM goods_receipt_line ORDER BY id");
        var payable=jdbc.queryForList("SELECT * FROM payable");
        Flyway.configure().dataSource(source).target("105").load().migrate();
        assertThat(jdbc.queryForList("SELECT "+fields+" FROM goods_receipt_line ORDER BY id")).isEqualTo(before);
        assertThat(jdbc.queryForList("SELECT * FROM payable")).isEqualTo(payable);
        assertThat(jdbc.queryForList("SELECT pricing_return_basis FROM goods_receipt_line",String.class)).containsOnlyNulls();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM goods_receipt_line WHERE price_adjusted_amount<>0 OR price_adjusted_tax<>0",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(105);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_permission WHERE code LIKE 'price_adjustment:%'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission r JOIN sys_permission p ON p.id=r.permission_id WHERE r.tenant_id=1 AND r.role_id=1 AND p.code LIKE 'price_adjustment:%'",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE tenant_id=2",Integer.class)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE goods_receipt_line SET debited_amount=114 WHERE id=1"))
                .isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->
                        assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->{
                            assertThat(sql.getErrorCode()).isEqualTo(3819);
                            assertThat(sql.getMessage()).contains("ck_goods_receipt_line_debit");
                        }));
    }

    private void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-PRICE','采购调价升级演练')");
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(2,'UPGRADE-RESTRICTED','未取得授权管理权限的演练企业')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','升级管理员'),(2,2,'SYSTEM_ADMIN','受限管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','历史采购公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(1,1,1,'W','历史仓')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史供应商')");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(1,1,1,'supplier')");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(1,1,'件')");
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(1,1,'C','分类','/')");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(1,1,1,'P','历史商品',1)");
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(1,1,1,'S','历史规格')");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(1,1,1,1,1,1)");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive) VALUES(1,1,1,'OLD-PO',1,'2026-11-01',1)");
        jdbc.update("INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount) VALUES(1,1,1,1,1,1,1,1,1,10,10,10,11.3,113,113,113,.13,100,13)");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,period_id,posted_at,payable_amount,untaxed_amount,tax_amount,cost_amount) VALUES(1,1,1,'OLD-GR',1,1,'2026-11-01','posted',1,UTC_TIMESTAMP(3),113,100,13,100)");
        jdbc.update("INSERT INTO goods_receipt_line(id,tenant_id,company_id,receipt_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,payable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,returned_base_qty,debited_amount,debited_tax) VALUES(1,1,1,1,1,1,1,1,1,10,10,113,100,13,10,100,2,22.6,2.6)");
        jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,due_date,period_id) VALUES(1,1,1,1,'charge','goods_receipt_line',1,1,113,100,13,'2026-11-01','2026-12-01',1)");
    }
}
