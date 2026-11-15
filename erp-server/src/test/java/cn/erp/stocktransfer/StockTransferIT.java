package cn.erp.stocktransfer;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class StockTransferIT extends StockTransferTestData {
    @Test void draftEditSubmitAndVoidNeverChangeStockAndB20RejectsIllegalTransitions(){
        long id=draft("1");service.update(maker,id,input("2",0L));
        assertThat(balance(from).onHand()).isEqualByComparingTo("3");assertThat(count("stock_transfer_event_line")).isZero();
        code(()->service.command(maker,id,"ship",key(),new StockTransferModels.Version(1L,null)),"STATE_CONFLICT");
        code(()->service.update(maker,id,input("1",0L)),"VERSION_CONFLICT");command(id,"submit");command(id,"void");
        code(()->command(id,"ship"),"STATE_CONFLICT");assertThat(balance(from).onHand()).isEqualByComparingTo("3");zero();
    }
    @Test void T12RebuiltServiceReadsPersistedTransitAndThreeReceiptsExhaustExactCost(){
        long id=shipped("3");assertThat(balance(from).onHand()).isEqualByComparingTo("0");assertThat(balance(to).onHand()).isEqualByComparingTo("0");
        var restarted=new StockTransferService(jdbc,inventory,costs,ledger,finance,catalog,pricing,commands,numbers,audit);
        assertThat(restarted.get(maker,id)).containsEntry("status","shipped");assertThat(rows(restarted.get(maker,id),"events")).hasSize(1);
        restarted.receive(maker,id,key(),new StockTransferModels.Receive(version(id),null,false,List.of(new StockTransferModels.ReceivedLine(line(id),"1"))));
        assertThat(service.get(maker,id)).containsEntry("status","shipped");assertThat(balance(to).cost()).isEqualByComparingTo("0.33");
        receive(id,"1",false);assertThat(balance(to).cost()).isEqualByComparingTo("0.67");receive(id,"1",true);
        var actual=service.get(maker,id);assertThat(actual).containsEntry("status","received");assertThat(balance(to).onHand()).isEqualByComparingTo("3");assertThat(balance(to).cost()).isEqualByComparingTo("1");
        assertThat(rows(actual,"events")).extracting(e->e.get("eventType")).containsExactly("ship","receive","receive","receive");
        assertThat(rows(actual,"lines").getFirst()).containsEntry("receivedCostAmount","1.00").containsEntry("remainingQty","0.000000");zero();
    }
    @Test void shortReceiptCanReturnUnreceivedGoodsWithoutCreatingTargetStock(){
        long id=shipped("3");receive(id,"1",true);assertThat(service.get(maker,id)).containsEntry("status","received_with_diff");
        assertThat(balance(to).onHand()).isEqualByComparingTo("1");assertThat(rows(service.get(maker,id),"lines").getFirst()).containsEntry("diffBaseQty","2.000000");
        resolve(id,"returned");assertThat(balance(from).onHand()).isEqualByComparingTo("2");assertThat(balance(from).cost()).isEqualByComparingTo("0.67");assertThat(service.get(maker,id)).containsEntry("status","received");zero();
    }
    @Test void allShortageCanBeResolvedAsTransportLossWithExactTransitCost(){
        long id=shipped("3");receive(id,"0",true);resolve(id,"lost");
        assertThat(balance(from).onHand()).isEqualByComparingTo("0");assertThat(balance(to).onHand()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM journal_line WHERE tenant_id=? AND account_code='INVENTORY_ADJUSTMENT' AND direction='D'",BigDecimal.class,tenant)).isEqualByComparingTo("1");
        assertThat(rows(service.get(maker,id),"lines").getFirst()).containsEntry("diffStatus","resolved").containsEntry("diffCostAmount","1.00");zero();
    }
    @Test void receiveLimitAndDuplicateCommandsAreAtomicAndIdempotent(){
        long id=shipped("3"),line=line(id);long version=version(id);String key=key();var input=new StockTransferModels.Receive(version,null,false,List.of(new StockTransferModels.ReceivedLine(line,"1")));
        var result=service.receive(maker,id,key,input);var replay=service.receive(maker,id,key,input);
        var json=new tools.jackson.databind.ObjectMapper();assertThat(json.readTree(json.writeValueAsString(replay))).isEqualTo(json.readTree(json.writeValueAsString(result)));
        assertThat(balance(to).onHand()).isEqualByComparingTo("1");assertThat(count("stock_transfer_event_line")).isEqualTo(2);
        code(()->service.receive(maker,id,key(),new StockTransferModels.Receive(version(id),null,true,List.of(new StockTransferModels.ReceivedLine(line,"3")))),"RECEIVE_QTY_EXCEEDED");
        assertThat(balance(to).onHand()).isEqualByComparingTo("1");assertThat(version(id)).isEqualTo(version+1);receive(id,"2",true);zero();
    }
    @Test void bothWarehousesCompanyAndCachedCostsRespectCurrentScope(){
        String key=key();var input=input("2",null);var created=service.create(maker,key,input);long id=id(created);
        assertThat(rows(created,"lines").getFirst()).doesNotContainKeys("unitCost","costAmount","receivedCostAmount","diffCostAmount");
        var half=new CompanyAccess(tenant,user,company,false,Set.of(from),maker.permissions());code(()->service.get(half,id),"NOT_FOUND");code(()->service.create(half,key,input),"NOT_FOUND");
        var other=new CompanyAccess(tenant,user,otherCompany,true,Set.of(),maker.permissions());code(()->service.get(other,id),"NOT_FOUND");
        code(()->service.create(maker,key(),new StockTransferModels.Input(from,outside,DAY,null,input.lines(),null)),"NOT_FOUND");
        var permissions=new HashSet<>(maker.permissions());permissions.remove("cost:view");var noCost=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);
        command(id,"submit");command(id,"ship");assertThat(rows(service.get(maker,id),"lines").getFirst()).containsKey("costAmount");
        assertThat(rows(service.get(noCost,id),"lines").getFirst()).doesNotContainKeys("unitCost","costAmount","receivedCostAmount","diffCostAmount");assertThat(rows(service.get(noCost,id),"events").getFirst()).doesNotContainKey("costAmount");receive(id,"2",true);zero();
    }
    @Test void strictUnitConversionRejectsTinyQuantityAndPreservesDraftOnFailedEdit(){
        long small=Ids.next();jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'十分件')",small,tenant);jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,0.1,0)",Ids.next(),tenant,sku,small);
        long id=draft("1");var input=new StockTransferModels.Input(from,to,DAY,null,List.of(new StockTransferModels.Line(sku,small,"0.000001")),0L);
        assertThatThrownBy(()->service.update(maker,id,input)).isInstanceOf(ApiException.class);assertThat(rows(service.get(maker,id),"lines").getFirst()).containsEntry("qty","1.000000");assertThat(version(id)).isZero();
        assertThatThrownBy(()->service.create(maker,key(),input)).isInstanceOf(ApiException.class);assertThat(count("stock_transfer")).isEqualTo(1);command(id,"void");zero();
    }
    @Test void closedPeriodAndInsufficientStockLeaveNoShipFacts(){
        long id=draft("4");command(id,"submit");code(()->command(id,"ship"),"INVENTORY_INSUFFICIENT");assertThat(count("stock_transfer_event_line")).isZero();
        assertThat(balance(from).onHand()).isEqualByComparingTo("3");command(id,"void");long second=draft("1");command(second,"submit");
        jdbc.update("UPDATE accounting_period SET status='closed',closed_by=?,closed_at=UTC_TIMESTAMP(3) WHERE id=?",user,period);code(()->command(second,"ship"),"PERIOD_CLOSED");
        assertThat(count("stock_transfer_event_line")).isZero();jdbc.update("UPDATE accounting_period SET status='open',closed_by=NULL,closed_at=NULL WHERE id=?",period);command(second,"void");zero();
    }
    @Test void twentyConcurrentShipsDoNotOverdrawSharedWarehouse() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)){
            for(int round=0;round<20;round++){
                long first=draft("2"),second=draft("2");command(first,"submit");command(second,"submit");var gate=new CountDownLatch(1);
                var a=pool.submit(()->shipAfter(gate,first));var b=pool.submit(()->shipAfter(gate,second));gate.countDown();boolean one=a.get(10,TimeUnit.SECONDS),two=b.get(10,TimeUnit.SECONDS);assertThat(one^two).isTrue();
                long winner=one?first:second,loser=one?second:first;command(loser,"void");receive(winner,"0",true);resolve(winner,"returned");assertThat(balance(from).onHand()).isEqualByComparingTo("3");
            }
        }zero();
    }
    @Test void zeroCostTransferStillCreatesQuantityFactsWithoutUnbalancedZeroJournals(){
        long zeroSku=Ids.next();long product=jdbc.queryForObject("SELECT product_id FROM sku WHERE id=?",Long.class,sku);jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'ZERO','零成本商品')",zeroSku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,zeroSku,unit);long opening=Ids.next();inventory.receive(maker,new cn.erp.inventory.InventoryModels.Posting(new cn.erp.inventory.InventoryModels.Key(from,zeroSku),new cn.erp.inventory.InventoryModels.Source("opening",opening,opening),DAY,period,"opening_in"),d("2"),d("0"));
        long id=id(service.create(maker,key(),new StockTransferModels.Input(from,to,DAY,null,List.of(new StockTransferModels.Line(zeroSku,unit,"2")),null)));command(id,"submit");command(id,"ship");receive(id,"1",false);receive(id,"1",true);
        assertThat(service.get(maker,id)).containsEntry("status","received");assertThat(rows(service.get(maker,id),"lines").getFirst()).containsEntry("costAmount","0.00").containsEntry("receivedCostAmount","0.00");assertThat(count("journal")).isEqualTo(1);zero();
    }
    @Test void submissionCannotOverrideClosedDocumentPeriodAndDisabledWarehouseCanStillVoid(){
        long october=Ids.next();jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date,status,closed_by,closed_at) VALUES(?,?,?,'202610','2026-10-01','2026-10-31','closed',?,UTC_TIMESTAMP(3))",october,tenant,company,user);
        var oldInput=new StockTransferModels.Input(from,to,DAY.minusMonths(1),null,List.of(new StockTransferModels.Line(sku,unit,"1")),null);long id=id(service.create(maker,key(),oldInput));
        code(()->service.command(maker,id,"submit",key(),new StockTransferModels.Version(0L,DAY)),"PERIOD_CLOSED");assertThat(service.get(maker,id)).containsEntry("status","draft");jdbc.update("UPDATE warehouse SET status='disabled' WHERE id=?",from);command(id,"void");assertThat(service.get(maker,id)).containsEntry("status","voided");zero();
    }
    private boolean shipAfter(CountDownLatch gate,long id) throws InterruptedException {gate.await();try{service.command(maker,id,"ship",key(),new StockTransferModels.Version(1L,null));return true;}catch(ApiException error){assertThat(error.getErrorCode()).isEqualTo("INVENTORY_INSUFFICIENT");return false;}}
}
