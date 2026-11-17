package cn.erp.ar;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

@Component
class ArSupport {
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final BusinessCommands commands;
    final AuditService audit;

    ArSupport(JdbcTemplate jdbc, ObjectMapper json, BusinessCommands commands, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.commands = commands;
        this.audit = audit;
    }

    void require(ActorScope a, String permission) {
        FinancialScope.require(a, permission);
    }

    void customer(ActorScope a, long id) {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM business_partner p JOIN partner_role r ON"
                            + " r.tenant_id=p.tenant_id AND r.partner_id=p.id WHERE p.tenant_id=?"
                            + " AND p.id=? AND p.status='active' AND r.role='customer' AND"
                            + " r.status='active'",
                        Long.class,
                        a.tenantId(),
                        id)
                != 1) throw missing();
    }

    Map<String, Object> row(ActorScope a, String table, long id, boolean lock) {
        if (!Set.of("ar_credit_limit", "credit_increase", "ar_collection_plan", "ar_bad_debt")
                .contains(table)) throw invalid("财务资源类型无效");
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + table
                                + " WHERE tenant_id=? AND company_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        a.tenantId(),
                        a.companyId(),
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    Map<String, Object> item(ActorScope a, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM partner_open_item WHERE tenant_id=? AND company_id=? AND"
                                + " id=? AND item_type='receivable' AND direction='D'"
                                + (lock ? " FOR UPDATE" : ""),
                        a.tenantId(),
                        a.companyId(),
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    void lockCustomer(ActorScope a, long customer) {
        jdbc.update(
                "INSERT INTO ar_customer_lock(id,tenant_id,company_id,partner_id) VALUES(?,?,?,?)"
                        + " ON DUPLICATE KEY UPDATE marker=marker",
                Ids.next(),
                a.tenantId(),
                a.companyId(),
                customer);
        jdbc.queryForObject(
                "SELECT id FROM ar_customer_lock WHERE tenant_id=? AND company_id=? AND"
                        + " partner_id=? FOR UPDATE",
                Long.class,
                a.tenantId(),
                a.companyId(),
                customer);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Object> execute(
            CompanyAccess a,
            String op,
            String key,
            Object input,
            Supplier<Map<String, Object>> work) {
        return commands.execute(a, op, key, input, 200, work, (Class) Map.class);
    }

    static LocalDate today() {
        return LocalDate.now(ZoneId.of("Asia/Shanghai"));
    }

    static LocalDate date(Object value) {
        return value instanceof java.sql.Date d
                ? d.toLocalDate()
                : value instanceof LocalDate d ? d : LocalDate.parse(value.toString());
    }

    static BigDecimal money(String value) {
        if (value == null || !value.matches("[0-9]{1,22}(?:\\.[0-9]{1,2})?"))
            throw invalid("请填写最多两位小数的非负金额");
        return new BigDecimal(value).setScale(2);
    }

    static BigDecimal decimal(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    static long number(Object value) {
        return value == null
                ? 0
                : value instanceof Number n ? n.longValue() : Long.parseLong(value.toString());
    }

    static void version(Long expected, Map<String, Object> row) {
        if (expected == null || expected != number(row.get("version")))
            throw new ApiException(409, "VERSION_CONFLICT", "资料已变化，请刷新后重试");
    }

    static String text(String value, int max, String label) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw invalid("请填写有效的" + label + "，最多" + max + "字");
        return value.trim();
    }

    static ApiException invalid(String text) {
        return new ApiException(400, "VALIDATION_FAILED", text);
    }

    static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "往来资料不存在或不在当前公司范围");
    }
}
