package cn.erp.ar;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;

import cn.erp.shared.*;

import static cn.erp.ar.ArSupport.*;

@Service
public class CreditService implements CreditPort {
    private final ArSupport s;
    private final CreditSnapshot snapshots;

    CreditService(ArSupport s, CreditSnapshot snapshots) {
        this.s = s;
        this.snapshots = snapshots;
    }

    public void lock(ActorScope a, long customer) {
        s.jdbc.queryForList(
                "SELECT id FROM ar_credit_limit WHERE tenant_id=? AND company_id=? AND partner_id=?"
                        + " FOR UPDATE",
                Long.class,
                a.tenantId(),
                a.companyId(),
                customer);
    }

    public Map<String, Object> inspect(ActorScope a, long order) {
        var rows =
                s.jdbc.queryForList(
                        "SELECT customer_id FROM sales_order WHERE tenant_id=? AND company_id=? AND"
                                + " id=?",
                        a.tenantId(),
                        a.companyId(),
                        order);
        if (rows.isEmpty()) throw missing();
        long customer = number(rows.getFirst().get("customer_id"));
        Map<String, Object> policy = policy(a, customer, order);
        if (policy == null) return Map.of("overCreditLimit", false);
        BigDecimal candidate = BigDecimal.ZERO;
        for (var line :
                s.jdbc.queryForList(
                        "SELECT receivable_net,recognized_amount,closed_base_qty,base_qty FROM"
                            + " sales_order_line WHERE tenant_id=? AND company_id=? AND order_id=?"
                            + " AND is_deleted=0 ORDER BY id FOR SHARE",
                        a.tenantId(),
                        a.companyId(),
                        order)) {
            var closed =
                    decimal(line.get("receivable_net"))
                            .multiply(decimal(line.get("closed_base_qty")))
                            .divide(decimal(line.get("base_qty")), 2, RoundingMode.HALF_UP);
            candidate =
                    candidate.add(
                            decimal(line.get("receivable_net"))
                                    .subtract(decimal(line.get("recognized_amount")))
                                    .subtract(closed)
                                    .max(BigDecimal.ZERO));
        }
        for (var fee :
                s.jdbc.queryForList(
                        "SELECT amount FROM sales_order_fee WHERE tenant_id=? AND company_id=? AND"
                            + " order_id=? AND bearer='buyer' AND recognized_shipment_id IS NULL"
                            + " ORDER BY id FOR SHARE",
                        a.tenantId(),
                        a.companyId(),
                        order)) candidate = candidate.add(decimal(fee.get("amount")));
        return result(a, customer, order, policy, candidate);
    }

    private Map<String, Object> policy(ActorScope a, long customer, long exclude) {
        if (s.jdbc.queryForList(
                        "SELECT id FROM ar_credit_limit WHERE tenant_id=? AND company_id=? AND"
                                + " partner_id=? FOR SHARE",
                        Long.class,
                        a.tenantId(),
                        a.companyId(),
                        customer)
                .isEmpty()) return null;
        try {
            var p = snapshots.read(a, customer, exclude);
            if (today().isBefore(date(p.get("valid_from")))
                    || p.get("valid_to") != null && today().isAfter(date(p.get("valid_to"))))
                return null;
            return p;
        } catch (EmptyResultDataAccessException absent) {
            return null;
        }
    }

    private Map<String, Object> result(
            ActorScope a, long customer, long order, Map<String, Object> p, BigDecimal candidate) {
        BigDecimal
                exposure =
                        decimal(p.get("receivables"))
                                .add(decimal(p.get("committed")))
                                .add(candidate)
                                .max(BigDecimal.ZERO),
                limit = decimal(p.get("limit_amount")).add(decimal(p.get("increase_amount")));
        boolean over =
                exposure.compareTo(limit) > 0
                        || number(p.get("overdue_days_limit")) > 0
                                && number(p.get("overdue_days"))
                                        > number(p.get("overdue_days_limit"));
        String fingerprint =
                hash(
                        a.tenantId()
                                + ":"
                                + a.companyId()
                                + ":"
                                + customer
                                + ":"
                                + order
                                + ":"
                                + today()
                                + ":"
                                + p.get("version")
                                + ":"
                                + p.get("policy")
                                + ":"
                                + exposure.stripTrailingZeros().toPlainString()
                                + ":"
                                + limit.stripTrailingZeros().toPlainString()
                                + ":"
                                + p.get("overdue_days"));
        return Map.of(
                "overCreditLimit",
                over,
                "creditPolicy",
                p.get("policy"),
                "creditExposure",
                exposure.toPlainString(),
                "creditLimit",
                limit.toPlainString(),
                "creditOverdueDays",
                number(p.get("overdue_days")),
                "creditFingerprint",
                fingerprint);
    }

    public void order(ActorScope a, long order, String stage) {
        var info = inspect(a, order);
        if (!Boolean.TRUE.equals(info.get("overCreditLimit"))) return;
        String policy = info.get("creditPolicy").toString();
        if (policy.equals("warn")) return;
        if (policy.equals("approval") && stage.equals("submit")) return;
        if (policy.equals("approval")
                && WorkflowContext.creditApproved(
                        a, order, info.get("creditFingerprint").toString())) return;
        throw exceeded(a, policy, info);
    }

    public List<Map<String, Object>> warnings(ActorScope a, long customer) {
        var p = policy(a, customer, 0);
        if (p == null || !"warn".equals(p.get("policy"))) return List.of();
        var info = result(a, customer, 0, p, BigDecimal.ZERO);
        return Boolean.TRUE.equals(info.get("overCreditLimit"))
                ? List.of(Map.of("type", "credit", "message", "客户超额或超期，已按提示策略放行，请及时跟进回款"))
                : List.of();
    }

    public void shipment(ActorScope a, long customer) {
        var p = policy(a, customer, 0);
        if (p == null) return;
        var info = result(a, customer, 0, p, BigDecimal.ZERO);
        if (Boolean.TRUE.equals(info.get("overCreditLimit"))
                && !"warn".equals(info.get("creditPolicy")))
            throw exceeded(a, info.get("creditPolicy").toString(), info);
    }

    public Map<String, Object> exposure(cn.erp.identity.CompanyAccess actor, long customer) {
        s.require(actor, "credit_limit:view");
        var p = policy(actor, customer, 0);
        return p == null
                ? Map.of("configured", false)
                : result(actor, customer, 0, p, BigDecimal.ZERO);
    }

    private ApiException exceeded(ActorScope a, String policy, Map<String, Object> info) {
        boolean detailed = false;
        try {
            s.require(a, "credit_limit:view");
            detailed = true;
        } catch (ApiException denied) {
            if (denied.getStatus() != 403) throw denied;
        }
        return new ApiException(
                409,
                policy.equals("approval") ? "CREDIT_APPROVAL_REQUIRED" : "CREDIT_LIMIT_EXCEEDED",
                policy.equals("approval")
                        ? "客户超额或超期，请完成人工信用审批；发货前可办理临时额度调增或先收款"
                        : "客户超额或超期，当前规则禁止提交",
                detailed ? info : Map.of("overCreditLimit", true));
    }

    private static String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("缺少信用快照摘要算法", e);
        }
    }
}
