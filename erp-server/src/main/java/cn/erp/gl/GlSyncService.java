package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.shared.*;

import static cn.erp.gl.GlSupport.*;

@Service
public class GlSyncService {
    private final GlSupport s;
    private final GlPostingEngine engine;
    private final GlMappingResolver mappings;

    GlSyncService(GlSupport s, GlPostingEngine engine, GlMappingResolver mappings) {
        this.s = s;
        this.engine = engine;
        this.mappings = mappings;
    }

    public List<Long> pending(ActorScope a, long period) {
        return s.jdbc.queryForList(
                "SELECT j.id FROM journal j JOIN gl_account_book b ON b.tenant_id=j.tenant_id AND"
                    + " b.company_id=j.company_id AND b.status='active' JOIN accounting_period p ON"
                    + " p.tenant_id=b.tenant_id AND p.company_id=b.company_id AND"
                    + " p.id=b.start_period_id WHERE j.tenant_id=? AND j.company_id=? AND"
                    + " j.business_date>=p.start_date AND (?=0 OR j.period_id=?) AND NOT"
                    + " EXISTS(SELECT 1 FROM gl_voucher v WHERE v.tenant_id=j.tenant_id AND"
                    + " v.company_id=j.company_id AND v.journal_id=j.id) ORDER BY"
                    + " j.business_date,j.id LIMIT 1000",
                Long.class,
                a.tenantId(),
                a.companyId(),
                period,
                period);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public long project(long tenant, long company, long journal) {
        ActorScope a =
                new ActorScope() {
                    public long tenantId() {
                        return tenant;
                    }

                    public long companyId() {
                        return company;
                    }

                    public long userId() {
                        return 0;
                    }
                };
        var journals =
                s.jdbc.queryForList(
                        "SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND id=?",
                        tenant,
                        company,
                        journal);
        if (journals.isEmpty()) throw missing();
        var j = journals.getFirst();
        var book = s.bookOrNull(a);
        if (book == null || !"active".equals(book.get("status"))) return 0;
        var p = s.period(a, number(book.get("start_period_id")), false);
        if (date(j.get("business_date")).isBefore(date(p.get("start_date")))) return 0;
        s.openPeriod(a, date(j.get("business_date")));
        s.book(a, true);
        s.active(a);
        s.lockPosting(a);
        var existing =
                s.jdbc.queryForList(
                        "SELECT id FROM gl_voucher WHERE tenant_id=? AND company_id=? AND"
                                + " journal_id=?",
                        Long.class,
                        tenant,
                        company,
                        journal);
        if (!existing.isEmpty()) return existing.getFirst();
        var raw =
                s.jdbc.queryForList(
                        "SELECT * FROM journal_line WHERE tenant_id=? AND company_id=? AND"
                                + " journal_id=? ORDER BY line_no",
                        tenant,
                        company,
                        journal);
        var lines = new ArrayList<GlModels.Line>();
        var snapshots = new ArrayList<Map<String, Object>>();
        Long originalVoucher = null;
        if (j.get("reverses_journal_id") != null) {
            var original =
                    s.jdbc.queryForList(
                            "SELECT id FROM gl_voucher WHERE tenant_id=? AND company_id=? AND"
                                    + " journal_id=?",
                            Long.class,
                            tenant,
                            company,
                            j.get("reverses_journal_id"));
            if (!original.isEmpty()) {
                originalVoucher = original.getFirst();
                for (var line : engine.lines(a, originalVoucher)) {
                    lines.add(
                            new GlModels.Line(
                                    line.digest(),
                                    line.subjectId(),
                                    line.direction(),
                                    money(line.amount(), true).negate().toPlainString(),
                                    decimal(line.quantity(), 6, true).negate().toPlainString(),
                                    line.unitPrice(),
                                    line.aux(),
                                    line.cashFlowItemId()));
                }
                snapshots.addAll(
                        engine.rawLines(a, originalVoucher).stream()
                                .map(
                                        row -> {
                                            var map = new LinkedHashMap<String, Object>();
                                            map.put(
                                                    "mapping_snapshot",
                                                    row.get("mapping_snapshot"));
                                            map.put(
                                                    "ledger_account_code",
                                                    row.get("ledger_account_code"));
                                            return map;
                                        })
                                .toList());
            }
        }
        if (lines.isEmpty()) {
            List<Map<String, Object>> rules = mappings.mappings(a);
            if (j.get("reverses_journal_id") != null && book.get("opening_mapping_json") != null)
                rules =
                        s.json.readValue(
                                book.get("opening_mapping_json").toString(),
                                new tools.jackson.core.type.TypeReference<
                                        List<Map<String, Object>>>() {});
            var mappingJournal = j;
            boolean historical = j.get("reverses_journal_id") != null;
            if (historical)
                mappingJournal =
                        s.jdbc.queryForMap(
                                "SELECT * FROM journal WHERE tenant_id=? AND company_id=? AND id=?",
                                tenant,
                                company,
                                j.get("reverses_journal_id"));
            for (var line : raw) {
                var resolved = mappings.resolve(a, mappingJournal, line, rules, historical);
                lines.add(resolved.line());
                snapshots.add(
                        Map.of(
                                "mapping_snapshot",
                                s.json.writeValueAsString(resolved.mapping()),
                                "ledger_account_code",
                                line.get("account_code")));
            }
        }
        long word =
                s.jdbc.queryForObject(
                        "SELECT id FROM gl_voucher_word WHERE tenant_id=? AND company_id=? AND"
                                + " is_default=1 AND status='active'",
                        Long.class,
                        tenant,
                        company);
        long voucher =
                engine.create(
                        a,
                        new GlModels.Voucher(
                                word, date(j.get("business_date")), 0, lines, "运营账本自动生成", null),
                        "business",
                        j.get("source_type").toString(),
                        number(j.get("source_id")),
                        journal,
                        originalVoucher,
                        null,
                        true);
        var saved = engine.rawLines(a, voucher);
        for (int i = 0; i < saved.size(); i++)
            s.jdbc.update(
                    "UPDATE gl_voucher_line SET"
                            + " journal_line_id=?,ledger_account_code=?,mapping_snapshot=? WHERE"
                            + " tenant_id=? AND company_id=? AND id=?",
                    raw.get(i).get("id"),
                    snapshots.get(i).get("ledger_account_code"),
                    snapshots.get(i).get("mapping_snapshot"),
                    tenant,
                    company,
                    saved.get(i).get("id"));
        engine.post(a, s.row(a, "gl_voucher", voucher, true), true);
        s.jdbc.update(
                "UPDATE gl_sync_error SET status='resolved',version=version+1 WHERE tenant_id=? AND"
                        + " company_id=? AND journal_id=?",
                tenant,
                company,
                journal);
        s.audit.record(
                a,
                "gl_business.generated",
                "gl_voucher",
                voucher,
                null,
                Map.of("journalId", Long.toString(journal)));
        return voucher;
    }
}
