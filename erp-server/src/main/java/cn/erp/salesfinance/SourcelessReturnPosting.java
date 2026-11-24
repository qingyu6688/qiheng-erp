package cn.erp.salesfinance;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.Ids;
import cn.erp.shared.PutawayPort;
import static cn.erp.inventory.InventoryModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

/** 无原单入库、客户信用额与运营分录仍由原库存及账本服务在同一事务处理。 */
@Component
class SourcelessReturnPosting {
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final PutawayPort putaway;

    SourcelessReturnPosting(JdbcTemplate jdbc,InventoryService inventory,LedgerService ledger,SettlementService settlement,PutawayPort putaway) {
        this.jdbc=jdbc;this.inventory=inventory;this.ledger=ledger;this.settlement=settlement;this.putaway=putaway;
    }

    void post(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines,long period,LocalDate day) {
        long id=id(header,"id"),warehouse=id(header,"warehouse_id"),customer=id(header,"customer_id");
        Long receiving=lines.stream().anyMatch(line->"passed".equals(line.get("quality_result")))?putaway.receivingLocation(actor,warehouse):null;
        var intake=new ArrayList<Inbound>();var stockCosts=new TreeMap<Long,BigDecimal>();
        for(var line:lines) {
            long lineId=id(line,"id"),sku=id(line,"sku_id");var cost=decimal(line,"cost_amount");
            var posting=new Posting(new Key(warehouse,sku),new Source("sales_return_line",id,lineId),day,period,"sales_return_in");
            intake.add(new Inbound(posting,decimal(line,"base_qty"),cost,selection(line,receiving),"passed".equals(line.get("quality_result"))?null:"sales_reject"));
            stockCosts.merge(sku,cost,BigDecimal::add);
        }
        inventory.receiveBatch(actor,intake);
        for(var line:lines) credit(actor,header,line,period,day);
        var journal=new ArrayList<LedgerService.Line>();
        add(journal,"AR","C",decimal(header,"credit_amount"),customer,null,null);
        add(journal,"SALES_REVENUE","D",decimal(header,"untaxed_amount"),null,null,null);
        add(journal,"OUTPUT_VAT","D",decimal(header,"tax_amount"),null,null,null);
        stockCosts.forEach((sku,cost)->add(journal,"INVENTORY","D",cost,null,warehouse,sku));
        add(journal,"COGS","C",decimal(header,"cost_amount"),null,null,null);
        if(!journal.isEmpty()) ledger.post(actor,new LedgerService.Posting("sales_return",id,0,period,day,journal));
        jdbc.update("UPDATE sales_return SET status='posted',business_date=?,period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                day,period,actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),id);
        putaway.createFromReceipt(actor,warehouse,"sales_return",id);
    }

    private void credit(CompanyAccess actor,Map<String,Object> header,Map<String,Object> line,long period,LocalDate day) {
        BigDecimal amount=decimal(line,"credit_amount");if(amount.signum()==0) return;
        long receivable=Ids.next(),customer=id(header,"customer_id");
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
                  amount,untaxed_amount,tax_amount,business_date,period_id,created_by)
                VALUES(?,?,?,?,'credit','sales_return_line',?,?,?,?,?,?,?,?)
                """,receivable,actor.tenantId(),actor.companyId(),customer,header.get("id"),line.get("id"),amount,line.get("untaxed_amount"),line.get("tax_amount"),day,period,actor.userId());
        settlement.open(actor,new SettlementService.OpenItem("receivable",receivable,customer,"C",amount,day));
    }

    private StockSelection selection(Map<String,Object> line,Long receiving) {
        var requested=inventory.decodeSelection(line.get("stock_selection"));
        if(receiving==null || !"passed".equals(line.get("quality_result")) || requested!=null && requested.locationId()!=null) return requested;
        if(requested==null) return new StockSelection(null,null,null,null,receiving,List.of());
        return new StockSelection(requested.batchId(),requested.batchNo(),requested.productionDate(),requested.expiryDate(),receiving,requested.serials());
    }

    private static void add(List<LedgerService.Line> lines,String account,String direction,BigDecimal amount,Long partner,Long warehouse,Long sku) {
        if(amount.signum()>0) lines.add(new LedgerService.Line(account,direction,amount,partner,null,warehouse,sku,null,"无原单销售退货"));
    }
}
