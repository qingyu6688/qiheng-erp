package cn.erp.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 保留成本维度，超出单张限制时以应付对边拆成可独立冲销的平衡组。 */
public final class SupplementalCostJournals {
    private static final int MAX_LINES=1000;
    private final String memo;
    private final List<List<LedgerService.Line>> batches=new ArrayList<>();
    private List<LedgerService.Line> current=new ArrayList<>();
    public SupplementalCostJournals(String memo) {this.memo=memo;}
    public void add(List<LedgerService.Line> costs,long partner,long sourceLine) {
        for(int from=0;from<costs.size();from+=MAX_LINES-1) {
            var balanced=new ArrayList<>(costs.subList(from,Math.min(from+MAX_LINES-1,costs.size())));
            BigDecimal net=balanced.stream().map(row->row.direction().equals("D")?row.amount():row.amount().negate()).reduce(BigDecimal.ZERO,BigDecimal::add);
            if(net.signum()!=0) balanced.add(new LedgerService.Line("AP",net.signum()>0?"C":"D",net.abs(),partner,null,null,null,sourceLine,memo));
            if(current.size()+balanced.size()>MAX_LINES) flush();
            current.addAll(balanced);
        }
    }
    public List<List<LedgerService.Line>> batches() {flush();return List.copyOf(batches);}
    private void flush() {
        if(current.isEmpty()) return;
        batches.add(List.copyOf(current));current=new ArrayList<>();
    }
}
