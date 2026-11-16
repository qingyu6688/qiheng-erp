package cn.erp.notify;

import cn.erp.identity.CompanyAccess;
import cn.erp.workbench.TodoQueries;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotificationAccess {
    private cn.erp.workflow.WorkflowAccess workflows;
    @org.springframework.beans.factory.annotation.Autowired
    void workflows(cn.erp.workflow.WorkflowAccess workflows) {this.workflows=workflows;}
    private final TodoQueries todos;
    public NotificationAccess(TodoQueries todos) {this.todos=todos;}
    public record Filter(String where,Map<String,Object> params) {}
    public Filter visible(CompanyAccess actor) {
        var params=new HashMap<String,Object>();params.put("tenant",actor.tenantId());params.put("company",actor.companyId());params.put("user",actor.userId());
        var sources=new ArrayList<String>();
        for(var query:todos.eligible(actor)) {
            params.putAll(query.params());String where=query.where().replace(":state","'"+query.task().state()+"'");
            sources.add("(m.category='todo' AND m.source_type='"+query.type()+"' AND m.source_action='"+query.task().action()+"' AND EXISTS(SELECT 1 FROM "+query.type()+" h WHERE "+where+" AND h.id=m.source_id AND h.version=m.source_version))");
        }
        sources.add("(m.category='notice' AND EXISTS(SELECT 1 FROM sys_notice n WHERE n.id=m.notice_id AND "+NoticeService.visible("n")+"))");
        sources.add("(m.source_type='wf_task_ext' AND EXISTS(SELECT 1 FROM wf_task_ext wt JOIN wf_instance wi ON wi.tenant_id=wt.tenant_id AND wi.id=wt.instance_id WHERE wt.id=m.source_id AND wt.version=m.source_version AND "+workflows.task(actor,"wt","wi")+"))");
        sources.add("(m.source_type='wf_copy' AND EXISTS(SELECT 1 FROM wf_copy wc JOIN wf_instance wi ON wi.tenant_id=wc.tenant_id AND wi.id=wc.instance_id WHERE wc.tenant_id=:tenant AND wc.company_id=:company AND wc.user_id=:user AND wc.id=m.source_id AND "+workflows.business(actor,"wi",false)+"))");
        sources.add("(m.source_type='wf_instance' AND EXISTS(SELECT 1 FROM wf_instance wi WHERE wi.tenant_id=:tenant AND wi.company_id=:company AND wi.starter_id=:user AND wi.id=m.source_id AND "+workflows.business(actor,"wi",false)+"))");
        var imports=actor.permissions().stream().filter(p->p.startsWith("import:")).map(p->p.substring(7)).toList();
        if(!imports.isEmpty()) {params.put("importTypes",imports);sources.add("(m.source_type='import_job' AND EXISTS(SELECT 1 FROM import_job j WHERE j.tenant_id=:tenant AND j.company_id=:company AND j.id=m.source_id AND j.created_by=:user AND j.job_type IN(:importTypes)))");}
        var exports=actor.permissions().stream().filter(p->p.startsWith("export:")&&actor.hasPermission("report:"+p.substring(7))).map(p->p.substring(7)).toList();
        if(!exports.isEmpty()) {params.put("exportTypes",exports);sources.add("(m.source_type='export_job' AND EXISTS(SELECT 1 FROM export_job j WHERE j.tenant_id=:tenant AND j.company_id=:company AND j.id=m.source_id AND j.created_by=:user AND j.export_type IN(:exportTypes)))");}
        sources.add("(m.category IN('system','alert') AND m.source_type IS NULL AND m.notice_id IS NULL)");
        return new Filter("m.tenant_id=:tenant AND m.company_id IN(0,:company) AND m.user_id=:user AND m.state='open' AND ("+String.join(" OR ",sources)+")",params);
    }
}
