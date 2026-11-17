package cn.erp.dashboard;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql", config=@SqlConfig(encoding="UTF-8"))
class DashboardIT {
    private static final Set<String> PERMISSIONS=Set.of("dashboard:view","cost:view","gl_report:view","crm_statistic:view","crm_target:view",
            "batch:view","receipt:view","purchase_order:view","qc_inspection:view","mfg_report:view",ReportType.SALES_SUMMARY.permission(),
            ReportType.GROSS_MARGIN.permission(),ReportType.INVENTORY_SUMMARY.permission(),ReportType.INVENTORY_FLOW.permission(),
            ReportType.PURCHASE_SUMMARY.permission(),ReportType.RECEIVABLE_AGING.permission());
    @Autowired JdbcTemplate jdbc;
    @Autowired DashboardService dashboards;
    @Autowired ReportService reports;
    private long user;

    @BeforeEach void salesFacts() {
        user=Ids.next();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,70101,'m19-dashboard-test','驾驶舱验收','验收占位')",user);
        jdbc.update("""
                UPDATE shipment SET business_date='2026-11-03',status='signed_with_diff',posted_at=UTC_TIMESTAMP(3),
                    period_id=70601,sign_date='2026-11-04',sign_period_id=70601,signed_at=UTC_TIMESTAMP(3),cost_amount=40,
                    signed_cost_amount=30,receivable_amount=300,untaxed_amount=270,tax_amount=30,owner_department_id=42,owner_user_id=?
                WHERE id=71501
                """,user);
        jdbc.update("""
                UPDATE shipment_line SET unit_cost=10,cost_amount=40,signed_base_qty=3,diff_base_qty=1,diff_disposition='pending',
                    signed_cost_amount=30,diff_cost_amount=10,receivable_amount=300,untaxed_amount=270,tax_amount=30 WHERE id=71601
                """);
    }

    @Test void salesAndMarginsMatchReportAndLoadEveryMetric() {
        var actor=actor();
        var result=dashboards.data(actor,"2026-11",null,false);
        assertThat(result.items()).extracting(DashboardMetrics.MetricData::code).containsExactly("sales","gross_margin","receivables","inventory","purchase","finance","production","crm");
        var query=new ReportQuery(LocalDate.parse(result.dateFrom()),LocalDate.parse(result.dateTo()),null,null,null,null,false);
        assertThat(amount(result,0,0)).isEqualByComparingTo(reports.snapshot(actor,ReportType.SALES_SUMMARY,query).summary().get("amount").toString());
        assertThat(amount(result,1,0)).isEqualByComparingTo(reports.snapshot(actor,ReportType.GROSS_MARGIN,query).summary().get("grossMargin").toString());
        assertThat(result.items().getFirst().plots().getFirst().points()).hasSize(12);
        assertThat(result.items().getFirst().plots().get(1).points()).extracting(DashboardMetrics.Value::label).contains("甲客户");
        assertThat(result.items().get(1).plots().getFirst().points()).extracting(DashboardMetrics.Value::label).contains("测试商品");
    }

    @Test void separatesCacheAndLayoutAcrossTenantCompanyWarehouseDepartmentAndCostPermission() {
        var full=actor();
        assertThat(amount(dashboards.data(full,"2026-11","sales",false),0,0)).isEqualByComparingTo("300.00");
        jdbc.update("UPDATE shipment_line SET receivable_amount=900,untaxed_amount=870 WHERE id=71601");
        assertThat(amount(dashboards.data(full,"2026-11","sales",false),0,0)).isEqualByComparingTo("300.00");
        var restored=dashboards.save(full,new DashboardService.LayoutInput(0L,List.of("gross_margin","sales")));
        assertThat(restored.metrics()).containsExactly("gross_margin","sales");

        var foreignTenant=new CompanyAccess(70102,user,70203,true,Set.of(),PERMISSIONS);
        var otherCompany=new CompanyAccess(70101,user,70202,true,Set.of(),PERMISSIONS);
        var noWarehouse=new CompanyAccess(70101,user,70201,false,Set.of(),PERMISSIONS);
        var otherDepartment=new CompanyAccess(70101,user,70201,true,Set.of(),PERMISSIONS,OrgScope.restricted(Set.of(43L),Set.of(),43));
        var rightDepartment=new CompanyAccess(70101,user,70201,false,Set.of(70301L),PERMISSIONS,OrgScope.restricted(Set.of(42L),Set.of(),42));
        for (var restricted : List.of(foreignTenant,otherCompany,noWarehouse,otherDepartment))
            assertThat(amount(dashboards.data(restricted,"2026-11","sales",false),0,0)).isZero();
        assertThat(amount(dashboards.data(rightDepartment,"2026-11","sales",false),0,0)).isEqualByComparingTo("900.00");
        assertThat(dashboards.layout(otherCompany).version()).isZero();

        var visible=new HashSet<>(PERMISSIONS);visible.remove("cost:view");
        var noCost=new CompanyAccess(70101,user,70201,true,Set.of(),visible);
        assertThat(dashboards.layout(noCost).metrics()).containsExactly("sales");
        assertThat(dashboards.available(noCost)).extracting(DashboardMetric.Definition::code).doesNotContain("gross_margin","finance");
        assertThatThrownBy(()->dashboards.data(noCost,"2026-11","gross_margin",false)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThatThrownBy(()->dashboards.save(noCost,new DashboardService.LayoutInput(1L,List.of("gross_margin"))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThatThrownBy(()->dashboards.save(full,new DashboardService.LayoutInput(0L,List.of("sales"))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(409));
    }

    @Test void handRefreshSharesTwoAttemptLimitAcrossCompanies() {
        var actor=actor();
        dashboards.data(actor,"2026-11","sales",true);
        dashboards.data(new CompanyAccess(70101,user,70202,true,Set.of(),PERMISSIONS),"2026-11","sales",true);
        assertThatThrownBy(()->dashboards.data(actor,"2026-11","sales",true)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(429));
    }

    private CompanyAccess actor() {return new CompanyAccess(70101,user,70201,true,Set.of(),PERMISSIONS);}
    private static BigDecimal amount(DashboardService.Result data,int metric,int value) {
        return new BigDecimal(data.items().get(metric).values().get(value).value());
    }
}
