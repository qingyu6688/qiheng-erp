package cn.erp.coderule;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.ConfiguredDocumentNumber;
import cn.erp.shared.Ids;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Service
public class CodeRules implements ConfiguredDocumentNumber {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String SKU = "masterdata:sku";
    private static final String PARTNER = "masterdata:partner";
    private static final Map<String, String> DOCUMENTS = Map.ofEntries(
            Map.entry("SO", "document:sales_order"), Map.entry("PO", "document:purchase_order"),
            Map.entry("SH", "document:shipment"), Map.entry("GR", "document:goods_receipt"),
            Map.entry("PR", "document:purchase_requisition"), Map.entry("PR_RETURN", "document:purchase_return"),
            Map.entry("RC", "document:receipt"), Map.entry("PY", "document:payment"),
            Map.entry("AT", "document:account_transfer"), Map.entry("SA", "document:stock_adjustment"),
            Map.entry("SC", "document:stock_count"), Map.entry("ST", "document:stock_transfer"),
            Map.entry("OE", "document:opening_entry"), Map.entry("RV", "document:document_reversal"),
            Map.entry("RF", "document:refund_request"), Map.entry("SR", "document:sales_return"));
    private static final TypeReference<List<CodePattern.Segment>> SEGMENTS = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final AuditService audit;

    CodeRules(JdbcTemplate jdbc, ObjectMapper json, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.audit = audit;
    }

    record Input(String companyId, String target, List<CodePattern.Segment> segments, String resetCycle,
                 boolean enabled, Long version) {}
    record Preview(Input rule, LocalDate date, String categoryId) {}
    record Rule(String id, String companyId, String target, List<CodePattern.Segment> segments,
                String resetCycle, boolean enabled, long version) {}

    List<Rule> list(CompanyAccess actor) {
        return query("WHERE tenant_id=? AND company_id IN (0,?) ORDER BY target,company_id", actor.tenantId(), actor.companyId());
    }

    @Transactional
    Rule save(CompanyAccess actor, Input rule) {
        long company = validate(actor, rule);
        var before = find(actor.tenantId(), company, rule.target());
        long id;
        if (before == null) {
            if (rule.version() != null) throw conflict();
            id = Ids.next();
            try {
                jdbc.update("INSERT INTO code_rule(id,tenant_id,company_id,target,segments,reset_cycle,enabled) VALUES(?,?,?,?,?,?,?)",
                        id, actor.tenantId(), company, rule.target(), json.writeValueAsString(rule.segments()), rule.resetCycle(), rule.enabled());
            } catch (DuplicateKeyException collision) { throw conflict(); }
        } else {
            if (rule.version() == null) throw conflict();
            id = Long.parseLong(before.id());
            int updated = jdbc.update("""
                    UPDATE code_rule SET segments=?,reset_cycle=?,enabled=?,version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=? AND version=?
                    """, json.writeValueAsString(rule.segments()), rule.resetCycle(), rule.enabled(),
                    actor.tenantId(), company, id, rule.version());
            if (updated != 1) throw conflict();
        }
        var after = find(actor.tenantId(), company, rule.target());
        audit.record(actor, "code_rule.save", "code_rule", id, before, after);
        return after;
    }

    String preview(CompanyAccess actor, Preview preview) {
        if (preview == null || preview.rule() == null) throw CodePattern.invalid("请提供编码规则");
        long company = validate(actor, preview.rule());
        var input = preview.rule();
        LocalDate date = preview.date() == null ? LocalDate.now(BUSINESS_ZONE) : preview.date();
        var rule = find(actor.tenantId(), company, input.target());
        long next = rule == null ? 1 : next(actor, rule, input.resetCycle(), date, false);
        Long category = preview.categoryId() == null || preview.categoryId().isBlank() ? null : identifier(preview.categoryId());
        return CodePattern.render(input.segments(), context(actor, date, input.segments(), category), next);
    }

