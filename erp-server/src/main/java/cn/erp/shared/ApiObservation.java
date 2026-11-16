package cn.erp.shared;

import java.util.List;

/** 日志只传递服务端路由和定位信息，禁止携带请求内容或原始异常消息。 */
public record ApiObservation(long tenantId,long userId,long companyId,String method,String route,int status,long duration,String requestId,String traceId,String errorClass,List<String> frames) {
    public ApiObservation {frames=List.copyOf(frames);}
}
