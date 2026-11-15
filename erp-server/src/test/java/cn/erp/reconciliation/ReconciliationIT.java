package cn.erp.reconciliation;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class ReconciliationIT {
    private static final long TENANT=70101,COMPANY=70201,PERIOD=70601;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReconciliationChecks checks;
    @Autowired ReconciliationService service;
    private final CompanyAccess scope=new CompanyAccess(TENANT,1,COMPANY,true,Set.of(),Set.of());

    @BeforeEach
    void makeConsistentBusinessFacts() {
        // 约束夹具原先故意留下彼此不一致的余额，用真实期初流水与分录补成可核对闭环。
        jdbc.update("UPDATE inventory_movement SET movement_type='opening_in',on_hand_delta=10,blocked_delta=1,cost_delta=100 WHERE id=72301");
        jdbc.update("UPDATE partner_open_item SET open_amount=90 WHERE id IN (72701,72702)");
        jdbc.update("UPDATE document_reversal SET status='rejected' WHERE tenant_id=70101 AND company_id=70201 AND status='pending'");
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id,balance) VALUES(70512,70101,70201,70502,0),(70514,70101,70201,70504,0)");
        jdbc.update("UPDATE journal SET source_type='opening',total_debit=110,total_credit=110 WHERE id=73001");
        jdbc.update("""
                INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,amount,cash_account_id,warehouse_id,sku_id)
                VALUES(74001,70101,70201,73001,1,'FUNDS','D',10,70501,NULL,NULL),
                      (74002,70101,70201,73001,2,'INVENTORY','D',100,NULL,70301,71001),
                      (74003,70101,70201,73001,3,'OPENING_EQUITY','C',110,NULL,NULL,NULL)
                """);
    }

    @ParameterizedTest
    @ValueSource(strings={"inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close"})
    void completeFactsAndProjectionsHaveZeroDifference(String type) {
        assertThat(checks.check(scope,type,type.equals("period_close")?PERIOD:null)).isEmpty();
    }

    @Test
    void detectsActualDivergencesForAllSevenBalanceChecksAndPeriodCheck() {
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=11,reserved_qty=3,blocked_qty=2 WHERE id=71901");
        jdbc.update("UPDATE inventory_cost SET qty=12,total_cost=105 WHERE id=72001");
        jdbc.update("UPDATE cash_account_balance SET balance=11 WHERE id=70511");
        jdbc.update("UPDATE partner_open_item SET open_amount=80 WHERE id=72701");
        jdbc.update("UPDATE journal_line SET amount=99 WHERE id=74002");
        assertValue("inventory_balance","/在库","10","11");
        assertValue("inventory_balance","/冻结","1","2");
        assertValue("inventory_cost","/数量","11","12");
        assertValue("inventory_cost","/成本","100","105");
        assertValue("reservation","70301/71001//","4","3");
        assertValue("cash_balance","70501","10","11");
        assertValue("open_item","72701","90","80");
        assertValue("journal_balance","/借方","110","109");
        assertValue("ledger_inventory","INVENTORY","105","99");
        assertThat(checks.check(scope,"period_close",PERIOD)).isNotEmpty();
    }

    @Test
    void reverseChecksFindMovementsAndAccountsWhoseProjectionIsMissing() {
        jdbc.update("DELETE FROM inventory_balance WHERE id=71901");
        assertValue("inventory_balance","/在库","10","0");
        assertValue("reservation","70301/71001//","4","0");
        jdbc.update("DELETE FROM inventory_cost WHERE id=72001");
        assertValue("inventory_cost","/成本","100","0");
        jdbc.update("DELETE FROM cash_account_balance WHERE id=70511");
        assertValue("cash_balance","70501","10","0");
        jdbc.update("DELETE FROM journal_line WHERE journal_id=73001");
        assertValue("journal_balance","/借方","110","0");
    }

    @Test
    void periodCheckUsesRealPendingConfirmationAndRefundStates() {
        jdbc.update("UPDATE receipt SET status='pending_confirm' WHERE id=72501");
        jdbc.update("UPDATE refund_request SET status='pending_review' WHERE id=72601");
        jdbc.update("""
                INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,amount,business_date,status)
                VALUES(75001,70101,70201,'P-PENDING','supplier',70401,70501,20,'2026-11-15','pending_confirm')
                """);
        var differences=checks.check(scope,"period_close",PERIOD);
        assertThat(differences).extracting(ReconciliationChecks.Difference::key).contains("receipt","payment","refund_request");
        jdbc.update("UPDATE refund_request SET status='pending_payment',reviewed_at=UTC_TIMESTAMP(3) WHERE id=72601");
        assertThat(checks.check(scope,"period_close",PERIOD)).extracting(ReconciliationChecks.Difference::key).contains("refund_request");
    }

    @Test
    void requestPersistsOutboxAndPerformStoresResultsExactlyOnce() {
        var requested=service.request(scope,"inventory_balance",null);
        long run=Long.parseLong(requested.get("id").toString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND aggregate_type='reconciliation' AND aggregate_id=? AND event_type='reconciliation.requested'",Long.class,TENANT,run)).isEqualTo(1);
        service.perform(TENANT,run,false);
        assertThat(service.get(scope,run).get("status")).isEqualTo("passed");
        assertThat(service.get(scope,run).get("diffCount")).isEqualTo(0L);

        jdbc.update("UPDATE inventory_balance SET blocked_qty=2 WHERE id=71901");
        long failed=Long.parseLong(service.request(scope,"inventory_balance",null).get("id").toString());
        service.perform(TENANT,failed,false);
        service.perform(TENANT,failed,false);
        assertThat(service.get(scope,failed).get("status")).isEqualTo("failed");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reconciliation_diff WHERE tenant_id=? AND run_id=?",Long.class,TENANT,failed)).isEqualTo(1);
        @SuppressWarnings("unchecked") var items=(List<java.util.Map<String,Object>>)service.differences(scope,failed,null,20).get("items");
        assertThat(items).singleElement().satisfies(diff->{
            assertThat(diff.get("expectedValue")).isEqualTo("1.000000");
            assertThat(diff.get("actualValue")).isEqualTo("2.000000");
        });
    }

    @Test
    void backgroundTaskRechecksActorAndQueriesRespectTenantAndCompany() {
        long run=Long.parseLong(service.request(scope,"inventory_balance",null).get("id").toString());
        service.perform(TENANT,run,true);
        assertThat(service.get(scope,run).get("status")).isEqualTo("error");
        var foreign=new CompanyAccess(70102,1,70203,true,Set.of(),Set.of());
        assertThatThrownBy(()->service.get(foreign,run)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        assertThat(checks.check(foreign,"inventory_balance",null)).isEmpty();
        assertThatThrownBy(()->checks.check(foreign,"period_close",PERIOD)).isInstanceOf(ApiException.class);
        var partial=new CompanyAccess(TENANT,1,COMPANY,false,Set.of(70301L),Set.of());
        assertThatThrownBy(()->service.request(partial,"inventory_balance",null)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
    }

    private void assertValue(String type,String suffix,String expected,String actual) {
        assertThat(checks.check(scope,type,null)).filteredOn(diff->diff.key().endsWith(suffix))
                .anySatisfy(diff->{
                    assertThat(diff.expected()).isEqualByComparingTo(expected);
                    assertThat(diff.actual()).isEqualByComparingTo(actual);
                });
    }

    @Test
    void storesLegitimateLargeMonetaryDifferencesWithoutPrecisionLoss() {
        var actual=new java.math.BigDecimal("12345678901234567890.12");
        jdbc.update("UPDATE cash_account_balance SET balance=? WHERE id=70511",actual);
        var task=service.request(scope,"cash_balance",null);
        long id=Long.parseLong(task.get("id").toString());
        service.perform(TENANT,id,false);
        assertThat(jdbc.queryForObject("SELECT actual_value FROM reconciliation_diff WHERE run_id=? AND object_key='70501'",java.math.BigDecimal.class,id))
                .isEqualByComparingTo(actual);
    }
}
