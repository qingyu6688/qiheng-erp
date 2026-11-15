package cn.erp.shared;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
class NumberCapacityIT {
    private static final String COMPANY_CODE = "C123456789012345";
    private static final List<String> NUMBER_COLUMNS = List.of(
            "sales_order.order_no",
            "shipment.shipment_no",
            "sales_return.return_no",
            "purchase_requisition.requisition_no",
            "purchase_order.order_no",
            "goods_receipt.receipt_no",
            "purchase_return.return_no",
            "stock_transfer.transfer_no",
            "stock_count.count_no",
            "stock_adjustment.adjust_no",
            "opening_entry.entry_no",
            "receipt.receipt_no",
            "payment.payment_no",
            "refund_request.refund_no",
            "account_transfer.transfer_no",
            "document_change.change_no",
            "document_reversal.reversal_no");
    @Autowired JdbcTemplate jdbc;
    @Autowired DocumentNumbers numbers;
    private long tenantId;
    private long companyId;
    private long userId;

    @BeforeEach
    void seedCommittedCompany() {
        tenantId = Ids.next();
        companyId = Ids.next();
        userId = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)", tenantId, "CAP" + tenantId, "单号容量测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",
                companyId, tenantId, COMPANY_CODE, "长编码公司");
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'number_user','申请人','test')",
                userId, tenantId);
    }

    @AfterEach
    void cleanOwnData() {
        jdbc.update("DELETE FROM purchase_requisition WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM sys_user WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM company WHERE tenant_id=?", tenantId);
        jdbc.update("DELETE FROM tenant WHERE id=?", tenantId);
    }

    @Test
    void fullCompanyCodeFitsEveryBusinessNumberColumnAndPersistsInDocument() {
        var scope = new CompanyAccess(tenantId, userId, companyId, true, Set.of(), Set.of());
        String number = numbers.next(scope, "PR", YearMonth.of(2026, 11));
        assertThat(number).isEqualTo("PR-" + COMPANY_CODE + "-202611-000001").hasSize(33);
        for (String qualified : NUMBER_COLUMNS) {
            String[] column = qualified.split("\\.");
            Long capacity = jdbc.queryForObject("""
                    SELECT CHARACTER_MAXIMUM_LENGTH FROM information_schema.COLUMNS
                    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND COLUMN_NAME=?
                    """, Long.class, column[0], column[1]);
            assertThat(capacity).as(qualified).isGreaterThanOrEqualTo((long) number.length());
        }
        long requisitionId = Ids.next();
        jdbc.update("""
                INSERT INTO purchase_requisition(id,tenant_id,company_id,requisition_no,requester_id,business_date)
                VALUES(?,?,?,?,?,'2026-11-15')
                """, requisitionId, tenantId, companyId, number, userId);
        assertThat(jdbc.queryForObject("SELECT requisition_no FROM purchase_requisition WHERE id=?",
                String.class, requisitionId)).isEqualTo(number);
    }
}
