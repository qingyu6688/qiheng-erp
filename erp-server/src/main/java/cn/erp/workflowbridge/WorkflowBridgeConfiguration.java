package cn.erp.workflowbridge;

import java.math.*;
import java.util.*;
import java.util.function.BiFunction;

import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.erp.identity.CompanyAccess;
import cn.erp.opening.OpeningService;
import cn.erp.pricing.MinimumSalePrice;
import cn.erp.purchasing.*;
import cn.erp.reversal.*;
import cn.erp.salesfinance.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;
import cn.erp.stockcount.StockCountModels;
import cn.erp.stockcount.StockCountService;

/** 组合层登记业务适配器，避免工作流引擎与业务模块形成依赖环。 */
@Configuration(proxyBeanMethods = false)
class WorkflowBridgeConfiguration {
    @FunctionalInterface
    interface Decision {
        void apply(CompanyAccess actor, long id, String outcome, String comment, String key);
    }

    @Bean
    WorkflowBusiness salesWorkflow(
            SalesOrderQueries queries,
            SalesOrderService service,
            MinimumSalePrice prices,CreditPort credit,
            JdbcTemplate jdbc) {
        return adapter(
                "sales_order",
                "销售订单",
                queries::get,
                (actor, id, outcome, comment, key) ->
                        service.command(
                                actor,
                                id,
                                outcome.equals("approved") ? "approve" : "reject",
                                key,
                                new SalesOrderModels.Command(
                                        version(jdbc, "sales_order", actor, id), comment, null)),
                (actor, id) -> {var info=new LinkedHashMap<>(prices.inspect(actor,id,false));info.putAll(credit.inspect(actor,id));return info;},
                jdbc);
    }

    @Bean
    WorkflowBusiness purchaseWorkflow(
            PurchaseOrderQueries queries, PurchaseOrderService service, JdbcTemplate jdbc) {
        return adapter(
                "purchase_order",
                "采购订单",
                queries::get,
                (actor, id, outcome, comment, key) ->
                        service.command(
                                actor,
                                id,
                                outcome.equals("approved") ? "approve" : "reject",
                                key,
                                new PurchaseOrderModels.Command(
                                        version(jdbc, "purchase_order", actor, id), comment, null)),
                (actor, id) ->
                        Map.of(
                                "requisitionApproved",
                                jdbc.queryForObject(
                                        "SELECT COUNT(*)>0 AND SUM(r.status<>'approved')=0 FROM"
                                            + " purchase_order_line l JOIN"
                                            + " purchase_requisition_line q ON"
                                            + " q.tenant_id=l.tenant_id AND"
                                            + " q.id=l.requisition_line_id JOIN"
                                            + " purchase_requisition r ON r.tenant_id=q.tenant_id"
                                            + " AND r.id=q.requisition_id WHERE l.tenant_id=? AND"
                                            + " l.company_id=? AND l.order_id=? AND l.is_deleted=0",
                                        Boolean.class,
                                        actor.tenantId(),
                                        actor.companyId(),
                                        id)),
                jdbc);
    }

    @Bean
    WorkflowBusiness refundWorkflow(RefundApplicationService service, JdbcTemplate jdbc) {
        return adapter(
                "refund_request",
                "退款申请",
                service::get,
                (actor, id, outcome, comment, key) ->
                        service.command(
                                actor,
                                id,
                                outcome.equals("approved") ? "approve" : "reject",
                                key,
                                new FinanceModels.Version(
                                        version(jdbc, "refund_request", actor, id), null, comment)),
                (actor, id) ->
                        Map.of(
                                "refundMethod",
                                jdbc.queryForObject(
                                        "SELECT c.account_type FROM refund_request r JOIN"
                                            + " cash_account c ON c.tenant_id=r.tenant_id AND"
                                            + " c.company_id=r.company_id AND"
                                            + " c.id=r.cash_account_id WHERE r.tenant_id=? AND"
                                            + " r.company_id=? AND r.id=?",
                                        String.class,
                                        actor.tenantId(),
                                        actor.companyId(),
                                        id)),
                jdbc);
    }

