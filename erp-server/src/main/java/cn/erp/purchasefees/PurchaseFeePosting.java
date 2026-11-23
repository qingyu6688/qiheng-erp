package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.accounting.SupplementalCostJournals;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ReceiptCostAllocator;
import cn.erp.shared.*;
import static cn.erp.purchasefees.PurchaseFeeQueries.*;

@Component
class PurchaseFeePosting {
    private final JdbcTemplate jdbc;
    private final PurchaseFeeWriter writer;
    private final ReceiptCostAllocator costs;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final SettlementService settlement;
    PurchaseFeePosting(JdbcTemplate jdbc,PurchaseFeeWriter writer,ReceiptCostAllocator costs,InventoryService inventory,LedgerService ledger,SettlementService settlement) {
        this.jdbc=jdbc;this.writer=writer;this.costs=costs;this.inventory=inventory;this.ledger=ledger;this.settlement=settlement;
    }
    List<Map<String,Object>> preview(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> allocations) {
        FinancialScope.require(actor,"cost:view");writer.revalidate(actor,header,allocations);
        var shares=trace(actor,allocations);
        Map<Long,String> names=new HashMap<>();
        jdbc.query("SELECT id,name FROM warehouse WHERE tenant_id=? AND company_id=?",row->{names.put(row.getLong("id"),row.getString("name"));},actor.tenantId(),actor.companyId());
        return allocations.stream().map(line->Map.<String,Object>of("allocationId",line.get("id").toString(),"receiptLineId",line.get("receipt_line_id").toString(),
                "amount",amount(line,"amount").toPlainString(),"taxAmount",amount(line,"tax_amount").toPlainString(),"costAmount",amount(line,"cost_amount").toPlainString(),
                "allocations",plan(actor,line,shares).stream().map(part->Map.of("warehouseId",Long.toString(part.warehouseId()),"warehouseName",names.get(part.warehouseId()),
                        "inStockQty",part.inStockQty().toPlainString(),"stockAmount",part.stockAmount().toPlainString(),"issuedAmount",part.issuedAmount().toPlainString())).toList())).toList();
    }
    void post(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> allocations,List<Map<String,Object>> fees) {
        FinancialScope.require(actor,TYPE+":post");
        var day=date(header,"business_date");long period=period(actor,day);ledger.lockOpenPeriod(actor,period,day);
        writer.revalidate(actor,header,allocations);
        costs.lock(actor,allocations.stream().map(line->number(line,"sku_id")).collect(Collectors.toSet()));
        var shares=trace(actor,allocations);var journals=new SupplementalCostJournals("采购费用分摊");
        BigDecimal stock=BigDecimal.ZERO,issued=BigDecimal.ZERO;
        for(var allocation:allocations) {
            var entries=new ArrayList<LedgerService.Line>();
            for(var part:plan(actor,allocation,shares)) {
                applyCost(actor,header,allocation,part,period,day,entries,false);
                stock=stock.add(part.stockAmount());issued=issued.add(part.issuedAmount());
            }
            add(entries,"INPUT_VAT",flag(header,"cost_tax_inclusive")?BigDecimal.ZERO:amount(allocation,"tax_amount"),null,null,number(allocation,"id"));
            journals.add(entries,number(header,"partner_id"),number(allocation,"id"));
        }
        for(var fee:fees) payable(actor,number(header,"partner_id"),"purchase_fee_doc_line",number(header,"id"),number(fee,"id"),amount(fee,"amount"),amount(fee,"tax_amount"),day,period,
                header.get("due_date")==null?null:date(header,"due_date"));
        int revision=0;
        for(var entries:journals.batches()) ledger.post(actor,new LedgerService.Posting(TYPE,number(header,"id"),revision++,period,day,entries));
        jdbc.update("UPDATE purchase_fee_doc SET status='posted',stock_cost=?,issued_cost=?,period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                stock,issued,period,actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),header.get("id"));
    }
    private Map<Long,Map<Long,BigDecimal>> trace(CompanyAccess actor,List<Map<String,Object>> allocations) {
        var result=new HashMap<Long,Map<Long,BigDecimal>>();
        var grouped=allocations.stream().collect(Collectors.groupingBy(line->number(line,"sku_id"),Collectors.mapping(line->number(line,"receipt_line_id"),Collectors.toSet())));
        grouped.forEach((sku,receipts)->result.putAll(costs.shares(actor,sku,receipts)));return result;
    }
    private List<ReceiptCostAllocator.Part> plan(CompanyAccess actor,Map<String,Object> allocation,Map<Long,Map<Long,BigDecimal>> shares) {
        return costs.plan(actor,number(allocation,"sku_id"),number(allocation,"source_warehouse_id"),amount(allocation,"source_base_qty"),amount(allocation,"cost_amount"),shares.getOrDefault(number(allocation,"receipt_line_id"),Map.of()));
    }
    void applyCost(CompanyAccess actor,Map<String,Object> header,Map<String,Object> allocation,ReceiptCostAllocator.Part part,long period,LocalDate day,List<LedgerService.Line> entries,boolean automatic) {
        long id=Ids.next(),document=number(header,"id"),allocationId=number(allocation,"id"),sku=number(allocation,"sku_id");
        var movements=jdbc.queryForList("SELECT id,posted_at FROM inventory_movement WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND on_hand_delta<>0"
                +" AND movement_type NOT IN('location_move_in','location_move_out') ORDER BY posted_at DESC,id DESC LIMIT 1",actor.tenantId(),actor.companyId(),part.warehouseId(),sku);
        jdbc.update("INSERT INTO purchase_fee_cost(id,tenant_id,company_id,document_id,allocation_id,warehouse_id,sku_id,in_stock_qty,stock_amount,issued_amount,movement_cutoff_id,movement_cutoff_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                id,actor.tenantId(),actor.companyId(),document,allocationId,part.warehouseId(),sku,part.inStockQty(),part.stockAmount(),part.issuedAmount(),
                movements.isEmpty()?0:movements.getFirst().get("id"),movements.isEmpty()?null:movements.getFirst().get("posted_at"));
        inventory.purchaseFeeCost(actor,new InventoryModels.Posting(new InventoryModels.Key(part.warehouseId(),sku),
                new InventoryModels.Source(automatic?"goods_receipt_fee":"purchase_fee_cost",automatic?number(header,"source_receipt_id"):document,id),day,period,"purchase_fee"),part.stockAmount(),part.issuedAmount());
        jdbc.update("UPDATE purchase_fee_allocation SET stock_cost=stock_cost+?,issued_cost=issued_cost+? WHERE tenant_id=? AND company_id=? AND id=?",part.stockAmount(),part.issuedAmount(),actor.tenantId(),actor.companyId(),allocationId);
        add(entries,"INVENTORY",part.stockAmount(),part.warehouseId(),sku,allocationId);
        add(entries,"COST_VARIANCE",part.issuedAmount(),null,null,allocationId);
    }
    long payable(CompanyAccess actor,long partner,String source,long document,long line,BigDecimal gross,BigDecimal tax,LocalDate day,long period,LocalDate due) {
        long id=Ids.next();
        jdbc.update("INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by,due_date) VALUES(?,?,?,?,'charge',?,?,?,?,?,?,?,?,?,?)",
                id,actor.tenantId(),actor.companyId(),partner,source,document,line,gross,gross.subtract(tax),tax,day,period,actor.userId(),due);
        return settlement.open(actor,new SettlementService.OpenItem("payable",id,partner,"C",gross,day));
    }
    private long period(CompanyAccess actor,LocalDate day) {
        var rows=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,actor.tenantId(),actor.companyId(),day,day);
        if(rows.size()!=1) throw new ApiException(422,"PERIOD_NOT_FOUND","费用日期没有对应会计期间");return rows.getFirst();
    }
    static void add(List<LedgerService.Line> entries,String account,BigDecimal value,Long warehouse,Long sku,long line) {
        if(value.signum()!=0) entries.add(new LedgerService.Line(account,"D",value,null,null,warehouse,sku,line,"采购费用分摊"));
    }
}
