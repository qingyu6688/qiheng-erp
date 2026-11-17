package cn.erp.shared;

/** 已进入业务状态的事实，仅传出单据标识、版本和状态。 */
public record BusinessMilestone(long tenantId,long companyId,String action,long documentId,int version,String status) {}
