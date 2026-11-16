package cn.erp.shared;

import java.util.*;

/** 业务模块仅依赖此端口，流程不能修改业务事实或替代原审批校验。 */
public interface WorkflowPort {
    void start(
            ActorScope actor,
            String businessType,
            long businessId,
            Map<String, List<Long>> selections);

    void guard(ActorScope actor, String businessType, long businessId, String action);
}
