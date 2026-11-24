package cn.erp.shared;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** 为既有事实补充退货模式及冻结成本来源，不改变流水类型、数量或金额。 */
public final class SalesReturnSourceMetadata {
    private static final int QUERY_BATCH_SIZE=1000;
    private final NamedParameterJdbcTemplate jdbc;

    public SalesReturnSourceMetadata(NamedParameterJdbcTemplate jdbc) {
        this.jdbc=jdbc;
    }

    /** 仅补充当前仓库和组织范围内的单据；成本来源仍受成本权限约束。 */
    public <A extends WarehouseScope & PermissionScope> void annotate(A actor,List<Map<String,Object>> rows) {
        boolean costs=actor.hasPermission("cost:view");
        var reversals=reversals(actor,rows);
        var returnIds=new HashSet<Long>();
        for(var row:rows) {
            row.remove("return_mode");row.remove("return_cost_source");
            Long id=returnId(row,reversals);
            if(id!=null)returnIds.add(id);
        }
        var heads=new HashMap<Long,Map<String,Object>>();
        String warehouse=actor.allWarehouses()?"":" AND h.warehouse_id IN (:warehouses)";
        for(var head:query(actor,returnIds,"SELECT h.id,h.return_no,h.return_mode FROM sales_return h WHERE h.tenant_id=:t AND h.company_id=:c AND h.id IN (:ids)"+warehouse+OrgOwnership.filter(actor,"h")))
            heads.put(number(head.get("id")),head);
        var lines=costs?costs(actor,rows,reversals,heads.keySet()):Map.<Long,Map<String,Object>>of();
        for(var row:rows) {
            Long id=returnId(row,reversals);
            var head=heads.get(id);
            if(head==null)continue;
            row.put("return_mode",head.get("return_mode"));
            row.putIfAbsent("source_no",head.get("return_no"));
            var line=lines.get(number(row.get("source_line_id")));
            if(line!=null&&number(line.get("return_id"))==id.longValue()&&line.get("cost_source")!=null)
                row.put("return_cost_source",line.get("cost_source"));
        }
    }

    private <A extends WarehouseScope & PermissionScope> Map<Long,Long> reversals(A actor,List<Map<String,Object>> rows) {
        var ids=new HashSet<Long>();
        for(var row:rows)if("document_reversal".equals(row.get("source_type")))ids.add(number(row.get("source_id")));
        var result=new HashMap<Long,Long>();
        for(var row:query(actor,ids,"SELECT id,source_id FROM document_reversal WHERE tenant_id=:t AND company_id=:c AND source_type='sales_return' AND id IN (:ids)"))
            result.put(number(row.get("id")),number(row.get("source_id")));
        return result;
    }

    private <A extends WarehouseScope & PermissionScope> Map<Long,Map<String,Object>> costs(A actor,List<Map<String,Object>> rows,
            Map<Long,Long> reversals,Set<Long> visibleReturns) {
        var ids=new HashSet<Long>();
        var reverseLineIds=new HashSet<Long>();
        for(var row:rows) {
            if(!visibleReturns.contains(returnId(row,reversals)))continue;
            long id=number(row.get("source_line_id"));
            if(id==0)continue;
            ids.add(id);
            if("document_reversal".equals(row.get("source_type")))reverseLineIds.add(id);
        }
        // 库存反向流水指向原流水，而销售报表反向行仍指向退货明细，两者都还原到原成本依据。
        var movements=query(actor,reverseLineIds,"SELECT id,source_id,source_line_id FROM inventory_movement WHERE tenant_id=:t AND company_id=:c AND source_type='sales_return_line' AND id IN (:ids)");
        for(var movement:movements)if(visibleReturns.contains(number(movement.get("source_id"))))ids.add(number(movement.get("source_line_id")));
        var result=new HashMap<Long,Map<String,Object>>();
        for(var line:query(actor,ids,"SELECT id,return_id,JSON_UNQUOTE(JSON_EXTRACT(cost_basis,'$.source')) cost_source FROM sales_return_line WHERE tenant_id=:t AND company_id=:c AND id IN (:ids)"))
            if(visibleReturns.contains(number(line.get("return_id"))))result.put(number(line.get("id")),line);
        for(var movement:movements) {
            var line=result.get(number(movement.get("source_line_id")));
            if(line!=null&&number(line.get("return_id"))==number(movement.get("source_id")))result.put(number(movement.get("id")),line);
        }
        return result;
    }

    private <A extends WarehouseScope & PermissionScope> List<Map<String,Object>> query(A actor,Set<Long> ids,String sql) {
        var result=new ArrayList<Map<String,Object>>();
        var all=new ArrayList<>(ids);
        // 大报表按批补充资料，避免五万行产生超过数据库占位符上限的一条查询。
        for(int offset=0;offset<all.size();offset+=QUERY_BATCH_SIZE) {
            var params=new MapSqlParameterSource().addValue("t",actor.tenantId()).addValue("c",actor.companyId())
                    .addValue("warehouses",actor.warehouseIds().isEmpty()?Set.of(0L):actor.warehouseIds())
                    .addValue("ids",all.subList(offset,Math.min(offset+QUERY_BATCH_SIZE,all.size())));
            result.addAll(jdbc.queryForList(sql,params));
        }
        return result;
    }

    private static Long returnId(Map<String,Object> row,Map<Long,Long> reversals) {
        String type=String.valueOf(row.get("source_type"));
        if(type.equals("sales_return")||type.equals("sales_return_line"))return number(row.get("source_id"));
        return type.equals("document_reversal")?reversals.get(number(row.get("source_id"))):null;
    }

    private static long number(Object value) {
        return value==null?0:value instanceof Number number?number.longValue():Long.parseLong(value.toString());
    }
}
