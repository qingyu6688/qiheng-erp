package cn.erp.sysadmin;

import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import java.util.*;

final class PlatformRows {
    private PlatformRows() {}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","资料不存在或无权查看");}
    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    static void version(Long expected,Object current) {
        if(expected==null) throw invalid("请提供版本号");
        if(expected.longValue()!=((Number)current).longValue()) throw new ApiException(409,"VERSION_CONFLICT","资料已被修改，请刷新后重试");
    }
    static Map<String,Object> page(List<Map<String,Object>> rows,int limit) {
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;
    }
    static long cursor(Long value,int limit) {
        if(limit<1||limit>200||value!=null&&value<=0) throw invalid("分页条件无效");
        return value==null?Long.MAX_VALUE:value;
    }
}
