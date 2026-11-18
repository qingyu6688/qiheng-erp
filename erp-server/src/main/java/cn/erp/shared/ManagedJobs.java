package cn.erp.shared;

import java.util.function.IntPredicate;
import java.util.function.LongConsumer;

/** 固定动作由所属模块登记，控制台不能指定任意类名、脚本或方法。 */
public interface ManagedJobs {
    void register(String code, LongConsumer action);

    void registerBatch(String code, BatchAction action);

    @FunctionalInterface
    interface BatchAction {
        void run(long tenant, Batch batch);
    }

    interface Batch {
        default void run(long company, String itemKey, long target, Runnable action) {
            run(company, itemKey, target, action, minutes -> true);
        }

        /** 业务动作自行管理事务，重试时间在业务回滚后另开事务保存。 */
        void run(long company, String itemKey, long target, Runnable action, IntPredicate retry);
    }
}
