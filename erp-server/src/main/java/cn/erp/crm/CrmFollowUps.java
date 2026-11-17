package cn.erp.crm;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.CrmReminder;
import cn.erp.shared.Ids;
import cn.erp.sysadmin.DictionaryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
@Transactional(readOnly = true)
class CrmFollowUps {
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final AccessService access;
    private final DictionaryService dictionaries;
    private final ObjectMapper json;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    CrmFollowUps(JdbcTemplate jdbc, CrmScope scope, AccessService access, DictionaryService dictionaries,
                 ObjectMapper json, AuditService audit, ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.access = access;
        this.dictionaries = dictionaries;
        this.json = json;
        this.audit = audit;
        this.events = events;
    }

    Map<String, Object> list(CompanyAccess actor, String type, long businessId, String cursor, int limit) {
        subject(actor, type, businessId, false);
        long before = CrmCoreRows.cursor(cursor, limit);
        var rows = jdbc.queryForList("""
                SELECT f.id,f.biz_type,f.biz_id,f.method,f.content,f.next_contact_at,f.mentioned_user_ids,f.created_by,f.created_at,
                    u.display_name AS author_name FROM crm_follow_up f LEFT JOIN sys_user u ON u.tenant_id=f.tenant_id AND u.id=f.created_by
                WHERE f.tenant_id=? AND f.company_id=? AND f.biz_type=? AND f.biz_id=? AND f.id<? ORDER BY f.id DESC LIMIT ?
                """, actor.tenantId(), actor.companyId(), type, businessId, before, limit + 1);
        return CrmCoreRows.page(rows.stream().map(this::view).toList(), limit, "id");
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess actor, CrmCoreModels.FollowInput input) {
        scope.lockCompany(actor);
        long partner = subject(actor, input.bizType(), input.bizId(), true);
        dictionaries.validateValue(actor.tenantId(), "crm_follow_method", input.method(), false);
        var mentions = input.mentionedUserIds() == null ? List.<Long>of() : input.mentionedUserIds().stream().distinct().toList();
        mentions.forEach(user -> scope.memberDepartment(actor, user));
        long id = Ids.next();
        var next = CrmCoreRows.timestamp(input.nextContactAt());
        jdbc.update("""
                INSERT INTO crm_follow_up(id,tenant_id,company_id,biz_type,biz_id,method,content,next_contact_at,mentioned_user_ids,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), input.bizType(), input.bizId(), input.method(), input.content().strip(), next,
                json.writeValueAsString(mentions.stream().map(String::valueOf).toList()), actor.userId());
        updateContactTime(actor, input, partner);
        audit.record(actor, "crm_follow_up.create", "crm_follow_up", id, null,
                Map.of("bizType", input.bizType(), "bizId", input.bizId().toString(), "method", input.method()));
        for (long user : mentions) notifyMention(actor, input, id, user);
        return view(jdbc.queryForMap("SELECT id,biz_type,biz_id,method,content,next_contact_at,mentioned_user_ids,created_by,created_at FROM crm_follow_up WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id));
    }

    private long subject(CompanyAccess actor, String type, long id, boolean write) {
        return switch (type) {
            case "clue" -> {
                CrmScope.require(actor, write ? "crm_clue:update" : "crm_clue:view");
                var lead = scope.requireClue(actor, id, write);
                if (write && !Set.of("new", "following").contains(lead.get("status"))) {
                    throw new ApiException(409, "STATE_CONFLICT", "仅未完成线索可以继续跟进");
                }
                yield 0;
            }
            case "customer" -> {
                CrmScope.require(actor, write ? "crm_customer:update" : "crm_customer:view");
                scope.requireCustomer(actor, id, write, write);
                yield id;
            }
            case "business", "contract" -> {
                String table = "business".equals(type) ? "crm_business" : "crm_contract";
                CrmScope.require(actor, table + (write ? ":manage" : ":view"));
                var rows = jdbc.queryForList("SELECT partner_id FROM " + table + " WHERE tenant_id=? AND company_id=? AND id=?",
                        Long.class, actor.tenantId(), actor.companyId(), id);
                if (rows.isEmpty()) throw CrmScope.missing();
                long partner = rows.getFirst();
                scope.requireCustomer(actor, partner, write, write);
                yield partner;
            }
            default -> throw CrmScope.invalid("跟进对象无效");
        };
    }

    private void updateContactTime(CompanyAccess actor, CrmCoreModels.FollowInput input, long partner) {
        var next = CrmCoreRows.timestamp(input.nextContactAt());
        if (partner == 0) {
            jdbc.update("""
                    UPDATE crm_clue SET last_follow_at=UTC_TIMESTAMP(3),next_contact_at=COALESCE(?,next_contact_at),status='following',version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """, next, actor.tenantId(), actor.companyId(), input.bizId());
        } else {
            jdbc.update("""
                    UPDATE crm_customer_profile SET last_follow_at=UTC_TIMESTAMP(3),next_contact_at=COALESCE(?,next_contact_at),version=version+1
                    WHERE tenant_id=? AND company_id=? AND partner_id=?
                    """, next, actor.tenantId(), actor.companyId(), partner);
        }
    }

    private void notifyMention(CompanyAccess author, CrmCoreModels.FollowInput input, long id, long user) {
        if (user == author.userId()) return;
        var recipient = access.forUser(author.tenantId(), user, author.companyId());
        if (recipient == null) return;
        try { subject(recipient, input.bizType(), input.bizId(), false); }
        catch (ApiException denied) {
            if (denied.getStatus() == 403 || denied.getStatus() == 404) return;
            throw denied;
        }
        events.publishEvent(new CrmReminder(author.tenantId(), author.companyId(), user, "mention", "crm:follow:" + id + ":" + user));
    }

    private Map<String, Object> view(Map<String, Object> raw) {
        var row = CrmCoreRows.row(raw);
        var mentions = new ArrayList<String>();
        json.readTree(row.get("mentionedUserIds").toString()).forEach(node -> mentions.add(node.stringValue()));
        row.put("mentionedUserIds", List.copyOf(mentions));
        return row;
    }
}
