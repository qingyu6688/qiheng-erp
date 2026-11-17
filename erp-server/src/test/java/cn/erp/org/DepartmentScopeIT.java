package cn.erp.org;

import cn.erp.identity.*;
import cn.erp.shared.*;
import cn.erp.salesorder.SalesOrderQueries;
import cn.erp.fulfillment.ShipmentQueries;
import cn.erp.documents.DocumentAccess;
import cn.erp.reporting.*;
import cn.erp.exporting.ExportAccess;
import cn.erp.workbench.DocumentBrowser;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class DepartmentScopeIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired OrgScopeResolver resolver;
    @Autowired DepartmentService departments;
    @Autowired SalesOrderQueries orders;
    @Autowired ShipmentQueries shipments;
    @Autowired DocumentAccess documents;
    @Autowired DocumentBrowser browser;
    @Autowired cn.erp.workbench.WorkbenchService workbench;
    @Autowired cn.erp.salesorder.SalesInventoryQueries inventory;
    @Autowired ReportService reports;
    @Autowired ExportAccess exports;
    @Autowired CommandResultAccess results;
    long user=88001,otherUser=88002,role=88011,root=88021,child=88022,sibling=88023;
    Set<String> permissions;
    @BeforeEach void prepare() {
        permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,must_change_password) VALUES(?,70101,'scope','范围用户','测试散列',0),(?,70101,'other','其他用户','测试散列',0)",user,otherUser);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,70101,'SCOPE','范围角色')",role);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,70101,?,?)",Ids.next(),user,role);
        jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,70101,?,'company',70201)",Ids.next(),user);
        for(long id:List.of(root,child,sibling)) jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,parent_id,code,name,path,depth) VALUES(?,70101,70201,?,?,?, ?,?)",id,id==child?root:null,"D"+id,"部门"+id,id==child?"/"+root+"/"+child+"/":"/"+id+"/",id==child?2:1);
        jdbc.update("INSERT INTO org_user_company(tenant_id,company_id,user_id) VALUES(70101,70201,?)",user);
        jdbc.update("INSERT INTO org_user_department(tenant_id,company_id,user_id,department_id,is_primary) VALUES(70101,70201,?,?,1)",user,root);
        jdbc.update("UPDATE sales_order SET owner_department_id=?,owner_user_id=? WHERE id=71201",root,user);
        jdbc.update("UPDATE shipment SET owner_department_id=?,owner_user_id=?,status='signed',posted_at=UTC_TIMESTAMP(3),period_id=70601,sign_date='2026-11-15',sign_period_id=70601,signed_at=UTC_TIMESTAMP(3),cost_amount=40,signed_cost_amount=40,receivable_amount=100,untaxed_amount=100,tax_amount=0 WHERE id=71501",child,otherUser);
        jdbc.update("UPDATE shipment_line SET unit_cost=10,signed_base_qty=4,receivable_amount=100,untaxed_amount=100,tax_amount=0,cost_amount=40,signed_cost_amount=40 WHERE id=71601");
        jdbc.update("UPDATE receipt SET owner_department_id=?,owner_user_id=? WHERE id=72501",sibling,otherUser);
    }
    void mode(String type) {jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(70101,70201,?,?) ON DUPLICATE KEY UPDATE scope_type=?",role,type,type);}
    CompanyAccess actor() {return new CompanyAccess(70101,user,70201,true,Set.of(),permissions,resolver.resolve(70101,70201,user));}
    CompanyAccess all() {return new CompanyAccess(70101,user,70201,true,Set.of(),permissions);}
    @Test void Y02_01_defaultAllAndRoleUnionRemainCompatible() {
        assertThat(actor().orgScope().allDepartments()).isTrue();
        mode("dept");assertThat(actor().orgScope().allows(root,otherUser)).isTrue();assertThat(actor().orgScope().allows(child,otherUser)).isFalse();
        long second=88012;jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,70101,'SECOND','第二角色')",second);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,70101,?,?)",Ids.next(),user,second);
        assertThat(actor().orgScope().allDepartments()).isTrue();
        jdbc.update("INSERT INTO org_role_data_scope(tenant_id,company_id,role_id,scope_type) VALUES(70101,70201,?,'self')",second);
        assertThat(actor().orgScope().allows(sibling,user)).isTrue();assertThat(actor().orgScope().allows(sibling,otherUser)).isFalse();
    }
    @Test void Y02_02_modesListsDetailsAndWarehouseIntersection() {
        mode("dept");assertThat(orders.get(actor(),71201)).containsEntry("id","71201");
        denied(()->shipments.get(actor(),71501));
        mode("dept_and_children");assertThat(shipments.get(actor(),71501)).containsEntry("id","71501");
        var partial=new CompanyAccess(70101,user,70201,false,Set.of(999L),permissions,actor().orgScope());denied(()->shipments.get(partial,71501));
        mode("self");assertThat(orders.get(actor(),71201)).containsEntry("id","71201");denied(()->shipments.get(actor(),71501));
        mode("custom");jdbc.update("INSERT INTO org_role_data_scope_dept(tenant_id,company_id,role_id,department_id) VALUES(70101,70201,?,?)",role,child);
        denied(()->orders.get(actor(),71201));assertThat(shipments.get(actor(),71501)).containsEntry("id","71501");
        var page=browser.page(actor(),"sales-orders",DocumentBrowser.Filter.empty(),null,20,true);
        assertThat(page.get("items")).isEqualTo(List.of());assertThat(page.get("total")).isEqualTo(0L);
    }
    @Test void Y02_03_moveRefreshesDescendantsWithoutRewritingOwnership() {
        mode("dept_and_children");assertThat(actor().orgScope().allows(child,otherUser)).isTrue();
        mode("all");
        departments.move(all(),child,"move-out",new OrgModels.Move(sibling,null,0L));
        mode("dept_and_children");
        assertThat(actor().orgScope().allows(child,otherUser)).isFalse();
        assertThat(jdbc.queryForObject("SELECT owner_department_id FROM shipment WHERE id=71501",Long.class)).isEqualTo(child);
    }
    @Test void Y02_04_reportsFilterBeforeTotalsAndProtectFilesAndOldResults() {
        var query=new ReportQuery(LocalDate.of(2026,11,1),LocalDate.of(2026,11,15),null,null,null,null,true);
        mode("dept");assertThat(reports.snapshot(actor(),ReportType.SALES_SUMMARY,query).items()).isEmpty();
        for(var type:ReportType.values()) reports.snapshot(actor(),type,query);
        denied(()->documents.view(actor(),"shipment",71501));denied(()->results.verify(actor(),"shipment.post",Map.of("id","71501")));
        mode("dept_and_children");assertThat(reports.snapshot(actor(),ReportType.SALES_SUMMARY,query).summary()).containsEntry("amount","100.00");
        var captured=exports.capture(actor());mode("dept");
        assertThatThrownBy(()->exports.covers(actor(),captured,ReportType.SALES_SUMMARY)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThat(exports.intersect(actor(),captured,ReportType.SALES_SUMMARY).orgScope().allows(child,otherUser)).isFalse();
    }
    @Test void Y02_05_unassignedLegacyRowsOnlyVisibleToAllScope() {
        jdbc.update("UPDATE sales_order SET owner_department_id=0,owner_user_id=? WHERE id=71201",user);
        mode("self");denied(()->orders.get(actor(),71201));
        mode("all");assertThat(orders.get(actor(),71201)).containsEntry("id","71201");
    }
    @Test void Y02_09_allSeventeenHeaderReadersRejectOtherDepartments() {
        var attachmentReaders=Set.of("sales_order","shipment","receipt","sales_return","refund_request",
                "purchase_requisition","purchase_order","goods_receipt","purchase_return","payment","account_transfer",
                "opening_entry","stock_adjustment","stock_count","stock_transfer","document_reversal","invoice_registration");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(88031,70101,70201,'SCOPE-W','范围分仓')");
        jdbc.update("INSERT INTO purchase_requisition(id,tenant_id,company_id,requisition_no,requester_id,business_date) VALUES(88101,70101,70201,'SCOPE-PR',?,'2026-11-15')",user);
        jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,reason) VALUES(88102,70101,70201,'SCOPE-SR',70401,70301,'2026-11-15','测试')");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date) VALUES(88103,70101,70201,'SCOPE-GR',70401,70301,'2026-11-15')");
        jdbc.update("INSERT INTO purchase_return(id,tenant_id,company_id,return_no,supplier_id,warehouse_id,business_date,reason) VALUES(88104,70101,70201,'SCOPE-RT',70401,70301,'2026-11-15','测试')");
        jdbc.update("INSERT INTO payment(id,tenant_id,company_id,payment_no,payment_type,partner_id,cash_account_id,amount,business_date) VALUES(88105,70101,70201,'SCOPE-P','supplier',70401,70501,1,'2026-11-15')");
        jdbc.update("INSERT INTO opening_entry(id,tenant_id,company_id,entry_no,entry_type,partner_id,direction,amount,business_date) VALUES(88106,70101,70201,'SCOPE-O','receivable',70401,'D',1,'2026-11-15')");
        jdbc.update("INSERT INTO stock_adjustment(id,tenant_id,company_id,adjust_no,warehouse_id,adjust_type,reason,business_date) VALUES(88107,70101,70201,'SCOPE-SA',70301,'gain','测试','2026-11-15')");
        jdbc.update("INSERT INTO stock_count(id,tenant_id,company_id,count_no,warehouse_id,count_mode,business_date) VALUES(88108,70101,70201,'SCOPE-C',70301,'watermark','2026-11-15')");
        jdbc.update("INSERT INTO stock_transfer(id,tenant_id,company_id,transfer_no,from_warehouse_id,to_warehouse_id,business_date) VALUES(88109,70101,70201,'SCOPE-T',70301,88031,'2026-11-15')");
        jdbc.update("INSERT INTO invoice_registration(id,tenant_id,company_id,invoice_type,partner_id,invoice_no,invoice_date,amount,tax_amount,tax_diff_amount) VALUES(88110,70101,70201,'sales',70401,'SCOPE-I','2026-11-15',1,0,0)");
        for(String table:attachmentReaders) jdbc.update("UPDATE "+table+" SET owner_department_id=?,owner_user_id=?,created_by=? WHERE tenant_id=70101 AND company_id=70201",root,user,user);
        mode("dept");
        for(String table:attachmentReaders) {
            long id=jdbc.queryForObject("SELECT MIN(id) FROM "+table+" WHERE tenant_id=70101 AND company_id=70201",Long.class);
            assertThat(documents.view(actor(),table,id)).as(table).isNotEmpty();
        }
        assertThat(jdbc.queryForList("SELECT 1 WHERE "+OrgOwnership.sourceCondition(actor(),"'shipment_diff'","71501"))).hasSize(1);
        assertThat(jdbc.queryForList("SELECT 1 WHERE "+OrgOwnership.sourceCondition(actor(),"'stock_transfer_event'","88109"))).hasSize(1);
        mode("custom");
        assertThat(jdbc.queryForList("SELECT 1 WHERE "+OrgOwnership.sourceCondition(actor(),"'stock_transfer_event'","88109"))).isEmpty();
        for(String table:attachmentReaders) {
            long id=jdbc.queryForObject("SELECT MIN(id) FROM "+table+" WHERE tenant_id=70101 AND company_id=70201",Long.class);
            denied(()->documents.view(actor(),table,id));
        }
    }
    @Test void Y02_12_inventoryDemandAndWorkbenchDoNotRevealOtherDepartmentOrders() {
        jdbc.update("UPDATE sales_order SET status='pending' WHERE id=71201");
        assertThat(workbench.summary(all()).get("inventoryAlerts")).asList().isNotEmpty();
        mode("custom");
        assertThat(workbench.summary(actor())).containsEntry("inventoryAlerts",List.of()).doesNotContainKey("recentActions");
        var stock=inventory.balances(actor(),null,"",null,100);
        assertThat(stock.get("items")).asList().allSatisfy(value->{
            var row=(Map<?,?>)value;assertThat(new java.math.BigDecimal(row.get("pendingDemandQty").toString())).isZero();
        });
        assertThat(stock.get("items")).asList().isNotEmpty();
    }
    void denied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));}
}
