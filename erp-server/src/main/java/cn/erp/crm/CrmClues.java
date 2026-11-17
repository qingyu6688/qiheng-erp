package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.ScopedPrivateValues;
import cn.erp.importing.ClueCsv;
import cn.erp.reference.CrmPartnerMaster;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.sysadmin.AreaService;
import cn.erp.sysadmin.DictionaryService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CrmClues {
    private static final String MOBILE = "crm-clue-mobile";
    private static final Set<String> STATUS = Set.of("new", "following", "converted", "invalid");
    private final JdbcTemplate jdbc;
    private final CrmScope scope;
    private final CrmOwnership ownership;
    private final ScopedPrivateValues secrets;
    private final CrmPartnerMaster partnerMaster;
    private final DictionaryService dictionaries;
    private final AreaService areas;
    private final AuditService audit;
    private final Validator validator;

    CrmClues(JdbcTemplate jdbc, CrmScope scope, CrmOwnership ownership, ScopedPrivateValues secrets,
             CrmPartnerMaster partnerMaster, DictionaryService dictionaries, AreaService areas, AuditService audit, Validator validator) {
        this.jdbc = jdbc;
        this.scope = scope;
        this.ownership = ownership;
        this.secrets = secrets;
        this.partnerMaster = partnerMaster;
        this.dictionaries = dictionaries;
        this.areas = areas;
        this.audit = audit;
        this.validator = validator;
    }

    Map<String, Object> list(CompanyAccess actor, String status, String keyword, String cursor, int limit) {
        long before = CrmCoreRows.cursor(cursor, limit);
        if (status != null && !STATUS.contains(status)) throw CrmScope.invalid("线索状态无效");
        String term = CrmCoreRows.trim(keyword);
        if (term.length() > 100) throw CrmScope.invalid("搜索词最多 100 字");
        var rows = jdbc.queryForList("""
                SELECT l.id, l.name, l.contact_name, l.mobile_last4, l.source, l.industry, l.area_id, l.owner_user_id,
                    l.owner_department_id, l.status, l.converted_partner_id, l.next_contact_at, l.last_follow_at,
                    l.version, l.created_at, l.updated_at
                FROM crm_clue l WHERE l.tenant_id=? AND l.company_id=? AND l.id<?
                    AND (? IS NULL OR l.status=?) AND (LOCATE(?,l.name)>0 OR LOCATE(?,l.contact_name)>0) AND %s
                ORDER BY l.id DESC LIMIT ?
                """.formatted(scope.clueFilter(actor, "l")), actor.tenantId(), actor.companyId(), before, status, status, term, term, limit + 1);
        return CrmCoreRows.page(rows.stream().map(row -> response(actor, row, false)).toList(), limit, "id");
    }

    Map<String, Object> get(CompanyAccess actor, long id) {
        return response(actor, scope.requireClue(actor, id, false), true);
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess actor, CrmCoreModels.ClueInput input) {
        scope.lockCompany(actor);
        long id = insert(actor, input);
        return get(actor, id);
    }

    @Transactional
    public Map<String, Object> importRows(CompanyAccess actor, List<ClueCsv.Row> rows) {
        scope.lockCompany(actor);
        for (var row : rows) importRow(actor, row);
        return Map.of("imported", rows.size());
    }

    private void importRow(CompanyAccess actor, ClueCsv.Row row) {
        var cells = row.cells();
        var input = new CrmCoreModels.ClueInput(cells.get(0), cells.get(1), cells.get(2), cells.get(3), cells.get(4),
                cells.get(5), cells.get(6), null, null, null);
        var failures = validator.validate(input);
        if (!failures.isEmpty()) throw CrmScope.invalid("第 " + row.number() + " 行" + failures.iterator().next().getMessage());
        try { insert(actor, input); }
        catch (ApiException error) {
            throw new ApiException(error.getStatus(), error.getErrorCode(), "第 " + row.number() + " 行" + error.getMessage());
        }
    }

    private long insert(CompanyAccess actor, CrmCoreModels.ClueInput input) {
        var reference = validateReferences(actor, input, Map.of());
        long department = OrgOwnership.department(actor);
        long id = Ids.next();
        var number = phone(actor, id, input.mobile());
        jdbc.update("""
                INSERT INTO crm_clue(id,tenant_id,company_id,name,contact_name,mobile_cipher,mobile_hash,mobile_last4,email,tax_no,
                    source,industry,area_id,owner_user_id,owner_department_id,next_contact_at,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), input.name().strip(), CrmCoreRows.trim(input.contactName()),
                number.cipher(), number.hash(), number.lastFour(), CrmCoreRows.trim(input.email()), CrmCoreRows.trim(input.taxNo()),
                reference.source(), reference.industry(), input.areaId(), actor.userId(), department,
                CrmCoreRows.timestamp(input.nextContactAt()), actor.userId());
        audit.record(actor, "crm_clue.create", "crm_clue", id, null, Map.of("status", "new"));
        return id;
    }

    @Transactional
    public Map<String, Object> update(CompanyAccess actor, long id, CrmCoreModels.ClueInput input) {
        scope.lockCompany(actor);
        var before = scope.requireClue(actor, id, true);
        requireOpen(before, input.version());
        var references = validateReferences(actor, input, before);
        var number = input.mobile() == null
                ? new Phone((String) before.get("mobile_cipher"), (String) before.get("mobile_hash"), (String) before.get("mobile_last4"))
                : phone(actor, id, input.mobile());
        jdbc.update("""
                UPDATE crm_clue SET name=?,contact_name=?,mobile_cipher=?,mobile_hash=?,mobile_last4=?,email=?,tax_no=?,
                    source=?,industry=?,area_id=?,next_contact_at=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                """, input.name().strip(), CrmCoreRows.trim(input.contactName()), number.cipher(), number.hash(), number.lastFour(),
                input.email() == null ? before.get("email") : CrmCoreRows.trim(input.email()),
                input.taxNo() == null ? before.get("tax_no") : CrmCoreRows.trim(input.taxNo()), references.source(), references.industry(), input.areaId(),
                CrmCoreRows.timestamp(input.nextContactAt()), actor.tenantId(), actor.companyId(), id);
        audit.record(actor, "crm_clue.update", "crm_clue", id, null, Map.of("status", before.get("status")));
        return get(actor, id);
    }

    @Transactional
    public Map<String, Object> action(CompanyAccess actor, long id, String action, CrmCoreModels.ClueAction input) {
        if ("convert".equals(action)) {
            // CRM 各公司的转换共用一个租户级入口，避免对同一名称或税号并发创建主数据。
            jdbc.update("INSERT INTO crm_tenant_guard(tenant_id) VALUES(?) ON DUPLICATE KEY UPDATE tenant_id=tenant_id", actor.tenantId());
        }
        scope.lockCompany(actor);
        var before = scope.requireClue(actor, id, true);
        if ("convert".equals(action) && "converted".equals(before.get("status"))) {
            long partner = CrmScope.number(before, "converted_partner_id");
            if (input.partnerId() != null && partner != input.partnerId()) {
                throw new ApiException(409, "CRM_CLUE_ALREADY_CONVERTED", "线索已转化到另一客户");
            }
            scope.requireCustomer(actor, partner, false, false);
            return response(actor, before, true);
        }
        requireOpen(before, input.version());
        return switch (action) {
            case "assign" -> assign(actor, id, input.ownerUserId(), before);
            case "invalidate" -> invalidate(actor, id, before);
            case "convert" -> convert(actor, id, input, before);
            default -> throw CrmScope.invalid("线索操作无效");
        };
    }

    private Map<String, Object> assign(CompanyAccess actor, long id, Long owner, Map<String, Object> before) {
        if (owner == null) throw CrmScope.invalid("请选择线索负责人");
        long department = scope.memberDepartment(actor, owner);
        scope.requireAssignable(actor, owner, department);
        jdbc.update("UPDATE crm_clue SET owner_user_id=?,owner_department_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                owner, department, actor.tenantId(), actor.companyId(), id);
        audit.record(actor, "crm_clue.assign", "crm_clue", id, Map.of("ownerUserId", before.get("owner_user_id")), Map.of("ownerUserId", owner));
        return Map.of("id", Long.toString(id), "ownerUserId", owner.toString(), "version", CrmScope.number(before, "version") + 1);
    }

    @Transactional
    public Map<String, Object> batchAssign(CompanyAccess actor, CrmCoreModels.BatchAssign input) {
        scope.lockCompany(actor);
        if (input.items().stream().map(CrmCoreModels.ClueReference::id).distinct().count() != input.items().size()) {
            throw CrmScope.invalid("批量分配不能包含重复线索");
        }
        for (var item : input.items()) {
            var prior = scope.requireClue(actor, item.id(), true);
            requireOpen(prior, item.version());
            assign(actor, item.id(), input.ownerUserId(), prior);
        }
        return Map.of("assigned", input.items().size());
    }

    private Map<String, Object> invalidate(CompanyAccess actor, long id, Map<String, Object> before) {
        jdbc.update("UPDATE crm_clue SET status='invalid',version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        audit.record(actor, "crm_clue.invalidate", "crm_clue", id, Map.of("status", before.get("status")), Map.of("status", "invalid"));
        return get(actor, id);
    }

    private Map<String, Object> convert(CompanyAccess actor, long id, CrmCoreModels.ClueAction input, Map<String, Object> clue) {
        String name = clue.get("name").toString();
        String tax = clue.get("tax_no").toString();
        long partner = input.partnerId() == null ? createMaster(actor, input.code(), clue, name, tax) : merge(actor, input.partnerId());
        boolean hasProfile = !jdbc.queryForList("SELECT partner_id FROM crm_customer_profile WHERE tenant_id=? AND company_id=? AND partner_id=? FOR UPDATE",
                Long.class, actor.tenantId(), actor.companyId(), partner).isEmpty();
        if (hasProfile) scope.requireCustomer(actor, partner, false, true);
        else insertProfile(actor, clue, partner);
        jdbc.update("UPDATE crm_clue SET status='converted',converted_partner_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                partner, actor.tenantId(), actor.companyId(), id);
        audit.record(actor, "crm_clue.convert", "crm_clue", id, Map.of("status", clue.get("status")),
                Map.of("status", "converted", "partnerId", Long.toString(partner)));
        return get(actor, id);
    }

    private long createMaster(CompanyAccess actor, String code, Map<String, Object> clue, String name, String tax) {
        var duplicates = jdbc.queryForList("""
                SELECT id,code,name,status FROM business_partner WHERE tenant_id=? AND (name=? OR (?<>'' AND tax_no=?))
                ORDER BY id LIMIT 20
                """, actor.tenantId(), name, tax, tax);
        if (!duplicates.isEmpty()) {
            Object details = actor.hasPermission("partner:view") ? Map.of("candidates", duplicates.stream().map(CrmCoreRows::row).toList()) : null;
            throw new ApiException(409, "CRM_DUPLICATE_PARTNER", "存在同名或同税号的往来单位，请核对后选择合并", details);
        }
        Long area = clue.get("area_id") == null ? null : CrmScope.number(clue, "area_id");
        String selectedCode = CrmCoreRows.optional(code);
        if (selectedCode == null) selectedCode = "CRM-" + CrmScope.number(clue, "id");
        return partnerMaster.createCustomer(actor, selectedCode, name, CrmCoreRows.optional(tax), area, CrmCoreRows.optional(clue.get("source").toString()));
    }

    private long merge(CompanyAccess actor, long partner) {
        CrmScope.require(actor, "partner:view");
        partnerMaster.ensureCustomer(actor, partner, true);
        return partner;
    }

    private void insertProfile(CompanyAccess actor, Map<String, Object> clue, long partner) {
        long owner = CrmScope.number(clue, "owner_user_id");
        long department = scope.memberDepartment(actor, owner);
        scope.requireAssignable(actor, owner, department);
        ownership.enforce(actor, owner, department, "unsold", false, partner);
        jdbc.update("""
                INSERT INTO crm_customer_profile(tenant_id,company_id,partner_id,owner_user_id,owner_department_id,source,industry,area_id,
                    next_contact_at,last_follow_at,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, actor.tenantId(), actor.companyId(), partner, owner, department, clue.get("source"), clue.get("industry"),
                clue.get("area_id"), clue.get("next_contact_at"), clue.get("last_follow_at"), actor.userId());
    }

    private record References(String source, String industry) {}
    private References validateReferences(CompanyAccess actor, CrmCoreModels.ClueInput input, Map<String, Object> prior) {
        areas.validate(input.areaId());
        String source = CrmCoreRows.trim(input.source()), industry = CrmCoreRows.trim(input.industry());
        dictionaries.validateValue(actor.tenantId(), "customer_source", CrmCoreRows.optional(source), source.equals(prior.get("source")));
        dictionaries.validateValue(actor.tenantId(), "crm_industry", CrmCoreRows.optional(industry), industry.equals(prior.get("industry")));
        return new References(source, industry);
    }

    private record Phone(String cipher, String hash, String lastFour) {}
    private Phone phone(CompanyAccess actor, long id, String input) {
        String mobile = CrmCoreRows.trim(input).replaceAll("[ ()-]", "");
        if (mobile.isEmpty()) return new Phone(null, null, null);
        if (!mobile.matches("\\+?[0-9]{6,20}")) throw CrmScope.invalid("手机号格式无效");
        String hash = secrets.fingerprint(actor, MOBILE, mobile);
        if (!jdbc.queryForList("SELECT id FROM crm_clue WHERE tenant_id=? AND company_id=? AND mobile_hash=? AND id<>? AND status IN ('new','following') LIMIT 1",
                Long.class, actor.tenantId(), actor.companyId(), hash, id).isEmpty()) {
            throw new ApiException(409, "CRM_DUPLICATE_CLUE", "当前公司已存在使用此手机号的未完成线索");
        }
        return new Phone(secrets.seal(actor, MOBILE, id, mobile), hash, mobile.substring(mobile.length() - 4));
    }

    private Map<String, Object> response(CompanyAccess actor, Map<String, Object> raw, boolean detail) {
        var row = CrmCoreRows.row(raw);
        row.remove("mobileCipher");
        row.remove("mobileHash");
        Object suffix = row.remove("mobileLast4");
        boolean sensitive = detail && actor.hasPermission("partner_contact:view");
        if (!sensitive) {
            row.remove("email");
            row.remove("taxNo");
        }
        if (suffix != null) row.put("mobile", sensitive
                ? secrets.open(actor, MOBILE, CrmScope.number(raw, "id"), raw.get("mobile_cipher").toString()) : "****" + suffix);
        return row;
    }

    private void requireOpen(Map<String, Object> row, Long version) {
        CrmScope.version(row, version);
        if (!Set.of("new", "following").contains(row.get("status"))) {
            throw new ApiException(409, "STATE_CONFLICT", "仅未完成线索可以继续处理");
        }
    }
}
