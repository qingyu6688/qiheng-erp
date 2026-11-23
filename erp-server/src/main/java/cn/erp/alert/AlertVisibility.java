package cn.erp.alert;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.AlertVisibilityPort;
import cn.erp.shared.OrgOwnership;
import cn.erp.workflow.WorkflowAccess;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class AlertVisibility implements AlertVisibilityPort {
    private final WorkflowAccess workflows;

    AlertVisibility(WorkflowAccess workflows) {this.workflows=workflows;}

    @Override public String eventCondition(ActorScope actor,String a) {
        if (!a.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException("预警查询别名无效");
        if (!(actor instanceof CompanyAccess scope) || !scope.hasPermission("alert:view")) return "1=0";
        var kinds=new ArrayList<String>();
        add(kinds,scope,"inventory:view",a,"stock_balance",warehouse(scope,a));
        add(kinds,scope,"batch:view",a,"inv_batch",warehouse(scope,a));
        add(kinds,scope,"qc_inspection:view",a,"qc_inspection",warehouse(scope,a)+OrgOwnership.filter(scope,a));
        add(kinds,scope,"purchase_order:view",a,"purchase_order_line",warehouse(scope,a)+OrgOwnership.filter(scope,a)+purchaseScope(scope,a));
        if (scope.hasPermission("cost:view")) add(kinds,scope,"shipment:view",a,"shipment_line",warehouse(scope,a)+OrgOwnership.filter(scope,a));
        if (scope.allWarehouses()) addCompanyKinds(kinds,scope,a);
        kinds.add("("+a+".target_type='wf_task_ext' AND EXISTS(SELECT 1 FROM wf_task_ext wt JOIN wf_instance wi ON"
                +" wi.tenant_id=wt.tenant_id AND wi.company_id=wt.company_id AND wi.id=wt.instance_id WHERE wt.id="+a+".target_id AND "
                +workflows.task(scope,"wt","wi")+"))");
        return "("+a+".tenant_id="+scope.tenantId()+" AND "+a+".company_id="+scope.companyId()+" AND ("+String.join(" OR ",kinds)+"))";
    }

    private static void addCompanyKinds(List<String> kinds,CompanyAccess actor,String a) {
        for(String side:List.of("receivable","payable")) {
            add(kinds,actor,"report:"+side+"_aging",a,"partner_open_item"," AND EXISTS(SELECT 1 FROM partner_open_item oi WHERE"
                    +" oi.tenant_id="+a+".tenant_id AND oi.company_id="+a+".company_id AND oi.id="+a+".target_id AND oi.item_type='"+side+"' AND "
                    +OrgOwnership.itemCondition(actor,"oi")+")");
        }
        add(kinds,actor,"collection:view",a,"ar_collection_plan"," AND "+a+".owner_user_id="+actor.userId()
                +" AND EXISTS(SELECT 1 FROM ar_collection_plan p JOIN partner_open_item oi ON oi.tenant_id=p.tenant_id AND oi.company_id=p.company_id AND oi.id=p.open_item_id"
                +" WHERE p.tenant_id="+a+".tenant_id AND p.company_id="+a+".company_id AND p.id="+a+".target_id AND "+OrgOwnership.itemCondition(actor,"oi")+")");
        if (!actor.orgScope().allDepartments()) return;
        add(kinds,actor,"payment:view",a,"payment"," ");
        add(kinds,actor,"reversal:view",a,"reversal_user"," ");
        if (actor.hasPermission("cost:view")) add(kinds,actor,"credit_limit:view",a,"ar_credit_limit"," ");
        add(kinds,actor,"job:view",a,"scheduled_job"," ");
        if (actor.permissions().stream().anyMatch(p->p.startsWith("import:"))) kinds.add(fileJob(actor,a,"import_job","job_type","import:"));
        if (actor.permissions().stream().anyMatch(p->p.startsWith("export:"))) kinds.add(fileJob(actor,a,"export_job","export_type","export:"));
    }

    private static String fileJob(CompanyAccess actor,String a,String table,String column,String permission) {
        var types=actor.permissions().stream().filter(p->p.startsWith(permission))
                .map(p->p.substring(permission.length())).filter(p->p.matches("[a-zA-Z0-9_-]{1,48}"))
                .filter(p->permission.equals("import:") || actor.hasPermission("report:"+p)).sorted().toList();
        if (types.isEmpty()) return "1=0";
        return "("+a+".target_type='"+table+"' AND "+a+".owner_user_id="+actor.userId()+" AND EXISTS(SELECT 1 FROM "+table
                +" f WHERE f.tenant_id="+a+".tenant_id AND f.company_id="+a+".company_id AND f.id="+a+".target_id AND f."+column+" IN('"
                +String.join("','",types)+"')))";
    }

    private static void add(List<String> kinds,CompanyAccess scope,String permission,String alias,String type,String filter) {
        if (scope.hasPermission(permission)) kinds.add("("+alias+".target_type='"+type+"'"+filter+")");
    }

    private static String warehouse(CompanyAccess actor,String alias) {
        if (actor.allWarehouses()) return "";
        if (actor.warehouseIds().isEmpty()) return " AND 1=0";
        var ids=actor.warehouseIds().stream().sorted().map(String::valueOf).toList();
        return " AND "+alias+".warehouse_id IN("+String.join(",",ids)+")";
    }

    private static String purchaseScope(CompanyAccess actor,String a) {
        if (actor.allWarehouses()) return "";
        if (actor.warehouseIds().isEmpty()) return " AND 1=0";
        var ids=actor.warehouseIds().stream().sorted().map(String::valueOf).toList();
        return " AND EXISTS(SELECT 1 FROM purchase_order_line selected WHERE selected.tenant_id="+a+".tenant_id AND selected.company_id="+a+".company_id"
                +" AND selected.id="+a+".target_id AND NOT EXISTS(SELECT 1 FROM purchase_order_line l WHERE l.tenant_id=selected.tenant_id"
                +" AND l.company_id=selected.company_id AND l.order_id=selected.order_id AND l.is_deleted=0"
                +" AND (l.warehouse_id IS NULL OR l.warehouse_id NOT IN("+String.join(",",ids)+"))))";
    }
}
