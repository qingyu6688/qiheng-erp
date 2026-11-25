package cn.erp.invoicing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import cn.erp.shared.SalesInvoiceLock;
import cn.erp.shared.SalesInvoiceScope;

/** 手工红字是独立确认事实；申请和普通作废都不能冒充已完成红字。 */
@Service
@Transactional(readOnly = true)
public class ManualInvoiceRedService {
    public record Input(@Positive(message = "请选择原蓝字发票") long invoiceId,
                        @NotNull(message = "请提供原票版本") @PositiveOrZero(message = "原票版本无效") Long sourceVersion,
                        @NotBlank(message = "请输入红字发票号") @Size(max = 64, message = "红字票号最多64字") String redInvoiceNo,
                        @NotNull(message = "请选择红字日期") LocalDate redInvoiceDate,
                        @NotBlank(message = "请输入红字含税金额") String amount,
                        @NotBlank(message = "请输入红字税额") String taxAmount,
                        @NotBlank(message = "请填写红字原因") @Size(max = 500, message = "红字原因最多500字") String reason) {}
    public record Revision(@NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version) {}

    private final JdbcTemplate jdbc;
    private final InvoiceService invoices;
    private final SalesInvoiceLock invoiceLock;
    private final BusinessCommands commands;
    private final AuditService audit;

    public ManualInvoiceRedService(JdbcTemplate jdbc, InvoiceService invoices, SalesInvoiceLock invoiceLock,
                                   BusinessCommands commands, AuditService audit) {
        this.jdbc = jdbc; this.invoices = invoices; this.invoiceLock = invoiceLock;
        this.commands = commands; this.audit = audit;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> create(CompanyAccess actor, String key, Input input) {
        require(actor, "create");
        if (input == null) throw invalid("请填写手工红字登记");
        return execute(actor, "create", key, input, 201, () -> {
            invoiceLock.lock(actor);
            var blue = invoices.lockForRelease(actor, input.invoiceId());
            manual(blue);
            version(blue, input.sourceVersion());
            validate(input, blue);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM invoice_red_registration WHERE tenant_id=? AND company_id=? AND invoice_id=? AND status IN('pending','confirmed')",
                    Long.class, actor.tenantId(), actor.companyId(), input.invoiceId()) > 0)
                throw new ApiException(409, "INVOICE_RED_EXISTS", "原票已有待确认或已确认红字登记");
            long id = Ids.next();
            jdbc.update("""
                    INSERT INTO invoice_red_registration(id,tenant_id,company_id,invoice_id,red_invoice_no,red_invoice_date,
                      amount,tax_amount,reason,source_version,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                    """, id, actor.tenantId(), actor.companyId(), input.invoiceId(), input.redInvoiceNo().strip(), input.redInvoiceDate(),
                    money(input.amount()), money(input.taxAmount()), input.reason().strip(), input.sourceVersion(), actor.userId(), actor.userId());
            var result = get(actor, id);
            audit.record(actor, "invoice_red.create", "invoice_red_registration", id, null, result);
            return result;
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> command(CompanyAccess actor, long id, String action, String key, Revision input) {
        if (!Set.of("confirm", "cancel").contains(action)) throw invalid("红字登记操作无效");
        require(actor, action);
        if (input == null || input.version() == null || input.version() < 0) throw invalid("请提供有效版本号");
        get(actor, id);
        return execute(actor, action, key, Map.of("id", id, "input", input), 200, () -> {
            invoiceLock.lock(actor);
            var current = head(actor, id, false);
            var blue = invoices.lockForRelease(actor, number(current, "invoice_id"));
            var before = get(actor, id);
            current = head(actor, id, true);
            version(current, input.version());
            if (!"pending".equals(current.get("status"))) throw new ApiException(409, "STATE_CONFLICT", "当前红字登记不允许此操作");
            if (action.equals("cancel")) cancel(actor, current);
            else confirm(actor, current, blue);
            var after = get(actor, id);
            audit.record(actor, "invoice_red." + action, "invoice_red_registration", id, before, after);
            return after;
        });
    }

    private void confirm(CompanyAccess actor, Map<String, Object> red, Map<String, Object> blue) {
        if (!actor.allWarehouses() || !actor.orgScope().allDepartments())
            throw new ApiException(403, "FORBIDDEN", "红字财务确认需要当前公司的完整数据范围");
        if (number(red, "created_by") == actor.userId())
            throw new ApiException(422, "TWO_PERSON_REQUIRED", "红字复核人不能是登记人");
        manual(blue);
        version(blue, number(red, "source_version"));
        if (((BigDecimal) red.get("amount")).compareTo((BigDecimal) blue.get("amount")) != 0
                || ((BigDecimal) red.get("tax_amount")).compareTo((BigDecimal) blue.get("tax_amount")) != 0)
            throw new ApiException(409, "INVOICE_SOURCE_CHANGED", "原票金额已变化，请撤回后重新登记红字");
        // 旧票可能早已作废登记；补录真实红字时保留事实，不能再次释放额度。
        if ("registered".equals(blue.get("status"))) invoices.release(actor, number(blue, "id"));
        jdbc.update("UPDATE invoice_red_registration SET status='confirmed',confirmed_by=?,confirmed_at=UTC_TIMESTAMP(3),version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(), actor.userId(), actor.tenantId(), actor.companyId(), red.get("id"));
    }

    private void cancel(CompanyAccess actor, Map<String, Object> red) {
        if (number(red, "created_by") != actor.userId())
            throw new ApiException(403, "FORBIDDEN", "只有登记人可以撤回待确认红字");
        jdbc.update("UPDATE invoice_red_registration SET status='cancelled',version=version+1,updated_by=? WHERE tenant_id=? AND company_id=? AND id=?",
                actor.userId(), actor.tenantId(), actor.companyId(), red.get("id"));
    }

    public Map<String, Object> get(CompanyAccess actor, long id) {
        require(actor, "view");
        SalesInvoiceScope.red(jdbc, actor, id);
        var red = head(actor, id, false);
        var blue = SalesInvoiceScope.registration(jdbc, actor, number(red, "invoice_id"));
        var result = ApiRows.row(red);
        result.remove("activeInvoiceId");
        result.put("invoiceNo", blue.get("invoice_no"));
        result.put("invoiceStatus", blue.get("status"));
        result.put("partnerId", blue.get("partner_id").toString());
        result.put("lines", invoices.get(actor, number(red, "invoice_id")).get("lines"));
        var actions = new ArrayList<String>();
        if ("pending".equals(red.get("status"))) {
            if (actor.hasPermission("invoice_red:confirm") && number(red, "created_by") != actor.userId()
                    && actor.allWarehouses() && actor.orgScope().allDepartments()) actions.add("confirm");
            if (actor.hasPermission("invoice_red:cancel") && number(red, "created_by") == actor.userId()) actions.add("cancel");
        }
        result.put("allowedActions", actions);
        return result;
    }

    public Map<String, Object> list(CompanyAccess actor, Long invoiceId, String status, Long cursor, int limit) {
        require(actor, "view");
        if (limit < 1 || limit > 100 || cursor != null && cursor <= 0 || invoiceId != null && invoiceId <= 0)
            throw invalid("分页或原票参数无效");
        if (status != null && !Set.of("pending", "confirmed", "cancelled").contains(status)) throw invalid("红字登记状态无效");
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), cursor == null ? Long.MAX_VALUE : cursor));
        String filter = OrgOwnership.filter(actor, "b") + " AND " + SalesInvoiceScope.registrationCondition(actor, "b");
        if (invoiceId != null) { filter += " AND r.invoice_id=?"; args.add(invoiceId); }
        if (status != null) { filter += " AND r.status=?"; args.add(status); }
        args.add(limit + 1);
        var ids = jdbc.queryForList("SELECT r.id FROM invoice_red_registration r JOIN invoice_registration b ON b.tenant_id=r.tenant_id AND b.company_id=r.company_id AND b.id=r.invoice_id"
                + " WHERE r.tenant_id=? AND r.company_id=? AND r.id<?" + filter + " ORDER BY r.id DESC LIMIT ?", Long.class, args.toArray());
        var result = new LinkedHashMap<String, Object>();
        result.put("items", ids.stream().limit(limit).map(id -> get(actor, id)).toList());
        result.put("nextCursor", ids.size() > limit ? ids.get(limit - 1).toString() : null);
        return result;
    }

