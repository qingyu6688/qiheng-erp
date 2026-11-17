package cn.erp.mfg;

import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import static cn.erp.mfg.MfgPlanning.*;

@Component
class BomExpansion {
    private static final int MAX_DEPTH=10;
    private static final int MAX_GRAPH_VISITS=100_000;
    private static final int MAX_EXPANDED_LINES=2000;
    private final JdbcTemplate jdbc;
    private final MfgBoms boms;
    private final InventoryService inventory;

    BomExpansion(JdbcTemplate jdbc,MfgBoms boms,InventoryService inventory) {
        this.jdbc=jdbc; this.boms=boms; this.inventory=inventory;
    }

    record Alternative(long skuId,long unitId,BigDecimal ratio,int priority) {}
    record Material(long bomLineId,long skuId,long unitId,Long warehouseId,BigDecimal quantity,
                    List<String> path,List<Alternative> alternatives,String skuCode,String skuName,
                    boolean phantom,boolean consumable) {}

    private record Quantity(BigDecimal numerator,BigDecimal denominator) {
        Quantity scale(BigDecimal factor,BigDecimal base) {
            return new Quantity(numerator.multiply(factor),denominator.multiply(base));
        }

        BigDecimal round() {return fit(numerator.divide(denominator,SCALE,RoundingMode.CEILING),RoundingMode.UNNECESSARY);}
    }

    List<Material> expand(CompanyAccess actor,Map<String,Object> bom,BigDecimal planBase,LocalDate day,
                          Long warehouse,boolean preview) {
        var sweep=new Expansion(actor,day,preview);
        sweep.expand(bom,new Quantity(planBase,BigDecimal.ONE),warehouse,List.of(Long.toString(number(bom,"sku_id"))),true);
        return List.copyOf(sweep.materials);
    }

    List<Map<String,Object>> view(CompanyAccess actor,List<Material> materials) {
        var balances=new HashMap<Key,String>();
        return materials.stream().map(material->{
            var row=new LinkedHashMap<String,Object>();
            row.put("bomLineId",Long.toString(material.bomLineId()));
            row.put("componentSkuId",Long.toString(material.skuId()));
            row.put("unitId",Long.toString(material.unitId()));
            row.put("skuCode",material.skuCode()); row.put("skuName",material.skuName());
            row.put("componentSkuCode",material.skuCode()); row.put("componentSkuName",material.skuName());
            row.put("requiredQty",material.quantity().toPlainString());
            row.put("issueWarehouseId",material.warehouseId()==null ? null : material.warehouseId().toString());
            row.put("path",material.path()); row.put("depth",material.path().size()-1);
            row.put("phantom",material.phantom()); row.put("consumable",material.consumable());
            row.put("substitutes",material.alternatives().stream().map(alternate->Map.of(
                    "substituteSkuId",Long.toString(alternate.skuId()),"unitId",Long.toString(alternate.unitId()),
                    "ratio",alternate.ratio().toPlainString(),"priority",alternate.priority())).toList());
            if(material.warehouseId()!=null && actor.hasPermission("inventory:view")) {
                var key=new Key(material.warehouseId(),material.skuId());
                row.put("availableQty",balances.computeIfAbsent(key,k->inventory.balance(actor,k).available().setScale(SCALE).toPlainString()));
            }
            return (Map<String,Object>)row;
        }).toList();
    }

    private final class Expansion {
        private final CompanyAccess actor;
        private final LocalDate day;
        private final boolean preview;
        private final List<Material> materials=new ArrayList<>();
        private final Map<Long,Long> unitIds=new HashMap<>();
        private final Map<Long,Map<String,Object>> children=new HashMap<>();
        private final Set<Long> leaves=new HashSet<>();
        private int traversed;

        private Expansion(CompanyAccess actor,LocalDate day,boolean preview) {
            this.actor=actor; this.day=day; this.preview=preview;
        }

