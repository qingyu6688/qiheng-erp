package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.ScopedPrivateValues;
import cn.erp.reference.CrmPartnerMaster;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.sysadmin.AreaService;
import cn.erp.sysadmin.DictionaryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CrmCustomers {
    private static final String WECHAT = "crm-contact-wechat";
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final CrmOwnership ownership;
    private final CrmPartnerMaster master;
    private final ScopedPrivateValues secrets;
    private final DictionaryService dictionaries;
    private final AreaService areas;
    private final AuditService audit;

    CrmCustomers(JdbcTemplate jdbc, CrmScope scope, CrmOwnership ownership, CrmPartnerMaster master,
                 ScopedPrivateValues secrets, DictionaryService dictionaries, AreaService areas, AuditService audit) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.ownership = ownership;
        this.master = master;
        this.secrets = secrets;
        this.dictionaries = dictionaries;
        this.areas = areas;
        this.audit = audit;
    }

    Map<String, Object> list(CompanyAccess actor, boolean pool, String keyword, String cursor, int limit) {
        long before = CrmCoreRows.cursor(cursor, limit);
        String term = CrmCoreRows.trim(keyword);
        if (term.length() > 100) throw CrmScope.invalid("搜索词最多 100 字");
        var rows = jdbc.queryForList("""
                SELECT c.*, p.name AS partner_name, p.code AS partner_code, p.status AS partner_status,
                    EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id
                        AND r.partner_id=p.id AND r.role='customer' AND r.status='active') AS active_customer_role,
                    (%s) AS can_write
                FROM crm_customer_profile c JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id
                WHERE c.tenant_id=? AND c.company_id=? AND c.partner_id<? AND c.pool_entered_at IS %s
                    AND (LOCATE(?,p.code)>0 OR LOCATE(?,p.name)>0) AND (%s)
                ORDER BY c.partner_id DESC LIMIT ?
                """.formatted(scope.customerWritable(actor, "c"), pool ? "NOT NULL" : "NULL", scope.customerFilter(actor, "c")),
                actor.tenantId(), actor.companyId(), before, term, term, limit + 1);
        return CrmCoreRows.page(rows.stream().map(CrmCustomers::response).toList(), limit, "partnerId");
    }

    Map<String, Object> get(CompanyAccess actor, long partner) {
        return response(scope.requireCustomer(actor, partner, false, false));
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess actor, CrmCoreModels.CustomerInput input) {
        CrmScope.require(actor, "partner:view");
        if (input.partnerId() == null) throw CrmScope.invalid("请选择要建立档案的 ERP 客户");
        scope.lockCompany(actor);
        long partner = input.partnerId();
        master.ensureCustomer(actor, partner, false);
        if (!jdbc.queryForList("SELECT partner_id FROM crm_customer_profile WHERE tenant_id=? AND company_id=? AND partner_id=?",
                Long.class, actor.tenantId(), actor.companyId(), partner).isEmpty()) {
            throw new ApiException(409, "CRM_CUSTOMER_EXISTS", "当前公司已经建立该客户档案");
        }
        long owner = input.ownerUserId() == null ? actor.userId() : input.ownerUserId();
        if (owner != actor.userId()) CrmScope.require(actor, "crm_customer:transfer");
        long department = scope.memberDepartment(actor, owner);
        scope.requireAssignable(actor, owner, department);
        var fields = customerValues(actor, input, Map.of());
        ownership.enforce(actor, owner, department, fields.dealStatus(), false, partner);
        jdbc.update("""
                INSERT INTO crm_customer_profile(tenant_id,company_id,partner_id,owner_user_id,owner_department_id,level,source,
                    industry,area_id,deal_status,next_contact_at,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                """, actor.tenantId(), actor.companyId(), partner, owner, department, fields.level(), fields.source(), fields.industry(),
                input.areaId(), fields.dealStatus(), CrmCoreRows.timestamp(input.nextContactAt()), actor.userId());
        audit.record(actor, "crm_customer.create", "crm_customer_profile", partner, null, Map.of("ownerUserId", owner));
        if (owner == actor.userId() || actor.hasPermission("crm_customer:view_all") || actor.hasPermission("crm_customer:manage_all")) {
            return get(actor, partner);
        }
        return Map.of("id", Long.toString(partner), "partnerId", Long.toString(partner));
    }

    @Transactional
    public Map<String, Object> update(CompanyAccess actor, long partner, CrmCoreModels.CustomerInput input) {
        scope.lockCompany(actor);
        var before = scope.requireCustomer(actor, partner, true, true);
        CrmScope.version(before, input.version());
        if (input.partnerId() != null && input.partnerId() != partner) throw CrmScope.invalid("不能更改客户主数据标识");
        if (input.ownerUserId() != null && input.ownerUserId() != CrmScope.number(before, "owner_user_id")) {
            throw CrmScope.invalid("请使用转移动作更换客户负责人");
        }
        var fields = customerValues(actor, input, before);
        long owner = CrmScope.number(before, "owner_user_id");
        if (owner > 0 && !fields.dealStatus().equals(before.get("deal_status"))) {
            ownership.enforce(actor, owner, CrmScope.number(before, "owner_department_id"), fields.dealStatus(),
                    CrmScope.flag(before.get("locked")), partner);
        }
        jdbc.update("""
                UPDATE crm_customer_profile SET level=?,source=?,industry=?,area_id=?,deal_status=?,next_contact_at=?,version=version+1
                WHERE tenant_id=? AND company_id=? AND partner_id=?
                """, fields.level(), fields.source(), fields.industry(), input.areaId(), fields.dealStatus(), CrmCoreRows.timestamp(input.nextContactAt()),
                actor.tenantId(), actor.companyId(), partner);
        audit.record(actor, "crm_customer.update", "crm_customer_profile", partner, null, Map.of("dealStatus", fields.dealStatus()));
        return get(actor, partner);
    }

    @Transactional
    public Map<String, Object> action(CompanyAccess actor, long partner, String action, CrmCoreModels.OwnerAction input) {
        scope.lockCompany(actor);
        var before = scope.requireCustomer(actor, partner, true, true);
        CrmScope.version(before, input.version());
        switch (action) {
            case "transfer" -> changeOwner(actor, partner, before, input.ownerUserId(), "transfer");
            case "lock", "unlock" -> setLocked(actor, partner, before, "lock".equals(action));
            case "release" -> release(actor, partner, before);
            default -> throw CrmScope.invalid("客户操作无效");
        }
        return Map.of("id", Long.toString(partner), "partnerId", Long.toString(partner), "version", CrmScope.number(before, "version") + 1);
    }

    @Transactional
    public Map<String, Object> poolAction(CompanyAccess actor, long partner, String action, CrmCoreModels.OwnerAction input) {
        CrmScope.require(actor, "crm_pool:view");
        scope.lockCompany(actor);
        var before = scope.requireCustomer(actor, partner, false, true);
        if (before.get("pool_entered_at") == null) throw new ApiException(409, "CRM_POOL_ALREADY_TAKEN", "客户已被领取或移出公海");
        if (input.version() != null) CrmScope.version(before, input.version());
        if (!"active".equals(before.get("partner_status")) || !CrmScope.flag(before.get("active_customer_role"))) {
            throw new ApiException(409, "CRM_CUSTOMER_DISABLED", "已停用客户不可领取");
        }
        Long owner = switch (action) {
            case "claim" -> actor.userId();
            case "assign" -> input.ownerUserId();
            default -> throw CrmScope.invalid("公海操作无效");
        };
        changeOwner(actor, partner, before, owner, action);
        return Map.of("id", Long.toString(partner), "partnerId", Long.toString(partner), "version", CrmScope.number(before, "version") + 1);
    }

    private void changeOwner(CompanyAccess actor, long partner, Map<String, Object> before, Long owner, String action) {
        if (owner == null) throw CrmScope.invalid("请选择客户负责人");
        long department = scope.memberDepartment(actor, owner);
        scope.requireAssignable(actor, owner, department);
        ownership.enforce(actor, owner, department, before.get("deal_status").toString(), CrmScope.flag(before.get("locked")), partner);
        String publicOnly = before.get("pool_entered_at") != null ? " AND owner_user_id=0 AND pool_entered_at IS NOT NULL" : "";
        int changed = jdbc.update("""
                UPDATE crm_customer_profile SET owner_user_id=?,owner_department_id=?,owner_since_at=UTC_TIMESTAMP(3),pool_entered_at=NULL,pool_reason=NULL,
                    version=version+1 WHERE tenant_id=? AND company_id=? AND partner_id=?
                """ + publicOnly, owner, department, actor.tenantId(), actor.companyId(), partner);
        if (changed != 1) throw new ApiException(409, "CRM_POOL_ALREADY_TAKEN", "客户已被领取或移出公海");
        audit.record(actor, "crm_customer." + action, "crm_customer_profile", partner,
                Map.of("ownerUserId", before.get("owner_user_id")), Map.of("ownerUserId", owner));
    }

    private void setLocked(CompanyAccess actor, long partner, Map<String, Object> before, boolean locked) {
        long owner = CrmScope.number(before, "owner_user_id");
        if (locked && owner == 0) throw CrmScope.invalid("请分配客户后再锁定");
        if (locked) ownership.enforce(actor, owner, CrmScope.number(before, "owner_department_id"), before.get("deal_status").toString(), true, partner);
        jdbc.update("UPDATE crm_customer_profile SET `locked`=?,version=version+1 WHERE tenant_id=? AND company_id=? AND partner_id=?",
                locked, actor.tenantId(), actor.companyId(), partner);
        audit.record(actor, locked ? "crm_customer.lock" : "crm_customer.unlock", "crm_customer_profile", partner,
                Map.of("locked", CrmScope.flag(before.get("locked"))), Map.of("locked", locked));
    }

    private void release(CompanyAccess actor, long partner, Map<String, Object> before) {
        if (CrmScope.flag(before.get("locked"))) throw new ApiException(409, "CRM_CUSTOMER_LOCKED", "请解锁后再放回公海");
        jdbc.update("""
                UPDATE crm_customer_profile SET owner_user_id=0,owner_department_id=0,pool_entered_at=UTC_TIMESTAMP(3),
                    pool_reason='manual',version=version+1 WHERE tenant_id=? AND company_id=? AND partner_id=?
                """, actor.tenantId(), actor.companyId(), partner);
        jdbc.update("DELETE FROM crm_customer_team WHERE tenant_id=? AND company_id=? AND partner_id=?",
                actor.tenantId(), actor.companyId(), partner);
        audit.record(actor, "crm_customer.release", "crm_customer_profile", partner, Map.of("ownerUserId", before.get("owner_user_id")), Map.of("poolReason", "manual"));
    }

    Map<String, Object> team(CompanyAccess actor, long partner) {
        var customer = scope.requireCustomer(actor, partner, false, false);
        if (customer.get("pool_entered_at") != null) return Map.of("items", List.of());
        var team = jdbc.queryForList("""
                SELECT t.user_id,u.display_name,t.permission,t.expires_at FROM crm_customer_team t
                JOIN sys_user u ON u.tenant_id=t.tenant_id AND u.id=t.user_id
                WHERE t.tenant_id=? AND t.company_id=? AND t.partner_id=? ORDER BY t.user_id
                """, actor.tenantId(), actor.companyId(), partner);
        return Map.of("items", team.stream().map(CrmCoreRows::row).toList());
    }

    @Transactional
    public Map<String, Object> saveTeam(CompanyAccess actor, long partner, CrmCoreModels.TeamInput input) {
        scope.lockCompany(actor);
        var customer = scope.requireCustomer(actor, partner, true, true);
        long user = input.userId();
        if (user == CrmScope.number(customer, "owner_user_id")) throw CrmScope.invalid("负责人已拥有维护权限");
        long department = scope.memberDepartment(actor, user);
        scope.requireAssignable(actor, user, department);
        if (input.expiresAt() != null && !input.expiresAt().isAfter(Instant.now())) throw CrmScope.invalid("到期时间必须晚于现在");
        jdbc.update("""
                INSERT INTO crm_customer_team(tenant_id,company_id,partner_id,user_id,permission,expires_at) VALUES(?,?,?,?,?,?) AS staged
                ON DUPLICATE KEY UPDATE permission=staged.permission,expires_at=staged.expires_at
                """, actor.tenantId(), actor.companyId(), partner, user, input.permission(), CrmCoreRows.timestamp(input.expiresAt()));
        audit.record(actor, "crm_customer.team_save", "crm_customer_profile", partner, null, Map.of("userId", user, "permission", input.permission()));
        return team(actor, partner);
    }

    @Transactional
    public Map<String, Object> removeTeam(CompanyAccess actor, long partner, long user) {
        scope.lockCompany(actor);
        scope.requireCustomer(actor, partner, true, true);
        int removed = jdbc.update("DELETE FROM crm_customer_team WHERE tenant_id=? AND company_id=? AND partner_id=? AND user_id=?",
                actor.tenantId(), actor.companyId(), partner, user);
        if (removed > 0) audit.record(actor, "crm_customer.team_remove", "crm_customer_profile", partner, Map.of("userId", user), null);
        return Map.of("userId", Long.toString(user), "deleted", removed > 0);
    }

    Map<String, Object> contacts(CompanyAccess actor, long partner) {
        var customer = scope.requireCustomer(actor, partner, false, false);
        if (customer.get("pool_entered_at") != null) return Map.of("items", List.of());
        var rows = jdbc.queryForList("""
                SELECT m.id,m.name,m.mobile,m.email,m.address,m.area_id,m.is_default,m.version,c.position,c.is_decision_maker,
                    c.birthday,c.wechat_cipher,c.remark,c.version AS profile_version
                FROM partner_contact m LEFT JOIN crm_contact_profile c ON c.tenant_id=m.tenant_id AND c.company_id=?
                    AND c.partner_id=m.partner_id AND c.contact_id=m.id
                WHERE m.tenant_id=? AND m.partner_id=? ORDER BY m.is_default DESC,m.id
                """, actor.companyId(), actor.tenantId(), partner);
        return Map.of("items", rows.stream().map(row -> contactView(actor, row)).toList());
    }

    @Transactional
    public Map<String, Object> saveContact(CompanyAccess actor, long partner, CrmCoreModels.ContactInput input) {
        scope.lockCompany(actor);
        scope.requireCustomer(actor, partner, true, true);
        boolean privateAccess = actor.hasPermission("partner_contact:view");
        if ((input.birthday() != null || input.wechat() != null || input.email() != null) && !privateAccess) {
            throw CrmScope.forbidden("没有维护私密联系方式的权限");
        }
        if (input.birthday() != null && (input.birthday().getYear() < 1000 || input.birthday().isAfter(LocalDate.now()))) {
            throw CrmScope.invalid("生日必须是有效的过去日期");
        }
        String email = input.email();
        if (!privateAccess && input.contactId() != null) {
            var values = jdbc.queryForList("SELECT email FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=? FOR UPDATE",
                    String.class, actor.tenantId(), partner, input.contactId());
            email = values.isEmpty() ? null : values.getFirst();
        }
        var row = master.saveContact(actor, partner, input.contactId(), input.name().strip(), input.mobile(), email, input.address(),
                input.isDefault(), input.version(), input.areaId());
        long contact = Long.parseLong(row.get("id").toString());
        saveContactProfile(actor, partner, contact, input);
        return Map.of("id", Long.toString(contact), "partnerId", Long.toString(partner));
    }

    private void saveContactProfile(CompanyAccess actor, long partner, long contact, CrmCoreModels.ContactInput input) {
        var profiles = jdbc.queryForList("SELECT version,wechat_cipher,birthday FROM crm_contact_profile WHERE tenant_id=? AND company_id=? AND partner_id=? AND contact_id=? FOR UPDATE",
                actor.tenantId(), actor.companyId(), partner, contact);
        var prior = profiles.isEmpty() ? Map.<String, Object>of() : profiles.getFirst();
        if (!prior.isEmpty()) CrmScope.version(prior, input.profileVersion());
        String encrypted = input.wechat() == null ? (String) prior.get("wechat_cipher") : encryptWechat(actor, contact, input.wechat());
        Object birthday = actor.hasPermission("partner_contact:view") ? input.birthday() : prior.get("birthday");
        jdbc.update("""
                INSERT INTO crm_contact_profile(tenant_id,company_id,partner_id,contact_id,position,is_decision_maker,birthday,wechat_cipher,remark)
                VALUES(?,?,?,?,?,?,?,?,?) AS staged ON DUPLICATE KEY UPDATE position=staged.position,
                    is_decision_maker=staged.is_decision_maker,birthday=staged.birthday,wechat_cipher=staged.wechat_cipher,remark=staged.remark,
                    version=crm_contact_profile.version+1
                """, actor.tenantId(), actor.companyId(), partner, contact, CrmCoreRows.trim(input.position()),
                input.decisionMaker(), birthday, encrypted, CrmCoreRows.trim(input.remark()));
        audit.record(actor, "crm_customer.contact_save", "crm_customer_profile", partner, null, Map.of("contactId", contact));
    }

    private String encryptWechat(CompanyAccess actor, long contact, String input) {
        String wechat = CrmCoreRows.optional(input);
        return wechat == null ? null : secrets.seal(actor, WECHAT, contact, wechat);
    }

    private Map<String, Object> contactView(CompanyAccess actor, Map<String, Object> raw) {
        var result = CrmCoreRows.row(raw);
        Object ciphertext = result.remove("wechatCipher");
        if (actor.hasPermission("partner_contact:view")) {
            if (ciphertext != null) result.put("wechat", secrets.open(actor, WECHAT, CrmScope.number(raw, "id"), ciphertext.toString()));
        } else {
            Object mobile = result.get("mobile");
            result.put("mobile", mobile == null ? null : "****" + mobile.toString().substring(Math.max(0, mobile.toString().length() - 4)));
            for (String field : List.of("birthday", "email", "address", "areaId")) result.remove(field);
        }
        return result;
    }

    private record Values(String level, String source, String industry, String dealStatus) {}
    private Values customerValues(CompanyAccess actor, CrmCoreModels.CustomerInput input, Map<String, Object> prior) {
        areas.validate(input.areaId());
        String level = CrmCoreRows.trim(input.level()), source = CrmCoreRows.trim(input.source()), industry = CrmCoreRows.trim(input.industry());
        dictionaries.validateValue(actor.tenantId(), "crm_level", CrmCoreRows.optional(level), level.equals(prior.get("level")));
        dictionaries.validateValue(actor.tenantId(), "customer_source", CrmCoreRows.optional(source), source.equals(prior.get("source")));
        dictionaries.validateValue(actor.tenantId(), "crm_industry", CrmCoreRows.optional(industry), industry.equals(prior.get("industry")));
        String status = input.dealStatus() == null ? (String) prior.getOrDefault("deal_status", "unsold") : input.dealStatus();
        return new Values(level, source, industry, status);
    }

    private static Map<String, Object> response(Map<String, Object> raw) {
        var result = CrmCoreRows.row(raw);
        result.put("id", result.get("partnerId"));
        boolean enabled = "active".equals(raw.get("partner_status")) && CrmScope.flag(result.remove("activeCustomerRole"));
        result.put("status", enabled ? "active" : "disabled");
        result.put("canWrite", enabled && CrmScope.flag(raw.get("can_write")));
        return result;
    }
}
