package cn.erp.purchasing;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static cn.erp.purchasing.PurchaseOrderModels.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class PurchasingIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired PurchaseOrderService orders;
    @Autowired PurchaseOrderQueries queries;
    @Autowired RequisitionService requisitions;
    private long tenant,company,warehouse,otherWarehouse,supplier,sku,unit,makerId,reviewerId;
    private CompanyAccess maker,reviewer;
    private static final LocalDate DAY=LocalDate.of(2026,11,15);

    @BeforeEach void prepare() {
        tenant=Ids.next();company=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();supplier=Ids.next();sku=Ids.next();unit=Ids.next();makerId=Ids.next();reviewerId=Ids.next();
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'采购订单测试')",tenant,"PUR"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲')",company,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id,over_receipt_pct) VALUES(?,?,?,5)",Ids.next(),tenant,company);
        for(long user:List.of(makerId,reviewerId)) {jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",user,tenant,"U"+user,"测试经办员","仅用于服务测试的散列");}
        for(long wh:List.of(warehouse,otherWarehouse)) {jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",wh,tenant,company,"W"+wh,"收货仓");}
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'SUP','测试供应商')",supplier,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'supplier',1)",Ids.next(),tenant,supplier);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','采购商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,purchase_price,default_tax_rate) VALUES(?,?,?,'S','规格',10,.13)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        maker=new CompanyAccess(tenant,makerId,company,true,Set.of(),permissions);reviewer=new CompanyAccess(tenant,reviewerId,company,true,Set.of(),permissions);
    }
    @AfterEach void cleanup() {
        for(String table:List.of("document_change","approval_record","audit_log","idempotency_record","document_sequence","purchase_order_fee","purchase_order_line","purchase_order","purchase_requisition_line","purchase_requisition","unit_conversion","sku","product","product_category","unit","partner_role","business_partner","warehouse","company_policy","company","sys_user")) {jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);}
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void Q18RequisitionMustBeApprovedAndDraftTransfersCannotExceedItsQuantity() {
        var req=req("10");long reqId=id(req),line=reqLine(req);
        assertCode(()->orders.create(maker,key(),input("2",line,null,null)),"REQUISITION_NOT_APPROVED");
        reqCommand(maker,reqId,"submit",null);
        assertCode(()->reqCommand(maker,reqId,"approve",null),"SAME_OPERATOR_NOT_ALLOWED");
        reqCommand(reviewer,reqId,"approve",null);
        var first=orders.create(maker,key(),input("8",line,null,null));
        assertThat(ordered(line)).isEqualByComparingTo("8");
        assertCode(()->orders.create(maker,key(),input("3",line,null,null)),"REQUISITION_QTY_EXCEEDED");
        assertThat(ordered(line)).isEqualByComparingTo("8");
        orders.edit(maker,id(first),input("6",line,version(first),null));
        assertThat(ordered(line)).isEqualByComparingTo("6");
        var second=orders.create(maker,key(),input("4",line,null,null));
        assertThat(ordered(line)).isEqualByComparingTo("10");
        long deleteVersion=version(second);orders.delete(maker,id(second),deleteVersion);orders.delete(maker,id(second),deleteVersion);
        assertThat(ordered(line)).isEqualByComparingTo("6");
        orderCommand(maker,first,"submit");orderCommand(reviewer,first,"approve");
        orders.command(maker,id(first),"cancel",key(),new Command(version(first),null,"需求取消"));
        assertThat(ordered(line)).isEqualByComparingTo("0");
        assertThat(requisitions.get(maker,reqId).get("status")).isEqualTo("approved");
    }

    @Test void Q18TwentyRoundsOfConcurrentDraftTransfersNeverOverbook() throws Exception {
        var req=approvedReq("10");long source=reqLine(req);
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                var start=new CountDownLatch(1);
                var first=pool.submit(()->attemptCreate(start,source));var second=pool.submit(()->attemptCreate(start,source));start.countDown();
                long left=first.get(10,TimeUnit.SECONDS),right=second.get(10,TimeUnit.SECONDS);
                assertThat((left>0?1:0)+(right>0?1:0)).isEqualTo(1);
                assertThat(ordered(source)).isEqualByComparingTo("7");
                long winner=left>0?left:right;
                orders.delete(maker,winner,((Number)queries.get(maker,winner).get("version")).longValue());
                assertThat(ordered(source)).isEqualByComparingTo("0");
            }
        }
    }

    @Test void R4_03ApprovalFreezesToleranceAndDoesNotReserveStockOrPostMoney() {
        var order=order("10");orderCommand(maker,order,"submit");
        assertCode(()->orderCommand(maker,order,"approve"),"SAME_OPERATOR_NOT_ALLOWED");
        orderCommand(reviewer,order,"approve");
        assertThat(lines(order).getFirst()).containsEntry("receiveLimitBaseQty","10.500000");
        jdbc.update("UPDATE company_policy SET over_receipt_pct=0 WHERE tenant_id=?",tenant);
        assertThat(lines(order).getFirst()).containsEntry("receiveLimitBaseQty","10.500000");
        var choices=items(queries.candidates(maker,idText(supplier),idText(warehouse),true,200));
        assertThat(choices).hasSize(1);assertThat(choices.getFirst()).containsEntry("receivableQty","10.500000");
        for(String table:List.of("inventory_balance","inventory_reservation","inventory_movement","journal","payable")) {assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant)).as(table).isZero();}
    }

    @Test void quantityChangeRetainsApprovedToleranceAfterPolicyChanges() {
        var order=approvedOrder("10");
        jdbc.update("UPDATE company_policy SET over_receipt_pct=10 WHERE tenant_id=?",tenant);
        var decrease=orders.change(maker,id(order),key(),input("8",null,version(order),"减少数量"));
        assertThat(decrease.get("status")).isEqualTo("approved");
        assertThat(lines(order).getFirst()).containsEntry("receiveLimitBaseQty","8.400000");
        var increase=orders.change(reviewer,id(order),key(),input("12",null,version(order),"追加数量"));
        assertThat(increase.get("status")).isEqualTo("change_pending");
        assertThat(lines(order).getFirst()).containsEntry("baseQty","8.000000");
        long change=Long.parseLong(((Map<?,?>)increase.get("change")).get("id").toString());
        assertCode(()->orders.decideChange(reviewer,change,"approve",key(),new Command(0L,null,null)),"SAME_OPERATOR_NOT_ALLOWED");
        orders.decideChange(maker,change,"approve",key(),new Command(0L,null,null));
        assertThat(lines(order).getFirst()).containsEntry("baseQty","12.000000").containsEntry("receiveLimitBaseQty","12.600000");
        assertThat(jdbc.queryForObject("SELECT over_receipt_pct FROM purchase_order WHERE id=?",BigDecimal.class,id(order))).isEqualByComparingTo("5");
    }

    @Test void versionsIdempotencyIllegalStatesAndScopeAreEnforced() {
        var input=input("3",null,null,null);String key=key();
        var order=orders.create(maker,key,input);
        assertThat(orders.create(maker,key,input).get("id")).isEqualTo(order.get("id"));
        assertCode(()->orders.create(maker,key,input("4",null,null,null)),"IDEMPOTENCY_KEY_REUSED");
        assertCode(()->orderCommand(reviewer,order,"approve"),"STATE_CONFLICT");
        orders.edit(maker,id(order),input("4",null,0L,null));
        assertCode(()->orders.delete(maker,id(order),0L),"VERSION_CONFLICT");
        var outside=new CompanyAccess(tenant,makerId,Ids.next(),true,Set.of(),maker.permissions());
        assertCode(()->queries.get(outside,id(order)),"NOT_FOUND");
        var limited=new CompanyAccess(tenant,makerId,company,false,Set.of(otherWarehouse),maker.permissions());
        assertCode(()->queries.get(limited,id(order)),"NOT_FOUND");
        assertThat(items(queries.list(limited,"",null,null,null,20))).isEmpty();
        orders.delete(maker,id(order),version(order));
        assertCode(()->queries.get(maker,id(order)),"NOT_FOUND");
    }

    @Test void closeRemainingQuantityLeavesNoReceivableToleranceSpace() {
        var order=approvedOrder("10");long line=Long.parseLong(lines(order).getFirst().get("id").toString());
        orders.close(maker,id(order),key(),new Close(version(order),"全部取消收货",List.of(new CloseLine(idText(line),"10.500000"))));
        assertThat(queries.get(maker,id(order))).containsEntry("status","closed");
        assertThat(items(queries.candidates(maker,null,null,true,200))).isEmpty();
        assertThat(lines(order).getFirst().get("progress")).isEqualTo(Map.of("baseQty","10.000000","closedQty","10.500000","receivedQty","0.000000","pendingReceiveQty","0.000000","returnedQty","0.000000","receivableQty","0.000000","closableQty","0.000000"));
    }

    @Test void pendingReceiptPreventsCancellationAndWarehouseOrPriceChangesAfterReceipt() {
        var order=approvedOrder("10");long line=Long.parseLong(lines(order).getFirst().get("id").toString());
        jdbc.update("UPDATE purchase_order_line SET pending_receive_base_qty=2 WHERE id=?",line);
        assertCode(()->orders.command(maker,id(order),"cancel",key(),new Command(version(order),null,"取消")),"DEPENDENCY_EXISTS");
        var differentWarehouse=new Input(idText(supplier),DAY,null,true,"0",List.of(new Line(idText(line),null,idText(sku),idText(otherWarehouse),idText(unit),"10","10",null,null,null,null)),List.of(),null,version(order),"改仓");
        assertCode(()->orders.change(maker,id(order),key(),differentWarehouse),"DEPENDENCY_EXISTS");
        long otherSku=Ids.next(),box=Ids.next();
        long product=jdbc.queryForObject("SELECT product_id FROM sku WHERE id=?",Long.class,sku);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,purchase_price,default_tax_rate) VALUES(?,?,?,'OTHER','替换商品',10,.13)",otherSku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,otherSku,unit);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'箱')",box,tenant);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,10,0)",Ids.next(),tenant,sku,box);
        var changes=List.of(
            new Line(idText(line),null,idText(sku),idText(warehouse),idText(unit),"10","11",null,null,null,"0.13"),
            new Line(idText(line),null,idText(sku),idText(warehouse),idText(unit),"10","10",null,null,null,"0.09"),
            new Line(idText(line),null,idText(otherSku),idText(warehouse),idText(unit),"10","10",null,null,null,"0.13"),
            new Line(idText(line),null,idText(sku),idText(warehouse),idText(box),"1","100",null,null,null,"0.13"));
        for(var replacement:changes) {
            var change=new Input(idText(supplier),DAY,null,true,"0",List.of(replacement),List.of(),null,version(order),"调整待收货行");
            assertCode(()->orders.change(maker,id(order),key(),change),"CHANGE_NOT_ALLOWED");
        }
        var allocatedDiscount=new Input(idText(supplier),DAY,null,true,"1",List.of(new Line(idText(line),null,idText(sku),idText(warehouse),idText(unit),"10","10",null,null,null,"0.13")),List.of(),null,version(order),"调整整单优惠");
        assertCode(()->orders.change(maker,id(order),key(),allocatedDiscount),"CHANGE_NOT_ALLOWED");
        assertThat(queries.get(maker,id(order))).containsEntry("status","approved");
        assertThat(lines(order).getFirst()).containsEntry("skuId",idText(sku)).containsEntry("unitId",idText(unit)).containsEntry("unitPrice","10.00000000").containsEntry("pendingReceiveBaseQty","2.000000");
        jdbc.update("UPDATE purchase_order_line SET pending_receive_base_qty=0,received_base_qty=2,recognized_amount=20 WHERE id=?",line);
        var newPrice=new Input(idText(supplier),DAY,null,true,"0",List.of(new Line(idText(line),null,idText(sku),idText(warehouse),idText(unit),"10","11",null,null,null,null)),List.of(),null,version(order),"改价");
        assertCode(()->orders.change(maker,id(order),key(),newPrice),"CHANGE_NOT_ALLOWED");
    }

    @Test void historicalUnitAndPriceSnapshotsRemainAfterCatalogChanges() {
        long box=Ids.next();jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'箱')",box,tenant);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,10,0)",Ids.next(),tenant,sku,box);
        var order=orders.create(maker,key(),new Input(idText(supplier),DAY,null,true,"0",List.of(new Line(null,null,idText(sku),idText(warehouse),idText(box),"2","100",null,null,null,null)),List.of(),null,null,null));
        jdbc.update("UPDATE unit_conversion SET rate_to_base=12 WHERE tenant_id=? AND sku_id=? AND unit_id=?",tenant,sku,box);
        jdbc.update("UPDATE sku SET purchase_price=999,default_tax_rate=.09 WHERE id=?",sku);
        assertThat(lines(order).getFirst()).containsEntry("unitRate","10.000000").containsEntry("unitPrice","100.00000000").containsEntry("baseQty","20.000000").containsEntry("taxRate","0.130000");
    }

    @Test void requisitionClosePreventsNewTransfersButExistingDraftCanReleaseQuantity() {
        var req=approvedReq("10");long source=reqLine(req);var order=orders.create(maker,key(),input("5",source,null,null));
        reqCommand(maker,id(req),"close","结束请购");
        assertCode(()->orders.create(maker,key(),input("1",source,null,null)),"REQUISITION_NOT_APPROVED");
        orders.edit(maker,id(order),input("4",source,version(order),null));
        assertThat(ordered(source)).isEqualByComparingTo("4");
        orders.delete(maker,id(order),version(order));assertThat(ordered(source)).isEqualByComparingTo("0");
    }

    @Test void requisitionDraftDeleteAndWithdrawRespectVersionsAndDoNotTouchInventory() {
        var req=req("3");long reqId=id(req);
        requisitions.edit(maker,reqId,new RequisitionModels.Input(DAY,"修订",List.of(new RequisitionModels.Line(null,idText(sku),idText(unit),"2",DAY.plusDays(1))),0L));
        assertCode(()->requisitions.delete(maker,reqId,0L),"VERSION_CONFLICT");
        reqCommand(maker,reqId,"submit",null);reqCommand(maker,reqId,"withdraw",null);
        long version=((Number)requisitions.get(maker,reqId).get("version")).longValue();
        requisitions.delete(maker,reqId,version);requisitions.delete(maker,reqId,version);
        assertCode(()->requisitions.get(maker,reqId),"NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Long.class,tenant)).isZero();
    }

    @Test void requisitionConversionRejectsFractionalMicroQuantityWithoutChangingTransferQuota() {
        long half=Ids.next();jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'半件')",half,tenant);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,.5,0)",Ids.next(),tenant,sku,half);
        var invalidLine=new RequisitionModels.Line(null,idText(sku),idText(half),"0.000001",null);
        assertCode(()->requisitions.create(maker,key(),new RequisitionModels.Input(DAY,null,List.of(invalidLine),null)),"VALIDATION_FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM purchase_requisition WHERE tenant_id=?",Long.class,tenant)).isZero();
        var req=requisitions.create(maker,key(),new RequisitionModels.Input(DAY,null,List.of(new RequisitionModels.Line(null,idText(sku),idText(half),"0.000002",null)),null));
        long source=reqLine(req);
        assertCode(()->requisitions.edit(maker,id(req),new RequisitionModels.Input(DAY,"无效数量",List.of(invalidLine),0L)),"VALIDATION_FAILED");
        assertThat(reqLine(req)).isEqualTo(source);
        assertThat(jdbc.queryForObject("SELECT base_qty FROM purchase_requisition_line WHERE id=?",BigDecimal.class,source)).isEqualByComparingTo("0.000001");
        assertThat(ordered(source)).isEqualByComparingTo("0");
        reqCommand(maker,id(req),"submit",null);reqCommand(reviewer,id(req),"approve",null);
        orders.create(maker,key(),input("0.000001",source,null,null));
        assertThat(ordered(source)).isEqualByComparingTo("0.000001");
        assertCode(()->orders.create(maker,key(),input("0.000001",source,null,null)),"REQUISITION_QTY_EXCEEDED");
        assertThat(ordered(source)).isEqualByComparingTo("0.000001");
    }

    private Map<String,Object> req(String qty) {return requisitions.create(maker,key(),new RequisitionModels.Input(DAY,null,List.of(new RequisitionModels.Line(null,idText(sku),idText(unit),qty,DAY.plusDays(1))),null));}
    private Map<String,Object> approvedReq(String qty) {var req=req(qty);reqCommand(maker,id(req),"submit",null);reqCommand(reviewer,id(req),"approve",null);return req;}
    private void reqCommand(CompanyAccess actor,long id,String action,String reason) {requisitions.command(actor,id,action,key(),new Command(((Number)requisitions.get(maker,id).get("version")).longValue(),reason,reason));}
    private Map<String,Object> order(String qty) {return orders.create(maker,key(),input(qty,null,null,null));}
    private Map<String,Object> approvedOrder(String qty) {var order=order(qty);orderCommand(maker,order,"submit");orderCommand(reviewer,order,"approve");return order;}
    private void orderCommand(CompanyAccess actor,Map<String,Object> order,String action) {orders.command(actor,id(order),action,key(),new Command(version(order),null,null));}
    private Input input(String qty,Long requisition,Long version,String reason) {return new Input(idText(supplier),DAY,null,true,"0",List.of(new Line(null,requisition==null?null:idText(requisition),idText(sku),idText(warehouse),idText(unit),qty,"10",null,null,null,null)),List.of(),null,version,reason);}
    private long attemptCreate(CountDownLatch start,long source) throws Exception {start.await();try{return id(orders.create(maker,key(),input("7",source,null,null)));}catch(ApiException error){if(error.getErrorCode().equals("REQUISITION_QTY_EXCEEDED")) {return 0;}throw error;}}
    private long reqLine(Map<String,Object> req) {return Long.parseLong(((Map<?,?>)((List<?>)requisitions.get(maker,id(req)).get("lines")).getFirst()).get("id").toString());}
    private BigDecimal ordered(long id) {return jdbc.queryForObject("SELECT ordered_base_qty FROM purchase_requisition_line WHERE id=?",BigDecimal.class,id);}
    private long version(Map<String,Object> order) {return ((Number)queries.get(maker,id(order)).get("version")).longValue();}
    private long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private String idText(long value) {return Long.toString(value);}
    private String key() {return UUID.randomUUID().toString();}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> lines(Map<String,Object> order) {return (List<Map<String,Object>>)queries.get(maker,id(order)).get("lines");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> page) {return (List<Map<String,Object>>)page.get("items");}
    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
}
