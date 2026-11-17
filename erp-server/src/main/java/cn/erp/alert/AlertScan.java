package cn.erp.alert;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.AlertNotificationPort;
import cn.erp.shared.Ids;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlertScan {
    private final JdbcTemplate jdbc;
    private final AlertRules rules;
    private final AlertProbes probes;
    private final AccessService access;
    private final AlertEvents events;
    private final AlertNotificationPort messages;

    AlertScan(JdbcTemplate jdbc,AlertRules rules,AlertProbes probes,AccessService access,AlertEvents events,AlertNotificationPort messages) {
        this.jdbc=jdbc;this.rules=rules;this.probes=probes;this.access=access;this.events=events;this.messages=messages;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    void scan(long tenant,long company,AlertKind kind,Instant now,boolean realtime) {
        var current=rules.current(tenant,company,kind);
        if (!due(current,now,realtime) || realtime && current.id()==0L && probes.detect(tenant,company,current,now).isEmpty()) return;
        var rule=rules.lock(tenant,company,kind);
        if (!due(rule,now,realtime)) return;
        long marker=Ids.next();
        var cache=new RecipientCache(tenant,company);
        var candidates=probes.detect(tenant,company,rule,now);
        for (var candidate:candidates) assess(tenant,company,rule,candidate,marker,now,cache);
        jdbc.update("""
                UPDATE alert_event SET status='resolved',handled_by=NULL,handled_at=?,handle_reason='触发条件已解除',version=version+1
                WHERE tenant_id=? AND company_id=? AND rule_code=? AND scan_id<>? AND status<>'resolved'
                """,Timestamp.from(now),tenant,company,kind.code,marker);
        var next=AlertRules.nextRun(rule.schedule(),now);
        jdbc.update("UPDATE alert_rule SET next_run_at=? WHERE tenant_id=? AND company_id=? AND id=?",Timestamp.from(next),tenant,company,rule.id());
    }

    private static boolean due(AlertRules.Rule rule,Instant now,boolean realtime) {
        return rule.enabled() && (!realtime || rule.schedule().equals("realtime")) && (rule.nextRun()==null || !rule.nextRun().isAfter(now));
    }

    private void assess(long tenant,long company,AlertRules.Rule rule,AlertCandidate candidate,long marker,Instant now,RecipientCache cache) {
        String fingerprint=AlertEvents.fingerprint(rule.kind(),candidate.key());
        var previous=jdbc.queryForList("""
                SELECT id,status,occurrence FROM alert_event WHERE tenant_id=? AND company_id=? AND fingerprint=? FOR UPDATE
                """,tenant,company,fingerprint);
        if (previous.isEmpty()) {
            long id=Ids.next();
            jdbc.update("""
                    INSERT INTO alert_event(id,tenant_id,company_id,rule_code,target_type,target_id,identity_key,warehouse_id,owner_department_id,
                        owner_user_id,level,title,detail,target_url,fingerprint,first_seen_at,last_seen_at,scan_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,tenant,company,rule.kind().code,candidate.type(),candidate.id(),candidate.key(),candidate.warehouse(),candidate.department(),
                    candidate.owner(),rule.severity(),rule.kind().title,detail(candidate.detail()),candidate.targetUrl(),fingerprint,Timestamp.from(now),Timestamp.from(now),marker);
            notify(rule,candidate,id,1,cache);
            return;
        }
        var row=previous.getFirst();
        long id=((Number)row.get("id")).longValue();
        boolean reopen=row.get("status").equals("resolved");
        boolean notify=reopen || row.get("status").equals("open");
        int occurrence=((Number)row.get("occurrence")).intValue()+(reopen?1:0);
        String update=reopen?",status='open',handled_by=NULL,handled_at=NULL,handle_reason=NULL,version=version+1":"";
        jdbc.update("""
                UPDATE alert_event SET target_id=?,warehouse_id=?,owner_department_id=?,owner_user_id=?,level=?,detail=?,target_url=?,
                  last_seen_at=?,scan_id=?,occurrence=?
                """+update+" WHERE tenant_id=? AND company_id=? AND id=?",candidate.id(),candidate.warehouse(),candidate.department(),candidate.owner(),
                rule.severity(),detail(candidate.detail()),candidate.targetUrl(),Timestamp.from(now),marker,occurrence,tenant,company,id);
        if (notify) notify(rule,candidate,id,occurrence,cache);
    }

    private void notify(AlertRules.Rule rule,AlertCandidate candidate,long id,int occurrence,RecipientCache cache) {
        var users=new LinkedHashSet<Long>();
        for (var recipient:rule.receivers()) users.addAll(cache.resolve(recipient,candidate));
        var channels=new LinkedHashSet<>(rule.channels());
        if (rule.severity().equals("critical")) channels.add("sms");
        for (long user:users) {
            var actor=cache.access(user);
            if (actor!=null && events.visible(actor,id)) messages.create(actor,id,occurrence,Set.copyOf(channels));
        }
    }

    private static String detail(String detail) {
        if (detail==null) return "请核对预警来源资料。";
        int count=detail.codePointCount(0,detail.length());
        return count<=400?detail:detail.substring(0,detail.offsetByCodePoints(0,400));
    }

    private final class RecipientCache {
        private final long tenant;
        private final long company;
        private final Map<String,List<Long>> groups=new HashMap<>();
        private final Map<Long,Optional<CompanyAccess>> profiles=new HashMap<>();

        RecipientCache(long tenant,long company) {this.tenant=tenant;this.company=company;}

        CompanyAccess access(long user) {
            return profiles.computeIfAbsent(user,key->Optional.ofNullable(access.forUser(tenant,key,company))).orElse(null);
        }

        List<Long> resolve(AlertRules.Receiver target,AlertCandidate record) {
            return switch (target.type()) {
                case "owner" -> record.owner()>0?List.of(record.owner()):List.of();
                case "user" -> List.of(AlertRules.positive(target.id()));
                case "department-leader" -> record.department()<=0?List.of():groups.computeIfAbsent("d:"+record.department(),key->jdbc.queryForList("""
                        SELECT leader_user_id FROM org_department WHERE tenant_id=? AND company_id=? AND id=? AND status='active' AND leader_user_id IS NOT NULL
                        """,Long.class,tenant,company,record.department()));
                case "role", "role-code" -> groups.computeIfAbsent(target.type()+":"+target.id(),key->roleUsers(target));
                default -> throw AlertRules.invalid("预警接收人类型无效");
            };
        }

        private List<Long> roleUsers(AlertRules.Receiver target) {
            String field=target.type().equals("role")?"r.id":"r.code";
            Object value=target.type().equals("role")?AlertRules.positive(target.id()):target.id();
            return jdbc.queryForList("""
                    SELECT DISTINCT x.user_id FROM sys_user_role x JOIN sys_role r ON r.tenant_id=x.tenant_id AND r.id=x.role_id
                    JOIN org_user_data_scope s ON s.tenant_id=x.tenant_id AND s.user_id=x.user_id AND s.company_id=?
                    WHERE x.tenant_id=? AND r.status='active' AND
                    """+field+"=? ORDER BY x.user_id",Long.class,company,tenant,value);
        }
    }
}
