package cn.erp.gl;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.*;

import cn.erp.shared.*;

@Component
class GlSyncErrors {
    private final GlSupport s;

    GlSyncErrors(GlSupport s) {
        this.s = s;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(long tenant, long company, long journal, RuntimeException error) {
        String message = describe(tenant, company, journal, error);
        s.jdbc.update(
                "INSERT INTO"
                    + " gl_sync_error(id,tenant_id,company_id,journal_id,error_code,error_message)"
                    + " VALUES(?,?,?,?,'GL_SYNC_FAILED',?) ON DUPLICATE KEY UPDATE"
                    + " status='pending',error_message=?,attempts=attempts+1,version=version+1",
                Ids.next(),
                tenant,
                company,
                journal,
                message,
                message);
    }

    private String describe(long tenant, long company, long journal, RuntimeException error) {
        if (!(error instanceof ApiException)) return "财务凭证生成失败，请按来源编号检查服务日志";
        if (error instanceof GlDimensions.MissingCashFlow && Boolean.TRUE.equals(s.jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM journal j
                    JOIN opening_entry o ON o.tenant_id=j.tenant_id AND o.company_id=j.company_id AND o.id=j.source_id
                    JOIN gl_account_book b ON b.tenant_id=j.tenant_id AND b.company_id=j.company_id
                    JOIN accounting_period p ON p.tenant_id=b.tenant_id AND p.company_id=b.company_id AND p.id=b.start_period_id
                    WHERE j.tenant_id=? AND j.company_id=? AND j.id=? AND j.source_type='opening_entry'
                      AND o.entry_type='cash' AND b.status='active' AND j.business_date>=p.start_date)
                """, Boolean.class, tenant, company, journal))) {
            return "资金期初的业务日期在账套启用期间或之后，当前映射缺少现金流量项目。请财务核对日期和业务性质；启用前现金余额应通过账套期初承接。";
        }
        return error.getMessage();
    }
}
