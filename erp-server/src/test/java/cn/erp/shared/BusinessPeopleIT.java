package cn.erp.shared;

import cn.erp.identity.CompanyAccess;
import cn.erp.reporting.*;
import cn.erp.exporting.ExportCsv;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class BusinessPeopleIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @Autowired ObjectMapper json;
    CompanyAccess actor;
    @BeforeEach void prepare() {
        for(long id:List.of(89001L,89002L,89003L)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,70101,?,?,'非登录测试摘要')",id,"rep"+id,id==89001?"甲业务员":"乙业务员");
            jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,70101,?,'company',?)",Ids.next(),id,id==89003?70202:70201);
        }
        actor=new CompanyAccess(70101,89001,70201,true,Set.of(),Set.copyOf(jdbc.queryForList("SELECT code FROM sys_permission",String.class)));
    }
    @Test void X01_01_defaultAndDraftChangeDoNotRewritePermissionOwnership() {
        BusinessPeople.assign(jdbc,actor,"sales_order",71201,null);
        assertThat(value("sales_order",71201,"sales_user_id")).isEqualTo(89001);
        assertThat(value("sales_order",71201,"owner_user_id")).isZero();
        BusinessPeople.assign(jdbc,actor,"sales_order",71201,89002L);
        assertThat(value("sales_order",71201,"sales_user_id")).isEqualTo(89002);
        jdbc.update("UPDATE sys_user SET status='disabled' WHERE id=89002");
        BusinessPeople.assign(jdbc,actor,"sales_order",71201,89002L);
        jdbc.update("UPDATE sales_order SET status='pending' WHERE id=71201");
        assertThatThrownBy(()->BusinessPeople.assign(jdbc,actor,"sales_order",71201,89001L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(422));
    }
    @Test void X01_02_sourceInheritanceAndCompanyValidation() {
        jdbc.update("UPDATE sales_order SET sales_user_id=89002 WHERE id=71201");
        BusinessPeople.assign(jdbc,actor,"shipment",71501,null);
        assertThat(value("shipment",71501,"sales_user_id")).isEqualTo(89002);
        assertThatThrownBy(()->BusinessPeople.assign(jdbc,actor,"shipment",71501,89003L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->BusinessPeople.assign(jdbc,actor,"shipment",71501,0L)).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE shipment SET owner_department_id=10,owner_user_id=89001 WHERE id=71501");
        var denied=new CompanyAccess(70101,89001,70201,true,Set.of(),actor.permissions(),OrgScope.restricted(Set.of(),Set.of(),0));
        assertThatThrownBy(()->BusinessPeople.assign(jdbc,denied,"shipment",71501,89001L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }
    @Test void X01_03_mixedSourceRepresentativesRequireExplicitSelection() {
        jdbc.update("UPDATE sales_order SET sales_user_id=89001 WHERE id=71201");
        jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,price_tax_inclusive,sales_user_id) VALUES(89101,70101,70201,'REP-2',70401,'2026-11-15',1,89002)");
        jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount) SELECT 89102,tenant_id,company_id,89101,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount FROM sales_order_line WHERE id=71301");
        jdbc.update("INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty) VALUES(89103,70101,70201,71501,2,89102,71001,70701,1,1,1)");
        assertThatThrownBy(()->BusinessPeople.assign(jdbc,actor,"shipment",71501,null)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("BUSINESS_USER_REQUIRED"));
        BusinessPeople.assign(jdbc,actor,"shipment",71501,89002L);assertThat(value("shipment",71501,"sales_user_id")).isEqualTo(89002);
    }
    @Test void X01_04_businessSummaryExportAndDepartmentIntersection() {
        jdbc.update("UPDATE shipment SET sales_user_id=89001,owner_department_id=10,owner_user_id=89001,status='signed',posted_at=UTC_TIMESTAMP(3),period_id=70601,sign_date='2026-11-15',sign_period_id=70601,signed_at=UTC_TIMESTAMP(3),cost_amount=40,signed_cost_amount=40,receivable_amount=100,untaxed_amount=100,tax_amount=0 WHERE id=71501");
        jdbc.update("UPDATE shipment_line SET unit_cost=10,signed_base_qty=4,receivable_amount=100,untaxed_amount=100,tax_amount=0,cost_amount=40,signed_cost_amount=40 WHERE id=71601");
        var query=new ReportQuery(LocalDate.of(2026,11,1),LocalDate.of(2026,11,15),null,null,null,null,false,false,89001L,"business_user");
        for(var type:List.of(ReportType.SALES_SUMMARY,ReportType.GROSS_MARGIN)) {
            var report=reports.snapshot(actor,type,query);assertThat(report.items()).hasSize(1);assertThat(report.items().getFirst()).containsEntry("businessUserName","甲业务员");
            assertThat(report.summary()).containsEntry("amount","100.00");
            assertThat(new String(ExportCsv.writeReport(type,false,true,report.items(),json),StandardCharsets.UTF_8)).contains("销售员","甲业务员");
        }
        var restricted=new CompanyAccess(70101,89001,70201,true,Set.of(),actor.permissions(),OrgScope.restricted(Set.of(),Set.of(),0));
        assertThat(reports.snapshot(restricted,ReportType.SALES_SUMMARY,query).items()).isEmpty();
    }
    long value(String table,long id,String field) {return jdbc.queryForObject("SELECT "+field+" FROM "+table+" WHERE id=?",Long.class,id);}
}