    @Bean
    WorkflowBusiness reversalWorkflow(ReversalService service, JdbcTemplate jdbc) {
        var base =
                adapter(
                        "document_reversal",
                        "冲销申请",
                        service::get,
                        (actor, id, outcome, comment, key) ->
                                service.command(
                                        actor,
                                        id,
                                        outcome.equals("approved") ? "approve" : "reject",
                                        key,
                                        new ReversalModels.Version(
                                                version(jdbc, "document_reversal", actor, id),
                                                comment)),
                        (actor, id) -> {
                            var head =
                                    jdbc.queryForMap(
                                            "SELECT source_type,source_id,business_date FROM"
                                                + " document_reversal WHERE tenant_id=? AND"
                                                + " company_id=? AND id=?",
                                            actor.tenantId(),
                                            actor.companyId(),
                                            id);
                            String source = head.get("source_type").toString();
                            var original =
                                    jdbc.queryForMap(
                                            "SELECT * FROM "
                                                    + source
                                                    + " WHERE tenant_id=? AND company_id=? AND"
                                                    + " id=?",
                                            actor.tenantId(),
                                            actor.companyId(),
                                            head.get("source_id"));
                            return Map.of(
                                    "totalAmount",
                                    money(original),
                                    "sourceType",
                                    source,
                                    "crossPeriod",
                                    !head.get("business_date")
                                            .toString()
                                            .substring(0, 7)
                                            .equals(
                                                    original.get("business_date")
                                                            .toString()
                                                            .substring(0, 7)));
                        },
                        jdbc);
        return new WorkflowBusiness() {
            public String businessType() {
                return base.businessType();
            }

            public Map<String, Object> snapshot(ActorScope actor, long id) {
                return base.snapshot(actor, id);
            }

            public void decide(
                    ActorScope actor, long id, String outcome, String comment, String key) {
                base.decide(actor, id, outcome, comment, key);
            }

            public Map<String, Object> preview(
                    ActorScope actor, String type, long id, java.time.LocalDate date) {
                return service.workflowPreview((CompanyAccess) actor, type, id, date);
            }
        };
    }

    @Bean
    WorkflowBusiness requisitionWorkflow(RequisitionService service, JdbcTemplate jdbc) {
        String type = "purchase_requisition";
        return adapter(type, "请购单", service::get,
                (actor, id, outcome, comment, key) -> service.command(actor, id,
                        outcome.equals("approved") ? "approve" : "reject", key,
                        new PurchaseOrderModels.Command(version(jdbc, type, actor, id),
                                Objects.toString(comment, "流程未通过"), null, Map.of())),
                (actor, id) -> Map.of(), jdbc);
    }

    @Bean
    WorkflowBusiness countWorkflow(StockCountService service, JdbcTemplate jdbc) {
        String type = "stock_count";
        return adapter(type, "库存盘点", service::get,
                (actor, id, outcome, comment, key) -> service.command(actor, id,
                        outcome.equals("approved") ? "approve" : "reject", key,
                        new StockCountModels.Version(version(jdbc, type, actor, id),
                                Objects.toString(comment, "流程未通过"))),
                (actor, id) -> Map.of(), jdbc);
    }

