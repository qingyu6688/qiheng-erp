package cn.erp.ar;

import java.time.LocalDate;
import java.util.*;
import jakarta.validation.constraints.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
public class CreditAdministration {
    private final ArSupport s;
    private final CreditService credit;
    private final WorkflowPort workflow;

    CreditAdministration(ArSupport s, CreditService credit, WorkflowPort workflow) {
        this.s = s;
        this.credit = credit;
        this.workflow = workflow;
    }

    public record Limit(
            @Positive(message = "请选择客户") long partnerId,
            @NotBlank(message = "请输入额度") String limitAmount,
            int overdueDaysLimit,
            @NotBlank(message = "请选择信用策略") String policy,
            @NotNull(message = "请选择生效日期") LocalDate validFrom,
            LocalDate validTo,
            Long version) {}

    public record Increase(
            @Positive(message = "请选择客户") @Positive(message = "请选择客户") long partnerId,
            @NotBlank(message = "请输入调增金额") String amount,
            @NotNull(message = "请选择生效日期") LocalDate validFrom,
            @NotNull(message = "请选择截止日期") LocalDate validTo,
            @NotBlank(message = "请填写调增原因") String reason) {}

    public record Command(
            @NotNull(message = "请提供版本号") Long version,
            String reason,
            Map<String, List<Long>> workflowAssignees) {}

