package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.InboundInvoiceScope;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class InboundInvoiceQueries {
    private static final String TOTALS = """
            SELECT invoice_id,COUNT(*) part_count,MAX(payable_id) single_payable_id,
              SUM(invoice_untaxed_amount) allocated_untaxed_amount,SUM(invoice_tax_amount) allocated_tax_amount,
              SUM(ABS(untaxed_difference)+ABS(tax_difference)) absolute_difference,
              SUM(untaxed_difference) untaxed_difference,SUM(tax_difference) tax_difference
            FROM einv_inbound_match WHERE tenant_id=:t AND company_id=:c AND released_at IS NULL GROUP BY invoice_id
            """;
    private static final String FULL = "COALESCE(a.allocated_untaxed_amount,0)=i.amount-i.tax_amount AND COALESCE(a.allocated_tax_amount,0)=i.tax_amount";
    private static final String STATUS = "CASE WHEN a.part_count IS NULL THEN 'pending' WHEN a.absolute_difference<>0 THEN 'difference' WHEN " + FULL + " THEN 'matched' ELSE 'partial' END";
    private final NamedParameterJdbcTemplate jdbc;
    InboundInvoiceQueries(JdbcTemplate jdbc) { this.jdbc = new NamedParameterJdbcTemplate(jdbc); }

    Map<String,Object> suppliers(CompanyAccess actor,String keyword,Long cursor,int limit) {
        if(limit<1||limit>100||cursor!=null&&cursor<=0||keyword!=null&&keyword.length()>100)throw invalid("供应商查询条件无效");
        var params=params(actor);params.put("before",cursor==null?Long.MAX_VALUE:cursor);params.put("limit",limit+1);
        String filter="";
        if(keyword!=null&&!keyword.isBlank()) {
            params.put("keyword","%"+keyword.trim().replace("!","!!").replace("%","!%").replace("_","!_")+"%");
            filter=" AND (p.code LIKE :keyword ESCAPE '!' OR p.name LIKE :keyword ESCAPE '!' OR p.tax_no LIKE :keyword ESCAPE '!')";
        }
        var rows=jdbc.queryForList("SELECT p.id,p.code,p.name,p.tax_no FROM business_partner p WHERE p.tenant_id=:t AND p.id<:before AND p.status='active'"
                +" AND EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='supplier' AND r.status='active')"+filter+" ORDER BY p.id DESC LIMIT :limit",params);
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;
    }

    List<Map<String, Object>> list(CompanyAccess actor, Long cursor, int limit, String status, String source, String keyword) {
        InboundInvoiceScope.requireFull(actor);
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0) throw invalid("进项发票分页条件无效");
        if (status != null && !Set.of("pending", "partial", "matched", "difference").contains(status)) throw invalid("核对状态无效");
        if (source != null && !Set.of("manual", "einv").contains(source)) throw invalid("进项来源无效");
        if (keyword != null && keyword.length() > 100) throw invalid("搜索内容最多100字");
        var params = params(actor); params.put("before", cursor == null ? Long.MAX_VALUE : cursor); params.put("limit", limit);
        String filter = "i.id<:before AND " + InboundInvoiceScope.condition(actor, "i");
        if (status != null) { params.put("status", status); filter += " AND i.status='normal' AND (" + STATUS + ")=:status"; }
        if (source != null) { params.put("source", source); filter += " AND i.source=:source"; }
        if (keyword != null && !keyword.isBlank()) { params.put("keyword", "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"); filter += " AND (i.invoice_no LIKE :keyword ESCAPE '!' OR i.seller_name LIKE :keyword ESCAPE '!')"; }
        return jdbc.queryForList(select() + " WHERE i.tenant_id=:t AND i.company_id=:c AND " + filter + " ORDER BY i.id DESC LIMIT :limit", params).stream().map(InboundInvoiceQueries::view).toList();
    }

    Map<String, Object> head(CompanyAccess actor, long id, boolean lock) {
        InboundInvoiceScope.requireFull(actor);
        var params = params(actor); params.put("id", id);
        return jdbc.queryForList("SELECT i.* FROM einv_inbound i WHERE i.tenant_id=:t AND i.company_id=:c AND i.id=:id AND "
                + InboundInvoiceScope.condition(actor, "i") + (lock ? " FOR UPDATE" : ""), params).stream().findFirst().orElseThrow(InboundInvoiceQueries::missing);
    }

    Map<String, Object> detail(CompanyAccess actor, long id) {
        head(actor, id, false);
        var params = params(actor); params.put("id", id);
        var row = jdbc.queryForMap(select() + " WHERE i.tenant_id=:t AND i.company_id=:c AND i.id=:id", params);
        var result = view(row);
        result.put("allocations", allocations(actor, id));
        result.put("history", history(actor, id, null, 50));
        result.put("tolerance", tolerance(actor, false).toPlainString());
        return result;
    }

    List<Map<String, Object>> allocations(CompanyAccess actor, long id) {
        var params = params(actor); params.put("id", id);
        return jdbc.queryForList(matchSelect() + " WHERE m.tenant_id=:t AND m.company_id=:c AND m.invoice_id=:id AND m.released_at IS NULL ORDER BY m.id", params)
                .stream().map(ApiRows::row).toList();
    }

    Map<String, Object> history(CompanyAccess actor, long id, Long cursor, int limit) {
        head(actor, id, false);
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0) throw invalid("核对历史分页条件无效");
        var params = params(actor); params.put("id", id); params.put("before", cursor == null ? Long.MAX_VALUE : cursor); params.put("limit", limit + 1);
        var rows = jdbc.queryForList(matchSelect() + " WHERE m.tenant_id=:t AND m.company_id=:c AND m.invoice_id=:id AND m.released_at IS NOT NULL AND m.id<:before ORDER BY m.id DESC LIMIT :limit", params);
        var result = new LinkedHashMap<String, Object>();
        result.put("items", rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor", rows.size() > limit ? rows.get(limit - 1).get("id").toString() : null);
        return result;
    }

    BigDecimal tolerance(CompanyAccess actor, boolean lock) {
        var params = params(actor);
        if (lock) jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=:t AND id=:c FOR SHARE", params, Long.class);
        return jdbc.queryForList("SELECT invoice_match_tolerance FROM company_policy WHERE tenant_id=:t AND company_id=:c" + (lock ? " FOR SHARE" : ""), params, BigDecimal.class)
                .stream().findFirst().orElse(new BigDecimal("1.00"));
    }

    private static String select() {
        return "SELECT i.id,i.invoice_no,i.seller_name,i.seller_tax_no,i.invoice_date,i.amount,i.tax_amount,i.amount-i.tax_amount untaxed_amount,i.status,i.checked,i.version,"
                + "i.source,i.supplier_id,i.received_date,i.remark,i.match_notice,i.created_at,i.updated_at,COALESCE(a.allocated_untaxed_amount,0) allocated_untaxed_amount,"
                + "COALESCE(a.allocated_tax_amount,0) allocated_tax_amount,i.amount-i.tax_amount-COALESCE(a.allocated_untaxed_amount,0) remaining_untaxed_amount,"
                + "i.tax_amount-COALESCE(a.allocated_tax_amount,0) remaining_tax_amount,COALESCE(a.absolute_difference,0) absolute_difference,COALESCE(a.untaxed_difference,0) untaxed_difference,"
                + "COALESCE(a.tax_difference,0) tax_difference,(" + FULL + ") fully_allocated,(" + STATUS + ") match_status,"
                + "IF(a.part_count=1 AND (" + FULL + "),a.single_payable_id,NULL) payable_id FROM einv_inbound i LEFT JOIN (" + TOTALS + ") a ON a.invoice_id=i.id";
    }

    private static String matchSelect() {
        return "SELECT m.*,p.source_type,p.source_id,p.source_line_id,p.business_date,p.supplier_id,p.amount source_amount,p.untaxed_amount source_untaxed_amount,p.tax_amount source_tax_amount,"
                +"COALESCE(receipt.receipt_no,price.adjust_no,fee.document_no) source_no"
                + " FROM einv_inbound_match m JOIN payable p ON p.tenant_id=m.tenant_id AND p.company_id=m.company_id AND p.id=m.payable_id"
                +" LEFT JOIN goods_receipt receipt ON receipt.tenant_id=p.tenant_id AND receipt.company_id=p.company_id AND receipt.id=p.source_id AND p.source_type IN('goods_receipt_line','purchase_order_fee')"
                +" LEFT JOIN price_adjustment price ON price.tenant_id=p.tenant_id AND price.company_id=p.company_id AND price.id=p.source_id AND p.source_type='price_adjustment_line'"
                +" LEFT JOIN purchase_fee_doc fee ON fee.tenant_id=p.tenant_id AND fee.company_id=p.company_id AND fee.id=p.source_id AND p.source_type='purchase_fee_doc_line'";
    }
    private static Map<String, Object> view(Map<String, Object> row) {
        var result = ApiRows.row(row);
        Object complete = row.get("fully_allocated");
        result.put("fullyAllocated", Boolean.TRUE.equals(complete) || complete instanceof Number number && number.intValue() != 0);
        return result;
    }
    static void version(Map<String, Object> row, Long version) {
        if (version == null || version.longValue() != InboundInvoiceSources.number(row, "version")) throw new ApiException(409, "VERSION_CONFLICT", "进项发票已变化，请刷新后重试");
    }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "进项发票不存在或无权查看"); }
    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
    private static Map<String, Object> params(CompanyAccess actor) {
        var result = new HashMap<String, Object>(); result.put("t", actor.tenantId()); result.put("c", actor.companyId()); return result;
    }
}