    @Bean
    WorkflowBusiness adjustmentWorkflow(OpeningService service, JdbcTemplate jdbc) {
        return adapter(
                "stock_adjustment",
                "库存调整",
                (actor, id) -> service.get(actor, true, id),
                (actor, id, outcome, comment, key) ->
                        service.command(
                                actor,
                                true,
                                id,
                                outcome.equals("approved") ? "approve" : "reject",
                                key,
                                new OpeningService.Version(
                                        version(jdbc, "stock_adjustment", actor, id), comment)),
                (actor, id) -> {
                    var amount =
                            jdbc.queryForObject(
                                    "SELECT COALESCE(SUM(CASE WHEN h.adjust_type"
                                        + " IN('opening','gain') THEN"
                                        + " COALESCE(l.input_total_cost,0) WHEN h.adjust_type"
                                        + " IN('loss','scrap') THEN"
                                        + " ROUND(l.base_qty*COALESCE(c.total_cost/NULLIF(c.qty,0),0),2)"
                                        + " ELSE 0 END),0) FROM stock_adjustment h JOIN"
                                        + " stock_adjustment_line l ON l.tenant_id=h.tenant_id AND"
                                        + " l.company_id=h.company_id AND l.adjustment_id=h.id LEFT"
                                        + " JOIN inventory_cost c ON c.tenant_id=h.tenant_id AND"
                                        + " c.company_id=h.company_id AND"
                                        + " c.warehouse_id=h.warehouse_id AND c.sku_id=l.sku_id"
                                        + " WHERE h.tenant_id=? AND h.company_id=? AND h.id=?",
                                    BigDecimal.class,
                                    actor.tenantId(),
                                    actor.companyId(),
                                    id);
                    return Map.of("totalAmount", amount.toPlainString());
                },
                jdbc);
    }

    private WorkflowBusiness adapter(
            String type,
            String label,
            BiFunction<CompanyAccess, Long, Map<String, Object>> reader,
            Decision decision,
            BiFunction<CompanyAccess, Long, Map<String, Object>> extra,
            JdbcTemplate jdbc) {
        return new WorkflowBusiness() {
            @Override
            public String businessType() {
                return type;
            }

            @Override
            public Map<String, Object> snapshot(ActorScope scope, long id) {
                var actor = (CompanyAccess) scope;
                var raw = reader.apply(actor, id);
                var result = new LinkedHashMap<String, Object>();
                result.put(
                        "documentNo",
                        Objects.toString(
                                raw.get("documentNo"), Objects.toString(raw.get("orderNo"), "")));
                result.put("title", label + " " + result.get("documentNo"));
                for (String field :
                        List.of(
                                "createdBy",
                                "salesUserId",
                                "buyerUserId",
                                "customerId",
                                "supplierId"))
                    result.put(field, Objects.toString(raw.get(field), "0"));
                result.put("ownerDeptId", Objects.toString(raw.get("ownerDepartmentId"), "0"));
                result.put("totalAmount", money(raw));
                result.put("status", raw.get("status"));
                result.put("version", raw.get("version"));
                for (String field : List.of("adjustType", "sourceType", "abovePriceList", "purchasePriceApprovalRequired"))
                    if (raw.get(field) != null) result.put(field, raw.get(field));
                var goods = new BigDecimal(Objects.toString(raw.get("goodsAmount"), "0"));
                var discount =
                        new BigDecimal(Objects.toString(raw.get("lineDiscountAmount"), "0"))
                                .add(
                                        new BigDecimal(
                                                Objects.toString(
                                                        raw.get("orderDiscountAmount"), "0")));
                result.put(
                        "discountRate",
                        goods.signum() > 0
                                ? goods.subtract(discount)
                                        .multiply(new BigDecimal("100"))
                                        .divide(goods, 6, RoundingMode.HALF_UP)
                                        .toPlainString()
                                : "100");
                result.putAll(extra.apply(actor, id));
                return result;
            }

            @Override
            public void decide(
                    ActorScope actor, long id, String outcome, String comment, String key) {
                if (!WorkflowContext.allows(actor, type, id))
                    throw new ApiException(403, "FORBIDDEN", "业务回调缺少有效流程上下文");
                decision.apply((CompanyAccess) actor, id, outcome, comment, key);
            }
        };
    }

    private static long version(JdbcTemplate jdbc, String table, CompanyAccess actor, long id) {
        return jdbc.queryForObject(
                "SELECT version FROM " + table + " WHERE tenant_id=? AND company_id=? AND id=?",
                Long.class,
                actor.tenantId(),
                actor.companyId(),
                id);
    }

    private static String money(Map<String, Object> row) {
        for (String key :
                List.of(
                        "totalAmount",
                        "receivableAmount",
                        "payableAmount",
                        "amount",
                        "totalCost",
                        "total_amount",
                        "receivable_amount",
                        "payable_amount",
                        "total_cost")) if (row.get(key) != null) return row.get(key).toString();
        return "0";
    }
}
