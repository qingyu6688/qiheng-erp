package cn.erp.salesfinance;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import tools.jackson.databind.ObjectMapper;
import static cn.erp.salesfinance.FinanceSupport.*;

/** 锁定整个退货涉及的库存池后一次取价，不能让先入库的行影响后续行。 */
@Component
class SourcelessReturnCostPlans {
    record Snapshot(long warehouseId,long skuId,String source,BigDecimal stockQuantity,BigDecimal stockAmount,
                    BigDecimal unitCost,long version,BigDecimal baseQuantity,BigDecimal costAmount) {
        SourcelessReturnCosts.Basis basis() {return new SourcelessReturnCosts.Basis(source,stockQuantity,stockAmount,unitCost,version);}
    }
    record Valued(long lineId,Snapshot snapshot) {}
    record Plan(List<Valued> items,BigDecimal amount) {}
    private final JdbcTemplate jdbc;
    private final InventoryService inventory;
    private final SourcelessReturnCosts costs;
    private final ObjectMapper json;

    SourcelessReturnCostPlans(JdbcTemplate jdbc,InventoryService inventory,SourcelessReturnCosts costs,ObjectMapper json) {
        this.jdbc=jdbc;this.inventory=inventory;this.costs=costs;this.json=json;
    }

    Plan build(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines,boolean lock) {
        long warehouse=id(header,"warehouse_id");
        var groups=new LinkedHashMap<Long,List<Map<String,Object>>>();
        lines.stream().sorted(Comparator.comparingLong(line->id(line,"line_no")))
                .forEach(line->groups.computeIfAbsent(id(line,"sku_id"),unused->new ArrayList<>()).add(line));
        if(groups.isEmpty()) throw invalid("退货明细不能为空");
        if(lock) inventory.lockKeys(actor,groups.keySet().stream().sorted().map(sku->new Key(warehouse,sku)).toList());
        var arguments=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),warehouse));arguments.addAll(groups.keySet());
        var rows=jdbc.queryForList("SELECT sku_id,qty,total_cost,last_in_unit_cost,version FROM inventory_cost WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id IN("
                +String.join(",",java.util.Collections.nCopies(groups.size(),"?"))+") ORDER BY sku_id",arguments.toArray());
        var stock=new LinkedHashMap<Long,Map<String,Object>>();rows.forEach(row->stock.put(id(row,"sku_id"),row));
        var result=new ArrayList<Valued>();BigDecimal total=BigDecimal.ZERO.setScale(2);
        for(var entry:groups.entrySet()) {
            var source=stock.get(entry.getKey());var group=entry.getValue();
            var state=source==null?new SourcelessReturnCosts.Stock(BigDecimal.ZERO,BigDecimal.ZERO,null,0)
                    :new SourcelessReturnCosts.Stock(decimal(source,"qty"),decimal(source,"total_cost"),decimal(source,"last_in_unit_cost"),id(source,"version"));
            var first=group.getFirst();
            for(var line:group) if(!Objects.equals(line.get("manual_unit_cost"),first.get("manual_unit_cost"))
                    || !Objects.equals(line.get("manual_cost_reason"),first.get("manual_cost_reason")))
                throw invalid("同一商品的手填单位成本及依据须一致，请统一核对或拆单");
            var basis=costs.choose(state,decimal(first,"manual_unit_cost"),(String)first.get("manual_cost_reason"));
            var amounts=costs.allocate(basis,group.stream().map(line->decimal(line,"base_qty")).toList());
            for(int index=0;index<group.size();index++) {
                var line=group.get(index);BigDecimal amount=amounts.get(index);total=total.add(amount);
                result.add(new Valued(id(line,"id"),new Snapshot(warehouse,entry.getKey(),basis.source(),basis.stockQuantity(),basis.stockAmount(),
                        basis.unitCost(),basis.version(),decimal(line,"base_qty"),amount)));
            }
        }
        return new Plan(List.copyOf(result),total);
    }

    void freeze(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        var plan=build(actor,header,lines,true);
        for(var line:plan.items()) jdbc.update("UPDATE sales_return_line SET cost_basis=?,unit_cost=?,cost_amount=?,version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                json.writeValueAsString(view(line.snapshot())),line.snapshot().unitCost(),line.snapshot().costAmount(),actor.userId(),actor.tenantId(),actor.companyId(),line.lineId());
        jdbc.update("UPDATE sales_return SET cost_amount=? WHERE tenant_id=? AND company_id=? AND id=?",plan.amount(),actor.tenantId(),actor.companyId(),header.get("id"));
    }

    Plan recheck(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        Plan current;
        try {current=build(actor,header,lines,true);}
        catch(ApiException error) {
            if(java.util.Set.of("RETURN_COST_REQUIRED","RETURN_COST_SOURCE_CHANGED").contains(error.getErrorCode())) throw changed();
            throw error;
        }
        var previous=new LinkedHashMap<Long,Map<String,Object>>();lines.forEach(line->previous.put(id(line,"id"),line));
        for(var valued:current.items()) {
            var row=previous.get(valued.lineId());var old=read(row.get("cost_basis"));var now=valued.snapshot();
            if(row.get("cost_amount")==null || row.get("unit_cost")==null) throw changed();
            if(old.warehouseId()!=now.warehouseId() || old.skuId()!=now.skuId() || !old.source().equals(now.source())
                    || old.unitCost().compareTo(now.unitCost())!=0 || old.baseQuantity().compareTo(now.baseQuantity())!=0
                    || old.costAmount().compareTo(now.costAmount())!=0 || old.costAmount().compareTo(decimal(row,"cost_amount"))!=0
                    || old.unitCost().compareTo(decimal(row,"unit_cost"))!=0) throw changed();
            // 数量或版本变化但成本比例完全相同时可继续；不能只比较八位显示单价。
            if(old.source().equals("average") && old.stockAmount().multiply(now.stockQuantity()).compareTo(now.stockAmount().multiply(old.stockQuantity()))!=0) throw changed();
        }
        if(decimal(header,"cost_amount").compareTo(current.amount())!=0) throw changed();
        return current;
    }

    Map<String,Object> preview(CompanyAccess actor,Map<String,Object> header,List<Map<String,Object>> lines) {
        if(!actor.hasPermission("cost:view")) throw new ApiException(403,"FORBIDDEN","查看退货成本预览需要成本权限");
        var plan=build(actor,header,lines,false);
        return Map.of("items",plan.items().stream().map(item->{var result=view(item.snapshot());result.put("lineId",Long.toString(item.lineId()));return result;}).toList(),
                "totalCost",plan.amount().toPlainString(),"notice","预览按当前仓库成本计算；提交冻结依据，审批和过账重新核对，成本变化须重新提交审批");
    }

    Map<String,Object> view(Object raw) {return raw==null?null:view(read(raw));}

    private Map<String,Object> view(Snapshot value) {
        var result=new LinkedHashMap<String,Object>();result.put("source",value.source());
        result.put("warehouseId",Long.toString(value.warehouseId()));result.put("skuId",Long.toString(value.skuId()));
        result.put("stockQuantity",value.stockQuantity().toPlainString());result.put("stockAmount",value.stockAmount().toPlainString());
        result.put("unitCost",value.unitCost().toPlainString());result.put("version",Long.toString(value.version()));
        result.put("baseQuantity",value.baseQuantity().toPlainString());result.put("costAmount",value.costAmount().toPlainString());return result;
    }

    private Snapshot read(Object raw) {
        try {
            if(raw==null) throw changed();
            var value=json.readValue(raw.toString(),Snapshot.class);
            if(value==null || value.warehouseId()<=0 || value.skuId()<=0 || value.source()==null
                    || !java.util.Set.of("average","last_in","manual").contains(value.source()) || value.version()<0
                    || value.stockQuantity()==null || value.stockAmount()==null || value.unitCost()==null || value.baseQuantity()==null || value.costAmount()==null
                    || value.stockQuantity().signum()<0 || value.stockAmount().signum()<0 || value.unitCost().signum()<0 || value.baseQuantity().signum()<=0 || value.costAmount().signum()<0
                    || value.source().equals("average") && value.stockQuantity().signum()==0) throw changed();
            return value;
        } catch(RuntimeException error) {throw new ApiException(409,"RETURN_COST_SNAPSHOT_INVALID","退货成本快照不完整，请重新提交审批");}
    }

    private static ApiException changed() {return new ApiException(409,"RETURN_COST_CHANGED","退货成本来源或金额已变化，请重新提交审批");}
}
