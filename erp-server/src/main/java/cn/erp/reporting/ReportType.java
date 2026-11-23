package cn.erp.reporting;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.*;

public enum ReportType {
    INVENTORY_SUMMARY, INVENTORY_FLOW, UNSIGNED_SHIPMENTS, SALES_SUMMARY, PURCHASE_SUMMARY,
    RECEIVABLE_AGING, PAYABLE_AGING, PARTNER_STATEMENT, CASH_JOURNAL, GROSS_MARGIN, UNINVOICED_RECEIPTS;

    public String code() { return name().toLowerCase(Locale.ROOT); }
    public String permission() { return "report:"+code(); }
    public String exportPermission() { return "export:"+code(); }
    public boolean companyWide() { return Set.of(RECEIVABLE_AGING,PAYABLE_AGING,PARTNER_STATEMENT,CASH_JOURNAL).contains(this); }
    public boolean costBearing() { return !companyWide(); }
    public void authorize(CompanyAccess actor,boolean export) {
        if(!actor.hasPermission(permission()) || export && !actor.hasPermission(exportPermission())) {
            throw new ApiException(403,"FORBIDDEN","没有此报表的"+(export?"导出":"查看")+"权限");
        }
        if(companyWide() && !actor.allWarehouses()) { throw new ApiException(403,"FORBIDDEN","公司财务报表需要整家公司数据范围"); }
        if(this==GROSS_MARGIN && !actor.hasPermission("cost:view")) { throw new ApiException(403,"FORBIDDEN","毛利报表需要查看成本权限"); }
    }
    public static ReportType parse(String value) {
        try { return valueOf(value.replace('-','_').toUpperCase(Locale.ROOT)); }
        catch(IllegalArgumentException|NullPointerException error) { throw new ApiException(400,"VALIDATION_FAILED","报表类型无效"); }
    }
}
