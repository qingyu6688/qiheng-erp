package cn.erp.alert;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlertEvents {
    private static final Set<String> STATES=Set.of("open","acknowledged","resolved","ignored");
    private static final String FIELDS="e.id,e.rule_code,e.target_type,e.target_id,e.warehouse_id,e.level,e.title,e.detail,e.target_url,"
            +"e.status,e.first_seen_at,e.last_seen_at,e.occurrence,e.handled_by,e.handled_at,e.handle_reason,e.version";
    private final JdbcTemplate jdbc;
    private final AlertVisibility visibility;
    private final BusinessCommands commands;
    private final AuditService audit;

    AlertEvents(JdbcTemplate jdbc,AlertVisibility visibility,BusinessCommands commands,AuditService audit) {
        this.jdbc=jdbc;this.visibility=visibility;this.commands=commands;this.audit=audit;
    }

    record Handle(Long version,String reason) {}

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,String rule,String level,String status,Long cursor,int limit) {
        if (cursor!=null && cursor<=0 || limit<1 || limit>100) throw AlertRules.invalid("分页参数无效");
        if (rule!=null) AlertKind.parse(rule);
        if (level!=null && !AlertRules.LEVELS.contains(level) || status!=null && !STATES.contains(status))
            throw AlertRules.invalid("筛选条件无效");
        var query=new StringBuilder("SELECT "+FIELDS+" FROM alert_event e WHERE "+visibility.eventCondition(actor,"e"));
        var args=new ArrayList<>();
        filter(query,args,"rule_code",rule);filter(query,args,"level",level);filter(query,args,"status",status);
        if (cursor!=null) {query.append(" AND e.id<?");args.add(cursor);}
        query.append(" ORDER BY e.id DESC LIMIT ?");args.add(limit+1);
        var records=jdbc.queryForList(query.toString(),args.toArray());
        var rows=records.stream().limit(limit).map(ApiRows::row).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows);result.put("nextCursor",records.size()>limit?rows.getLast().get("id"):null);
        return result;
    }

    @Transactional(readOnly=true)
    long unhandled(CompanyAccess actor) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM alert_event e WHERE e.status='open' AND "+visibility.eventCondition(actor,"e"),Long.class);
    }

    @Transactional(readOnly=true)
    Map<String,Object> detail(CompanyAccess actor,long id) {return ApiRows.row(find(actor,id,false));}

    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    Map<String,Object> handle(CompanyAccess actor,long id,String action,String key,Handle body) {
        if (!actor.hasPermission("alert:handle") || !actor.hasPermission("alert:view"))
            throw new ApiException(403,"FORBIDDEN","没有处理预警的权限");
        String state=switch (action) {
            case "acknowledge" -> "acknowledged";
            case "resolve" -> "resolved";
            case "ignore" -> "ignored";
            default -> throw AlertRules.invalid("预警处理动作无效");
        };
        String reason=body.reason()==null?"":body.reason().strip();
        if (reason.length()>300 || state.equals("ignored") && reason.isEmpty())
            throw AlertRules.invalid("忽略预警须填写原因，最多 300 字");
        find(actor,id,false);
        return commands.execute(actor,"alert."+action,key,List.of(id,body),200,()->{
            var old=find(actor,id,true);
            AlertRules.version(body.version(),((Number)old.get("version")).longValue());
            String before=old.get("status").toString();
            if (!before.equals("open") && !(before.equals("acknowledged") && !state.equals("acknowledged")))
                throw new ApiException(409,"STATE_CONFLICT","该预警无法执行当前操作");
            jdbc.update("""
                    UPDATE alert_event SET status=?,handled_by=?,handled_at=UTC_TIMESTAMP(3),handle_reason=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,state,actor.userId(),reason.isEmpty()?null:reason,actor.tenantId(),actor.companyId(),id);
            audit.record(actor,"alert."+action,"alert_event",id,Map.of("status",before),Map.of("status",state,"reason",reason));
            return detail(actor,id);
        },(Class)Map.class);
    }

    boolean visible(CompanyAccess actor,long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM alert_event e WHERE e.id=? AND "+visibility.eventCondition(actor,"e")+")",
                Boolean.class,id));
    }

    private Map<String,Object> find(CompanyAccess actor,long id,boolean lock) {
        var sql="SELECT "+FIELDS+" FROM alert_event e WHERE e.id=? AND "+visibility.eventCondition(actor,"e")+(lock?" FOR UPDATE OF e":"");
        return jdbc.queryForList(sql,id).stream().findFirst().orElseThrow(AlertRules::missing);
    }

    private static void filter(StringBuilder sql,List<Object> args,String field,String value) {
        if (value!=null) {sql.append(" AND e.").append(field).append("=?");args.add(value);}
    }

    static String fingerprint(AlertKind kind,String key) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((kind.code+":"+key).getBytes(StandardCharsets.UTF_8)));}
        catch (NoSuchAlgorithmException failure) {throw new IllegalStateException("缺少预警去重摘要算法",failure);}
    }
}
