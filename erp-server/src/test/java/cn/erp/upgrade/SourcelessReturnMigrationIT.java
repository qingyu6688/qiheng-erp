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

/** 先保留真实旧表字段，再验证无源模式不能绕过来源、审批及退款约束。 */
@EnabledIfEnvironmentVariable(named="ERP_SOURCELESS_UPGRADE_DB",matches="erp_test_sourceless_upgrade_[a-z0-9]+")
class SourcelessReturnMigrationIT {
    private static final List<String> FACTS=List.of("sales_return","sales_return_line","refund_request",
            "receipt","receivable","shipment","shipment_line","sales_order","sales_order_line");

    @Test void upgradeKeepsLinkedReturnAndRefundFactsAndSeparatesSourcelessModes() {
        String host=System.getenv().getOrDefault("DB_HOST","127.0.0.1");
        assertThat(host).isIn("127.0.0.1","localhost");
        String url="jdbc:mysql://"+host+":"+System.getenv("DB_PORT")+"/"
                +Objects.requireNonNull(System.getenv("ERP_SOURCELESS_UPGRADE_DB"))+"?connectionTimeZone=UTC&characterEncoding=UTF-8";
        var data=new DriverManagerDataSource(url,System.getenv("TEST_DB_USER"),System.getenv("TEST_DB_PASSWORD"));
        var jdbc=new JdbcTemplate(data);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",Integer.class)).isZero();
        Flyway.configure().dataSource(data).target("109").load().migrate();
        legacy(jdbc);
        var columns=new LinkedHashMap<String,String>();
        var before=new LinkedHashMap<String,List<Map<String,Object>>>();
        for(String table:FACTS) {
            String names=String.join(",",jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=? ORDER BY ORDINAL_POSITION",String.class,table));
            columns.put(table,names);
            before.put(table,jdbc.queryForList("SELECT "+names+" FROM "+table+" ORDER BY id"));
        }
        Flyway.configure().dataSource(data).target("110").load().migrate();
        for(String table:FACTS) assertThat(jdbc.queryForList("SELECT "+columns.get(table)+" FROM "+table+" ORDER BY id"))
                .as("旧表字段保持：%s",table).isEqualTo(before.get(table));
        assertThat(jdbc.queryForObject("SELECT return_mode FROM sales_return WHERE id=1",String.class)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT return_mode FROM sales_return_line WHERE id=1",String.class)).isEqualTo("original");
        assertThat(jdbc.queryForObject("SELECT refund_mode FROM refund_request WHERE id=1",String.class)).isEqualTo("receipt");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_return WHERE approved_by IS NULL AND approved_at IS NULL AND submitted_by IS NULL AND wf_instance_id=0",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=1 AND permission_id BETWEEN 18700 AND 18701",Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=2 AND permission_id BETWEEN 18700 AND 18701",Integer.class)).isZero();
        new TransactionTemplate(new DataSourceTransactionManager(data)).executeWithoutResult(status->{
            returnConstraints(jdbc);
            refundConstraints(jdbc);
            status.setRollbackOnly();
        });
        for(String table:FACTS) assertThat(jdbc.queryForList("SELECT "+columns.get(table)+" FROM "+table+" ORDER BY id"))
                .as("约束验收回滚：%s",table).isEqualTo(before.get(table));
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1",Integer.class)).isEqualTo(110);
    }

    private static void returnConstraints(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,return_mode,created_by) VALUES(11,1,1,'NO-SOURCE',1,1,'2026-11-24','sourceless',1)");
        jdbc.update("INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty,return_mode,unit_price,tax_rate) VALUES(11,1,1,11,1,1,1,1,1,1,'sourceless',11.3,.13)");
        rejects(()->jdbc.update("UPDATE sales_return_line SET shipment_line_id=NULL WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE sales_return_line SET shipment_line_id=1 WHERE id=11"),3819);
        rejects(()->jdbc.update("INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,unit_rate,qty,base_qty) VALUES(12,1,1,11,2,1,1,1,1,1,1)"),1452);
        rejects(()->jdbc.update("UPDATE sales_return_line SET tax_rate=1.01 WHERE id=11"),3819);
        rejects(()->jdbc.update("UPDATE sales_return_line SET manual_unit_cost=-1,manual_cost_reason='无历史成本' WHERE id=11"),3819);
        rejects(()->jdbc.update("UPDATE sales_return_line SET manual_unit_cost=0,manual_cost_reason=' ' WHERE id=11"),3819);
        rejects(()->jdbc.update("UPDATE sales_return_line SET cost_basis='{}' WHERE id=11"),3819);
        jdbc.update("UPDATE sales_return_line SET manual_unit_cost=0,manual_cost_reason='历史确认零成本',unit_cost=0,cost_amount=0,cost_basis='{\"source\":\"manual\"}' WHERE id=11");
        rejects(()->jdbc.update("UPDATE sales_return SET status='submitted' WHERE id=11"),3819);
        rejects(()->jdbc.update("UPDATE sales_return SET status='approved' WHERE id=11"),3819);
        rejects(()->jdbc.update("UPDATE sales_return SET status='approved',submitted_by=1,submitted_at=UTC_TIMESTAMP(3),approved_by=1,approved_at=UTC_TIMESTAMP(3) WHERE id=11"),3819);
        jdbc.update("UPDATE sales_return SET status='approved',submitted_by=1,submitted_at=UTC_TIMESTAMP(3),approved_by=2,approved_at=UTC_TIMESTAMP(3) WHERE id=11");
        rejects(()->jdbc.update("UPDATE sales_return SET status='posted' WHERE id=11"),3819);
        jdbc.update("UPDATE sales_return SET status='posted',posted_by=2,posted_at=UTC_TIMESTAMP(3),period_id=1 WHERE id=11");
        rejects(()->jdbc.update("UPDATE sales_return SET return_mode='original' WHERE id=11"),1451);
        jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,status) VALUES(12,1,2,'LINKED-OTHER-COMPANY',1,2,'2026-11-24','submitted')");
        rejects(()->jdbc.update("INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,unit_rate,qty,base_qty) VALUES(12,1,2,12,1,1,1,1,1,1,1)"),1452);
    }

