package cn.erp.notify;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NoticeService {
    public record Target(@NotBlank(message="请选择公告对象类型") @Pattern(regexp="all|company|department|role|user",message="公告对象类型无效") String type,@PositiveOrZero(message="公告对象标识无效") long id) {}
    public record Input(@NotBlank(message="请输入公告标题") @Size(max=200,message="标题最多200字") String title,@NotBlank(message="请输入公告内容") @Size(max=50000,message="公告最多50000字") String content,@NotBlank(message="请选择公告级别") @Pattern(regexp="normal|important",message="公告级别无效") String level,Instant publishAt,Instant expireAt,@NotEmpty(message="请选择公告对象") @Size(max=100,message="公告对象最多100项") List<@NotNull(message="公告对象不能为空") @Valid Target> targets,@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version) {}
    private final JdbcTemplate jdbc;private final BusinessCommands commands;private final AuditService audit;private final ApplicationEventPublisher events;
    public NoticeService(JdbcTemplate jdbc,BusinessCommands commands,AuditService audit,ApplicationEventPublisher events) {this.jdbc=jdbc;this.commands=commands;this.audit=audit;this.events=events;}
    /** 只匹配当前公司及实时成员资格；撤回、到期或撤销范围后不再展示旧正文。 */
    public static String visible(String alias) {
        return alias+".tenant_id=:tenant AND "+alias+".status='published' AND "+alias+".publish_at<=UTC_TIMESTAMP(3) AND ("+alias+".expire_at IS NULL OR "+alias+".expire_at>UTC_TIMESTAMP(3)) AND EXISTS(SELECT 1 FROM sys_notice_target nt WHERE nt.tenant_id="+alias+".tenant_id AND nt.notice_id="+alias+".id AND (nt.target_type='all' OR nt.target_type='user' AND nt.target_id=:user OR nt.target_type='company' AND nt.target_id=:company OR nt.target_type='role' AND EXISTS(SELECT 1 FROM sys_user_role ur JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id AND r.status='active' WHERE ur.tenant_id=:tenant AND ur.user_id=:user AND ur.role_id=nt.target_id) OR nt.target_type='department' AND EXISTS(SELECT 1 FROM org_user_department m JOIN org_department d ON d.tenant_id=m.tenant_id AND d.id=m.department_id AND d.company_id=m.company_id AND d.status='active' WHERE m.tenant_id=:tenant AND m.company_id=:company AND m.user_id=:user AND m.department_id=nt.target_id)))";
    }
    public Map<String,Object> list(CompanyAccess actor,Long cursor,int limit) {PlatformAccess.require(jdbc,actor,"notice:view");return page(jdbc.queryForList("SELECT id,title,level,status,publish_at,expire_at,version,created_by,created_at FROM sys_notice WHERE tenant_id=? AND id<? ORDER BY id DESC LIMIT ?",actor.tenantId(),before(cursor,limit),limit+1),limit);}
    public Map<String,Object> get(CompanyAccess actor,long id) {PlatformAccess.require(jdbc,actor,"notice:view");var row=head(actor,id,false);var result=ApiRows.row(row);result.put("targets",jdbc.queryForList("SELECT target_type type,CAST(target_id AS CHAR) id FROM sys_notice_target WHERE tenant_id=? AND notice_id=? ORDER BY target_type,target_id",actor.tenantId(),id));return result;}
    @Transactional public Map<String,Object> save(CompanyAccess actor,Long id,String key,Input input) {
        PlatformAccess.lock(jdbc,actor,id==null?"notice:create":"notice:update");validateTargets(actor,input.targets());
        if(input.expireAt()!=null&&input.publishAt()!=null&&!input.expireAt().isAfter(input.publishAt())) throw invalid("到期时间应晚于发布时间");
        if(input.title().chars().anyMatch(Character::isISOControl)) throw invalid("公告标题不能含换行或控制字符");
        String html=Jsoup.clean(input.content(),Safelist.basic());if(Jsoup.parse(html).text().isBlank()) throw invalid("公告正文不能为空");
        return commands.execute(actor,"notice.save",key,Arrays.asList(id,input),200,()->{
            long current=id==null?Ids.next():id;Map<String,Object> previous=null;
            if(id!=null) {previous=head(actor,id,true);version(input.version(),previous.get("version"));if(!previous.get("status").equals("draft")) throw conflict("只有草稿公告可以编辑，请撤回后复制为新公告");}
            else if(input.version()!=0) throw invalid("新公告版本号应为0");
            if(id==null) jdbc.update("INSERT INTO sys_notice(id,tenant_id,title,content,level,publish_at,expire_at,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?)",current,actor.tenantId(),input.title(),html,input.level(),time(input.publishAt()),time(input.expireAt()),actor.userId(),actor.userId());
            else jdbc.update("UPDATE sys_notice SET title=?,content=?,level=?,publish_at=?,expire_at=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",input.title(),html,input.level(),time(input.publishAt()),time(input.expireAt()),actor.userId(),actor.tenantId(),current);
            jdbc.update("DELETE FROM sys_notice_target WHERE tenant_id=? AND notice_id=?",actor.tenantId(),current);
            for(var target:new LinkedHashSet<>(input.targets())) jdbc.update("INSERT INTO sys_notice_target(tenant_id,notice_id,target_type,target_id) VALUES(?,?,?,?)",actor.tenantId(),current,target.type(),target.id());
            var result=ApiRows.row(head(actor,current,false));audit.record(actor,"notice.save","sys_notice",current,previous==null?null:Map.of("version",previous.get("version")),Map.of("version",result.get("version")));return result;
        },Map.class);
    }
    @Transactional public Map<String,Object> change(CompanyAccess actor,long id,String action,long expected,String key) {
        PlatformAccess.lock(jdbc,actor,"notice:publish");if(!Set.of("publish","withdraw").contains(action)) throw invalid("公告操作无效");
        return commands.execute(actor,"notice."+action,key,Map.of("id",id,"version",expected),200,()->{
            var row=head(actor,id,true);version(expected,row.get("version"));String target=action.equals("publish")?"published":"withdrawn";
            if(action.equals("publish")&&!row.get("status").equals("draft")||action.equals("withdraw")&&!row.get("status").equals("published")) throw conflict("公告当前状态不允许此操作");
            if(action.equals("publish")&&row.get("expire_at") instanceof java.time.LocalDateTime expiry&&expiry.toInstant(java.time.ZoneOffset.UTC).isBefore(Instant.now())) throw conflict("已到期的公告不能发布");
            jdbc.update("UPDATE sys_notice SET status=?,publish_at=COALESCE(publish_at,UTC_TIMESTAMP(3)),version=version+1,updated_by=? WHERE tenant_id=? AND id=?",target,actor.userId(),actor.tenantId(),id);audit.record(actor,"notice."+action,"sys_notice",id,Map.of("status",row.get("status")),Map.of("status",target));events.publishEvent(new UserSignal(actor.tenantId(),0,"notice.changed",Map.of("messageId",Long.toString(id))));return ApiRows.row(head(actor,id,false));
        },Map.class);
    }
    private void validateTargets(CompanyAccess actor,List<Target> targets) {
        for(var target:targets) {
            boolean exists=switch(target.type()) {
                case "all"->target.id()==0&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT NOT EXISTS(SELECT 1 FROM company c WHERE c.tenant_id=? AND c.status='active' AND NOT EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=c.tenant_id AND s.company_id=c.id AND s.user_id=?))",Boolean.class,actor.tenantId(),actor.userId()));
                case "company"->memberCompany(actor,target.id());
                case "department"->!jdbc.queryForList("SELECT d.id FROM org_department d WHERE d.tenant_id=? AND d.id=? AND d.status='active' AND EXISTS(SELECT 1 FROM org_user_data_scope s WHERE s.tenant_id=d.tenant_id AND s.company_id=d.company_id AND s.user_id=?)",Long.class,actor.tenantId(),target.id(),actor.userId()).isEmpty();
                case "user"->!jdbc.queryForList("SELECT u.id FROM sys_user u WHERE u.tenant_id=? AND u.id=? AND u.status='active' AND EXISTS(SELECT 1 FROM org_user_data_scope a JOIN org_user_data_scope b ON b.tenant_id=a.tenant_id AND b.company_id=a.company_id WHERE a.tenant_id=u.tenant_id AND a.user_id=? AND b.user_id=u.id)",Long.class,actor.tenantId(),target.id(),actor.userId()).isEmpty();
                case "role"->Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM sys_role WHERE tenant_id=? AND id=? AND status='active') AND NOT EXISTS(SELECT 1 FROM sys_user_role ur JOIN org_user_data_scope s ON s.tenant_id=ur.tenant_id AND s.user_id=ur.user_id WHERE ur.tenant_id=? AND ur.role_id=? AND NOT EXISTS(SELECT 1 FROM org_user_data_scope a WHERE a.tenant_id=s.tenant_id AND a.company_id=s.company_id AND a.user_id=?))",Boolean.class,actor.tenantId(),target.id(),actor.tenantId(),target.id(),actor.userId()));
                default->false;
            };if(!exists) throw new ApiException(404,"NOT_FOUND","公告对象不存在或超出授权范围");
        }
    }
    private boolean memberCompany(CompanyAccess actor,long id) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM company c JOIN org_user_data_scope s ON s.tenant_id=c.tenant_id AND s.company_id=c.id WHERE c.tenant_id=? AND c.id=? AND c.status='active' AND s.user_id=?)",Boolean.class,actor.tenantId(),id,actor.userId()));}
    private Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {var rows=jdbc.queryForList("SELECT * FROM sys_notice WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),id);if(rows.isEmpty()) throw new ApiException(404,"NOT_FOUND","公告不存在或无权查看");return rows.getFirst();}
    static long before(Long cursor,int limit) {if(limit<1||limit>200||cursor!=null&&cursor<=0) throw invalid("分页条件无效");return cursor==null?Long.MAX_VALUE:cursor;}
    static Map<String,Object> page(List<Map<String,Object>> rows,int limit) {var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;}
    static void version(long expected,Object current) {if(expected!=((Number)current).longValue()) throw new ApiException(409,"VERSION_CONFLICT","资料已被修改，请刷新后重试");}
    static ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
    static ApiException conflict(String message) {return new ApiException(422,"STATE_CONFLICT",message);}
    private static java.sql.Timestamp time(Instant instant) {return instant==null?null:java.sql.Timestamp.from(instant);}
}
