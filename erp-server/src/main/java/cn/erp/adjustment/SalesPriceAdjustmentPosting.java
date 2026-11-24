package cn.erp.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.ShipmentReturnPricing;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.PaymentTerms;
import cn.erp.shared.SalesInvoiceAmounts;
import static cn.erp.adjustment.SalesPriceAdjustmentQueries.*;

@Component
class SalesPriceAdjustmentPosting {
    private final JdbcTemplate jdbc;
    private final SalesPriceAdjustmentWriter writer;
    private final SalesInvoiceAmounts invoices;
    private final LedgerService ledger;
    private final SettlementService settlement;
    private final ObjectMapper json;
    SalesPriceAdjustmentPosting(JdbcTemplate jdbc,SalesPriceAdjustmentWriter writer,SalesInvoiceAmounts invoices,LedgerService ledger,SettlementService settlement,ObjectMapper json) {
        this.jdbc=jdbc;this.writer=writer;this.invoices=invoices;this.ledger=ledger;this.settlement=settlement;this.json=json;
    }
    List<Map<String,Object>> preview(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        writer.revalidate(actor,header,lines);
        var incomplete=invoices.incompleteRedSources(actor,lines.stream().filter(line->amount(line,"amount").signum()<0).map(line->number(line,"shipment_line_id")).toList());
        return lines.stream().map(line->Map.<String,Object>of("lineId",line.get("id").toString(),"shipmentLineId",line.get("shipment_line_id").toString(),
                "amount",line.get("amount").toString(),"taxAmount",line.get("tax_amount").toString(),"remainingQuantity",line.get("source_base_qty").toString(),
                "needsRed",incomplete.contains(number(line,"shipment_line_id")))).toList();
    }
    void post(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        requireFinancial(actor,TYPE+":post");LocalDate day=date(header,"business_date");long period=period(actor,day);ledger.lockOpenPeriod(actor,period,day);
        var sources=writer.revalidate(actor,header,lines);
        invoices.requireRed(actor,lines.stream().filter(line->amount(line,"amount").signum()<0).map(line->number(line,"shipment_line_id")).toList());
        var entries=new ArrayList<LedgerService.Line>();
        for(var line:lines) {
            receivable(actor,header,line,sources.get(number(line,"id")),period,day);
            var basis=new ShipmentReturnPricing.Basis(amount(line,"source_returned_qty"),amount(line,"source_base_qty"),
                    amount(line,"old_amount").add(amount(line,"amount")),amount(line,"old_tax").add(amount(line,"tax_amount")),day);
            jdbc.update("UPDATE shipment_line SET price_adjusted_amount=price_adjusted_amount+?,price_adjusted_tax=price_adjusted_tax+?,pricing_return_basis=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                    line.get("amount"),line.get("tax_amount"),json.writeValueAsString(basis),actor.userId(),actor.tenantId(),actor.companyId(),line.get("shipment_line_id"));
            add(entries,"AR","D",amount(line,"amount"),number(header,"partner_id"),number(line,"id"));
            add(entries,"SALES_REVENUE","C",amount(line,"untaxed_amount"),null,number(line,"id"));
            add(entries,"OUTPUT_VAT","C",amount(line,"tax_amount"),null,number(line,"id"));
        }
        ledger.post(actor,new LedgerService.Posting(TYPE,number(header,"id"),0,period,day,entries));
        jdbc.update("UPDATE price_adjustment SET status='posted',period_id=?,posted_by=?,posted_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                period,actor.userId(),actor.userId(),actor.tenantId(),actor.companyId(),header.get("id"));
    }
    private void receivable(CompanyAccess actor,Map<String,Object> header,Map<String,Object> line,Map<String,Object> source,long period,LocalDate day) {
        boolean charge=amount(line,"amount").signum()>0;
        var due=jdbc.queryForList("SELECT due_date FROM receivable WHERE tenant_id=? AND company_id=? AND source_type='shipment_line' AND source_line_id=? AND posting_action='post'",
                actor.tenantId(),actor.companyId(),line.get("shipment_line_id"));
        Object dueDate=due.isEmpty()?PaymentTerms.forOrder(jdbc,actor,number(source,"order_id"),"customer",date(source,"sign_date")):due.getFirst().get("due_date");
        long id=Ids.next(),partner=number(header,"partner_id");
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,amount,untaxed_amount,tax_amount,business_date,period_id,created_by,due_date)
                VALUES(?,?,?,?,?,'price_adjustment_line',?,?,?,?,?,?,?,?,?)
                """,id,actor.tenantId(),actor.companyId(),partner,charge?"charge":"credit",header.get("id"),line.get("id"),amount(line,"amount").abs(),
                amount(line,"untaxed_amount").abs(),amount(line,"tax_amount").abs(),day,period,actor.userId(),dueDate);
        settlement.open(actor,new SettlementService.OpenItem("receivable",id,partner,charge?"D":"C",amount(line,"amount").abs(),day));
    }
    private long period(CompanyAccess actor,LocalDate day) {
        var rows=jdbc.queryForList("SELECT id FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,actor.tenantId(),actor.companyId(),day,day);
        if(rows.size()!=1)throw new ApiException(422,"PERIOD_NOT_FOUND","调价日期没有对应会计期间");return rows.getFirst();
    }
    private static void add(List<LedgerService.Line> rows,String account,String direction,BigDecimal amount,Long partner,long line) {
        if(amount.signum()!=0)rows.add(new LedgerService.Line(account,amount.signum()>0?direction:direction.equals("D")?"C":"D",amount.abs(),partner,null,null,null,line,"销售价格调整"));
    }
}
