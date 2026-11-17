package cn.erp.shared;

/** CRM 只向站内信传递消息类型和去重键，客户资料继续经 CRM 接口授权后读取。 */
public record CrmReminder(long tenantId, long companyId, long userId, String kind, String eventKey) {}
