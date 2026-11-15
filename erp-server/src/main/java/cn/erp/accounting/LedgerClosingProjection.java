package cn.erp.accounting;

import cn.erp.identity.CompanyAccess;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.Ids;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 关账前补齐已提交凭证投影，与后台消费者共用收件去重。 */
@Service
public class LedgerClosingProjection {
    private final JdbcTemplate jdbc;
    private final LedgerProjectionHandler handler;
    public LedgerClosingProjection(JdbcTemplate jdbc,LedgerProjectionHandler handler) {this.jdbc=jdbc;this.handler=handler;}

    public List<Long> pending(CompanyAccess scope) {
        return jdbc.queryForList("""
            SELECT e.id FROM outbox_event e JOIN journal j ON j.tenant_id=e.tenant_id AND j.id=e.aggregate_id
            WHERE j.tenant_id=? AND j.company_id=? AND e.event_type='journal.posted'
              AND NOT EXISTS(SELECT 1 FROM inbox_event i WHERE i.consumer='ledger_projection' AND i.event_id=e.id)
            ORDER BY e.id
            """,Long.class,scope.tenantId(),scope.companyId());
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void project(CompanyAccess scope,long eventId) {
        var rows=jdbc.queryForList("""
            SELECT e.* FROM outbox_event e JOIN journal j ON j.tenant_id=e.tenant_id AND j.id=e.aggregate_id
            WHERE e.id=? AND e.tenant_id=? AND j.company_id=? AND e.event_type='journal.posted' FOR UPDATE OF e
            """,eventId,scope.tenantId(),scope.companyId());
        if(rows.isEmpty()) {return;}
        if(!jdbc.queryForList("SELECT id FROM inbox_event WHERE consumer='ledger_projection' AND event_id=? FOR UPDATE",Long.class,eventId).isEmpty()) {return;}
        var row=rows.getFirst();
        jdbc.update("INSERT INTO inbox_event(id,tenant_id,consumer,event_id) VALUES(?,?,'ledger_projection',?)",Ids.next(),scope.tenantId(),eventId);
        handler.handle(new OutboxService.Event(eventId,scope.tenantId(),row.get("aggregate_type").toString(),((Number)row.get("aggregate_id")).longValue(),
            ((Number)row.get("aggregate_version")).intValue(),"journal.posted",row.get("payload").toString(),null,((Number)row.get("attempts")).intValue()));
    }
}
