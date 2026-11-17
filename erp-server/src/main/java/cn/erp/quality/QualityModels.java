package cn.erp.quality;

import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QualityModels {
    private QualityModels() {}

    record Item(String code,String name,String type,String unit,String method,String status,Long version) {}
    record Check(String itemId,String standardValue,String lowerLimit,String upperLimit,Boolean required) {}
    record Template(String code,String name,String sampling,String samplingValue,String status,List<Check> checks,Long version) {}
    record Rule(String targetType,String targetId,String supplierId,String templateId,Boolean inspectionRequired,String status,Long version) {}
    record Value(String itemId,String measuredValue,String judgement) {}
    record Defect(String defectCode,String qty,String remark) {}
    record Submission(Long version,String qualifiedQty,String unqualifiedQty,String concessionQty,String concessionReason,
                      List<Value> values,List<Defect> defects) {}
    record Decision(Long version,String reason) {}
    record Disposition(Long version,String action,String qty,String reason) {}

    record Criterion(String itemId,String code,String name,String type,String unit,String method,String standardValue,
                     String lowerLimit,String upperLimit,boolean required) {}
    record Snapshot(String templateId,String code,String name,String sampling,String samplingValue,List<Criterion> checks) {}

    static ApiException invalid(String msg) {return new ApiException(400,"VALIDATION_FAILED",msg);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","质量资料不存在或无权查看");}
    static ApiException conflict(String msg) {return new ApiException(409,"STATE_CONFLICT",msg);}
    static ApiException forbidden() {return new ApiException(403,"FORBIDDEN","没有执行质量操作的权限");}
    static long id(String value) {
        if(value!=null&&value.matches("[1-9][0-9]{0,18}"))try {return Long.parseLong(value);}
        catch(NumberFormatException ex){throw invalid("编号超出范围");}
        throw invalid("编号无效");
    }
    static long id(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
    static BigDecimal decimal(Map<String,Object> row,String key){return (BigDecimal)row.get(key);}
    static BigDecimal number(String value,String label,boolean negative) {
        if (value==null||!value.matches((negative?"-?":"")+"[0-9]{1,18}(\\.[0-9]{1,6})?"))throw invalid(label+"格式无效，最多六位小数");
        return new BigDecimal(value).setScale(6,RoundingMode.UNNECESSARY);
    }
    static BigDecimal optionalNumber(String value,String label,boolean negative) {
        return blank(value)?null:number(value,label,negative);
    }
    static String text(String value,int max,String label,boolean required) {
        if (blank(value)) {if(required) throw invalid("请填写"+label);return null;}
        if (value.length()>max||value.indexOf(0)>=0)throw invalid(label+"最多允许"+max+"个字符");
        return value.strip();
    }
    static boolean blank(String value){return value==null||value.isBlank();}
    static String status(String value){return option(value==null?"active":value,Set.of("active","disabled"),"状态");}
    static String option(String value,Set<String> allowed,String label){if(value==null||!allowed.contains(value))throw invalid(label+"无效");return value;}
    static String code(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,32}"))throw invalid("编码须为1至32位字母、数字或连字符");return value;}
    static void version(Long expected,Map<String,Object> row){
        if(expected==null||expected<0)throw invalid("请提供有效的版本号");
        if(expected!=id(row,"version"))throw new ApiException(409,"VERSION_CONFLICT","资料已变化，请刷新后重试");
    }
}
