package cn.erp.opening;

import cn.erp.ErpApplication;
import cn.erp.inventory.InventoryModels.Key;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class StockAdjustmentIT extends OpeningTestData {
    @Test void gainLossFreezeUnfreezeAndScrapKeepInventoryCostAndLedgerConsistent() {
        post("opening","10","100");
        var blocked=post("block","4",null);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).available()).isEqualByComparingTo("6");
        assertThat(lines(service.get(maker,true,id(blocked))).getFirst()).containsEntry("costAmount","0.00");
        var loss=post("loss","3",null);
        assertThat(lines(service.get(maker,true,id(loss))).getFirst()).containsEntry("unitCost","10.00000000").containsEntry("costAmount","30.00");
        post("unblock","1",null);var scrapped=post("scrap","2",null);
        assertThat(lines(service.get(maker,true,id(scrapped))).getFirst()).containsEntry("costAmount","20.00");
        post("gain","2","30");var balance=inventory.balance(maker,new Key(warehouse,sku));
        assertThat(balance.onHand()).isEqualByComparingTo("7");assertThat(balance.blocked()).isEqualByComparingTo("1");
        assertThat(balance.available()).isEqualByComparingTo("6");assertThat(balance.cost()).isEqualByComparingTo("80");
        assertThat(count("inventory_movement")).isEqualTo(6);assertThat(count("journal")).isEqualTo(4);
        assertThat(service.list(maker,true,null,"gain",null,20).get("items")).asList().hasSize(1);assertZero();
    }

    @Test void insufficientAvailableFrozenAndUnfreezeQuantitiesFailAtomically() {
        post("opening","5","50");post("block","3",null);
        for(String type:List.of("loss","block","unblock","scrap")) {
            String qty=type.equals("loss")||type.equals("block")?"3":"4";
            var approved=approve(type,qty,null);long id=id(approved);
            assertCode(()->command(maker,true,id,"post",version(approved)),"INVENTORY_INSUFFICIENT");
            assertThat(service.get(maker,true,id)).containsEntry("status","approved");
            assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("5");
            assertThat(inventory.balance(maker,new Key(warehouse,sku)).blocked()).isEqualByComparingTo("3");
            command(maker,true,id,"void",version(approved));
        }
        assertThat(count("inventory_movement")).isEqualTo(2);assertThat(count("journal")).isEqualTo(1);assertZero();
    }

    @Test void outboundFailureInSecondLineRollsBackFirstLineInventoryAndJournal() {
        var opening=service.createStock(maker,key(),new OpeningService.Stock("opening",warehouse,DAY,"两行期初",List.of(
            new OpeningService.StockLine(sku,unit,"10","100"),new OpeningService.StockLine(zeroSku,unit,"1","20")),null));
        var pending=command(maker,true,id(opening),"submit",version(opening));var approved=command(reviewer,true,id(opening),"approve",version(pending));
        command(maker,true,id(opening),"post",version(approved));
        var draft=service.createStock(maker,key(),new OpeningService.Stock("loss",warehouse,DAY,"两行出库",List.of(
            new OpeningService.StockLine(sku,unit,"2",null),new OpeningService.StockLine(zeroSku,unit,"2",null)),null));
        var submitted=command(maker,true,id(draft),"submit",version(draft));var ready=command(reviewer,true,id(draft),"approve",version(submitted));
        assertCode(()->command(maker,true,id(draft),"post",version(ready)),"INVENTORY_INSUFFICIENT");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("10");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("100");
        assertThat(inventory.balance(maker,new Key(warehouse,zeroSku)).onHand()).isEqualByComparingTo("1");
        assertThat(count("inventory_movement")).isEqualTo(2);assertThat(count("journal")).isEqualTo(1);
        command(maker,true,id(draft),"void",version(ready));assertZero();
    }

    @Test void adjustmentTypeIsImmutableAndOnlyInboundAcceptsInputCost() {
        var draft=service.createStock(maker,key(),input("gain","2","12",null));long id=id(draft);
        assertCode(()->service.updateStock(maker,id,input("opening","2","12",version(draft))),"VALIDATION_FAILED");
        assertThat(service.get(maker,true,id)).containsEntry("adjustType","gain");
        assertThat(lines(service.get(maker,true,id)).getFirst()).containsEntry("inputTotalCost","12.00");
        for(String type:List.of("loss","block","unblock","scrap"))assertCode(()->service.createStock(maker,key(),input(type,"1","1",null)),"VALIDATION_FAILED");
        assertCode(()->service.createStock(maker,key(),input("gain","1",null,null)),"VALIDATION_FAILED");
        assertCode(()->service.createStock(maker,key(),input("unknown","1","1",null)),"VALIDATION_FAILED");
        assertThat(count("stock_adjustment")).isEqualTo(1);assertThat(count("inventory_movement")).isZero();assertZero();
    }

    private OpeningService.Stock input(String type,String qty,String cost,Long version){return new OpeningService.Stock(type,warehouse,DAY,"库存调整验收",List.of(new OpeningService.StockLine(sku,unit,qty,cost)),version);}
    private Map<String,Object> approve(String type,String qty,String cost){var draft=service.createStock(maker,key(),input(type,qty,cost,null));var submitted=command(maker,true,id(draft),"submit",version(draft));return command(reviewer,true,id(draft),"approve",version(submitted));}
    private Map<String,Object> post(String type,String qty,String cost){var ready=approve(type,qty,cost);return command(maker,true,id(ready),"post",version(ready));}
}