    private static void refundConstraints(JdbcTemplate jdbc) {
        rejects(()->jdbc.update("UPDATE refund_request SET original_receipt_id=NULL WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE refund_request SET refund_mode='sourceless_credit',credit_item_ids='[1]' WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE refund_request SET refund_mode='sourceless_credit',original_receipt_id=NULL,credit_item_ids='[]' WHERE id=1"),3819);
        rejects(()->jdbc.update("UPDATE refund_request SET refund_mode='sourceless_credit',original_receipt_id=NULL,credit_item_ids='{}' WHERE id=1"),3819);
        jdbc.update("UPDATE refund_request SET refund_mode='sourceless_credit',original_receipt_id=NULL,credit_item_ids='[1]' WHERE id=1");
        rejects(()->jdbc.update("UPDATE refund_request SET refund_mode='receipt' WHERE id=1"),3819);
        jdbc.update("UPDATE refund_request SET refund_mode='receipt',original_receipt_id=1 WHERE id=1");
        rejects(()->jdbc.update("INSERT INTO refund_request(id,tenant_id,company_id,refund_no,customer_id,original_receipt_id,cash_account_id,amount,business_date,request_key,reason) VALUES(2,1,2,'FOREIGN-RECEIPT',1,1,2,1,'2026-11-24','foreign-receipt','验证原收款范围')"),1452);
    }

