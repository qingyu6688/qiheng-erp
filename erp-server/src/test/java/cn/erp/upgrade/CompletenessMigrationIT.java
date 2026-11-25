package cn.erp.upgrade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

/** 只在调用方显式新建的隔离空库中比较V110原字段，不清理任何已有数据库。 */
@EnabledIfEnvironmentVariable(named="ERP_COMPLETENESS_UPGRADE_DB",matches="erp_test_complete_upgrade_[a-z0-9]+")
class CompletenessMigrationIT {
    private static final List<String> FACTS=List.of("sales_return","sales_return_line","refund_request","receipt","receivable",
            "shipment","shipment_line","sales_order","sales_order_line","purchase_order","purchase_order_line","company_policy",
            "price_list","price_list_item","price_list_target","inventory_cost","inventory_movement","cost_adjustment");

    @Test void upgradeKeepsLegacyFactsAndIntroducesScopedTasksAndPriceRules() {
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");
        assertThat(host).isIn("localhost","127.0.0.1");
        assertThat(System.getenv("DB_PORT")).isEqualTo("13331");
        String database=Objects.requireNonNull(System.getenv("ERP_COMPLETENESS_UPGRADE_DB"));
        var source=new DriverManagerDataSource("jdbc:mysql://"+host+":13331/"+database+"?connectionTimeZone=UTC&characterEncoding=UTF-8",
                System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(source).target("110").load().migrate();
        SourcelessReturnMigrationIT.legacy(jdbc);
        legacyPurchaseAndCost(jdbc);
        var columns=new LinkedHashMap<String,String>();
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:FACTS) {
            String names=String.join(",",jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ORDINAL_POSITION",String.class,table));
            columns.put(table,names);before.put(table,jdbc.queryForList("SELECT "+names+" FROM "+table+" ORDER BY id"));
        }
        Flyway.configure().dataSource(source).target("113").load().migrate();
        unchanged(jdbc,columns,before);
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(113);
        assertThat(jdbc.queryForObject("SELECT purchase_over_price_policy FROM company_policy WHERE id=1",String.class)).isEqualTo("warn");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order WHERE above_price_list=0 AND purchase_price_approval_required=0 AND purchase_price_hash IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_line WHERE price_source_snapshot IS NULL",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1 AND permission_id BETWEEN 18800 AND 19006",Integer.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2 AND permission_id BETWEEN 18800 AND 19006",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT schedule FROM job_definition WHERE tenant_id=0 AND code='sales.quote-expiry'",String.class)).isEqualTo("interval:60000");
        for(String table:List.of("cost_revaluation","cost_revaluation_line","sales_quote","sales_quote_line"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
        new TransactionTemplate(new DataSourceTransactionManager(source)).executeWithoutResult(status->{
            constraints(jdbc);status.setRollbackOnly();
        });
        unchanged(jdbc,columns,before);
    }

    private static void legacyPurchaseAndCost(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(1,1,1,'supplier')");
        jdbc.update("INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount) VALUES(1,1,1,'OLD-PO',1,'2026-11-01',1,22.60,22.60,20,2.60)");
        jdbc.update("INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount,version) VALUES(1,1,1,1,1,1,1,1,1,2,2,2,11.3,22.60,22.60,22.60,.13,20,2.60,4)");
        jdbc.update("INSERT INTO price_list(id,tenant_id,company_id,name,price_includes_tax,valid_from,valid_to,priority,status) VALUES(1,1,1,'历史客户协议',1,'2026-01-01','2026-12-31',3,'active')");
        jdbc.update("INSERT INTO price_list_item(id,tenant_id,company_id,price_list_id,sku_id,unit_id,min_qty,unit_price) VALUES(1,1,1,1,1,1,0,11.3)");
        jdbc.update("INSERT INTO price_list_target(id,tenant_id,company_id,price_list_id,target_type,target_value) VALUES(1,1,1,1,'customer','1')");
        jdbc.update("INSERT INTO inventory_cost(id,tenant_id,company_id,warehouse_id,sku_id,qty,total_cost,last_in_unit_cost,version) VALUES(1,1,1,1,1,2,23,10,5)");
        jdbc.update("INSERT INTO inventory_movement(id,tenant_id,company_id,warehouse_id,sku_id,movement_type,on_hand_delta,unit_cost,cost_delta,source_type,source_id,source_line_id,business_date,period_id,posted_at) VALUES(1,1,1,1,1,'opening_in',2,10,20,'legacy_line',1,1,'2026-11-01',1,'2026-11-01 01:00:00')");
        jdbc.update("INSERT INTO cost_adjustment(id,tenant_id,company_id,warehouse_id,sku_id,adjust_type,amount,source_type,source_id,source_line_id,business_date,period_id) VALUES(1,1,1,1,1,'revaluation',3,'legacy_line',1,1,'2026-11-01',1)");
    }
    private static void constraints(JdbcTemplate jdbc) {
        rejects(()->jdbc.update("UPDATE company_policy SET purchase_over_price_policy='block' WHERE id=1"),3819);
        jdbc.update("INSERT INTO price_list(id,tenant_id,company_id,name,price_includes_tax,valid_from,valid_to,priority,status) VALUES(2,1,1,'供应商协议',1,'2026-01-01','2026-12-31',3,'active')");
        jdbc.update("INSERT INTO price_list_target(id,tenant_id,company_id,price_list_id,target_type,target_value) VALUES(2,1,1,2,'supplier','1')");
        jdbc.update("INSERT INTO cost_revaluation(id,tenant_id,company_id,document_no,warehouse_id,sku_id,start_date,business_date,reason,preview_hash,inventory_version,quantity,before_cost,after_cost,stock_delta,event_count,owner_user_id,created_by,updated_by) VALUES(1,1,1,'CHECK-CR',1,1,'2026-11-01','2026-11-24','约束演练',REPEAT('a',64),5,2,23,24,1,1,1,1,1)");
        rejects(()->jdbc.update("UPDATE cost_revaluation SET status='approved',approved_by=1,approved_at=UTC_TIMESTAMP(3) WHERE id=1"),3819);
        jdbc.update("UPDATE cost_revaluation SET status='approved',approved_by=2,approved_at=UTC_TIMESTAMP(3) WHERE id=1");
        rejects(()->jdbc.update("UPDATE cost_revaluation SET stock_delta=2 WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE cost_revaluation SET company_id=2 WHERE id=1"),1452);
        jdbc.update("INSERT INTO sales_quote(id,tenant_id,company_id,document_no,customer_id,business_date,valid_to,price_tax_inclusive,total_amount,untaxed_amount,tax_amount,order_input,pricing_snapshot,owner_user_id,created_by,updated_by) VALUES(1,1,1,'CHECK-SQ',1,'2026-11-24','2026-12-31',1,11.3,10,1.3,'{}','{}',1,1,1)");
        rejects(()->jdbc.update("UPDATE sales_quote SET status='converted' WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE sales_quote SET valid_to='2026-11-01' WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE sales_quote SET company_id=999 WHERE id=1"),1452);
    }
    private static void unchanged(JdbcTemplate jdbc,Map<String,String> columns,Map<String,List<Map<String,Object>>> before) {
        for(String table:FACTS)assertThat(jdbc.queryForList("SELECT "+columns.get(table)+" FROM "+table+" ORDER BY id"))
                .as("V110原字段保持：%s",table).isEqualTo(before.get(table));
    }
    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->
                assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->assertThat(sql.getErrorCode()).isEqualTo(code)));
    }
}
