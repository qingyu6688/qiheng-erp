package cn.erp.gl;

import java.math.BigDecimal;
import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;

import static cn.erp.gl.GlSupport.*;

@Component
class GlDimensions {
    static final class MissingCashFlow extends ApiException {
        MissingCashFlow() {
            super(400, "VALIDATION_FAILED", "现金及银行类分录必须选择现金流量项目");
        }
    }

    private final GlSupport s;

    GlDimensions(GlSupport support) {
        this.s = support;
    }

    List<String> required(Map<String, Object> subject) {
        return s.json.readValue(
                subject.get("aux_types").toString(),
                new tools.jackson.core.type.TypeReference<List<String>>() {});
    }

    GlModels.Aux validate(ActorScope actor, Map<String, Object> subject, GlModels.Aux input) {
        var aux = input == null ? GlModels.Aux.empty() : input;
        var values = aux.values();
        var required = required(subject);
        for (int index = 0; index < AUX.size(); index++) {
            String type = AUX.get(index);
            long id = values.get(index);
            if (id < 0) throw invalid("辅助核算编号不能为负数");
            if (required.contains(type) && id == 0)
                throw invalid("科目“" + subject.get("name") + "”必须填写" + label(type));
            if (!required.contains(type) && id != 0)
                throw invalid("科目未启用" + label(type) + "，不能携带其他辅助项目");
            if (id > 0) active(actor, type, id, subject);
        }
        return new GlModels.Aux(
                values.get(0),
                values.get(1),
                values.get(2),
                values.get(3),
                values.get(4),
                values.get(5),
                values.get(6));
    }

    void active(ActorScope actor, String type, long id, Map<String, Object> subject) {
        String query;
        List<Object> args = new ArrayList<>(List.of(actor.tenantId()));
        switch (type) {
            case "customer", "supplier" -> {
                query =
                        "SELECT COUNT(*) FROM business_partner p WHERE p.tenant_id=? AND p.id=? AND"
                                + " p.status='active' AND EXISTS(SELECT 1 FROM partner_role r WHERE"
                                + " r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role=?)";
                args.add(id);
                args.add(type);
            }
            case "dept" -> {
                query =
                        "SELECT COUNT(*) FROM org_department WHERE tenant_id=? AND company_id=? AND"
                                + " id=? AND status='active'";
                args.add(actor.companyId());
                args.add(id);
            }
            case "employee" -> {
                query =
                        "SELECT COUNT(*) FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND"
                            + " u.status='active' AND EXISTS(SELECT 1 FROM org_user_data_scope d"
                            + " WHERE d.tenant_id=u.tenant_id AND d.user_id=u.id AND"
                            + " d.company_id=?)";
                args.add(id);
                args.add(actor.companyId());
            }
            case "sku" -> {
                query = "SELECT COUNT(*) FROM sku WHERE tenant_id=? AND id=? AND status='active'";
                args.add(id);
            }
            case "project", "custom" -> {
                query =
                        "SELECT COUNT(*) FROM gl_aux_item i JOIN gl_aux_type t ON"
                                + " t.tenant_id=i.tenant_id AND t.company_id=i.company_id AND"
                                + " t.id=i.aux_type_id WHERE i.tenant_id=? AND i.company_id=? AND"
                                + " i.id=? AND i.status='active' AND t.status='active' AND "
                                + (type.equals("project")
                                        ? "t.code='project'"
                                        : "t.id=? AND t.source='custom'");
                args.add(actor.companyId());
                args.add(id);
                if (type.equals("custom")) args.add(subject.get("custom_aux_type_id"));
            }
            default -> throw invalid("辅助核算类型无效");
        }
        if (s.jdbc.queryForObject(query, Long.class, args.toArray()) != 1) throw missing();
    }

    String label(String type) {
        return switch (type) {
            case "customer" -> "客户";
            case "supplier" -> "供应商";
            case "dept" -> "部门";
            case "employee" -> "员工";
            case "project" -> "项目";
            case "sku" -> "存货";
            case "custom" -> "自定义辅助";
            default -> "辅助项目";
        };
    }

    Map<String, Object> subject(ActorScope actor, long id, boolean historical) {
        var subject = s.row(actor, "gl_subject", id, false);
        if (!flag(subject.get("is_leaf"))) throw invalid("只能使用末级会计科目");
        if (!historical && !"active".equals(subject.get("status"))) throw invalid("会计科目已停用");
        return subject;
    }

    void cashFlow(ActorScope actor, Map<String, Object> subject, Long cashId, boolean historical) {
        boolean cash = flag(subject.get("is_cash")) || flag(subject.get("is_bank"));
        if (cash && (cashId == null || cashId <= 0)) throw new MissingCashFlow();
        if (!cash && cashId != null && cashId > 0) throw invalid("非现金类分录不能重复填写现金流量项目");
        if (cashId != null && cashId > 0) {
            var item = s.row(actor, "gl_cash_flow_item", cashId, false);
            if (!historical && !"active".equals(item.get("status"))) throw invalid("现金流量项目已停用");
        }
    }

    static String key(long subject, GlModels.Aux aux) {
        return subject
                + ":"
                + String.join(":", aux.values().stream().map(Object::toString).toList());
    }

    static BigDecimal signed(String direction, BigDecimal amount) {
        return direction.equals("D") ? amount : amount.negate();
    }
}
