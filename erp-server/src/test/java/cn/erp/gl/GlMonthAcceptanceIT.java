package cn.erp.gl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.opening.OpeningService;
import cn.erp.period.PeriodService;
import cn.erp.salesorder.*;
import cn.erp.salesfinance.*;
import cn.erp.fulfillment.*;
import cn.erp.reporting.*;
import cn.erp.shared.Ids;

import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.*;

/** 用原业务命令验收完整月份，不能用直接写入凭证替代业务过账。 */
@SpringBootTest
@ActiveProfiles("test")
class GlMonthAcceptanceIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired OpeningService opening;
    @Autowired PeriodService periods;
    @Autowired GlBookService books;
    @Autowired GlVoucherService vouchers;
    @Autowired GlQueryService queries;
    @Autowired SalesOrderService orders;
    @Autowired ShipmentService shipments;
    @Autowired ReceiptService receipts;
    @Autowired SalesFinanceQueries finance;
    @Autowired ReportService reports;
    GlTestData d;
    final long warehouse = Ids.next(), unit = Ids.next(), sku = Ids.next(), september = Ids.next();

    @BeforeEach
    void setup() {
        d = new GlTestData(jdbc);
        jdbc.update(
                "INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",
                Ids.next(),
                d.tenant,
                d.company);
        jdbc.update(
                "INSERT INTO warehouse(id,tenant_id,company_id,code,name)"
                        + " VALUES(?,?,?,'W','月度验收仓')",
                warehouse,
                d.tenant,
                d.company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')", unit, d.tenant);
        long category = Ids.next(), product = Ids.next();
        jdbc.update(
                "INSERT INTO product_category(id,tenant_id,code,name,path)"
                        + " VALUES(?,?,'C','月度验收分类','/')",
                category,
                d.tenant);
        jdbc.update(
                "INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id)"
                        + " VALUES(?,?,?,'P','月度验收商品',?)",
                product,
                d.tenant,
                category,
                unit);
        jdbc.update(
                "INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price)"
                        + " VALUES(?,?,?,'S','月度验收规格',100)",
                sku,
                d.tenant,
                product);
        jdbc.update(
                "INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base)"
                        + " VALUES(?,?,?,?,1,1)",
                Ids.next(),
                d.tenant,
                sku,
                unit);
        jdbc.update(
                "INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id,balance)"
                        + " VALUES(?,?,?,?,0)",
                Ids.next(),
                d.tenant,
                d.company,
                d.bank);
        jdbc.update(
                "INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                        + " VALUES(?,?,?,'202609','2026-09-01','2026-09-30')",
                september,
                d.tenant,
                d.company);
    }

    @AfterEach
    void cleanup() {
        if (d != null) d.cleanup();
    }

    Map<String, Object> op(Map<String, Object> v, boolean stock, String action, long user) {
        return opening.command(
                d.actor(user),
                stock,
                number(v.get("id")),
                action,
                key(),
                new OpeningService.Version(number(v.get("version")), "月度样例复核"));
    }

    Map<String, Object> manual(Map<String, Object> v, String action, long user) {
        return vouchers.change(
                d.actor(user),
                number(v.get("id")),
                action,
                key(),
                new GlModels.Version(number(v.get("version")), "月度样例复核"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void realMonthAndTenManualVouchersReconcileCloseAndReopen() {
        var stock =
                opening.createStock(
                        d.actor(d.maker),
                        key(),
                        new OpeningService.Stock(
                                "opening",
                                warehouse,
                                LocalDate.of(2026, 9, 30),
                                "样例存货期初",
                                List.of(new OpeningService.StockLine(sku, unit, "100", "1000")),
                                null));
        op(
                op(op(stock, true, "submit", d.maker), true, "approve", d.reviewer),
                true,
                "post",
                d.maker);
        var cash =
                opening.createEntry(
                        d.actor(d.maker),
                        key(),
                        new OpeningService.Entry(
                                "cash",
                                null,
                                d.bank,
                                "D",
                                "10000",
                                LocalDate.of(2026, 9, 30),
                                "样例资金期初",
                                null));
        op(op(cash, false, "approve", d.reviewer), false, "post", d.maker);
        periods.close(
                d.actor(d.reviewer), september, key(), new PeriodService.Version(0L, "完成业务期初"));
        books.create(d.actor(d.maker), key(), new GlModels.Book("enterprise", d.october, null, 0L));
        var suggestions = (List<Map<String, Object>>) books.suggest(d.actor(d.maker)).get("rows");
        var lines =
                suggestions.stream()
                        .map(
                                row -> {
                                    var aux = (Map<String, String>) row.get("aux");
                                    var dimensions =
                                            new GlModels.Aux(
                                                    Long.valueOf(aux.get("customerId")),
                                                    Long.valueOf(aux.get("supplierId")),
                                                    Long.valueOf(aux.get("deptId")),
                                                    Long.valueOf(aux.get("employeeId")),
                                                    Long.valueOf(aux.get("projectId")),
                                                    Long.valueOf(aux.get("skuId")),
                                                    Long.valueOf(aux.get("customId")));
                                    String debit = row.get("openingDebit").toString(),
                                            credit = row.get("openingCredit").toString();
                                    return new GlModels.OpeningLine(
                                            number(row.get("subjectId")),
                                            dimensions,
                                            debit,
                                            credit,
                                            "0",
                                            "0",
                                            debit,
                                            credit,
                                            "0");
                                })
                        .toList();
        // 样例补入原运营账本不管理的既有固定资产及对应投入，折旧有实际期初基础。
        lines = new ArrayList<>(lines);
        lines.add(
                new GlModels.OpeningLine(
                        d.subject("1601"),
                        GlModels.Aux.empty(),
                        "1000",
                        "0",
                        "0",
                        "0",
                        "1000",
                        "0",
                        "0"));
        lines.add(
                new GlModels.OpeningLine(
                        d.subject("4001"),
                        GlModels.Aux.empty(),
                        "0",
                        "1000",
                        "0",
                        "0",
                        "0",
                        "1000",
                        "0"));
        books.saveOpenings(d.actor(d.maker), key(), new GlModels.Openings(0L, lines));
        var book = books.get(d.actor(d.maker));
        book =
                books.confirm(
                        d.actor(d.reviewer),
                        key(),
                        new GlModels.Confirmation(
                                number(book.get("version")), "本机样例年初与启用期初相同，已核对辅助和公式"));
        books.activate(
                d.actor(d.maker),
                "activate",
                key(),
                new GlModels.Version(number(book.get("version")), null));
        LocalDate day = LocalDate.of(2026, 10, 15);
        var sale =
                orders.create(
                        d.actor(d.maker),
                        key(),
                        new SalesOrderModels.Input(
                                "" + d.customer,
                                day,
                                day.plusDays(1),
                                true,
                                "0",
                                List.of(
                                        new SalesOrderModels.Line(
                                                null,
                                                "" + sku,
                                                "" + warehouse,
                                                "" + unit,
                                                "10",
                                                "100",
                                                null,
                                                "0",
                                                null,
                                                "0.13")),
                                List.of(),
                                "月度验收销售",
                                null,
                                null));
        sale =
                orders.command(
                        d.actor(d.maker),
                        number(sale.get("id")),
                        "submit",
                        key(),
                        new SalesOrderModels.Command(number(sale.get("version")), null, null));
        sale =
                orders.command(
                        d.actor(d.reviewer),
                        number(sale.get("id")),
                        "approve",
                        key(),
                        new SalesOrderModels.Command(number(sale.get("version")), null, null));
        long order = number(sale.get("id")),
                orderLine =
                        jdbc.queryForObject(
                                "SELECT id FROM sales_order_line WHERE tenant_id=? AND order_id=?",
                                Long.class,
                                d.tenant,
                                order);
        var shipment =
                shipments.create(
                        d.actor(d.maker),
                        key(),
                        new ShipmentModels.Input(
                                d.customer,
                                warehouse,
                                day,
                                "delivery",
                                null,
                                List.of(new ShipmentModels.InputLine(orderLine, unit, "10")),
                                null));
        shipment =
                shipments.submit(
                        d.actor(d.maker),
                        number(shipment.get("id")),
                        key(),
                        number(shipment.get("version")));
        shipment =
                shipments.post(
                        d.actor(d.reviewer),
                        number(shipment.get("id")),
                        key(),
                        number(shipment.get("version")));
        long shipmentLine =
                jdbc.queryForObject(
                        "SELECT id FROM shipment_line WHERE tenant_id=? AND shipment_id=?",
                        Long.class,
                        d.tenant,
                        number(shipment.get("id")));
        shipments.sign(
                d.actor(d.maker),
                number(shipment.get("id")),
                key(),
                new ShipmentModels.Signed(
                        number(shipment.get("version")),
                        day,
                        List.of(new ShipmentModels.SignedLine(shipmentLine, "10")),
                        List.of()));
        var receipt =
                receipts.create(
                        d.actor(d.maker),
                        key(),
                        new FinanceModels.Receipt(
                                "customer",
                                d.customer,
                                null,
                                d.bank,
                                "1000",
                                LocalDate.of(2026, 10, 25),
                                order,
                                null,
                                "月度样例收款",
                                null));
        receipt =
                receipts.command(
                        d.actor(d.maker),
                        number(receipt.get("id")),
                        "submit",
                        key(),
                        new FinanceModels.Version(number(receipt.get("version")), null, null));
        receipts.command(
                d.actor(d.reviewer),
                number(receipt.get("id")),
                "confirm",
                key(),
                new FinanceModels.Version(number(receipt.get("version")), null, null));

        long debit =
                jdbc.queryForObject(
                        "SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND"
                            + " item_type='receivable' AND direction='D'",
                        Long.class,
                        d.tenant,
                        d.company);
        long credit =
                jdbc.queryForObject(
                        "SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND"
                            + " item_type='receipt' AND source_id=?",
                        Long.class,
                        d.tenant,
                        d.company,
                        receipt.get("id"));
        finance.allocate(
                d.actor(d.maker),
                key(),
                new FinanceModels.Allocations(
                        d.customer, List.of(new FinanceModels.Allocation(debit, credit, "1000"))));
        for (int i = 0; i < 10; i++) {
            var voucher =
                    vouchers.save(
                            d.actor(d.maker),
                            null,
                            key(),
                            new GlModels.Voucher(
                                    d.word(),
                                    day,
                                    0,
                                    List.of(
                                            new GlModels.Line(
                                                    "月度费用" + i,
                                                    d.subject("6602"),
                                                    "D",
                                                    "10",
                                                    "0",
                                                    null,
                                                    null,
                                                    null),
                                            new GlModels.Line(
                                                    i % 3 == 0
                                                            ? "应付费用"
                                                            : i % 3 == 1 ? "应付工资" : "累计折旧",
                                                    d.subject(
                                                            i % 3 == 0
                                                                    ? "2241"
                                                                    : i % 3 == 1 ? "2211" : "1602"),
                                                    "C",
                                                    "10",
                                                    "0",
                                                    null,
                                                    null,
                                                    null)),
                                    null,
                                    null));
            manual(
                    manual(manual(voucher, "submit", d.maker), "approve", d.reviewer),
                    "post",
                    d.maker);
        }
        periods.close(
                d.actor(d.reviewer), d.october, key(), new PeriodService.Version(0L, "真实业务月份验收"));
        for (String type : List.of("balance_sheet", "income_statement", "cash_flow"))
            assertThat(queries.statement(d.actor(d.maker), type, d.october))
                    .as(type)
                    .containsEntry("passed", true);
        var income =
                (List<Map<String, Object>>)
                        queries.statement(d.actor(d.maker), "income_statement", d.october)
                                .get("rows");
        BigDecimal gross =
                income.stream()
                        .filter(r -> r.get("code").equals("gross_profit"))
                        .map(r -> new BigDecimal(r.get("amount").toString()))
                        .findFirst()
                        .orElseThrow();
        var operating =
                reports.snapshot(
                        d.actor(d.maker),
                        ReportType.GROSS_MARGIN,
                        new ReportQuery(
                                LocalDate.of(2026, 10, 1),
                                LocalDate.of(2026, 10, 31),
                                null,
                                null,
                                null,
                                null,
                                false));
        assertThat(gross).isEqualByComparingTo("784.96");
        assertThat(income)
                .anySatisfy(
                        r -> {
                            assertThat(r.get("code")).isEqualTo("net_profit");
                            assertThat(new BigDecimal(r.get("amount").toString()))
                                    .isEqualByComparingTo("684.96");
                        });
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COALESCE(SUM(open_amount),0) FROM partner_open_item WHERE"
                                        + " tenant_id=? AND item_type='receivable'",
                                BigDecimal.class,
                                d.tenant))
                .isZero();

        assertThat(operating.items())
                .anySatisfy(
                        r ->
                                assertThat(new BigDecimal(r.get("grossMargin").toString()))
                                        .isEqualByComparingTo(gross));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT balance FROM cash_account_balance WHERE tenant_id=? AND"
                                        + " cash_account_id=?",
                                BigDecimal.class,
                                d.tenant,
                                d.bank))
                .isEqualByComparingTo("11000");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM gl_voucher WHERE tenant_id=? AND"
                                        + " source='manual' AND status='posted'",
                                Long.class,
                                d.tenant))
                .isEqualTo(10);
        periods.reopen(
                d.actor(d.reviewer), d.october, key(), new PeriodService.Version(1L, "复核反结账保留发生额"));
        assertThat(queries.statement(d.actor(d.maker), "income_statement", d.october).get("rows"))
                .isEqualTo(income);
        assertThat(periods.closeCheck(d.actor(d.maker), d.october)).containsEntry("passed", true);
    }
}
