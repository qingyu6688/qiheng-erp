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
        String message =
                error instanceof ApiException ? error.getMessage() : "财务凭证生成失败，请按来源编号检查服务日志";
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
}
