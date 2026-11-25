package cn.erp.purchasefees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.accounting.SupplementalCostJournals;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.ReceiptCostAllocator;
import cn.erp.shared.DocumentNumbers;
import cn.erp.shared.Ids;
import cn.erp.shared.PaymentTerms;
import static cn.erp.purchasefees.PurchaseFeeQueries.*;

/** 自动费用与收货共享事务、账本及冲销入口，不开放独立写入接口。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class ReceiptFeePosting {
    public record Result(boolean enabled,BigDecimal amount,BigDecimal tax) {}
    private record Fee(Map<String,Object> original,BigDecimal amount,BigDecimal tax) {}
    private final JdbcTemplate jdbc;
    private final PurchaseFeeWriter writer;
    private final PurchaseFeePosting posting;
    private final PurchaseFeeQueries queries;
    private final SettlementService settlement;
    private final DocumentNumbers numbers;
    ReceiptFeePosting(JdbcTemplate jdbc,PurchaseFeeWriter writer,PurchaseFeePosting posting,PurchaseFeeQueries queries,SettlementService settlement,DocumentNumbers numbers) {
        this.jdbc=jdbc;this.writer=writer;this.posting=posting;this.queries=queries;this.settlement=settlement;this.numbers=numbers;
    }
    public Result post(CompanyAccess actor,long receipt,LocalDate day,long period,boolean inclusive,List<LedgerService.Line> journal) {
        var policy=queries.policy(actor,true);
        boolean enabled=flag(policy,"purchase_fee_cost_enabled");
        String method=policy.get("purchase_fee_allocation_method").toString();
        var sources=jdbc.queryForList("SELECT l.*,o.order_id,COALESCE(s.weight_kg,0) sku_weight_kg FROM goods_receipt_line l"
                +" JOIN purchase_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id"
                +" JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id WHERE l.tenant_id=? AND l.company_id=? AND l.receipt_id=? ORDER BY l.id FOR UPDATE",actor.tenantId(),actor.companyId(),receipt);
        var byOrder=new TreeMap<Long,List<Map<String,Object>>>();
        sources.forEach(source->byOrder.computeIfAbsent(number(source,"order_id"),unused->new ArrayList<>()).add(source));
        var fees=new TreeMap<Long,List<Fee>>();
        BigDecimal gross=BigDecimal.ZERO,tax=BigDecimal.ZERO;
        for(var order:byOrder.entrySet()) {
            // 已开始资本化的费用继续原确认路径，关闭开关不能遗漏余款或混成费用支出。
            var orderFees=jdbc.queryForList("SELECT f.* FROM purchase_order_fee f WHERE f.tenant_id=? AND f.company_id=? AND f.order_id=? AND f.bearer='buyer' AND f.recognized_amount<f.total_amount"
                    +" AND(? OR EXISTS(SELECT 1 FROM purchase_fee_doc_line l JOIN purchase_fee_doc h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.document_id"
                    +" WHERE l.tenant_id=f.tenant_id AND l.company_id=f.company_id AND l.order_fee_id=f.id AND h.source_kind='receipt' AND h.status='posted')) ORDER BY f.id FOR UPDATE",actor.tenantId(),actor.companyId(),order.getKey(),enabled);
            if(orderFees.isEmpty()) continue;
            var progress=progress(actor,order.getKey(),method);
            for(var fee:orderFees) {
                var recognition=PurchaseFeeMath.recognise(amount(fee,"total_amount"),amount(fee,"tax_amount"),amount(fee,"recognized_amount"),amount(fee,"recognized_tax"),progress[0],progress[1]);
                if(recognition.amount().signum()==0) continue;
                fees.computeIfAbsent(order.getKey(),unused->new ArrayList<>()).add(new Fee(fee,recognition.amount(),recognition.tax()));
                gross=gross.add(recognition.amount());tax=tax.add(recognition.tax());
                if(gross.setScale(2).precision()>24) throw invalid("本次采购费用合计过大，请拆分收货");
            }
        }
        if(gross.signum()==0) return new Result(enabled,gross,tax);
        var receiptHead=jdbc.queryForMap("SELECT * FROM goods_receipt WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE",actor.tenantId(),actor.companyId(),receipt);
        long document=Ids.next(),partner=number(receiptHead,"supplier_id");
        jdbc.update("""
                INSERT INTO purchase_fee_doc(id,tenant_id,company_id,document_no,source_kind,source_receipt_id,partner_id,warehouse_id,business_date,allocation_method,reason,
                  cost_tax_inclusive,status,amount,untaxed_amount,tax_amount,stock_cost,period_id,posted_by,posted_at,owner_user_id,owner_department_id,created_by,updated_by)
                VALUES(?,?,?,?,'receipt',?,?,?,?,?,'采购订单随单费用',?,'posted',?,?,?,?,?,?,UTC_TIMESTAMP(3),?,?,?,?)
                """,document,actor.tenantId(),actor.companyId(),numbers.next(actor,"PF",day),receipt,partner,receiptHead.get("warehouse_id"),day,method,inclusive,
                gross,gross.subtract(tax),tax,inclusive?gross:gross.subtract(tax),period,actor.userId(),receiptHead.get("owner_user_id"),receiptHead.get("owner_department_id"),actor.userId(),actor.userId());
        int lineNo=0;
        for(var order:fees.entrySet()) {
            BigDecimal orderGross=BigDecimal.ZERO,orderTax=BigDecimal.ZERO;
            for(var fee:order.getValue()) {
                long line=Ids.next(),orderFee=number(fee.original(),"id");
                jdbc.update("INSERT INTO purchase_fee_doc_line(id,tenant_id,company_id,document_id,line_no,order_fee_id,fee_type,amount,untaxed_amount,tax_amount,tax_rate) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                        line,actor.tenantId(),actor.companyId(),document,++lineNo,orderFee,fee.original().get("fee_type"),fee.amount(),fee.amount().subtract(fee.tax()),fee.tax(),fee.original().get("tax_rate"));
                jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=COALESCE(recognized_receipt_id,?),recognized_amount=recognized_amount+?,recognized_tax=recognized_tax+?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                        receipt,fee.amount(),fee.tax(),actor.userId(),actor.tenantId(),actor.companyId(),orderFee);
                long open=posting.payable(actor,partner,"purchase_order_fee",receipt,line,fee.amount(),fee.tax(),day,period,PaymentTerms.forOrder(jdbc,actor,order.getKey(),"supplier",day));
                settlement.autoAllocatePurchaseOrder(actor,order.getKey(),open);
                orderGross=orderGross.add(fee.amount());orderTax=orderTax.add(fee.tax());
            }
            allocate(actor,document,byOrder.get(order.getKey()),method,orderGross,orderTax,inclusive);
        }
        var header=jdbc.queryForMap("SELECT * FROM purchase_fee_doc WHERE tenant_id=? AND company_id=? AND id=?",actor.tenantId(),actor.companyId(),document);
        var allocations=jdbc.queryForList("SELECT * FROM purchase_fee_allocation WHERE tenant_id=? AND company_id=? AND document_id=? ORDER BY receipt_line_id",actor.tenantId(),actor.companyId(),document);
        var journals=new SupplementalCostJournals("采购随单费用分摊");
        for(var allocation:allocations) {
            var entries=new ArrayList<LedgerService.Line>();
            var part=new ReceiptCostAllocator.Part(number(receiptHead,"warehouse_id"),amount(allocation,"source_base_qty"),amount(allocation,"cost_amount"),BigDecimal.ZERO);
            posting.applyCost(actor,header,allocation,part,period,day,entries,true);
            PurchaseFeePosting.add(entries,"INPUT_VAT",inclusive?BigDecimal.ZERO:amount(allocation,"tax_amount"),null,null,number(allocation,"id"));
            journals.add(entries,partner,number(allocation,"id"));
        }
        journals.batches().forEach(journal::addAll);
        return new Result(enabled,gross,tax);
    }
    private void allocate(CompanyAccess actor,long document,List<Map<String,Object>> sources,String method,BigDecimal gross,BigDecimal tax,boolean inclusive) {
        var weights=sources.stream().map(source->new PurchaseFeeMath.Weight(number(source,"id"),PurchaseFeeWriter.weight(source,method))).toList();
        var indexed=new HashMap<Long,Map<String,Object>>();sources.forEach(source->indexed.put(number(source,"id"),source));
        for(var part:PurchaseFeeMath.allocate(gross,tax,weights)) {
            var source=indexed.get(part.sourceId());
            writer.insertAllocation(actor,document,source,PurchaseFeeWriter.weight(source,method),part.amount(),part.tax(),inclusive);
        }
    }
    private BigDecimal[] progress(CompanyAccess actor,long order,String method) {
        var lines=jdbc.queryForList("SELECT l.*,COALESCE(s.weight_kg,0) sku_weight_kg FROM purchase_order_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.order_id=? AND l.is_deleted=0 ORDER BY l.id FOR SHARE",actor.tenantId(),actor.companyId(),order);
        BigDecimal received=BigDecimal.ZERO,total=BigDecimal.ZERO;
        for(var line:lines) {
            var base=amount(line,"base_qty");var target=base.subtract(amount(line,"closed_base_qty")).max(BigDecimal.ZERO);
            var fulfilled=amount(line,"received_base_qty").min(target);
            var unitWeight=switch(method) {
                case "quantity" -> BigDecimal.ONE;
                case "weight" -> amount(line,"sku_weight_kg");
                case "amount" -> base.signum()==0?BigDecimal.ZERO:amount(line,"untaxed_amount").divide(base,18,RoundingMode.HALF_UP);
                default -> throw invalid("公司采购费用分摊方式无效");
            };
            total=total.add(target.multiply(unitWeight));received=received.add(fulfilled.multiply(unitWeight));
        }
        if(total.signum()==0) throw invalid("订单分摊权重合计为零，请更换公司分摊方式或补齐商品重量");
        return new BigDecimal[]{received,total};
    }
}
