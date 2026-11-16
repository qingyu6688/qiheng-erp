package cn.erp.ar;

import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;

import com.zaxxer.hikari.*;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.shared.ActorScope;

/** 独立小池仅从主库读取一个已提交快照，避免外层持有额度锁时耗尽业务连接池。 */
@Component
class CreditSnapshot {
    private final HikariDataSource pool;
    private final NamedParameterJdbcTemplate jdbc;

    CreditSnapshot(DataSource main) throws SQLException {
        var source = main.unwrap(HikariDataSource.class);
        var config = new HikariConfig();
        config.setPoolName("ERPCreditSnapshot");
        config.setJdbcUrl(source.getJdbcUrl());
        config.setUsername(source.getUsername());
        config.setPassword(source.getPassword());
        config.setDataSourceProperties(source.getDataSourceProperties());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setInitializationFailTimeout(-1);
        config.setReadOnly(true);
        config.setConnectionTimeout(10000);
        config.setIdleTimeout(30000);
        config.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        pool = new HikariDataSource(config);
        jdbc = new NamedParameterJdbcTemplate(pool);
    }

    Map<String, Object> read(ActorScope a, long customer, long excludeOrder) {
        return jdbc.queryForMap(
                """
WITH policy AS(SELECT * FROM ar_credit_limit WHERE tenant_id=:t AND company_id=:c AND partner_id=:p),
items AS(SELECT COALESCE(SUM(IF(p.item_type='receivable',IF(p.direction='D',p.open_amount,-p.open_amount),-p.open_amount)),0) amount,
  COALESCE(MAX(IF(p.item_type='receivable' AND p.direction='D' AND p.open_amount>0,DATEDIFF(:today,p.aging_date),0)),0) overdue_days
  FROM partner_open_item p WHERE p.tenant_id=:t AND p.company_id=:c AND p.partner_id=:p AND (p.item_type='receivable' OR p.item_type='receipt' AND EXISTS(SELECT 1 FROM receipt r WHERE r.tenant_id=p.tenant_id AND r.company_id=p.company_id AND r.id=p.source_id AND r.receipt_type='customer'))),
commitments AS(SELECT COALESCE(SUM(GREATEST(l.receivable_net-l.recognized_amount-ROUND(l.receivable_net*l.closed_base_qty/l.base_qty,2),0)),0) amount
  FROM sales_order o JOIN sales_order_line l ON l.tenant_id=o.tenant_id AND l.company_id=o.company_id AND l.order_id=o.id AND l.is_deleted=0 WHERE o.tenant_id=:t AND o.company_id=:c AND o.customer_id=:p AND o.id<>:exclude AND o.status IN('pending','approved','closed','change_pending')),
fees AS(SELECT COALESCE(SUM(f.amount),0) amount FROM sales_order o JOIN sales_order_fee f ON f.tenant_id=o.tenant_id AND f.company_id=o.company_id AND f.order_id=o.id WHERE o.tenant_id=:t AND o.company_id=:c AND o.customer_id=:p AND o.id<>:exclude AND o.status IN('pending','approved','closed','change_pending') AND f.bearer='buyer' AND f.recognized_shipment_id IS NULL),
increases AS(SELECT COALESCE(SUM(amount),0) amount FROM credit_increase WHERE tenant_id=:t AND company_id=:c AND partner_id=:p AND status='approved' AND :today BETWEEN valid_from AND valid_to)
SELECT policy.*,items.amount receivables,items.overdue_days,commitments.amount+fees.amount committed,increases.amount increase_amount FROM policy CROSS JOIN items CROSS JOIN commitments CROSS JOIN fees CROSS JOIN increases
""",
                Map.of(
                        "t",
                        a.tenantId(),
                        "c",
                        a.companyId(),
                        "p",
                        customer,
                        "exclude",
                        excludeOrder,
                        "today",
                        ArSupport.today()));
    }

    @PreDestroy
    void close() {
        pool.close();
    }
}
