package cn.erp.salesfinance;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class FinanceSupport {
    private final JdbcTemplate jdbc;
    public FinanceSupport(JdbcTemplate jdbc) {this.jdbc=jdbc;}

    public static BigDecimal amount(String value) {
        if(value==null || value.length()>30 || !value.matches("[0-9]+(?:\\.[0-9]{1,2})?")) {throw invalid("金额须为最多两位小数的正数");}
        BigDecimal number=new BigDecimal(value).setScale(2);
        if(number.signum()<=0 || number.precision()>24) {throw invalid("金额必须大于零且在允许范围内");}
        return number;
    }

    public static BigDecimal quantity(String value) {
        if(value==null || value.length()>32 || !value.matches("[0-9]+(?:\\.[0-9]{1,6})?")) {throw invalid("数量须为最多六位小数的正数");}
        BigDecimal number=new BigDecimal(value).setScale(6);
        if(number.signum()<=0 || number.precision()>24) {throw invalid("数量超出允许范围");}
        return number;
    }

    public static void date(LocalDate date) {
        if(date==null || date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) {throw invalid("业务日期须为今天或之前");}
    }

    public long period(CompanyAccess scope,LocalDate date) {
        date(date);
        var rows=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=? AND status='open' FOR SHARE",
                Long.class,scope.tenantId(),scope.companyId(),date,date);
        if(rows.size()!=1) {throw new ApiException(422,"PERIOD_CLOSED","业务日期没有对应的开放期间");}
        return rows.getFirst();
    }

    public Map<String,Object> find(CompanyAccess scope,String table,long id,boolean lock) {
        if(!Set.of("receipt","refund_request","sales_return","settlement_allocation").contains(table)) {throw invalid("资源类型无效");}
        var rows=jdbc.queryForList("SELECT * FROM "+table+" WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()) {throw missing();}
        return rows.getFirst();
    }

    public static void version(Map<String,Object> row,Long version) {
        if(version==null || version<0) {throw invalid("请提供有效的版本号");}
        if(((Number)row.get("version")).longValue()!=version) {
            throw new ApiException(409,"VERSION_CONFLICT","单据已被他人修改，请刷新后重试",Map.of("currentVersion",row.get("version")));
        }
    }
    public static void state(Map<String,Object> row,String... states) {
        if(!Set.of(states).contains(row.get("status").toString())) {throw new ApiException(409,"STATE_CONFLICT","当前单据状态不允许此操作");}
    }
    public static long id(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
    public static BigDecimal decimal(Map<String,Object> row,String field) {return (BigDecimal)row.get(field);}
    public static Map<String,Object> row(Map<String,Object> raw) {
        var result=new LinkedHashMap<String,Object>();
        raw.forEach((key,value)->{
            if(Set.of("tenant_id","company_id").contains(key)) {return;}
            StringBuilder field=new StringBuilder();boolean upper=false;
            for(char c:key.toCharArray()) {if(c=='_') {upper=true;} else {field.append(upper?Character.toUpperCase(c):c);upper=false;}}
            Object output=value;
            if((key.equals("id")||key.endsWith("_id")||key.endsWith("_by")) && value!=null) {output=value.toString();}
            if(value instanceof BigDecimal decimal) {output=decimal.toPlainString();}
            if(value instanceof java.sql.Date date) {output=date.toLocalDate().toString();}
            if(value instanceof java.sql.Timestamp time) {output=time.toInstant().toString();}
            if(value instanceof LocalDateTime time) {output=time.toInstant(ZoneOffset.UTC).toString();}
            result.put(field.toString(),output);
        });
        return result;
    }
    public static long cursor(Long cursor,int limit) {
        if(limit<1 || limit>200 || cursor!=null&&cursor<=0) {throw invalid("分页条件无效");}
        return cursor==null?Long.MAX_VALUE:cursor;
    }
    public static Map<String,Object> page(List<Map<String,Object>> rows,int limit) {
        var items=rows.stream().limit(limit).map(FinanceSupport::row).toList();
        var page=new LinkedHashMap<String,Object>();page.put("items",items);
        page.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return page;
    }
    public static ApiException missing() {return new ApiException(404,"NOT_FOUND","单据不存在或无权查看");}
    public static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