    /** 取号由单据的独立短事务保护，现有默认取号继续可用。 */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String document(ActorScope actor, String type, LocalDate date) {
        String target = DOCUMENTS.get(type);
        if (target == null) return null;
        return allocate(actor, target, date, null);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String master(ActorScope actor, String target, String entered, Long category) {
        if (entered != null && !entered.isBlank()) return entered.strip();
        String generated = allocate(actor, "masterdata:" + target, LocalDate.now(BUSINESS_ZONE), category);
        if (generated == null) throw CodePattern.invalid("请输入编码，或配置并启用自动编码规则");
        return generated;
    }

    private String allocate(ActorScope actor, String target, LocalDate date, Long category) {
        var rules = query("WHERE tenant_id=? AND company_id IN (0,?) AND target=? AND enabled=1 ORDER BY company_id DESC LIMIT 1",
                actor.tenantId(), actor.companyId(), target);
        if (rules.isEmpty()) return null;
        var rule = rules.getFirst();
        return CodePattern.render(rule.segments(), context(actor, date, rule.segments(), category),
                next(actor, rule, rule.resetCycle(), date, true));
    }

    private long next(ActorScope actor, Rule rule, String reset, LocalDate date, boolean update) {
        String key = CodePattern.cycle(reset, date);
        long scope = master(rule.target()) ? 0 : actor.companyId();
        if (update) jdbc.update("""
                INSERT INTO code_rule_seq(tenant_id,rule_id,scope_key,cycle_key,next_value) VALUES(?,?,?,?,1)
                ON DUPLICATE KEY UPDATE next_value=next_value
                """, actor.tenantId(), rule.id(), scope, key);
        var rows = jdbc.queryForList("""
                SELECT next_value FROM code_rule_seq WHERE tenant_id=? AND rule_id=? AND scope_key=? AND cycle_key=?
                """ + (update ? " FOR UPDATE" : ""), Long.class, actor.tenantId(), rule.id(), scope, key);
        long number = rows.isEmpty() ? 1 : rows.getFirst();
        if (update) jdbc.update("UPDATE code_rule_seq SET next_value=next_value+1 WHERE tenant_id=? AND rule_id=? AND scope_key=? AND cycle_key=?",
                actor.tenantId(), rule.id(), scope, key);
        return number;
    }

    private CodePattern.Context context(ActorScope actor, LocalDate date, List<CodePattern.Segment> segments, Long category) {
        if (date.getYear() < 1000 || date.getYear() > 9999) throw CodePattern.invalid("业务日期超出编号支持范围");
        String company = segment(segments, "company_code") ? code(
                "SELECT code FROM company WHERE tenant_id=? AND id=? AND status='active'", actor.tenantId(), actor.companyId()) : null;
        String dept = segment(segments, "dept_code") ? code("""
                SELECT d.code FROM org_department d JOIN org_user_department m
                  ON m.tenant_id=d.tenant_id AND m.company_id=d.company_id AND m.department_id=d.id
                WHERE d.tenant_id=? AND d.company_id=? AND m.user_id=? AND m.is_primary=1 AND d.status='active'
                """, actor.tenantId(), actor.companyId(), actor.userId()) : null;
        String categoryCode = segment(segments, "category_code") && category != null ? code(
                "SELECT code FROM product_category WHERE tenant_id=? AND id=? AND status='active'", actor.tenantId(), category) : null;
        return new CodePattern.Context(date, company, dept, categoryCode);
    }

    private String code(String sql, Object... args) {
        return jdbc.queryForList(sql, String.class, args).stream().findFirst().orElse(null);
    }

    private boolean segment(List<CodePattern.Segment> segments, String type) {
        return segments.stream().anyMatch(s -> type.equals(s.type()));
    }

    private long validate(CompanyAccess actor, Input input) {
        if (input == null || input.target() == null || !master(input.target()) && !DOCUMENTS.containsValue(input.target()))
            throw CodePattern.invalid("编码适用类型无效");
        long company = "0".equals(input.companyId()) ? 0 : identifier(input.companyId());
        if (company != 0 && company != actor.companyId()) throw new ApiException(403, "FORBIDDEN", "不能维护其他公司的编码规则");
        if (master(input.target()) && company != 0) throw CodePattern.invalid("主数据编码在企业内唯一，请选择全公司通用");
        CodePattern.validate(input.segments(), input.resetCycle(), master(input.target()));
        if (master(input.target()) && input.segments().stream().anyMatch(s -> List.of("company_code","dept_code").contains(s.type())))
            throw CodePattern.invalid("企业共享的主数据编码不使用公司或部门段");
        if (PARTNER.equals(input.target()) && segment(input.segments(), "category_code"))
            throw CodePattern.invalid("往来单位没有物料分类");
        return company;
    }

    private Rule find(long tenant, long company, String target) {
        return query("WHERE tenant_id=? AND company_id=? AND target=?", tenant, company, target).stream().findFirst().orElse(null);
    }

    private List<Rule> query(String condition, Object... args) {
        return jdbc.query("SELECT id,company_id,target,segments,reset_cycle,enabled,version FROM code_rule " + condition,
                (row, i) -> new Rule(row.getString(1), row.getString(2), row.getString(3), json.readValue(row.getString(4), SEGMENTS),
                        row.getString(5), row.getBoolean(6), row.getLong(7)), args);
    }

    private static boolean master(String target) { return SKU.equals(target) || PARTNER.equals(target); }

    private static long identifier(String value) {
        if (value != null && value.matches("[1-9][0-9]{0,18}")) {
            try { return Long.parseLong(value); } catch (NumberFormatException outsideRange) { throw CodePattern.invalid("编号超出范围"); }
        }
        throw CodePattern.invalid("编号格式无效");
    }

    private static ApiException conflict() { return new ApiException(409, "VERSION_CONFLICT", "编码规则已变化，请刷新后继续"); }
}
