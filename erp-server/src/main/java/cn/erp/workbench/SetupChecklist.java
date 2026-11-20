package cn.erp.workbench;

import cn.erp.identity.CompanyAccess;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 新公司上线清单：按开账顺序检查基础资料、期间、期初和第一张单据是否就绪。
 * 只返回完成与否，不返回数量和明细；每一步只对有对应查看权限的人返回。
 */
@Service
@Transactional(readOnly = true)
public class SetupChecklist {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String ACTIVE_PARTNER = """
            SELECT EXISTS(SELECT 1 FROM partner_role r JOIN business_partner p ON p.tenant_id=r.tenant_id AND p.id=r.partner_id
              WHERE r.tenant_id=:tenant AND r.role=:role AND r.status='active' AND p.status='active')
            """;

    record Step(String key, List<String> permissions, boolean required, String sql, Map<String, Object> extra) {}

    // 顺序就是建议的开账顺序；期初两步按需，没有存量数据的公司可以跳过
    static final List<Step> STEPS = List.of(
            new Step("warehouse", List.of("warehouse:view"), true,
                    "SELECT EXISTS(SELECT 1 FROM warehouse WHERE tenant_id=:tenant AND company_id=:company AND status='active')", Map.of()),
            new Step("customer", List.of("partner:view"), true, ACTIVE_PARTNER, Map.of("role", "customer")),
            new Step("supplier", List.of("partner:view"), true, ACTIVE_PARTNER, Map.of("role", "supplier")),
            new Step("sku", List.of("sku:view", "product:view"), true,
                    "SELECT EXISTS(SELECT 1 FROM sku WHERE tenant_id=:tenant AND status='active')", Map.of()),
            new Step("cash-account", List.of("cash_account:view"), true,
                    "SELECT EXISTS(SELECT 1 FROM cash_account WHERE tenant_id=:tenant AND company_id=:company AND status='active')", Map.of()),
            new Step("period", List.of("period:view"), true, """
                    SELECT EXISTS(SELECT 1 FROM accounting_period WHERE tenant_id=:tenant AND company_id=:company
                      AND status='open' AND start_date<=:today AND end_date>=:today)
                    """, Map.of()),
            new Step("stock-opening", List.of("stock_adjustment:view"), false, """
                    SELECT EXISTS(SELECT 1 FROM stock_adjustment WHERE tenant_id=:tenant AND company_id=:company
                      AND adjust_type='opening' AND status='posted')
                    """, Map.of()),
            new Step("partner-opening", List.of("opening_entry:view"), false,
                    "SELECT EXISTS(SELECT 1 FROM opening_entry WHERE tenant_id=:tenant AND company_id=:company AND status='posted')", Map.of()),
            new Step("first-document", List.of("sales_order:view", "purchase_order:view"), true, """
                    SELECT EXISTS(SELECT 1 FROM sales_order WHERE tenant_id=:tenant AND company_id=:company AND is_deleted=0)
                      OR EXISTS(SELECT 1 FROM purchase_order WHERE tenant_id=:tenant AND company_id=:company AND is_deleted=0)
                    """, Map.of()));

    private final NamedParameterJdbcTemplate jdbc;

    public SetupChecklist(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * completed 按全部必做步骤计算，与当前用户权限无关，这样完成后所有人的工作台都会收起清单。
     */
    public Map<String, Object> status(CompanyAccess actor) {
        var params = new HashMap<String, Object>();
        params.put("tenant", actor.tenantId());
        params.put("company", actor.companyId());
        params.put("today", LocalDate.now(BUSINESS_ZONE));
        var steps = new ArrayList<Map<String, Object>>();
        boolean completed = true;
        for (Step step : STEPS) {
            var bindings = new HashMap<>(params);
            bindings.putAll(step.extra());
            boolean done = Boolean.TRUE.equals(jdbc.queryForObject(step.sql(), bindings, Boolean.class));
            if (step.required() && !done) completed = false;
            if (step.permissions().stream().anyMatch(actor::hasPermission)) {
                steps.add(Map.of("key", step.key(), "required", step.required(), "done", done));
            }
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("completed", completed);
        result.put("steps", steps);
        return result;
    }
}
