package cn.erp.gl;

import java.util.Map;

import org.springframework.stereotype.Component;

import cn.erp.jobs.OutboxService;
import cn.erp.shared.FinancialJournalPort;

/** 运营投影只投递独立事件，映射错误不会回滚运营余额。 */
@Component
class GlJournalQueue implements FinancialJournalPort {
    private final GlSupport s;
    private final OutboxService outbox;

    GlJournalQueue(GlSupport s, OutboxService outbox) {
        this.s = s;
        this.outbox = outbox;
    }

    public void projected(long tenant, long company, long journal) {
        if (s.jdbc.queryForObject(
                        "SELECT COUNT(*) FROM gl_account_book b JOIN accounting_period p ON"
                            + " p.tenant_id=b.tenant_id AND p.company_id=b.company_id AND"
                            + " p.id=b.start_period_id JOIN journal j ON j.tenant_id=b.tenant_id"
                            + " AND j.company_id=b.company_id AND j.id=? WHERE b.tenant_id=? AND"
                            + " b.company_id=? AND b.status='active' AND"
                            + " j.business_date>=p.start_date",
                        Long.class,
                        journal,
                        tenant,
                        company)
                == 0) return;
        outbox.publish(
                tenant,
                "gl_journal",
                journal,
                0,
                "gl.journal.requested",
                Map.of("companyId", Long.toString(company), "journalId", Long.toString(journal)));
    }
}
