package cn.erp.adjustment;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;

@Component
@Transactional(readOnly = true)
public class PriceAdjustmentQueries {
    static final String TYPE = "price_adjustment";
    private final JdbcTemplate jdbc;
    public PriceAdjustmentQueries(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    static void require(CompanyAccess actor, String permission) {
        if (!actor.hasPermission(permission) || !actor.hasPermission("cost:view"))
            throw new ApiException(403, "FORBIDDEN", "调价操作需要对应权限和成本查看权限");
    }

    Map<String, Object> head(CompanyAccess actor, long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM price_adjustment WHERE tenant_id=? AND company_id=? AND id=? AND kind='purchase'"
                + (lock ? " FOR UPDATE" : ""), actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty() || !actor.allowsWarehouse(number(rows.getFirst(), "warehouse_id"))) throw missing();
        cn.erp.shared.PriceAdjustmentScope.require(jdbc, actor, rows.getFirst());
        return rows.getFirst();
    }

    List<Map<String, Object>> lines(CompanyAccess actor, long id) {
        return jdbc.queryForList("SELECT l.*,s.name sku_name,u.name unit_name,h.receipt_no FROM price_adjustment_line l"
                + " JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id"
                + " LEFT JOIN goods_receipt_line r ON r.tenant_id=l.tenant_id AND r.company_id=l.company_id AND r.id=l.receipt_line_id"
                + " LEFT JOIN goods_receipt h ON h.tenant_id=r.tenant_id AND h.company_id=r.company_id AND h.id=r.receipt_id"
                + " WHERE l.tenant_id=? AND l.company_id=? AND l.adjustment_id=? ORDER BY l.line_no", actor.tenantId(), actor.companyId(), id);
    }

