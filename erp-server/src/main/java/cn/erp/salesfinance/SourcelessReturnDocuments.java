package cn.erp.salesfinance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.pricing.PricingEngine;
import cn.erp.pricing.PricingModels;
import cn.erp.reference.ReferenceAvailability;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import static cn.erp.salesfinance.FinanceSupport.*;

/** 无源录价沿用R3，草稿不创建库存或往来；单位换算在保存时冻结。 */
@Component
class SourcelessReturnDocuments {
    private record Prepared(FinanceModels.ReturnLine input,BigDecimal rate,BigDecimal manual,String reason) {}
    private final JdbcTemplate jdbc;
    private final PricingEngine pricing;
    private final InventoryService inventory;
    private final ReferenceAvailability references;

    SourcelessReturnDocuments(JdbcTemplate jdbc,PricingEngine pricing,InventoryService inventory,ReferenceAvailability references) {
        this.jdbc=jdbc;this.pricing=pricing;this.inventory=inventory;this.references=references;
    }

    static String mode(FinanceModels.ReturnInput input) {
        if(input==null) throw invalid("请填写退货内容");
        String mode=input.returnMode()==null?"original":input.returnMode();
        if(!Set.of("original","sourceless").contains(mode)) throw invalid("退货模式无效");
        if(input.reason()==null || input.reason().isBlank() || input.reason().length()>200
                || input.legacyReference()!=null && input.legacyReference().length()>500) throw invalid("请填写有效退货原因和历史依据");
        return mode;
    }

    static boolean sourceless(Map<String,Object> header) { return "sourceless".equals(header.get("return_mode")); }

