package cn.erp.mfg;

import cn.erp.catalog.CatalogAvailability;
import cn.erp.catalog.CatalogAvailability.SkuUnit;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgStockModels.*;
import static cn.erp.salesfinance.FinanceSupport.*;

@Component
class MfgStockLines {
    private static final int MAX_LINES = 100;
    private static final int QUANTITY_SCALE = 6;
    private static final int MAX_QUANTITY_PRECISION = 24;
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final CatalogAvailability catalog;

    MfgStockLines(JdbcTemplate jdbc, InventoryService inventory, CatalogAvailability catalog) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.catalog = catalog;
    }

    private record Line(long sku, long unit, BigDecimal qty, Long material, BigDecimal equivalent,
                        Long origin, StockSelection selection) {}

    void replace(CompanyAccess actor, Type type, long document, Map<String, Object> order, Draft input) {
        if (input.lines() == null || input.lines().isEmpty() || input.lines().size() > MAX_LINES)
            throw invalid("请填写 1 到 100 行生产明细");
        var materials = type == Type.ISSUE ? materials(actor, id(order, "id")) : Map.<Long, Map<String, Object>>of();
        var lines = new ArrayList<Line>();
        for (var raw : input.lines()) {
            if (raw == null) throw invalid("生产单据明细不能为空");
            lines.add(type == Type.ISSUE ? issueLine(actor, input, raw, materials) : receiptLine(order, raw));
        }
        catalog.requireActiveSkus(actor, lines.stream().map(line -> new SkuUnit(line.sku(), line.unit())).toList());
        jdbc.update("DELETE FROM " + type.line + " WHERE tenant_id=? AND company_id=? AND doc_id=?",
                actor.tenantId(), actor.companyId(), document);
        write(actor, type, document, lines);
    }

    private void write(CompanyAccess actor, Type type, long document, List<Line> lines) {
        var values = new ArrayList<Object[]>();
        for (int index = 0; index < lines.size(); index++) {
            var line = lines.get(index);
            var id = Ids.next();
            var selection = inventory.encodeSelection(line.selection());
            values.add(type == Type.ISSUE
                    ? new Object[]{id, actor.tenantId(), actor.companyId(), document, index + 1, line.sku(), line.unit(),
                            line.qty(), line.material(), line.equivalent(), line.origin(), selection}
                    : new Object[]{id, actor.tenantId(), actor.companyId(), document, index + 1, line.sku(), line.unit(), line.qty(), selection});
        }
        String fields = "id,tenant_id,company_id,doc_id,line_no,sku_id,unit_id,qty,";
        String statement = type == Type.ISSUE
                ? fields + "material_id,material_qty,original_issue_line_id,stock_selection) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                : fields + "stock_selection) VALUES(?,?,?,?,?,?,?,?,?)";
        jdbc.batchUpdate("INSERT INTO " + type.line + "(" + statement, values);
    }

    Map<Long, Map<String, Object>> materials(CompanyAccess actor, long order) {
        return materials(actor, order, true);
    }

    private Map<Long, Map<String, Object>> materials(CompanyAccess actor, long order, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT * FROM mfg_work_order_material WHERE tenant_id=? AND company_id=? AND work_order_id=?
                ORDER BY line_no
                """ + (lock ? " FOR UPDATE" : ""), actor.tenantId(), actor.companyId(), order);
        var result = new HashMap<Long, Map<String, Object>>();
        for (var row : rows) result.put(id(row, "id"), row);
        return result;
    }

    boolean overIssue(CompanyAccess actor, long order, List<Map<String, Object>> lines) {
        return overIssue(actor, order, lines, true);
    }

    boolean overIssue(CompanyAccess actor, long order, List<Map<String, Object>> lines, boolean lock) {
        var allocations = new HashMap<Long, BigDecimal>();
        for (var line : lines) allocations.merge(id(line, "material_id"), decimal(line, "material_qty"), BigDecimal::add);
        var materials = materials(actor, order, lock);
        for (var entry : allocations.entrySet()) {
            var material = materials.get(entry.getKey());
            if (material == null) throw missing();
            var available = decimal(material, "required_qty").subtract(decimal(material, "issued_qty"))
                    .add(decimal(material, "returned_qty"));
            if (entry.getValue().compareTo(available) > 0) return true;
        }
        return false;
    }

    Map<String, Object> original(CompanyAccess actor, long order, long warehouse, long material, long origin, LocalDate day) {
        return jdbc.queryForList("""
                SELECT l.*,d.id issue_doc_id FROM mfg_issue_line l JOIN mfg_issue_doc d
                  ON d.tenant_id=l.tenant_id AND d.company_id=l.company_id AND d.id=l.doc_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.id=? AND l.material_id=?
                  AND d.work_order_id=? AND d.warehouse_id=? AND d.business_date<=?
                  AND d.direction='issue' AND d.status='posted' FOR SHARE OF d, l
                """, actor.tenantId(), actor.companyId(), origin, material, order, warehouse, day).stream().findFirst()
                .orElseThrow(() -> new ApiException(404, "NOT_FOUND", "没有可用的原领料明细，请检查工单、原单和仓库"));
    }

    private Line issueLine(CompanyAccess actor, Draft input, InputLine raw, Map<Long, Map<String, Object>> materials) {
        var material = materials.get(raw.materialId());
        if (material == null || id(material, "issue_warehouse_id") != input.warehouseId())
            throw invalid("领退料明细必须属于当前工单和领料仓");
        long materialId = id(material, "id");
        var qty = quantity(raw.qty());
        if ("return".equals(input.direction())) {
            if (raw.originalIssueLineId() == null || raw.originalIssueLineId() <= 0) throw invalid("请选择原领料单的明细");
            var original = original(actor, input.workOrderId(), input.warehouseId(), materialId, raw.originalIssueLineId(), input.businessDate());
            if (raw.skuId() != null && raw.skuId() != id(original, "sku_id")) throw invalid("退料商品必须与原领料明细一致");
            if (qty.compareTo(decimal(original, "qty")) > 0) throw invalid("退料数量不能超过原领料数量");
            var equivalent = proportional(qty, decimal(original, "qty"), decimal(original, "material_qty"), QUANTITY_SCALE);
            validEquivalent(equivalent);
            return new Line(id(original, "sku_id"), id(original, "unit_id"), qty, materialId,
                    equivalent, raw.originalIssueLineId(), raw.stockSelection());
        }
        if (raw.originalIssueLineId() != null) throw invalid("领料不应引用原领料单");
        long primary = id(material, "component_sku_id");
        long sku = raw.skuId() == null ? primary : raw.skuId();
        if (sku == primary) return new Line(sku, id(material, "unit_id"), qty, materialId, qty, null, raw.stockSelection());
        var option = jdbc.queryForList("""
                SELECT unit_id,ratio FROM mfg_work_order_substitute
                WHERE tenant_id=? AND company_id=? AND work_order_material_id=? AND substitute_sku_id=?
                """, actor.tenantId(), actor.companyId(), materialId, sku).stream().findFirst()
                .orElseThrow(() -> invalid("所选物料不在该工单的替代料清单中"));
        var equivalent = qty.divide(decimal(option, "ratio"), QUANTITY_SCALE, RoundingMode.HALF_UP);
        validEquivalent(equivalent);
        return new Line(sku, id(option, "unit_id"), qty, materialId, equivalent, null, raw.stockSelection());
    }

    private Line receiptLine(Map<String, Object> order, InputLine raw) {
        long sku = id(order, "sku_id");
        if (raw.materialId() != null || raw.originalIssueLineId() != null || raw.skuId() != null && raw.skuId() != sku)
            throw invalid("完工入库只接收当前工单的成品");
        return new Line(sku, id(order, "unit_id"), quantity(raw.qty()), null, null, null, raw.stockSelection());
    }

    static BigDecimal proportional(BigDecimal qty, BigDecimal base, BigDecimal total, int scale) {
        return total.multiply(qty).divide(base, scale, RoundingMode.HALF_UP);
    }

    static void validEquivalent(BigDecimal quantity) {
        if (quantity.signum() <= 0 || quantity.precision() > MAX_QUANTITY_PRECISION)
            throw invalid("折算领退料数量超出允许范围");
    }
}
