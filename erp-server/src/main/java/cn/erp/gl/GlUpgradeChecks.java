package cn.erp.gl;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GlUpgradeChecks {
    private final GlSupport support;
    private final GlMappingResolver resolver;
    private final GlDimensions dimensions;

    GlUpgradeChecks(GlSupport support, GlMappingResolver resolver, GlDimensions dimensions) {
        this.support = support;
        this.resolver = resolver;
        this.dimensions = dimensions;
    }

    public List<Map<String, Object>> missingCodes() {
        return support.jdbc.queryForList("""
                SELECT l.tenant_id,l.company_id,l.account_code,COUNT(*) line_count
                FROM journal_line l
                WHERE NOT EXISTS (
                    SELECT 1 FROM gl_business_mapping m
                    JOIN gl_subject s ON s.tenant_id=m.tenant_id AND s.company_id=m.company_id AND s.id=m.subject_id
                    JOIN gl_account_book b ON b.tenant_id=m.tenant_id AND b.company_id=m.company_id
                    WHERE m.tenant_id=l.tenant_id AND m.company_id=l.company_id
                      AND m.ledger_account_code=l.account_code AND m.enabled=1
                      AND s.status='active' AND s.is_leaf=1 AND b.status='active'
                ) GROUP BY l.tenant_id,l.company_id,l.account_code
                ORDER BY l.tenant_id,l.company_id,l.account_code
                """);
    }

    void requireJournal(ActorScope actor, long id, boolean historical,
                        List<Map<String, Object>> mappings) {
        var journal = support.jdbc.queryForMap("SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        if (!historical) {
            var book = support.active(actor);
            var first = support.period(actor, GlSupport.number(book.get("start_period_id")), false);
            if (GlSupport.date(journal.get("business_date")).isBefore(GlSupport.date(first.get("start_date"))))
                throw new ApiException(422, "GL_MAPPING_REQUIRED", "过账日期早于本公司财务账套的启用日期");
        }
        for (var line : support.jdbc.queryForList("""
                SELECT * FROM journal_line WHERE tenant_id=? AND company_id=? AND journal_id=? ORDER BY line_no
                """, actor.tenantId(), actor.companyId(), id)) {
            var mapped = resolver.resolve(actor, journal, line, mappings, historical).line();
            var subject = dimensions.subject(actor, mapped.subjectId(), historical);
            dimensions.cashFlow(actor, subject, mapped.cashFlowItemId(), historical);
        }
    }

    public List<Map<String, Object>> invalidJournals(ActorScope actor) {
        var mappings = resolver.mappings(actor);
        var failures = new java.util.ArrayList<Map<String, Object>>();
        long cursor = 0;
        while (true) {
            var ids = support.jdbc.queryForList("""
                    SELECT j.id FROM journal j JOIN gl_account_book b
                      ON b.tenant_id=j.tenant_id AND b.company_id=j.company_id AND b.status='active'
                    JOIN accounting_period p
                      ON p.tenant_id=b.tenant_id AND p.company_id=b.company_id AND p.id=b.start_period_id
                    WHERE j.tenant_id=? AND j.company_id=? AND j.id>? AND j.business_date>=p.start_date
                    ORDER BY j.id LIMIT 500
                    """, Long.class, actor.tenantId(), actor.companyId(), cursor);
            if (ids.isEmpty()) break;
            for (long id : ids) {
                try {
                    requireJournal(actor, id, true, mappings);
                } catch (ApiException invalid) {
                    failures.add(Map.of("tenantId", actor.tenantId(), "companyId", actor.companyId(),
                            "journalId", id, "reason", invalid.getMessage()));
                }
            }
            cursor = ids.getLast();
        }
        return failures;
    }
}
