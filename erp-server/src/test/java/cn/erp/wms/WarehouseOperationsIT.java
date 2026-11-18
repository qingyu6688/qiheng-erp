package cn.erp.wms;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryModels.StockSelection;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import cn.erp.stockcount.StockCountModels;
import cn.erp.stockcount.StockCountService;
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

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class WarehouseOperationsIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired InventoryService inventory;
    @Autowired WmsMoves moves;
    @Autowired LocationRules rules;
    @Autowired Replenishment replenishment;
    @Autowired StockCountService counts;
    @Autowired CountTasks countWork;
    @Autowired TaskAdministration admin;
    private final LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private long tenant,company,warehouse,otherWarehouse,category,period,sku,zone,source,target,otherBin,department;
    private CompanyAccess actor;

    @BeforeTransaction void commitScope() {
        tenant=Ids.next();
        company=Ids.next();
        // 单号独立事务读取公司，先提交测试租户和公司。
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'仓储作业测试')",tenant,"WO"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','测试公司')",company,tenant);
    }

    @AfterTransaction void clearScope() {
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=? AND company_id=?",tenant,company);
        jdbc.update("DELETE FROM company WHERE tenant_id=? AND id=?",tenant,company);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @BeforeEach void seed() {
        warehouse=Ids.next();
        otherWarehouse=Ids.next();
        department=Ids.next();
        category=Ids.next();
        sku=Ids.next();
        period=Ids.next();
        actor=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("stock_count:record_counts",
                "stock_count:view","wms_task:view","wms_task:execute","wms_task:assign","wms_move:view","wms_move:manage"),
                new OrgScope(List.of(),department));
        long unit=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("""
                INSERT INTO warehouse(id,tenant_id,company_id,code,name,location_managed,wms_task_mode)
                VALUES(?,?,?,'W1','作业仓',1,'optional'),(?,?,?,'W2','其他仓',1,'off')
                """,warehouse,tenant,company,otherWarehouse,tenant,company);
        zone=newZone(warehouse);
        source=bin(warehouse,zone,"STOCK");
        target=bin(warehouse,zone,"PICK");
        otherBin=bin(otherWarehouse,newZone(otherWarehouse),"OTHER");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','产品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'SKU','测试商品',0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        var month=YearMonth.from(day);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,company,month.toString().replace("-",""),month.atDay(1),month.atEndOfMonth());
    }

    @Test void replenishesAndMovesOnlyPhysicalQuantityAndPreservesWarehouseCost() {
        receive(source,"8");
        var before=inventory.balance(actor,new Key(warehouse,sku));
        var saved=rules.save(actor,new LocationRules.Input(null,null,warehouse,sku,null,zone,target,n(2),n(5)));
        assertThat(saved).containsEntry("fixedLocationId",Long.toString(target));
        assertThat(rules.target(actor,warehouse,sku).fixedLocationId()).isEqualTo(target);
        assertThatThrownBy(()->rules.save(actor,new LocationRules.Input(null,null,warehouse,null,category,null,otherBin,null,null)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        String generateKey=key();
        var created=replenishment.generate(actor,warehouse,generateKey);
        assertThat(created).hasSize(1).isEqualTo(replenishment.generate(actor,warehouse,generateKey));
        assertThat(replenishment.generate(actor,warehouse,key())).isEmpty();
        long id=Long.parseLong(created.getFirst());
        var plan=items(moves.detail(actor,id),"lines").getFirst();
        assertThat(plan).containsEntry("qty","5.000000").containsEntry("fromLocationCode","STOCK").containsEntry("toLocationCode","PICK");
        var wrongWarehouse=new CompanyAccess(tenant,actor.userId(),company,false,Set.of(otherWarehouse),actor.permissions());
        assertThat(items(moves.page(wrongWarehouse,null,null,null,null,20),"items")).isEmpty();
        assertThatThrownBy(()->moves.detail(wrongWarehouse,id)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.getStatus()).isEqualTo(404));
        String postKey=key();
        moves.post(actor,id,postKey,new WmsScopes.Revision(0L),true);
        moves.post(actor,id,postKey,new WmsScopes.Revision(0L),true);
        assertThat(quantity(source)).isEqualByComparingTo("3");
        assertThat(quantity(target)).isEqualByComparingTo("5");
        long move=Long.parseLong(moves.create(actor,key(),new WmsMoves.Draft(warehouse,"改放",null,
                List.of(new WmsMoves.Line(sku,null,target,source,"1",null)))));
        moves.edit(actor,move,key(),new WmsMoves.Draft(warehouse,"改放",0L,
                List.of(new WmsMoves.Line(sku,null,target,source,"2",List.of()))));
        moves.post(actor,move,key(),new WmsScopes.Revision(1L),false);
        assertThat(quantity(source)).isEqualByComparingTo("5");
        assertThat(quantity(target)).isEqualByComparingTo("3");
        assertThat(inventory.balance(actor,new Key(warehouse,sku))).isEqualTo(before);
        assertThat(admin.statistics(actor,warehouse,day,day)).hasSize(2);
        long ruleId=Long.parseLong(saved.get("id").toString());
        rules.save(actor,new LocationRules.Input(ruleId,0L,warehouse,sku,null,zone,target,n(4),n(5)));
        replenishment.scheduled(tenant,company,warehouse);
        replenishment.scheduled(tenant,company,warehouse);
        var waiting=items(moves.page(actor,warehouse,"replenish","draft",null,20),"items");
        assertThat(waiting).hasSize(1);
        long scheduled=Long.parseLong(waiting.getFirst().get("id").toString());
        assertThat(items(moves.detail(actor,scheduled),"lines")).singleElement().satisfies(line->assertThat(line).containsEntry("qty","2.000000"));
    }

    @Test void pdaCountsOnlyAssignedLocationAndLeavesPostingForOriginalApproval() {
        receive(source,"4");
        receive(target,"2");
        long id=startTwoLocationCount();
        var tasks=items(countWork.page(actor,warehouse,"created",null,20),"items");
        assertThat(tasks).hasSize(2);
        long stockTask=taskIn(tasks,"STOCK");
        long pickTask=taskIn(tasks,"PICK");
        long stockLine=firstLine(stockTask);
        long pickLine=firstLine(pickTask);
        countWork.claim(actor,stockTask,key(),new WmsScopes.Revision(0L));
        assertThat(admin.todayPendingCount(actor,warehouse)).isEqualTo(1);
        assertThat(admin.todayPendingCount(actor,otherWarehouse)).isZero();
        assertThat(items(admin.page(actor,warehouse,"count","assigned","me",null,1),"items"))
                .singleElement().satisfies(row->assertThat(row).containsEntry("assigneeId",Long.toString(actor.userId())));
        assertThatThrownBy(()->countWork.confirm(actor,stockTask,key(),new CountTasks.Confirmation(1L,"L:STOCK",
                List.of(new StockCountModels.Count(pickLine,"2",null))))).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.getErrorCode()).isEqualTo("VALIDATION_FAILED"));
        String key=key();
        var input=new CountTasks.Confirmation(1L,"L:STOCK",List.of(new StockCountModels.Count(stockLine,"4",null)));
        countWork.confirm(actor,stockTask,key,input);
        countWork.confirm(actor,stockTask,key,input);
        assertThat(admin.todayPendingCount(actor,warehouse)).isZero();
        assertThat(countWork.detail(actor,stockTask)).containsEntry("status","done");
        assertThat(countWork.detail(actor,pickTask)).containsEntry("status","created");
        countWork.claim(actor,pickTask,key(),new WmsScopes.Revision(0L));
        countWork.confirm(actor,pickTask,key(),new CountTasks.Confirmation(1L,"PICK",List.of(new StockCountModels.Count(pickLine,"2",null))));
        var submitted=counts.command(actor,id,"submit",key(),new StockCountModels.Version(3L,null));
        assertThat(submitted).containsEntry("status","submitted");
        var reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),actor.permissions());
        counts.command(reviewer,id,"reject",key(),new StockCountModels.Version(4L,"抽查复盘"));
        assertThat(countWork.detail(actor,pickTask)).containsEntry("status","assigned");
        assertThat(inventory.balance(actor,new Key(warehouse,sku)).onHand()).isEqualByComparingTo("6");
    }

    @Test void pdaCountUsesStartSnapshotAndPreservesLaterReceipt() {
        receive(source,"4");
        receive(target,"2");
        long count = startTwoLocationCount();
        String snapshotAt = (String) counts.get(actor,count).get("snapshotAt");
        assertThat(java.time.Instant.parse(snapshotAt)).isNotNull();

        var tasks = items(countWork.page(actor,warehouse,"created",null,20),"items");
        assertThat(tasks).hasSize(2).allSatisfy(task -> assertThat(task).containsEntry("snapshotAt",snapshotAt));
        long stockTask = taskIn(tasks,"STOCK");
        long pickTask = taskIn(tasks,"PICK");
        var stockDetail = countWork.detail(actor,stockTask);
        assertThat(stockDetail).containsEntry("snapshotAt",snapshotAt);
        assertThat(items(stockDetail,"lines").getFirst()).containsEntry("bookQty","4.000000");

        receive(source,"2");
        countWork.claim(actor,stockTask,key(),new WmsScopes.Revision(0L));
        countWork.confirm(actor,stockTask,key(),new CountTasks.Confirmation(1L,"L:STOCK",
                List.of(new StockCountModels.Count(firstLine(stockTask),"4",null))));
        countWork.claim(actor,pickTask,key(),new WmsScopes.Revision(0L));
        countWork.confirm(actor,pickTask,key(),new CountTasks.Confirmation(1L,"PICK",
                List.of(new StockCountModels.Count(firstLine(pickTask),"2",null))));

        var reviewer = new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),actor.permissions());
        counts.command(actor,count,"submit",key(),new StockCountModels.Version(3L,null));
        counts.command(reviewer,count,"approve",key(),new StockCountModels.Version(4L,null));
        assertThat(counts.command(actor,count,"post",key(),new StockCountModels.Version(5L,null)))
                .containsEntry("status","posted");
        assertThat(quantity(source)).isEqualByComparingTo("6");
        assertThat(quantity(target)).isEqualByComparingTo("2");
    }

    @Test void stalePdaFormCannotOverwriteNewPcCount() {
        receive(source,"4");
        receive(target,"2");
        long id=startTwoLocationCount();
        var tasks=items(countWork.page(actor,warehouse,"created",null,20),"items");
        long stockTask=taskIn(tasks,"STOCK");
        long stockLine=firstLine(stockTask);
        long pickLine=firstLine(taskIn(tasks,"PICK"));
        countWork.claim(actor,stockTask,key(),new WmsScopes.Revision(0L));

        counts.counts(actor,id,new StockCountModels.Counts(1L,List.of(
                new StockCountModels.Count(stockLine,"4",null),new StockCountModels.Count(pickLine,"2",null))));

        assertThatThrownBy(()->countWork.confirm(actor,stockTask,key(),new CountTasks.Confirmation(1L,"L:STOCK",
                List.of(new StockCountModels.Count(stockLine,"3",null))))).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
        var pending=countWork.detail(actor,stockTask);
        assertThat(pending).containsEntry("status","assigned");
        assertThat(((Number)pending.get("version")).longValue()).isEqualTo(2);
        assertThat(items(pending,"lines").getFirst()).containsEntry("countedQty","4.000000");
    }

    @Test void taskCancellationRetainsFactsAndRespectsScope() {
        long id=Long.parseLong(moves.create(actor,key(),new WmsMoves.Draft(warehouse,"测试取消",null,
                List.of(new WmsMoves.Line(sku,null,source,target,"1",List.of())))));
        var outsider=new CompanyAccess(tenant,Ids.next(),company,false,Set.of(warehouse),actor.permissions(),
                OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        assertThatThrownBy(()->admin.cancel(outsider,id,key(),new WmsScopes.Revision(0L)))
                .isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(404));
        admin.cancel(actor,id,key(),new WmsScopes.Revision(0L));
        assertThat(moves.detail(actor,id)).containsEntry("status","cancelled");
        assertThat(items(moves.detail(actor,id),"lines")).hasSize(1);
    }

    private void receive(long location,String qty) {
        long label=Ids.next();
        inventory.receive(actor,new Posting(new Key(warehouse,sku),new Source("opening_entry",label,label),
                day,period,"opening_in"),new BigDecimal(qty),new BigDecimal(qty).multiply(n(10)),at(location),null);
    }

    private long startTwoLocationCount() {
        var created=counts.create(actor,key(),new StockCountModels.Document(warehouse,day,"watermark",null,
                List.of(new StockCountModels.Item(sku,at(source)),new StockCountModels.Item(sku,at(target))),null));
        long id=Long.parseLong(created.get("id").toString());
        counts.command(actor,id,"start",key(),new StockCountModels.Version(0L,null));
        return id;
    }

    private BigDecimal quantity(long location) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(on_hand_qty),0) FROM inventory_balance
                WHERE tenant_id=? AND company_id=? AND warehouse_id=? AND sku_id=? AND location_key=?
                """,BigDecimal.class,tenant,company,warehouse,sku,Long.toString(location));
    }

    private long newZone(long warehouse) {
        long id=Ids.next();
        jdbc.update("INSERT INTO wms_zone(id,tenant_id,company_id,warehouse_id,code,name,type) VALUES(?,?,?,?,'Z','存储区','storage')",
                id,tenant,company,warehouse);
        return id;
    }

    private long bin(long warehouse,long zone,String code) {
        long id=Ids.next();
        jdbc.update("INSERT INTO wms_location(id,tenant_id,company_id,warehouse_id,zone_id,code,name) VALUES(?,?,?,?,?,?,'库位')",
                id,tenant,company,warehouse,zone,code);
        return id;
    }

    private static StockSelection at(long location) { return new StockSelection(null,null,null,null,location,List.of()); }
    private static BigDecimal n(int value) { return BigDecimal.valueOf(value); }
    private static String key() { return UUID.randomUUID().toString(); }
    private static long taskIn(List<Map<String,Object>> tasks,String location) {
        return Long.parseLong(tasks.stream().filter(task->location.equals(task.get("locationCode"))).findFirst().orElseThrow().get("id").toString());
    }
    private long firstLine(long task) { return Long.parseLong(items(countWork.detail(actor,task),"lines").getFirst().get("id").toString()); }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> items(Map<String,Object> page,String field) { return (List<Map<String,Object>>)page.get(field); }
}