    static void legacy(JdbcTemplate jdbc) {
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(1,'UPGRADE-SOURCELESS','无源退货升级'),(2,'UPGRADE-SOURCELESS-LIMITED','受限授权升级')");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(1,1,'SYSTEM_ADMIN','完整管理员'),(2,2,'SYSTEM_ADMIN','受限管理员')");
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT 1,1,1,id FROM sys_permission WHERE code='role:assign_permission'");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(1,1,'A','历史销售公司'),(2,1,'B','其他公司')");
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(1,1,1)");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(1,1,1,'W','历史仓'),(2,1,2,'W','其他公司仓')");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(1,1,'P','历史客户')");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(1,1,'件')");
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(1,1,'C','历史分类','/')");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(1,1,1,'P','历史商品',1)");
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(1,1,1,'S','历史规格')");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(1,1,1,1,1,1)");
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(1,1,1,'202611','2026-11-01','2026-11-30')");
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount,status,fulfillment_status,revision,approved_by,approved_at) VALUES(1,1,1,'OLD-SO',1,'2026-11-01',1,113,113,100,13,'approved','full',1,2,'2026-11-01 01:00:00')");
        jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,tax_rate,untaxed_amount,tax_amount,shipped_base_qty,signed_base_qty,returned_base_qty,recognized_amount,recognized_tax) VALUES(1,1,1,1,1,1,1,1,1,10,10,11.3,113,113,113,.13,100,13,10,10,2,113,13)");
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,receivable_amount,untaxed_amount,tax_amount,cost_amount,signed_cost_amount,period_id,posted_at,sign_date,sign_period_id,signed_at) VALUES(1,1,1,'OLD-SH',1,1,'2026-11-01','signed',113,100,13,50,50,1,'2026-11-01 02:00:00','2026-11-01',1,'2026-11-01 03:00:00')");
        jdbc.update("INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,signed_base_qty,returned_base_qty,receivable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount,signed_cost_amount,credited_amount,credited_tax,version) VALUES(1,1,1,1,1,1,1,1,1,10,10,10,2,113,100,13,5,50,50,22.60,2.60,7)");
        jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,status,credit_amount,untaxed_amount,tax_amount,cost_amount,period_id,posted_by,posted_at,created_by,version,created_at,updated_at) VALUES(1,1,1,'OLD-RETURN',1,1,'2026-11-02','posted',22.60,20,2.60,10,1,2,'2026-11-02 01:02:03.123',1,8,'2026-11-01 01:02:03.123','2026-11-02 04:05:06.789')");
        jdbc.update("INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,unit_rate,qty,base_qty,quality_result,unit_cost,cost_amount,credit_amount,untaxed_amount,tax_amount,version) VALUES(1,1,1,1,1,1,1,1,1,2,2,'passed',5,10,22.60,20,2.60,9)");
        jdbc.update("INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,due_date,version) VALUES(1,1,1,1,'credit','sales_return_line',1,1,22.60,20,2.60,'2026-11-02',1,'2026-12-01',6)");
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(1,1,1,'B','历史银行','bank'),(2,1,2,'B','其他公司银行','bank')");
        jdbc.update("INSERT INTO receipt(id,tenant_id,company_id,receipt_no,receipt_type,partner_id,cash_account_id,amount,business_date,status,period_id,confirmed_at,confirmed_by,created_by,refunded_amount,refund_pending_amount,version) VALUES(1,1,1,'OLD-RECEIPT','customer',1,1,100,'2026-11-01','confirmed',1,'2026-11-01 08:00:00',2,1,10,5,4)");
        jdbc.update("INSERT INTO refund_request(id,tenant_id,company_id,refund_no,customer_id,original_receipt_id,cash_account_id,amount,business_date,request_key,reason,created_by,credit_item_ids,version,created_at,updated_at) VALUES(1,1,1,'OLD-REFUND',1,1,1,5,'2026-11-02','old-refund','历史退款草稿',1,'[1]',3,'2026-11-02 02:03:04.123','2026-11-02 05:06:07.789')");
    }

    private static void rejects(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,int code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(org.springframework.dao.DataAccessException.class,error->
                assertThat(error.getCause()).isInstanceOfSatisfying(java.sql.SQLException.class,sql->assertThat(sql.getErrorCode()).isEqualTo(code)));
    }
}
