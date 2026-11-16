package cn.erp.shared;

/** 在流程事务中发布，消息中心在同一事务持久化，Redis只在提交后广播摘要。 */
public record WorkflowNotice(
        long tenantId,
        long companyId,
        long userId,
        long instanceId,
        Long taskId,
        String kind,
        String eventKey) {}
