package cn.erp.shared;

import java.util.List;

/** 账龄报表复用业务来源权限，只读取当前公司的区间配置。 */
public interface AgingPolicyPort {
    List<Integer> bounds(ActorScope actor);
}
