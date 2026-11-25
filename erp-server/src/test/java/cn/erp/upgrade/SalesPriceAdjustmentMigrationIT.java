package cn.erp.upgrade;

import java.util.Objects;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** 销售价格扩展不改原签收、成本、应收及旧开票分配。 */
@EnabledIfEnvironmentVariable(named="ERP_SALES_PRICE_UPGRADE_DB",matches="erp_test_sales_price_upgrade_[a-z0-9]+")
class SalesPriceAdjustmentMigrationIT {
    @Test void upgradePreservesSignedFactsAndAddsPriceCountersWithoutRepricingHistory() {
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");assertThat(host).isIn("127.0.0.1","localhost");
        String url="jdbc:mysql://"+host+":"+System.getenv("DB_PORT")+"/"+Objects.requireNonNull(System.getenv("ERP_SALES_PRICE_UPGRADE_DB"))+"?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var data=new DriverManagerDataSource(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));var jdbc=new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(data).target("108").load().migrate();legacy(jdbc);
        String columns="id,qty,base_qty,signed_base_qty,returned_base_qty,receivable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,signed_cost_amount,credited_amount,credited_tax,version,created_at,updated_at";
        var source=jdbc.queryForList("SELECT "+columns+" FROM shipment_line ORDER BY id");
        var shipments=jdbc.queryForList("SELECT * FROM shipment ORDER BY id");
        var ar=jdbc.queryForList("SELECT * FROM receivable ORDER BY id");
        var invoices=jdbc.queryForList("SELECT * FROM invoice_registration ORDER BY id");
        var lines=jdbc.queryForList("SELECT * FROM invoice_registration_line ORDER BY id");
        Flyway.configure().dataSource(data).target("109").load().migrate();
        assertThat(jdbc.queryForList("SELECT "+columns+" FROM shipment_line ORDER BY id")).isEqualTo(source);
        assertThat(jdbc.queryForList("SELECT * FROM shipment ORDER BY id")).isEqualTo(shipments);
        assertThat(jdbc.queryForList("SELECT * FROM receivable ORDER BY id")).isEqualTo(ar);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_registration ORDER BY id")).isEqualTo(invoices);
        assertThat(jdbc.queryForList("SELECT * FROM invoice_registration_line ORDER BY id")).isEqualTo(lines);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_line WHERE price_adjusted_amount=0 AND price_adjusted_tax=0 AND pricing_return_basis IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1 AND permission_id BETWEEN 18600 AND 18606",Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2 AND permission_id BETWEEN 18600 AND 18606",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT character_maximum_length FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='price_adjustment' AND column_name='adjust_no'",Integer.class)).isEqualTo(64);
        var transaction=new TransactionTemplate(new DataSourceTransactionManager(data));
        transaction.executeWithoutResult(status->{
            jdbc.update("UPDATE shipment_line SET price_adjusted_amount=22.60,price_adjusted_tax=2.60,returned_base_qty=10,credited_amount=135.60,credited_tax=15.60 WHERE id=1");
            rejects(()->jdbc.update("UPDATE shipment_line SET credited_amount=135.61 WHERE id=1"));
            rejects(()->jdbc.update("UPDATE shipment_line SET price_adjusted_amount=-200 WHERE id=1"));
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForList("SELECT "+columns+" FROM shipment_line ORDER BY id")).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(109);
    }
    private static void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-SALE-PRICE','销售价格升级'),(2,'UPGRADE-SALE-LIMITED','受限角色升级')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','完整管理员'),(2,2,'SYSTEM_ADMIN','受限管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','历史销售公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(1,1,1,'W','历史发货仓')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史客户')");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(1,1,'件')");
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(1,1,'C','历史分类','/')");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(1,1,1,'P','历史商品',1)");
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(1,1,1,'S','历史规格')");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(1,1,1,1,1,1)");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount,status,fulfillment_status,revision,approved_by,approved_at) VALUES(1,1,1,'OLD-SO',1,'2026-11-01',1,113,113,100,13,'approved','full',1,2,'2026-11-01 01:00:00')");
        jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount,shipped_base_qty,signed_base_qty,recognized_amount,recognized_tax) VALUES(1,1,1,1,1,1,1,1,1,10,10,11.3,113,113,113,.13,100,13,10,10,113,13)");
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,receivable_amount,untaxed_amount,tax_amount,cost_amount,signed_cost_amount,period_id,posted_at,sign_date,sign_period_id,signed_at) VALUES(1,1,1,'OLD-SH',1,1,'2026-11-01','signed',113,100,13,50,50,1,'2026-11-01 02:00:00','2026-11-01',1,'2026-11-01 03:00:00')");
        jdbc.update("INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,signed_base_qty,receivable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,signed_cost_amount,version,created_at,updated_at) VALUES(1,1,1,1,1,1,1,1,1,10,10,10,113,100,13,5,50,50,7,'2026-11-01 01:02:03.123','2026-11-02 04:05:06.789')");
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,invoiced_amount,business_date,due_date,period_id,version) VALUES(1,1,1,1,'charge','shipment_line',1,1,113,100,13,50,'2026-11-01','2026-12-01',1,9)");
        jdbc.update("INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,amount,tax_amount,version,created_at,updated_at) VALUES(1,1,1,'sales',1,'OLD-INVOICE','2026-11-01',50,5.75,3,'2026-11-01 05:06:07.123','2026-11-02 05:06:07.456')");
        jdbc.update("INSERT INTO invoice_registration_line(id,tenant_id,company_id,invoice_id,receivable_id,amount) VALUES(1,1,1,1,1,50)");
    }
    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->
                assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->{assertThat(sql.getErrorCode()).isEqualTo(3819);assertThat(sql.getMessage()).contains("ck_shipment_line_credit");}));
    }
}
