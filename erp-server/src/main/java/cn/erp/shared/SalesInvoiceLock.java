package cn.erp.shared;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 销项额度的写入先取得公司锁，避免蓝字预留、红字释放与销售折让相互穿插。 */
@Component
public class SalesInvoiceLock {
    private final JdbcTemplate jdbc;
    public SalesInvoiceLock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lock(ActorScope actor) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM company WHERE tenant_id=? AND id=?", Integer.class,
                actor.tenantId(), actor.companyId()) != 1)
            throw new ApiException(404, "NOT_FOUND", "公司不存在或无权访问");
        jdbc.update("INSERT INTO sales_invoice_scope_lock(tenant_id,company_id) VALUES(?,?) ON DUPLICATE KEY UPDATE company_id=company_id",
                actor.tenantId(), actor.companyId());
        jdbc.queryForObject("SELECT company_id FROM sales_invoice_scope_lock WHERE tenant_id=? AND company_id=? FOR UPDATE",
                Long.class, actor.tenantId(), actor.companyId());
    }
}
