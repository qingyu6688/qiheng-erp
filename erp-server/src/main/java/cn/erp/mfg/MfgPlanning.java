package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

final class MfgPlanning {
    static final int SCALE = 6;
    static final int MAX_LINES = 500;
    static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    static final LocalDate FOREVER = LocalDate.of(9999,12,31);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private MfgPlanning() {}

    static long id(Long value,String label) {
        if(value==null || value<=0) throw invalid("请选择有效的"+label);
        return value;
    }

    static long number(Map<String,Object> row,String name) {return ((Number)row.get(name)).longValue();}

    static BigDecimal decimal(Map<String,Object> row,String name) {return (BigDecimal)row.get(name);}

    static LocalDate date(Map<String,Object> row,String name) {
        var value=row.get(name);
        return value==null ? null : value instanceof java.sql.Date sql ? sql.toLocalDate() : (LocalDate)value;
    }

    static LocalDate day(LocalDate day,String label) {
        if(day==null || day.getYear()<1000 || day.isAfter(FOREVER)) throw invalid("请选择有效的"+label);
        return day;
    }

    static LocalDate today() {return LocalDate.now(BUSINESS_ZONE);}

    static LocalDate end(LocalDate to) {return to==null ? FOREVER : to;}

    static BigDecimal qty(String value,String label) {
        var number=decimal(value,label);
        if(number.signum()<=0) throw invalid(label+"必须大于零");
        return number;
    }

    static BigDecimal percentage(String value,String label) {
        var number=value==null ? BigDecimal.ZERO : decimal(value,label);
        if(number.signum()<0 || number.compareTo(ONE_HUNDRED)>0) throw invalid(label+"需在 0 至 100 之间");
        return number;
    }

    private static BigDecimal decimal(String value,String label) {
        if(value==null || !value.matches("[0-9]{1,18}(?:\\.[0-9]{1,6})?"))
            throw invalid(label+"最多包含 18 位整数和 6 位小数");
        return new BigDecimal(value).setScale(SCALE,RoundingMode.UNNECESSARY);
    }

    static BigDecimal fit(BigDecimal value,RoundingMode rounding) {
        var scaled=value.setScale(SCALE,rounding);
        if(scaled.signum()<=0 || scaled.precision()>24) throw invalid("换算后的数量超出允许范围");
        return scaled;
    }

    static String text(String value,int limit,String label,boolean required) {
        var result=value==null ? null : value.strip();
        if(result!=null && result.isEmpty()) result=null;
        if((result==null && required) || (result!=null && result.length()>limit))
            throw invalid(label+"长度无效");
        return result;
    }

    static long activeWarehouse(JdbcTemplate jdbc,CompanyAccess actor,Long raw) {
        long warehouse=id(raw,"仓库");
        if(!actor.allowsWarehouse(warehouse)) throw missing();
        var rows=jdbc.queryForList("""
                SELECT status FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? FOR SHARE
                """,String.class,actor.tenantId(),actor.companyId(),warehouse);
        if(rows.isEmpty()) throw missing();
        if(!"active".equals(rows.getFirst())) throw invalid("仓库已停用");
        return warehouse;
    }

    static void page(Long cursor,int limit) {
        if((cursor!=null && cursor<=0) || limit<1 || limit>100) throw invalid("分页参数无效");
    }

    static Map<String,Object> page(List<Map<String,Object>> rows,int limit) {
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(cn.erp.shared.ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit ? rows.get(limit-1).get("id").toString() : null);
        return result;
    }

    static ApiException invalid(String message) {return new ApiException(422,"VALIDATION_FAILED",message);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","生产资料不存在或无权查看");}
    static ApiException conflict(String message) {return new ApiException(409,"MFG_STATE_CONFLICT",message);}
}
