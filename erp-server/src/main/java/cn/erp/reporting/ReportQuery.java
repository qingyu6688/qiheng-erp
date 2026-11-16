package cn.erp.reporting;

import cn.erp.shared.ApiException;
import java.time.*;

public record ReportQuery(LocalDate dateFrom,LocalDate dateTo,Long warehouseId,Long skuId,Long partnerId,Long cashAccountId,Boolean detail,Boolean feesOnly,Long businessUserId,String groupBy) {
    public ReportQuery(LocalDate dateFrom,LocalDate dateTo,Long warehouseId,Long skuId,Long partnerId,Long cashAccountId,Boolean detail,Boolean feesOnly) {
        this(dateFrom,dateTo,warehouseId,skuId,partnerId,cashAccountId,detail,feesOnly,null,null);
    }
    public ReportQuery(LocalDate dateFrom,LocalDate dateTo,Long warehouseId,Long skuId,Long partnerId,Long cashAccountId,Boolean detail) {
        this(dateFrom,dateTo,warehouseId,skuId,partnerId,cashAccountId,detail,false);
    }
    public ReportQuery normalized() {
        var today=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var to=dateTo==null?today:dateTo;
        var from=dateFrom==null?to.withDayOfMonth(1):dateFrom;
        if(from.isAfter(to) || to.isAfter(today) || to.isAfter(from.plusYears(1).minusDays(1))) {
            throw new ApiException(400,"VALIDATION_FAILED","报表日期须按先后填写，不能超过今天或一年");
        }
        for(Long id:new Long[]{warehouseId,skuId,partnerId,cashAccountId}) {
            if(id!=null && id<=0) { throw new ApiException(400,"VALIDATION_FAILED","报表筛选编号无效"); }
        }
        if(businessUserId!=null&&businessUserId<0 || groupBy!=null&&!java.util.Set.of("standard","business_user","department").contains(groupBy)) throw new ApiException(400,"VALIDATION_FAILED","业务员或汇总方式无效");
        if(java.util.Set.of("business_user","department").contains(groupBy==null?"standard":groupBy)&&Boolean.TRUE.equals(detail)) throw new ApiException(400,"VALIDATION_FAILED","按业务员汇总时不能同时选择明细");
        return new ReportQuery(from,to,warehouseId,skuId,partnerId,cashAccountId,Boolean.TRUE.equals(detail),Boolean.TRUE.equals(feesOnly),businessUserId,groupBy==null?"standard":groupBy);
    }
}
