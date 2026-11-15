package cn.erp.receiving;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

final class ReceivingSupport {
    static final BigDecimal ZERO=BigDecimal.ZERO.setScale(2);
    private ReceivingSupport() {}
    static long id(Map<String,Object> row,String name) {return ((Number)row.get(name)).longValue();}
    static BigDecimal decimal(Map<String,Object> row,String name) {return row.get(name)==null?ZERO:(BigDecimal)row.get(name);}
    static LocalDate date(Map<String,Object> row,String name) {return row.get(name) instanceof LocalDate value?value:((java.sql.Date)row.get(name)).toLocalDate();}
    static void date(LocalDate value) {if(value==null||value.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {throw invalid("业务日期不能晚于当天");}}
    static BigDecimal quantity(String text) {
        if(text==null||text.length()>32||!text.matches("[0-9]+(?:\\.[0-9]{1,6})?")) {throw invalid("数量须为最多六位小数的正数");}
        var value=new BigDecimal(text).setScale(6);
        if(value.signum()<=0||value.precision()>24) {throw invalid("数量精度或范围有误");}return value;
    }
    static void version(Long value,Map<String,Object> row) {
        if(value==null||value<0) {throw invalid("请提供有效版本号");}
        if(value!=id(row,"version")) {throw new ApiException(409,"VERSION_CONFLICT","单据已被修改，请刷新后重试",Map.of("currentVersion",row.get("version")));}
    }
    static void state(Map<String,Object> row,String... states) {if(!Set.of(states).contains(row.get("status"))) {throw new ApiException(409,"STATE_CONFLICT","当前单据状态不允许此操作");}}
    static ApiException invalid(String text) {return new ApiException(400,"VALIDATION_FAILED",text);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","单据或来源不存在，或无权查看");}
    static Map<String,Object> view(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();raw.forEach((name,value)->{
            if(Set.of("tenant_id","company_id").contains(name)) {return;}
            var key=new StringBuilder();boolean upper=false;
            for(char c:name.toCharArray()) {if(c=='_') {upper=true;}else{key.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object visible=value;
            if(value!=null&&(name.equals("id")||name.endsWith("_id")||name.endsWith("_by"))) {visible=value.toString();}
            if(value instanceof BigDecimal number) {visible=number.toPlainString();}
            if(value instanceof java.sql.Date day) {visible=day.toLocalDate().toString();}
            if(value instanceof LocalDateTime time) {visible=time.toInstant(ZoneOffset.UTC).toString();}
            result.put(key.toString(),visible);
        });return result;
    }
    static String warehouseFilter(CompanyAccess scope,String alias,List<Object> args) {
        if(scope.allWarehouses()) {return "";}
        if(scope.warehouseIds().isEmpty()) {return " AND 1=0";}
        args.addAll(scope.warehouseIds());return " AND "+alias+"warehouse_id IN ("+String.join(",",Collections.nCopies(scope.warehouseIds().size(),"?"))+")";
    }
}
