package cn.erp.shared;

/** 催收提醒只传业务键，消息查询时重新检查来源未清项和当前数据范围。 */
public record CollectionNotice(
        long tenantId, long companyId, long userId, long planId, String eventKey) {}
