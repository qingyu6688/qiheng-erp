package cn.erp.shared;

import java.util.Map;

/** 业务适配器重新校验来源权限并调用原业务命令；流程不直接写凭证或库存。 */
public interface WorkflowBusiness {
    String businessType();

    Map<String, Object> snapshot(ActorScope actor, long id);

    default Map<String, Object> preview(
            ActorScope actor, String sourceType, long sourceId, java.time.LocalDate date) {
        throw new ApiException(400, "VALIDATION_FAILED", "此类单据不支持创建前流程预览");
    }

    void decide(ActorScope actor, long id, String outcome, String comment, String key);
}
