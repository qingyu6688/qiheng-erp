package cn.erp.shared;

import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/** 费用单引用多个收货仓，查看主仓授权不能代替全部来源与成本归属授权。 */
public final class PurchaseFeeScope {
    private PurchaseFeeScope() {}
    public static String condition(ActorScope actor,String alias) {
        if(!(actor instanceof PermissionScope permissions) || !permissions.hasPermission("cost:view")
                || !(actor instanceof WarehouseScope warehouses)) return "1=0";
        String sourceOrg=OrgOwnership.filter(actor,"fr");
        String visible="NOT EXISTS(SELECT 1 FROM purchase_fee_allocation fa JOIN goods_receipt_line fl"
                +" ON fl.tenant_id=fa.tenant_id AND fl.company_id=fa.company_id AND fl.id=fa.receipt_line_id"
                +" JOIN goods_receipt fr ON fr.tenant_id=fl.tenant_id AND fr.company_id=fl.company_id AND fr.id=fl.receipt_id"
                +" WHERE fa.tenant_id="+alias+".tenant_id AND fa.company_id="+alias+".company_id AND fa.document_id="+alias+".id AND NOT(1=1"+sourceOrg+"))";
        if(warehouses.allWarehouses()) return sourceOrg.isEmpty()?"1=1":visible;
        if(warehouses.warehouseIds().isEmpty()) return "1=0";
        String ids=warehouses.warehouseIds().stream().sorted().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
        return visible+" AND "+alias+".warehouse_id IN("+ids+")"
                +" AND NOT EXISTS(SELECT 1 FROM purchase_fee_allocation fa JOIN goods_receipt_line fl"
                +" ON fl.tenant_id=fa.tenant_id AND fl.company_id=fa.company_id AND fl.id=fa.receipt_line_id"
                +" JOIN goods_receipt fr ON fr.tenant_id=fl.tenant_id AND fr.company_id=fl.company_id AND fr.id=fl.receipt_id"
                +" WHERE fa.tenant_id="+alias+".tenant_id AND fa.company_id="+alias+".company_id AND fa.document_id="+alias+".id AND fr.warehouse_id NOT IN("+ids+"))"
                +" AND NOT EXISTS(SELECT 1 FROM purchase_fee_cost fc WHERE fc.tenant_id="+alias+".tenant_id AND fc.company_id="+alias+".company_id AND fc.document_id="+alias+".id AND fc.warehouse_id NOT IN("+ids+"))";
    }
    public static void require(JdbcTemplate jdbc,ActorScope actor,Map<String,Object> header) {
        OrgOwnership.require(actor,header);
        if(jdbc.queryForList("SELECT id FROM purchase_fee_doc fd WHERE fd.tenant_id=? AND fd.company_id=? AND fd.id=? AND "+condition(actor,"fd"),
                Long.class,actor.tenantId(),actor.companyId(),header.get("id")).isEmpty())
            throw new ApiException(404,"NOT_FOUND","费用单来源或成本归属不在当前可见范围");
    }
    public static String sourceCondition(ActorScope actor,String type,String id) {
        String visible=condition(actor,"fd_scope");
        if(visible.equals("1=1")) return "1=1";
        String scope="fd_scope.tenant_id="+actor.tenantId()+" AND fd_scope.company_id="+actor.companyId()+" AND "+visible;
        String reversal="rf_scope.tenant_id="+actor.tenantId()+" AND rf_scope.company_id="+actor.companyId()+" AND rf_scope.id="+id+" AND rf_scope.source_type='purchase_fee_doc'";
        return "(("+type+" NOT IN('purchase_fee_doc','purchase_fee_doc_line','purchase_fee_cost') OR EXISTS(SELECT 1 FROM purchase_fee_doc fd_scope WHERE "+scope+" AND fd_scope.id="+id+"))"
                +" AND ("+type+"<>'document_reversal' OR NOT EXISTS(SELECT 1 FROM document_reversal rf_scope WHERE "+reversal+")"
                +" OR EXISTS(SELECT 1 FROM document_reversal rf_scope JOIN purchase_fee_doc fd_scope ON fd_scope.tenant_id=rf_scope.tenant_id AND fd_scope.company_id=rf_scope.company_id AND fd_scope.id=rf_scope.source_id WHERE "+reversal+" AND "+scope+")))";
    }
}
