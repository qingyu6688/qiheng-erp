package cn.erp.shared;

import java.util.Map;

/** 跨实例通知仅含可公开的变更摘要，正文始终通过带权限的接口读取。 */
public record UserSignal(long tenantId,long userId,String type,Map<String,String> data) {
    public UserSignal {data=Map.copyOf(data);}
}
