package cn.erp.receiving;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.reconciliation.ReconciliationChecks;
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
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class ReceivingIT {
    private static final LocalDate DAY=LocalDate.of(2026,11,15);
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService service;
    @Autowired ReceivingQueries queries;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired ReconciliationChecks checks;
    @Autowired cn.erp.purchasing.PurchaseOrderService purchaseOrders;
    @Autowired cn.erp.purchasing.PurchaseOrderQueries purchaseQueries;
    private CompanyAccess scope;
    private long tenant,company,supplier,warehouse,otherWarehouse,unit,sku,period;
    private int number;

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();supplier=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();unit=Ids.next();sku=Ids.next();period=Ids.next();number=0;
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("goods_receipt:view","goods_receipt:create","goods_receipt:update","goods_receipt:submit","goods_receipt:void","goods_receipt:post",
            "purchase_return:view","purchase_return:create","purchase_return:update","purchase_return:submit","purchase_return:void","purchase_return:post","cost:view"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'采购收退货测试')",tenant,"RCV"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲')",company,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'S','测试供应商')",supplier,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),tenant,supplier);
        for(long id:List.of(warehouse,otherWarehouse)) {jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",id,tenant,company,"W"+id,"仓库"+id);}
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'S','测试商品',0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=NULL WHERE tenant_id=?",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item","payable","document_change","approval_record",
            "purchase_return_line","purchase_return","goods_receipt_line","goods_receipt","inventory_reservation_change","inventory_reservation","cost_adjustment","inventory_movement","inventory_cost","inventory_balance",
            "purchase_order_fee","purchase_order_line","purchase_order","audit_log","idempotency_record","document_sequence","accounting_period",
            "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","warehouse","company_policy","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test void frozenToleranceCumulativeAmountsAndFeeAreRecognizedOnlyOnce() {
        long source=order("10","100","0","11");
        long order=jdbc.queryForObject("SELECT order_id FROM purchase_order_line WHERE id=?",Long.class,source);
        jdbc.update("INSERT INTO purchase_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,'freight','buyer',30,0,30,0)",Ids.next(),tenant,company,order);
        long first=receive(source,"6",DAY);
        assertThat(queries.get(scope,false,first)).containsEntry("payableAmount","90.00").containsEntry("costAmount","60.00");
        jdbc.update("UPDATE company_policy SET over_receipt_pct=0 WHERE tenant_id=?",tenant);
        long second=receive(source,"5",DAY);
        assertThat(queries.get(scope,false,second)).containsEntry("payableAmount","50.00").containsEntry("costAmount","50.00");
        assertThat(value("SELECT recognized_amount FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("110");
        assertThat(value("SELECT SUM(amount) FROM payable WHERE tenant_id=?",tenant)).isEqualByComparingTo("140");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("110");
        long extra=draft(source,"0.000001",DAY);
        assertCode(()->service.command(scope,false,extra,"submit",key(),0L),"RECEIVE_QTY_EXCEEDED");
        zeroDifferences();
    }

    @Test void B12PurchaseReturnUsesOriginalCostAndRecordsTenYuanZeroQuantityResidue() {
        receive(order("10","100","0","10"),"10",DAY);
        long original=receive(order("10","140","0","10"),"10",DAY);
        issue("15",DAY);
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("60");
        long returned=returned(receiptLine(original),"5");
        var result=queries.get(scope,true,returned);
        assertThat(result).containsEntry("debitAmount","70.00").containsEntry("costAmount","60.00");
        assertThat(onlyLine(result)).containsEntry("unitCost","14.00000000").containsEntry("costAdjustAmount","10.00");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("0");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("0");
        assertThat(value("SELECT SUM(amount) FROM cost_adjustment WHERE tenant_id=?",tenant)).isEqualByComparingTo("10");
        zeroDifferences();
    }

    @Test void B13HistoricalReceiptOnlyChangesCostsOfLaterPostings() {
        receive(order("100","1000","0","100"),"100",DAY.minusDays(2));
        receive(order("50","650","0","50"),"50",DAY.minusDays(1));
        long source=issue("30",DAY);
        assertThat(value("SELECT unit_cost FROM inventory_movement WHERE tenant_id=? AND source_id=?",tenant,source)).isEqualByComparingTo("11");
        receive(order("30","435","0","30"),"30",DAY.minusDays(3));
        assertThat(value("SELECT cost_delta FROM inventory_movement WHERE tenant_id=? AND source_id=?",tenant,source)).isEqualByComparingTo("-330");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("1755");
        issue("150",DAY);
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("0");
        zeroDifferences();
    }

    @Test void taxIdentitySnapshotSurvivesPolicyChangeAndReturn() {
        jdbc.update("UPDATE company_policy SET taxpayer_type='small' WHERE tenant_id=?",tenant);
        long receipt=receive(order("10","113","13","10"),"10",DAY);
        assertThat(queries.get(scope,false,receipt)).containsEntry("costAmount","113.00");
        jdbc.update("UPDATE company_policy SET taxpayer_type='general' WHERE tenant_id=?",tenant);
        long returned=returned(receiptLine(receipt),"2");
        assertThat(queries.get(scope,true,returned)).containsEntry("debitAmount","22.60").containsEntry("costAmount","22.60");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_line WHERE tenant_id=? AND account_code='INPUT_VAT'",Long.class,tenant)).isZero();
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).cost()).isEqualByComparingTo("90.40");
        long general=receive(order("1","11.30","1.30","1"),"1",DAY);
        assertThat(queries.get(scope,false,general)).containsEntry("costAmount","10.00");
        zeroDifferences();
    }

    @Test void twentyConcurrentReceiptSubmissionsNeverExceedApprovedLimit() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                long source=order("1","10","0","1"),first=draft(source,"1",DAY),second=draft(source,"1",DAY);
                long winner=compete(pool,false,first,second);
                assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("1");
                service.command(scope,false,winner,"void",key(),version(false,winner));
                assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("0");
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=?",Long.class,tenant)).isZero();
        zeroDifferences();
    }

    @Test void twentyConcurrentReturnSubmissionsReserveSourceOnlyOnce() throws Exception {
        long receipt=receive(order("3","30","0","3"),"3",DAY),source=receiptLine(receipt);
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                long first=returnDraft(source,"2"),second=returnDraft(source,"2"),winner=compete(pool,true,first,second);
                assertThat(value("SELECT pending_return_base_qty FROM goods_receipt_line WHERE id=?",source)).isEqualByComparingTo("2");
                service.command(scope,true,winner,"void",key(),version(true,winner));
            }
        }
        assertThat(value("SELECT pending_return_base_qty FROM goods_receipt_line WHERE id=?",source)).isEqualByComparingTo("0");
        zeroDifferences();
    }

    @Test void versionStateCompanyWarehouseAndCostBoundariesAreEnforced() {
        long source=order("2","20","0","2");long draft=draft(source,"1",DAY);
        assertCode(()->service.command(scope,false,draft,"post",key(),0L),"STATE_CONFLICT");
        String request=key();var submitted=service.command(scope,false,draft,"submit",request,0L);
        assertThat(service.command(scope,false,draft,"submit",request,0L).get("version").toString()).isEqualTo(submitted.get("version").toString());
        assertCode(()->service.command(scope,false,draft,"post",key(),0L),"VERSION_CONFLICT");
        var wrongWarehouse=new CompanyAccess(tenant,scope.userId(),company,false,Set.of(otherWarehouse),scope.permissions());
        assertCode(()->queries.get(wrongWarehouse,false,draft),"NOT_FOUND");
        assertCode(()->service.command(wrongWarehouse,false,draft,"submit",request,0L),"NOT_FOUND");
        var foreign=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),scope.permissions());
        assertCode(()->queries.get(foreign,false,draft),"NOT_FOUND");
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->service.command(scope,false,draft,"post",key(),1L),"PERIOD_CLOSED");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("0");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        String postKey=key();var posted=service.command(scope,false,draft,"post",postKey,1L);
        assertThat(posted).doesNotContainKey("costAmount");assertThat(onlyLine(posted)).doesNotContainKeys("unitCost","costAmount");
        var noCost=new CompanyAccess(tenant,scope.userId(),company,true,Set.of(),Set.of("goods_receipt:post","goods_receipt:view"));
        assertThat(service.command(noCost,false,draft,"post",postKey,1L)).doesNotContainKey("costAmount");
        assertThat(queries.get(scope,false,draft)).containsEntry("costAmount","10.00");
        assertThat(queries.get(noCost,false,draft)).doesNotContainKey("costAmount");
        assertCode(()->service.command(scope,false,draft,"post",key(),version(false,draft)),"STATE_CONFLICT");
        long returned=returnDraft(receiptLine(draft),"1");
        assertCode(()->service.command(scope,true,returned,"post",key(),0L),"STATE_CONFLICT");
        assertCode(()->service.command(scope,true,returned,"void",key(),0L),"STATE_CONFLICT");
        zeroDifferences();
    }

    @Test void submittedReceiptCanPostDuringPurchaseChangeButNewSubmitIsBlocked() {
        long source=order("2","20","0","2"),first=draft(source,"1",DAY),other=draft(source,"1",DAY);
        service.command(scope,false,first,"submit",key(),0L);
        jdbc.update("UPDATE purchase_order SET status='change_pending' WHERE id=(SELECT order_id FROM purchase_order_line WHERE id=?)",source);
        assertCode(()->service.command(scope,false,other,"submit",key(),0L),"CHANGE_PENDING");
        service.command(scope,false,first,"post",key(),1L);
        assertThat(queries.get(scope,false,first).get("status")).isEqualTo("posted");
        zeroDifferences();
    }

    @Test void submittedReceiptCanPostAfterPurchaseRemarkAndDeliveryDateChange() {
        long source=order("2","20","0","2"),receipt=draft(source,"1",DAY);
        service.command(scope,false,receipt,"submit",key(),0L);
        long order=jdbc.queryForObject("SELECT order_id FROM purchase_order_line WHERE id=?",Long.class,source);
        var before=purchaseQueries.get(scope,order);
        var input=new cn.erp.purchasing.PurchaseOrderModels.Input(Long.toString(supplier),DAY.minusDays(5),DAY.plusDays(3),true,"0",
            List.of(new cn.erp.purchasing.PurchaseOrderModels.Line(Long.toString(source),null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"2","10",null,null,null,"0")),
            List.of(),"更新交货安排",((Number)before.get("version")).longValue(),"只调整备注与交期");
        assertThat(purchaseOrders.change(scope,order,key(),input)).containsEntry("status","approved");
        assertThat(purchaseQueries.get(scope,order)).containsEntry("remark","更新交货安排").containsEntry("deliveryDate",DAY.plusDays(3).toString());
        service.command(scope,false,receipt,"post",key(),1L);
        var posted=queries.get(scope,false,receipt);
        assertThat(posted).containsEntry("status","posted").containsEntry("payableAmount","10.00").containsEntry("costAmount","10.00");
        assertThat(onlyLine(posted)).containsEntry("skuId",Long.toString(sku)).containsEntry("unitRate","1.000000");
        assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("0");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("1");
        zeroDifferences();
    }

    @Test void draftReceiptRejectsChangedSourceSkuUntilEdited() {
        long source=order("2","20","0","2"),receipt=draft(source,"1",DAY),otherSku=Ids.next();
        long product=jdbc.queryForObject("SELECT product_id FROM sku WHERE id=?",Long.class,sku);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'NEW','变更后的商品',0)",otherSku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,otherSku,unit);
        changeSource(source,otherSku,unit,"2","10");
        assertCode(()->service.command(scope,false,receipt,"submit",key(),0L),"SOURCE_CHANGED");
        assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("0");
        service.updateReceipt(scope,receipt,new ReceivingModels.ReceiptInput(supplier,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(source,unit,"1")),0L));
        service.command(scope,false,receipt,"submit",key(),1L);service.command(scope,false,receipt,"post",key(),2L);
        assertThat(onlyLine(queries.get(scope,false,receipt))).containsEntry("skuId",Long.toString(otherSku));
        assertThat(inventory.balance(scope,new Key(warehouse,otherSku)).onHand()).isEqualByComparingTo("1");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("0");
        zeroDifferences();
    }

    @Test void draftReceiptRejectsChangedSourceUnitRateUntilEdited() {
        long box=Ids.next();jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'箱')",box,tenant);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,2,0)",Ids.next(),tenant,sku,box);
        long source=order("2","20","0","2");
        jdbc.update("UPDATE purchase_order_line SET unit_id=?,unit_rate=2,qty=1,unit_price=20 WHERE id=?",box,source);
        long receipt=documentId(service.createReceipt(scope,key(),new ReceivingModels.ReceiptInput(supplier,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(source,box,"1")),null)));
        long unchanged=documentId(service.createReceipt(scope,key(),new ReceivingModels.ReceiptInput(supplier,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(source,box,"1")),null)));
        jdbc.update("UPDATE unit_conversion SET rate_to_base=3 WHERE tenant_id=? AND sku_id=? AND unit_id=?",tenant,sku,box);
        service.command(scope,false,unchanged,"submit",key(),0L);
        assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("2");
        service.command(scope,false,unchanged,"void",key(),1L);
        changeSource(source,sku,box,"1","20");
        assertCode(()->service.command(scope,false,receipt,"submit",key(),0L),"SOURCE_CHANGED");
        assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("0");
        service.updateReceipt(scope,receipt,new ReceivingModels.ReceiptInput(supplier,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(source,box,"1")),0L));
        service.command(scope,false,receipt,"submit",key(),1L);service.command(scope,false,receipt,"post",key(),2L);
        assertThat(queries.get(scope,false,receipt)).containsEntry("payableAmount","20.00").containsEntry("costAmount","20.00");
        assertThat(inventory.balance(scope,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("3");
        zeroDifferences();
    }

    @Test void draftReceiptRejectsInconsistentFrozenQuantityWithoutTakingQuota() {
        long source=order("2","20","0","2"),receipt=draft(source,"1",DAY);
        jdbc.update("UPDATE goods_receipt_line SET base_qty=1.5 WHERE receipt_id=?",receipt);
        assertCode(()->service.command(scope,false,receipt,"submit",key(),0L),"SOURCE_CHANGED");
        assertThat(value("SELECT pending_receive_base_qty FROM purchase_order_line WHERE id=?",source)).isEqualByComparingTo("0");
        zeroDifferences();
    }

    private void changeSource(long source,long nextSku,long nextUnit,String qty,String price) {
        long order=jdbc.queryForObject("SELECT order_id FROM purchase_order_line WHERE id=?",Long.class,source);
        var before=purchaseQueries.get(scope,order);
        var input=new PurchaseOrderModels.Input(Long.toString(supplier),DAY.minusDays(5),null,true,"0",
            List.of(new PurchaseOrderModels.Line(Long.toString(source),null,Long.toString(nextSku),Long.toString(warehouse),Long.toString(nextUnit),qty,price,null,null,null,"0")),
            List.of(),null,((Number)before.get("version")).longValue(),"调整采购商品或单位");
        var changed=purchaseOrders.change(scope,order,key(),input);
        if("change_pending".equals(changed.get("status"))) {
            var reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),scope.permissions());
            long change=Long.parseLong(((Map<?,?>)changed.get("change")).get("id").toString());
            purchaseOrders.decideChange(reviewer,change,"approve",key(),new PurchaseOrderModels.Command(0L,null,null));
        }
    }

    private long order(String qty,String amount,String tax,String limit) {
        long id=Ids.next(),line=Ids.next();BigDecimal gross=d(amount),vat=d(tax),quantity=d(qty);LocalDate date=DAY.minusDays(5);
        jdbc.update("""
            INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,goods_amount,total_amount,untaxed_amount,tax_amount,status,revision,approved_at)
            VALUES(?,?,?,?,?,?,1,?,?,?,?,'approved',1,UTC_TIMESTAMP(3))
            """,id,tenant,company,"PO-"+(++number),supplier,date,gross,gross,gross.subtract(vat),vat);
        jdbc.update("""
            INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,receive_limit_base_qty,
            unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount)
            VALUES(?,?,?,?,1,?,?,?,1,?,?,?,?,?,?,?,?,?,?)
            """,line,tenant,company,id,sku,warehouse,unit,quantity,quantity,d(limit),gross.divide(quantity,8,java.math.RoundingMode.HALF_UP),gross,gross,gross,vat.signum()==0?BigDecimal.ZERO:d("0.13"),gross.subtract(vat),vat);
        return line;
    }
    private long draft(long source,String qty,LocalDate day) {return documentId(service.createReceipt(scope,key(),new ReceivingModels.ReceiptInput(supplier,warehouse,day,null,List.of(new ReceivingModels.ReceiptLine(source,unit,qty)),null)));}
    private long receive(long source,String qty,LocalDate day) {long id=draft(source,qty,day);service.command(scope,false,id,"submit",key(),0L);service.command(scope,false,id,"post",key(),1L);return id;}
    private long returnDraft(long source,String qty) {return documentId(service.createReturn(scope,key(),new ReceivingModels.ReturnInput(supplier,warehouse,DAY,"采购退回",List.of(new ReceivingModels.ReturnLine(source,unit,qty)),null)));}
    private long returned(long source,String qty) {long id=returnDraft(source,qty);service.command(scope,true,id,"submit",key(),0L);service.command(scope,true,id,"post",key(),1L);return id;}
    private long receiptLine(long receipt) {return jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);}
    private long issue(String qty,LocalDate day) {
        long source=Ids.next();var result=inventory.issue(scope,new Posting(new Key(warehouse,sku),new Source("test_sale",source,source),day,period,"sales_out"),d(qty),null);
        ledger.post(scope,new LedgerService.Posting("test_sale",source,0,period,day,List.of(
            new LedgerService.Line("COGS","D",result.movementCost(),null,null,null,null,source,"测试销售出库"),
            new LedgerService.Line("INVENTORY","C",result.movementCost(),null,null,warehouse,sku,source,"测试销售出库"))));return source;
    }
    private long compete(ExecutorService pool,boolean returning,long first,long second) throws Exception {
        var start=new CountDownLatch(1);var a=pool.submit(()->attempt(start,returning,first));var b=pool.submit(()->attempt(start,returning,second));start.countDown();
        long left=a.get(15,TimeUnit.SECONDS),right=b.get(15,TimeUnit.SECONDS);assertThat((left>0?1:0)+(right>0?1:0)).isEqualTo(1);return left>0?left:right;
    }
    private long attempt(CountDownLatch start,boolean returning,long id) throws InterruptedException {
        start.await();try {service.command(scope,returning,id,"submit",key(),0L);return id;}
        catch(ApiException error) {if(error.getErrorCode().equals(returning?"RETURN_QTY_EXCEEDED":"RECEIVE_QTY_EXCEEDED")) {return 0;}throw error;}
    }
    private long version(boolean returning,long id) {return ((Number)queries.get(scope,returning,id).get("version")).longValue();}
    private static long documentId(Map<String,Object> value) {return Long.parseLong(value.get("id").toString());}
    @SuppressWarnings("unchecked") private Map<String,Object> onlyLine(Map<String,Object> value) {return ((List<Map<String,Object>>)value.get("lines")).getFirst();}
    private static BigDecimal d(String value) {return new BigDecimal(value);}
    private String key() {return UUID.randomUUID().toString();}
    private BigDecimal value(String sql,Object... args) {return jdbc.queryForObject(sql,BigDecimal.class,args);}
    private void assertCode(Runnable work,String code) {assertThatThrownBy(work::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(code));}
    private void zeroDifferences() {for(String type:ReconciliationChecks.TYPES) {assertThat(checks.check(scope,type,type.equals("period_close")?period:null)).as(type).isEmpty();}}
}
