package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.InboundInvoiceScope;
import cn.erp.shared.OrgOwnership;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** 发票只覆盖采购事实的净额度，资金是否核销不参与到票计算。 */
@Component
class InboundInvoiceSources {
    private static final int MAX_FACTS = 50_000;
    private static final BigDecimal ZERO = new BigDecimal("0.00");
    private static final String FAMILY = "COALESCE(CONCAT('R:',(" + InboundInvoiceScope.family("p") + ")),CONCAT('P:',p.id))";
    private static final String JOINS = """
            FROM payable p JOIN business_partner vendor ON vendor.tenant_id=p.tenant_id AND vendor.id=p.supplier_id
            LEFT JOIN goods_receipt receipt ON receipt.tenant_id=p.tenant_id AND receipt.company_id=p.company_id AND receipt.id=p.source_id AND p.source_type IN('goods_receipt_line','purchase_order_fee')
            LEFT JOIN goods_receipt_line goods ON goods.tenant_id=p.tenant_id AND goods.company_id=p.company_id AND goods.id=(%s)
            LEFT JOIN goods_receipt original_receipt ON original_receipt.tenant_id=goods.tenant_id AND original_receipt.company_id=goods.company_id AND original_receipt.id=goods.receipt_id
            LEFT JOIN price_adjustment price ON price.tenant_id=p.tenant_id AND price.company_id=p.company_id AND price.id=p.source_id AND p.source_type='price_adjustment_line' AND price.kind='purchase'
            LEFT JOIN purchase_return ret ON ret.tenant_id=p.tenant_id AND ret.company_id=p.company_id AND ret.id=p.source_id AND p.source_type='purchase_return_line'
            LEFT JOIN purchase_fee_doc fee ON fee.tenant_id=p.tenant_id AND fee.company_id=p.company_id AND fee.id=p.source_id AND p.source_type='purchase_fee_doc_line'
            WHERE p.tenant_id=:t AND p.company_id=:c AND p.posting_action='post' AND p.business_date<=:day
              AND p.source_type IN('goods_receipt_line','purchase_order_fee','price_adjustment_line','purchase_return_line','purchase_fee_doc_line')
              AND COALESCE(receipt.status,price.status,ret.status,fee.status) IN('posted','reversed')
              AND (COALESCE(receipt.status,price.status,ret.status,fee.status)<>'reversed' OR EXISTS(
                SELECT 1 FROM payable future_reverse WHERE future_reverse.tenant_id=p.tenant_id AND future_reverse.company_id=p.company_id
                  AND future_reverse.reverses_payable_id=p.id AND future_reverse.business_date>:day))
              AND NOT EXISTS(SELECT 1 FROM payable reverse_row WHERE reverse_row.tenant_id=p.tenant_id AND reverse_row.company_id=p.company_id
                AND reverse_row.reverses_payable_id=p.id AND reverse_row.business_date<=:day)
            """.formatted(InboundInvoiceScope.family("p"));
    private final NamedParameterJdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired
    InboundInvoiceSources(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    InboundInvoiceSources(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    record Position(Map<String, Object> source, BigDecimal basisUntaxed, BigDecimal basisTax,
                    BigDecimal invoicedUntaxed, BigDecimal invoicedTax) {
        long id() { return number(source, "id"); }
        long supplierId() { return number(source, "supplier_id"); }
        InboundInvoiceAmounts.Available available() {
            return new InboundInvoiceAmounts.Available(id(), supplierId(), basisUntaxed.subtract(invoicedUntaxed).max(ZERO), basisTax.subtract(invoicedTax).max(ZERO));
        }
        Map<String, Object> view() {
            var result = ApiRows.row(source);
            result.remove("family"); result.remove("sourceVisible"); result.remove("kind");
            result.put("basisUntaxedAmount", basisUntaxed.toPlainString()); result.put("basisTaxAmount", basisTax.toPlainString());
            result.put("invoicedUntaxedAmount", invoicedUntaxed.toPlainString()); result.put("invoicedTaxAmount", invoicedTax.toPlainString());
            result.put("availableUntaxedAmount", available().untaxedAmount().toPlainString()); result.put("availableTaxAmount", available().taxAmount().toPlainString());
            result.put("invoicingStatus", invoicedUntaxed.signum() == 0 && invoicedTax.signum() == 0 ? "uninvoiced"
                    : available().untaxedAmount().signum() == 0 && available().taxAmount().signum() == 0 ? "invoiced" : "partial");
            return result;
        }
    }

    Map<Long, Position> positions(CompanyAccess actor, Set<Long> suppliers, LocalDate day, long excludingInvoice, Set<String> families) {
        var params = params(actor, day);
        String filter = filters(params, suppliers, families);
        String visible = OrgOwnership.sourceCondition(actor, "p.source_type", "p.source_id");
        String select = "SELECT p.id,p.supplier_id,p.kind,p.source_type,p.source_id,p.source_line_id,p.amount,p.untaxed_amount,p.tax_amount,p.business_date,p.version,"
                + "vendor.name supplier_name,COALESCE(receipt.receipt_no,price.adjust_no,ret.return_no,fee.document_no) receipt_no,"
                + "COALESCE(original_receipt.id,receipt.id) original_receipt_id,COALESCE(original_receipt.receipt_no,receipt.receipt_no) original_receipt_no,"
                + "COALESCE(receipt.warehouse_id,price.warehouse_id,ret.warehouse_id,fee.warehouse_id) warehouse_id,goods.sku_id,"
                + FAMILY + " family,COALESCE((" + visible + "),FALSE) source_visible " + JOINS + filter + " ORDER BY p.id LIMIT " + (MAX_FACTS + 1);
        var facts = jdbc.queryForList(select, params);
        if (facts.size() > MAX_FACTS) throw invalid("采购往来超过50000笔，请缩小供应商或日期范围");
        var used = used(actor, day, excludingInvoice, facts.stream().filter(row -> "charge".equals(row.get("kind"))).map(row -> number(row, "id")).toList());
        var result = new LinkedHashMap<Long, Position>();
        for (var group : facts.stream().collect(Collectors.groupingBy(row -> row.get("family").toString())).values()) {
            if (group.stream().anyMatch(row -> !truth(row.get("source_visible")))) continue;
            var charges = group.stream().filter(row -> "charge".equals(row.get("kind"))).toList();
            if (charges.isEmpty()) continue;
            var credits = group.stream().filter(row -> "credit".equals(row.get("kind"))).toList();
            var quota = InboundInvoiceBasisMath.netAvailable(number(charges.getFirst(), "supplier_id"), charges.stream()
                    .map(row -> new InboundInvoiceAmounts.Available(number(row, "id"), number(row, "supplier_id"), money(row, "untaxed_amount"), money(row, "tax_amount"))).toList(),
                    sum(credits, "untaxed_amount"), sum(credits, "tax_amount"));
            var byId = charges.stream().collect(Collectors.toMap(row -> number(row, "id"), row -> row));
            for (var available : quota) {
                var cover = used.getOrDefault(available.payableId(), new BigDecimal[]{ZERO, ZERO});
                result.put(available.payableId(), new Position(byId.get(available.payableId()), available.untaxedAmount(), available.taxAmount(), cover[0], cover[1]));
            }
        }
        return result;
    }

    Map<Long, Position> selected(CompanyAccess actor, Set<Long> ids, LocalDate day, long excludingInvoice) {
        if (ids.isEmpty()) return Map.of();
        var params = params(actor, day); params.put("ids", ids);
        var roots = jdbc.queryForList("SELECT DISTINCT p.supplier_id," + FAMILY + " family " + JOINS + " AND p.id IN(:ids)", params);
        if (roots.isEmpty()) return Map.of();
        Set<Long> suppliers = roots.stream().map(row -> number(row, "supplier_id")).collect(Collectors.toSet());
        Set<String> families = roots.stream().map(row -> row.get("family").toString()).collect(Collectors.toSet());
        var all = positions(actor, suppliers, day, excludingInvoice, families);
        all.keySet().removeIf(id -> !ids.contains(id));
        return all;
    }

    Map<Long, Position> period(CompanyAccess actor, LocalDate from, LocalDate day, Long supplier) {
        var params=params(actor,day);params.put("from",from);
        String seller="";
        if(supplier!=null){params.put("supplier",supplier);seller=" AND p.supplier_id=:supplier";}
        // 先找范围内的来源组，再完整读取其退货和调价，日期筛选不会截掉组内的抵减事实。
        var roots=jdbc.queryForList("SELECT DISTINCT "+FAMILY+" family "+JOINS+" AND p.kind='charge' AND p.business_date>=:from"+seller+" AND "+InboundInvoiceScope.payableCondition(actor,"p")+" LIMIT "+(MAX_FACTS+1),params,String.class);
        if(roots.size()>MAX_FACTS)throw invalid("采购来源超过50000组，请缩小来源日期或供应商范围");
        if(roots.isEmpty())return Map.of();
        return positions(actor,supplier==null?Set.of():Set.of(supplier),day,0,Set.copyOf(roots));
    }

    Map<String, Object> candidates(CompanyAccess actor, Map<String, Object> invoice, Long cursor, int limit) {
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0) throw invalid("候选应付分页条件无效");
        var day = LocalDate.now(EInvoiceService.ZONE);
        var params = params(actor, day); params.put("before", cursor == null ? Long.MAX_VALUE : cursor); params.put("limit", limit + 1);
        String seller;
        if (invoice.get("supplier_id") != null) { params.put("supplier", number(invoice, "supplier_id")); seller = "p.supplier_id=:supplier"; }
        else { params.put("tax", invoice.get("seller_tax_no")); seller = "vendor.tax_no=:tax"; }
        var rows = jdbc.queryForList("SELECT p.id " + JOINS + " AND p.kind='charge' AND p.id<:before AND " + seller
                + " AND " + InboundInvoiceScope.payableCondition(actor, "p") + " ORDER BY p.id DESC LIMIT :limit", params);
        var ids = rows.stream().limit(limit).map(row -> number(row, "id")).collect(Collectors.toSet());
        var positions = selected(actor, ids, day, number(invoice, "id"));
        var items = positions.values().stream().filter(row -> row.available().untaxedAmount().add(row.available().taxAmount()).signum() > 0)
                .sorted(Comparator.comparingLong(Position::id).reversed()).map(Position::view).toList();
        var result = new LinkedHashMap<String, Object>(); result.put("items", items);
        result.put("nextCursor", rows.size() > limit ? rows.get(limit - 1).get("id").toString() : null);
        return result;
    }

    private Map<Long, BigDecimal[]> used(CompanyAccess actor, LocalDate day, long excludingInvoice, List<Long> ids) {
        if (ids.isEmpty()) return Map.of();
        var params = params(actor, day); params.put("ids", ids); params.put("excluded", excludingInvoice);
        var result = new HashMap<Long, BigDecimal[]>();
        for (var row : jdbc.queryForList("""
                SELECT payable_id,SUM(payable_untaxed_amount) untaxed,SUM(payable_tax_amount) tax
                FROM einv_inbound_match WHERE tenant_id=:t AND company_id=:c AND payable_id IN(:ids) AND invoice_id<>:excluded
                  AND matched_date<=:day AND (released_date IS NULL OR released_date>:day) GROUP BY payable_id
                """, params)) result.put(number(row, "payable_id"), new BigDecimal[]{money(row, "untaxed"), money(row, "tax")});
        return result;
    }

    private static String filters(Map<String, Object> params, Set<Long> suppliers, Set<String> families) {
        String result = "";
        if (suppliers != null && !suppliers.isEmpty()) { params.put("suppliers", suppliers); result += " AND p.supplier_id IN(:suppliers)"; }
        if (families != null && !families.isEmpty()) { params.put("families", families); result += " AND " + FAMILY + " IN(:families)"; }
        return result;
    }
    private static Map<String, Object> params(CompanyAccess actor, LocalDate day) {
        var result = new HashMap<String, Object>(); result.put("t", actor.tenantId()); result.put("c", actor.companyId()); result.put("day", day); return result;
    }
    private static BigDecimal sum(List<Map<String, Object>> rows, String field) { return rows.stream().map(row -> money(row, field)).reduce(ZERO, BigDecimal::add); }
    static long number(Map<String, Object> row, String field) { return Long.parseLong(row.get(field).toString()); }
    static BigDecimal money(Map<String, Object> row, String field) { return new BigDecimal(row.get(field).toString()); }
    private static boolean truth(Object value) { return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0; }
    private static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
}