    private Map<String, Object> head(CompanyAccess actor, long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT * FROM invoice_red_registration WHERE tenant_id=? AND company_id=? AND id=?" + (lock ? " FOR UPDATE" : ""),
                actor.tenantId(), actor.companyId(), id);
        if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "红字登记不存在或无权访问");
        return rows.getFirst();
    }

    private void validate(Input input, Map<String, Object> blue) {
        String no = input.redInvoiceNo();
        if (no == null || no.isBlank() || no.strip().length() > 64 || no.strip().equals(blue.get("invoice_no")))
            throw invalid("请填写与原蓝字不同的红字票号，最多64字");
        LocalDate day = input.redInvoiceDate();
        if (day == null || day.isAfter(LocalDate.now(ZoneId.of("Asia/Shanghai")))
                || day.isBefore(((java.sql.Date) blue.get("invoice_date")).toLocalDate())) throw invalid("红字日期不能早于原票或晚于今天");
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 500) throw invalid("请填写红字原因，最多500字");
        if (money(input.amount()).compareTo((BigDecimal) blue.get("amount")) != 0
                || money(input.taxAmount()).compareTo((BigDecimal) blue.get("tax_amount")) != 0)
            throw invalid("本阶段须按原蓝字的完整含税金额和税额登记红字");
    }

    private static void manual(Map<String, Object> blue) {
        if (!"sales".equals(blue.get("invoice_type")) || blue.get("einvoice_request_id") != null)
            throw new ApiException(422, "MANUAL_INVOICE_REQUIRED", "这里只登记手工销项红字，数电发票请使用原红字开具流程");
        if (!Set.of("registered", "voided").contains(blue.get("status"))) throw invalid("原票状态无效");
    }

    private static void require(CompanyAccess actor, String action) {
        if (!actor.hasPermission("invoice_red:view") || !actor.hasPermission("invoice_registration:view") || !actor.hasPermission("invoice_red:" + action))
            throw new ApiException(403, "FORBIDDEN", "无权查看或处理手工销项红字登记");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> execute(CompanyAccess actor, String action, String key, Object input, int status, Supplier<Map<String, Object>> work) {
        return commands.execute(actor, "invoice_red." + action, key, input, status, work, (Class) Map.class);
    }
    private static void version(Map<String, Object> row, Long expected) {
        if (expected == null || expected < 0) throw invalid("请提供有效版本号");
        if (number(row, "version") != expected) throw new ApiException(409, "VERSION_CONFLICT", "发票或红字登记已变化，请刷新后重试");
    }
    private static BigDecimal money(String text) {
        if (text == null || text.length() > 26 || !text.matches("[0-9]+(?:\\.[0-9]{1,2})?")) throw invalid("金额须为非负数且最多两位小数");
        BigDecimal value = new BigDecimal(text).setScale(2);
        if (value.precision() > 24) throw invalid("金额超过可用范围");
        return value;
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
    private static ApiException invalid(String text) { return new ApiException(400, "VALIDATION_FAILED", text); }
}
