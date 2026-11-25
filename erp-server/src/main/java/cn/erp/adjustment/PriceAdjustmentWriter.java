package cn.erp.adjustment;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import cn.erp.identity.CompanyAccess;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.Ids;
import static cn.erp.adjustment.PriceAdjustmentQueries.*;

@Component
class PriceAdjustmentWriter {
    private static final int MAX_LINES = 100;
    private final JdbcTemplate jdbc;
    private final PriceAdjustmentQueries queries;
    private final ReferenceAvailability references;

    PriceAdjustmentWriter(JdbcTemplate jdbc, PriceAdjustmentQueries queries, ReferenceAvailability references) {
        this.jdbc = jdbc; this.queries = queries; this.references = references;
    }

    void validate(CompanyAccess actor, PriceAdjustmentModels.Input input) {
        if (input == null) throw invalid("请填写调价内容");
        day(input.businessDate());
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 500) throw invalid("请填写调价原因，最多500字");
        if (input.lines() == null || input.lines().isEmpty() || input.lines().size() > MAX_LINES || input.lines().stream().anyMatch(java.util.Objects::isNull)) throw invalid("调价明细须为1到100行");
        if (!actor.allowsWarehouse(input.warehouseId())) throw missing();
        references.requirePartner(actor, input.partnerId(), "supplier");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                Integer.class, actor.tenantId(), actor.companyId(), input.warehouseId()) != 1) throw invalid("请选择当前公司的启用仓库");
    }

    void save(CompanyAccess actor, long header, PriceAdjustmentModels.Input input) {
        var sources = new LinkedHashMap<Long, Map<String, Object>>();
        var unique = new HashSet<Long>();
        for (var line : input.lines()) if (!unique.add(line.receiptLineId())) throw invalid("同一收货行不能重复调价");
        for (long id : unique.stream().sorted().toList()) {
            var source = queries.source(actor, id, true);
            queries.eligible(actor, source, input.partnerId(), input.warehouseId(), input.businessDate());
            sources.put(id, source);
        }
        jdbc.update("DELETE FROM price_adjustment_line WHERE tenant_id=? AND company_id=? AND adjustment_id=?", actor.tenantId(), actor.companyId(), header);
        BigDecimal gross = BigDecimal.ZERO, tax = BigDecimal.ZERO, magnitude = BigDecimal.ZERO;
        int lineNo = 0;
        for (var line : input.lines()) {
            var source = sources.get(line.receiptLineId());
            var result = calculate(source, line);
            magnitude = magnitude.add(result.amount().abs());
            if(magnitude.setScale(2).precision()>24) throw invalid("调价金额合计超过允许范围，请拆分单据");
            insert(actor, header, ++lineNo, line, source, result);
            gross = gross.add(result.amount()); tax = tax.add(result.tax());
        }
        jdbc.update("UPDATE price_adjustment SET amount=?,tax_amount=?,untaxed_amount=? WHERE tenant_id=? AND company_id=? AND id=?",
                gross, tax, gross.subtract(tax), actor.tenantId(), actor.companyId(), header);
    }

    private PriceAdjustmentMath.Result calculate(Map<String, Object> source, PriceAdjustmentModels.LineInput input) {
        BigDecimal remaining = amount(source, "base_qty").subtract(amount(source, "returned_base_qty"));
        BigDecimal gross = amount(source, "payable_amount").add(amount(source, "price_adjusted_amount")).subtract(amount(source, "debited_amount"));
        BigDecimal tax = amount(source, "tax_amount").add(amount(source, "price_adjusted_tax")).subtract(amount(source, "debited_tax"));
        return PriceAdjustmentMath.calculate(remaining, amount(source, "unit_rate"), gross, tax,
                amount(source, "tax_rate"), inclusive(source), input.newUnitPrice(), input.adjustmentAmount());
    }

    static boolean inclusive(Map<String, Object> source) {
        Object value = source.get("cost_tax_inclusive");
        if (value == null) return amount(source, "cost_amount").compareTo(amount(source, "payable_amount")) == 0;
        return value instanceof Boolean flag ? flag : ((Number) value).intValue() != 0;
    }

    private void insert(CompanyAccess actor, long header, int lineNo, PriceAdjustmentModels.LineInput input,
                         Map<String, Object> source, PriceAdjustmentMath.Result result) {
        BigDecimal remaining = amount(source, "base_qty").subtract(amount(source, "returned_base_qty"));
        BigDecimal gross = amount(source, "payable_amount").add(amount(source, "price_adjusted_amount")).subtract(amount(source, "debited_amount"));
        BigDecimal tax = amount(source, "tax_amount").add(amount(source, "price_adjusted_tax")).subtract(amount(source, "debited_tax"));
        jdbc.update("""
                INSERT INTO price_adjustment_line(id,tenant_id,company_id,adjustment_id,line_no,receipt_line_id,source_version,
                  sku_id,unit_id,source_qty,source_base_qty,source_returned_qty,input_unit_price,input_amount,old_unit_price,
                  new_unit_price,old_amount,old_tax,tax_rate,amount,untaxed_amount,tax_amount,cost_tax_inclusive,prior_return_basis)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), actor.tenantId(), actor.companyId(), header, lineNo, input.receiptLineId(), source.get("version"),
                source.get("sku_id"), source.get("unit_id"), result.quantity(), remaining, source.get("returned_base_qty"),
                input.newUnitPrice() == null ? null : new BigDecimal(input.newUnitPrice()),
                input.adjustmentAmount() == null ? null : new BigDecimal(input.adjustmentAmount()), result.oldUnitPrice(), result.newUnitPrice(),
                gross, tax, source.get("tax_rate"), result.amount(), result.untaxed(), result.tax(), inclusive(source), source.get("pricing_return_basis"));
    }

    Map<Long, Map<String, Object>> revalidate(CompanyAccess actor, Map<String, Object> header, List<Map<String, Object>> lines) {
        var result = new LinkedHashMap<Long, Map<String, Object>>();
        var sorted = lines.stream().sorted(java.util.Comparator.comparingLong(line -> number(line, "receipt_line_id"))).toList();
        for (var line : sorted) {
            var source = queries.source(actor, number(line, "receipt_line_id"), true);
            queries.eligible(actor, source, number(header, "partner_id"), number(header, "warehouse_id"), date(header, "business_date"));
            if (number(source, "version") != number(line, "source_version"))
                throw new cn.erp.shared.ApiException(409, "PRICE_SOURCE_CHANGED", "原收货行已发生退货、调价或其他修改，请更新草稿；已审批的调整单请作废后重新申请");
            result.put(number(line, "id"), source);
        }
        return result;
    }
}
