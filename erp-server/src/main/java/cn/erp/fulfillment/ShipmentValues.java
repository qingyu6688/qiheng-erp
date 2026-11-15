package cn.erp.fulfillment;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

final class ShipmentValues {
    static final BigDecimal ZERO=BigDecimal.ZERO.setScale(2);
    private ShipmentValues() {}
    static long id(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    static BigDecimal decimal(Map<String,Object> row,String field) {return row.get(field)==null?ZERO:(BigDecimal)row.get(field);}
    static LocalDate date(Map<String,Object> row,String field) {
        Object value=row.get(field);
        return value instanceof LocalDate local?local:((java.sql.Date)value).toLocalDate();
    }
    static BigDecimal quantity(String text,boolean zero) {
        if(text==null || text.length()>64 || !text.matches("[0-9]+(?:\\.[0-9]+)?")) {throw invalid("数量须为非负十进制数");}
        BigDecimal value=new BigDecimal(text);
        if(value.scale()>6 || value.precision()-value.scale()>18 || (!zero&&value.signum()==0)) {throw invalid("数量精度或范围有误");}
        return value.setScale(6);
    }
    static void date(LocalDate date) {
        if(date==null || date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {throw invalid("业务日期不能晚于当天");}
    }
    static void version(Long expected,Map<String,Object> row) {
        if(expected==null || expected<0) {throw invalid("请提供有效版本号");}
        long actual=id(row,"version");
        if(expected!=actual) {throw new ApiException(409,"VERSION_CONFLICT","发货单已被修改，请刷新后重试",Map.of("currentVersion",actual));}
    }
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","发货单或来源订单不存在，或无权查看");}
    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException state() {return new ApiException(409,"STATE_CONFLICT","发货单当前状态不允许此操作");}
}
