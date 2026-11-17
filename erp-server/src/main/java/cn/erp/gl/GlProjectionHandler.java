package cn.erp.gl;

import org.springframework.stereotype.Component;

import cn.erp.jobs.*;

import static cn.erp.gl.GlSupport.number;

@Component
class GlProjectionHandler implements EventHandler {
    private final GlSupport s;
    private final GlSyncService sync;
    private final GlSyncErrors errors;

    GlProjectionHandler(GlSupport s, GlSyncService sync, GlSyncErrors errors) {
        this.s = s;
        this.sync = sync;
        this.errors = errors;
    }

    public String eventType() {
        return "gl.journal.requested";
    }

    public String consumer() {
        return "gl_voucher";
    }

    public void handle(OutboxService.Event event) {
        var rows =
                s.jdbc.queryForList(
                        "SELECT company_id FROM journal WHERE tenant_id=? AND id=?",
                        event.tenantId(),
                        event.aggregateId());
        if (rows.isEmpty()) throw GlSupport.missing();
        long company = number(rows.getFirst().get("company_id"));
        try {
            sync.project(event.tenantId(), company, event.aggregateId());
        } catch (RuntimeException error) {
            errors.save(event.tenantId(), company, event.aggregateId(), error);
            throw error;
        }
    }
}
