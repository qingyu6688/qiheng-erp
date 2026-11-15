package cn.erp.opening;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class OpeningIT extends OpeningTestData {
    @Test void inventoryOpeningUsesCapturedUnitConversionAndInputTotalCostExactlyOnce() {
        var input=new OpeningService.Stock("opening",warehouse,DAY,"库存期初",List.of(
                new OpeningService.StockLine(sku,box,"2","220"),new OpeningService.StockLine(zeroSku,unit,"3","0")),null);
        String creation=key();var draft=service.createStock(maker,creation,input);long id=id(draft);
        assertThat(lines(draft).getFirst()).doesNotContainKeys("inputTotalCost","unitCost","costAmount");
        assertThat(service.createStock(maker,creation,input).get("id")).isEqualTo(draft.get("id"));
        assertThat(lines(service.get(maker,true,id)).getFirst()).containsEntry("unitRate","10.000000").containsEntry("baseQty","20.000000").containsEntry("inputTotalCost","220.00");
        assertThat(count("inventory_movement")).isZero();
        jdbc.update("UPDATE unit_conversion SET rate_to_base=12 WHERE tenant_id=? AND sku_id=? AND unit_id=?",tenant,sku,box);
        var pending=command(maker,true,id,"submit",version(draft));
        assertCode(()->command(maker,true,id,"approve",version(pending)),"SAME_OPERATOR_NOT_ALLOWED");
        var approved=command(reviewer,true,id,"approve",version(pending));
        String posting=key();var request=new OpeningService.Version(version(approved),null);
        var posted=service.command(maker,true,id,"post",posting,request);
        assertThat(service.command(maker,true,id,"post",posting,request).get("id")).isEqualTo(posted.get("id"));
        assertCode(()->command(maker,true,id,"post",version(posted)),"STATE_CONFLICT");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("20");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("220");
        assertThat(inventory.balance(maker,new Key(warehouse,zeroSku)).onHand()).isEqualByComparingTo("3");
        assertThat(inventory.balance(maker,new Key(warehouse,zeroSku)).cost()).isEqualByComparingTo("0");
        assertThat(count("inventory_movement")).isEqualTo(2);
        assertThat(count("journal")).isEqualTo(1);
        var saved=lines(service.get(maker,true,id));
        assertThat(saved.getFirst()).containsEntry("unitRate","10.000000").containsEntry("unitCost","11.00000000").containsEntry("costAmount","220.00");
        assertThat(lines(posted).getFirst()).doesNotContainKeys("inputTotalCost","unitCost","costAmount");
        assertZero();
    }

    @Test void fourPartnerDirectionsAndCashOpeningCreateCorrectFactsAndBalancedJournals() {
        for(String type:List.of("receivable","payable")) {
            for(String direction:List.of("D","C")) {
                var draft=service.createEntry(maker,key(),entry(type,direction,"10",null));long id=id(draft);
                assertThat(draft.get("allowedActions")).asList().doesNotContain("approve");
                assertCode(()->command(maker,false,id,"approve",version(draft)),"SAME_OPERATOR_NOT_ALLOWED");
                var approved=command(reviewer,false,id,"approve",version(draft));
                var posted=command(maker,false,id,"post",version(approved));
                assertThat(posted).containsEntry("status","posted");
                String positive=type.equals("receivable")?"D":"C";
                var fact=jdbc.queryForMap("SELECT kind,amount,tax_amount FROM "+type+" WHERE tenant_id=? AND source_id=?",tenant,id);
                assertThat(fact.get("kind")).isEqualTo(direction.equals(positive)?"charge":"credit");
                assertThat((BigDecimal)fact.get("amount")).isEqualByComparingTo("10");
                assertThat((BigDecimal)fact.get("tax_amount")).isEqualByComparingTo("0");
            }
        }
        var cashDraft=service.createEntry(maker,key(),entry("cash","D","100",null));long id=id(cashDraft);
        assertThat(cash()).isEqualByComparingTo("0");
        var approved=command(reviewer,false,id,"approve",version(cashDraft));
        assertThat(cash()).isEqualByComparingTo("0");
        String key=key();var input=new OpeningService.Version(version(approved),null);
        var first=service.command(maker,false,id,"post",key,input);
        assertThat(service.command(maker,false,id,"post",key,input).get("id")).isEqualTo(first.get("id"));
        assertThat(cash()).isEqualByComparingTo("100");
        assertThat(count("receivable")).isEqualTo(2);assertThat(count("payable")).isEqualTo(2);
        assertThat(count("partner_open_item")).isEqualTo(4);assertThat(count("journal")).isEqualTo(5);
        assertCode(()->service.createEntry(maker,key(),entry("cash","C","1",null)),"VALIDATION_FAILED");
        assertZero();
    }

    @Test void statesVersionsRejectAndApprovedOpeningsBlockPeriodCloseUntilPosted() {
        var draft=service.createStock(maker,key(),stock(warehouse,"2","20",null));long id=id(draft);
        assertCode(()->command(maker,true,id,"post",version(draft)),"STATE_CONFLICT");
        var edited=service.updateStock(maker,id,stock(warehouse,"3","30",version(draft)));
        assertCode(()->service.updateStock(maker,id,stock(warehouse,"4","40",version(draft))),"VERSION_CONFLICT");
        var pending=command(maker,true,id,"submit",version(edited));
        assertCode(()->service.updateStock(maker,id,stock(warehouse,"5","50",version(pending))),"STATE_CONFLICT");
        assertCode(()->command(reviewer,true,id,"reject",version(pending)),"VALIDATION_FAILED");
        var rejected=service.command(reviewer,true,id,"reject",key(),new OpeningService.Version(version(pending),"请核对数量"));
        assertThat(rejected).containsEntry("status","draft");
        var resubmitted=command(maker,true,id,"submit",version(rejected));
        var approved=command(reviewer,true,id,"approve",version(resubmitted));
        assertThat(checks.check(maker,"period_close",period)).extracting(d->d.key()).contains("stock_adjustment");
        command(maker,true,id,"post",version(approved));
        var entry=service.createEntry(maker,key(),entry("receivable","D","10",null));long entryId=id(entry);
        var entryApproved=command(reviewer,false,entryId,"approve",version(entry));
        assertThat(checks.check(maker,"period_close",period)).extracting(d->d.key()).contains("opening_entry");
        command(maker,false,entryId,"post",version(entryApproved));assertZero();
    }

    @Test void warehouseCompanyAndCostRevocationAlsoProtectHistoricalCommandResults() {
        String key=key();var input=stock(warehouse,"1","123.45",null);
        var draft=service.createStock(maker,key,input);long id=id(draft);
        assertThat(lines(service.get(maker,true,id)).getFirst()).containsEntry("inputTotalCost","123.45");
        var permissions=new HashSet<>(maker.permissions());permissions.remove("cost:view");
        var hidden=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);
        assertThat(lines(service.get(hidden,true,id)).getFirst()).doesNotContainKeys("inputTotalCost","unitCost","costAmount");
        assertThat(results.get(hidden,"stock_adjustment.create",key).get("status")).isEqualTo("succeeded");
        var limited=new CompanyAccess(tenant,user,company,false,Set.of(otherWarehouse),maker.permissions());
        assertCode(()->service.get(limited,true,id),"NOT_FOUND");
        assertCode(()->results.get(limited,"stock_adjustment.create",key),"NOT_FOUND");
        assertCode(()->service.createStock(limited,key,input),"NOT_FOUND");
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)service.list(limited,true,null,null,20).get("items");
        assertThat(items).isEmpty();
        var outside=new CompanyAccess(tenant,user,otherCompany,true,Set.of(),maker.permissions());
        assertCode(()->service.get(outside,true,id),"NOT_FOUND");
        assertCode(()->service.createStock(maker,key,stock(warehouse,"2","123.45",null)),"IDEMPOTENCY_KEY_REUSED");
        assertZero();
    }

    @Test void closedPeriodRollsBackStockAndCashOpeningWithoutPartialFacts() {
        var draft=service.createStock(maker,key(),stock(warehouse,"2","20",null));long id=id(draft);
        var pending=command(maker,true,id,"submit",version(draft));var approved=command(reviewer,true,id,"approve",version(pending));
        var cashDraft=service.createEntry(maker,key(),entry("cash","D","100",null));long cashId=id(cashDraft);
        var cashApproved=command(reviewer,false,cashId,"approve",version(cashDraft));
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->command(maker,true,id,"post",version(approved)),"PERIOD_CLOSED");
        assertCode(()->command(maker,false,cashId,"post",version(cashApproved)),"PERIOD_CLOSED");
        assertThat(service.get(maker,true,id)).containsEntry("status","approved");
        assertThat(service.get(maker,false,cashId)).containsEntry("status","approved");
        assertThat(count("inventory_movement")).isZero();assertThat(count("inventory_balance")).isZero();
        assertThat(count("journal")).isZero();assertThat(cash()).isEqualByComparingTo("0");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        command(maker,true,id,"post",version(approved));command(maker,false,cashId,"post",version(cashApproved));assertZero();
    }

    @Test void concurrentSameKeyPostingForTwentyRoundsHasOneInventoryAndJournalFact() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int round=0;round<20;round++) {
                var draft=service.createStock(maker,key(),stock(warehouse,"1","1",null));long id=id(draft);
                var pending=command(maker,true,id,"submit",version(draft));var approved=command(reviewer,true,id,"approve",version(pending));
                String key=key();var input=new OpeningService.Version(version(approved),null);var start=new CountDownLatch(1);
                Callable<Map<String,Object>> task=()->{start.await();return service.command(maker,true,id,"post",key,input);};
                var first=pool.submit(task);var second=pool.submit(task);start.countDown();
                assertThat(first.get(10,TimeUnit.SECONDS).get("id")).isEqualTo(second.get(10,TimeUnit.SECONDS).get("id"));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND source_id=?",Long.class,tenant,id)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='stock_adjustment' AND source_id=?",Long.class,tenant,id)).isEqualTo(1);
            }
        }
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("20");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("20");
        assertZero();
    }

    @Test void subMicroUnitConversionIsRejectedWithoutChangingDraftOrCreatingFacts() {
        jdbc.update("UPDATE unit_conversion SET rate_to_base=0.5 WHERE tenant_id=? AND sku_id=? AND unit_id=?",tenant,sku,box);
        var invalid=new OpeningService.Stock("opening",warehouse,DAY,"精度校验",List.of(new OpeningService.StockLine(sku,box,"0.000001","1")),null);
        assertCode(()->service.createStock(maker,key(),invalid),"VALIDATION_FAILED");
        assertThat(count("stock_adjustment")).isZero();assertThat(count("stock_adjustment_line")).isZero();
        var draft=service.createStock(maker,key(),new OpeningService.Stock("opening",warehouse,DAY,"原始草稿",List.of(new OpeningService.StockLine(sku,box,"1","1")),null));long id=id(draft);
        var edit=new OpeningService.Stock("opening",warehouse,DAY,"精度校验",invalid.lines(),version(draft));
        assertCode(()->service.updateStock(maker,id,edit),"VALIDATION_FAILED");
        var restored=service.get(maker,true,id);
        assertThat(version(restored)).isEqualTo(version(draft));assertThat(restored).containsEntry("reason","原始草稿");
        assertThat(lines(restored).getFirst()).containsEntry("qty","1.000000").containsEntry("baseQty","0.500000");
        assertThat(count("inventory_movement")).isZero();assertThat(count("journal")).isZero();assertZero();
    }
}
