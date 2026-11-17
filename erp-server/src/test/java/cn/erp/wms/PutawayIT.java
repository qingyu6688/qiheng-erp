package cn.erp.wms;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class PutawayIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReceivingService receiving;
    @Autowired PutawayTasks tasks;
    @Autowired InventoryService inventory;
    @Autowired ReconciliationChecks checks;
    @Autowired ReversalService reversals;
    private final LocalDate day = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private long tenant,company,warehouse,otherWarehouse,department,sku,unit,supplier,receivingBin,storageBin;
    private CompanyAccess maker;

    @BeforeTransaction void seedCompany() {
        tenant = Ids.next();
        company = Ids.next();
        // 单号在独立事务中分配，租户和公司需要在测试事务开始前提交。
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'上架验收')",tenant,"PA"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','上架验收公司')",company,tenant);
    }

    @AfterTransaction void cleanCompany() {
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=? AND company_id=?",tenant,company);
        jdbc.update("DELETE FROM company WHERE tenant_id=? AND id=?",tenant,company);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @BeforeEach void seed() {
        warehouse = Ids.next();
        otherWarehouse = Ids.next();
        department = Ids.next();
        sku = Ids.next();
        unit = Ids.next();
        supplier = Ids.next();
        long category = Ids.next(), product = Ids.next();
        maker = new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),
                Set.of("wms_task:view","wms_task:execute","wms_task:configure",
                        "goods_receipt:view","reversal:view","reversal:create","reversal:approve","reversal:post"),
                new OrgScope(List.of(),department));
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','收货仓'),(?,?,?,'W2','另一仓')",
                warehouse,tenant,company,otherWarehouse,tenant,company);
        receivingBin = location(warehouse,"R01","receiving",null,0);
        storageBin = location(warehouse,"S01","storage",new BigDecimal("4"),20);
        location(warehouse,"FULL","storage",BigDecimal.ONE,1);
        location(otherWarehouse,"OTHER","storage",null,1);
        jdbc.update("UPDATE warehouse SET location_managed=1,default_receiving_location_id=?,default_shipping_location_id=? WHERE id=?",
                receivingBin,storageBin,warehouse);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'SUP','上架供应商')",supplier,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'supplier')",Ids.next(),tenant,supplier);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("""
                INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate,batch_managed,serial_managed,issue_strategy)
                VALUES(?,?,?,'SKU','批次序列商品',0,1,1,'fefo')
                """,sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",
                Ids.next(),tenant,sku,unit);
        var month = YearMonth.from(day);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                Ids.next(),tenant,company,month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
    }

    @Test void receivesAndConfirmsTwoPutawayScansWithoutChangingWarehouseTotals() {
        tasks.mode(maker,warehouse,new PutawayTasks.Mode("optional"));
        long receipt = receive(List.of("S-01","S-02","S-03"));
        var work = items(tasks.page(maker,warehouse,"created",null,20));
        assertThat(work).singleElement().satisfies(task -> assertThat(task).containsEntry("sourceType","goods_receipt")
                .containsEntry("sourceId",Long.toString(receipt)));
        long task = id(work.getFirst());
        var assignedOperator = new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),maker.permissions(),
                OrgScope.restricted(Set.of(department),Set.of(),department));
        var line = items(tasks.detail(assignedOperator,task),"lines").getFirst();
        assertThat(line).containsEntry("batchNo","PA-BATCH").containsEntry("fromLocationCode","R01")
                .containsEntry("recommendedLocationCode","S01").containsEntry("planQty","3.000000").containsEntry("doneQty","0.000000");
        long lineId = id(line);
        checkScope(task,lineId);
        tasks.claim(assignedOperator,task,key(),new PutawayTasks.Revision(0L));
        reject("STATE_CONFLICT",() -> tasks.finish(assignedOperator,task,key(),new PutawayTasks.Revision(1L)));
        reject("VALIDATION_FAILED",() -> tasks.confirm(assignedOperator,task,lineId,key(),
                new PutawayTasks.Confirmation("L:WRONG","L:S01","B:SKU|PA-BATCH","1",List.of("S-01"),1L)));
        reject("VALIDATION_FAILED",() -> tasks.confirm(assignedOperator,task,lineId,key(),
                new PutawayTasks.Confirmation("L:R01","L:OTHER","B:SKU|PA-BATCH","1",List.of("S-01"),1L)));
        reject("VALIDATION_FAILED",() -> tasks.confirm(assignedOperator,task,lineId,key(),
                new PutawayTasks.Confirmation("L:R01","L:S01","B:WRONG|PA-BATCH","1",List.of("S-01"),1L)));
        assertThat(locationQuantity(receivingBin)).isEqualByComparingTo("3");
        var snapshot = inventory.balance(maker,new Key(warehouse,sku));

        var first = new PutawayTasks.Confirmation("L:R01","L:S01","B:SKU|PA-BATCH","1",List.of("S-01"),1L);
        String confirmKey = key();
        tasks.confirm(assignedOperator,task,lineId,confirmKey,first);
        tasks.confirm(assignedOperator,task,lineId,confirmKey,first);
        assertThat(locationQuantity(receivingBin)).isEqualByComparingTo("2");
        assertThat(locationQuantity(storageBin)).isEqualByComparingTo("1");
        tasks.confirm(assignedOperator,task,lineId,key(),
                new PutawayTasks.Confirmation("L:R01","L:S01","PA-BATCH","2",List.of("S-02","S-03"),2L));
        tasks.finish(assignedOperator,task,key(),new PutawayTasks.Revision(3L));
        assertThat(tasks.detail(maker,task)).containsEntry("status","done");
        assertThat(locationQuantity(receivingBin)).isZero();
        assertThat(locationQuantity(storageBin)).isEqualByComparingTo("3");
        assertThat(inventory.balance(maker,new Key(warehouse,sku))).isEqualTo(snapshot);
        assertThat(snapshot.cost()).isEqualByComparingTo("30.00");
        assertThat(jdbc.queryForList("""
                SELECT serial_no FROM inv_serial WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=?
                    AND location_key=? AND status='in_stock' ORDER BY serial_no
                """,String.class,tenant,company,warehouse,sku,Long.toString(storageBin))).containsExactly("S-01","S-02","S-03");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM inventory_movement WHERE tenant_id=? AND company_id=? AND warehouse_id=?
                    AND source_type='wms_task_confirmation' AND source_id=? AND cost_delta=0
                """,Long.class,tenant,company,warehouse,task)).isEqualTo(4L);
        assertThat(checks.check(maker,"location_balance",null)).isEmpty();
        assertThat(checks.check(maker,"inventory_cost",null)).isEmpty();
    }

    @Test void defaultOffStillPostsTheReceiptWithoutCreatingATask() {
        assertThat(tasks.warehouses(maker)).anySatisfy(row -> assertThat(row).containsEntry("id",Long.toString(warehouse))
                .containsEntry("taskMode","off"));
        receive(List.of("S-01"));
        assertThat(items(tasks.page(maker,warehouse,null,null,20))).isEmpty();
        assertThat(locationQuantity(receivingBin)).isEqualByComparingTo("1");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isEqualByComparingTo("10.00");
    }

    @Test void reversingUntouchedReceiptCancelsItsClaimedPutaway() {
        tasks.mode(maker,warehouse,new PutawayTasks.Mode("optional"));
        long receipt = receive(List.of("S-01"));
        long task = id(items(tasks.page(maker,warehouse,"created",null,20)).getFirst());
        tasks.claim(maker,task,key(),new PutawayTasks.Revision(0L));

        long reversal = approveReversal(receipt);
        var posted = reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null));
        assertThat(posted).containsEntry("status","posted");
        assertThat(tasks.detail(maker,task)).containsEntry("status","cancelled");
        assertThat(receiptStatus(receipt)).isEqualTo("reversed");
        assertThat(locationQuantity(receivingBin)).isZero();
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).cost()).isZero();
        assertThat(checks.check(maker,"location_balance",null)).isEmpty();
    }

    @Test void receiptMovedAfterApprovalCannotBeReversed() {
        tasks.mode(maker,warehouse,new PutawayTasks.Mode("optional"));
        long receipt = receive(List.of("S-01","S-02"));
        long task = id(items(tasks.page(maker,warehouse,"created",null,20)).getFirst());
        long line = id(items(tasks.detail(maker,task),"lines").getFirst());
        long reversal = approveReversal(receipt);
        tasks.claim(maker,task,key(),new PutawayTasks.Revision(0L));
        tasks.confirm(maker,task,line,key(),
                new PutawayTasks.Confirmation("R01","S01","PA-BATCH","1",List.of("S-01"),1L));

        reject("WMS_PUTAWAY_ALREADY_MOVED",() -> reversals.command(maker,reversal,"post",key(),new ReversalModels.Version(1L,null)));
        reject("WMS_PUTAWAY_ALREADY_MOVED",() -> reversals.create(maker,key(),reversalInput(receipt)));
        assertThat(reversals.get(maker,reversal)).containsEntry("status","approved");
        assertThat(receiptStatus(receipt)).isEqualTo("posted");
        assertThat(tasks.detail(maker,task)).containsEntry("status","in_progress");
        assertThat(locationQuantity(receivingBin)).isEqualByComparingTo("1");
        assertThat(locationQuantity(storageBin)).isEqualByComparingTo("1");
    }

    private long approveReversal(long receipt) {
        long reversal = id(reversals.create(maker,key(),reversalInput(receipt)));
        var reviewer = new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),maker.permissions());
        assertThat(reversals.command(reviewer,reversal,"approve",key(),new ReversalModels.Version(0L,null)))
                .containsEntry("status","approved");
        return reversal;
    }

    private ReversalModels.Input reversalInput(long receipt) {
        return new ReversalModels.Input("goods_receipt",receipt,day,"验收入库纠错");
    }

    private String receiptStatus(long receipt) {
        return jdbc.queryForObject("SELECT status FROM goods_receipt WHERE tenant_id=? AND company_id=? AND id=?",
                String.class,tenant,company,receipt);
    }

    private void checkScope(long task,long line) {
        var outsideDepartment = new CompanyAccess(tenant,Ids.next(),company,false,Set.of(warehouse),maker.permissions(),
                OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        var outsideWarehouse = new CompanyAccess(tenant,maker.userId(),company,false,Set.of(otherWarehouse),maker.permissions());
        var outsideCompany = new CompanyAccess(tenant,maker.userId(),Ids.next(),true,Set.of(),maker.permissions());
        var outsideTenant = new CompanyAccess(Ids.next(),maker.userId(),company,true,Set.of(),maker.permissions());
        for (var outside : List.of(outsideDepartment,outsideWarehouse,outsideCompany,outsideTenant)) {
            assertThat(items(tasks.page(outside,null,null,null,20))).isEmpty();
            reject("NOT_FOUND",() -> tasks.detail(outside,task));
            reject("NOT_FOUND",() -> tasks.claim(outside,task,key(),new PutawayTasks.Revision(0L)));
            reject("NOT_FOUND",() -> tasks.confirm(outside,task,line,key(),
                    new PutawayTasks.Confirmation("R01","S01","PA-BATCH","1",List.of("S-01"),0L)));
        }
    }

    private long receive(List<String> serials) {
        long order = Ids.next(), line = Ids.next();
        var qty = BigDecimal.valueOf(serials.size());
        var amount = qty.multiply(BigDecimal.TEN);
        jdbc.update("""
                INSERT INTO purchase_order(id,tenant_id,company_id,order_no,supplier_id,business_date,price_tax_inclusive,
                  goods_amount,total_amount,untaxed_amount,tax_amount,status,revision,approved_at,owner_user_id,owner_department_id)
                VALUES(?,?,?,?,?,?,1,?,?,?,0,'approved',1,UTC_TIMESTAMP(3),?,?)
                """,order,tenant,company,"PO-"+order,supplier,day,amount,amount,amount,maker.userId(),department);
        jdbc.update("""
                INSERT INTO purchase_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,
                  receive_limit_base_qty,unit_price,amount,line_net,payable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,1,?,?,?,1,?,?,?,10,?,?,?,0,?,0)
                """,line,tenant,company,order,sku,warehouse,unit,qty,qty,qty,amount,amount,amount,amount);
        var selection = new StockSelection(null,"PA-BATCH",day.minusDays(1),day.plusDays(10),null,serials);
        var input = new ReceivingModels.ReceiptInput(supplier,warehouse,day,null,
                List.of(new ReceivingModels.ReceiptLine(line,unit,qty.toPlainString(),selection)),null);
        long receipt = id(receiving.createReceipt(maker,key(),input));
        receiving.command(maker,false,receipt,"submit",key(),0L);
        assertThat(receiving.command(maker,false,receipt,"post",key(),1L)).containsEntry("status","posted");
        return receipt;
    }

    private long location(long warehouse,String code,String type,BigDecimal capacity,int sequence) {
        long zone = Ids.next(), location = Ids.next();
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,?,'上架验收库区',?)",
                zone,tenant,company,warehouse,code,type);
        jdbc.update("""
                INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name,capacity_qty,pick_sequence)
                VALUES(?,?,?,?,?,?,'上架验收库位',?,?)
                """,location,tenant,company,warehouse,zone,code,capacity,sequence);
        return location;
    }

    private BigDecimal locationQuantity(long location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty),0) FROM inventory_balance
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key=?
                """,BigDecimal.class,tenant,company,warehouse,sku,Long.toString(location));
    }

    private static List<Map<String,Object>> items(Map<String,Object> page) {return items(page,"items");}

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> items(Map<String,Object> page,String name) {
        return (List<Map<String,Object>>)page.get(name);
    }

    private static long id(Map<?,?> row) {return Long.parseLong(row.get("id").toString());}

    private static String key() {return UUID.randomUUID().toString();}

    private static void reject(String code,Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
