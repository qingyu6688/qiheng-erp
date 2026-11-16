package cn.erp.notify;

import cn.erp.identity.CompanyAccess;
import cn.erp.workbench.TodoQueries;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class NotificationAccess {
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
        var imports=actor.permissions().stream().filter(p->p.startsWith("import:")).map(p->p.substring(7)).toList();
        if(!imports.isEmpty()) {params.put("importTypes",imports);sources.add("(m.source_type='import_job' AND EXISTS(SELECT 1 FROM import_job j WHERE j.tenant_id=:tenant AND j.company_id=:company AND j.id=m.source_id AND j.created_by=:user AND j.job_type IN(:importTypes)))");}
        var exports=actor.permissions().stream().filter(p->p.startsWith("export:")&&actor.hasPermission("report:"+p.substring(7))).map(p->p.substring(7)).toList();
        if(!exports.isEmpty()) {params.put("exportTypes",exports);sources.add("(m.source_type='export_job' AND EXISTS(SELECT 1 FROM export_job j WHERE j.tenant_id=:tenant AND j.company_id=:company AND j.id=m.source_id AND j.created_by=:user AND j.export_type IN(:exportTypes)))");}
        sources.add("(m.category IN('system','alert') AND m.source_type IS NULL AND m.notice_id IS NULL)");
        return new Filter("m.tenant_id=:tenant AND m.company_id IN(0,:company) AND m.user_id=:user AND m.state='open' AND ("+String.join(" OR ",sources)+")",params);
    }
}
