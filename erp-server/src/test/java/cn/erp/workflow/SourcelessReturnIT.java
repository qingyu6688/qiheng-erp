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
        for(String table:List.of("sales_return_line","sales_return")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
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

    private record LegacyLine(Long shipmentLineId,Long unitId,String qty,String qualityResult,StockSelection stockSelection) {}
    private record LegacyInput(Long customerId,Long warehouseId,java.time.LocalDate businessDate,String reason,List<LegacyLine> lines,Long version,Long salesUserId) {}

    @Test void originalRequestSerializationKeepsThePreUpgradeIdempotencyPayload() {
        var before=new LegacyInput(d.customer,d.warehouse,d.day,"原单退货",List.of(new LegacyLine(41L,d.unit,"1","passed",null)),null,null);
        var after=new FinanceModels.ReturnInput(d.customer,d.warehouse,d.day,"原单退货",List.of(new FinanceModels.ReturnLine(41L,d.unit,"1","passed")),null);
        assertThat(json.writeValueAsString(after)).isEqualTo(json.writeValueAsString(before));
    }

    private void receive(String qty,String price) {
        var input=new PurchaseOrderModels.Input(""+d.customer,d.day,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,""+d.sku,""+d.warehouse,""+d.unit,qty,price,null,null,null,"0")),List.of(),null,null,null);
        long order=id(purchases.create(d.actor(d.maker),key(),input));
        var submitted=purchases.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        purchases.command(d.actor(d.manager),order,"approve",key(),new PurchaseOrderModels.Command(((Number)submitted.get("version")).longValue(),null,null));
        long source=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,d.warehouse,d.day,null,List.of(new ReceivingModels.ReceiptLine(source,d.unit,qty)),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);
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
        var task=jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE i.tenant_id=? AND i.business_type='sales_return' AND i.business_id=? AND t.status='pending' AND t.assignee_id=?",d.tenant,id,user);
        tasks.decide(d.actor(user),((Number)task.get("id")).longValue(),"approve",key(),new WorkflowTasks.Decision(((Number)task.get("version")).longValue(),"核对成本及退货依据",null,null,null,null,null));
        assertThat(returns.get(d.actor(user),id)).containsEntry("status","approved");
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
