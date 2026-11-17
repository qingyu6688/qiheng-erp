package cn.erp.gl;

import java.util.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;

import static cn.erp.gl.GlSupport.*;

@Service
@Transactional(readOnly = true)
class GlPrintService {
    @org.springframework.beans.factory.annotation.Autowired private GlDimensionViews dimensionViews;
    private final GlSupport s;
    private final GlQueryService queries;
    private final GlVoucherService vouchers;

    GlPrintService(GlSupport s, GlQueryService queries, GlVoucherService vouchers) {
        this.s = s;
        this.queries = queries;
        this.vouchers = vouchers;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> snapshot(CompanyAccess a, String type, Long id, GlModels.Query q) {
        s.require(a, "gl_print:view");
        String title;
        Map<String, Object> data;
        if (type.equals("voucher")) {
            if (id == null) throw invalid("请选择打印凭证");
            data = vouchers.get(a, id);
            title = "记账凭证";
            var lines =
                    s.jdbc.queryForList(
                            "SELECT l.line_no,l.digest,s.code subject_code,s.name"
                                    + " subject_name,l.direction,l.amount,l.quantity,l.unit_price,"
                                    + String.join(
                                            ",",
                                            AUX.stream().map(n -> "l.aux_" + n + "_id").toList())
                                    + " FROM gl_voucher_line l JOIN gl_subject s ON"
                                    + " s.tenant_id=l.tenant_id AND s.company_id=l.company_id AND"
                                    + " s.id=l.subject_id WHERE l.tenant_id=? AND l.company_id=?"
                                    + " AND l.voucher_id=? ORDER BY l.line_no",
                            a.tenantId(),
                            a.companyId(),
                            id);
            var names = dimensionViews.load(a, lines.stream().map(GlSupport::aux).toList());
            data.put(
                    "items",
                    lines.stream()
                            .map(
                                    row -> {
                                        var output = view(row);
                                        output.put("auxText", dimensionViews.text(aux(row), names));
                                        return output;
                                    })
                            .toList());
            long maker = number(data.get("makerId")), approver = number(data.get("approverId"));
            data.put("makerName", maker == 0 ? "系统生成" : name(a, maker));
            data.put(
                    "approverName",
                    approver == 0
                            ? ("manual".equals(data.get("source")) ? "待复核" : "来源业务复核")
                            : name(a, approver));
        } else if (Set.of("balance_sheet", "income_statement", "cash_flow").contains(type)) {
            if (id == null) throw invalid("请选择报表期间");
            data = new LinkedHashMap<>(queries.statement(a, type, id));
            title = data.get("name").toString();
            data.put("items", data.get("rows"));
        } else {
            var full =
                    new GlModels.Query(
                            q.periodFrom(),
                            q.periodTo(),
                            q.subjectId(),
                            q.auxType(),
                            q.auxId(),
                            q.dateFrom(),
                            q.dateTo(),
                            q.level(),
                            null,
                            10000);
            data = queries.ledger(a, type, full);
            title =
                    switch (type) {
                        case "balance" -> "科目余额表";
                        case "general" -> "总分类账";
                        case "detail" -> "明细分类账";
                        case "multi_column" -> "多栏账";
                        case "quantity" -> "数量金额账";
                        case "auxiliary" -> "辅助核算账";
                        default -> throw invalid("打印账簿类型无效");
                    };
        }
        data = new LinkedHashMap<>(data);
        if (data.get("periodId") != null)
            data.put("periodLabel", s.period(a, number(data.get("periodId")), false).get("period"));
        else if (data.get("from") != null)
            data.put("periodLabel", data.get("from") + " 至 " + data.get("to"));
        String company =
                s.jdbc.queryForObject(
                        "SELECT name FROM company WHERE tenant_id=? AND id=?",
                        String.class,
                        a.tenantId(),
                        a.companyId());
        return Map.of(
                "title",
                title,
                "company",
                company,
                "currency",
                "人民币",
                "printedAt",
                java.time.Instant.now().toString(),
                "data",
                data);
    }

    private String name(CompanyAccess actor, long user) {
        var names =
                s.jdbc.queryForList(
                        "SELECT display_name FROM sys_user WHERE tenant_id=? AND id=?",
                        String.class,
                        actor.tenantId(),
                        user);
        return names.isEmpty() ? "历史用户（" + user + "）" : names.getFirst();
    }
}
