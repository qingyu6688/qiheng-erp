package cn.erp.ar;

import java.math.*;
import java.time.*;
import java.util.*;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

/** 坏账同时追加运营分录和核销事实，再经总账事件形成只读业务凭证。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class BadDebtService implements WorkflowBusiness {
    private static final String TYPE = "ar_bad_debt";
    private WorkflowPort workflow;

    @org.springframework.beans.factory.annotation.Autowired
    void workflow(WorkflowPort workflow) { this.workflow = workflow; }
    private final ArSupport s;
    private final AgingService aging;
    private final FinancialBookPort books;
    private final LedgerService ledger;
    private final SettlementService settlement;

    BadDebtService(
            ArSupport s,
            AgingService aging,
            FinancialBookPort books,
            LedgerService ledger,
            SettlementService settlement) {
        this.s = s;
        this.aging = aging;
        this.books = books;
        this.ledger = ledger;
        this.settlement = settlement;
    }

    public record Input(
            @Positive(message = "请选择客户") long customerId,
            @NotBlank(message = "请选择坏账处理类型") String kind,
            @NotBlank(message = "请选择认定方法") String method,
            Long openItemId,
            @NotBlank(message = "请输入金额") String amount,
            @NotNull(message = "请选择认定日期") LocalDate businessDate,
            @NotBlank(message = "请填写认定原因") String reason) {}

    public record Command(@NotNull(message = "请提供版本号") Long version, String reason) {}

    public List<Map<String, Object>> list(CompanyAccess a) {
        s.require(a, "bad_debt:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT b.*,p.name customer_name FROM ar_bad_debt b JOIN business_partner p"
                                + " ON p.tenant_id=b.tenant_id AND p.id=b.customer_id WHERE"
                                + " b.tenant_id=? AND b.company_id=? ORDER BY b.id DESC LIMIT 500",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    public Map<String, Object> estimate(CompanyAccess a, long customer, LocalDate asOf) {
        s.require(a, "bad_debt:view");
        s.customer(a, customer);
        enterprise(a);
        if (asOf == null || asOf.isAfter(today())) throw invalid("估计日期不能晚于今天");
        var config = aging.read(a);
        var details = new ArrayList<Map<String, Object>>();
        BigDecimal target = BigDecimal.ZERO;
        for (var item :
                s.jdbc.queryForList(
                        "SELECT id,open_amount,aging_date FROM partner_open_item WHERE tenant_id=?"
                            + " AND company_id=? AND partner_id=? AND item_type='receivable' AND"
                            + " direction='D' AND open_amount>0 AND aging_date<=? ORDER BY"
                            + " aging_date,id",
                        a.tenantId(),
                        a.companyId(),
                        customer,
                        asOf)) {
            long days =
                    java.time.temporal.ChronoUnit.DAYS.between(date(item.get("aging_date")), asOf);
            int bucket = 0;
            while (bucket < config.bounds().size() && days > config.bounds().get(bucket)) bucket++;
            var amount =
                    decimal(item.get("open_amount"))
                            .multiply(new BigDecimal(config.rates().get(bucket)))
                            .setScale(2, RoundingMode.HALF_UP);
            target = target.add(amount);
            details.add(
                    Map.of(
                            "openItemId",
                            item.get("id").toString(),
                            "openAmount",
                            item.get("open_amount").toString(),
                            "days",
                            days,
                            "rate",
                            config.rates().get(bucket),
                            "provision",
                            amount.toPlainString()));
        }
        BigDecimal existing = allowance(a, customer, asOf);
        return Map.of(
                "asOf",
                asOf,
                "target",
                target.toPlainString(),
                "existing",
                existing.toPlainString(),
                "suggestedIncrease",
                target.subtract(existing).max(BigDecimal.ZERO).toPlainString(),
                "suggestedRelease",
                existing.subtract(target).max(BigDecimal.ZERO).toPlainString(),
                "details",
                details,
                "notice",
                "账龄比例仅为估计工具，须由财务结合可回收性和适用准则复核；本接口不自动记账");
    }

    @Transactional
    public Map<String, Object> create(CompanyAccess a, String key, Input input) {
        s.require(a, "bad_debt:edit");
        s.customer(a, input.customerId());
        return s.execute(
                a,
                "bad_debt.create",
                key,
                input,
                () -> {
                    requireBook(a);
                    if (!Set.of("provision", "release", "write_off")
                                    .contains(Objects.toString(input.kind(), ""))
                            || !Set.of("aging", "individual", "actual")
                                    .contains(Objects.toString(input.method(), "")))
                        throw invalid("坏账处理类型或估计方法无效");
                    var amount = money(input.amount());
                    if (amount.signum() <= 0
                            || input.businessDate() == null
                            || input.businessDate().isAfter(today()))
                        throw invalid("坏账金额须为正，业务日期不能晚于今天");
                    String reason = text(input.reason(), 500, "认定依据");
                    if (!input.kind().equals("write_off")) enterprise(a);
                    if (input.kind().equals("write_off") && !input.method().equals("actual")
                            || !input.kind().equals("write_off") && input.method().equals("actual"))
                        throw invalid("实际核销请选择实际损失，准备计提或转回请选择账龄比例或个别认定");
                    if (input.kind().equals("write_off")) {
                        if (input.openItemId() == null) throw invalid("请选择被核销应收未清项");
                        var item = s.item(a, input.openItemId(), false);
                        if (number(item.get("partner_id")) != input.customerId())
                            throw invalid("未清项客户不一致");
                        if (amount.compareTo(decimal(item.get("open_amount"))) > 0)
                            throw invalid("核销金额超过应收未清余额");
                    } else if (input.openItemId() != null) throw invalid("计提不直接核销未清项");
                    Object snapshot =
                            input.method().equals("aging")
                                    ? estimate(a, input.customerId(), input.businessDate())
                                    : null;
                    long id = Ids.next();
                    s.jdbc.update(
                            "INSERT INTO"
                                + " ar_bad_debt(id,tenant_id,company_id,customer_id,kind,method,open_item_id,amount,business_date,estimate_snapshot,reason,created_by)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenantId(),
                            a.companyId(),
                            input.customerId(),
                            input.kind(),
                            input.method(),
                            input.openItemId(),
                            amount,
                            input.businessDate(),
                            snapshot == null ? null : s.json.writeValueAsString(snapshot),
                            reason,
                            a.userId());
                    var result = ApiRows.row(s.row(a, "ar_bad_debt", id, false));
                    s.audit.record(a, "bad_debt.create", "ar_bad_debt", id, null, result);
                    return result;
                });
    }

    @Transactional
    public Map<String, Object> change(
            CompanyAccess a, long id, String action, String key, Command input) {
        if (!Set.of("submit", "approve", "reject").contains(action)) throw invalid("坏账操作无效");
        s.require(a, action.equals("submit") ? "bad_debt:edit" : "bad_debt:approve");
        s.row(a, "ar_bad_debt", id, false);
        return s.execute(
                a,
                "bad_debt." + action,
                key,
                Map.of("id", id, "input", input),
                () -> {
                    workflow.guard(a, TYPE, id, action);
                    var first = s.row(a, "ar_bad_debt", id, false);
                    long period = openPeriod(a, date(first.get("business_date")));
                    s.lockCustomer(a, number(first.get("customer_id")));
                    var before = s.row(a, "ar_bad_debt", id, true);
                    version(input.version(), before);
                    String status = before.get("status").toString();
                    if (action.equals("submit")) {
                        if (!Set.of("draft", "rejected").contains(status))
                            throw invalid("当前状态不能提交");
                        s.jdbc.update(
                                "UPDATE ar_bad_debt SET status='pending',version=version+1 WHERE"
                                        + " tenant_id=? AND company_id=? AND id=?",
                                a.tenantId(),
                                a.companyId(),
                                id);
                        workflow.start(a, TYPE, id, Map.of());
                    } else {
                        if (!status.equals("pending")) throw invalid("当前申请不在待审状态");
                        if (number(before.get("created_by")) == a.userId())
                            throw new ApiException(422, "TWO_PERSON_REQUIRED", "坏账认定须由另一人复核");
                        if (action.equals("reject")) {
                            text(input.reason(), 500, "驳回原因");
                            s.jdbc.update(
                                    "UPDATE ar_bad_debt SET status='rejected',version=version+1"
                                            + " WHERE tenant_id=? AND company_id=? AND id=?",
                                    a.tenantId(),
                                    a.companyId(),
                                    id);
                        } else post(a, id, before, period);
                    }
                    var after = ApiRows.row(s.row(a, "ar_bad_debt", id, false));
                    s.audit.record(a, "bad_debt." + action, "ar_bad_debt", id, before, after);
                    return after;
                });
    }

    @Override
    public String businessType() { return TYPE; }

    @Override
    public Map<String, Object> snapshot(ActorScope scope, long id) {
        var actor = (CompanyAccess) scope;
        s.require(actor, "bad_debt:view");
        var row = s.row(actor, TYPE, id, false);
        return Map.of("documentNo", Long.toString(id), "title", "坏账处理 " + id,
                "createdBy", row.get("created_by"), "totalAmount", row.get("amount").toString());
    }

    @Override
    @Transactional
    public void decide(ActorScope scope, long id, String outcome, String comment, String key) {
        if (!WorkflowContext.allows(scope, TYPE, id)) throw new ApiException(403, "FORBIDDEN", "业务回调缺少流程上下文");
        var actor = (CompanyAccess) scope;
        var row = s.row(actor, TYPE, id, false);
        change(actor, id, outcome.equals("approved") ? "approve" : "reject", key,
                new Command(number(row.get("version")), Objects.toString(comment, "流程未通过")));
    }

    private void post(CompanyAccess a, long id, Map<String, Object> row, long period) {
        books.lockControls(a);
        var book = requireBook(a);
        long customer = number(row.get("customer_id"));
        BigDecimal amount = decimal(row.get("amount"));
        String kind = row.get("kind").toString();
        String debit, credit;
        if (Set.of("provision", "release").contains(kind)) {
            enterprise(a);
            debit = kind.equals("release") ? "BAD_DEBT_ALLOWANCE" : "BAD_DEBT_EXPENSE";
            credit = kind.equals("release") ? "BAD_DEBT_EXPENSE" : "BAD_DEBT_ALLOWANCE";
            if (kind.equals("release")
                    && allowance(a, customer, date(row.get("business_date"))).compareTo(amount) < 0)
                throw invalid("转回金额不能超过已有准备余额");
            if (row.get("method").equals("aging")) {
                var current = estimate(a, customer, date(row.get("business_date")));
                if (amount.compareTo(
                                new BigDecimal(
                                        current.get(
                                                        kind.equals("release")
                                                                ? "suggestedRelease"
                                                                : "suggestedIncrease")
                                                .toString()))
                        > 0) throw invalid("应收或准备余额已变化，当前计提超过最新账龄建议，请重新认定");
            }
        } else {
            var item = s.item(a, number(row.get("open_item_id")), true);
            if (number(item.get("partner_id")) != customer
                    || amount.compareTo(decimal(item.get("open_amount"))) > 0)
                throw invalid("应收未清余额已变化，请重新认定");
            if (book.standard().equals("enterprise")) {
                if (allowance(a, customer, date(row.get("business_date"))).compareTo(amount) < 0)
                    throw invalid("坏账准备不足，请先按财务认定补提准备");
                debit = "BAD_DEBT_ALLOWANCE";
            } else debit = "BAD_DEBT_LOSS";
            credit = "AR";
        }
        long journal =
                ledger.post(
                        a,
                        new LedgerService.Posting(
                                "ar_bad_debt",
                                id,
                                0,
                                period,
                                date(row.get("business_date")),
                                List.of(
                                        new LedgerService.Line(
                                                debit, "D", amount, customer, null, null, null,
                                                null, "坏账认定"),
                                        new LedgerService.Line(
                                                credit, "C", amount, customer, null, null, null,
                                                null, "坏账认定"))));
        s.jdbc.update(
                "UPDATE ar_bad_debt SET"
                        + " status='posted',approved_by=?,journal_id=?,version=version+1 WHERE"
                        + " tenant_id=? AND company_id=? AND id=?",
                a.userId(),
                journal,
                a.tenantId(),
                a.companyId(),
                id);
        if (kind.equals("write_off")) {
            long creditItem =
                    settlement.open(
                            a,
                            new SettlementService.OpenItem(
                                    "bad_debt",
                                    id,
                                    customer,
                                    "C",
                                    amount,
                                    date(row.get("business_date"))));
            long allocation =
                    settlement.allocate(
                            a,
                            number(row.get("open_item_id")),
                            creditItem,
                            amount,
                            "bad_debt_write_off",
                            "ar_bad_debt",
                            id);
            s.jdbc.update(
                    "UPDATE ar_bad_debt SET allocation_id=? WHERE tenant_id=? AND company_id=? AND"
                            + " id=?",
                    allocation,
                    a.tenantId(),
                    a.companyId(),
                    id);
        }
    }

    private FinancialBookPort.Info requireBook(ActorScope a) {
        var book = books.information(a);
        if (book == null || !"active".equals(book.status()) || !book.confirmed())
            throw invalid("请先启用当前公司的财务账套");
        return book;
    }

    private void enterprise(ActorScope a) {
        if (!requireBook(a).standard().equals("enterprise"))
            throw invalid("小企业会计准则不计提资产减值准备，实际坏账请按核销处理");
    }

    private BigDecimal allowance(ActorScope a, long customer, LocalDate asOf) {
        var base = books.allowanceBase(a, customer, asOf);
        if (base == null) throw invalid("账套缺少坏账准备控制余额");
        return base.amount()
                .add(
                        s.jdbc.queryForObject(
                                "SELECT COALESCE(SUM(IF(l.direction='C',l.amount,-l.amount)),0)"
                                    + " FROM journal_line l JOIN journal j ON"
                                    + " j.tenant_id=l.tenant_id AND j.company_id=l.company_id AND"
                                    + " j.id=l.journal_id WHERE l.tenant_id=? AND l.company_id=?"
                                    + " AND l.partner_id=? AND l.account_code='BAD_DEBT_ALLOWANCE'"
                                    + " AND j.business_date>=? AND j.business_date<=?",
                                BigDecimal.class,
                                a.tenantId(),
                                a.companyId(),
                                customer,
                                base.start(),
                                asOf));
    }

    private long openPeriod(ActorScope a, LocalDate date) {
        var rows =
                s.jdbc.queryForList(
                        "SELECT id,status FROM accounting_period WHERE tenant_id=? AND company_id=?"
                                + " AND ? BETWEEN start_date AND end_date FOR SHARE",
                        a.tenantId(),
                        a.companyId(),
                        date);
        if (rows.size() != 1 || !"open".equals(rows.getFirst().get("status")))
            throw new ApiException(422, "PERIOD_CLOSED", "业务日期须在唯一开放期间内");
        return number(rows.getFirst().get("id"));
    }
}