    public Map<String, Object> get(CompanyAccess actor, long id) {
        require(actor, TYPE + ":view");
        var row = head(actor, id, false);
        var result = ApiRows.row(row);
        result.put("documentNo", row.get("adjust_no"));
        result.put("partnerName", jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?", String.class, actor.tenantId(), row.get("partner_id")));
        result.put("warehouseName", jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?", String.class, actor.tenantId(), actor.companyId(), row.get("warehouse_id")));
        result.put("lines", lines(actor, id).stream().map(raw -> {
            var line = ApiRows.row(raw);
            line.remove("priorReturnBasis");
            return line;
        }).toList());
        result.put("costAllocations", jdbc.queryForList("SELECT c.*,w.name warehouse_name FROM price_adjustment_cost c JOIN warehouse w"
                + " ON w.tenant_id=c.tenant_id AND w.company_id=c.company_id AND w.id=c.warehouse_id"
                + " WHERE c.tenant_id=? AND c.company_id=? AND c.adjustment_id=? ORDER BY c.adjustment_line_id,c.warehouse_id",
                actor.tenantId(), actor.companyId(), id).stream().map(ApiRows::row).toList());
        result.put("allowedActions", actions(actor, row));
        result.put("canPreviewCost",actor.allWarehouses() && actor.orgScope().allDepartments()
                && Set.of("draft","rejected","pending","approved").contains(row.get("status").toString()));
        return result;
    }

    public Map<String, Object> list(CompanyAccess actor, String status, Long partner, Long cursor, int limit) {
        require(actor, TYPE + ":view");
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0) throw invalid("分页参数无效");
        if (status != null && !Set.of("draft", "pending", "approved", "rejected", "posted", "voided", "reversed").contains(status)) throw invalid("调价单状态无效");
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), cursor == null ? Long.MAX_VALUE : cursor));
        String filter = "tenant_id=? AND company_id=? AND id<? AND kind='purchase'" + OrgOwnership.filter(actor, "p");
        if (status != null) { filter += " AND status=?"; args.add(status); }
        if (partner != null) { filter += " AND partner_id=?"; args.add(partner); }
        if (!actor.allWarehouses()) {
            if (actor.warehouseIds().isEmpty()) filter += " AND 1=0";
            else {
                String placeholders = String.join(",", java.util.Collections.nCopies(actor.warehouseIds().size(), "?"));
                filter += " AND warehouse_id IN(" + placeholders + ")"; args.addAll(actor.warehouseIds());
                filter += " AND NOT EXISTS(SELECT 1 FROM price_adjustment_cost pc WHERE pc.tenant_id=p.tenant_id AND pc.company_id=p.company_id AND pc.adjustment_id=p.id AND pc.warehouse_id NOT IN(" + placeholders + "))";
                args.addAll(actor.warehouseIds());
            }
        }
        args.add(limit + 1);
        var ids = jdbc.queryForList("SELECT id FROM price_adjustment p WHERE " + filter + " ORDER BY id DESC LIMIT ?", Long.class, args.toArray());
        var result = new LinkedHashMap<String, Object>();
        result.put("items", ids.stream().limit(limit).map(id -> get(actor, id)).toList());
        result.put("nextCursor", ids.size() > limit ? ids.get(limit - 1).toString() : null);
        return result;
    }

    public Map<String,Object> metadata(CompanyAccess actor) {
        require(actor,TYPE+":view");
        if(!actor.hasPermission("goods_receipt:view"))throw new ApiException(403,"FORBIDDEN","选择调价来源需要收货查看权限");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        String visible=OrgOwnership.filter(actor,"h");
        if(!actor.allWarehouses()) {
            if(actor.warehouseIds().isEmpty())visible+=" AND 1=0";
            else {visible+=" AND h.warehouse_id IN("+String.join(",",java.util.Collections.nCopies(actor.warehouseIds().size(),"?"))+")";args.addAll(actor.warehouseIds());}
        }
        var sources=jdbc.queryForList("SELECT DISTINCT p.id partner_id,p.name partner_name,w.id warehouse_id,w.name warehouse_name FROM goods_receipt h"
                +" JOIN business_partner p ON p.tenant_id=h.tenant_id AND p.id=h.supplier_id AND p.status='active'"
                +" JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='supplier' AND r.status='active'"
                +" JOIN warehouse w ON w.tenant_id=h.tenant_id AND w.company_id=h.company_id AND w.id=h.warehouse_id AND w.status='active'"
                +" WHERE h.tenant_id=? AND h.company_id=? AND h.status='posted'"+visible
                +" AND EXISTS(SELECT 1 FROM goods_receipt_line l WHERE l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.receipt_id=h.id AND l.base_qty>l.returned_base_qty+l.pending_return_base_qty)"
                +" ORDER BY p.id,w.id",args.toArray());
        var partners=new LinkedHashMap<String,Map<String,String>>();
        var warehouses=new LinkedHashMap<String,Map<String,String>>();
        for(var row:sources) {
            String partner=row.get("partner_id").toString(),warehouse=row.get("warehouse_id").toString();
            partners.put(partner,Map.of("id",partner,"name",row.get("partner_name").toString()));
            warehouses.put(warehouse,Map.of("id",warehouse,"name",row.get("warehouse_name").toString()));
        }
        return Map.of("partners",List.copyOf(partners.values()),"warehouses",List.copyOf(warehouses.values()));
    }

    Map<String, Object> source(CompanyAccess actor, long line, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT l.*,h.supplier_id,h.warehouse_id,h.status receipt_status,h.business_date receipt_date,
                  h.cost_tax_inclusive,h.owner_user_id,h.owner_department_id,o.tax_rate,o.order_id
                FROM goods_receipt_line l JOIN goods_receipt h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.receipt_id
                JOIN purchase_order_line o ON o.tenant_id=l.tenant_id AND o.company_id=l.company_id AND o.id=l.order_line_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.id=?
                """ + (lock ? " FOR UPDATE" : ""), actor.tenantId(), actor.companyId(), line);
        if (rows.isEmpty() || !actor.allowsWarehouse(number(rows.getFirst(), "warehouse_id"))) throw missing();
        OrgOwnership.require(actor, rows.getFirst());
        if (!actor.hasPermission("goods_receipt:view")) throw new ApiException(403, "FORBIDDEN", "没有查看原收货单的权限");
        return rows.getFirst();
    }

    void eligible(CompanyAccess actor, Map<String, Object> source, long partner, long warehouse, LocalDate day) {
        if (!"posted".equals(source.get("receipt_status"))) throw invalid("只能调整已过账的收货行");
        if (number(source, "supplier_id") != partner || number(source, "warehouse_id") != warehouse) throw invalid("调价行的供应商和仓库必须与调整单一致");
        if (day.isBefore(date(source, "receipt_date"))) throw invalid("调价日期不能早于原收货日期");
        if (amount(source, "pending_return_base_qty").signum() != 0) throw invalid("原行有未完成的采购退货，请先处理退货单");
        if (amount(source, "base_qty").compareTo(amount(source, "returned_base_qty")) <= 0) throw invalid("原收货行已经全部退货，不能调价");
        if (!jdbc.queryForList("SELECT id FROM document_reversal WHERE tenant_id=? AND company_id=? AND source_type='goods_receipt' AND source_id=? AND status<>'rejected'",
                Long.class, actor.tenantId(), actor.companyId(), source.get("receipt_id")).isEmpty()) throw invalid("原收货正在冲销，不能调价");
        if (!jdbc.queryForList("SELECT h.id FROM price_adjustment h JOIN price_adjustment_line l ON l.adjustment_id=h.id AND l.tenant_id=h.tenant_id AND l.company_id=h.company_id"
                + " WHERE h.tenant_id=? AND h.company_id=? AND l.receipt_line_id=? AND h.status='posted' AND h.business_date>? LIMIT 1",
                Long.class, actor.tenantId(), actor.companyId(), source.get("id"), day).isEmpty()) throw invalid("调价日期不能早于该行已有调价日期");
    }

    static void day(LocalDate day) {
        if (day == null || day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))) throw invalid("业务日期不能为空或晚于今天");
    }
    static long number(Map<String, Object> row, String name) { return ((Number) row.get(name)).longValue(); }
    static BigDecimal amount(Map<String, Object> row, String name) { return (BigDecimal) row.get(name); }
    static LocalDate date(Map<String, Object> row, String name) { return ((java.sql.Date) row.get(name)).toLocalDate(); }
    static ApiException invalid(String message) { return new ApiException(400, "VALIDATION_FAILED", message); }
    static ApiException missing() { return new ApiException(404, "NOT_FOUND", "调价单或原收货不在当前可见范围"); }

    private List<String> actions(CompanyAccess actor, Map<String,Object> header) {
        var actions = switch (header.get("status").toString()) {
            case "draft", "rejected" -> List.of("update", "submit", "void");
            case "pending" -> List.of("approve", "reject");
            case "approved" -> List.of("post", "void");
            default -> List.<String>of();
        };
        boolean financial=actor.allWarehouses() && actor.orgScope().allDepartments();
        boolean flow=number(header,"wf_instance_id")>0 && !jdbc.queryForList("SELECT id FROM wf_instance WHERE tenant_id=? AND company_id=? AND id=? AND status='running'",
                Long.class,actor.tenantId(),actor.companyId(),header.get("wf_instance_id")).isEmpty();
        return actions.stream().filter(action -> actor.hasPermission(TYPE + ":" + (action.equals("reject") ? "approve" : action)))
                .filter(action -> !Set.of("approve","reject","post").contains(action) || financial)
                .filter(action -> !Set.of("approve","reject").contains(action) || number(header,"created_by")!=actor.userId() && !flow).toList();
    }
}
