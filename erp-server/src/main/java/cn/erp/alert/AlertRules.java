package cn.erp.alert;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
class AlertRules {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    static final Set<String> LEVELS=Set.of("info","warning","critical");
    static final Set<String> SCHEDULES=Set.of("realtime","hourly","daily");
    static final Set<String> CHANNELS=Set.of("site","email","sms");
    private static final TypeReference<List<Receiver>> RECEIVERS=new TypeReference<>() {};
    private static final TypeReference<List<String>> METHODS=new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AccessService access;
    private final AuditService audit;

    AlertRules(JdbcTemplate jdbc,ObjectMapper json,AccessService access,AuditService audit) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.audit=audit;
    }

    record Receiver(String type,String id) {}
    record Change(Long version,Boolean enabled,String severity,String schedule,String threshold,List<Receiver> receivers,List<String> channels) {}
    record Rule(long id,AlertKind kind,boolean enabled,String severity,String schedule,BigDecimal threshold,List<Receiver> receivers,
                List<String> channels,long version,Instant nextRun) {
        Map<String,Object> toView() {
            var result=new LinkedHashMap<String,Object>();
            result.put("code",kind.code);result.put("name",kind.title);result.put("thresholdUnit",kind.unit);
            result.put("threshold",threshold==null?null:threshold.stripTrailingZeros().toPlainString());
            result.put("enabled",enabled);result.put("severity",severity);result.put("schedule",schedule);
            result.put("version",version);result.put("receivers",receivers);result.put("channels",channels);result.put("nextRunAt",nextRun);
            return result;
        }
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> list(CompanyAccess actor) {
        configure(actor);
        return list(actor.tenantId(),actor.companyId()).stream().map(Rule::toView).toList();
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> receiverOptions(CompanyAccess actor,String type,String query) {
        configure(actor);
        String keyword=query==null?"":query.strip();
        if (keyword.length()>80) throw invalid("接收人查询关键词最多 80 字");
        var rows=switch (type) {
            case "role" -> jdbc.queryForList("""
                    SELECT id,name FROM sys_role WHERE tenant_id=? AND status='active' AND LOCATE(?,name)>0
                    ORDER BY name,id LIMIT 50
                    """,actor.tenantId(),keyword);
            case "user" -> jdbc.queryForList("""
                    SELECT DISTINCT u.id,u.display_name name FROM sys_user u JOIN org_user_data_scope s ON s.tenant_id=u.tenant_id AND s.user_id=u.id
                    WHERE u.tenant_id=? AND s.company_id=? AND u.status='active' AND LOCATE(?,u.display_name)>0 ORDER BY name,u.id LIMIT 50
                    """,actor.tenantId(),actor.companyId(),keyword);
            default -> throw invalid("接收人查询类型无效");
        };
        return rows.stream().map(cn.erp.shared.ApiRows::row).toList();
    }

    List<Rule> list(long tenant,long company) {
        var existing=new LinkedHashMap<String,Rule>();
        read(tenant,company,null,false).forEach(row->existing.put(row.kind().code,row));
        var result=new ArrayList<Rule>();
        for (var kind : AlertKind.values()) result.add(existing.getOrDefault(kind.code,initial(kind)));
        return result;
    }

    @Transactional
    Map<String,Object> update(CompanyAccess actor,String code,Change change) {
        configure(actor);
        var kind=AlertKind.parse(code);
        var old=lock(actor.tenantId(),actor.companyId(),kind);
        version(change.version(),old.version());
        if (change.enabled()==null || change.severity()==null || !LEVELS.contains(change.severity())
                || change.schedule()==null || !SCHEDULES.contains(change.schedule())) throw invalid("请选择有效的预警级别及触发频率");
        var threshold=threshold(kind,change.threshold());
        var receivers=validateReceivers(actor,change.receivers());
        var channels=change.channels();
        if (channels==null || channels.isEmpty() || channels.size()>3 || channels.stream().anyMatch(channel->channel==null || !CHANNELS.contains(channel))
                || new HashSet<>(channels).size()!=channels.size()) throw invalid("请选择有效的通知渠道");
        jdbc.update("""
                UPDATE alert_rule SET severity=?,schedule=?,threshold=?,receivers=?,channels=?,enabled=?,next_run_at=UTC_TIMESTAMP(3),version=version+1
                WHERE tenant_id=? AND company_id=? AND id=?
                """,change.severity(),change.schedule(),threshold,json.writeValueAsString(receivers),json.writeValueAsString(channels),
                change.enabled(),actor.tenantId(),actor.companyId(),old.id());
        if (!change.enabled()) jdbc.update("""
                UPDATE alert_event SET status='resolved',handled_by=?,handled_at=UTC_TIMESTAMP(3),handle_reason='规则已停用',version=version+1
                WHERE tenant_id=? AND company_id=? AND rule_code=? AND status<>'resolved'
                """,actor.userId(),actor.tenantId(),actor.companyId(),code);
        var updated=read(actor.tenantId(),actor.companyId(),kind,false).getFirst().toView();
        audit.record(actor,"alert_rule.update","alert_rule",old.id(),old.toView(),updated);
        return updated;
    }

    Rule lock(long tenant,long company,AlertKind kind) {
        var stored=read(tenant,company,kind,false);
        if (!stored.isEmpty()) return read(tenant,company,kind,true).getFirst();
        var rule=initial(kind);
        jdbc.update("""
                INSERT INTO alert_rule(id,tenant_id,company_id,code,threshold,severity,schedule,receivers,channels,enabled)
                VALUES(?,?,?,?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE id=id
                """,Ids.next(),tenant,company,kind.code,rule.threshold(),rule.severity(),rule.schedule(),
                json.writeValueAsString(rule.receivers()),json.writeValueAsString(rule.channels()));
        return read(tenant,company,kind,true).getFirst();
    }

    Rule current(long tenant,long company,AlertKind kind) {
        return read(tenant,company,kind,false).stream().findFirst().orElseGet(()->initial(kind));
    }

    private List<Rule> read(long tenant,long company,AlertKind kind,boolean lock) {
        String sql="SELECT * FROM alert_rule WHERE tenant_id=? AND company_id=?"+(kind==null?"":" AND code=?")+(lock?" FOR UPDATE":"");
        Object[] args=kind==null?new Object[]{tenant,company}:new Object[]{tenant,company,kind.code};
        return jdbc.query(sql,(r,n)->{
            var next=r.getTimestamp("next_run_at");
            return new Rule(r.getLong("id"),AlertKind.parse(r.getString("code")),r.getBoolean("enabled"),r.getString("severity"),r.getString("schedule"),
                    r.getBigDecimal("threshold"),json.readValue(r.getString("receivers"),RECEIVERS),json.readValue(r.getString("channels"),METHODS),
                    r.getLong("version"),next==null?null:next.toInstant());
        },args);
    }

    static Instant nextRun(String schedule,Instant now) {
        return switch (schedule) {
            case "daily" -> {
                ZonedDateTime next=now.atZone(ZONE).withHour(8).withMinute(0).withSecond(0).withNano(0);
                if (!next.toInstant().isAfter(now)) next=next.plusDays(1);
                yield next.toInstant();
            }
            case "hourly" -> now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusSeconds(3600);
            default -> now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusSeconds(60);
        };
    }

    private Rule initial(AlertKind kind) {
        return new Rule(0,kind,true,kind.severity,kind.schedule,kind.threshold,kind.defaultReceivers(),List.of("site"),0,null);
    }

    private List<Receiver> validateReceivers(CompanyAccess actor,List<Receiver> values) {
        if (values==null || values.isEmpty() || values.size()>30 || new HashSet<>(values).size()!=values.size()) throw invalid("请选择有效的预警接收人");
        var defaults=new HashSet<Receiver>();
        for(var kind:AlertKind.values()) defaults.addAll(kind.defaultReceivers());
        defaults.add(new Receiver("department-leader",null));
        for (var row : values) {
            if (row==null || row.type()==null) throw invalid("预警接收人无效");
            if (defaults.contains(row)) continue;
            long id=positive(row.id());
            if (row.type().equals("role") && jdbc.queryForList("SELECT id FROM sys_role WHERE tenant_id=? AND id=? AND status='active'",
                    Long.class,actor.tenantId(),id).size()==1) continue;
            if (row.type().equals("user") && access.forUser(actor.tenantId(),id,actor.companyId())!=null) continue;
            throw missing();
        }
        return List.copyOf(values);
    }

    static BigDecimal threshold(AlertKind kind,String raw) {
        if (kind.unit==null) {
            if (raw!=null && !raw.isBlank()) throw invalid("该规则没有全局阈值，请在商品或来源设置维护");
            return null;
        }
        if (raw==null || raw.isBlank()) return kind.threshold;
        if (!raw.matches("[0-9]{1,15}(?:\\.[0-9]{1,6})?")) throw invalid("阈值须为非负数字");
        var amount=new BigDecimal(raw);
        if (Set.of("天","小时","次").contains(kind.unit) && (amount.stripTrailingZeros().scale()>0 || amount.compareTo(BigDecimal.valueOf(36500))>0))
            throw invalid("时间和次数阈值须为不超过 36500 的整数");
        if ("%".equals(kind.unit) && amount.compareTo(BigDecimal.valueOf(10000))>0) throw invalid("百分比阈值超出允许范围");
        return amount;
    }

    static void configure(CompanyAccess actor) {
        if (!actor.hasPermission("alert:view") || !actor.hasPermission("alert:configure") || !actor.allWarehouses() || !actor.orgScope().allDepartments())
            throw new ApiException(403,"FORBIDDEN","预警规则需要对应权限及整家公司数据范围");
    }

    static long positive(String value) {
        try {
            if (value!=null && value.matches("[1-9][0-9]{0,18}")) return Long.parseLong(value);
        } catch (NumberFormatException error) {throw invalid("编号超出允许范围");}
        throw invalid("编号无效");
    }

    static void version(Long actual,long expected) {
        if (actual==null || actual<0) throw invalid("请提供有效的版本号");
        if (actual!=expected) throw new ApiException(409,"VERSION_CONFLICT","预警记录已更新，请刷新后重试");
    }

    static ApiException invalid(String message) {return new ApiException(422,"ALERT_INVALID",message);}
    static ApiException missing() {return new ApiException(404,"NOT_FOUND","预警不存在或无权查看");}
}
