package cn.erp.reporting;

import cn.erp.shared.ApiException;
import java.time.*;

public record ReportQuery(LocalDate dateFrom,LocalDate dateTo,Long warehouseId,Long skuId,Long partnerId,Long cashAccountId,Boolean detail,Boolean feesOnly,Long businessUserId,String groupBy,LocalDate agingDate) {
    public ReportQuery(LocalDate dateFrom,LocalDate dateTo,Long warehouseId,Long skuId,Long partnerId,Long cashAccountId,Boolean detail,Boolean feesOnly,Long businessUserId,String groupBy) {
        this(dateFrom,dateTo,warehouseId,skuId,partnerId,cashAccountId,detail,feesOnly,businessUserId,groupBy,null);
    }
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
        if(businessUserId!=null&&businessUserId<0 || groupBy!=null&&!java.util.Set.of("standard","business_user","department","brand","customer","sku").contains(groupBy)) throw new ApiException(400,"VALIDATION_FAILED","业务员或汇总方式无效");
        if(groupBy!=null&&!"standard".equals(groupBy)&&Boolean.TRUE.equals(detail)) throw new ApiException(400,"VALIDATION_FAILED","汇总和明细不能同时选择");
        if(agingDate!=null&&(agingDate.isAfter(today)||agingDate.getYear()<1900))throw new ApiException(400,"VALIDATION_FAILED","账龄截止日期超出业务日期范围");
        return new ReportQuery(from,to,warehouseId,skuId,partnerId,cashAccountId,Boolean.TRUE.equals(detail),Boolean.TRUE.equals(feesOnly),businessUserId,groupBy==null?"standard":groupBy,agingDate);
    }
}
