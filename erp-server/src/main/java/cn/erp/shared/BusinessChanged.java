package cn.erp.shared;

/** 已在业务事务中完成的命令，由消息模块在同一事务写入通知事件。 */
public record BusinessChanged(long tenantId,long companyId,long userId,String operation,long documentId) {}
