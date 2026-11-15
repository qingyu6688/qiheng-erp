package cn.erp.shared;

/** 共享写入机制所需的服务端身份信息。 */
public interface ActorScope {
    long tenantId();
    long userId();
    long companyId();
}
