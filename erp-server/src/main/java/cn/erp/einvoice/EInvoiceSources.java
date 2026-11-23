package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.SalesInvoiceAmounts;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class EInvoiceSources {
    private static final String SELECT="""
            SELECT a.id receivable_id,a.source_type,h.id shipment_id,h.shipment_no,a.business_date,l.sku_id,
                   k.name goods_name,k.tax_category_code,k.spec,u.name unit_name,(l.signed_base_qty-l.returned_base_qty) signed_base_qty,l.unit_rate,
                   a.amount,a.untaxed_amount,a.tax_amount,o.tax_rate
            FROM receivable a
            LEFT JOIN price_adjustment_line adjustment ON adjustment.tenant_id=a.tenant_id AND adjustment.company_id=a.company_id
              AND adjustment.id=a.source_line_id AND a.source_type='price_adjustment_line'
            JOIN shipment_line l ON l.tenant_id=a.tenant_id AND l.company_id=a.company_id
              AND l.id=CASE WHEN a.source_type='shipment_line' THEN a.source_line_id ELSE adjustment.shipment_line_id END
            JOIN shipment h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.shipment_id
            JOIN sales_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
            JOIN sku k ON k.tenant_id=l.tenant_id AND k.id=l.sku_id
            JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
            WHERE a.tenant_id=:tenant AND a.company_id=:company AND a.customer_id=:partner AND h.status IN ('signed','signed_with_diff')
              AND a.kind='charge' AND a.posting_action='post' AND a.source_type IN('shipment_line','price_adjustment_line') AND l.signed_base_qty>l.returned_base_qty
              AND l.receivable_amount+l.price_adjusted_amount-l.credited_amount>0
              AND a.invoiced_amount<a.amount AND NOT EXISTS(SELECT 1 FROM receivable removed
                 WHERE removed.tenant_id=a.tenant_id AND removed.company_id=a.company_id AND removed.reverses_receivable_id=a.id)
            """;
    private final NamedParameterJdbcTemplate jdbc;
    private final SalesInvoiceAmounts amounts;

    EInvoiceSources(NamedParameterJdbcTemplate jdbc,SalesInvoiceAmounts amounts) {this.jdbc=jdbc;this.amounts=amounts;}

    record Source(long receivable,long shipment,long sku,String goods,String taxCode,String spec,String unit,BigDecimal quantity,
                  BigDecimal amount,BigDecimal untaxed,BigDecimal tax,BigDecimal rate) {}

    List<Map<String,Object>> candidates(CompanyAccess actor,long partner) {
        return rows(actor,partner,List.of()).stream().map(row->ApiRows.row(Map.of(
                "id",number(row,"receivable_id"),"shipment_id",number(row,"shipment_id"),"shipment_no",row.get("shipment_no"),
                "business_date",row.get("business_date"),"goods_name",row.get("goods_name"),"amount",row.get("amount"),
                "tax_amount",row.get("tax_amount"),"can_issue",hasTaxCode(row)&&quantity(row).signum()>0))).toList();
    }

    List<Source> selected(CompanyAccess actor,long partner,List<Long> ids) {
        if(ids==null||ids.isEmpty()||ids.size()>50||ids.stream().anyMatch(id->id==null||id<=0)
                ||ids.stream().distinct().count()!=ids.size())throw EInvoiceConfig.invalid("请选择一至五十笔不重复的已签收应收");
        var rows=rows(actor,partner,ids);
        if(rows.size()!=ids.size())throw missing();
        if(rows.stream().anyMatch(row->!hasTaxCode(row)))throw EInvoiceConfig.invalid("开票商品必须先维护税收分类编码");
        if(rows.stream().anyMatch(row->quantity(row).signum()<=0))throw EInvoiceConfig.invalid("签收数量换算后低于可开票精度");
        return rows.stream().map(row->new Source(number(row,"receivable_id"),number(row,"shipment_id"),number(row,"sku_id"),
                row.get("goods_name").toString(),row.get("tax_category_code").toString(),(String)row.get("spec"),row.get("unit_name").toString(),
                quantity(row),
                decimal(row,"amount"),decimal(row,"untaxed_amount"),decimal(row,"tax_amount"),decimal(row,"tax_rate")))
                .sorted(Comparator.comparingLong(Source::receivable)).toList();
    }

    private List<Map<String,Object>> rows(CompanyAccess actor,long partner,Collection<Long> ids) {
        var args=new java.util.HashMap<String,Object>();
        args.put("tenant",actor.tenantId());args.put("company",actor.companyId());args.put("partner",partner);
        String filter=OrgOwnership.filter(actor,"h")+" AND "+OrgOwnership.sourceCondition(actor,"a.source_type","a.source_id");
        if(!actor.allWarehouses()) {
            filter+=" AND h.warehouse_id IN (:warehouses)";
            args.put("warehouses",actor.warehouseIds().isEmpty()?List.of(-1L):actor.warehouseIds());
        }
        if(!ids.isEmpty()) {filter+=" AND a.id IN (:requested)";args.put("requested",ids);}
        var rows=jdbc.queryForList(SELECT+filter+" ORDER BY a.id DESC LIMIT 200",args);
        var capacities=amounts.capacities(actor,rows.stream().map(row->number(row,"receivable_id")).toList());
        return rows.stream().filter(row->capacities.get(number(row,"receivable_id")).available().signum()>0).map(row->{
            var capacity=capacities.get(number(row,"receivable_id"));var available=capacity.available();
            BigDecimal tax=capacity.tax().multiply(capacity.used().add(available)).divide(capacity.amount(),2,RoundingMode.HALF_UP)
                    .subtract(capacity.tax().multiply(capacity.used()).divide(capacity.amount(),2,RoundingMode.HALF_UP));
            if("price_adjustment_line".equals(row.get("source_type"))) {
                row.put("invoice_base_qty",row.get("unit_rate"));row.put("unit_name","笔");
                row.put("goods_name",row.get("goods_name")+"（价格调整）");
            } else row.put("invoice_base_qty",decimal(row,"signed_base_qty").multiply(available).divide(capacity.amount(),18,RoundingMode.HALF_UP));
            row.put("amount",available);row.put("tax_amount",tax);row.put("untaxed_amount",available.subtract(tax));return row;
        }).toList();
    }

    static boolean hasTaxCode(Map<String,Object> row) {
        return row.get("tax_category_code") instanceof String value&&value.matches("[0-9]{6,24}");
    }
    static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    static BigDecimal decimal(Map<String,Object> row,String key) {return (BigDecimal)row.get(key);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","发票或关联单据不存在或无权访问");}

    private static BigDecimal quantity(Map<String,Object> row) {
        return decimal(row,"invoice_base_qty").divide(decimal(row,"unit_rate"),6,RoundingMode.HALF_UP);
    }
}
