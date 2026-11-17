package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.jobs.OutboxService;
import cn.erp.shared.*;
import cn.erp.workbench.TodoQueries;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static cn.erp.notify.NoticeService.*;

@Service
@Transactional
public class MessageService implements TodoInbox {
    private cn.erp.workflow.WorkflowQueries workflows;
    @org.springframework.beans.factory.annotation.Autowired
    void workflows(cn.erp.workflow.WorkflowQueries workflows) {this.workflows=workflows;}
    @org.springframework.context.event.EventListener
    public void workflow(WorkflowNotice event) {
        outbox.publish(event.tenantId(),"workflow_notice",Ids.next(),0,"notify.workflow",Map.of("companyId",event.companyId(),"userId",event.userId(),"instanceId",event.instanceId(),"taskId",event.taskId()==null?0:event.taskId(),"kind",event.kind(),"eventKey",event.eventKey()));
        events.publishEvent(new UserSignal(event.tenantId(),event.userId(),"message.changed",Map.of("category","workflow")));
    }
    public void materialize(WorkflowNotice event) {
        var actor=new CompanyAccess(event.tenantId(),event.userId(),event.companyId(),true,Set.of(),Set.of());
        jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,event.tenantId(),event.userId());
        if(event.kind().equals("copy_read")) {jdbc.update("UPDATE notify_message SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE tenant_id=? AND user_id=? AND source_type='wf_copy' AND source_id=?",event.tenantId(),event.userId(),event.instanceId());events.publishEvent(new UserSignal(event.tenantId(),event.userId(),"message.read",Map.of()));return;}
        String title=switch(event.kind()) {case "todo"->"新的审批待办";case "remind"->"审批待办超时提醒";case "copy"->"收到流程抄送";default->"申请流程已结束";};
        String category=event.kind().equals("todo")?"todo":event.kind().equals("remind")?"alert":"system";
        String source=event.taskId()!=null?"wf_task_ext":event.kind().equals("copy")?"wf_copy":"wf_instance";long sourceId=event.taskId()!=null?event.taskId():event.kind().equals("copy")?Long.parseLong(event.eventKey().substring("wf:copy:".length())):event.instanceId();
        long version=0;if(event.taskId()!=null) {var rows=jdbc.queryForList("SELECT version FROM wf_task_ext WHERE tenant_id=? AND company_id=? AND id=? AND assignee_id=? AND status='pending'",Long.class,event.tenantId(),event.companyId(),event.taskId(),event.userId());if(rows.isEmpty())return;version=rows.getFirst();}
        String key=event.kind().equals("todo")?"wf:task:"+event.taskId()+":v"+version:event.eventKey();
        create(actor,"workflow."+event.kind(),title,"请进入审批中心查看，来源权限会重新校验。","/workflow/instances/"+event.instanceId(),category,source,sourceId,null,null,key,version);
    }
    private final JdbcTemplate jdbc;private final NamedParameterJdbcTemplate named;private final TodoQueries todos;private final NotificationAccess visibility;private final OutboxService outbox;private final ApplicationEventPublisher events;private final AccessService access;private final TemplateService templates;
    private final TransactionTemplate refreshTransaction;
    public MessageService(JdbcTemplate jdbc,NamedParameterJdbcTemplate named,TodoQueries todos,NotificationAccess visibility,OutboxService outbox,ApplicationEventPublisher events,AccessService access,TemplateService templates,PlatformTransactionManager transactions) {this.jdbc=jdbc;this.named=named;this.todos=todos;this.visibility=visibility;this.outbox=outbox;this.events=events;this.access=access;this.templates=templates;refreshTransaction=new TransactionTemplate(transactions);refreshTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
    @org.springframework.context.event.EventListener
    public void collection(cn.erp.shared.CollectionNotice event) {
        var actor=access.forUser(event.tenantId(),event.userId(),event.companyId());if(actor==null||!actor.allWarehouses()||!actor.hasPermission("collection:view"))return;
        var plans=jdbc.queryForList("SELECT open_item_id FROM ar_collection_plan WHERE tenant_id=? AND company_id=? AND id=? AND owner_id=? AND status='open'",event.tenantId(),event.companyId(),event.planId(),event.userId());if(plans.isEmpty())return;
        OrgOwnership.requireItem(jdbc,actor,plans.getFirst().get("open_item_id"));create(actor,"collection.remind","催收计划到期提醒","请进入催收计划核对未清余额并登记跟进。","/ar/collections?planId="+event.planId(),"alert","ar_collection_plan",event.planId(),null,null,event.eventKey(),0);
    }
    public void sync(CompanyAccess actor) {
        if(actor.companyId()==0) return;
        // 铃铛、工作台和收件箱会同时补齐消息，先锁收件人，避免空范围更新与去重插入互相死锁。
        jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,actor.tenantId(),actor.userId());
        var existing=new HashSet<>(jdbc.queryForList("SELECT event_key FROM notify_message WHERE tenant_id=? AND company_id=? AND user_id=?",String.class,actor.tenantId(),actor.companyId(),actor.userId()));
        for(var task:jdbc.queryForList("SELECT t.id,t.instance_id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE t.tenant_id=? AND t.company_id=? AND t.assignee_id=? AND t.status='pending' AND i.status='running'",actor.tenantId(),actor.companyId(),actor.userId())) {
            String key="wf:task:"+task.get("id")+":v"+task.get("version");if(!existing.contains(key)) create(actor,"workflow.todo","新的审批待办","请进入审批中心查看并处理。","/workflow/instances/"+task.get("instance_id"),"todo","wf_task_ext",((Number)task.get("id")).longValue(),null,null,key,((Number)task.get("version")).longValue());
        }
        for(var copy:jdbc.queryForList("SELECT id,instance_id,read_at FROM wf_copy WHERE tenant_id=? AND company_id=? AND user_id=?",actor.tenantId(),actor.companyId(),actor.userId())) {String key="wf:copy:"+copy.get("id");if(!existing.contains(key)) {create(actor,"workflow.copy","收到流程抄送","请到审批中心查看。","/workflow/instances/"+copy.get("instance_id"),"system","wf_copy",((Number)copy.get("id")).longValue(),null,null,key,0);if(copy.get("read_at")!=null) jdbc.update("UPDATE notify_message SET read_at=? WHERE tenant_id=? AND company_id=? AND user_id=? AND event_key=?",copy.get("read_at"),actor.tenantId(),actor.companyId(),actor.userId(),key);}}
        for(long instance:jdbc.queryForList("SELECT id FROM wf_instance WHERE tenant_id=? AND company_id=? AND starter_id=? AND status<>'running' AND ended_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 180 DAY)",Long.class,actor.tenantId(),actor.companyId(),actor.userId())) {String key="wf:finish:"+instance;if(!existing.contains(key)) create(actor,"workflow.done","申请流程已结束","请到我的申请查看处理结果。","/workflow/instances/"+instance,"system","wf_instance",instance,null,null,key,0);}
        for(var query:todos.eligible(actor)) for(var candidate:named.queryForList("SELECT h.id,h.version FROM "+query.type()+" h WHERE "+query.where(),query.params())) {
            long id=((Number)candidate.get("id")).longValue(),version=((Number)candidate.get("version")).longValue();
            String key="todo:"+query.type()+":"+id+":"+query.task().action()+":v"+version;
            if(!existing.contains(key)) create(actor,"business.todo",query.task().label(),"请进入单据查看并处理。","/"+query.task().kind()+"/"+id,"todo",query.type(),id,query.task().action(),null,key,version);
            else jdbc.update("UPDATE notify_message SET state='open' WHERE tenant_id=? AND company_id=? AND user_id=? AND event_key=? AND state='resolved'",actor.tenantId(),actor.companyId(),actor.userId(),key);
        }
        var params=Map.<String,Object>of("tenant",actor.tenantId(),"company",actor.companyId(),"user",actor.userId());
        for(var notice:named.queryForList("SELECT n.id,n.title FROM sys_notice n WHERE "+NoticeService.visible("n"),params)) {
            long id=((Number)notice.get("id")).longValue();String key="notice:"+id;if(!existing.contains(key)) create(actor,"notice.published",notice.get("title").toString(),"有新的公告，请查看并确认。","/messages?notice="+id,"notice",null,null,null,id,key,0);
        }
        for(String type:List.of("import","export")) {
            String statuses=type.equals("import")?"'succeeded','partial','failed'":"'succeeded','failed'";
            for(long id:jdbc.queryForList("SELECT id FROM "+type+"_job WHERE tenant_id=? AND company_id=? AND created_by=? AND status IN("+statuses+") AND finished_at>DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 7 DAY)",Long.class,actor.tenantId(),actor.companyId(),actor.userId())) {
                String key=type+":"+id;if(!existing.contains(key)) create(actor,"file."+type+".finished",type.equals("import")?"导入任务已完成":"导出任务已完成","请到文件任务查看处理结果，下载时会重新校验权限。","/file-jobs","system",type+"_job",id,null,null,key,0);
            }
        }
        var visible=visibility.visible(actor);
        named.update("UPDATE notify_message m SET m.state='resolved' WHERE m.tenant_id=:tenant AND m.company_id=:company AND m.user_id=:user AND m.category='todo' AND m.state='open' AND NOT ("+visible.where()+")",visible.params());
    }
    private void create(CompanyAccess actor,String template,String title,String content,String link,String category,String source,Long sourceId,String action,Long notice,String key,long sourceVersion) {
        var rendered=templates.render(actor.tenantId(),template,"site",Map.of("title",title,"message",content));
        if(rendered!=null) {title=rendered.title();content=rendered.content();}
        long id=Ids.next();int inserted=jdbc.update("INSERT IGNORE INTO notify_message(id,tenant_id,company_id,user_id,template_code,title,content,link,category,source_type,source_id,source_action,notice_id,event_key,source_version) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,actor.tenantId(),actor.companyId(),actor.userId(),template,title,content,link,category,source,sourceId,action,notice,key,sourceVersion);
        if(inserted==1) {outbox.publish(actor.tenantId(),"notify_message",id,0,"notify.created",Map.of("companyId",actor.companyId(),"userId",actor.userId()));events.publishEvent(new UserSignal(actor.tenantId(),actor.userId(),"message.changed",Map.of("messageId",Long.toString(id),"category",category)));}
    }
    public Map<String,Object> list(CompanyAccess actor,String category,Boolean unread,Long cursor,int limit) {
        long before=before(cursor,limit);if(!Set.of("","todo","notice","alert","system").contains(category)) throw invalid("消息类型无效");sync(actor);
        var filter=visibility.visible(actor);var params=new HashMap<>(filter.params());params.put("before",before);params.put("limit",limit+1);String where=filter.where()+" AND m.id<:before";
        if(!category.isEmpty()) {where+=" AND m.category=:category";params.put("category",category);}if(unread!=null) where+=unread?" AND m.read_at IS NULL":" AND m.read_at IS NOT NULL";
        return page(named.queryForList("SELECT m.id,m.title,m.content,m.link,m.category,m.source_type,m.source_id,m.read_at,m.created_at FROM notify_message m WHERE "+where+" ORDER BY m.id DESC LIMIT :limit",params),limit);
    }
    public Map<String,Object> counts(CompanyAccess actor) {sync(actor);var filter=visibility.visible(actor);var result=new LinkedHashMap<String,Object>();long total=0;for(var row:named.queryForList("SELECT m.category,COUNT(*) amount FROM notify_message m WHERE "+filter.where()+" AND m.read_at IS NULL GROUP BY m.category",filter.params())) {long count=((Number)row.get("amount")).longValue();result.put(row.get("category").toString(),count);total+=count;}result.put("total",total);return result;}
    public Map<String,Object> read(CompanyAccess actor,long id) {
        var filter=visibility.visible(actor);var params=new HashMap<>(filter.params());params.put("id",id);
        var rows=named.queryForList("SELECT m.id,m.notice_id,m.source_type,m.source_id FROM notify_message m WHERE "+filter.where()+" AND m.id=:id",params);if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","消息不存在或无权查看");
        jdbc.update("UPDATE notify_message SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE tenant_id=? AND user_id=? AND id=?",actor.tenantId(),actor.userId(),id);
        if(rows.getFirst().get("notice_id")!=null) jdbc.update("INSERT IGNORE INTO sys_notice_read(tenant_id,notice_id,user_id) VALUES(?,?,?)",actor.tenantId(),rows.getFirst().get("notice_id"),actor.userId());
        if("wf_copy".equals(rows.getFirst().get("source_type"))) jdbc.update("UPDATE wf_copy SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE tenant_id=? AND company_id=? AND user_id=? AND id=?",actor.tenantId(),actor.companyId(),actor.userId(),rows.getFirst().get("source_id"));
        events.publishEvent(new UserSignal(actor.tenantId(),actor.userId(),"message.read",Map.of("messageId",Long.toString(id))));return Map.of("id",Long.toString(id),"read",true);
    }
    public Map<String,Object> notice(CompanyAccess actor,long id) {
        var params=new HashMap<String,Object>(Map.of("tenant",actor.tenantId(),"company",actor.companyId(),"user",actor.userId(),"id",id));
        var rows=named.queryForList("SELECT n.*,EXISTS(SELECT 1 FROM sys_notice_read nr WHERE nr.tenant_id=n.tenant_id AND nr.notice_id=n.id AND nr.user_id=:user) confirmed FROM sys_notice n WHERE "+NoticeService.visible("n")+" AND n.id=:id",params);if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","公告不存在或无权查看");return ApiRows.row(rows.getFirst());
    }
    public List<Map<String,Object>> important(CompanyAccess actor) {
        return named.queryForList("SELECT n.id,n.title FROM sys_notice n WHERE "+NoticeService.visible("n")+" AND n.level='important' AND NOT EXISTS(SELECT 1 FROM sys_notice_read nr WHERE nr.tenant_id=n.tenant_id AND nr.notice_id=n.id AND nr.user_id=:user) ORDER BY n.id",Map.of("tenant",actor.tenantId(),"company",actor.companyId(),"user",actor.userId())).stream().map(ApiRows::row).toList();
    }
    public Map<String,Object> confirmNotice(CompanyAccess actor,long id) {
        notice(actor,id);jdbc.update("INSERT IGNORE INTO sys_notice_read(tenant_id,notice_id,user_id) VALUES(?,?,?)",actor.tenantId(),id,actor.userId());
        jdbc.update("UPDATE notify_message SET read_at=COALESCE(read_at,UTC_TIMESTAMP(3)) WHERE tenant_id=? AND user_id=? AND notice_id=?",actor.tenantId(),actor.userId(),id);
        events.publishEvent(new UserSignal(actor.tenantId(),actor.userId(),"message.read",Map.of("messageId",Long.toString(id))));return Map.of("id",Long.toString(id),"confirmed",true);
    }
    @Override public Map<String,Object> summary(ActorScope scope) {
        if(!(scope instanceof CompanyAccess actor)) throw invalid("工作台身份无效");sync(actor);var blocks=new ArrayList<Map<String,Object>>();var items=new ArrayList<Map<String,Object>>();
        for(var query:todos.eligible(actor)) {
            var params=new HashMap<>(query.params());String where="m.tenant_id=:tenant AND m.company_id=:company AND m.user_id=:user AND m.category='todo' AND m.state='open' AND m.source_type='"+query.type()+"' AND m.source_action='"+query.task().action()+"' AND EXISTS(SELECT 1 FROM "+query.type()+" h WHERE "+query.where()+" AND h.id=m.source_id AND h.version=m.source_version)";
            long count=named.queryForObject("SELECT COUNT(*) FROM notify_message m WHERE "+where,params,Long.class);String url="/"+query.task().kind()+"?status="+query.task().state()+(query.task().kind().equals("shipments")?"&signOverdue=true":"");blocks.add(Map.of("key",query.task().kind(),"label",query.task().label(),"count",count,"url",url));
            for(var row:named.queryForList("SELECT m.id,m.source_id FROM notify_message m WHERE "+where+" ORDER BY m.id DESC LIMIT 10",params)) {
                var detail=todos.detail(actor,query,((Number)row.get("source_id")).longValue());if(detail!=null) {detail.put("messageId",row.get("id").toString());items.add(detail);}
            }
        }
        var flow=workflows.workbench(actor);if(((Number)flow.get("count")).longValue()>0) {blocks.add(Map.of("key","workflow","label","流程审批待办","count",flow.get("count"),"url","/workflow/todo"));if(flow.get("items") instanceof List<?> rows) for(Object row:rows) if(row instanceof Map<?,?> values) {var todo=new LinkedHashMap<String,Object>();values.forEach((key,value)->todo.put(key.toString(),value));items.addFirst(todo);}}
        return Map.of("blocks",blocks,"todos",items.stream().limit(10).toList());
    }
    /** 后台每次只处理一个企业，授权变化在实际派发及读取时再次检查。 */
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    public void refreshTenant(long tenant) {
        for(var row:jdbc.queryForList("SELECT DISTINCT user_id,company_id FROM sys_user_data_scope WHERE tenant_id=? ORDER BY user_id,company_id",tenant)) {
            refreshTransaction.executeWithoutResult(status->{var actor=access.forUser(tenant,((Number)row.get("user_id")).longValue(),((Number)row.get("company_id")).longValue());if(actor!=null) sync(actor);});
        }
    }
}
