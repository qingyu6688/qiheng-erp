package cn.erp.shared;

import java.util.Map;

/** 业务先锁客户额度，再锁单据；信用检查仍由服务端执行，流程只能提供本轮审批证据。 */
public interface CreditPort {
    default void lock(ActorScope actor, long customer) {}

    default Map<String, Object> inspect(ActorScope actor, long order) {
        return Map.of("overCreditLimit", false);
    }

    default void order(ActorScope actor, long order, String stage) {}

    default java.util.List<Map<String, Object>> warnings(ActorScope actor, long customer) {
        return java.util.List.of();
    }

    default void shipment(ActorScope actor, long customer) {}
}
