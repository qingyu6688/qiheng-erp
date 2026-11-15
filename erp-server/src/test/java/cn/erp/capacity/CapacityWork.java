package cn.erp.capacity;

import cn.erp.accounting.LedgerService;
import cn.erp.accounting.RefundService;
import cn.erp.fulfillment.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.inventory.InventoryModels.*;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesfinance.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 容量入口调用真实领域事务，只用于隔离的测试进程。 */
@Service
@Profile("dev & load & !prod & !production")
public class CapacityWork {
    private final CapacityFixture fixture;
    private final JdbcTemplate jdbc;
    private final SalesOrderService orders;
    private final SalesOrderQueries orderQueries;
    private final ShipmentService shipments;
    private final ReceiptService receipts;
    private final RefundApplicationService refunds;
    private final RefundService refundLocks;
    private final ReservationService reservations;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final BusinessCommands commands;
    private final ReconciliationChecks checks;
    public CapacityWork(CapacityFixture fixture,JdbcTemplate jdbc,SalesOrderService orders,SalesOrderQueries orderQueries,ShipmentService shipments,ReceiptService receipts,RefundApplicationService refunds,RefundService refundLocks,ReservationService reservations,InventoryService inventory,LedgerService ledger,BusinessCommands commands,ReconciliationChecks checks){
        this.fixture=fixture;this.jdbc=jdbc;this.orders=orders;this.orderQueries=orderQueries;this.shipments=shipments;this.receipts=receipts;this.refunds=refunds;this.refundLocks=refundLocks;this.reservations=reservations;this.inventory=inventory;this.ledger=ledger;this.commands=commands;this.checks=checks;
    }
    CapacityFixture.Context context(CompanyAccess actor){return fixture.contexts().stream().filter(item->item.tenant()==actor.tenantId()&&item.company()==actor.companyId()).findFirst().orElseThrow(()->new ApiException(404,"NOT_FOUND","容量公司不存在"));}
    public Map<String,Object> workflow(CompanyAccess actor,int count,int slot,String key) {
        if(!Set.of(1,20,100).contains(count)||slot<0)throw new ApiException(400,"VALIDATION_FAILED","容量档位无效");
        var context=context(actor);var reviewer=fixture.scope(context,true);LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        var lines=new ArrayList<SalesOrderModels.Line>();for(int index=0;index<count;index++)lines.add(new SalesOrderModels.Line(null,context.skus().get((slot+index)%100).toString(),Long.toString(context.warehouse()),Long.toString(context.unit()),"1","10",null,"0",null,"0"));
        var order=orders.create(actor,key+"c",new SalesOrderModels.Input(Long.toString(context.customer()),day,day.plusDays(1),true,"0",lines,List.of(),"容量真实销售",null,null));long orderId=CapacityFixture.number(order,"id");
        order=orders.command(actor,orderId,"submit",key+"s",new SalesOrderModels.Command(0L,null,null));
        orders.command(reviewer,orderId,"approve",key+"a",new SalesOrderModels.Command(CapacityFixture.number(order,"version"),null,null));
        @SuppressWarnings("unchecked") var currentLines=(List<Map<String,Object>>)orderQueries.get(actor,orderId).get("lines");
        var shipmentLines=currentLines.stream().map(line->new ShipmentModels.InputLine(CapacityFixture.number(line,"id"),context.unit(),"1")).toList();
        var shipment=shipments.create(actor,key+"h",new ShipmentModels.Input(context.customer(),context.warehouse(),day,"pickup","容量真实出库",shipmentLines,null));long shipmentId=CapacityFixture.number(shipment,"id");
        shipments.submit(actor,shipmentId,key+"hs",0L);shipments.post(actor,shipmentId,key+"hp",1L);
        var receipt=receipts.create(actor,key+"r",new FinanceModels.Receipt("customer",context.customer(),null,context.cash(),Integer.toString(count*10),day,orderId,null,"容量自动核销",null));long receiptId=CapacityFixture.number(receipt,"id");
        receipts.command(actor,receiptId,"submit",key+"rs",new FinanceModels.Version(0L,null,null));receipts.command(reviewer,receiptId,"confirm",key+"rc",new FinanceModels.Version(1L,null,null));
        return Map.of("orderId",Long.toString(orderId),"shipmentId",Long.toString(shipmentId),"receiptId",Long.toString(receiptId),"lines",count,"status","confirmed");
    }
    public Map<String,Object> reserve(CompanyAccess actor,int slot) {
        var context=context(actor);if(slot<0||slot>=1000)throw new ApiException(400,"VALIDATION_FAILED","热点请求序号须为0到999");
        long id=reservations.reserve(actor,List.of(new ReservationRequest(context.hotLines().get(slot),new Key(context.hotWarehouse(),context.skus().getFirst()),BigDecimal.ONE,Instant.now().plusSeconds(3600)))).getFirst();
        return Map.of("id",Long.toString(id),"status","reserved");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> duplicate(CompanyAccess actor,String key) {
        return inbound(actor,key,"capacity.duplicate","capacity_duplicate");
    }
    public Map<String,Object> mixedInbound(CompanyAccess actor,String key) {
        return inbound(actor,key,"capacity.mixed_inbound","capacity_mixed_inbound");
    }
    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> inbound(CompanyAccess actor,String key,String operation,String sourceType) {
        var context=context(actor);LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
        return commands.execute(actor,operation,key,Map.of("quantity","1","cost","1.00"),200,()->{
            long source=Ids.next();inventory.receive(actor,new Posting(new Key(context.warehouse(),context.skus().getFirst()),new Source(sourceType,source,source),day,context.period(),"adjust_in"),BigDecimal.ONE,BigDecimal.ONE);
            long journal=ledger.post(actor,new LedgerService.Posting(sourceType,source,0,context.period(),day,List.of(new LedgerService.Line("INVENTORY","D",BigDecimal.ONE,null,null,context.warehouse(),context.skus().getFirst(),source,"容量真实入库"),new LedgerService.Line("OPENING_EQUITY","C",BigDecimal.ONE,null,null,null,null,source,"容量真实入库"))));
            return Map.of("id",Long.toString(source),"journalId",Long.toString(journal));
        },(Class)Map.class);
    }
    public Map<String,Object> refund(CompanyAccess actor,int slot) {
        var context=context(actor);if(slot<0||slot>=100)throw new ApiException(400,"VALIDATION_FAILED","退款请求序号须为0到99");
        long id=context.refunds().get(slot);
        return refunds.command(actor,id,"submit","load_refund_"+id,new FinanceModels.Version(0L,null,null));
    }
    public Map<String,Object> verify(CompanyAccess actor,boolean finish) {
        var context=context(actor);
        if(finish) {
            for(long id:context.refunds()) {
                String state=jdbc.queryForObject("SELECT status FROM refund_request WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),id);
                if(Set.of("pending_review","pending_payment").contains(state))refundLocks.release(actor,id);
            }
        }
        var failures=new ArrayList<Map<String,Object>>();
        for(String type:ReconciliationChecks.TYPES) {
            for(var difference:checks.check(actor,type,type.equals("period_close")?context.period():null))failures.add(Map.of("check",type,"object",difference.key(),"expected",difference.expected().toPlainString(),"actual",difference.actual().toPlainString()));
        }
        long over=jdbc.queryForObject("SELECT COUNT(*) FROM sales_order_line WHERE tenant_id=? AND company_id=? AND (closed_base_qty+shipped_base_qty+pending_ship_base_qty>base_qty OR signed_base_qty>shipped_base_qty)",Long.class,actor.tenantId(),actor.companyId());
        long duplicates=jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT source_type,source_id,source_revision,posting_action,COUNT(*) n FROM journal WHERE tenant_id=? AND company_id=? GROUP BY source_type,source_id,source_revision,posting_action HAVING n>1) x",Long.class,actor.tenantId(),actor.companyId());
        var stock=inventory.balance(actor,new Key(context.hotWarehouse(),context.skus().getFirst()));
        long duplicateFacts=jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_type='capacity_duplicate'",Long.class,actor.tenantId(),actor.companyId());
        BigDecimal pending=jdbc.queryForObject("SELECT refund_pending_amount+refunded_amount FROM receipt WHERE id=?",BigDecimal.class,context.originalReceipt());
        var result=new LinkedHashMap<String,Object>(Map.of("differences",failures,"overFulfillment",over,"duplicatePosting",duplicates,"hotReserved",stock.reserved().toPlainString(),"hotAvailable",stock.available().toPlainString(),"duplicateFacts",duplicateFacts,"refundUsed",pending.toPlainString(),"refundLimit","100.00","pendingEvents",jdbc.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE tenant_id=? AND status<>'done'",Long.class,actor.tenantId())));
        result.put("pendingExports",jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE tenant_id=? AND status IN ('pending','running')",Long.class,actor.tenantId()));
        result.put("failedExports",jdbc.queryForObject("SELECT COUNT(*) FROM export_job WHERE tenant_id=? AND status='failed'",Long.class,actor.tenantId()));
        result.put("excessRefunds",jdbc.queryForObject("SELECT COUNT(*) FROM receipt WHERE tenant_id=? AND company_id=? AND refund_pending_amount+refunded_amount>amount",Long.class,actor.tenantId(),actor.companyId()));
        result.put("mixedInboundFacts",jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND source_type='capacity_mixed_inbound'",Long.class,actor.tenantId(),actor.companyId()));
        result.put("mixedRefundPaid",jdbc.queryForObject("SELECT COUNT(*) FROM refund_request WHERE tenant_id=? AND company_id=? AND reason='容量混合退款' AND status='refunded'",Long.class,actor.tenantId(),actor.companyId()));
        return result;
    }
}
