package cn.erp.purchasing;

import cn.erp.shared.OrderCreationRaceData;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static cn.erp.purchasing.PurchaseOrderModels.*;

class PurchaseOrderCreationConcurrencyIT extends OrderCreationRaceData {
    @Autowired PurchaseOrderWriter writer;
    @Test void newPurchaseOrdersWithAndWithoutFeesCommitWithoutEmptyRangeLocks() throws Exception {
        for(boolean fees:List.of(false,true))for(int round=0;round<2;round++) {
            var inputs=List.of(input(0,fees),input(1,fees));
            var previews=List.of(writer.price(actors[0],inputs.get(0)),writer.price(actors[1],inputs.get(1)));
            raceNewOrders("purchase_order",fees,(index,order)->writer.writeNew(actors[index],order,inputs.get(index),previews.get(index)));
        }
    }
    private Input input(int index,boolean fees) {
        var lines=new ArrayList<Line>();
        for(int number=0;number<LINE_COUNT;number++)lines.add(new Line(null,null,id(sku),id(warehouses[index]),id(unit),"1","10",null,null,null,"0"));
        return new Input(id(partner),DAY,null,true,"0",lines,fees?List.of(new Fee(null,"freight","buyer","2","0")):List.of(),null,null,null);
    }
}
