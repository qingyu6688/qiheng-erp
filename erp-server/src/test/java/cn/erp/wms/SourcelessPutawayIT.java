package cn.erp.wms;

import cn.erp.ErpApplication;
import cn.erp.identity.AccessService;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.SalesReturnService;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.workflow.WorkflowModels;
import cn.erp.workflow.WorkflowTasks;
import cn.erp.workflow.WorkflowTestData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class SourcelessPutawayIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessService access;
    @Autowired SalesOrderService sales;
    @Autowired WorkflowModels models;
    @Autowired WorkflowTasks workflow;
    @Autowired ProcessEngine engine;
    @Autowired SalesReturnService returns;
    @Autowired ReversalService reversals;
    @Autowired InventoryService inventory;
    @Autowired PutawayTasks tasks;
    @Autowired ReconciliationChecks checks;
    private WorkflowTestData d;
    private long receiving,storage;

    @BeforeEach void prepare() {
        d=new WorkflowTestData(jdbc,access,sales,models);
        // 退货按历史业务日入账，上架按实际作业日执行；跨月运行时仍保留两个真实开放期间。
        var month=YearMonth.now(ZoneId.of("Asia/Shanghai"));
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                Ids.next(),d.tenant,d.company,month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
        jdbc.update("UPDATE inventory_balance SET on_hand_qty=0 WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE inventory_cost SET qty=0,total_cost=0 WHERE tenant_id=?",d.tenant);
        receiving=location("R01","receiving");storage=location("S01","storage");
        long shipping=location("SH01","shipping");
        jdbc.update("UPDATE warehouse SET location_managed=1,default_receiving_location_id=?,default_shipping_location_id=? WHERE tenant_id=? AND id=?",receiving,shipping,d.tenant,d.warehouse);
        jdbc.update("UPDATE sku SET batch_managed=1,serial_managed=1,issue_strategy='fefo' WHERE tenant_id=? AND id=?",d.tenant,d.sku);
        tasks.mode(d.actor(d.maker),d.warehouse,new PutawayTasks.Mode("required"));
    }

    @AfterEach void cleanup() {
        if(d==null)return;
        for(String table:List.of("wms_task_confirmation","wms_task_line","wms_task","inv_shipment_pick","doc_line_serial","inv_movement_serial","inv_serial","inv_movement_hold"))
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        for(String fact:List.of("receivable","inventory_movement")) jdbc.update("DELETE FROM "+fact+" WHERE tenant_id=? AND posting_action='reverse'",d.tenant);
        for(String table:List.of("sales_return_line","sales_return","inventory_movement","inventory_balance","inv_batch")) jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        jdbc.update("UPDATE warehouse SET wms_task_mode='off',location_managed=0,default_receiving_location_id=NULL,default_shipping_location_id=NULL WHERE tenant_id=?",d.tenant);
        jdbc.update("DELETE FROM wms_location WHERE tenant_id=?",d.tenant);jdbc.update("DELETE FROM wms_zone WHERE tenant_id=?",d.tenant);
        d.cleanup(engine);
    }

    @Test void qualifiedReturnCreatesAndExecutesPutawayWithoutAnOriginalShipment() {
        long source=approved(List.of(line("passed",List.of("SN-1","SN-2")),line("pending",List.of("SN-WAIT"))));post(source);
        var task=task(source);long taskId=id(task);
        assertThat(task).containsEntry("sourceType","sales_return").containsEntry("sourceId",""+source);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment WHERE tenant_id=?",Integer.class,d.tenant)).isZero();
        var lines=items(tasks.detail(d.actor(d.maker),taskId),"lines");assertThat(lines).hasSize(1);
        long line=id(lines.getFirst());assertThat(lines.getFirst()).containsEntry("batchNo","RETURN-LOT").containsEntry("planQty","2.000000");
        var before=inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku));
        assertThat(before.onHand()).isEqualByComparingTo("3");assertThat(before.blocked()).isEqualByComparingTo("1");
        tasks.claim(d.actor(d.maker),taskId,key(),new PutawayTasks.Revision(0L));
        code(()->tasks.confirm(d.actor(d.maker),taskId,line,key(),scan("SN-WAIT",1L)),"VALIDATION_FAILED");
        String firstKey=key();tasks.confirm(d.actor(d.maker),taskId,line,firstKey,scan("SN-1",1L));
        tasks.confirm(d.actor(d.maker),taskId,line,firstKey,scan("SN-1",1L));
        tasks.confirm(d.actor(d.maker),taskId,line,key(),scan("SN-2",2L));
        tasks.finish(d.actor(d.maker),taskId,key(),new PutawayTasks.Revision(3L));
        assertThat(tasks.detail(d.actor(d.maker),taskId)).containsEntry("status","done");
        assertThat(inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku))).isEqualTo(before);
        assertThat(locationQty(storage)).isEqualByComparingTo("2");assertThat(locationQty(receiving)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no='SN-WAIT'",String.class,d.tenant)).isEqualTo("quality_hold");
        code(()->reversals.create(d.actor(d.maker),key(),new ReversalModels.Input("sales_return",source,d.day,"已上架不能直接冲销")),"WMS_PUTAWAY_ALREADY_MOVED");
        reconciled();
    }

    @Test void untouchedPutawayIsCancelledWhenTheSourcelessReturnIsReversed() {
        long source=approved(List.of(line("passed",List.of("SN-1"))));post(source);
        long task=id(task(source));models.installDefault(d.tenant,d.company,"document_reversal");
        long reversal=id(reversals.create(d.actor(d.maker),key(),new ReversalModels.Input("sales_return",source,d.day,"入库有误尚未上架")));
        approve("document_reversal",reversal,d.manager);
        reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
        assertThat(tasks.detail(d.actor(d.maker),task)).containsEntry("status","cancelled");
        assertThat(returns.get(d.actor(d.maker),source)).containsEntry("status","reversed");
        assertThat(inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku)).onHand()).isEqualByComparingTo("0");
        assertThat(inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku)).cost()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT status FROM inv_serial WHERE tenant_id=? AND serial_no='SN-1'",String.class,d.tenant)).isEqualTo("reversed");
        reconciled();
    }

    @Test void aLateDuplicateSerialRollsBackTheEntireInboundAndPutaway() {
        long source=approved(List.of(line("passed",List.of("SN-DUP")),line("passed",List.of("SN-DUP"))));
        code(()->post(source),"SERIAL_NOT_AVAILABLE");
        assertThat(returns.get(d.actor(d.maker),source)).containsEntry("status","approved");
        for(String table:List.of("inventory_movement","inv_serial","inv_batch","receivable","journal","wms_task"))
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Integer.class,d.tenant)).as(table).isZero();
        assertThat(inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku)).onHand()).isEqualByComparingTo("0");
        assertThat(inventory.balance(d.actor(d.maker),new Key(d.warehouse,d.sku)).cost()).isEqualByComparingTo("0");
        reconciled();
    }

    private long location(String code,String type) {
        long zone=Ids.next(),id=Ids.next();
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,?,'退货作业区',?)",zone,d.tenant,d.company,d.warehouse,code,type);
        jdbc.update("INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name) VALUES(?,?,?,?,?,?,'退货作业位')",id,d.tenant,d.company,d.warehouse,zone,code);return id;
    }
    private FinanceModels.ReturnLine line(String quality,List<String> serials) {
        return new FinanceModels.ReturnLine(null,d.unit,""+serials.size(),quality,new StockSelection(null,"RETURN-LOT",d.day.minusDays(3),LocalDate.now().plusDays(30),null,serials),d.sku,"10","0","5","历史成本凭据");
    }
    private long approved(List<FinanceModels.ReturnLine> lines) {
        long id=id(returns.create(d.actor(d.maker),key(),new FinanceModels.ReturnInput(d.customer,d.warehouse,d.day,"旧系统销售退回",lines,null,null,"sourceless","退货单原件")));
        returns.command(d.actor(d.maker),id,"submit",key(),new FinanceModels.Version(0L,null,null));approve("sales_return",id,d.financeA);return id;
    }
    private void approve(String type,long id,long user) {
        var task=jdbc.queryForMap("SELECT t.id,t.version FROM wf_task_ext t JOIN wf_instance i ON i.tenant_id=t.tenant_id AND i.id=t.instance_id WHERE i.tenant_id=? AND i.business_type=? AND i.business_id=? AND t.status='pending' AND t.assignee_id=?",d.tenant,type,id,user);
        workflow.decide(d.actor(user),((Number)task.get("id")).longValue(),"approve",key(),new WorkflowTasks.Decision(((Number)task.get("version")).longValue(),"核对退货成本及追溯",null,null,null,null,null));
    }
    private void post(long source) {returns.command(d.actor(d.maker),source,"post",key(),new FinanceModels.Version(2L,null,null));}
    private Map<String,Object> task(long source) {return items(tasks.page(d.actor(d.maker),d.warehouse,null,null,20),"items").stream().filter(row->row.get("sourceId").equals(""+source)).findFirst().orElseThrow();}
    private PutawayTasks.Confirmation scan(String serial,long version) {return new PutawayTasks.Confirmation("R01","S01","RETURN-LOT","1",List.of(serial),version);}
    private BigDecimal locationQty(long id) {return jdbc.queryForObject("SELECT COALESCE(SUM(on_hand_qty),0) FROM inventory_balance WHERE tenant_id=? AND company_id=? AND location_key=?",BigDecimal.class,d.tenant,d.company,""+id);}
    private void reconciled() {
        for(String type:List.of("inventory_balance","inventory_cost","batch_balance","stock_blocks","location_balance","open_item","journal_balance","ledger_inventory"))
            assertThat(checks.check(d.actor(d.maker),type,null)).as(type).isEmpty();
    }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> items(Map<String,Object> value,String key) {return (List<Map<String,Object>>)value.get(key);}
    private static long id(Map<String,Object> value) {return Long.parseLong(value.get("id").toString());}
    private static String key() {return UUID.randomUUID().toString();}
    private static void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,String expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(expected));
    }
}