    void save(CompanyAccess actor,long returnId,FinanceModels.ReturnInput input,List<Map<String,Object>> previous) {
        var prepared=new ArrayList<Prepared>();
        var prices=new ArrayList<PricingModels.Line>();
        for(var line:input.lines()) {
            if(line==null || line.shipmentLineId()!=null || line.skuId()==null || line.skuId()<=0
                    || line.unitId()==null || line.unitId()<=0 || !Set.of("pending","passed","failed").contains(line.qualityResult()==null?"":line.qualityResult()))
                throw invalid("无原单退货须选择商品、单位和质检结果，不能引用原发货行");
            var manual=manual(actor,line,previous);
            BigDecimal rate=rate(actor,line.skuId(),line.unitId());
            prepared.add(new Prepared(line,rate,(BigDecimal)manual.get("amount"),(String)manual.get("reason")));
            prices.add(new PricingModels.Line(line.skuId().toString(),line.unitId().toString(),input.warehouseId().toString(),
                    line.qty(),rate.toPlainString(),line.unitPrice(),null,null,null,line.taxRate()));
        }
        var valued=pricing.calculate(new PricingModels.Calculation(true,"0",prices,List.of()));
        jdbc.update("DELETE FROM sales_return_line WHERE tenant_id=? AND company_id=? AND return_id=?",actor.tenantId(),actor.companyId(),returnId);
        var inserts=new ArrayList<Object[]>();
        for(int index=0;index<prepared.size();index++) {
            var value=prepared.get(index);var line=value.input();var price=valued.lines().get(index);
            inserts.add(new Object[]{Ids.next(),actor.tenantId(),actor.companyId(),returnId,index+1,"sourceless",line.skuId(),line.unitId(),value.rate(),
                    price.qty(),price.baseQty(),line.qualityResult(),inventory.encodeSelection(line.stockSelection()),price.unitPrice(),price.taxRate(),
                    price.totalAmount(),price.untaxedAmount(),price.taxAmount(),value.manual(),value.reason(),actor.userId(),actor.userId()});
        }
        jdbc.batchUpdate("""
                INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,return_mode,sku_id,unit_id,unit_rate,qty,base_qty,
                  quality_result,stock_selection,unit_price,tax_rate,credit_amount,untaxed_amount,tax_amount,manual_unit_cost,manual_cost_reason,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,inserts);
        jdbc.update("UPDATE sales_return SET credit_amount=?,untaxed_amount=?,tax_amount=?,cost_amount=0,legacy_reference=? WHERE tenant_id=? AND company_id=? AND id=?",
                valued.totalAmount(),valued.untaxedAmount(),valued.taxAmount(),input.legacyReference(),actor.tenantId(),actor.companyId(),returnId);
    }

    private Map<String,Object> manual(CompanyAccess actor,FinanceModels.ReturnLine input,List<Map<String,Object>> previous) {
        var result=new java.util.LinkedHashMap<String,Object>();
        if(!actor.hasPermission("cost:view")) {
            if(input.manualUnitCost()!=null || input.manualCostReason()!=null && !input.manualCostReason().isBlank())
                throw new ApiException(403,"FORBIDDEN","填写成本依据需要成本查看权限");
            // 普通经办修改售价或数量时，不能因详情隐藏成本而抹掉财务已填的单位成本。
            var same=previous.stream().filter(line->id(line,"sku_id")==input.skuId() && line.get("manual_unit_cost")!=null).toList();
            if(same.stream().anyMatch(line->!java.util.Objects.equals(line.get("manual_unit_cost"),same.getFirst().get("manual_unit_cost"))
                    || !java.util.Objects.equals(line.get("manual_cost_reason"),same.getFirst().get("manual_cost_reason"))))
                throw invalid("同一商品的原成本依据不一致，请由有成本权限人员统一核对");
            if(!same.isEmpty()) {result.put("amount",same.getFirst().get("manual_unit_cost"));result.put("reason",same.getFirst().get("manual_cost_reason"));}
            return result;
        }
        BigDecimal amount=SourcelessReturnCosts.manualCost(input.manualUnitCost());
        String reason=input.manualCostReason()==null || input.manualCostReason().isBlank()?null:input.manualCostReason().trim();
        if(amount!=null && (reason==null || reason.length()>200) || amount==null && reason!=null)
            throw invalid("手填成本与200字以内的依据须同时填写");
        result.put("amount",amount);result.put("reason",reason);return result;
    }

    private BigDecimal rate(CompanyAccess actor,long sku,long unit) {
        var rows=jdbc.queryForList("""
                SELECT c.rate_to_base FROM unit_conversion c JOIN sku s ON s.tenant_id=c.tenant_id AND s.id=c.sku_id
                JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id JOIN unit u ON u.tenant_id=c.tenant_id AND u.id=c.unit_id
                WHERE c.tenant_id=? AND c.sku_id=? AND c.unit_id=? AND s.status='active' AND p.status='active' AND u.status='active' FOR SHARE
                """,BigDecimal.class,actor.tenantId(),sku,unit);
        if(rows.isEmpty()) throw missing();return rows.getFirst();
    }

    void revalidate(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        date(((java.sql.Date)header.get("business_date")).toLocalDate());
        references.requirePartner(actor,id(header,"customer_id"),"customer");
        var warehouses=jdbc.queryForList("SELECT id FROM warehouse WHERE tenant_id=? AND company_id=? AND id=? AND status='active' FOR SHARE",
                Long.class,actor.tenantId(),actor.companyId(),header.get("warehouse_id"));
        if(warehouses.isEmpty() || !actor.allowsWarehouse(id(header,"warehouse_id"))) throw missing();
        var enabled=jdbc.queryForList("""
                SELECT l.id FROM sales_return_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id
                JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
                WHERE l.tenant_id=? AND l.company_id=? AND l.return_id=? AND s.status='active' AND p.status='active' AND u.status='active'
                ORDER BY l.sku_id,l.unit_id,l.id FOR SHARE
                """,Long.class,actor.tenantId(),actor.companyId(),header.get("id"));
        if(lines.isEmpty() || enabled.size()!=lines.size()) throw new ApiException(422,"MASTER_DATA_DISABLED","退货商品或单位已停用，请重新核对");
    }
}
