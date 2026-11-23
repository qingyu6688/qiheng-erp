package cn.erp.workbench;

import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.OrgScope;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/constraints/fixtures.sql", config = @SqlConfig(encoding = "UTF-8"))
class OrderSettlementSummaryIT {
    private static final Set<String> SALES = Set.of("sales_order:view", "settlement:view", "shipment:view", "receipt:view");
    private static final Set<String> PURCHASE = Set.of("purchase_order:view", "settlement:view", "goods_receipt:view", "payment:view");
    private static final CompanyAccess FINANCE = company(SALES);
    @Autowired OrderRelatedDocuments documents;
    @Autowired JdbcTemplate jdbc;
    @Autowired SettlementService settlement;

    @Test void sameReceiptAcrossOrdersAndMultipleAllocationsUseAmountsOnEachOrder() {
        prepareSalesReceipt();
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive)
                VALUES (81202,70101,70201,'SO-摘要-2',70401,'2026-11-15',1)
                """);
        jdbc.update("UPDATE sales_order_line SET order_id=81202 WHERE id=71302");
        jdbc.update("""
                INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty)
                VALUES (81602,70101,70201,71501,2,71302,71002,70701,1,1,1)
                """);
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES (82402,70101,70201,70401,'charge','shipment_line',71501,81602,40,40,0,'2026-11-15',70601),
                       (82403,70101,70201,70401,'credit','sales_return_line',81803,81603,15,15,0,'2026-11-15',70601)
                """);
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,
                    amount,open_amount,aging_date) VALUES
                    (82705,70101,70201,70401,'receivable',82402,'D',40,40,'2026-11-15'),
                    (82706,70101,70201,70401,'receivable',82403,'C',15,15,'2026-11-15')
                """);
        // 同额的两次分摊都应计入，同一收款核到别单的金额和退货信用额均有独立归属。
        long another = settlement.allocate(FINANCE, 72701, 72702, new BigDecimal("10"), "manual", null, null);
        settlement.allocate(FINANCE, 82705, 72702, new BigDecimal("27"), "manual", null, null);
        settlement.allocate(FINANCE, 72701, 82706, new BigDecimal("15"), "manual", null, null);
        assertThat(sales(FINANCE)).isEqualTo(summary("20.00", "65.00", "company"));
        assertThat(documents.settlementSummary(FINANCE, "sales-orders", 81202))
                .isEqualTo(summary("27.00", "13.00", "company"));
        settlement.revoke(FINANCE, another, "该笔收款分摊撤销");
        assertThat(sales(FINANCE)).isEqualTo(summary("10.00", "75.00", "company"));
    }

    @Test void actualCashScopesUseBothDocumentsWhileOpenItemsFollowTheirFulfillment() {
        prepareSalesReceipt();
        jdbc.update("UPDATE sales_order SET owner_department_id=1001 WHERE id=71201");
        jdbc.update("UPDATE shipment SET owner_department_id=1001 WHERE id=71501");
        jdbc.update("UPDATE receipt SET owner_department_id=1002 WHERE id=72501");
        var scoped = new CompanyAccess(70101, 101, 70201, false, Set.of(70301L), SALES,
                OrgScope.restricted(Set.of(1001L), Set.of(), 1001));
        assertThat(sales(scoped)).isEqualTo(summary("0.00", "90.00", "visible"));
        jdbc.update("UPDATE receipt SET owner_department_id=1001 WHERE id=72501");
        assertThat(sales(scoped)).isEqualTo(summary("10.00", "90.00", "visible"));
        jdbc.update("UPDATE shipment SET owner_department_id=1002 WHERE id=71501");
        assertThat(sales(scoped)).isEqualTo(summary("0.00", "0.00", "visible"));
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES (80304,70101,70201,'W3','受限仓')");
        jdbc.update("UPDATE shipment SET owner_department_id=1001,warehouse_id=80304 WHERE id=71501");
        assertThat(sales(scoped)).isEqualTo(summary("0.00", "0.00", "visible"));
        assertThat(sales(FINANCE)).isEqualTo(summary("10.00", "90.00", "company"));
    }

    @Test void missingPermissionsReturnNullAndAnInaccessibleOrderNeverRevealsAmounts() {
        prepareSalesReceipt();
        assertThat(sales(company(Set.of("sales_order:view", "settlement:view", "shipment:view"))))
                .isEqualTo(summary(null, "90.00", "company"));
        assertThat(sales(company(Set.of("sales_order:view", "shipment:view", "receipt:view"))))
                .isEqualTo(summary(null, null, "company"));
        assertThat(sales(company(Set.of("sales_order:view", "settlement:view", "receipt:view"))))
                .isEqualTo(summary(null, null, "company"));
        assertMissing(new CompanyAccess(70101, 101, 70202, true, Set.of(), SALES));
        assertMissing(new CompanyAccess(70102, 101, 70203, true, Set.of(), SALES));
        assertMissing(new CompanyAccess(70101, 101, 70201, false, Set.of(), SALES));
        assertMissing(new CompanyAccess(70101, 101, 70201, true, Set.of(), SALES,
                OrgScope.restricted(Set.of(1010L), Set.of(), 1010)));
    }

    @Test void refundDoesNotImplicitlyReassignCashAndReversedChargeDropsOutOfBothAmounts() {
        prepareSalesReceipt();
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,
                    amount,open_amount,aging_date) VALUES (82711,70101,70201,70401,'refund_request',72601,'D',20,0,'2026-11-15')
                """);
        jdbc.update("""
                INSERT INTO settlement_allocation(id,tenant_id,company_id,partner_id,debit_item_id,credit_item_id,
                    amount,status,allocation_type,trigger_type,trigger_id)
                VALUES (82811,70101,70201,70401,82711,72702,20,'active','refund','refund_request',72601)
                """);
        assertThat(sales(FINANCE)).isEqualTo(summary("10.00", "90.00", "company"));
        jdbc.update("UPDATE receipt SET status='reversed' WHERE id=72501");
        assertThat(sales(FINANCE)).isEqualTo(summary("0.00", "90.00", "company"));
        jdbc.update("""
                INSERT INTO receivable(id,tenant_id,company_id,customer_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id,posting_action,reverses_receivable_id)
                VALUES (82411,70101,70201,70401,'credit','shipment',71501,71601,100,100,0,'2026-11-15',70601,'reverse',72401)
                """);
        assertThat(sales(FINANCE)).isEqualTo(summary("0.00", "0.00", "company"));
    }

    @Test void purchaseFeeAndReceiptLineUseSupplierPaymentAllocationsAndExcludeUnallocatedPrepayment() {
        var actor = company(PURCHASE);
        jdbc.update("""
                INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date)
                VALUES (82001,70101,70201,'收货摘要',70401,70301,'2026-11-15')
                """);
        jdbc.update("""
                INSERT INTO goods_receipt_line(id,tenant_id,company_id,receipt_id,line_no,order_line_id,sku_id,unit_id,
                    unit_rate,qty,base_qty) VALUES (82002,70101,70201,82001,1,71801,71001,70701,1,1,1)
                """);
        jdbc.update("""
                INSERT INTO purchase_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,tax_rate,
                    untaxed_amount,tax_amount,recognized_receipt_id)
                VALUES (82020,70101,70201,71701,'freight','buyer',6,0,6,0,82001)
                """);
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id) VALUES
                    (82021,70101,70201,70401,'charge','purchase_order_fee',82001,82020,6,6,0,'2026-11-15',70601),
                    (82031,70101,70201,70401,'charge','goods_receipt_line',82001,82002,20,20,0,'2026-11-15',70601)
                """);
        jdbc.update("""
                INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,amount,
                    business_date,status,confirmed_at,period_id,related_order_id) VALUES
                    (82011,70101,70201,'预付摘要','supplier',70401,70501,100,'2026-11-15','confirmed',NOW(),70601,71701),
                    (82012,70101,70201,'手工核销摘要','supplier',70401,70501,40,'2026-11-15','confirmed',NOW(),70601,NULL)
                """);
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,
                    amount,open_amount,aging_date) VALUES
                    (82022,70101,70201,70401,'payable',82021,'C',6,6,'2026-11-15'),
                    (82032,70101,70201,70401,'payable',82031,'C',20,20,'2026-11-15'),
                    (82023,70101,70201,70401,'payment',82012,'D',40,40,'2026-11-15'),
                    (82024,70101,70201,70401,'payment',82011,'D',100,100,'2026-11-15')
                """);
        settlement.allocate(actor, 82023, 82022, new BigDecimal("5"), "manual", null, null);
        settlement.allocate(actor, 82024, 82032, new BigDecimal("3.25"), "auto_order", "purchase_order", 71701L);
        assertThat(documents.settlementSummary(actor, "purchase-orders", 71701))
                .isEqualTo(summary("8.25", "17.75", "company"));
        assertThat(documents.settlementSummary(company(Set.of("purchase_order:view", "settlement:view", "goods_receipt:view")),
                "purchase-orders", 71701)).isEqualTo(summary(null, "17.75", "company"));
        jdbc.update("""
                INSERT INTO purchase_fee_doc(id,tenant_id,company_id,document_no,source_kind,source_receipt_id,partner_id,warehouse_id,business_date,
                  allocation_method,reason,cost_tax_inclusive,status,amount,untaxed_amount,tax_amount,stock_cost,period_id,posted_by,posted_at,owner_user_id,created_by,updated_by)
                VALUES(83010,70101,70201,'费用摘要','receipt',82001,70401,70301,'2026-11-15','amount','分批费用摘要',0,'posted',6,6,0,6,70601,101,NOW(),101,101,101)
                """);
        jdbc.update("""
                INSERT INTO purchase_fee_doc_line(id,tenant_id,company_id,document_id,line_no,order_fee_id,fee_type,amount,untaxed_amount,tax_amount,tax_rate)
                VALUES(83011,70101,70201,83010,1,82020,'freight',6,6,0,0)
                """);
        jdbc.update("UPDATE payable SET source_line_id=83011 WHERE id=82021");
        assertThat(documents.settlementSummary(actor,"purchase-orders",71701)).isEqualTo(summary("8.25","17.75","company"));
        assertThat(documents.page(actor,"purchase-orders",71701,"settlement-allocations",null,20).items()).hasSize(2);
    }

    private void prepareSalesReceipt() {
        jdbc.update("UPDATE receipt SET status='confirmed',confirmed_at=NOW(),period_id=70601 WHERE id=72501");
        // 共用约束样例预置了 10 元分摊行，此处补齐其余额结果。
        jdbc.update("UPDATE partner_open_item SET open_amount=90 WHERE id IN (72701,72702)");
    }

    private static CompanyAccess company(Set<String> permissions) {
        return new CompanyAccess(70101, 101, 70201, true, Set.of(), permissions);
    }

    private OrderRelatedDocuments.SettlementSummary sales(CompanyAccess actor) {
        return documents.settlementSummary(actor, "sales-orders", 71201);
    }

    private static OrderRelatedDocuments.SettlementSummary summary(String cash, String open, String scope) {
        return new OrderRelatedDocuments.SettlementSummary(cash, open, scope);
    }

    private void assertMissing(CompanyAccess actor) {
        assertThatThrownBy(() -> sales(actor)).isInstanceOfSatisfying(ApiException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo("NOT_FOUND"));
    }
}