        private void expand(Map<String,Object> bom,Quantity target,Long issue,List<String> path,boolean consume) {
            var base=decimal(bom,"base_qty").multiply(decimal(bom,"base_factor"));
            for(var line:boms.lines(actor,number(bom,"id"),!preview)) {
                if(++traversed>MAX_EXPANDED_LINES) throw invalid("展开物料数超出 2000 行，请拆分虚拟组件");
                long sku=number(line,"component_sku_id"), lineId=number(line,"id");
                var nextPath=nextPath(path,sku);
                var factor=decimal(line,"qty").multiply(decimal(line,"base_factor"))
                        .multiply(BigDecimal.ONE.add(decimal(line,"scrap_rate").movePointLeft(2)));
                // 虚拟件逐层保留精确比值，只有实际用料才按库存精度向上取整。
                var gross=target.scale(factor,base);
                Long warehouse=line.get("issue_warehouse_id")==null ? issue : number(line,"issue_warehouse_id");
                boolean phantom=Boolean.TRUE.equals(line.get("is_phantom"));
                if(preview || consume && !phantom) {
                    var qty=gross.round();
                    materials.add(new Material(lineId,sku,unit(sku),warehouse,qty,nextPath,alternatives(lineId),
                            line.get("sku_code").toString(),line.get("sku_name").toString(),phantom,consume&&!phantom));
                }
                if(!phantom && !preview) continue;
                var child=child(sku);
                if(child!=null) expand(child,gross,warehouse,nextPath,consume&&phantom);
                else if(phantom) throw invalid("虚拟组件缺少计划开工日有效且可访问的默认物料清单");
            }
        }

        private List<String> nextPath(List<String> path,long sku) {
            String value=Long.toString(sku);
            if(path.contains(value)) throw invalid("物料清单存在循环引用");
            if(path.size()>MAX_DEPTH) throw invalid("物料清单最多展开 10 层");
            var next=new ArrayList<>(path); next.add(value); return List.copyOf(next);
        }

        private Map<String,Object> child(long sku) {
            if(leaves.contains(sku)) return null;
            var child=children.computeIfAbsent(sku,s->boms.effectiveDefault(actor,s,day,!preview));
            if(child==null) leaves.add(sku);
            return child;
        }

        private long unit(long sku) {return unitIds.computeIfAbsent(sku,s->baseUnit(jdbc,actor,s,!preview));}

        private List<Alternative> alternatives(long line) {
            return boms.substitutes(actor,line,!preview).stream().map(row->{
                long sku=number(row,"substitute_sku_id");
                return new Alternative(sku,unit(sku),decimal(row,"base_ratio"),(int)number(row,"priority"));
            }).toList();
        }
    }

    static long baseUnit(JdbcTemplate jdbc,CompanyAccess actor,long sku,boolean lock) {
        return jdbc.queryForList("""
                SELECT unit_id FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND is_base=TRUE
                """+(lock?" FOR SHARE":""),Long.class,actor.tenantId(),sku).stream().findFirst()
                .orElseThrow(()->invalid("商品缺少基准单位"));
    }

    private record Definition(long id,long sku,LocalDate start,LocalDate end,boolean isDefault,List<Long> children) {}

    void validateGraph(CompanyAccess actor,long id) {
        var definitions=new LinkedHashMap<Long,Definition>();
        for(var edge:boms.graph(actor,id)) {
            var node=definitions.computeIfAbsent(number(edge,"id"),key->new Definition(key,number(edge,"sku_id"),
                    date(edge,"valid_from"),end(date(edge,"valid_to")),Boolean.TRUE.equals(edge.get("is_default")),new ArrayList<>()));
            node.children().add(number(edge,"component_sku_id"));
        }
        var root=definitions.get(id);
        if(root==null) throw invalid("物料清单至少需要一行子件");
        var defaults=new HashMap<Long,List<Definition>>();
        for(var node:definitions.values()) if(node.isDefault()) defaults.computeIfAbsent(node.sku(),ignored->new ArrayList<>()).add(node);
        var affected=new HashSet<Long>();
        affected.add(root.sku());
        if(root.isDefault()) {
            boolean changed;
            do {
                changed=false;
                for(var node:definitions.values()) if(node.children().stream().anyMatch(affected::contains)) changed|=affected.add(node.sku());
            } while(changed);
        }
        var budget=new int[]{MAX_GRAPH_VISITS};
        for(var node:definitions.values()) if(node.id()==id || root.isDefault() && affected.contains(node.sku()))
            visit(node,node.start(),node.end(),defaults,new HashSet<>(),0,budget);
    }

    private void visit(Definition root,LocalDate start,LocalDate end,Map<Long,List<Definition>> defaults,Set<Long> path,int depth,int[] budget) {
        path.add(root.sku());
        for(long child:root.children()) {
            if(--budget[0]<0) throw invalid("物料清单关系过多，请拆分层级后再保存");
            if(path.contains(child)) throw invalid("物料清单存在循环引用");
            if(depth>=MAX_DEPTH) throw invalid("物料清单最多展开 10 层");
            for(var definition:defaults.getOrDefault(child,List.of())) {
                var from=definition.start().isAfter(start) ? definition.start() : start;
                var to=definition.end().isBefore(end) ? definition.end() : end;
                if(!from.isAfter(to)) visit(definition,from,to,defaults,path,depth+1,budget);
            }
        }
        path.remove(root.sku());
    }
}
