package cn.erp.workflow;

import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ManufacturingReversalPort;
import cn.erp.shared.ManufacturingScope;
import cn.erp.shared.OrgOwnership;

/** 消息计数及流程列表在分页前使用同一公司、仓库、部门与操作权限交集。 */
@Component
public class WorkflowAccess {
    public String business(CompanyAccess actor, String instance, boolean review) {
        var parts = new ArrayList<String>();
        for (String type : SimpleModel.TYPES.stream().sorted().toList()) {
            if (!actor.hasPermission(WorkflowDocuments.permission(type, "view"))
                    || review
                            && !actor.hasPermission(WorkflowDocuments.permission(type, "approve")))
                continue;
            if(Set.of("credit_increase", "gl_voucher", "ar_bad_debt").contains(type)
                    && (!actor.allWarehouses() || !actor.orgScope().allDepartments() || !actor.hasPermission("cost:view"))) continue;
            if(Set.of("price_adjustment","purchase_fee_doc").contains(type) && (!actor.hasPermission("cost:view")
                    || review && (!actor.allWarehouses() || !actor.orgScope().allDepartments()))) continue;
            String where =
                    "h.tenant_id="
                            + instance
                            + ".tenant_id AND h.company_id="
                            + instance
                            + ".company_id AND h.id="
                            + instance
                            + ".business_id"
                            + OrgOwnership.filter(actor, "h");
            if(type.equals("purchase_fee_doc")) where += " AND " + cn.erp.shared.PurchaseFeeScope.condition(actor,"h");
            if(type.equals("price_adjustment")) where += " AND " + cn.erp.shared.PriceAdjustmentScope.condition(actor,"h");
            if (Set.of("sales_order", "purchase_order", "purchase_requisition").contains(type)) {
                where += " AND h.is_deleted=0";
                if (!actor.allWarehouses() && !type.equals("purchase_requisition"))
                    where +=
                            " AND NOT EXISTS(SELECT 1 FROM "
                                    + type
                                    + "_line l WHERE l.tenant_id=h.tenant_id AND"
                                    + " l.company_id=h.company_id AND l.order_id=h.id AND"
                                    + " l.is_deleted=0 AND (l.warehouse_id IS NULL OR "
                                    + outside(actor, "l.warehouse_id")
                                    + "))";
                if (review)
                    where +=
                            " AND (h.created_by IS NULL OR h.created_by<>"
                                    + actor.userId()
                                    + " OR EXISTS(SELECT 1 FROM company_policy p WHERE"
                                    + " p.tenant_id=h.tenant_id AND p.company_id=h.company_id AND"
                                    + " p.separate_order_approver=0))";
            } else if (review && !type.equals("mfg_bom")) {
                String maker = switch (type) {
                    case "gl_voucher" -> "h.maker_id";
                    case "qc_inspection" -> "COALESCE(h.inspector_id,h.owner_user_id)";
                    default -> "h.created_by";
                };
                where += " AND " + maker + "<>" + actor.userId();
            }
            if (type.equals("gl_voucher")) where += " AND h.is_deleted=0";
            if (Set.of("stock_adjustment", "stock_count", "stock_misc_doc", "qc_inspection").contains(type) && !actor.allWarehouses())
                where += " AND NOT (" + outside(actor, "h.warehouse_id") + ")";
            if (type.equals("mfg_bom") && !actor.allWarehouses())
                where += " AND NOT EXISTS(SELECT 1 FROM mfg_bom_line l WHERE l.tenant_id=h.tenant_id"
                        + " AND l.company_id=h.company_id AND l.bom_id=h.id AND l.issue_warehouse_id IS NOT NULL AND "
                        + outside(actor, "l.issue_warehouse_id") + ")";
            if (Set.of("mfg_issue_doc", "mfg_receipt_doc").contains(type)) {
                where += " AND " + ManufacturingScope.stockCondition(actor, "h");
                if (review) where += " AND COALESCE(h.submitted_by,h.created_by)<>" + actor.userId();
            }
            if (type.equals("document_reversal")) {
                var sources = new ArrayList<String>();
                for (String source :
                        List.of(
                                "shipment",
                                "sales_return",
                                "goods_receipt",
                                "purchase_return",
                                "receipt",
                                "payment",
                                "account_transfer",
                                "stock_adjustment",
                                "stock_misc_doc",
                                "mfg_issue_doc",
                                "mfg_receipt_doc",
                                "price_adjustment","purchase_fee_doc"))
                    if (actor.hasPermission(ManufacturingReversalPort.viewPermission(source))) {
                        String predicate =
                                "s.tenant_id=h.tenant_id AND s.company_id=h.company_id AND"
                                    + " s.id=h.source_id"
                                        + OrgOwnership.filter(actor, "s");
                        if (ManufacturingReversalPort.supports(source))
                            predicate += " AND " + ManufacturingScope.stockCondition(actor, "s");
                        if(source.equals("price_adjustment")) predicate += " AND " + cn.erp.shared.PriceAdjustmentScope.condition(actor,"s");
                        if (!actor.allWarehouses()
                                && Set.of(
                                                "shipment",
                                                "sales_return",
                                                "goods_receipt",
                                                "purchase_return",
                                                "stock_adjustment",
                                                "stock_misc_doc")
                                        .contains(source))
                            predicate += " AND NOT (" + outside(actor, "s.warehouse_id") + ")";
                        sources.add(
                                "(h.source_type='"
                                        + source
                                        + "' AND EXISTS(SELECT 1 FROM "
                                        + source
                                        + " s WHERE "
                                        + predicate
                                        + "))");
                    }
                where +=
                        " AND (" + (sources.isEmpty() ? "1=0" : String.join(" OR ", sources)) + ")";
            }
            parts.add(
                    "("
                            + instance
                            + ".business_type='"
                            + type
                            + "' AND EXISTS(SELECT 1 FROM "
                            + type
                            + " h WHERE "
                            + where
                            + "))");
        }
        return "(" + (parts.isEmpty() ? "1=0" : String.join(" OR ", parts)) + ")";
    }

    public String task(CompanyAccess actor, String task, String instance) {
        String creditFilter=WorkflowDocuments.creditEligible(actor)?"":" AND "+task+".credit_approval=0";
        return task
                + ".tenant_id="
                + actor.tenantId()
                + " AND "
                + task
                + ".company_id="
                + actor.companyId()
                + " AND "
                + task
                + ".assignee_id="
                + actor.userId()
                + " AND "
                + task
                + ".status='pending'"+creditFilter+" AND "
                + instance
                + ".status='running' AND (("
                + task
                + ".node_kind='handler' AND "
                + business(actor, instance, false)
                + ") OR ("
                + task
                + ".node_kind='approver' AND "
                + business(actor, instance, true)
                + "))"
                + (actor.hasPermission("sales_order:price_approve")
                        ? ""
                        : " AND " + task + ".price_approval=0");
    }

    private String outside(CompanyAccess actor, String field) {
        return actor.warehouseIds().isEmpty()
                ? "1=1"
                : field
                        + " NOT IN("
                        + actor.warehouseIds().stream()
                                .sorted()
                                .map(String::valueOf)
                                .reduce((a, b) -> a + "," + b)
                                .orElseThrow()
                        + ")";
    }
}
