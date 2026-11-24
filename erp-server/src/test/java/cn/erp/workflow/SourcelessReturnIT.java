package cn.erp.workflow;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import cn.erp.ErpApplication;
import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reporting.ReportQuery;
import cn.erp.reporting.ReportService;
import cn.erp.reporting.ReportType;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.SalesReturnService;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import static cn.erp.workflow.WorkflowTestData.key;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class SourcelessReturnIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired ProcessEngine engine;
    @Autowired SalesReturnService returns;
    @Autowired PurchaseOrderService purchases;
    @Autowired ReceivingService receiving;
    @Autowired WorkflowTasks tasks;
    @Autowired ReconciliationChecks checks;
    @Autowired ObjectMapper json;
    @Autowired cn.erp.period.PeriodService periods;
    @Autowired cn.erp.shared.CommandResults commandResults;
    @Autowired cn.erp.reversal.ReversalService reversals;
    @Autowired cn.erp.salesfinance.RefundApplicationService refundApplications;
    @Autowired cn.erp.opening.OpeningService opening;
    @Autowired cn.erp.salesfinance.SalesFinanceQueries financeQueries;
    @Autowired cn.erp.fulfillment.ShipmentService shipments;
    @Autowired ReportService reports;
    @Autowired cn.erp.inventory.InventoryTraceQueries trace;
    @Autowired cn.erp.workbench.DocumentBrowser browser;
    private WorkflowTestData d;
    private long period;

    @BeforeEach void prepare() {
        d=new WorkflowTestData(jdbc,access,sales,models);
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),d.tenant,d.customer);
        period=jdbc.queryForObject("SELECT id FROM accounting_period WHERE tenant_id=?",Long.class,d.tenant);
    }

    @AfterEach void cleanup() {
        if(d==null)return;
        for(String table:List.of("inv_shipment_pick","doc_line_serial","inv_movement_serial","inv_serial")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        jdbc.update("DELETE FROM inv_movement_hold WHERE tenant_id=?",d.tenant);
        for(String fact:List.of("receivable","inventory_movement","journal")) {
            if(fact.equals("journal")) jdbc.update("DELETE FROM journal_line WHERE tenant_id=?",d.tenant);
            jdbc.update("DELETE FROM "+fact+" WHERE tenant_id=? AND posting_action='reverse'",d.tenant);
        }
        for(String table:List.of("sales_return_line","sales_return")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        jdbc.update("DELETE FROM opening_entry WHERE tenant_id=?",d.tenant);
        d.cleanup(engine);
    }

    @Test void defaultWorkflowPostsCreditAndInventoryWithoutInventingAnOriginalSale() {
        receive("100","5");
        var original=jdbc.queryForList("SELECT * FROM shipment_line WHERE tenant_id=?",d.tenant);
        long id=create(input(List.of(line("4","11.30","0.13","passed",null,null)),null));
        var pending=submit(d.actor(d.maker),id);
        assertThat(pending).containsEntry("status","pending");
        assertClosingBlocked(id,"pending");
        assertThat(Long.parseLong(pending.get("wfInstanceId").toString())).isPositive();
        code(()->returns.command(d.actor(d.financeA),id,"approve",key(),command(id)),"WORKFLOW_IN_PROGRESS");
        approve(id,d.financeA);
        assertClosingBlocked(id,"approved");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","approved").containsEntry("costAmount","20.00");
        var version=command(id);String postKey=key();
        assertThat(returns.command(d.actor(d.maker),id,"post",postKey,version)).containsEntry("status","posted").doesNotContainKey("costAmount");
        assertThat(returns.command(d.actor(d.maker),id,"post",postKey,version)).containsEntry("status","posted");
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("520");
        assertThat(value("SELECT qty FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("104");
        assertThat(jdbc.queryForList("SELECT * FROM shipment_line WHERE tenant_id=?",d.tenant)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=? AND source_id=? AND due_date IS NULL",Integer.class,d.tenant,id)).isEqualTo(1);
        assertThat(value("SELECT amount FROM receivable WHERE source_id=?",id)).isEqualByComparingTo("45.20");
        zeroDifferences();
    }

    @Test void changedAverageRequiresAnotherApprovalAndKeepsTheFirstSnapshot() {
        receive("100","5");long id=create(input(List.of(line("4","10","0","passed",null,null)),null));
        submit(d.actor(d.maker),id);approve(id,d.financeA);
        String frozen=jdbc.queryForObject("SELECT cost_basis FROM sales_return_line WHERE return_id=?",String.class,id);
        receive("100","7");
        code(()->returns.command(d.actor(d.maker),id,"post",key(),command(id)),"RETURN_COST_CHANGED");
        assertThat(jdbc.queryForObject("SELECT cost_basis FROM sales_return_line WHERE return_id=?",String.class,id)).isEqualTo(frozen);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_id=?",Integer.class,id)).isZero();
        submit(d.actor(d.maker),id);approve(id,d.financeB);
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("costAmount","24.00");
        assertThat(jdbc.queryForList("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.costAmount')) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='sales_return.submit' ORDER BY id",String.class,d.tenant,id))
                .containsExactly("20.00","24.00");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(before_data,'$.lines[0].costBasis.unitCost')) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='sales_return.submit' ORDER BY id DESC LIMIT 1",String.class,d.tenant,id))
                .isEqualTo("5.00000000");
        returns.command(d.actor(d.maker),id,"post",key(),command(id));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance WHERE tenant_id=? AND business_type='sales_return' AND business_id=?",Integer.class,d.tenant,id)).isEqualTo(2);
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("1224");
        zeroDifferences();
    }

    @Test void zeroManualCostAndThenZeroAverageAreBothUsable() {
        long first=create(input(List.of(line("2","10","0","passed","0","历史凭据确认零成本")),null));
        submit(d.actor(d.maker),first);approve(first,d.financeA);returns.command(d.actor(d.maker),first,"post",key(),command(first));
        long second=create(input(List.of(line("1","10","0","passed",null,null)),null));
        submit(d.actor(d.maker),second);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(cost_basis,'$.source')) FROM sales_return_line WHERE return_id=?",String.class,second)).isEqualTo("average");
        approve(second,d.financeA);returns.command(d.actor(d.maker),second,"post",key(),command(second));
        assertThat(value("SELECT qty FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("3");
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("0");
        zeroDifferences();
    }

    @Test void customAllReviewRechecksTheCostPermissionOfAnAlreadyApprovedReviewer() {
        receive("10","5");bindTwoReviews();
        long id=create(input(List.of(line("1","10","0","passed",null,null)),null));submit(d.actor(d.maker),id);
        reviewBusiness("sales_return",id,d.financeA);
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_id=?",Integer.class,id)).isZero();
        long role=jdbc.queryForObject("SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",Long.class,d.tenant,d.financeA);
        long permission=jdbc.queryForObject("SELECT id FROM sys_permission WHERE code='cost:view'",Long.class);
        jdbc.update("DELETE FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=?",d.tenant,role,permission);
        code(()->reviewBusiness("sales_return",id,d.financeB),"WORKFLOW_REVIEW_INVALID");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","pending").containsEntry("approvedBy",null);
        jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,role,permission);
        reviewBusiness("sales_return",id,d.financeB);
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","approved");
        returns.command(d.actor(d.maker),id,"post",key(),command(id));
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("55");
        zeroDifferences();
    }

    @Test void customReviewCannotFinishAfterCostChangesAndCanBeCancelledForAFreshReview() {
        receive("10","5");bindTwoReviews();
        long id=create(input(List.of(line("1","10","0","passed",null,null)),null));submit(d.actor(d.maker),id);
        reviewBusiness("sales_return",id,d.financeA);receive("10","7");
        code(()->reviewBusiness("sales_return",id,d.financeB),"RETURN_COST_CHANGED");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","pending").containsEntry("costAmount","5.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_id=?",Integer.class,id)).isZero();
        var instance=jdbc.queryForMap("SELECT i.id,i.version FROM wf_instance i WHERE i.tenant_id=? AND i.business_type='sales_return' AND i.business_id=? AND i.status='running'",d.tenant,id);
        tasks.end(d.actor(d.maker),((Number)instance.get("id")).longValue(),"cancel",key(),((Number)instance.get("version")).longValue(),"成本改变，撤回重新核对");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","rejected");
        submit(d.actor(d.maker),id);reviewBusiness("sales_return",id,d.financeA);reviewBusiness("sales_return",id,d.financeB);
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","approved").containsEntry("costAmount","6.00");
        returns.command(d.actor(d.maker),id,"post",key(),command(id));
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("126");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_id=?",Integer.class,id)).isEqualTo(1);
        zeroDifferences();
    }

    @Test void sourcelessReturnWorkflowCannotOmitHumanReview() {
        code(()->d.bind(WorkflowTestData.root(null),"sales_return"),"WORKFLOW_MODEL_INVALID");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_return WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
    }

    private void bindTwoReviews() {
        d.bind(WorkflowTestData.root(WorkflowTestData.approve("cost",List.of(d.financeA,d.financeB),"and",null)),"sales_return");
    }

    @Test void frozenDecimalStringsSurviveMysqlJsonAndWorkflowReviewExactly() {
        long id=create(input(List.of(line("1","10","0","passed","9007199254740991.12345678","历史成本原件")),null));
        submit(d.actor(d.maker),id);approve(id,d.financeA);
        assertThat(jdbc.queryForObject("SELECT JSON_TYPE(JSON_EXTRACT(cost_basis,'$.unitCost')) FROM sales_return_line WHERE return_id=?",String.class,id)).isEqualTo("STRING");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(cost_basis,'$.unitCost')) FROM sales_return_line WHERE return_id=?",String.class,id)).isEqualTo("9007199254740991.12345678");
        assertThat(returns.get(d.actor(d.manager),id)).containsEntry("costAmount","9007199254740991.12");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.lines[0].costBasis.unitCost')) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='sales_return.approve'",String.class,d.tenant,id))
                .isEqualTo("9007199254740991.12345678");
    }

    @Test void hiddenManualCostSurvivesAnOrdinaryPriceEditAndIsNeverEchoed() {
        var ordinary=without(d.maker,"cost:view");
        long id=id(returns.create(ordinary,key(),input(List.of(line("2","10","0","passed",null,null)),null)));
        code(()->submit(ordinary,id),"RETURN_COST_REQUIRED");
        returns.update(d.actor(d.manager),id,key(),input(List.of(line("2","10","0","passed","3.25","历史成本凭据")),version(id)));
        returns.update(ordinary,id,key(),input(List.of(line("2","11","0","passed",null,null)),version(id)));
        assertThat(value("SELECT manual_unit_cost FROM sales_return_line WHERE return_id=?",id)).isEqualByComparingTo("3.25");
        assertHidden(returns.get(ordinary,id));code(()->returns.costPreview(ordinary,id),"FORBIDDEN");
        submit(ordinary,id);approve(id,d.financeA);assertHidden(returns.command(ordinary,id,"post",key(),command(id)));
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("6.50");
        zeroDifferences();
    }

    @Test void groupedRoundingKeepsTheLastInboundCostAndQualityHolds() {
        receive("3","0.00333333");
        long id=create(input(List.of(line("1","1","0","passed",null,null),line("1","1","0","pending",null,null),line("1","1","0","failed",null,null)),null));
        submit(d.actor(d.maker),id);approve(id,d.financeA);returns.command(d.actor(d.maker),id,"post",key(),command(id));
        assertThat(value("SELECT qty FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("6");
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("0.02");
        assertThat(value("SELECT last_in_unit_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("0.00333333");
        assertThat(jdbc.queryForList("SELECT cost_amount FROM sales_return_line WHERE return_id=? ORDER BY line_no",BigDecimal.class,id))
                .containsExactly(new BigDecimal("0.01"),new BigDecimal("0.00"),new BigDecimal("0.00"));
        assertThat(value("SELECT blocked_qty FROM inventory_balance WHERE tenant_id=? AND location_key='' AND lot_key=''",d.tenant)).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_line l JOIN journal h ON h.id=l.journal_id WHERE h.source_type='sales_return' AND h.source_id=?",Integer.class,id)).isEqualTo(4);
        zeroDifferences();
    }

    @Test void currentPermissionsAndScopesAreCheckedBeforeReturningAnOldSuccess() {
        receive("10","5");var input=input(List.of(line("1","10","0","passed",null,null)),null);String createKey=key();
        long id=id(returns.create(d.actor(d.maker),createKey,input));
        code(()->returns.create(without(d.maker,"sales_return:sourceless"),createKey,input),"FORBIDDEN");
        code(()->commandResults.get(without(d.maker,"sales_return:sourceless"),"sales_return.create",createKey),"FORBIDDEN");
        code(()->commandResults.get(without(d.maker,"sales_return:create"),"sales_return.create",createKey),"FORBIDDEN");
        assertThat(commandResults.get(d.actor(d.maker),"sales_return.create",createKey)).containsEntry("status","succeeded");
        code(()->returns.create(without(d.maker,"cost:view"),key(),input(List.of(line("1","10","0","passed","0","成本依据")),null)),"FORBIDDEN");
        var actor=d.actor(d.maker);
        var noWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(),actor.permissions(),actor.orgScope());
        code(()->returns.get(noWarehouse,id),"NOT_FOUND");
        code(()->commandResults.get(noWarehouse,"sales_return.create",createKey),"NOT_FOUND");
        var noDepartment=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),actor.permissions(),OrgScope.restricted(Set.of(),Set.of(),0));
        code(()->returns.get(noDepartment,id),"NOT_FOUND");
        submit(d.actor(d.maker),id);
        code(()->returns.command(without(d.financeA,"cost:view"),id,"approve",key(),command(id)),"FORBIDDEN");
        approve(id,d.financeA);
        String approvalKey=jdbc.queryForObject("SELECT idempotency_key FROM idempotency_record WHERE tenant_id=? AND operator_id=? AND operation='sales_return.approve'",String.class,d.tenant,d.financeA);
        code(()->commandResults.get(without(d.financeA,"cost:view"),"sales_return.approve",approvalKey),"FORBIDDEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_return WHERE tenant_id=?",Integer.class,d.tenant)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE source_id=?",Integer.class,id)).isZero();
    }

    @Test void reversalUsesFrozenCostAfterLaterPurchasesWithoutChangingSalesProgress() {
        receive("100","5");long id=posted(List.of(line("4","11.30","0.13","passed",null,null)));
        receive("100","7");
        var orders=jdbc.queryForList("SELECT * FROM sales_order_line WHERE tenant_id=?",d.tenant);
        var shipments=jdbc.queryForList("SELECT * FROM shipment_line WHERE tenant_id=?",d.tenant);
        long reversal=applyReversal("sales_return",id);
        approveReversal(reversal);postReversal(reversal);
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","reversed");
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("1200");
        assertThat(value("SELECT qty FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("200");
        assertThat(value("SELECT cost_delta FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",d.tenant)).isEqualByComparingTo("-20");
        assertThat(jdbc.queryForList("SELECT * FROM sales_order_line WHERE tenant_id=?",d.tenant)).isEqualTo(orders);
        assertThat(jdbc.queryForList("SELECT * FROM shipment_line WHERE tenant_id=?",d.tenant)).isEqualTo(shipments);
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=? AND item_type='receivable'",d.tenant)).isEqualByComparingTo("0");
        zeroDifferences();
    }

    @Test void downstreamReservationBlocksReversalAndLeavesAllFactsIntact() {
        receive("100","5");long id=posted(List.of(line("4","10","0","passed",null,null)));
        long reversal=applyReversal("sales_return",id);approveReversal(reversal);
        long order=d.order("104","10");
        var submitted=sales.command(d.actor(d.maker),order,"submit",key(),new cn.erp.salesorder.SalesOrderModels.Command(0L,null,null));
        sales.command(d.actor(d.manager),order,"approve",key(),new cn.erp.salesorder.SalesOrderModels.Command(((Number)submitted.get("version")).longValue(),null,null));
        assertThat(value("SELECT reserved_qty FROM inventory_balance WHERE tenant_id=? AND location_key='' AND lot_key=''",d.tenant)).isEqualByComparingTo("104");
        code(()->postReversal(reversal),"INVENTORY_REVERSAL_NOT_ALLOWED");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","posted");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND posting_action='reverse'",Integer.class,d.tenant)).isZero();
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("520");
    }

    @Test void reversingALaterReceiptRestoresTheWholeGroupLastCostRatherThanItsZeroTail() {
        receive("3","0.00333333");
        posted(List.of(line("1","1","0","passed",null,null),line("1","1","0","passed",null,null),line("1","1","0","passed",null,null)));
        long receipt=receive("3","1");long reversal=applyReversal("goods_receipt",receipt);
        approveReversal(reversal);postReversal(reversal);
        assertThat(value("SELECT last_in_unit_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("0.00333333");
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("0.02");
        zeroDifferences();
    }

    private long posted(List<FinanceModels.ReturnLine> lines) {
        long id=create(input(lines,null));submit(d.actor(d.maker),id);approve(id,d.financeA);
        returns.command(d.actor(d.maker),id,"post",key(),command(id));return id;
    }

    @SuppressWarnings("unchecked") @Test void genericBrowserShowsReturnModeToViewersWithoutGrantingSourcelessActions() {
        long id=create(input(List.of(line("1","10","0","passed","5","历史成本")),null));
        var viewer=without(d.maker,"sales_return:sourceless","sales_return:create","sales_return:update");
        var page=browser.page(viewer,"sales-returns",cn.erp.workbench.DocumentBrowser.Filter.empty(),null,20,true);
        var items=(List<Map<String,Object>>)page.get("items");
        assertThat(items).singleElement().satisfies(item->assertThat(item).containsEntry("returnMode","sourceless").doesNotContainKeys("costAmount","manualUnitCost","costBasis"));
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat(returns.get(viewer,id)).containsEntry("returnMode","sourceless");
        code(()->submit(viewer,id),"FORBIDDEN");
    }

    @Test void genericRefundBrowserAppliesSourceVisibilityBeforeItemsCountsAndNumberLookup() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long refund=createRefund(cash(),"40",List.of(credit(source)));
        var maker=d.actor(d.maker);
        var visible=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(d.warehouse),maker.permissions(),maker.orgScope());
        assertThat(browser.page(visible,"refund-requests",cn.erp.workbench.DocumentBrowser.Filter.empty(),null,20,true).get("items")).asList().hasSize(1);
        var noWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(),maker.permissions(),maker.orgScope());
        for(var denied:List.of(noWarehouse,without(d.maker,"sales_return:view")))assertBrowserHidden(denied,"refund-requests",refund);
        jdbc.update("UPDATE sales_return SET owner_department_id=? WHERE id=?",d.parentDepartment,source);
        var child=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),maker.permissions(),OrgScope.restricted(Set.of(d.department),Set.of(),d.department));
        assertBrowserHidden(child,"refund-requests",refund);
    }

    @Test void genericReversalBrowserUsesTheCurrentSourcelessReturnDepartment() {
        long source=posted(List.of(line("1","10","0","passed","5","历史成本")));
        long reversal=applyReversal("sales_return",source);
        assertThat(browser.page(d.actor(d.maker),"document-reversals",cn.erp.workbench.DocumentBrowser.Filter.empty(),null,20,true).get("items")).asList().hasSize(1);
        jdbc.update("UPDATE sales_return SET owner_department_id=? WHERE id=?",d.parentDepartment,source);
        var maker=d.actor(d.maker);
        var child=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),maker.permissions(),OrgScope.restricted(Set.of(d.department),Set.of(),d.department));
        assertBrowserHidden(child,"document-reversals",reversal);
    }

    private void assertBrowserHidden(CompanyAccess actor,String kind,long id) {
        var filter=cn.erp.workbench.DocumentBrowser.Filter.empty();
        var page=browser.page(actor,kind,filter,null,20,true);
        assertThat(page.get("items")).asList().isEmpty();assertThat(page.get("total")).isEqualTo(0L);
        assertThat(browser.statusCounts(actor,kind,filter)).isEmpty();
        assertThat(browser.visibleNumber(actor,kind,id)).isEmpty();
    }

    @ParameterizedTest @CsvSource({"7,28,98,average","0,0,0,average","7,28,28,last_in"})
    void approvedManualCostCanBeSubmittedAgainUsingNewAutomaticCost(String unitCost,String returnCost,String stockCost,String source) {
        long id=create(input(List.of(line("4","10","0","passed","5","历史成本依据")),null));
        submit(d.actor(d.maker),id);approve(id,d.financeA);
        receive("10",unitCost);
        if(source.equals("last_in"))stockAction("loss","10",null);
        code(()->returns.command(d.actor(d.maker),id,"post",key(),command(id)),"RETURN_COST_CHANGED");
        assertThat(returns.costPreview(d.actor(d.maker),id)).containsEntry("totalCost",new BigDecimal(returnCost).setScale(2).toPlainString());
        assertThat(submit(d.actor(d.maker),id)).containsEntry("status","pending");
        assertThat(jdbc.queryForMap("SELECT manual_unit_cost,manual_cost_reason FROM sales_return_line WHERE return_id=?",id))
                .containsEntry("manual_unit_cost",null).containsEntry("manual_cost_reason",null);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=? AND source_id=?",Integer.class,d.tenant,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(before_data,'$.lines[0].costBasis.source')) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='sales_return.submit' ORDER BY id DESC LIMIT 1",String.class,d.tenant,id)).isEqualTo("manual");
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(after_data,'$.lines[0].costBasis.source')) FROM audit_log WHERE tenant_id=? AND object_id=? AND action='sales_return.submit' ORDER BY id DESC LIMIT 1",String.class,d.tenant,id)).isEqualTo(source);
        approve(id,d.financeB);
        returns.command(d.actor(d.maker),id,"post",key(),command(id));
        assertThat(value("SELECT cost_amount FROM sales_return WHERE id=?",id)).isEqualByComparingTo(returnCost);
        assertThat(value("SELECT total_cost FROM inventory_cost WHERE tenant_id=?",d.tenant)).isEqualByComparingTo(stockCost);
        zeroDifferences();
    }

    @Test void draftManualInputCannotOverrideAnAvailableAutomaticCost() {
        receive("10","7");
        long id=create(input(List.of(line("4","10","0","passed","5","待核对的手填成本")),null));
        code(()->returns.costPreview(d.actor(d.maker),id),"RETURN_COST_SOURCE_CHANGED");
        code(()->submit(d.actor(d.maker),id),"RETURN_COST_SOURCE_CHANGED");
        assertThat(returns.get(d.actor(d.maker),id)).containsEntry("status","draft");
        assertThat(value("SELECT manual_unit_cost FROM sales_return_line WHERE return_id=?",id)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=? AND source_id=?",Integer.class,d.tenant,id)).isZero();
    }

    @Test void reportsIdentifySourcelessReturnAndFrozenAverageCostBeforeAndAfterReversal() {
        receive("10","5");
        long source=posted(List.of(line("2","10","0","passed",null,null),line("2","10","0","passed",null,null)));
        var actor=d.actor(d.maker);
        var sales=report(actor,ReportType.SALES_SUMMARY);
        assertThat(sales.items()).hasSize(2).allSatisfy(row->assertThat(row)
                .containsEntry("sourceType","sales_return").containsEntry("sourceId",Long.toString(source))
                .containsEntry("returnMode","sourceless").containsEntry("returnCostSource","average"));
        assertThat(new BigDecimal(sales.summary().get("amount").toString())).isEqualByComparingTo("-40");
        assertThat(new BigDecimal(sales.summary().get("costAmount").toString())).isEqualByComparingTo("-20");
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);postReversal(reversal);
        var reversed=report(actor,ReportType.SALES_SUMMARY);
        assertThat(reversed.items()).hasSize(4).allSatisfy(row->assertThat(row)
                .containsEntry("returnMode","sourceless").containsEntry("returnCostSource","average"));
        assertThat(new BigDecimal(reversed.summary().get("amount").toString())).isEqualByComparingTo("0");
        assertThat(new BigDecimal(reversed.summary().get("costAmount").toString())).isEqualByComparingTo("0");
        var flow=report(actor,ReportType.INVENTORY_FLOW);
        assertThat(flow.items().stream().filter(row->"sourceless".equals(row.get("returnMode"))).toList()).hasSize(4)
                .allSatisfy(row->assertThat(row).containsEntry("returnCostSource","average"));
        assertThat(flow.items()).anySatisfy(row->assertThat(row).containsEntry("sourceType","document_reversal").containsEntry("sourceId",Long.toString(reversal)));
        assertThat(new BigDecimal(flow.summary().get("qty").toString())).isEqualByComparingTo("10");
        assertThat(new BigDecimal(flow.summary().get("costAmount").toString())).isEqualByComparingTo("50");
        for(var type:List.of(ReportType.INVENTORY_FLOW,ReportType.SALES_SUMMARY))
            assertThat(report(without(d.maker,"cost:view"),type).items()).allSatisfy(row->assertThat(row).doesNotContainKeys("costAmount","returnCostSource"));
        zeroDifferences();
    }

    @Test void serialTraceIdentifiesManualReturnCostAndReversalWithinCurrentScope() {
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",d.tenant,d.sku);
        long source=posted(List.of(tracked("1","passed",List.of("SN-METADATA"),"5")));
        var actor=d.actor(d.maker);
        assertThat(traceItems(actor,"SN-METADATA")).hasSize(1).allSatisfy(row->assertThat(row)
                .containsEntry("sourceType","sales_return_line").containsEntry("returnMode","sourceless").containsEntry("returnCostSource","manual"));
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);postReversal(reversal);
        assertThat(traceItems(actor,"SN-METADATA")).hasSize(2).allSatisfy(row->assertThat(row)
                .containsEntry("returnMode","sourceless").containsEntry("returnCostSource","manual"));
        assertThat(traceItems(without(d.maker,"cost:view"),"SN-METADATA")).hasSize(2)
                .allSatisfy(row->assertThat(row).containsEntry("returnMode","sourceless").doesNotContainKey("returnCostSource"));
        var otherWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(d.warehouse+1),actor.permissions());
        assertThat(report(otherWarehouse,ReportType.INVENTORY_FLOW).items()).isEmpty();
        code(()->trace.serial(otherWarehouse,d.sku,"SN-METADATA",null,20),"NOT_FOUND");
        jdbc.update("UPDATE sales_return SET owner_department_id=? WHERE id=?",d.parentDepartment,source);
        var child=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),actor.permissions(),OrgScope.restricted(Set.of(d.department),Set.of(),d.department));
        assertThat(report(child,ReportType.INVENTORY_FLOW).items()).isEmpty();
        code(()->trace.serial(child,d.sku,"SN-METADATA",null,20),"NOT_FOUND");
        zeroDifferences();
    }

    @Test void zeroStockReturnReportsLastInboundCostAsItsFrozenSource() {
        receive("1","5");stockAction("loss","1",null);
        assertThat(value("SELECT qty FROM inventory_cost WHERE tenant_id=?",d.tenant)).isZero();
        posted(List.of(line("1","10","0","passed",null,null)));
        assertThat(report(d.actor(d.maker),ReportType.SALES_SUMMARY).items()).hasSize(1)
                .allSatisfy(row->assertThat(row).containsEntry("returnMode","sourceless").containsEntry("returnCostSource","last_in").containsEntry("costAmount","-5.00"));
        zeroDifferences();
    }

    private ReportService.Snapshot report(CompanyAccess actor,ReportType type) {
        return reports.snapshot(actor,type,new ReportQuery(d.day,d.day,null,null,null,null,true));
    }

    @Test void costReconciliationFindsOffsettingReturnLineErrorsAndTheirReversalSources() {
        receive("10","5");
        long source=posted(List.of(line("2","10","0","passed",null,null),line("2","10","0","passed",null,null)));
        var original=jdbc.queryForList("SELECT id,cost_delta FROM inventory_movement WHERE tenant_id=? AND source_type='sales_return_line' AND source_id=? ORDER BY id",d.tenant,source);
        assertThat(original).hasSize(2);
        assertOffsettingCostErrors(original,"入库成本");
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);postReversal(reversal);
        var reverse=jdbc.queryForList("SELECT id,cost_delta FROM inventory_movement WHERE tenant_id=? AND source_type='document_reversal' AND source_id=? ORDER BY id",d.tenant,reversal);
        assertThat(reverse).hasSize(2);
        assertOffsettingCostErrors(reverse,"冲销成本");
        zeroDifferences();
    }

    private void assertOffsettingCostErrors(List<Map<String,Object>> movements,String suffix) {
        try {
            for(int i=0;i<movements.size();i++) {
                var movement=movements.get(i);var amount=(BigDecimal)movement.get("cost_delta");
                jdbc.update("UPDATE inventory_movement SET cost_delta=? WHERE tenant_id=? AND id=?",amount.add(i==0?BigDecimal.ONE:BigDecimal.ONE.negate()),d.tenant,movement.get("id"));
            }
            assertThat(checks.check(d.actor(d.maker),"inventory_cost",null)).hasSize(2).allSatisfy(diff->{
                assertThat(diff.key()).contains("/无原单退货/加权平均成本/").endsWith(suffix);
                assertThat(diff.expected().subtract(diff.actual()).abs()).isEqualByComparingTo("1");
            });
        } finally {
            for(var movement:movements)jdbc.update("UPDATE inventory_movement SET cost_delta=? WHERE tenant_id=? AND id=?",movement.get("cost_delta"),d.tenant,movement.get("id"));
        }
        assertThat(checks.check(d.actor(d.maker),"inventory_cost",null)).isEmpty();
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> traceItems(CompanyAccess actor,String serial) {
        return (List<Map<String,Object>>)trace.serial(actor,d.sku,serial,null,20).get("items");
    }

    @Test void rejectedSerialCannotBeShippedWhileAnotherReturnedSerialIsAvailable() {
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",d.tenant,d.sku);
        posted(List.of(tracked("1","passed",List.of("SN-OK"),"5"),tracked("1","failed",List.of("SN-BAD"),"5")));
        assertThat(value("SELECT blocked_qty FROM inventory_balance WHERE tenant_id=? AND location_key='' AND lot_key=''",d.tenant)).isEqualByComparingTo("1");
        long order=approvedOrder("1");
        code(()->ship(order,"SN-BAD"),"PICK_UNAVAILABLE");
        ship(order,"SN-OK");
        assertThat(jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no='SN-BAD'",String.class,d.tenant)).isEqualTo("quality_hold");
        assertThat(jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no='SN-OK'",String.class,d.tenant)).isEqualTo("shipped");
        stockAction("unblock","1",List.of("SN-BAD"));
        assertThat(jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no='SN-BAD'",String.class,d.tenant)).isEqualTo("in_stock");
        ship(approvedOrder("1"),"SN-BAD");
        zeroDifferences();
    }

    @Test void scrappingARejectedSerialConsumesItsOwnHoldAndReversalRestoresIt() {
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",d.tenant,d.sku);
        long source=posted(List.of(tracked("1","failed",List.of("SN-A"),"5"),tracked("1","failed",List.of("SN-B"),"5")));
        var rows=jdbc.queryForList("SELECT id FROM sales_return_line WHERE return_id=? ORDER BY line_no",Long.class,source);
        long adjustment=stockAction("scrap","1",List.of("SN-B"));
        assertThat(held(rows.getFirst())).isEqualByComparingTo("1");assertThat(held(rows.getLast())).isEqualByComparingTo("0");
        assertThat(serialStatus("SN-A")).isEqualTo("quality_hold");assertThat(serialStatus("SN-B")).isEqualTo("scrapped");
        long reversal=applyReversal("stock_adjustment",adjustment);approveReversal(reversal);postReversal(reversal);
        assertThat(serialStatus("SN-B")).isEqualTo("quality_hold");assertThat(held(rows.getLast())).isEqualByComparingTo("1");
        stockAction("unblock","1",List.of("SN-B"));
        assertThat(held(rows.getFirst())).isEqualByComparingTo("1");assertThat(held(rows.getLast())).isEqualByComparingTo("0");
        assertThat(serialStatus("SN-A")).isEqualTo("quality_hold");assertThat(serialStatus("SN-B")).isEqualTo("in_stock");
        zeroDifferences();
    }

    @Test void anOldPartialHoldCannotReleaseASerialThatWasReturnedAgainUnderAnotherSource() {
        jdbc.update("UPDATE sku SET serial_managed=1 WHERE tenant_id=? AND id=?",d.tenant,d.sku);
        long first=posted(List.of(tracked("2","failed",List.of("SN-01","SN-02"),"5")));
        stockAction("unblock","1",List.of("SN-01"));ship(approvedOrder("1"),"SN-01");
        long second=posted(List.of(tracked("1","failed",List.of("SN-01"),null)));
        // 沿原调整入口自动释放旧来源的剩余一件，不能放走已属于新退货的SN-01。
        stockAction("unblock","1",List.of());
        assertThat(serialStatus("SN-01")).isEqualTo("quality_hold");assertThat(serialStatus("SN-02")).isEqualTo("in_stock");
        long firstLine=jdbc.queryForObject("SELECT id FROM sales_return_line WHERE return_id=?",Long.class,first);
        long secondLine=jdbc.queryForObject("SELECT id FROM sales_return_line WHERE return_id=?",Long.class,second);
        assertThat(held(firstLine)).isEqualByComparingTo("0");assertThat(held(secondLine)).isEqualByComparingTo("1");
        zeroDifferences();
    }
    private String serialStatus(String serial) {return jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no=?",String.class,d.tenant,serial);}
    private BigDecimal held(long line) {return value("SELECT COALESCE(SUM(qty),0) FROM inv_block_detail WHERE source_type='sales_return_line' AND source_line_id=?",line);}
    private long stockAction(String type,String qty,List<String> serials) {
        long id=id(opening.createStock(d.actor(d.maker),key(),new cn.erp.opening.OpeningService.Stock(type,d.warehouse,d.day,"复核后处置退回商品",
                List.of(new cn.erp.opening.OpeningService.StockLine(d.sku,d.unit,qty,null,new StockSelection(null,null,null,null,null,serials))),null)));
        opening.command(d.actor(d.maker),true,id,"submit",key(),new cn.erp.opening.OpeningService.Version(0L,null));
        opening.command(d.actor(d.manager),true,id,"approve",key(),new cn.erp.opening.OpeningService.Version(1L,null));
        opening.command(d.actor(d.maker),true,id,"post",key(),new cn.erp.opening.OpeningService.Version(2L,null));return id;
    }
    private FinanceModels.ReturnLine tracked(String qty,String quality,List<String> serials,String manual) {
        return new FinanceModels.ReturnLine(null,d.unit,qty,quality,new StockSelection(null,null,null,null,null,serials),d.sku,"10","0",manual,manual==null?null:"历史成本凭据");
    }
    private long approvedOrder(String qty) {
        long order=d.order(qty,"10");
        var submitted=sales.command(d.actor(d.maker),order,"submit",key(),new cn.erp.salesorder.SalesOrderModels.Command(0L,null,null));
        sales.command(d.actor(d.manager),order,"approve",key(),new cn.erp.salesorder.SalesOrderModels.Command(((Number)submitted.get("version")).longValue(),null,null));return order;
    }
    private void ship(long order,String serial) {
        long source=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=?",Long.class,order);
        long id=id(shipments.create(d.actor(d.maker),key(),new cn.erp.fulfillment.ShipmentModels.Input(d.customer,d.warehouse,d.day,"delivery",null,
                List.of(new cn.erp.fulfillment.ShipmentModels.InputLine(source,d.unit,"1",new StockSelection(null,null,null,null,null,List.of(serial)))),null)));
        shipments.submit(d.actor(d.maker),id,key(),0L);shipments.post(d.actor(d.maker),id,key(),1L);
    }

    @Test void sourcelessCreditRefundPaysFromARealAccountWithoutAnOriginalReceipt() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash();long credit=credit(source);long refund=createRefund(account,"40",List.of(credit));
        assertThat(refundApplications.get(d.actor(d.maker),refund)).containsEntry("refundMode","sourceless_credit").containsEntry("originalReceiptId",null);
        refundCommand(refund,"submit");
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("0");
        code(()->applyReversal("sales_return",source),"DEPENDENCY_EXISTS");
        code(()->refundCommand(refund,"approve"),"WORKFLOW_IN_PROGRESS");
        reviewBusiness("refund_request",refund,d.financeB);
        String paidKey=key();var paid=new FinanceModels.Paid(refundVersion(refund),d.day,"实际银行流水-01");
        assertThat(refundApplications.paid(d.actor(d.maker),refund,paidKey,paid)).containsEntry("status","refunded");
        refundApplications.paid(d.actor(d.maker),refund,paidKey,paid);
        assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("960");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='refund_request' AND source_id=?",Integer.class,d.tenant,refund)).isEqualTo(1);
        code(()->applyReversal("sales_return",source),"DEPENDENCY_EXISTS");
        code(()->commandResults.get(without(d.maker,"refund_request:register_paid"),"refund_request.register_paid",paidKey),"FORBIDDEN");
        zeroDifferences();
    }

    @Test void rejectedAndCancelledRefundsReleaseCreditAndAllowTheReturnToBeReversed() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long credit=credit(source),account=cash(),refund=createRefund(account,"40",List.of(credit));
        refundCommand(refund,"submit");
        decideBusiness("refund_request",refund,d.financeA,"reject","金额需重新核对");
        assertThat(refundApplications.get(d.actor(d.maker),refund)).containsEntry("status","draft");
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("40");
        refundCommand(refund,"submit");reviewBusiness("refund_request",refund,d.financeB);refundCommand(refund,"cancel");
        assertThat(refundApplications.get(d.actor(d.maker),refund)).containsEntry("status","cancelled");
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("40");
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);postReversal(reversal);
        assertThat(refundApplications.get(d.actor(d.maker),refund)).containsEntry("status","cancelled");
        zeroDifferences();
    }

    @Test void refundScopeIsRecheckedInListsWorkflowsAndHistoricalResults() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash();String createKey=key();
        long refund=id(refundApplications.create(d.actor(d.maker),createKey,refundInput(account,"40",List.of(credit(source)),null)));
        var maker=d.actor(d.maker);var finance=d.actor(d.financeA);
        var noWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(),maker.permissions(),maker.orgScope());
        var noDept=new CompanyAccess(d.tenant,d.maker,d.company,true,Set.of(),maker.permissions(),OrgScope.restricted(Set.of(),Set.of(),0));
        long otherWarehouse=Ids.next();jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'OTHER','另一仓库')",otherWarehouse,d.tenant,d.company);
        var wrongWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(otherWarehouse),maker.permissions(),maker.orgScope());
        var visibleWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(d.warehouse),maker.permissions(),maker.orgScope());
        assertThat(refundApplications.get(visibleWarehouse,refund)).containsEntry("id",""+refund);
        assertThat(refundApplications.creditItems(visibleWarehouse,d.customer,null,20).get("items")).asList().hasSize(1);
        for(var denied:List.of(noWarehouse,noDept,wrongWarehouse)) {
            assertThat(refundApplications.list(denied,null,null,null,20).get("items")).asList().isEmpty();
            code(()->refundApplications.get(denied,refund),"NOT_FOUND");
            code(()->commandResults.get(denied,"refund_request.create",createKey),"NOT_FOUND");
        }
        code(()->refundApplications.get(without(d.maker,"sales_return:view"),refund),"FORBIDDEN");
        models.installDefault(d.tenant,d.company,"refund_request");refundCommand(refund,"submit");
        for(var denied:List.of(noWarehouse,noDept,wrongWarehouse)) {
            assertThat(financeQueries.openItems(denied,null,"ar",false,null,20).get("items")).asList().isEmpty();
            assertThat(financeQueries.allocations(denied,null,null,20).get("items")).asList().isEmpty();
        }
        var deniedReviewer=new CompanyAccess(d.tenant,d.financeA,d.company,false,Set.of(otherWarehouse),finance.permissions(),finance.orgScope());
        var visibility=new WorkflowAccess();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance i WHERE i.tenant_id=? AND i.business_type='refund_request' AND "+visibility.business(deniedReviewer,"i",true),Integer.class,d.tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM wf_instance i WHERE i.tenant_id=? AND i.business_type='refund_request' AND "+visibility.business(finance,"i",true),Integer.class,d.tenant)).isEqualTo(1);
    }

    @Test void refundSelectionRejectsMissingWrongAndReversedSourcesAndRollsBackAnOverrun() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash(),credit=credit(source);
        long other=Ids.next();jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'OTHER','另一客户')",other,d.tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),d.tenant,other);
        var otherCustomer=new FinanceModels.Refund(other,null,account,"1",d.day,"不能混用客户信用额",List.of(credit),null,"sourceless_credit");
        code(()->refundApplications.create(d.actor(d.maker),key(),otherCustomer),"REFUND_CREDIT_SOURCE_INVALID");
        code(()->createRefund(account,"1",List.of()),"VALIDATION_FAILED");
        code(()->createRefund(account,"1",List.of(credit,credit)),"VALIDATION_FAILED");
        code(()->createRefund(account,"1",List.of(Ids.next())),"REFUND_CREDIT_SOURCE_INVALID");
        long entry=id(opening.createEntry(d.actor(d.maker),key(),new cn.erp.opening.OpeningService.Entry("receivable",d.customer,null,"C","10",d.day,"旧信用余额",null)));
        opening.command(d.actor(d.manager),false,entry,"approve",key(),new cn.erp.opening.OpeningService.Version(0L,null));
        opening.command(d.actor(d.maker),false,entry,"post",key(),new cn.erp.opening.OpeningService.Version(1L,null));
        long unrelated=jdbc.queryForObject("SELECT i.id FROM partner_open_item i JOIN receivable r ON r.id=i.source_id AND r.tenant_id=i.tenant_id AND i.item_type='receivable' WHERE r.source_type='opening_entry' AND r.source_id=?",Long.class,entry);
        code(()->createRefund(account,"1",List.of(unrelated)),"REFUND_CREDIT_SOURCE_INVALID");
        var mixed=new FinanceModels.Refund(d.customer,Ids.next(),account,"1",d.day,"不合法混用",List.of(credit),null,"sourceless_credit");
        code(()->refundApplications.create(d.actor(d.maker),key(),mixed),"VALIDATION_FAILED");
        code(()->refundApplications.create(d.actor(d.maker),key(),new FinanceModels.Refund(d.customer,null,account,"1",d.day,"缺少原收款",List.of(credit),null)),"VALIDATION_FAILED");
        long tooMuch=createRefund(account,"41",List.of(credit));
        code(()->refundCommand(tooMuch,"submit"),"REFUND_AMOUNT_EXCEEDED");
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("40");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM partner_open_item WHERE tenant_id=? AND item_type='refund_request' AND source_id=?",Integer.class,d.tenant,tooMuch)).isZero();
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);postReversal(reversal);
        code(()->createRefund(account,"1",List.of(credit)),"REFUND_CREDIT_SOURCE_CHANGED");
        code(()->refundCommand(tooMuch,"submit"),"REFUND_CREDIT_SOURCE_CHANGED");
        zeroDifferences();
    }

    @Test void refundEditRecoversWithTheOriginalKeyAndCannotSwitchModes() {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash(),credit=credit(source),refund=createRefund(account,"40",List.of(credit));
        var edit=refundInput(account,"30",List.of(credit),refundVersion(refund));String editKey=key();
        code(()->refundApplications.update(d.actor(d.maker),refund,edit),"IDEMPOTENCY_KEY_REQUIRED");
        var saved=refundApplications.update(d.actor(d.maker),refund,editKey,edit);
        assertThat(saved).containsEntry("amount","30.00");
        var replayed=refundApplications.update(d.actor(d.maker),refund,editKey,edit);
        // 比较接口值，忽略MySQL JSON的键序和JDBC/Jackson内部整数类型差异。
        assertThat(json.readValue(json.writeValueAsString(replayed),Object.class))
                .isEqualTo(json.readValue(json.writeValueAsString(saved),Object.class));
        assertThat(commandResults.get(d.actor(d.maker),"refund_request.update",editKey)).containsEntry("status","succeeded");
        code(()->refundApplications.update(d.actor(d.maker),refund,editKey,refundInput(account,"31",List.of(credit),edit.version())),"IDEMPOTENCY_KEY_REUSED");
        var wrongMode=new FinanceModels.Refund(d.customer,null,account,"30",d.day,"不能改模式",List.of(credit),refundVersion(refund));
        code(()->refundApplications.update(d.actor(d.maker),refund,key(),wrongMode),"VALIDATION_FAILED");
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("40");
    }

    @SuppressWarnings("unchecked")
    @Test void creditCandidatesPageWholeVisibleSourcesAndExcludeReservedAmounts() {
        receive("10","5");long first=posted(List.of(line("1","10","0","passed",null,null)));
        long second=posted(List.of(line("2","10","0","passed",null,null)));
        var page=refundApplications.creditItems(d.actor(d.maker),d.customer,null,1);
        var items=(List<Map<String,Object>>)page.get("items");assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("id",""+credit(second)).containsEntry("sourceId",""+second).containsEntry("openAmount","20.00");
        assertThat(json.writeValueAsString(page)).doesNotContain("cost","Cost");
        long cursor=Long.parseLong(page.get("nextCursor").toString());
        assertThat((List<Map<String,Object>>)refundApplications.creditItems(d.actor(d.maker),d.customer,cursor,1).get("items"))
                .singleElement().satisfies(item->assertThat(item).containsEntry("id",""+credit(first)));
        long account=cash(),refund=createRefund(account,"25",List.of(credit(second),credit(first)));refundCommand(refund,"submit");
        assertThat(value("SELECT amount FROM settlement_allocation WHERE credit_item_id=? AND status='reserved'",credit(second))).isEqualByComparingTo("20");
        assertThat(value("SELECT amount FROM settlement_allocation WHERE credit_item_id=? AND status='reserved'",credit(first))).isEqualByComparingTo("5");
        assertThat((List<Map<String,Object>>)refundApplications.creditItems(d.actor(d.maker),d.customer,null,20).get("items"))
                .singleElement().satisfies(item->assertThat(item).containsEntry("id",""+credit(first)).containsEntry("openAmount","5.00"));
        var maker=d.actor(d.maker);var noWarehouse=new CompanyAccess(d.tenant,d.maker,d.company,false,Set.of(),maker.permissions(),maker.orgScope());
        assertThat(refundApplications.creditItems(noWarehouse,d.customer,null,20).get("items")).asList().isEmpty();
    }

    @Test void concurrentRefundApplicationsCannotReserveTheSameCreditTwice() throws Exception {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash(),credit=credit(source),first=createRefund(account,"30",List.of(credit)),second=createRefund(account,"30",List.of(credit));
        var gate=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->competeRefund(gate,first));var two=pool.submit(()->competeRefund(gate,second));
            assertThat(List.of(one.get(30,java.util.concurrent.TimeUnit.SECONDS),two.get(30,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(value("SELECT open_amount FROM partner_open_item WHERE id=?",credit)).isEqualByComparingTo("10");
        assertThat(value("SELECT SUM(amount) FROM settlement_allocation WHERE tenant_id=? AND status='reserved'",d.tenant)).isEqualByComparingTo("30");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE tenant_id=? AND status='pending_review'",Integer.class,d.tenant)).isEqualTo(1);
        assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("1000");
    }
    private boolean competeRefund(java.util.concurrent.CyclicBarrier gate,long id) throws Exception {
        gate.await(10,java.util.concurrent.TimeUnit.SECONDS);
        try {refundCommand(id,"submit");return true;}
        catch(ApiException error) {assertThat(error.getErrorCode()).isEqualTo("REFUND_AMOUNT_EXCEEDED");return false;}
    }

    @Test void refundReservationAndReturnReversalCannotBothCommit() throws Exception {
        receive("10","5");long source=posted(List.of(line("4","10","0","passed",null,null)));
        long account=cash(),refund=createRefund(account,"40",List.of(credit(source)));
        long reversal=applyReversal("sales_return",source);approveReversal(reversal);
        var gate=new java.util.concurrent.CyclicBarrier(2);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var one=pool.submit(()->race(gate,()->refundCommand(refund,"submit"),"REFUND_CREDIT_SOURCE_CHANGED"));
            var two=pool.submit(()->race(gate,()->postReversal(reversal),"DEPENDENCY_EXISTS"));
            boolean reserved=one.get(30,java.util.concurrent.TimeUnit.SECONDS),reversed=two.get(30,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(List.of(reserved,reversed)).containsExactlyInAnyOrder(true,false);
            assertThat(returns.get(d.actor(d.maker),source)).containsEntry("status",reserved?"posted":"reversed");
            assertThat(refundApplications.get(d.actor(d.maker),refund)).containsEntry("status",reserved?"pending_review":"draft");
        }
        assertThat(value("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",account)).isEqualByComparingTo("1000");
        for(String type:List.of("inventory_balance","inventory_cost","open_item","journal_balance","ledger_inventory"))
            assertThat(checks.check(d.actor(d.maker),type,null)).as(type).isEmpty();
    }
    private boolean race(java.util.concurrent.CyclicBarrier gate,Runnable action,String expected) throws Exception {
        gate.await(10,java.util.concurrent.TimeUnit.SECONDS);
        try {action.run();return true;}
        catch(ApiException error) {assertThat(error.getErrorCode()).isEqualTo(expected);return false;}
    }

    private record LegacyRefund(Long customerId,Long originalReceiptId,Long cashAccountId,String amount,java.time.LocalDate businessDate,String reason,List<Long> creditItemIds,Long version) {}
    @Test void originalRefundSerializationKeepsThePreUpgradeRequestShape() {
        var before=new LegacyRefund(d.customer,41L,42L,"10",d.day,"原收款退款",List.of(43L),null);
        var after=new FinanceModels.Refund(d.customer,41L,42L,"10",d.day,"原收款退款",List.of(43L),null);
        assertThat(json.writeValueAsString(after)).isEqualTo(json.writeValueAsString(before));
    }

    private long cash() {
        long account=Ids.next();
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'BANK','退款银行','bank')",account,d.tenant,d.company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,d.company,account);
        long entry=id(opening.createEntry(d.actor(d.maker),key(),new cn.erp.opening.OpeningService.Entry("cash",null,account,"D","1000",d.day,"真实期初资金",null)));
        opening.command(d.actor(d.manager),false,entry,"approve",key(),new cn.erp.opening.OpeningService.Version(0L,null));
        opening.command(d.actor(d.maker),false,entry,"post",key(),new cn.erp.opening.OpeningService.Version(1L,null));return account;
    }
    private long credit(long source) {return jdbc.queryForObject("SELECT i.id FROM partner_open_item i JOIN receivable r ON r.tenant_id=i.tenant_id AND r.company_id=i.company_id AND r.id=i.source_id AND i.item_type='receivable' WHERE i.tenant_id=? AND r.source_id=? AND r.posting_action='post'",Long.class,d.tenant,source);}
    private FinanceModels.Refund refundInput(long account,String amount,List<Long> credits,Long version) {
        return new FinanceModels.Refund(d.customer,null,account,amount,d.day,"历史销售退回退款",credits,version,"sourceless_credit");
    }
    private long createRefund(long account,String amount,List<Long> credits) {
        models.installDefault(d.tenant,d.company,"refund_request");
        return id(refundApplications.create(d.actor(d.maker),key(),refundInput(account,amount,credits,null)));
    }
    private long refundVersion(long id) {return jdbc.queryForObject("SELECT version FROM refund_request WHERE id=?",Long.class,id);}
    private Map<String,Object> refundCommand(long id,String action) {return refundApplications.command(d.actor(d.maker),id,action,key(),new FinanceModels.Version(refundVersion(id),null,null));}
    private long applyReversal(String type,long id) {
        models.installDefault(d.tenant,d.company,"document_reversal");
        return id(reversals.create(d.actor(d.maker),key(),new cn.erp.reversal.ReversalModels.Input(type,id,d.day,"核对后冲回原事实")));
    }
    private void approveReversal(long id) {reviewBusiness("document_reversal",id,d.manager);}
    private void postReversal(long id) {
        long version=jdbc.queryForObject("SELECT version FROM document_reversal WHERE id=?",Long.class,id);
        reversals.command(d.actor(d.maker),id,"post",key(),new cn.erp.reversal.ReversalModels.Version(version,null));
    }

    private record LegacyLine(Long shipmentLineId,Long unitId,String qty,String qualityResult,StockSelection stockSelection) {}
    private record LegacyInput(Long customerId,Long warehouseId,java.time.LocalDate businessDate,String reason,List<LegacyLine> lines,Long version,Long salesUserId) {}

    @Test void originalRequestSerializationKeepsThePreUpgradeIdempotencyPayload() {
        var before=new LegacyInput(d.customer,d.warehouse,d.day,"原单退货",List.of(new LegacyLine(41L,d.unit,"1","passed",null)),null,null);
        var after=new FinanceModels.ReturnInput(d.customer,d.warehouse,d.day,"原单退货",List.of(new FinanceModels.ReturnLine(41L,d.unit,"1","passed")),null);
        assertThat(json.writeValueAsString(after)).isEqualTo(json.writeValueAsString(before));
    }

    private long receive(String qty,String price) {
        var input=new PurchaseOrderModels.Input(""+d.customer,d.day,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,""+d.sku,""+d.warehouse,""+d.unit,qty,price,null,null,null,"0")),List.of(),null,null,null);
        long order=id(purchases.create(d.actor(d.maker),key(),input));
        var submitted=purchases.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        purchases.command(d.actor(d.manager),order,"approve",key(),new PurchaseOrderModels.Command(((Number)submitted.get("version")).longValue(),null,null));
        long source=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,d.warehouse,d.day,null,List.of(new ReceivingModels.ReceiptLine(source,d.unit,qty)),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);
        return receipt;
    }

    private FinanceModels.ReturnInput input(List<FinanceModels.ReturnLine> lines,Long version) {
        return new FinanceModels.ReturnInput(d.customer,d.warehouse,d.day,"老系统销售退回",lines,version,null,"sourceless","旧系统凭据");
    }
    private FinanceModels.ReturnLine line(String qty,String price,String tax,String quality,String manual,String reason) {
        return new FinanceModels.ReturnLine(null,d.unit,qty,quality,null,d.sku,price,tax,manual,reason);
    }
    private long create(FinanceModels.ReturnInput input) {return id(returns.create(d.actor(d.maker),key(),input));}
    private Map<String,Object> submit(CompanyAccess actor,long id) {return returns.command(actor,id,"submit",key(),command(id));}
    private FinanceModels.Version command(long id) {return new FinanceModels.Version(version(id),null,null);}
    private long version(long id) {return jdbc.queryForObject("SELECT version FROM sales_return WHERE id=?",Long.class,id);}
    private void approve(long id,long user) {
        reviewBusiness("sales_return",id,user);
        assertThat(returns.get(d.actor(user),id)).containsEntry("status","approved");
    }
    private void reviewBusiness(String type,long id,long user) {
        decideBusiness(type,id,user,"approve","核对成本及原始依据");
    }
    private void decideBusiness(String type,long id,long user,String action,String reason) {
        var task=jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE i.tenant_id=? AND i.business_type=? AND i.business_id=? AND t.status='pending' AND t.assignee_id=?",d.tenant,type,id,user);
        tasks.decide(d.actor(user),((Number)task.get("id")).longValue(),action,key(),new WorkflowTasks.Decision(((Number)task.get("version")).longValue(),reason,null,null,null,null,null));
    }
    private CompanyAccess without(long user,String... removed) {
        var actor=d.actor(user);var permissions=new HashSet<>(actor.permissions());permissions.removeAll(Set.of(removed));
        return new CompanyAccess(actor.tenantId(),user,actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),permissions,actor.orgScope());
    }
    @SuppressWarnings("unchecked") private static void assertHidden(Map<String,Object> row) {
        assertThat(row).doesNotContainKey("costAmount");
        for(var line:(List<Map<String,Object>>)row.get("lines")) assertThat(line).doesNotContainKeys("unitCost","costAmount","manualUnitCost","manualCostReason","costBasis");
    }
    private BigDecimal value(String sql,long id) {return jdbc.queryForObject(sql,BigDecimal.class,id);}
    @SuppressWarnings("unchecked") private void assertClosingBlocked(long id,String status) {
        assertThat(checks.check(d.actor(d.maker),"period_close",period)).anySatisfy(diff->{
            assertThat(diff.key()).isEqualTo("sales_return");assertThat(diff.actual()).isEqualByComparingTo("1");
        });
        var report=periods.closeCheck(d.actor(d.maker),period);
        assertThat(report).containsEntry("passed",false);
        var items=(List<Map<String,Object>>)report.get("items");
        var pending=items.stream().filter(item->item.get("check").equals("unposted_documents")).findFirst().orElseThrow();
        assertThat(pending).containsEntry("passed",false);
        assertThat((List<Map<String,Object>>)pending.get("documents")).anySatisfy(doc->
                assertThat(doc).containsEntry("documentType","sales_return").containsEntry("documentId",""+id).containsEntry("status",status));
    }
    private void zeroDifferences() {
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close"))
            assertThat(checks.check(d.actor(d.maker),type,type.equals("period_close")?period:null)).as(type).isEmpty();
    }
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String code) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
