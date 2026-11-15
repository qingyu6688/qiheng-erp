package cn.erp.inventory;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
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
class TransferCostsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransferCosts costs;
    @Autowired cn.erp.reconciliation.ReconciliationChecks checks;
    private final CompanyAccess scope=new CompanyAccess(70101,1,70201,true,Set.of(),Set.of());
    private long line;

    @BeforeEach
    void seedTransfer() {
        long transfer=Ids.next(),target=Ids.next();line=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,70101,70201,?,'目标仓')",target,"W"+target);
        jdbc.update("""
                INSERT INTO stock_transfer(id,tenant_id,company_id,transfer_no,from_warehouse_id,to_warehouse_id,
                    business_date,status) VALUES(?,70101,70201,?,70301,?,'2026-11-15','shipped')
                """,transfer,"TR"+transfer,target);
        jdbc.update("""
                INSERT INTO stock_transfer_line(id,tenant_id,company_id,transfer_id,line_no,sku_id,unit_id,
                    unit_rate,qty,base_qty,unit_cost,cost_amount) VALUES(?,70101,70201,?,1,71001,70701,1,2,2,0.005,0.01)
                """,line,transfer);
    }

    @Test
    void actualFirstReceiptKeepsExactResidualCostAndVersionPreventsDoubleConsumption() {
        assertThat(checks.check(scope,"ledger_inventory",null))
                .filteredOn(difference->difference.key().equals("GOODS_IN_TRANSIT"))
                .singleElement().satisfies(difference->assertThat(difference.expected()).isEqualByComparingTo("0.01"));
        var first=costs.receive(scope,line,0,d("1"),d("0.01"));
        assertThat(first.receivedCost()).isEqualByComparingTo("0.01");
        assertThat(first.remainingCost()).isEqualByComparingTo("0");
        assertThat(first.remainingQuantity()).isEqualByComparingTo("1");
        assertThat(checks.check(scope,"ledger_inventory",null))
                .noneMatch(difference->difference.key().equals("GOODS_IN_TRANSIT"));
        assertThat(jdbc.queryForObject("""
                SELECT cost_amount-received_cost_amount-diff_cost_amount FROM stock_transfer_line WHERE id=?
                """,BigDecimal.class,line)).isEqualByComparingTo("0");
        assertThatThrownBy(()->costs.receive(scope,line,0,d("1"),d("0.01")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
        var last=costs.receive(scope,line,1,d("1"),d("0.00"));
        assertThat(last.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(last.remainingCost()).isEqualByComparingTo("0");
        assertThat(last.receivedCost()).isEqualByComparingTo("0.01");
    }

    @Test
    void finalResolutionIncludesTheRemainingCentAndCannotResolveTwice() {
        jdbc.update("UPDATE stock_transfer_line SET diff_base_qty=1,diff_status='pending' WHERE id=?",line);
        costs.receive(scope,line,0,d("1"),d("0.00"));
        assertThatThrownBy(()->costs.resolveDifference(scope,line,1,d("1"),d("0.00")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getMessage()).contains("尾差"));
        var result=costs.resolveDifference(scope,line,1,d("1"),d("0.01"));
        assertThat(result.differenceCost()).isEqualByComparingTo("0.01");
        assertThat(result.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(result.remainingCost()).isEqualByComparingTo("0");
        assertThatThrownBy(()->costs.resolveDifference(scope,line,1,d("1"),d("0.01")))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void databaseRejectsOverConsumptionAndDifferentWarehouseScopesCannotRead() {
        assertThatThrownBy(()->jdbc.update("UPDATE stock_transfer_line SET received_base_qty=1,received_cost_amount=0.02 WHERE id=?",line))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_stock_transfer_line_cumulative_cost");
        var restricted=new CompanyAccess(70101,1,70201,false,Set.of(70301L),Set.of());
        assertThatThrownBy(()->costs.remaining(restricted,line))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }

    private static BigDecimal d(String value) {return new BigDecimal(value);}
}
