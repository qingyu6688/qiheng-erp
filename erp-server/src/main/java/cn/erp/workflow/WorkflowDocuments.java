package cn.erp.workflow;

import java.util.*;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.identity.*;
import cn.erp.shared.*;

@Component
public class WorkflowDocuments {
    private final ObjectProvider<WorkflowBusiness> handlers;
    private final JdbcTemplate jdbc;
    private final AccessService access;

    public WorkflowDocuments(
            ObjectProvider<WorkflowBusiness> handlers, JdbcTemplate jdbc, AccessService access) {
        this.handlers = handlers;
        this.jdbc = jdbc;
        this.access = access;
    }

    WorkflowBusiness handler(String type) {
        if (!SimpleModel.TYPES.contains(type))
            throw new ApiException(400, "VALIDATION_FAILED", "工作流单据类型无效");
        return handlers.stream()
                .filter(h -> h.businessType().equals(type))
                .findFirst()
                .orElseThrow(() -> new ApiException(503, "WORKFLOW_UNAVAILABLE", "此类单据尚未登记工作流适配器"));
    }

    public static String permission(String type, String action) {
        return (type.equals("document_reversal") ? "reversal" : type) + ":" + action;
    }

    public Map<String, Object> snapshot(CompanyAccess actor, String type, long id) {
        if (!actor.hasPermission(permission(type, "view")))
            throw new ApiException(403, "FORBIDDEN", "没有查看来源单据的权限");
        return handler(type).snapshot(actor, id);
    }

    public CompanyAccess eligible(
            long tenant,
            long company,
            long user,
            String type,
            long id,
            boolean review,
            boolean priceApproval) {
        var actor = access.forUser(tenant, user, company);
        if (actor == null
                || review && !actor.hasPermission(permission(type, "approve"))
                || priceApproval && !actor.hasPermission("sales_order:price_approve")) return null;
        try {
            var source = snapshot(actor, type, id);
            if (review
                    && separate(tenant, company, type)
                    && number(source.get("createdBy")) == user) return null;
            return actor;
        } catch (ApiException error) {
            if (Set.of(403, 404).contains(error.getStatus())) return null;
            throw error;
        }
    }

    Map<String, Object> preview(
            CompanyAccess actor, String sourceType, long sourceId, java.time.LocalDate date) {
        return handler("document_reversal").preview(actor, sourceType, sourceId, date);
    }

    CompanyAccess previewEligible(
            long tenant,
            long company,
            long user,
            long starter,
            String sourceType,
            long sourceId,
            java.time.LocalDate date,
            boolean review) {
        var actor = access.forUser(tenant, user, company);
        if (actor == null
                || review && (!actor.hasPermission("reversal:approve") || user == starter))
            return null;
        try {
            preview(actor, sourceType, sourceId, date);
            return actor;
        } catch (ApiException error) {
            if (Set.of(403, 404).contains(error.getStatus())) return null;
            throw error;
        }
    }

    boolean separate(long tenant, long company, String type) {
        if (!Set.of("sales_order", "purchase_order").contains(type)) return true;
        var rules =
                jdbc.queryForList(
                        "SELECT separate_order_approver FROM company_policy WHERE tenant_id=? AND"
                            + " company_id=?",
                        Integer.class,
                        tenant,
                        company);
        return rules.isEmpty() || rules.getFirst() != 0;
    }

    public boolean visible(CompanyAccess actor, Map<String, Object> instance) {
        try {
            snapshot(
                    actor,
                    instance.get("business_type").toString(),
                    number(instance.get("business_id")));
            return true;
        } catch (ApiException error) {
            if (Set.of(403, 404).contains(error.getStatus())) return false;
            throw error;
        }
    }

    static long number(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }

    static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "流程不存在或不在当前权限范围");
    }
}
