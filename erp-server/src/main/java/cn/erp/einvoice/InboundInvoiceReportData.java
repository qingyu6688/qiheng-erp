package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 使用报表事务提供的连接读取额度，避免配置备库后混入主库的核对结果。 */
public final class InboundInvoiceReportData {
    private final NamedParameterJdbcTemplate jdbc;
    private final InboundInvoiceSources sources;

    public InboundInvoiceReportData(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
        this.sources=new InboundInvoiceSources(jdbc);
    }

    public List<Map<String,Object>> sources(CompanyAccess actor,LocalDate from,LocalDate cutoff,Long supplier) {
        var positions=sources.period(actor,from,cutoff,supplier);
        var legacy=legacy(actor,positions.keySet(),cutoff);
        var rows=new ArrayList<Map<String,Object>>();
        for(var position:positions.values()) {
            var raw=position.source();var row=new LinkedHashMap<String,Object>();
            for(String field:List.of("id","source_type","source_id","source_line_id","warehouse_id","sku_id","business_date","original_receipt_id","original_receipt_no"))
                row.put(field,raw.get(field));
            row.put("payable_id",raw.get("id"));row.put("partner_id",raw.get("supplier_id"));row.put("partner_name",raw.get("supplier_name"));row.put("source_no",raw.get("receipt_no"));
            row.put("basis_untaxed_amount",position.basisUntaxed());row.put("basis_tax_amount",position.basisTax());
            row.put("invoiced_untaxed_amount",position.invoicedUntaxed());row.put("invoiced_tax_amount",position.invoicedTax());
            var available=position.available();
            row.put("uninvoiced_untaxed_amount",available.untaxedAmount());row.put("uninvoiced_tax_amount",available.taxAmount());
            row.put("uninvoiced_amount",available.untaxedAmount().add(available.taxAmount()));
            boolean empty=position.basisUntaxed().add(position.basisTax()).signum()==0;
            row.put("invoicing_status",empty?"not_required":position.view().get("invoicingStatus"));
            boolean migrated=legacy.contains(position.id());row.put("legacy_migrated",migrated);
            row.put("invoice_match_notice",migrated?"含旧版迁移关系，迁移前核对日期只能按旧记录更新时间还原":empty?"已全部退货或折让，无待到票额度":null);
            rows.add(row);
        }
        return rows;
    }

    public void annotateStatement(CompanyAccess actor,LocalDate cutoff,List<Map<String,Object>> rows) {
        var eligible=rows.stream().filter(InboundInvoiceReportData::purchasing).toList();
        if(eligible.isEmpty())return;
        var supplierIds=eligible.stream().map(row->Long.parseLong(row.get("partner_id").toString())).collect(java.util.stream.Collectors.toSet());
        var lineIds=eligible.stream().map(row->row.get("source_line_id")).collect(java.util.stream.Collectors.toSet());
        var documentIds=eligible.stream().map(row->row.get("source_id")).collect(java.util.stream.Collectors.toSet());
        var ids=jdbc.queryForList("SELECT id FROM payable WHERE tenant_id=:t AND company_id=:c AND supplier_id IN(:suppliers) AND source_line_id IN(:lines) AND source_id IN(:documents) AND posting_action='post' AND kind='charge' AND source_type IN('goods_receipt_line','purchase_order_fee','price_adjustment_line','purchase_fee_doc_line')",
                Map.of("t",actor.tenantId(),"c",actor.companyId(),"suppliers",supplierIds,"lines",lineIds,"documents",documentIds),Long.class);
        var positions=sources.selected(actor,Set.copyOf(ids),cutoff,0);
        var statuses=new HashMap<String,String>();
        for(var position:positions.values()) {
            var raw=position.source();String type=documentType(raw.get("source_type").toString());
            String status=position.basisUntaxed().add(position.basisTax()).signum()==0?"not_required":position.view().get("invoicingStatus").toString();
            statuses.put(key(type,raw.get("source_id"),raw.get("source_line_id")),status);
        }
        for(var row:eligible) {
            String key=key(row.get("source_type"),row.get("source_id"),row.get("source_line_id"));
            row.put("invoicing_status",statuses.getOrDefault(key,"source_unavailable"));
        }
    }

    private Set<Long> legacy(CompanyAccess actor,Set<Long> ids,LocalDate cutoff) {
        if(ids.isEmpty())return Set.of();
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT payable_id FROM einv_inbound_match WHERE tenant_id=:t AND company_id=:c AND payable_id IN(:ids) AND legacy_migrated=1 AND matched_date<=:day",
                Map.of("t",actor.tenantId(),"c",actor.companyId(),"ids",ids,"day",cutoff),Long.class));
    }
    private static boolean purchasing(Map<String,Object> row) {
        return "AP".equals(row.get("account_code"))&&row.get("partner_id")!=null&&row.get("source_line_id")!=null
                &&Set.of("goods_receipt","price_adjustment","purchase_fee_doc").contains(row.get("source_type"));
    }
    private static String documentType(String type) {
        return switch(type) {case "goods_receipt_line","purchase_order_fee"->"goods_receipt";case "price_adjustment_line"->"price_adjustment";case "purchase_fee_doc_line"->"purchase_fee_doc";default->type;};
    }
    private static String key(Object type,Object id,Object line) {return type+":"+id+":"+line;}
}
