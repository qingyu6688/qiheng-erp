package cn.erp.workbench;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.OrgScope;
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
class OrderRelatedDocumentsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderRelatedDocuments related;

    @Test void salesRelationsUseOrderLinesAndCheckBothSidesBeforePaging() {
        jdbc.update("UPDATE sales_order SET owner_department_id=1001, owner_user_id=101 WHERE id=71201");
        jdbc.update("UPDATE shipment SET owner_department_id=1002, owner_user_id=102 WHERE id=71501");
        jdbc.update("""
                INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,
                    owner_department_id,owner_user_id)
                VALUES (81002,70101,70201,'关联发货',70401,70301,'2026-11-15',1001,101),
                       (81003,70101,70201,'同客无关发货',70401,70301,'2026-11-15',1001,101)
                """);
        jdbc.update("""
                INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,
                    unit_rate,qty,base_qty)
                VALUES (81012,70101,70201,81002,1,71302,71002,70701,1,1,1),
                       (81013,70101,70201,81002,2,71303,71003,70701,1,1,1)
                """);
        jdbc.update("""
                INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,
                    reason,owner_department_id,owner_user_id)
                VALUES (81020,70101,70201,'来源受限退货',70401,70301,'2026-11-16','退货样例',1001,101)
                """);
        jdbc.update("""
                INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,
                    unit_rate,qty,base_qty)
                VALUES (81021,70101,70201,81020,1,71601,71001,70701,1,1,1)
                """);
        var permission = Set.of("sales_order:view", "shipment:view", "sales_return:view");
        var all = new CompanyAccess(70101, 101, 70201, true, Set.of(), permission);
        var scoped = new CompanyAccess(70101, 101, 70201, false, Set.of(70301L), permission,
                OrgScope.restricted(Set.of(1001L), Set.of(), 1001));
        var first = related.page(all, "sales-orders", 71201, "shipments", null, 1);
        assertThat(first.items()).extracting(OrderRelatedDocuments.Row::documentNo).containsExactly("关联发货");
        assertThat(first.nextCursor()).isEqualTo("81002");
        assertThat(related.page(all, "sales-orders", 71201, "shipments", 81002L, 1).items())
                .extracting(OrderRelatedDocuments.Row::id).containsExactly("71501");
        var visible = related.page(scoped, "sales-orders", 71201, "shipments", null, 1);
        assertThat(visible.items()).extracting(OrderRelatedDocuments.Row::id).containsExactly("81002");
        assertThat(visible.nextCursor()).isNull();
        assertThat(related.page(scoped, "sales-orders", 71201, "sales-returns", null, 20).items()).isEmpty();
        assertThat(related.page(all, "sales-orders", 71201, "sales-returns", null, 20).items())
                .extracting(OrderRelatedDocuments.Row::id).containsExactly("81020");

        jdbc.update("UPDATE receivable SET source_type='shipment_line' WHERE id=72401");
        var financialPermissions = Set.of("sales_order:view", "shipment:view", "receipt:view", "settlement:view");
        var finance = new CompanyAccess(70101, 101, 70201, true, Set.of(), financialPermissions);
        var financeScoped = new CompanyAccess(70101, 101, 70201, false, Set.of(70301L), financialPermissions, scoped.orgScope());
        var allocations = related.page(finance, "sales-orders", 71201, "settlement-allocations", null, 20).items();
        assertThat(allocations).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("72801");
            assertThat(row.documentId()).isEqualTo("72501");
            assertThat(row.amount()).isEqualTo("10.00");
        });
        assertThat(related.page(finance, "sales-orders", 71201, "receipts", null, 20).items()).isEmpty();
        assertThat(related.page(financeScoped, "sales-orders", 71201, "settlement-allocations", null, 20).items()).isEmpty();
        jdbc.update("UPDATE shipment SET owner_department_id=1001 WHERE id=71501");
        assertThat(related.page(financeScoped, "sales-orders", 71201, "settlement-allocations", null, 20).items()).isEmpty();
        jdbc.update("UPDATE receipt SET owner_department_id=1001 WHERE id=72501");
        assertThat(related.page(financeScoped, "sales-orders", 71201, "settlement-allocations", null, 20).items()).hasSize(1);
    }

    @Test void purchaseRelationsRejectOtherCompaniesAndUnauthorizedFinancialDocuments() {
        jdbc.update("UPDATE purchase_order_line SET warehouse_id=70301 WHERE id=71801");
        jdbc.update("""
                INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date)
                VALUES (82001,70101,70201,'关联收货',70401,70301,'2026-11-15')
                """);
        jdbc.update("""
                INSERT INTO goods_receipt_line(id,tenant_id,company_id,receipt_id,line_no,order_line_id,
                    sku_id,unit_id,unit_rate,qty,base_qty)
                VALUES (82002,70101,70201,82001,1,71801,71001,70701,1,1,1)
                """);
        jdbc.update("""
                INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,
                    amount,business_date,related_order_id)
                VALUES (82011,70101,70201,'关联付款','supplier',70401,70501,10,'2026-11-15',71701),
                       (82012,70101,70201,'同供应商未关联付款','supplier',70401,70501,10,'2026-11-15',NULL)
                """);
        jdbc.update("""
                INSERT INTO purchase_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,tax_rate,
                    untaxed_amount,tax_amount,recognized_receipt_id)
                VALUES (82020,70101,70201,71701,'freight','buyer',6,0,6,0,82001)
                """);
        jdbc.update("""
                INSERT INTO payable(id,tenant_id,company_id,supplier_id,kind,source_type,source_id,source_line_id,
                    amount,untaxed_amount,tax_amount,business_date,period_id)
                VALUES (82021,70101,70201,70401,'charge','purchase_order_fee',82001,82020,6,6,0,'2026-11-15',70601)
                """);
        jdbc.update("""
                INSERT INTO partner_open_item(id,tenant_id,company_id,partner_id,item_type,source_id,direction,
                    amount,open_amount,aging_date)
                VALUES (82022,70101,70201,70401,'payable',82021,'C',6,1,'2026-11-15'),
                       (82023,70101,70201,70401,'payment',82012,'D',10,5,'2026-11-15')
                """);
        jdbc.update("""
                INSERT INTO settlement_allocation(id,tenant_id,company_id,partner_id,debit_item_id,credit_item_id,
                    amount,allocation_type)
                VALUES (82024,70101,70201,70401,82023,82022,5,'manual')
                """);
        var permission = Set.of("purchase_order:view", "goods_receipt:view", "payment:view", "settlement:view");
        var all = new CompanyAccess(70101, 101, 70201, false, Set.of(70301L), permission);
        assertThat(related.page(all, "purchase-orders", 71701, "goods-receipts", null, 20).items())
                .extracting(OrderRelatedDocuments.Row::id).containsExactly("82001");
        assertThat(related.page(all, "purchase-orders", 71701, "payments", null, 20).items())
                .extracting(OrderRelatedDocuments.Row::id).containsExactly("82011");
        assertThat(related.page(all, "purchase-orders", 71701, "settlement-allocations", null, 20).items())
                .singleElement().satisfies(row -> {
                    assertThat(row.documentId()).isEqualTo("82012");
                    assertThat(row.amount()).isEqualTo("5.00");
                });
        denied(new CompanyAccess(70101, 101, 70202, true, Set.of(), permission), "goods-receipts", 404);
        denied(new CompanyAccess(70102, 101, 70203, true, Set.of(), permission), "goods-receipts", 404);
        denied(new CompanyAccess(70101, 101, 70201, false, Set.of(70302L), permission), "goods-receipts", 404);
        denied(new CompanyAccess(70101, 101, 70201, true, Set.of(), Set.of("purchase_order:view")), "payments", 403);
        denied(new CompanyAccess(70101, 101, 70201, true, Set.of(), Set.of("purchase_order:view", "payment:view", "settlement:view")),
                "settlement-allocations", 403);
    }

    private void denied(CompanyAccess actor, String resource, int status) {
        assertThatThrownBy(() -> related.page(actor, "purchase-orders", 71701, resource, null, 20))
                .isInstanceOfSatisfying(ApiException.class, cause -> assertThat(cause.getStatus()).isEqualTo(status));
    }
}
