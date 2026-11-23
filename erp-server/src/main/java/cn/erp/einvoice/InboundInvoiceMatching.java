package cn.erp.einvoice;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.shared.InboundInvoiceScope;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.einvoice.InboundInvoiceSources.number;
import static cn.erp.einvoice.InboundInvoiceSources.money;
import static cn.erp.einvoice.InboundInvoiceQueries.invalid;
import static cn.erp.einvoice.InboundInvoiceQueries.missing;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class InboundInvoiceMatching {
    public record Manual(@PositiveOrZero(message="版本号无效") Long version,
                  @NotNull(message="请选择供应商") @Positive(message="供应商编号无效") Long supplierId,
                  @NotBlank(message="请输入发票号码") @Size(max=64,message="发票号码最多64字") String invoiceNo,
                  @NotBlank(message="请输入销方识别号") @Size(max=32,message="销方识别号最多32字") String sellerTaxNo,
                  @Size(max=100,message="销方名称最多100字") String sellerName,
                  @NotNull(message="请选择开票日期") LocalDate invoiceDate,
                  @NotNull(message="请选择接收日期") LocalDate receivedDate,
                  @NotBlank(message="请输入价税合计") String amount,
                  @NotBlank(message="请输入税额") String taxAmount,
                  @Size(max=200,message="备注最多200字") String remark) {}
    public record Line(@NotNull(message="请选择应付") @Positive(message="应付编号无效") Long payableId,
                @NotBlank(message="请输入票面未税额") String invoiceUntaxedAmount,
                @NotBlank(message="请输入票面税额") String invoiceTaxAmount,
                @NotBlank(message="请输入应付覆盖未税额") String payableUntaxedAmount,
                @NotBlank(message="请输入应付覆盖税额") String payableTaxAmount) {}
    public record Allocation(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                      @NotNull(message="请提供核对分配") @Size(max=200,message="最多核对200笔应付") List<@NotNull(message="核对行不能为空") @Valid Line> lines) {}
    public record Void(@NotNull(message="请提供版本号") @PositiveOrZero(message="版本号无效") Long version,
                @NotBlank(message="请填写作废原因") @Size(max=200,message="原因最多200字") String reason) {}
    private record Plan(long supplier, InboundInvoiceAmounts.Result amounts, BigDecimal tolerance) {}
    private final JdbcTemplate jdbc;
    private final InboundInvoiceQueries queries;
    private final InboundInvoiceSources sources;
    private final InboundInvoiceCoordination coordination;
    private final BusinessCommands commands;
    private final AuditService audit;

    InboundInvoiceMatching(JdbcTemplate jdbc, InboundInvoiceQueries queries, InboundInvoiceSources sources,
            InboundInvoiceCoordination coordination, BusinessCommands commands, AuditService audit) {
        this.jdbc = jdbc; this.queries = queries; this.sources = sources; this.coordination = coordination; this.commands = commands; this.audit = audit;
    }

    public Map<String, Object> detail(CompanyAccess actor, long id) { require(actor, "einv:inbound"); return queries.detail(actor, id); }
    public Map<String, Object> history(CompanyAccess actor, long id, Long cursor, int limit) { require(actor, "einv:inbound"); return queries.history(actor, id, cursor, limit); }
    public Map<String, Object> candidates(CompanyAccess actor, long id, Long cursor, int limit) {
        require(actor, "einv:inbound"); var invoice = queries.head(actor, id, false); normal(invoice); return sources.candidates(actor, invoice, cursor, limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> register(CompanyAccess actor, String key, Manual input) {
        require(actor, "einv:inbound_register");
        return execute(actor, "inbound_register", key, input, 201, () -> {
            coordination.lock(actor); var supplier = validateManual(actor, input); long id = Ids.next();
            try {
                jdbc.update("""
                        INSERT INTO einv_inbound(id,tenant_id,company_id,invoice_no,seller_name,seller_tax_no,invoice_date,amount,tax_amount,status,source,supplier_id,received_date,remark)
                        VALUES(?,?,?,?,?,?,?,?,?,'normal','manual',?,?,?)
                        """, id, actor.tenantId(), actor.companyId(), input.invoiceNo().trim(), sellerName(input, supplier), input.sellerTaxNo().trim(),
                        input.invoiceDate(), InboundInvoiceAmounts.parseMoney(input.amount()), InboundInvoiceAmounts.parseMoney(input.taxAmount()), input.supplierId(), input.receivedDate(), input.remark());
            } catch (DuplicateKeyException duplicate) { throw duplicate(); }
            return audited(actor, id, "inbound_register", null);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> update(CompanyAccess actor, long id, String key, Manual input) {
        require(actor, "einv:inbound_register"); queries.head(actor, id, false);
        return execute(actor, "inbound_update", key, Map.of("id", id, "input", input), 200, () -> {
            coordination.lock(actor); var before = queries.head(actor, id, true); InboundInvoiceQueries.version(before, input.version()); manual(before); normal(before);
            if (!queries.allocations(actor, id).isEmpty()) throw new ApiException(422, "INVOICE_ALREADY_MATCHED", "请先解除当前核对，再更正手工发票");
            var supplier = validateManual(actor, input);
            try {
                jdbc.update("""
                        UPDATE einv_inbound SET invoice_no=?,seller_name=?,seller_tax_no=?,invoice_date=?,amount=?,tax_amount=?,supplier_id=?,received_date=?,remark=?,
                          payable_id=NULL,match_notice=NULL,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?
                        """, input.invoiceNo().trim(), sellerName(input, supplier), input.sellerTaxNo().trim(), input.invoiceDate(), InboundInvoiceAmounts.parseMoney(input.amount()),
                        InboundInvoiceAmounts.parseMoney(input.taxAmount()), input.supplierId(), input.receivedDate(), input.remark(), actor.tenantId(), actor.companyId(), id);
            } catch (DuplicateKeyException duplicate) { throw duplicate(); }
            return audited(actor, id, "inbound_update", before);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> voidManual(CompanyAccess actor, long id, String key, Void input) {
        require(actor, "einv:inbound_void"); queries.head(actor, id, false);
        if (input.reason() == null || input.reason().isBlank() || input.reason().length() > 200) throw invalid("请填写作废原因，最多200字");
        return execute(actor, "inbound_void", key, Map.of("id", id, "input", input), 200, () -> {
            coordination.lock(actor); var before = queries.head(actor, id, true); InboundInvoiceQueries.version(before, input.version()); manual(before); normal(before);
            coordination.releaseRows(actor, List.of(id), "手工发票作废：" + abbreviate(input.reason().trim(), 190));
            jdbc.update("UPDATE einv_inbound SET status='voided',payable_id=NULL,match_notice=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                    "手工发票已作废：" + input.reason().trim(), actor.tenantId(), actor.companyId(), id);
            return audited(actor, id, "inbound_void", before);
        });
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> preview(CompanyAccess actor, long id, Allocation input) {
        require(actor, "einv:inbound"); coordination.lock(actor);
        var invoice = queries.head(actor, id, false); InboundInvoiceQueries.version(invoice, input.version()); normal(invoice);
        return previewView(plan(actor, invoice, input.lines(), queries.tolerance(actor, true)));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> allocate(CompanyAccess actor, long id, String key, Allocation input) {
        require(actor, "einv:inbound"); queries.head(actor, id, false);
        return execute(actor, "inbound_allocate", key, Map.of("id", id, "input", input), 200, () -> save(actor, id, input.version(), input.lines()));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    Map<String, Object> legacy(CompanyAccess actor, long id, Long version, Long payableId, String key, Object originalPayload) {
        require(actor, "einv:inbound"); queries.head(actor, id, false);
        return execute(actor, "inbound_match", key, originalPayload, 200, () -> {
            coordination.lock(actor); var invoice = queries.head(actor, id, true); InboundInvoiceQueries.version(invoice, version); normal(invoice);
            if (payableId == null) return save(actor, id, version, List.of());
            var tolerance = queries.tolerance(actor, true);
            var position = sources.selected(actor, Set.of(payableId), LocalDate.now(EInvoiceService.ZONE), id).get(payableId);
            if (position == null) throw missing();
            var available = position.available();
            var iu = money(invoice, "amount").subtract(money(invoice, "tax_amount")); var it = money(invoice, "tax_amount");
            boolean tail = iu.subtract(available.untaxedAmount()).abs().add(it.subtract(available.taxAmount()).abs()).compareTo(tolerance) <= 0;
            var line = new Line(payableId, iu.toPlainString(), it.toPlainString(), (tail ? available.untaxedAmount() : iu).toPlainString(), (tail ? available.taxAmount() : it).toPlainString());
            return save(actor, id, version, List.of(line));
        });
    }

    private Map<String, Object> save(CompanyAccess actor, long id, Long version, List<Line> lines) {
        coordination.lock(actor); var before = queries.head(actor, id, true); InboundInvoiceQueries.version(before, version); normal(before);
        validateLines(lines);
        for (long payable : lines.stream().map(Line::payableId).distinct().sorted().toList())
            if (jdbc.queryForList("SELECT id FROM payable WHERE tenant_id=? AND company_id=? AND id=? FOR UPDATE", Long.class, actor.tenantId(), actor.companyId(), payable).isEmpty()) throw missing();
        var plan = plan(actor, before, lines, queries.tolerance(actor, true));
        coordination.releaseRows(actor, List.of(id), lines.isEmpty() ? "手工解除核对" : "重新保存核对分配");
        var today = LocalDate.now(EInvoiceService.ZONE);
        for (var part : plan.amounts().lines()) jdbc.update("""
                INSERT INTO einv_inbound_match(id,tenant_id,company_id,invoice_id,payable_id,invoice_untaxed_amount,invoice_tax_amount,payable_untaxed_amount,payable_tax_amount,matched_date,matched_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), actor.tenantId(), actor.companyId(), id, part.payableId(), part.invoiceUntaxed(), part.invoiceTax(), part.payableUntaxed(), part.payableTax(), today, actor.userId());
        Object legacy = plan.amounts().fullyAllocated() && plan.amounts().lines().size() == 1 ? plan.amounts().lines().getFirst().payableId() : null;
        jdbc.update("UPDATE einv_inbound SET supplier_id=COALESCE(?,supplier_id),payable_id=?,match_notice=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                plan.supplier() > 0 ? plan.supplier() : null, legacy, lines.isEmpty() ? "已手工解除核对，请重新分配" : null, actor.tenantId(), actor.companyId(), id);
        return audited(actor, id, "inbound_allocate", before);
    }

    private Plan plan(CompanyAccess actor, Map<String, Object> invoice, List<Line> lines, BigDecimal tolerance) {
        validateLines(lines);
        Set<Long> ids = lines.stream().map(Line::payableId).collect(Collectors.toSet());
        var positions = sources.selected(actor, ids, LocalDate.now(EInvoiceService.ZONE), number(invoice, "id"));
        if (!positions.keySet().containsAll(ids)) throw missing();
        long supplier = invoice.get("supplier_id") != null ? number(invoice, "supplier_id") : positions.isEmpty() ? 0 : positions.values().iterator().next().supplierId();
        if (!lines.isEmpty()) validateSupplier(actor, invoice, supplier);
        var amounts = InboundInvoiceAmounts.calculate(supplier, money(invoice, "amount").subtract(money(invoice, "tax_amount")), money(invoice, "tax_amount"), tolerance,
                positions.values().stream().map(InboundInvoiceSources.Position::available).toList(), lines.stream().map(line -> new InboundInvoiceAmounts.Allocation(line.payableId(),
                        InboundInvoiceAmounts.parseMoney(line.invoiceUntaxedAmount()), InboundInvoiceAmounts.parseMoney(line.invoiceTaxAmount()),
                        InboundInvoiceAmounts.parseMoney(line.payableUntaxedAmount()), InboundInvoiceAmounts.parseMoney(line.payableTaxAmount()))).toList());
        return new Plan(supplier, amounts, tolerance);
    }

    private Map<String, Object> validateManual(CompanyAccess actor, Manual input) {
        if (input == null || input.supplierId() == null || input.supplierId() <= 0 || input.invoiceNo() == null || !input.invoiceNo().trim().matches("[A-Za-z0-9_/-]{1,64}")) throw invalid("请输入有效供应商和发票号码");
        if (input.sellerTaxNo() == null || !input.sellerTaxNo().trim().matches("[0-9A-Z]{8,32}")) throw invalid("请输入有效的销方纳税人识别号");
        var today = LocalDate.now(EInvoiceService.ZONE);
        if (input.invoiceDate() == null || input.receivedDate() == null || input.invoiceDate().isAfter(today) || input.receivedDate().isAfter(today) || input.receivedDate().isBefore(input.invoiceDate())) throw invalid("开票及接收日期不能晚于今天，接收日期不能早于开票日期");
        var amount = InboundInvoiceAmounts.parseMoney(input.amount()); var tax = InboundInvoiceAmounts.parseMoney(input.taxAmount());
        if (amount.signum() == 0 || tax.compareTo(amount) > 0) throw invalid("价税合计必须大于零，税额不能超过价税合计");
        if (input.remark() != null && input.remark().length() > 200 || input.sellerName() != null && input.sellerName().trim().length() > 100) throw invalid("销方名称最多100字，备注最多200字");
        var supplier = jdbc.queryForList("SELECT p.id,p.name,p.tax_no FROM business_partner p WHERE p.tenant_id=? AND p.id=? AND p.status='active' AND EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='supplier')", actor.tenantId(), input.supplierId()).stream().findFirst().orElseThrow(InboundInvoiceQueries::missing);
        checkTax(supplier.get("tax_no"), input.sellerTaxNo().trim());
        return supplier;
    }

    private void validateSupplier(CompanyAccess actor, Map<String, Object> invoice, long supplier) {
        var partner = jdbc.queryForList("SELECT tax_no FROM business_partner WHERE tenant_id=? AND id=?", actor.tenantId(), supplier).stream().findFirst().orElseThrow(InboundInvoiceQueries::missing);
        if ("einv".equals(invoice.get("source")) && (partner.get("tax_no") == null || partner.get("tax_no").toString().isBlank())) throw missing();
        checkTax(partner.get("tax_no"), invoice.get("seller_tax_no").toString());
    }
    private static void checkTax(Object tax, String invoiceTax) {
        if (tax != null && !tax.toString().isBlank() && !tax.toString().equalsIgnoreCase(invoiceTax)) throw new ApiException(422, "INVOICE_SUPPLIER_MISMATCH", "发票销方识别号与供应商资料不一致，请先核对单位资料");
    }
    private static void validateLines(List<Line> lines) {
        if (lines == null || lines.size() > InboundInvoiceAmounts.MAX_LINES || lines.stream().anyMatch(line -> line == null || line.payableId() == null || line.payableId() <= 0)) throw invalid("请提供有效核对行，最多200行");
    }
    private static String sellerName(Manual input, Map<String, Object> supplier) { return input.sellerName() == null || input.sellerName().isBlank() ? supplier.get("name").toString() : input.sellerName().trim(); }
    private static void manual(Map<String, Object> row) { if (!"manual".equals(row.get("source"))) throw new ApiException(422, "INVOICE_SOURCE_READ_ONLY", "数电同步的进项不能通过手工接口改写"); }
    private static void normal(Map<String, Object> row) { if (!"normal".equals(row.get("status"))) throw new ApiException(409, "STATE_CONFLICT", "已作废或红冲的发票不能继续核对或更正"); }
    private static void require(CompanyAccess actor, String permission) {
        InboundInvoiceScope.requireFull(actor);
        if (!actor.hasPermission("einv:view") || !actor.hasPermission("einv:inbound") || !actor.hasPermission(permission)) throw new ApiException(403, "FORBIDDEN", "没有执行当前进项发票操作的权限");
    }
    private Map<String, Object> audited(CompanyAccess actor, long id, String operation, Map<String, Object> before) {
        var result = queries.detail(actor, id); audit.record(actor, "einv." + operation, "einv_inbound", id, before == null ? null : ApiRows.row(before), result); return result;
    }
    private static Map<String, Object> previewView(Plan plan) {
        var result = new LinkedHashMap<String, Object>(); var amounts = plan.amounts();
        result.put("supplierId", plan.supplier() > 0 ? Long.toString(plan.supplier()) : null); result.put("tolerance", plan.tolerance().toPlainString());
        result.put("allocatedUntaxedAmount", amounts.allocatedUntaxed().toPlainString()); result.put("allocatedTaxAmount", amounts.allocatedTax().toPlainString());
        result.put("remainingUntaxedAmount", amounts.remainingUntaxed().toPlainString()); result.put("remainingTaxAmount", amounts.remainingTax().toPlainString());
        result.put("untaxedDifference", amounts.untaxedDifference().toPlainString()); result.put("taxDifference", amounts.taxDifference().toPlainString()); result.put("absoluteDifference", amounts.absoluteDifference().toPlainString());
        result.put("fullyAllocated", amounts.fullyAllocated()); result.put("matchStatus", amounts.matchStatus());
        result.put("items", amounts.lines().stream().map(part -> Map.of("payableId", Long.toString(part.payableId()), "invoiceUntaxedAmount", part.invoiceUntaxed().toPlainString(), "invoiceTaxAmount", part.invoiceTax().toPlainString(),
                "payableUntaxedAmount", part.payableUntaxed().toPlainString(), "payableTaxAmount", part.payableTax().toPlainString(), "untaxedDifference", part.untaxedDifference().toPlainString(), "taxDifference", part.taxDifference().toPlainString(), "payableStatus", part.payableStatus())).toList());
        result.put("notice", "额度按预览时计算，保存时持锁重新校验；核对不改变原应付和资金余额"); return result;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> execute(CompanyAccess actor, String action, String key, Object payload, int status, Supplier<Map<String, Object>> work) {
        return commands.execute(actor, "einv." + action, key, payload, status, work, (Class) Map.class);
    }
    private static ApiException duplicate() { return new ApiException(409, "INVOICE_NO_DUPLICATED", "当前公司已存在此发票号码，请先核对是否重复登记"); }
    private static String abbreviate(String value, int limit) { return value.length() <= limit ? value : value.substring(0, limit); }
}
