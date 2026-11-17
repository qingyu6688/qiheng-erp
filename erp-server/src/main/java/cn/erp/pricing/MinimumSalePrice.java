package cn.erp.pricing;

import java.math.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;

/** 最低价为含税基本单位售价；按折后行含税金额比较，避免单位与分摊舍入绕过。 */
@Service
@Transactional
public class MinimumSalePrice {
    private final JdbcTemplate jdbc;

    public MinimumSalePrice(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> inspect(CompanyAccess actor, long order, boolean persist) {
        return inspect(actor, order, persist, null);
    }

    private Map<String, Object> inspect(CompanyAccess actor, long order, boolean persist, Set<Long> insertedLineIds) {
        var policies =
                jdbc.queryForList(
                        "SELECT below_min_price_policy FROM company_policy WHERE tenant_id=? AND"
                            + " company_id=?"
                                + (persist ? " FOR SHARE" : ""),
                        String.class,
                        actor.tenantId(),
                        actor.companyId());
        String policy = policies.isEmpty() ? "warn" : policies.getFirst();
        var rows = prices(actor, order, persist, insertedLineIds);
        boolean below = false;
        var fingerprint = new StringBuilder(policy);
        var affected = new ArrayList<String>();
        for (var row : rows) {
            var minimum = (BigDecimal) row.get("min_sale_price");
            var quantity = (BigDecimal) row.get("base_qty");
            var gross =
                    ((BigDecimal) row.get("untaxed_amount"))
                            .add((BigDecimal) row.get("tax_amount"));
            boolean low =
                    minimum.signum() > 0
                            && gross.compareTo(
                                            minimum.multiply(quantity)
                                                    .setScale(2, RoundingMode.HALF_UP))
                                    < 0;
            below |= low;
            if (low) affected.add(row.get("id").toString());
            fingerprint
                    .append('|')
                    .append(row.get("id"))
                    .append(':')
                    .append(quantity.stripTrailingZeros().toPlainString())
                    .append(':')
                    .append(gross.stripTrailingZeros().toPlainString())
                    .append(':')
                    .append(minimum.stripTrailingZeros().toPlainString());
            if (persist)
                jdbc.update(
                        "UPDATE sales_order_line SET below_min_price=?,min_sale_price_snapshot=?"
                            + " WHERE tenant_id=? AND company_id=? AND id=?",
                        low,
                        minimum,
                        actor.tenantId(),
                        actor.companyId(),
                        row.get("id"));
        }
        return Map.of(
                "belowMinPrice",
                below,
                "belowMinPricePolicy",
                policy,
                "priceFingerprint",
                hash(fingerprint.toString()),
                "belowMinPriceLineIds",
                affected);
    }

    private List<Map<String,Object>> prices(CompanyAccess actor, long order, boolean persist, Set<Long> insertedLineIds) {
        String select = "SELECT l.id,l.base_qty,l.untaxed_amount,l.tax_amount,s.min_sale_price FROM sales_order_line l";
        String scope = " sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id"
                + " WHERE l.tenant_id=? AND l.company_id=? AND l.order_id=? AND l.is_deleted=0";
        if (insertedLineIds == null) {
            return jdbc.queryForList(select + " JOIN" + scope + " ORDER BY l.id" + (persist ? " FOR SHARE" : ""),
                    actor.tenantId(), actor.companyId(), order);
        }
        if (insertedLineIds.isEmpty()) return List.of();
        // 新订单只需复核本事务插入的行，并锁所在 SKU 的价格，避免读锁覆盖相邻订单的索引间隙。
        var args = new ArrayList<Object>(List.of(actor.tenantId(), actor.companyId(), order));
        args.addAll(insertedLineIds.stream().sorted().toList());
        String placeholders = String.join(",", Collections.nCopies(insertedLineIds.size(), "?"));
        return jdbc.queryForList(select + " FORCE INDEX (PRIMARY) STRAIGHT_JOIN" + scope
                + " AND l.id IN (" + placeholders + ") ORDER BY l.id FOR SHARE OF s", args.toArray());
    }

    public void saved(CompanyAccess actor, long order, Set<Long> insertedLineIds) {
        var result = inspect(actor, order, true, insertedLineIds);
        if (Boolean.TRUE.equals(result.get("belowMinPrice"))) {
            if (result.get("belowMinPricePolicy").equals("block"))
                throw new ApiException(422, "MIN_SALE_PRICE_BLOCKED", "折后含税基本单位售价低于最低售价，当前公司禁止保存");
            var status =
                    jdbc.queryForObject(
                            "SELECT status FROM sales_order WHERE tenant_id=? AND company_id=? AND"
                                + " id=?",
                            String.class,
                            actor.tenantId(),
                            actor.companyId(),
                            order);
            if (result.get("belowMinPricePolicy").equals("approval")
                    && !status.equals("draft")
                    && !WorkflowContext.priceApproved(
                            actor, order, result.get("priceFingerprint").toString()))
                throw new ApiException(
                        422, "PRICE_APPROVAL_REQUIRED", "审批后的低价变更不能绕过价格流程，请按新订单重新提交审批");
        }
    }

    public void submitting(CompanyAccess actor, long order) {
        var result = inspect(actor, order, true);
        if (Boolean.TRUE.equals(result.get("belowMinPrice"))
                && result.get("belowMinPricePolicy").equals("block"))
            throw new ApiException(422, "MIN_SALE_PRICE_BLOCKED", "折后售价低于当前最低售价，不能提交");
    }

    public void approving(CompanyAccess actor, long order) {
        var result = inspect(actor, order, true);
        if (!Boolean.TRUE.equals(result.get("belowMinPrice"))) return;
        if (result.get("belowMinPricePolicy").equals("block"))
            throw new ApiException(422, "MIN_SALE_PRICE_BLOCKED", "当前最低售价规则禁止审批此订单");
        if (result.get("belowMinPricePolicy").equals("approval")
                && !WorkflowContext.priceApproved(
                        actor, order, result.get("priceFingerprint").toString()))
            throw new ApiException(422, "PRICE_APPROVAL_REQUIRED", "须取得与当前最低价快照一致的有效人工价格审批");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("缺少价格快照摘要算法", error);
        }
    }
}
