package cn.erp.crm;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.ReceiptService;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgOwnership;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.crm.CrmCycleData.*;

@Service
@Transactional(readOnly = true)
class CrmReceivableService {
    private final JdbcTemplate jdbc;
    private final CrmContractService contracts;
    private final CrmScope scope;
    private final ReceiptService receipts;
    private final AuditService audit;

    CrmReceivableService(JdbcTemplate jdbc, CrmContractService contracts, CrmScope scope, ReceiptService receipts, AuditService audit) {
        this.jdbc = jdbc;
        this.contracts = contracts;
        this.scope = scope;
        this.receipts = receipts;
        this.audit = audit;
    }

    Map<String, Object> plans(CompanyAccess actor, String contractId, String partnerId, String cursor, int limit) {
        Long contract = contractId == null ? null : id(contractId);
        Long partner = partnerId == null ? null : id(partnerId);
        var rows = jdbc.queryForList("""
                SELECT p.*,k.no contract_no,
                  COALESCE((SELECT SUM(a.amount) FROM crm_receivable a JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                    WHERE a.tenant_id=p.tenant_id AND a.company_id=p.company_id AND a.contract_id=p.contract_id AND a.plan_id=p.id AND r.status='confirmed'),0) received_amount,
                  COALESCE((SELECT SUM(a.amount) FROM crm_receivable a JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                    WHERE a.tenant_id=p.tenant_id AND a.company_id=p.company_id AND a.contract_id=p.contract_id AND a.plan_id=p.id AND r.status IN('draft','pending_confirm')),0) pending_amount
                FROM crm_receivable_plan p JOIN crm_contract k ON k.tenant_id=p.tenant_id AND k.company_id=p.company_id AND k.id=p.contract_id
                JOIN crm_customer_profile c ON c.tenant_id=k.tenant_id AND c.company_id=k.company_id AND c.partner_id=k.partner_id
                WHERE p.tenant_id=? AND p.company_id=? AND p.id<? AND (? IS NULL OR p.contract_id=?) AND (? IS NULL OR k.partner_id=?) AND (%s)
                ORDER BY p.id DESC LIMIT ?
                """.formatted(scope.customerFilter(actor,"c")), actor.tenantId(), actor.companyId(), cursor(cursor, limit),
                contract, contract, partner, partner, limit + 1);
        var day = today();
        rows.forEach(row -> row.put("status", planStatus(row, day)));
        return page(rows, limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> createPlan(CompanyAccess actor, CrmCycleModels.Plan input) {
        long contractId = id(input.contractId());
        var contract = contracts.source(actor, contractId, true, true);
        requireApproved(contract);
        BigDecimal amount = positive(input.planAmount(), 2, "计划回款金额");
        var allocated = jdbc.queryForObject("""
                SELECT COALESCE(SUM(plan_amount),0) FROM crm_receivable_plan WHERE tenant_id=? AND company_id=? AND contract_id=?
                """, BigDecimal.class, actor.tenantId(), actor.companyId(), contractId);
        if (allocated.add(amount).compareTo(value(contract, "total_amount")) > 0)
            throw conflict("CRM_PLAN_AMOUNT_EXCEEDED", "回款计划累计金额不能超过合同金额");
        long id = Ids.next();
        int days = input.remindDays() == null ? 3 : input.remindDays();
        jdbc.update("""
                INSERT INTO crm_receivable_plan(id,tenant_id,company_id,contract_id,period_no,plan_date,plan_amount,remind_days)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), contractId, input.periodNo(), input.planDate(), amount, days);
        var values = jdbc.queryForMap("SELECT * FROM crm_receivable_plan WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        values.put("received_amount", BigDecimal.ZERO.setScale(2));
        values.put("pending_amount", BigDecimal.ZERO.setScale(2));
        values.put("status", planStatus(values, today()));
        var result = ApiRows.row(values);
        audit.record(actor, "crm_receivable.plan_create", "crm_receivable_plan", id, null, result);
        return result;
    }

    Map<String, Object> list(CompanyAccess actor, String contractId, String planId, String partnerId, String cursor, int limit) {
        if (!actor.hasPermission("receipt:view")) throw forbidden("需要收款单查看权限才能查看具体回款关联");
        Long contract = contractId == null ? null : id(contractId);
        Long plan = planId == null ? null : id(planId);
        Long partner = partnerId == null ? null : id(partnerId);
        var rows = jdbc.queryForList("""
                SELECT a.*,r.receipt_no,r.status receipt_status,r.business_date received_date,k.no contract_no
                FROM crm_receivable a JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                JOIN crm_contract k ON k.tenant_id=a.tenant_id AND k.company_id=a.company_id AND k.id=a.contract_id
                JOIN crm_customer_profile c ON c.tenant_id=k.tenant_id AND c.company_id=k.company_id AND c.partner_id=k.partner_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.id<? AND (? IS NULL OR a.contract_id=?) AND (? IS NULL OR a.plan_id=?)
                    AND (? IS NULL OR k.partner_id=?) AND (%s) %s ORDER BY a.id DESC LIMIT ?
                """.formatted(scope.customerFilter(actor,"c"), OrgOwnership.filter(actor,"r")), actor.tenantId(), actor.companyId(),
                cursor(cursor, limit), contract, contract, plan, plan, partner, partner, limit + 1);
        return page(rows, limit);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> associate(CompanyAccess actor, CrmCycleModels.Receivable input) {
        if (!actor.hasPermission("receipt:view")) throw forbidden("关联回款前需要查看原收款单");
        long planId = id(input.planId()), receiptId = id(input.receiptId());
        var plan = one(jdbc.queryForList("SELECT * FROM crm_receivable_plan WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), planId));
        long contract = number(plan,"contract_id");
        var source = contracts.source(actor, contract, true, true);
        requireApproved(source);
        var original = lockReceipt(actor, receiptId, source);
        var amount = value(original, "amount");
        if (input.amount() != null && positive(input.amount(), 2, "回款金额").compareTo(amount) != 0)
            throw invalid("回款关联金额须与收款单金额一致");
        var previous = jdbc.queryForList("SELECT * FROM crm_receivable WHERE tenant_id=? AND company_id=? AND receipt_id=?",
                actor.tenantId(), actor.companyId(), receiptId);
        if (!previous.isEmpty()) {
            if (number(previous.getFirst(), "plan_id") != planId || number(previous.getFirst(), "contract_id") != contract)
                throw conflict("CRM_RECEIPT_ALREADY_LINKED", "此收款单已关联其他回款计划");
            return receiptView(previous.getFirst(), original);
        }
        BigDecimal planned = alreadyLinked(actor, planId);
        if (planned.add(amount).compareTo(value(plan, "plan_amount")) > 0)
            throw conflict("CRM_PLAN_AMOUNT_EXCEEDED", "关联收款合计不能超过该期回款计划金额");
        long id = Ids.next();
        jdbc.update("""
                INSERT INTO crm_receivable(id,tenant_id,company_id,contract_id,plan_id,receipt_id,amount,created_by)
                VALUES(?,?,?,?,?,?,?,?)
                """, id, actor.tenantId(), actor.companyId(), contract, planId, receiptId, amount, actor.userId());
        jdbc.update("UPDATE receipt SET crm_contract_id=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                contract, actor.tenantId(), actor.companyId(), receiptId);
        var row = jdbc.queryForMap("SELECT * FROM crm_receivable WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(), actor.companyId(), id);
        var result = receiptView(row, original);
        audit.record(actor, "crm_receivable.link_receipt", "crm_receivable", id, null, result);
        return result;
    }

    private BigDecimal alreadyLinked(CompanyAccess actor, long plan) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(a.amount),0) FROM crm_receivable a
                JOIN receipt r ON r.tenant_id=a.tenant_id AND r.company_id=a.company_id AND r.id=a.receipt_id
                WHERE a.tenant_id=? AND a.company_id=? AND a.plan_id=? AND r.status<>'reversed'
                """, BigDecimal.class, actor.tenantId(), actor.companyId(), plan);
    }

    private Map<String, Object> lockReceipt(CompanyAccess actor, long receipt, Map<String, Object> source) {
        receipts.get(actor, receipt);
        var row = one(jdbc.queryForList("SELECT * FROM receipt r WHERE r.tenant_id=? AND r.company_id=? AND r.id=?"
                + OrgOwnership.filter(actor, "r") + " FOR UPDATE", actor.tenantId(), actor.companyId(), receipt));
        if (!"customer".equals(row.get("receipt_type")) || row.get("partner_id") == null
                || number(row, "partner_id") != number(source, "partner_id")
                || !Set.of("draft", "pending_confirm", "confirmed").contains(row.get("status")))
            throw invalid("仅可关联合同客户的有效收款单");
        if (row.get("related_order_id") != null) one(jdbc.queryForList("""
                SELECT id FROM sales_order WHERE tenant_id=? AND company_id=? AND id=? AND source_type='crm_contract' AND source_id=?
                """, actor.tenantId(), actor.companyId(), row.get("related_order_id"), source.get("id")));
        return row;
    }

    private Map<String, Object> receiptView(Map<String, Object> link, Map<String, Object> receipt) {
        var result = ApiRows.row(link);
        var source = ApiRows.row(receipt);
        result.put("receiptNo", source.get("receiptNo"));
        result.put("receiptStatus", source.get("status"));
        result.put("receivedDate", source.get("businessDate"));
        return result;
    }

    private static String planStatus(Map<String, Object> row, LocalDate today) {
        var collected = value(row, "received_amount");
        if (collected.compareTo(value(row, "plan_amount")) >= 0) return "finished";
        if (LocalDate.parse(row.get("plan_date").toString()).isBefore(today)) return "overdue";
        return collected.signum() == 0 ? "pending" : "partial";
    }

    private static void requireApproved(Map<String, Object> contract) {
        if (!"approved".equals(contract.get("status"))) throw conflict("STATE_CONFLICT", "只有已审批合同可以维护回款计划");
    }
}
