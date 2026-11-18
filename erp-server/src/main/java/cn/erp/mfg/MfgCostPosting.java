package cn.erp.mfg;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.mfg.MfgCostMath.*;

@Component
class MfgCostPosting {
    private static final int POSTING_CHUNK=400;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final LedgerService ledger;

    MfgCostPosting(JdbcTemplate jdbc,ObjectMapper json,InventoryService inventory,LedgerService ledger) {
        this.jdbc=jdbc;this.json=json;this.inventory=inventory;this.ledger=ledger;
    }

    record Operations(List<Valuation> inventory,List<Valuation> issued,List<Long> journals) {}

    long post(CompanyAccess actor,MfgCostFacts.Period period,Plan plan) {
        long id=Ids.next();
        applyInventory(actor,period,id,plan.inventory(),false);
        var entries=new ArrayList<LedgerService.Line>();
        add(entries,"WIP","D",plan.labor().add(plan.overhead()),null,null);
        add(entries,"PRODUCTION_LABOR","C",plan.labor(),null,null);
        add(entries,"PRODUCTION_OVERHEAD","C",plan.overhead(),null,null);
        var journals=new ArrayList<Long>();
        journal(actor,period,id,entries,journals);
        valuationJournals(actor,period,id,plan.inventory(),"INVENTORY",journals);
        valuationJournals(actor,period,id,plan.issued(),"PRODUCTION_VARIANCE",journals);
        var shortCloseExpense=plan.orders().stream().map(OrderCost::shortCloseExpense)
                .filter(java.util.Objects::nonNull).reduce(ZERO,BigDecimal::add);
        if(shortCloseExpense.signum()!=0) {
            entries.clear();
            add(entries,"PRODUCTION_VARIANCE","D",shortCloseExpense,null,null);
            add(entries,"WIP","C",shortCloseExpense,null,null);
            journal(actor,period,id,entries,journals);
        }
        var operations=new Operations(plan.inventory(),plan.issued(),journals);
        jdbc.update("INSERT INTO mfg_cost_posting(id,tenant_id,company_id,period_id,journal_id,operations) VALUES(?,?,?,?,?,?)",
                id,actor.tenantId(),actor.companyId(),period.id(),journals.isEmpty()?null:journals.getFirst(),json.writeValueAsString(operations));
        return id;
    }

    void undo(CompanyAccess actor,MfgCostFacts.Period period,long prior) {
        if (period.reopenCount()<1) throw invalid("请先按原关账流程重开已关闭的会计期间");
        var row=jdbc.queryForMap("""
                SELECT operations,created_at FROM mfg_cost_posting
                WHERE tenant_id=? AND company_id=? AND period_id=? AND id=? AND reversed_by IS NULL FOR UPDATE
                """,actor.tenantId(),actor.companyId(),period.id(),prior);
        var operations=json.readValue(row.get("operations").toString(),Operations.class);
        verifyUndo(actor,period,row.get("created_at"),operations);
        long id=Ids.next();
        applyInventory(actor,period,id,operations.inventory(),true);
        int revision=0;
        for (long journal : operations.journals()) ledger.reverse(actor,journal,"mfg_cost_reopen",id,revision++,period.id(),period.end());
        jdbc.update("UPDATE mfg_cost_posting SET reversed_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                id,actor.tenantId(),actor.companyId(),prior);
    }

    private void verifyUndo(CompanyAccess actor,MfgCostFacts.Period period,Object posted,Operations operations) {
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM mfg_cost_period c JOIN accounting_period p ON p.tenant_id=c.tenant_id
                  AND p.company_id=c.company_id AND p.id=c.period_id WHERE c.tenant_id=? AND c.company_id=?
                  AND p.period>? AND c.status='posted')
                """,Boolean.class,actor.tenantId(),actor.companyId(),period.number())))
            throw invalid("请先冲回后续期间的生产成本");
        for (var value : operations.inventory()) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inventory_movement WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                      AND sku_id=? AND posted_at>=? AND on_hand_delta<>0 AND movement_type NOT IN ('location_move_out','location_move_in'))
                    """,Boolean.class,actor.tenantId(),actor.companyId(),value.warehouseId(),value.skuId(),posted)))
                throw invalid("完工品成本已被后续收发使用，请先按会计顺序处理后续单据");
        }
    }

    private void applyInventory(CompanyAccess actor,MfgCostFacts.Period period,long id,List<Valuation> values,boolean reverse) {
        var keys=values.stream().map(v->new Key(v.warehouseId(),v.skuId())).distinct().sorted().toList();
        for (int at=0;at<keys.size();at+=POSTING_CHUNK) inventory.lockKeys(actor,keys.subList(at,Math.min(at+POSTING_CHUNK,keys.size())));
        for (var value : values) {
            var source=new Source("mfg_cost_posting",id,Ids.next());
            inventory.productionVariance(actor,new Posting(new Key(value.warehouseId(),value.skuId()),source,period.end(),period.id(),"production_variance"),
                    reverse?value.amount().negate():value.amount());
        }
    }

    private void valuationJournals(CompanyAccess actor,MfgCostFacts.Period period,long id,List<Valuation> values,String account,List<Long> journals) {
        var entries=new ArrayList<LedgerService.Line>();
        for (var value : values) {
            add(entries,account,"D",value.amount(),value.warehouseId(),value.skuId());
            add(entries,"WIP","C",value.amount(),null,null);
            if (entries.size()>=POSTING_CHUNK) { journal(actor,period,id,entries,journals);entries.clear(); }
        }
        journal(actor,period,id,entries,journals);
    }

    private void journal(CompanyAccess actor,MfgCostFacts.Period period,long id,List<LedgerService.Line> lines,List<Long> journals) {
        if (!lines.isEmpty()) journals.add(ledger.post(actor,new LedgerService.Posting("mfg_cost_posting",id,journals.size(),period.id(),period.end(),lines)));
    }

    private static void add(List<LedgerService.Line> lines,String account,String direction,BigDecimal amount,Long warehouse,Long sku) {
        if (amount.signum()==0) return;
        if (amount.signum()<0) direction=direction.equals("D")?"C":"D";
        lines.add(new LedgerService.Line(account,direction,amount.abs(),null,null,warehouse,sku,null,"生产月末成本"));
    }
}
