package cn.erp.gl;

import java.math.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.stereotype.Component;

import cn.erp.shared.ActorScope;

import static cn.erp.gl.GlSupport.*;

/** 比率的分母为零时不伪造零值；流动项可用报表同名行覆盖通用样例分类。 */
@Component
class GlIndicators {
    private final GlSupport s;
    private final GlBalances balances;

    GlIndicators(GlSupport s, GlBalances balances) {
        this.s = s;
        this.balances = balances;
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> calculate(
            ActorScope a, long period, Map<String, Object> income, Map<String, Object> sheet) {
        var p = s.period(a, period, false);
        var subjects = new HashMap<Long, Map<String, Object>>();
        for (var row :
                s.jdbc.queryForList(
                        "SELECT * FROM gl_subject WHERE tenant_id=? AND company_id=?",
                        a.tenantId(),
                        a.companyId())) subjects.put(number(row.get("id")), row);
        var snapshot =
                balances.snapshot(a, date(p.get("start_date")), date(p.get("end_date")), false);
        BigDecimal currentAssets = BigDecimal.ZERO,
                currentLiabilities = BigDecimal.ZERO,
                inventoryOpening = BigDecimal.ZERO,
                inventoryClosing = BigDecimal.ZERO,
                arOpening = BigDecimal.ZERO,
                arClosing = BigDecimal.ZERO;
        var grouped = new LinkedHashMap<String, BigDecimal[]>();
        for (var balance : snapshot) {
            var subject = subjects.get(balance.subject());
            String root = subject.get("code").toString().substring(0, 4);
            String key = root + ":" + subject.get("category") + ":" + balance.aux().values();
            var totals =
                    grouped.computeIfAbsent(
                            key, k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
            totals[0] = totals[0].add(balance.opening());
            totals[1] = totals[1].add(balance.closing());
        }
        // 同一辅助下合并子科目后重分类，不能把进销项税同时计成资产和负债。
        for (var entry : grouped.entrySet()) {
            var keys = entry.getKey().split(":", 3);
            String root = keys[0], category = keys[1];
            int code = Integer.parseInt(root);
            var opening = entry.getValue()[0];
            var closing = entry.getValue()[1];
            boolean inventory = root.startsWith("14") || category.equals("cost");
            if (inventory) {
                inventoryOpening = inventoryOpening.add(opening);
                inventoryClosing = inventoryClosing.add(closing);
            }
            if (root.equals("1122")) {
                arOpening = arOpening.add(opening.max(BigDecimal.ZERO));
                arClosing = arClosing.add(closing.max(BigDecimal.ZERO));
            }
            if (root.equals("1231")) {
                arOpening = arOpening.add(opening);
                arClosing = arClosing.add(closing);
            }
            if (category.equals("asset") && code < 1500 || category.equals("cost"))
                currentAssets = currentAssets.add(closing);
            if (category.equals("liability") && code < 2300)
                currentLiabilities = currentLiabilities.subtract(closing);
            if (Set.of("1122", "1123").contains(root) && closing.signum() < 0) {
                currentAssets = currentAssets.subtract(closing);
                currentLiabilities = currentLiabilities.subtract(closing);
            }
            if (Set.of("2202", "2203", "2221").contains(root) && closing.signum() > 0) {
                currentAssets = currentAssets.add(closing);
                currentLiabilities = currentLiabilities.add(closing);
            }
        }
        var values = new HashMap<String, BigDecimal>();
        for (var row : (List<Map<String, Object>>) income.get("rows"))
            values.put(row.get("code").toString(), new BigDecimal(row.get("amount").toString()));
        for (var row : (List<Map<String, Object>>) sheet.get("rows"))
            values.put(row.get("code").toString(), new BigDecimal(row.get("amount").toString()));
        currentAssets = values.getOrDefault("current_assets", currentAssets);
        currentLiabilities = values.getOrDefault("current_liabilities", currentLiabilities);
        var revenue = values.getOrDefault("revenue", BigDecimal.ZERO);
        var cost = values.getOrDefault("cost", BigDecimal.ZERO);
        var days =
                BigDecimal.valueOf(
                        ChronoUnit.DAYS.between(date(p.get("start_date")), date(p.get("end_date")))
                                + 1);
        var averageAr = arOpening.add(arClosing).divide(BigDecimal.TWO);
        var averageInventory = inventoryOpening.add(inventoryClosing).divide(BigDecimal.TWO);
        var output = new ArrayList<Map<String, Object>>();
        output.add(
                ratio(
                        "current_ratio",
                        "流动比率",
                        currentAssets,
                        currentLiabilities,
                        BigDecimal.ONE,
                        "倍",
                        "流动资产 / 流动负债"));
        output.add(
                ratio(
                        "quick_ratio",
                        "速动比率",
                        currentAssets.subtract(inventoryClosing),
                        currentLiabilities,
                        BigDecimal.ONE,
                        "倍",
                        "（流动资产 − 存货）/ 流动负债"));
        output.add(
                ratio(
                        "debt_ratio",
                        "资产负债率",
                        values.getOrDefault("liabilities", BigDecimal.ZERO),
                        values.getOrDefault("assets", BigDecimal.ZERO),
                        new BigDecimal("100"),
                        "%",
                        "负债总计 / 资产总计"));
        output.add(
                ratio(
                        "gross_margin",
                        "毛利率",
                        values.getOrDefault("gross_profit", BigDecimal.ZERO),
                        revenue,
                        new BigDecimal("100"),
                        "%",
                        "营业毛利 / 营业收入"));
        output.add(
                ratio(
                        "net_margin",
                        "净利率",
                        values.getOrDefault("net_profit", BigDecimal.ZERO),
                        revenue,
                        new BigDecimal("100"),
                        "%",
                        "净利润 / 营业收入"));
        output.add(
                ratio(
                        "ar_days",
                        "应收账款周转天数",
                        averageAr,
                        revenue,
                        days,
                        "天",
                        "期初、期末平均应收净额 / 本期收入 × 期间天数"));
        output.add(
                ratio(
                        "inventory_days",
                        "存货周转天数",
                        averageInventory,
                        cost,
                        days,
                        "天",
                        "期初、期末平均存货 / 本期营业成本 × 期间天数"));
        return Map.of(
                "periodId",
                Long.toString(period),
                "items",
                output,
                "basis",
                "通用非金融样例：流动资产按1500以前资产及成本科目，流动负债按2300以前负债科目并按辅助明细重分类；财务可在资产负债表增加current_assets、current_liabilities公式行覆盖。平均数取期间两端，损益排除结转。",
                "incomeStatement",
                income,
                "balanceSheet",
                sheet);
    }

    private static Map<String, Object> ratio(
            String code,
            String name,
            BigDecimal numerator,
            BigDecimal denominator,
            BigDecimal multiplier,
            String unit,
            String formula) {
        var output = new LinkedHashMap<String, Object>();
        output.put("code", code);
        output.put("name", name);
        output.put(
                "value",
                denominator.signum() == 0
                        ? null
                        : numerator
                                .multiply(multiplier)
                                .divide(denominator, 4, RoundingMode.HALF_UP)
                                .stripTrailingZeros()
                                .toPlainString());
        output.put("unit", unit);
        output.put("numerator", numerator.toPlainString());
        output.put("denominator", denominator.toPlainString());
        output.put("formula", formula);
        output.put("notice", denominator.signum() == 0 ? "分母为零，本期不计算" : "");
        return output;
    }
}
