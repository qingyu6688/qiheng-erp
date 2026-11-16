package cn.erp.shared;

/** 业务增强只读取账套身份，不依赖总账实现或修改总账凭证。 */
public interface FinancialBookPort {
    record Info(long id, String standard, String status, long startPeriodId, boolean confirmed) {
        public Info(long id, String standard, String status, long startPeriodId) {
            this(id, standard, status, startPeriodId, false);
        }
    }

    record ControlBalance(java.time.LocalDate start, java.math.BigDecimal amount) {}

    Info information(ActorScope actor);

    default void lockControls(ActorScope actor) {}

    default ControlBalance allowanceBase(
            ActorScope actor, long customer, java.time.LocalDate asOf) {
        return null;
    }
}
