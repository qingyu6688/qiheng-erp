package cn.erp.gl;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

@Component
class GlSupport {
    static final List<String> AUX =
            List.of("customer", "supplier", "dept", "employee", "project", "sku", "custom");
    static final String AUX_COLUMNS =
            String.join(",", AUX.stream().map(n -> "aux_" + n + "_id").toList());
    final JdbcTemplate jdbc;
    final ObjectMapper json;
    final BusinessCommands commands;
    final AuditService audit;
    final LedgerService ledger;

    GlSupport(
            JdbcTemplate jdbc,
            ObjectMapper json,
            BusinessCommands commands,
            AuditService audit,
            LedgerService ledger) {
        this.jdbc = jdbc;
        this.json = json;
        this.commands = commands;
        this.audit = audit;
        this.ledger = ledger;
    }

    void require(ActorScope actor, String permission) {
        FinancialScope.require(actor, permission);
    }

    Map<String, Object> book(ActorScope actor, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM gl_account_book WHERE tenant_id=? AND company_id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        actor.tenantId(),
                        actor.companyId());
        if (rows.isEmpty()) throw new ApiException(422, "GL_BOOK_REQUIRED", "请先创建当前公司的账套");
        return rows.getFirst();
    }

    Map<String, Object> bookOrNull(ActorScope actor) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM gl_account_book WHERE tenant_id=? AND company_id=?",
                        actor.tenantId(),
                        actor.companyId());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Map<String, Object> active(ActorScope actor) {
        var book = book(actor, false);
        if (!"active".equals(book.get("status")))
            throw new ApiException(422, "GL_BOOK_INACTIVE", "账套尚未启用");
        if (book.get("confirmed_hash") == null)
            throw new ApiException(422, "GL_CONFIG_UNCONFIRMED", "财务配置已变化，请先核对并确认当前版本");
        return book;
    }

    void configurationChanged(ActorScope actor) {
        jdbc.update(
                "UPDATE gl_account_book SET"
                    + " config_revision=config_revision+1,confirmed_hash=NULL,confirmed_by=NULL,confirmed_at=NULL,version=version+1"
                    + " WHERE tenant_id=? AND company_id=?",
                actor.tenantId(),
                actor.companyId());
    }

    Map<String, Object> row(ActorScope actor, String table, long id, boolean lock) {
        if (!Set.of(
                        "gl_subject",
                        "gl_aux_type",
                        "gl_aux_item",
                        "gl_cash_flow_item",
                        "gl_business_mapping",
                        "gl_voucher_word",
                        "gl_digest",
                        "gl_voucher_template",
                        "gl_voucher",
                        "gl_closing_scheme",
                        "gl_report_template",
                        "gl_export_format")
                .contains(table)) throw invalid("财务资源类型无效");
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + table
                                + " WHERE tenant_id=? AND company_id=? AND id=?"
                                + (lock ? " FOR UPDATE" : ""),
                        actor.tenantId(),
                        actor.companyId(),
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    Map<String, Object> period(ActorScope actor, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? AND"
                                + " id=?"
                                + (lock ? " FOR SHARE" : ""),
                        actor.tenantId(),
                        actor.companyId(),
                        id);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    long openPeriod(ActorScope actor, LocalDate date) {
        if (date == null || date.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai"))))
            throw invalid("凭证日期须为今天或之前");
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM accounting_period WHERE tenant_id=? AND company_id=? AND ?"
                                + " BETWEEN start_date AND end_date ORDER BY start_date FOR SHARE",
                        actor.tenantId(),
                        actor.companyId(),
                        date);
        if (rows.size() != 1) throw invalid("凭证日期没有对应的唯一会计期间");
        var period = rows.getFirst();
        if (!"open".equals(period.get("status")))
            throw new ApiException(422, "PERIOD_CLOSED", "会计期间已关闭");
        return number(period.get("id"));
    }

    void lockPosting(ActorScope actor) {
        jdbc.update(
                "INSERT INTO gl_posting_lock(id,tenant_id,company_id) VALUES(?,?,?) ON DUPLICATE"
                        + " KEY UPDATE marker=marker",
                Ids.next(),
                actor.tenantId(),
                actor.companyId());
        jdbc.queryForObject(
                "SELECT id FROM gl_posting_lock WHERE tenant_id=? AND company_id=? FOR UPDATE",
                Long.class,
                actor.tenantId(),
                actor.companyId());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    Map<String, Object> execute(
            CompanyAccess actor,
            String operation,
            String key,
            Object input,
            Supplier<Map<String, Object>> work) {
        return commands.execute(actor, operation, key, input, 200, work, (Class) Map.class);
    }

    static void version(Long expected, Map<String, Object> row) {
        if (expected == null || expected != number(row.get("version")))
            throw new ApiException(409, "VERSION_CONFLICT", "财务资料已变化，请刷新后重试");
    }

    static BigDecimal money(String value, boolean negative) {
        return decimal(value, 2, negative);
    }

    static BigDecimal decimal(String value, int scale, boolean negative) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO.setScale(scale);
        if (value.length() > 40
                || !value.matches((negative ? "-?" : "") + "[0-9]+(?:\\.[0-9]{1," + scale + "})?"))
            throw invalid("请填写最多" + scale + "位小数的有效数值");
        var number = new BigDecimal(value).setScale(scale);
        if (number.precision() > 24) throw invalid("数值超出允许范围");
        return number;
    }

    static String text(String value, int max, String label) {
        if (value == null
                || value.isBlank()
                || value.length() > max
                || value.chars().anyMatch(Character::isISOControl))
            throw invalid("请填写有效的" + label + "，最多" + max + "字");
        return value.trim();
    }

    static String code(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_\\-]{1,40}"))
            throw invalid("编码限40位字母、数字、下划线或短横线");
        return value;
    }

    static long number(Object value) {
        if (value == null) return 0;
        if (value instanceof Boolean flag) return flag ? 1 : 0;
        return value instanceof Number number
                ? number.longValue()
                : Long.parseLong(value.toString());
    }

    static boolean flag(Object value) {
        return number(value) != 0;
    }

    static ApiException missing() {
        return new ApiException(404, "NOT_FOUND", "财务资料不存在或不在当前公司范围");
    }

    static ApiException invalid(String message) {
        return new ApiException(400, "VALIDATION_FAILED", message);
    }

    static Map<String, Object> view(Map<String, Object> row) {
        return ApiRows.row(row);
    }

    static LocalDate date(Object value) {
        return value instanceof java.sql.Date date
                ? date.toLocalDate()
                : value instanceof LocalDate date ? date : LocalDate.parse(value.toString());
    }

    static GlModels.Aux aux(Map<String, Object> row) {
        return new GlModels.Aux(
                number(row.get("aux_customer_id")),
                number(row.get("aux_supplier_id")),
                number(row.get("aux_dept_id")),
                number(row.get("aux_employee_id")),
                number(row.get("aux_project_id")),
                number(row.get("aux_sku_id")),
                number(row.get("aux_custom_id")));
    }

    static Map<String, Object> auxView(GlModels.Aux aux) {
        var output = new LinkedHashMap<String, Object>();
        for (int i = 0; i < AUX.size(); i++)
            output.put(AUX.get(i) + "Id", aux.values().get(i).toString());
        return output;
    }

    static Map<String, Object> lineView(GlModels.Line line) {
        var output = new LinkedHashMap<String, Object>();
        output.put("digest", line.digest());
        output.put("subjectId", line.subjectId().toString());
        output.put("direction", line.direction());
        output.put("amount", line.amount());
        output.put("quantity", line.quantity());
        output.put("unitPrice", line.unitPrice());
        output.put("aux", auxView(line.aux() == null ? GlModels.Aux.empty() : line.aux()));
        output.put(
                "cashFlowItemId",
                line.cashFlowItemId() == null ? null : line.cashFlowItemId().toString());
        return output;
    }
}
