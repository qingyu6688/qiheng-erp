package cn.erp.dashboard;

import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportType;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

enum DashboardMetric {
    SALES("销售", "/reports/sales-summary",Set.of(ReportType.SALES_SUMMARY.permission())),
    GROSS_MARGIN("毛利", "/reports/gross-margin",Set.of(ReportType.GROSS_MARGIN.permission(),"cost:view")),
    RECEIVABLES("回款与应收", "/reports/receivable-aging",Set.of(ReportType.RECEIVABLE_AGING.permission())),
    INVENTORY("库存", "/reports/inventory-summary",Set.of(ReportType.INVENTORY_SUMMARY.permission())),
    PURCHASE("采购", "/reports/purchase-summary",Set.of(ReportType.PURCHASE_SUMMARY.permission())),
    FINANCE("财务", "/gl/reports",Set.of("gl_report:view","cost:view")),
    PRODUCTION("生产", "/mfg/reports",Set.of("mfg_report:view")),
    CRM("客户经营", "/crm/statistics",Set.of("crm_statistic:view"));

    final String label;
    final String link;
    final Set<String> permissions;

    DashboardMetric(String label,String link,Set<String> permissions) {this.label=label;this.link=link;this.permissions=permissions;}

    String code() {return name().toLowerCase(Locale.ROOT);}

    boolean available(CompanyAccess actor) {
        if (!actor.permissions().containsAll(permissions)) return false;
        return switch (this) {
            case RECEIVABLES,CRM -> actor.allWarehouses();
            case FINANCE -> actor.allWarehouses() && actor.orgScope().allDepartments();
            case INVENTORY -> actor.hasPermission("cost:view") || actor.hasPermission("batch:view");
            default -> true;
        };
    }

    static DashboardMetric find(String code) {
        return Arrays.stream(values()).filter(metric->metric.code().equals(code)).findFirst().orElse(null);
    }

    Definition definition() {return new Definition(code(),label,link);}
    record Definition(String code,String name,String detailPath) {}
}
