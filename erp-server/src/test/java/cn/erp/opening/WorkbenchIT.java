package cn.erp.opening;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.workbench.DocumentBrowser;
import cn.erp.workbench.WorkbenchService;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=cn.erp.ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class WorkbenchIT extends OpeningTestData {
    @Autowired DocumentBrowser browser;
    @Autowired WorkbenchService workbench;
    @Autowired cn.erp.workbench.SetupChecklist setup;
    @AfterEach void cleanupOrders() {
        jdbc.update("DELETE FROM sales_order_line WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM sales_order WHERE tenant_id=?",tenant);
    }
    @Test void allDocumentKindsHaveScopedEmptyListsAndCounts() {
        for(String kind:DocumentBrowser.SPECS.keySet()) {
            var spec=browser.spec(kind);
            var page=browser.page(maker,kind,new DocumentBrowser.Filter("不存在的单号或备注",null,spec.partner()==null?null:partner,
                    Set.of("lines","warehouse_id","transfer").contains(Objects.toString(spec.warehouse(),""))?warehouse:null,DAY.minusDays(1),DAY,null,null,false),null,20,true);
            assertThat(page.get("items")).as(kind).asList().isEmpty();assertThat(page.get("total")).isEqualTo(0L);
        }
    }
    @Test void datePartyProgressAndWarehouseFiltersShareCountsAndNeverIncludeDeletedDrafts() {
        long first=order(warehouse,"pending",false,"2026-11-01"),second=order(otherWarehouse,"draft",false,"2026-11-02");order(warehouse,"draft",true,"2026-11-01");
        var filter=new DocumentBrowser.Filter(null,"pending",partner,warehouse,LocalDate.parse("2026-11-01"),LocalDate.parse("2026-11-01"),"none","none",false);
        var page=browser.page(maker,"sales-orders",filter,null,20,true);
        assertThat(page.get("total")).isEqualTo(1L);assertThat(page.get("statusCounts")).asString().contains("pending=1").doesNotContain("draft");assertThat(page.get("items").toString()).contains(Long.toString(first)).doesNotContain(Long.toString(second));
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),maker.permissions());
        assertThat(browser.page(limited,"sales-orders",DocumentBrowser.Filter.empty(),null,20,true).get("total")).isEqualTo(1L);
        var foreign=new CompanyAccess(tenant,user,otherCompany,true,Set.of(),maker.permissions());assertThat(browser.page(foreign,"sales-orders",DocumentBrowser.Filter.empty(),null,20,true).get("total")).isEqualTo(0L);
    }
    @Test void totalsExcludeEntireMultiWarehouseOrdersOutsideCurrentScope() {
        long id=order(warehouse,"draft",false,"2026-11-01");line(id,otherWarehouse,2);
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),maker.permissions());
        assertThat(browser.page(limited,"sales-orders",DocumentBrowser.Filter.empty(),null,20,true).get("total")).isEqualTo(0L);
        assertThatThrownBy(()->browser.page(limited,"sales-orders",new DocumentBrowser.Filter(null,null,null,otherWarehouse,null,null,null,null,false),null,20,true)).isInstanceOf(ApiException.class);
    }
    @Test void workbenchDoesNotReturnUnauthorizedBlocksAndExcludesOwnApproval() {
        long id=order(warehouse,"pending",false,"2026-11-01");
        var own=workbench.summary(maker);assertThat(own.get("todos").toString()).doesNotContain(Long.toString(id));
        assertThat(own.get("todosByType").toString()).doesNotContain(Long.toString(id));
        var other=workbench.summary(reviewer);assertThat(other.get("todos").toString()).contains(Long.toString(id),"期初双角色往来","amount=0.00");
        assertThat(other.get("todosByType").toString()).contains("sales-orders",Long.toString(id),"createdAt");
        var viewer=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),Set.of("warehouse:view"));
        var hidden=workbench.summary(viewer);assertThat(hidden.get("blocks")).asList().isEmpty();assertThat(hidden).doesNotContainKeys("inventoryAlerts","periods","recentActions");
        assertThat(hidden).containsEntry("todosByType",Map.of());
    }
    @Test void setupChecklistFollowsCompanyDataAndHidesStepsWithoutPermission() {
        // 期间按真实日期判断，测试里让期间覆盖今天，避免随运行日期变化
        var today=LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        jdbc.update("UPDATE accounting_period SET start_date=?,end_date=? WHERE id=?",today.withDayOfMonth(1),today.withDayOfMonth(today.lengthOfMonth()),period);
        var before=setup.status(maker);
        assertThat(before).containsEntry("completed",false);
        assertThat(steps(before)).containsEntry("warehouse",true).containsEntry("customer",true).containsEntry("supplier",true)
                .containsEntry("sku",true).containsEntry("cash-account",true).containsEntry("period",true)
                .containsEntry("stock-opening",false).containsEntry("partner-opening",false).containsEntry("first-document",false);
        var other=new CompanyAccess(tenant,user,otherCompany,true,Set.of(),maker.permissions());
        assertThat(steps(setup.status(other))).containsEntry("warehouse",false).containsEntry("cash-account",false).containsEntry("period",false);
        order(warehouse,"draft",false,today.toString());
        var after=setup.status(maker);
        assertThat(after).containsEntry("completed",true);assertThat(steps(after)).containsEntry("first-document",true);
        // 今天不在任何开放期间内时，清单回到未完成
        jdbc.update("UPDATE accounting_period SET start_date=?,end_date=? WHERE id=?",today.minusYears(1).withDayOfMonth(1),today.minusYears(1).withDayOfMonth(1),period);
        assertThat(setup.status(maker)).containsEntry("completed",false);
        var viewer=new CompanyAccess(tenant,user,company,false,Set.of(warehouse),Set.of("warehouse:view"));
        var limited=setup.status(viewer);
        assertThat(steps(limited)).containsOnlyKeys("warehouse");assertThat(limited.toString()).doesNotContain("customer","period");
    }
    @SuppressWarnings("unchecked")
    static Map<String,Boolean> steps(Map<String,Object> status) {
        var result=new LinkedHashMap<String,Boolean>();
        for(var step:(List<Map<String,Object>>)status.get("steps")) result.put((String)step.get("key"),(Boolean)step.get("done"));
        return result;
    }
    @Test void cursorPaginationAndFilterValidationRemainStable() {
        order(warehouse,"pending",false,"2026-11-01");order(warehouse,"pending",false,"2026-11-01");
        var first=browser.page(maker,"sales-orders",DocumentBrowser.Filter.empty(),null,1,true);var second=browser.page(maker,"sales-orders",DocumentBrowser.Filter.empty(),Long.parseLong(first.get("nextCursor").toString()),1,true);
        assertThat(first.get("items").toString()).isNotEqualTo(second.get("items").toString());assertThat(second.get("nextCursor")).isNull();assertThat(second.get("total")).isEqualTo(2L);
        assertThatThrownBy(()->browser.page(maker,"sales-orders",new DocumentBrowser.Filter(null,null,null,null,DAY,DAY.minusDays(1),null,null,false),null,20,true)).isInstanceOf(ApiException.class);
    }
    @Test void remarkSearchReturnsOnlyListFieldsAndScopedReferenceNames() {
        long id=order(warehouse,"pending",false,"2026-11-01");jdbc.update("UPDATE sales_order SET remark='紧急补货' WHERE id=?",id);
        var result=browser.page(maker,"sales-orders",new DocumentBrowser.Filter("补货",null,null,null,null,null,null,null,false),null,20,true);
        assertThat(result.get("items").toString()).contains("期初双角色往来","SO"+id).doesNotContain("lines=","password","bankAccount");
        assertThat(result.get("total")).isEqualTo(1L);
    }
    long order(long wh,String state,boolean deleted,String day) {
        long id=Ids.next();jdbc.update("INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,delivery_date,price_tax_inclusive,status,created_by,is_deleted) VALUES(?,?,?,?,?,?,?,1,?,?,?)",id,tenant,company,"SO"+id,partner,day,day,state,user,deleted);
        line(id,wh,1);return id;
    }
    void line(long id,long wh,int no) {jdbc.update("INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,unit_price,amount,line_net,receivable_net,untaxed_amount,tax_rate,tax_amount) VALUES(?,?,?,?,?,?,?,?,1,1,1,10,10,10,10,10,0,0)",Ids.next(),tenant,company,id,no,sku,wh,unit);}
}
