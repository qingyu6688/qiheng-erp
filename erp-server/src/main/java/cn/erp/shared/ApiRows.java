package cn.erp.shared;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** 平台通用响应投影，保留金额精度并统一使用字符串标识和UTC时间。 */
public final class ApiRows {
    private ApiRows() {}
    public static Map<String,Object> row(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();
        raw.forEach((key,value)->{
            if(Set.of("tenant_id","company_id").contains(key)) return;
            StringBuilder field=new StringBuilder();boolean upper=false;
            for(char c:key.toCharArray()) {if(c=='_') upper=true;else {field.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object output=value;
            if(value!=null&&(key.equals("id")||key.endsWith("_id")||key.endsWith("_by"))) output=value.toString();
            if(value instanceof BigDecimal decimal) output=decimal.toPlainString();
            if(value instanceof java.sql.Date date) output=date.toLocalDate().toString();
            if(value instanceof java.sql.Timestamp time) output=time.toInstant().toString();
            if(value instanceof LocalDateTime time) output=time.toInstant(ZoneOffset.UTC).toString();
            result.put(field.toString(),output);
        });return result;
    }
}