    public List<Map<String, Object>> limits(CompanyAccess a) {
        s.require(a, "credit_limit:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT l.*,p.code partner_code,p.name partner_name FROM ar_credit_limit l"
                            + " JOIN business_partner p ON p.tenant_id=l.tenant_id AND"
                            + " p.id=l.partner_id WHERE l.tenant_id=? AND l.company_id=? ORDER BY"
                            + " l.id",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    public List<Map<String, Object>> increases(CompanyAccess a) {
        s.require(a, "credit_limit:view");
        return s
                .jdbc
                .queryForList(
                        "SELECT l.*,p.name partner_name FROM credit_increase l JOIN"
                            + " business_partner p ON p.tenant_id=l.tenant_id AND p.id=l.partner_id"
                            + " WHERE l.tenant_id=? AND l.company_id=? ORDER BY l.id DESC LIMIT"
                            + " 500",
                        a.tenantId(),
                        a.companyId())
                .stream()
                .map(ApiRows::row)
                .toList();
    }

    public Map<String, Object> increase(CompanyAccess a, long id) {
        s.require(a, "credit_limit:view");
        return ApiRows.row(s.row(a, "credit_increase", id, false));
    }

    public Map<String, Object> exposure(CompanyAccess a, long customer) {
        s.require(a, "credit_limit:view");
        s.customer(a, customer);
        return credit.exposure(a, customer);
    }

    @Transactional
    public Map<String, Object> save(CompanyAccess a, Long id, String key, Limit input) {
        s.require(a, "credit_limit:edit");
        if (id != null) s.row(a, "ar_credit_limit", id, false);
        s.customer(a, input.partnerId());
        return s.execute(
                a,
                "credit_limit.save",
                key,
                Map.of("id", id == null ? 0 : id, "input", input),
                () -> {
                    if (!Set.of("warn", "approval", "block")
                                    .contains(Objects.toString(input.policy(), ""))
                            || input.overdueDaysLimit() < 0
                            || input.overdueDaysLimit() > 36500
                            || input.validFrom() == null
                            || input.validTo() != null
                                    && input.validTo().isBefore(input.validFrom()))
                        throw invalid("信用策略、有效期或超期天数无效");
                    var amount = money(input.limitAmount());
                    long target = id == null ? Ids.next() : id;
                    Map<String, Object> before =
                            id == null ? null : s.row(a, "ar_credit_limit", id, true);
                    if (before != null) {
                        version(input.version(), before);
                        if (number(before.get("partner_id")) != input.partnerId())
                            throw invalid("额度记录不能更换客户");
                        s.jdbc.update(
                                "UPDATE ar_credit_limit SET"
                                    + " limit_amount=?,overdue_days_limit=?,policy=?,valid_from=?,valid_to=?,version=version+1"
                                    + " WHERE tenant_id=? AND company_id=? AND id=?",
                                amount,
                                input.overdueDaysLimit(),
                                input.policy(),
                                input.validFrom(),
                                input.validTo(),
                                a.tenantId(),
                                a.companyId(),
                                id);
                    } else
                        s.jdbc.update(
                                "INSERT INTO"
                                    + " ar_credit_limit(id,tenant_id,company_id,partner_id,limit_amount,overdue_days_limit,policy,valid_from,valid_to)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                target,
                                a.tenantId(),
                                a.companyId(),
                                input.partnerId(),
                                amount,
                                input.overdueDaysLimit(),
                                input.policy(),
                                input.validFrom(),
                                input.validTo());
                    var after = ApiRows.row(s.row(a, "ar_credit_limit", target, false));
                    s.audit.record(
                            a, "credit_limit.save", "ar_credit_limit", target, before, after);
                    return after;
                });
    }

    @Transactional
    public Map<String, Object> createIncrease(CompanyAccess a, String key, Increase input) {
        s.require(a, "credit_limit:edit");
        s.customer(a, input.partnerId());
        return s.execute(
                a,
                "credit_increase.create",
                key,
                input,
                () -> {
                    var amount = money(input.amount());
                    if (amount.signum() <= 0
                            || input.validFrom() == null
                            || input.validTo() == null
                            || input.validTo().isBefore(input.validFrom())
                            || input.validTo().isBefore(today())
                            || input.validTo().isAfter(today().plusYears(1)))
                        throw invalid("临时调增须为正金额，有效期不能超过一年或已经失效");
                    credit.lock(a, input.partnerId());
                    if (s.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ar_credit_limit WHERE tenant_id=? AND"
                                            + " company_id=? AND partner_id=?",
                                    Long.class,
                                    a.tenantId(),
                                    a.companyId(),
                                    input.partnerId())
                            != 1) throw invalid("请先配置客户基础额度");
                    long id = Ids.next();
                    s.jdbc.update(
                            "INSERT INTO"
                                + " credit_increase(id,tenant_id,company_id,partner_id,amount,business_date,valid_from,valid_to,reason,created_by,owner_user_id,owner_department_id)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                            id,
                            a.tenantId(),
                            a.companyId(),
                            input.partnerId(),
                            amount,
                            today(),
                            input.validFrom(),
                            input.validTo(),
                            text(input.reason(), 500, "调增原因"),
                            a.userId(),
                            a.userId(),
                            a.orgScope().primaryDepartmentId());
                    var after = ApiRows.row(s.row(a, "credit_increase", id, false));
                    s.audit.record(a, "credit_increase.create", "credit_increase", id, null, after);
                    return after;
                });
    }

    @Transactional
    public Map<String, Object> changeIncrease(
            CompanyAccess a, long id, String action, String key, Command input) {
        if (!Set.of("submit", "approve", "reject", "cancel").contains(action))
            throw invalid("调增操作无效");
        s.require(
                a,
                Set.of("approve", "reject").contains(action)
                        ? "credit_limit:approve"
                        : "credit_limit:edit");
        s.row(a, "credit_increase", id, false);
        return s.execute(
                a,
                "credit_increase." + action,
                key,
                Map.of("id", id, "input", input),
                () -> {
                    var first = s.row(a, "credit_increase", id, false);
                    workflow.guard(a, "credit_increase", id, action);
                    credit.lock(a, number(first.get("partner_id")));
                    var before = s.row(a, "credit_increase", id, true);
                    version(input.version(), before);
                    String status = before.get("status").toString();
                    switch (action) {
                        case "submit" -> {
                            if (!Set.of("draft", "rejected").contains(status))
                                throw invalid("当前状态不能提交");
                            s.jdbc.update(
                                    "UPDATE credit_increase SET status='pending',version=version+1"
                                            + " WHERE tenant_id=? AND company_id=? AND id=?",
                                    a.tenantId(),
                                    a.companyId(),
                                    id);
                            workflow.start(a, "credit_increase", id, input.workflowAssignees());
                        }
                        case "approve", "reject" -> {
                            if (!status.equals("pending")) throw invalid("调增申请不在待审状态");
                            if (number(before.get("created_by")) == a.userId())
                                throw new ApiException(422, "TWO_PERSON_REQUIRED", "调增申请须由另一人审批");
                            if (action.equals("approve")
                                    && date(before.get("valid_to")).isBefore(today()))
                                throw invalid("调增有效期已经结束");
                            if (action.equals("reject")) text(input.reason(), 500, "驳回原因");
                            s.jdbc.update(
                                    "UPDATE credit_increase SET"
                                        + " status=?,approved_by=?,approved_at=IF(?='approved',UTC_TIMESTAMP(3),NULL),version=version+1"
                                        + " WHERE tenant_id=? AND company_id=? AND id=?",
                                    action.equals("approve") ? "approved" : "rejected",
                                    a.userId(),
                                    action.equals("approve") ? "approved" : "rejected",
                                    a.tenantId(),
                                    a.companyId(),
                                    id);
                        }
                        case "cancel" -> {
                            if (!Set.of("draft", "rejected", "approved").contains(status))
                                throw invalid("请先在审批中心撤销正在审批的调增申请");
                            if (status.equals("approved")) {
                                s.require(a, "credit_limit:approve");
                                if (number(before.get("created_by")) == a.userId())
                                    throw invalid("已审批额度由另一名复核人办理取消");
                            }
                            text(input.reason(), 500, "取消原因");
                            s.jdbc.update(
                                    "UPDATE credit_increase SET"
                                        + " status='cancelled',version=version+1 WHERE tenant_id=?"
                                        + " AND company_id=? AND id=?",
                                    a.tenantId(),
                                    a.companyId(),
                                    id);
                        }
                        default -> throw invalid("调增操作无效");
                    }
                    var after = ApiRows.row(s.row(a, "credit_increase", id, false));
                    s.audit.record(
                            a,
                            "credit_increase." + action,
                            "credit_increase",
                            id,
                            before,
                            Map.of(
                                    "record",
                                    after,
                                    "reason",
                                    Objects.toString(input.reason(), "")));
                    return after;
                });
    }
}
