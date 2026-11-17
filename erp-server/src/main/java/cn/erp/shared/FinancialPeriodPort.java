package cn.erp.shared;

import java.util.List;
import java.util.Map;

/** 原业务关账也通过此端口执行财务门槛；未建账公司保持旧行为。 */
public interface FinancialPeriodPort {
    default void authorize(ActorScope actor, String action) {}

    void prepare(ActorScope actor, long period);

    List<Map<String, Object>> checks(ActorScope actor, long period);

    default List<Map<String, Object>> closeReadiness(ActorScope actor, long period) {return List.of();}

    void close(ActorScope actor, long period);

    void reopen(ActorScope actor, long period, String reason);
}
