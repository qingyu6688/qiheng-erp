package cn.erp.portal;

import cn.erp.shared.ManagedJobs;
import cn.erp.shared.PortalTicketNotice;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
class PortalTicketSla {
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    PortalTicketSla(JdbcTemplate jdbc,ApplicationEventPublisher events,PlatformTransactionManager transactionManager,ManagedJobs jobs) {
        this.jdbc=jdbc;
        this.events=events;
        this.transaction=new TransactionTemplate(transactionManager);
        jobs.register("cs.sla",this::notifyTenant);
    }

    private record Due(long id,long company,long user,boolean firstReply,boolean resolution) {}

    void notifyTenant(long tenant) {
        transaction.executeWithoutResult(status -> {
            var due=jdbc.query("""
                    SELECT id,company_id,assignee_id,first_reply_at IS NULL AND first_reply_due_at<=UTC_TIMESTAMP(3)
                      AND first_sla_notified_at IS NULL first_due,sla_due_at<=UTC_TIMESTAMP(3) AND resolution_sla_notified_at IS NULL resolution_due
                    FROM cs_ticket WHERE tenant_id=? AND assignee_id IS NOT NULL AND status NOT IN ('resolved','closed')
                      AND ((first_reply_at IS NULL AND first_reply_due_at<=UTC_TIMESTAMP(3) AND first_sla_notified_at IS NULL)
                        OR (sla_due_at<=UTC_TIMESTAMP(3) AND resolution_sla_notified_at IS NULL))
                    ORDER BY sla_due_at,id LIMIT 100 FOR UPDATE SKIP LOCKED
                    """,(row,n)->new Due(row.getLong("id"),row.getLong("company_id"),row.getLong("assignee_id"),
                    row.getBoolean("first_due"),row.getBoolean("resolution_due")),tenant);
            for(var ticket:due) {
                if(ticket.firstReply()) events.publishEvent(new PortalTicketNotice(tenant,ticket.company(),ticket.user(),ticket.id(),"first_reply"));
                if(ticket.resolution()) events.publishEvent(new PortalTicketNotice(tenant,ticket.company(),ticket.user(),ticket.id(),"resolution"));
                jdbc.update("""
                        UPDATE cs_ticket SET first_sla_notified_at=IF(?,UTC_TIMESTAMP(3),first_sla_notified_at),
                          resolution_sla_notified_at=IF(?,UTC_TIMESTAMP(3),resolution_sla_notified_at) WHERE tenant_id=? AND company_id=? AND id=?
                        """,ticket.firstReply(),ticket.resolution(),tenant,ticket.company(),ticket.id());
            }
        });
    }
}
