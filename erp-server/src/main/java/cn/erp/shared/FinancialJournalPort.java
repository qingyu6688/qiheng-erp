package cn.erp.shared;

/** 运营投影只排入独立总账任务，科目映射错误不得回滚已完成的运营投影。 */
@FunctionalInterface
public interface FinancialJournalPort {
    void projected(long tenant, long company, long journal);

    default void validatePosting(ActorScope actor, long journal) {}
}
