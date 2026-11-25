package cn.erp.upgrade;

import static cn.erp.upgrade.UpgradeChecks.number;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.workflow.SystemWorkflowPlan;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowRuntime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
class UpgradeWorkflows {
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final WorkflowModels models;
    private final WorkflowRuntime runtime;

    UpgradeWorkflows(JdbcTemplate jdbc, AccessService access, WorkflowModels models, WorkflowRuntime runtime) {
        this.jdbc = jdbc;
        this.access = access;
        this.models = models;
        this.runtime = runtime;
    }

    List<String> types(boolean legacy) {
        var types = new TreeSet<>(SystemWorkflowPlan.LABELS.keySet());
        if (legacy) {
            var tables=new java.util.HashSet<>(jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.tables WHERE table_schema=DATABASE()
                """, String.class));
            types.removeIf(type->!tables.contains(SystemWorkflowPlan.sourceTable(type)));
        }
        return List.copyOf(types);
    }

    List<Pending> pending(boolean legacy) {
        var result = new ArrayList<Pending>();
        for (String type : types(legacy)) {
            for (var row : documents(type)) {
                result.add(new Pending(type, "" + number(row, "tenant_id"), "" + number(row, "company_id"),
                        "" + number(row, "id"), "" + number(row, "starter"), number(row, "wf_instance_id") != 0));
            }
        }
        return List.copyOf(result);
    }

    Map<String, Object> convert() {
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT @@global.offline_mode", Boolean.class)))
            throw new IllegalStateException("数据库未停写，拒绝补建默认流程");
        var companies = UpgradeChecks.companies(jdbc);
        var types = types(false);
        int installed = 0;
        for (var company : companies) {
            for (String type : types) {
                models.installDefault(number(company, "tenant_id"), number(company, "id"), type);
                installed++;
            }
        }
        var pending = pending(false);
        for (var document : pending) {
            if (document.invalidReference()) throw problem(document, "原工作流关联失效，请先查明状态");
            var actor = access.forUser(Long.parseLong(document.tenantId()), Long.parseLong(document.starterId()),
                    Long.parseLong(document.companyId()));
            if (actor == null) throw problem(document, "原发起人已停用、待改密或没有当前公司的访问权限");
            try {
                runtime.resumeLegacy(actor, document.type(), Long.parseLong(document.id()));
            } catch (ApiException failure) {
                throw problem(document, failure.getMessage());
            }
        }
        return Map.of("companies", companies.size(), "bindings", installed, "resumed", pending);
    }

    Map<String, Object> verify() {
        var candidates = jdbc.queryForList("""
                SELECT b.tenant_id,b.company_id,b.business_type,b.model_id
                FROM wf_business_binding b JOIN wf_model m
                  ON m.id=b.model_id AND m.tenant_id=b.tenant_id AND m.company_id=b.company_id
                JOIN wf_model_version v ON v.model_id=m.id AND v.tenant_id=m.tenant_id
                  AND v.company_id=m.company_id AND v.version_no=m.published_version
                JOIN ACT_RE_PROCDEF p ON p.ID_=v.flowable_definition_id AND p.TENANT_ID_=CAST(b.tenant_id AS CHAR)
                JOIN company c ON c.id=b.company_id AND c.tenant_id=b.tenant_id AND c.status='active'
                JOIN tenant t ON t.id=c.tenant_id AND t.status='active'
                WHERE b.enabled=1 AND b.priority=-1001 AND b.condition_json IS NULL AND m.status='published'
                  AND m.code=CONCAT('system20_', b.business_type) AND m.business_type=b.business_type
                ORDER BY b.tenant_id,b.company_id,b.business_type
                """);
        long expected = (long) UpgradeChecks.companies(jdbc).size() * types(false).size();
        return Map.of("expectedBindings", expected, "bindings", candidates, "pendingWithoutInstance", pending(false));
    }

    private List<Map<String, Object>> documents(String type) {
        String table=SystemWorkflowPlan.sourceTable(type);
        String submitAction = switch (type) {
            case "document_reversal" -> "reversal.create";
            case "credit_increase" -> "credit_increase.submit";
            case "ar_bad_debt" -> "bad_debt.submit";
            default -> type + ".submit";
        };
        String starter = type.startsWith("mfg_") && type.endsWith("_doc") ? "d.submitted_by," : "";
        String maker = switch (type) {
            case "gl_voucher" -> "d.maker_id";
            case "mfg_bom" -> "d.owner_user_id";
            case "qc_inspection" -> "COALESCE(d.inspector_id,d.owner_user_id)";
            default -> "d.created_by";
        };
        String reference = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE()
                  AND table_name=? AND column_name='wf_instance_id'
                """, Integer.class, table) == 0 ? "0" : "d.wf_instance_id";
        return jdbc.queryForList("""
                SELECT d.id,d.tenant_id,d.company_id,%s wf_instance_id,
                  COALESCE(%s(SELECT a.operator_id FROM audit_log a
                    WHERE a.tenant_id=d.tenant_id AND a.company_id=d.company_id
                      AND a.object_type=? AND a.object_id=d.id AND a.action=?
                    ORDER BY a.id DESC LIMIT 1),%s,0) starter
                FROM %s d JOIN company c ON c.tenant_id=d.tenant_id AND c.id=d.company_id AND c.status='active'
                JOIN tenant t ON t.id=c.tenant_id AND t.status='active'
                WHERE d.status=? AND %s AND NOT EXISTS (
                    SELECT 1 FROM wf_instance i JOIN ACT_RU_EXECUTION e
                      ON e.ID_=i.flowable_instance_id AND e.TENANT_ID_=CAST(i.tenant_id AS CHAR)
                    WHERE i.tenant_id=d.tenant_id AND i.company_id=d.company_id AND i.business_type=?
                      AND i.business_id=d.id AND i.id=%s AND i.status='running'
                ) ORDER BY d.tenant_id,d.company_id,d.id
                """.formatted(reference, starter, maker, table, SystemWorkflowPlan.sourceKind(type,"d"), reference), type, submitAction,
                SystemWorkflowPlan.pendingStatus(type), type);
    }

    private IllegalStateException problem(Pending document, String reason) {
        return new IllegalStateException("补建流程失败，企业 " + document.tenantId() + " 公司 " + document.companyId()
                + " 单据 " + document.type() + "/" + document.id() + "，" + reason);
    }

    record Pending(String type, String tenantId, String companyId, String id, String starterId, boolean invalidReference) {}
}
