package cn.erp.shared;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public interface ProductionCostPort {
    void requireStockPosting(ActorScope actor,long periodId);

    BigDecimal estimatedExpenseRate(ActorScope actor,long workOrderId,long periodId);

    List<Map<String,Object>> closingChecks(ActorScope actor,long periodId);
}
