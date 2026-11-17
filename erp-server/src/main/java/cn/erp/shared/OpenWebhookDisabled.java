package cn.erp.shared;

/** 开放平台推送连续失败停用，记录用于向有权管理员通知。 */
public record OpenWebhookDisabled(long tenantId,Long companyId,long webhookId,long version) {}
