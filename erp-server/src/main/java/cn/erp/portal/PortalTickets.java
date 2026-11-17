package cn.erp.portal;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.PortalTicketNotice;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PortalTickets {
    private static final Set<String> CATEGORIES=Set.of("order","logistics","invoice","quality","complaint","refund","other");
    private static final Set<String> STATES=Set.of("open","assigned","processing","waiting_customer","resolved","closed");
    private static final String FIELDS="""
            SELECT t.id,t.no,t.category,t.priority,t.title,t.status,t.assignee_id,t.first_reply_due_at,t.sla_due_at,
              t.first_reply_at,t.resolved_at,t.satisfaction,t.version,t.created_at,
              t.status NOT IN ('resolved','closed') AND
                ((t.first_reply_at IS NULL AND t.first_reply_due_at<=UTC_TIMESTAMP(3)) OR t.sla_due_at<=UTC_TIMESTAMP(3)) overdue
            FROM cs_ticket t WHERE t.tenant_id=? AND t.company_id=?
            """;
    private static final String CUSTOMER_SCOPE=" AND t.partner_id=? AND t.portal_user_id=?";
    private final JdbcTemplate jdbc;
    private final AccessService access;
    private final ApplicationEventPublisher events;

    PortalTickets(JdbcTemplate jdbc,AccessService access,ApplicationEventPublisher events) {
        this.jdbc=jdbc;
        this.access=access;
        this.events=events;
    }

    public record Ticket(String id,String no,String category,String priority,String title,String status,String assigneeId,
                         String firstReplyDueAt,String slaDueAt,String firstReplyAt,String resolvedAt,Integer satisfaction,
                         int version,String createdAt,boolean overdue) {}
    public record Reply(String id,String authorType,String content,boolean internal,String createdAt) {}
    public record Detail(Ticket ticket,List<Reply> replies) {}
    record NewTicket(String category,String title,String content) {}
    record NewReply(String content,Boolean internal,Boolean waitingCustomer,Integer version) {}
    record Assign(Long assigneeId,Integer version) {}
    record Revision(Integer version) {}
    record Rating(Integer satisfaction) {}

    List<Ticket> customerList(PortalActor actor,Long cursor,int limit) {
        return jdbc.query(FIELDS+CUSTOMER_SCOPE+" AND t.id<? ORDER BY t.id DESC LIMIT ?",this::ticket,
                actor.tenantId(),actor.companyId(),actor.partnerId(),actor.userId(),PortalValues.cursor(cursor,limit),limit+1)
                .stream().map(this::publicView).toList();
    }

    Detail customerDetail(PortalActor actor,long id) {return detail(customer(actor,id,false),actor.tenantId(),actor.companyId(),false);}

    @Transactional
    public Detail create(PortalActor actor,NewTicket input) {return create(actor,input,null);}

    /** 仅在门户已认证的上下文内转人工，同一对话并发请求复用同一工单。 */
    @Transactional
    public Detail handoff(PortalActor actor,String conversationId,String category,String title,String message) {
        if(conversationId==null || !conversationId.matches("[A-Za-z0-9_-]{1,64}")) throw PortalValues.invalid("客服对话编号无效");
        return create(actor,new NewTicket(category,title,message),conversationId);
    }

    private Detail create(PortalActor actor,NewTicket input,String conversationId) {
        String category=PortalValues.text(input.category(),16,"工单分类");
        if(!CATEGORIES.contains(category)) throw PortalValues.invalid("请选择有效的工单分类");
        String title=PortalValues.text(input.title(),120,"工单标题"),content=PortalValues.text(input.content(),4_000,"问题内容");
        boolean priority=Set.of("complaint","refund").contains(category);
        var owner=owner(actor);
        Long assignee=owner==null?null:owner.userId();
        Long department=owner==null?null:owner.orgScope().primaryDepartmentId();
        long id=Ids.next();
        try {
            jdbc.update("""
                    INSERT INTO cs_ticket(id,tenant_id,company_id,no,partner_id,portal_user_id,channel,category,priority,title,status,
                      assignee_id,owner_user_id,owner_department_id,ai_conversation_id,first_reply_due_at,sla_due_at)
                    VALUES(?,?,?,?,?,?,'portal',?,?,?,?,?,?,?,?,TIMESTAMPADD(HOUR,?,UTC_TIMESTAMP(3)),TIMESTAMPADD(HOUR,?,UTC_TIMESTAMP(3)))
                    """,id,actor.tenantId(),actor.companyId(),"CS-"+id,actor.partnerId(),actor.userId(),category,priority?"high":"normal",
                    title,assignee==null?"open":"assigned",assignee,assignee,department,conversationId,priority?2:8,priority?24:72);
        } catch(DuplicateKeyException conflict) {
            if(conversationId==null) throw conflict;
            var existing=jdbc.query(FIELDS+CUSTOMER_SCOPE+" AND t.ai_conversation_id=? FOR UPDATE",this::ticket,
                    actor.tenantId(),actor.companyId(),actor.partnerId(),actor.userId(),conversationId);
            if(existing.isEmpty()) throw conflict;
            return detail(existing.getFirst(),actor.tenantId(),actor.companyId(),false);
        }
        insertReply(actor.tenantId(),actor.companyId(),id,"customer",actor.userId(),content,false);
        if(assignee!=null) events.publishEvent(new PortalTicketNotice(actor.tenantId(),actor.companyId(),assignee,id,"assigned"));
        return customerDetail(actor,id);
    }

    @Transactional
    public Detail customerReply(PortalActor actor,long id,NewReply input) {
        var ticket=customer(actor,id,true);
        if(Boolean.TRUE.equals(input.internal()) || Boolean.TRUE.equals(input.waitingCustomer())) throw PortalValues.invalid("客户回复不支持内部标记");
        requireOpen(ticket);
        insertReply(actor.tenantId(),actor.companyId(),id,"customer",actor.userId(),PortalValues.text(input.content(),4_000,"回复"),false);
        jdbc.update("""
                UPDATE cs_ticket SET status=IF(status='waiting_customer','processing',status),version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,actor.tenantId(),actor.companyId(),id);
        return customerDetail(actor,id);
    }

    @Transactional
    public Detail rate(PortalActor actor,long id,Rating input) {
        var ticket=customer(actor,id,true);
        if(!"closed".equals(ticket.status()) || ticket.satisfaction()!=null || input.satisfaction()==null
                || input.satisfaction()<1 || input.satisfaction()>5) throw PortalValues.invalid("仅可为已关闭工单提交一次 1 到 5 分评价");
        jdbc.update("UPDATE cs_ticket SET satisfaction=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                input.satisfaction(),actor.tenantId(),actor.companyId(),id);
        return customerDetail(actor,id);
    }

    List<Ticket> staffList(CompanyAccess actor,Long cursor,int limit,String status) {
        if(status!=null && !STATES.contains(status)) throw PortalValues.invalid("工单状态无效");
        var values=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),PortalValues.cursor(cursor,limit)));
        if(status!=null) values.add(status);
        values.add(limit+1);
        return jdbc.query(FIELDS+" AND t.id<?"+staffScope(actor)+(status==null?"":" AND t.status=?")+" ORDER BY t.id DESC LIMIT ?",this::ticket,values.toArray());
    }

    Detail staffDetail(CompanyAccess actor,long id) {return detail(staff(actor,id,false),actor.tenantId(),actor.companyId(),true);}

    record StaffContext(Ticket ticket,long partnerId,String customerName) {}

    StaffContext staffContext(CompanyAccess actor,long id) {
        var ticket=staff(actor,id,false);
        return jdbc.query("""
                SELECT t.partner_id,p.name FROM cs_ticket t JOIN business_partner p ON p.tenant_id=t.tenant_id AND p.id=t.partner_id
                WHERE t.tenant_id=? AND t.company_id=? AND t.id=?
                """,(row,n)->new StaffContext(ticket,row.getLong("partner_id"),row.getString("name")),actor.tenantId(),actor.companyId(),id)
                .stream().findFirst().orElseThrow(()->PortalValues.missing("工单"));
    }

    @Transactional
    public Detail assign(CompanyAccess actor,long id,Assign input) {
        var current=staff(actor,id,true);
        requireVersion(current,input.version());
        requireOpen(current);
        long assignee=PortalValues.id(input.assigneeId());
        var target=access.forUser(actor.tenantId(),assignee,actor.companyId());
        if(target==null || !target.hasPermission("cs_ticket:view") || !target.hasPermission("cs_ticket:reply")
                || !actor.orgScope().allows(target.orgScope().primaryDepartmentId(),assignee))
            throw PortalValues.missing("可分配坐席");
        jdbc.update("""
                UPDATE cs_ticket SET assignee_id=?,owner_user_id=?,owner_department_id=?,status=IF(status='open','assigned',status),
                  version=version+1,first_sla_notified_at=NULL,resolution_sla_notified_at=NULL WHERE tenant_id=? AND company_id=? AND id=?
                """,assignee,assignee,target.orgScope().primaryDepartmentId(),actor.tenantId(),actor.companyId(),id);
        events.publishEvent(new PortalTicketNotice(actor.tenantId(),actor.companyId(),assignee,id,"assigned"));
        return staffDetail(actor,id);
    }

    @Transactional
    public Detail staffReply(CompanyAccess actor,long id,NewReply input) {
        var current=staff(actor,id,true);
        requireVersion(current,input.version());
        requireOpen(current);
        boolean internal=Boolean.TRUE.equals(input.internal());
        if(internal && Boolean.TRUE.equals(input.waitingCustomer())) throw PortalValues.invalid("内部备注不能等待客户回复");
        insertReply(actor.tenantId(),actor.companyId(),id,"agent",actor.userId(),PortalValues.text(input.content(),4_000,"回复"),internal);
        String target=Boolean.TRUE.equals(input.waitingCustomer())?"waiting_customer":"processing";
        jdbc.update("""
                UPDATE cs_ticket SET status=IF(?,status,?),first_reply_at=IF(?,first_reply_at,COALESCE(first_reply_at,UTC_TIMESTAMP(3))),
                  version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """,internal,target,internal,actor.tenantId(),actor.companyId(),id);
        return staffDetail(actor,id);
    }

    @Transactional
    public Detail finish(CompanyAccess actor,long id,String action,Revision input) {
        if(!"resolve".equals(action) && !"close".equals(action)) throw PortalValues.invalid("工单操作无效");
        var current=staff(actor,id,true);
        requireVersion(current,input.version());
        if("resolve".equals(action)) {
            requireOpen(current);
            jdbc.update("UPDATE cs_ticket SET status='resolved',resolved_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),actor.companyId(),id);
        } else {
            if(!"resolved".equals(current.status())) throw PortalValues.invalid("请先解决工单再关闭");
            jdbc.update("UPDATE cs_ticket SET status='closed',closed_at=UTC_TIMESTAMP(3),version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    actor.tenantId(),actor.companyId(),id);
        }
        return staffDetail(actor,id);
    }

    private CompanyAccess owner(PortalActor actor) {
        var owners=jdbc.queryForList("SELECT owner_user_id FROM crm_customer_profile WHERE tenant_id=? AND company_id=? AND partner_id=? AND owner_user_id>0",
                Long.class,actor.tenantId(),actor.companyId(),actor.partnerId());
        if(owners.isEmpty()) return null;
        var owner=access.forUser(actor.tenantId(),owners.getFirst(),actor.companyId());
        return owner!=null && owner.hasPermission("cs_ticket:view") && owner.hasPermission("cs_ticket:reply")?owner:null;
    }

    private Ticket customer(PortalActor actor,long id,boolean lock) {
        return jdbc.query(FIELDS+CUSTOMER_SCOPE+" AND t.id=?"+(lock?" FOR UPDATE":""),this::ticket,
                actor.tenantId(),actor.companyId(),actor.partnerId(),actor.userId(),id).stream().findFirst()
                .orElseThrow(()->PortalValues.missing("工单"));
    }

    private Ticket staff(CompanyAccess actor,long id,boolean lock) {
        return jdbc.query(FIELDS+" AND t.id=?"+staffScope(actor)+(lock?" FOR UPDATE":""),this::ticket,
                actor.tenantId(),actor.companyId(),id).stream().findFirst().orElseThrow(()->PortalValues.missing("工单"));
    }

    private String staffScope(CompanyAccess actor) {
        if(actor.orgScope().allDepartments()) return "";
        return " AND (t.assignee_id="+actor.userId()+" OR ("+actor.orgScope().ownerFilter("t")+")"
                +(actor.hasPermission("cs_ticket:assign")?" OR (t.assignee_id IS NULL AND t.owner_user_id IS NULL)":"")+")";
    }

    private Ticket publicView(Ticket ticket) {
        return new Ticket(ticket.id(),ticket.no(),ticket.category(),ticket.priority(),ticket.title(),ticket.status(),null,
                ticket.firstReplyDueAt(),ticket.slaDueAt(),ticket.firstReplyAt(),ticket.resolvedAt(),ticket.satisfaction(),ticket.version(),ticket.createdAt(),ticket.overdue());
    }

    private Detail detail(Ticket ticket,long tenant,long company,boolean internal) {
        String visibility=internal?"":" AND internal=FALSE";
        var replies=jdbc.query("""
                SELECT id,author_type,content,internal,created_at FROM cs_ticket_reply WHERE tenant_id=? AND company_id=? AND ticket_id=?
                """+visibility+" ORDER BY id DESC LIMIT 200",(row,n)->new Reply(row.getString("id"),row.getString("author_type"),row.getString("content"),
                row.getBoolean("internal"),row.getString("created_at")),tenant,company,Long.parseLong(ticket.id()));
        return new Detail(internal?ticket:publicView(ticket),replies.reversed());
    }

    private void insertReply(long tenant,long company,long ticket,String type,long actor,String content,boolean internal) {
        jdbc.update("INSERT INTO cs_ticket_reply(id,tenant_id,company_id,ticket_id,author_type,author_id,content,internal) VALUES(?,?,?,?,?,?,?,?)",
                Ids.next(),tenant,company,ticket,type,actor,content,internal);
    }

    private void requireVersion(Ticket ticket,Integer version) {
        if(version==null || version!=ticket.version()) throw new ApiException(409,"VERSION_CONFLICT","工单已变化，请刷新后重试");
    }

    private void requireOpen(Ticket current) {
        if(Set.of("resolved","closed").contains(current.status())) throw new ApiException(409,"STATE_CONFLICT","工单已解决或关闭");
    }

    private Ticket ticket(java.sql.ResultSet row,int count) throws java.sql.SQLException {
        return new Ticket(row.getString("id"),row.getString("no"),row.getString("category"),row.getString("priority"),row.getString("title"),
                row.getString("status"),row.getString("assignee_id"),row.getString("first_reply_due_at"),row.getString("sla_due_at"),row.getString("first_reply_at"),
                row.getString("resolved_at"),row.getObject("satisfaction",Integer.class),row.getInt("version"),row.getString("created_at"),row.getBoolean("overdue"));
    }
}
