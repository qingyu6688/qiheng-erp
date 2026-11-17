package cn.erp.shared;

import java.math.BigDecimal;
import java.util.List;

public interface FinancialReconciliationPort {
    record Difference(String key, BigDecimal expected, BigDecimal actual) {}

    List<Difference> check(ActorScope actor, String type);
}
