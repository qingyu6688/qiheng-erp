package cn.erp.upgrade;

import cn.erp.documents.DocumentAccess;
import cn.erp.gl.GlUpgradeChecks;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.shared.ApiException;
import cn.erp.workflow.WorkflowDocuments;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class UpgradeChecks {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final DocumentAccess documents;
    private final WorkflowDocuments workflow;
    private final GlUpgradeChecks finance;
    private final ReconciliationChecks reconciliation;
    private final UpgradeWorkflows flows;

    UpgradeChecks(JdbcTemplate jdbc, AccessService access, DocumentAccess documents, WorkflowDocuments workflow,
                  GlUpgradeChecks finance, ReconciliationChecks reconciliation, UpgradeWorkflows flows) {
        this.jdbc = jdbc;
        this.access = access;
        this.documents = documents;
        this.workflow = workflow;
        this.finance = finance;
        this.reconciliation = reconciliation;
        this.flows = flows;
    }

    Map<String, Object> inspect(boolean legacy) {
        var checks = new ArrayList<Check>();
        var invalidJournals = new ArrayList<Map<String, Object>>();
        for (var company : companies(jdbc)) {
            long tenant = number(company, "tenant_id"), id = number(company, "id");
            var actor = new CompanyAccess(tenant, 0, id, true, Set.of(), Set.of("gl_ledger:view", "cost:view"));
            invalidJournals.addAll(finance.invalidJournals(actor));
            for (String type : new TreeSet<>(ReconciliationChecks.TYPES)) {
                if (!type.equals("period_close")) {
                    checks.add(check(actor, type, null));
                    continue;
                }
                for (long period : jdbc.queryForList("""
                        SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND status='closed' ORDER BY id
                        """, Long.class, tenant, id)) checks.add(check(actor, type, period));
            }
        }
        return Map.of("mappingsMissing", finance.missingCodes(), "invalidJournals", invalidJournals,
                "reconciliation", checks, "pending", flows.pending(legacy), "scopes", scope(legacy));
    }

    List<Scope> scope(boolean legacy) {
        String table = legacy ? "sys_user_data_scope" : "org_user_data_scope";
        var grants = jdbc.queryForList("""
                SELECT DISTINCT s.tenant_id,s.user_id,s.company_id FROM %s s
                JOIN sys_user u ON u.id=s.user_id AND u.tenant_id=s.tenant_id
                WHERE u.user_type<>'app'
                ORDER BY s.tenant_id,s.user_id,s.company_id
                """.formatted(table));
        var types = new TreeSet<>(documents.supportedTypes());
        types.addAll(flows.types(legacy));
        var result = new ArrayList<Scope>();
        for (var grant : grants) {
            long tenant = number(grant, "tenant_id"), user = number(grant, "user_id"), company = number(grant, "company_id");
            var actor = legacy ? access.forLegacyUpgrade(tenant, user, company) : access.forUser(tenant, user, company);
            var counts = new TreeMap<String, Visible>();
            if (actor != null) {
                for (String type : types) counts.put(type, visible(actor, type));
            }
            result.add(new Scope("" + tenant, "" + company, "" + user, actor != null,
                    actor != null && actor.allWarehouses(),
                    actor == null ? List.of() : actor.warehouseIds().stream().sorted().map(String::valueOf).toList(),
                    actor == null ? "" : actor.orgScope().signature(), counts));
        }
        return result;
    }

    private Visible visible(CompanyAccess actor, String type) {
        boolean usesDocumentAccess = documents.supportedTypes().contains(type);
        String permission = usesDocumentAccess ? documents.permission(type, "view") : WorkflowDocuments.permission(type, "view");
        MessageDigest digest = sha256();
        long count = 0, cursor = 0;
        if (actor.hasPermission(permission)) {
            while (true) {
                var ids = jdbc.queryForList("SELECT id FROM " + type
                                + " WHERE tenant_id=? AND company_id=? AND id>? ORDER BY id LIMIT 500",
                        Long.class, actor.tenantId(), actor.companyId(), cursor);
                if (ids.isEmpty()) break;
                for (long id : ids) {
                    try {
                        if (usesDocumentAccess) documents.view(actor, type, id);
                        else workflow.snapshot(actor, type, id);
                        digest.update((id + "\n").getBytes(StandardCharsets.UTF_8));
                        count++;
                    } catch (ApiException failure) {
                        if (failure.getStatus() != 403 && failure.getStatus() != 404) throw failure;
                    }
                }
                cursor = ids.getLast();
            }
        }
        return new Visible(count, HexFormat.of().formatHex(digest.digest()));
    }

    private Check check(CompanyAccess actor, String type, Long period) {
        var differences = reconciliation.check(actor, type, period);
        return new Check("" + actor.tenantId(), "" + actor.companyId(), type,
                period == null ? null : period.toString(), differences.size(), differences.stream().limit(20).toList());
    }

    static List<Map<String, Object>> companies(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT c.tenant_id,c.id FROM company c JOIN tenant t ON t.id=c.tenant_id
                WHERE t.status='active' AND c.status='active' ORDER BY c.tenant_id,c.id
                """);
    }

    static long number(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).longValue();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("当前 Java 不支持 SHA-256", unavailable);
        }
    }

    record Check(String tenantId, String companyId, String type, String periodId, int differenceCount,
                 List<ReconciliationChecks.Difference> samples) {}
    record Visible(long count, String idDigest) {}
    record Scope(String tenantId, String companyId, String userId, boolean active, boolean allWarehouses,
                 List<String> warehouseIds, String departments, Map<String, Visible> documents) {}
}
