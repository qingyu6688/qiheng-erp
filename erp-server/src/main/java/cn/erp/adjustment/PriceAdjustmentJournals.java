package cn.erp.adjustment;

import java.util.List;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SupplementalCostJournals;

/** 保留调价调用入口，采购补充成本共用平衡凭证拆分规则。 */
final class PriceAdjustmentJournals {
    private final SupplementalCostJournals journals=new SupplementalCostJournals("采购价格调整");
    void add(List<LedgerService.Line> costs,long partner,long sourceLine) {journals.add(costs,partner,sourceLine);}
    List<List<LedgerService.Line>> batches() {return journals.batches();}
}
