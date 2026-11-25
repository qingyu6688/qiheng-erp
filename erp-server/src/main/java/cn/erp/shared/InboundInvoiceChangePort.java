package cn.erp.shared;

/** 采购来源在财务行加锁前协调到票额度，成功过账后释放需重新核对的关系。 */
public interface InboundInvoiceChangePort {
    void lock(ActorScope actor);
    void changed(ActorScope actor, String sourceType, long sourceId, String reason);
}
