package cn.erp.salesorder;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static cn.erp.salesorder.SalesOrderModels.*;
import static cn.erp.inventory.InventoryModels.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class SalesOrderIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired SalesOrderService service;
    @Autowired SalesOrderQueries queries;
    @Autowired SalesInventoryQueries stock;
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired cn.erp.fulfillment.ShipmentService shipments;
    @Autowired cn.erp.workbench.DocumentBrowser browser;
    @Autowired cn.erp.documents.PrintService prints;
    @Autowired tools.jackson.databind.ObjectMapper json;
    private CompanyAccess maker,reviewer;
    private long tenant,company,warehouse,otherWarehouse,customer,unit,sku,secondSku,thirdSku,period;
    private final LocalDate date=LocalDate.of(2026,11,15);
    private static final Set<String> PERMISSIONS=Set.of("sales_order:create","sales_order:view","sales_order:update","sales_order:delete",
            "sales_order:submit","sales_order:withdraw","sales_order:approve","sales_order:reject","sales_order:revoke_approval",
            "sales_order:cancel","sales_order:close_lines","sales_order:change","sales_order:change_approve","sales_order:change_reject",
            "sales_order:reserve","inventory:view","document:change_tax_rate");

    @BeforeEach
    void seed() {
        tenant=Ids.next();company=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();customer=Ids.next();
        unit=Ids.next();sku=Ids.next();secondSku=Ids.next();thirdSku=Ids.next();period=Ids.next();
        long category=Ids.next(),product=Ids.next();
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),PERMISSIONS);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),PERMISSIONS);
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'销售订单测试')",tenant,"SO"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','销售公司')",company,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W1','主仓'),(?,?,?,'W2','分仓')",
                warehouse,tenant,company,otherWarehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','客户')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'customer',1)",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        for(long id:List.of(sku,secondSku,thirdSku)) {
            jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price) VALUES(?,?,?,?,?,100)",id,tenant,product,"S"+id,"规格"+id);
            jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,id,unit);
        }
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
    }

    @AfterEach
    void clean() {
        for(String table:List.of("idempotency_record","audit_log","approval_record","document_change","outbox_event","inbox_event","journal_line","journal","partner_open_item","receivable","shipment_line","shipment","inventory_reservation_change",
                "inventory_reservation","inv_movement_hold","inventory_movement","inventory_cost","inventory_balance","sales_order_fee","sales_order_line",
                "sales_order","document_sequence","cf_definition","unit_conversion","sku","product","product_category","unit","partner_role",
                "business_partner","accounting_period","warehouse","company_policy","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void customValuesValidateAndExportThroughTheOriginalWarehouseScope() {
        jdbc.update("""
                INSERT INTO cf_definition(id,tenant_id,entity,field_key,label,field_type,choices,required,index_slot,list_visible,print_visible,import_export)
                VALUES(?,?,'sales_order','cf_project','项目编号','text',JSON_ARRAY(),1,1,1,1,1)
                """,Ids.next(),tenant);
        var row=line(null,sku,warehouse,"1","20","0");
        var missing=new Input(id(customer),date,null,true,"0",List.of(row),List.of(),null,null,null);
        assertThatThrownBy(()->service.create(maker,key(),missing)).hasMessageContaining("项目编号");
        var input=new Input(id(customer),date,null,true,"0",List.of(row),List.of(),null,null,null,null,null,null,
                json.readTree("{\"cf_project\":\"P-001\",\"cf_unknown\":\"不得写入\"}"));
        var saved=service.create(maker,key(),input);
        assertThat(saved.get("extJson")).isEqualTo(Map.of("cf_project","P-001"));
        assertThat(jdbc.queryForObject("SELECT cf_idx_1 FROM sales_order WHERE id=?",String.class,oid(saved))).isEqualTo("cf_project=P-001");
        var filter=cn.erp.workbench.DocumentBrowser.Filter.empty();
        var custom=Map.of("cf.cf_project",new String[]{"P-001"});
        assertThat(browser.page(maker,"sales-orders",filter,null,20,true,custom,false)).containsEntry("total",1L);
        var denied=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(otherWarehouse),PERMISSIONS);
        assertThat(browser.page(denied,"sales-orders",filter,null,20,true,custom,false)).containsEntry("total",0L);
        assertThat(new String(browser.customExport(maker,"sales-orders",filter,null,20,custom),java.nio.charset.StandardCharsets.UTF_8))
                .contains("项目编号","P-001").doesNotContain("不得写入");
        assertThat(new String(browser.customExport(denied,"sales-orders",filter,null,20,custom),java.nio.charset.StandardCharsets.UTF_8)).doesNotContain("P-001");
        var permissions=new HashSet<>(PERMISSIONS);permissions.add("sales_order:print");
        var printScope=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),permissions);
        assertThat(prints.get(printScope,"sales_order",oid(saved)).get("document").toString()).contains("printCustomFields={项目编号=P-001}");
        jdbc.update("UPDATE cf_definition SET enabled=0 WHERE tenant_id=?",tenant);
        assertThat(queries.get(maker,oid(saved)).get("extJson")).isEqualTo(Map.of());
        assertThat(jdbc.queryForObject("SELECT ext_json FROM sales_order WHERE id=?",String.class,oid(saved))).contains("P-001");
    }

    @Test
    void SO4AndB03B04SaveServiceCalculatedDiscountTaxAndFeeSnapshots() {
        var input=new Input(id(customer),date,date.plusDays(5),true,"100",List.of(
                line(null,sku,warehouse,"4","115","60"),line(null,secondSku,warehouse,"8","55","40"),
                line(null,thirdSku,warehouse,"2","210","20")),List.of(new Fee(null,"freight","buyer","30","0.13")),null,null,null);
        var result=service.create(maker,key(),input);
        assertThat(result).containsEntry("goodsAmount","1320.00").containsEntry("lineDiscountAmount","120.00")
                .containsEntry("totalAmount","1130.00").containsEntry("taxAmount","129.99").containsEntry("pricingVersion",2);
        assertThat(lines(result)).extracting(l->l.get("orderDiscountAlloc")).containsExactly("33.34","33.33","33.33");
        assertThat(queries.get(maker,oid(result))).containsEntry("totalAmount","1130.00");
    }

    @Test
    void B30B31SubmissionWarnsWithoutReservingAndPendingDemandDoesNotReduceAvailable() {
        receive(warehouse,sku,"10");
        var first=create("30");var second=create("30");
        var submitted=command(maker,first,"submit");
        command(maker,second,"submit");
        assertThat((List<?>)submitted.get("warnings")).hasSize(1);
        var balance=items(stock.balances(maker,id(warehouse),"",null,20)).getFirst();
        assertThat(balance).containsEntry("availableQty","10.000000").containsEntry("pendingDemandQty","60.000000");
        var costScope=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),Set.of("inventory:view","cost:view"));
        var filtered=stock.balances(costScope,id(warehouse),"",null,1,true,true);
        assertThat(filtered.get("summary")).asString().contains("shortageCount=1","costAmount=100.00");
        assertThat(items(filtered)).hasSize(1);
        var hidden=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),Set.of("inventory:view"));
        var safe=stock.balances(hidden,id(warehouse),"",null,20,true,true);
        assertThat(items(safe).getFirst()).doesNotContainKeys("costAmount","unitCost");
        assertThat(safe.get("summary").toString()).doesNotContain("costAmount","unreceivedCostAmount","unreceivedCount");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        assertThatThrownBy(()->command(reviewer,first,"approve")).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.getErrorCode()).isEqualTo("INVENTORY_INSUFFICIENT"));
        assertThat(queries.get(maker,oid(first))).containsEntry("status","pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",Integer.class,tenant)).isZero();
    }

    @Test
    void inventoryUnitCostKeepsEightDecimalPlacesBeforeDisplayRounding() {
        inventory.receive(maker,new Posting(new Key(warehouse,sku),new Source("opening",Ids.next(),Ids.next()),date,period,"opening_in"),new BigDecimal("3"),new BigDecimal("1.00"));
        var costScope=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),Set.of("inventory:view","cost:view"));
        assertThat(items(stock.balances(costScope,id(warehouse),"",null,20)).getFirst())
                .containsEntry("unitCost","0.33333333").containsEntry("costAmount","1.00");
    }

    @Test
    void inventoryPageSharesTotalsAcrossCursorAndKeepsUnsignedIndependent() {
        receive(warehouse,sku,"10");
        receive(warehouse,secondSku,"5");
        receive(otherWarehouse,sku,"20");
        jdbc.update("INSERT INTO inventory_balance(id,tenant_id,company_id,warehouse_id,sku_id) VALUES(?,?,?,?,?)",
                Ids.next(),tenant,company,warehouse,thirdSku);
        var shipped=approved("3");
        long sourceLine=Long.parseLong(lines(queries.get(maker,oid(shipped))).getFirst().get("id").toString());
        var shipment=shipments.create(maker,key(),new cn.erp.fulfillment.ShipmentModels.Input(customer,warehouse,date,"delivery",null,
                List.of(new cn.erp.fulfillment.ShipmentModels.InputLine(sourceLine,unit,"1")),null));
        long shipmentId=oid(shipment);
        var submitted=shipments.submit(maker,shipmentId,key(),((Number)shipment.get("version")).longValue());
        shipments.post(maker,shipmentId,key(),((Number)submitted.get("version")).longValue());
        command(maker,create("15"),"submit");

        var permissions=Set.of("inventory:view","cost:view","report:unsigned_shipments");
        var scoped=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),permissions);
        var first=stock.balances(scoped,null,"",null,1,false,true);
        assertThat(items(first)).singleElement().satisfies(item->assertThat(item)
                .containsEntry("id",id(warehouse)+":"+id(sku)).containsEntry("pendingDemandQty","15.000000")
                .doesNotContainKeys("summaryCostAmount","summaryUnreceivedCount"));
        String cursor=(String)first.get("nextCursor");
        assertThat(cursor).isEqualTo(id(warehouse)+":"+id(sku));
        var next=stock.balances(scoped,null,"",cursor,1,false,true);
        assertThat(items(next)).singleElement().satisfies(item->assertThat(item)
                .containsEntry("id",id(warehouse)+":"+id(secondSku)));
        assertThat(next.get("nextCursor")).isNull();
        var empty=stock.balances(scoped,null,"",id(warehouse)+":"+id(secondSku),1,false,true);
        assertThat(items(empty)).isEmpty();
        assertThat(empty.get("nextCursor")).isNull();
        assertThat(empty.get("summary")).isEqualTo(first.get("summary")).isEqualTo(next.get("summary"));
        assertThat(first.get("summary").toString()).contains("costAmount=140.00","shortageCount=1",
                "unreceivedCount=1","unreceivedCostAmount=10.00");
        assertThat(items(stock.balances(scoped,null,"",null,20,false,false))).hasSize(3);

        var shortage=stock.balances(scoped,null,"",null,20,true,true);
        assertThat(items(shortage)).hasSize(1);
        assertThat(shortage.get("summary").toString()).contains("costAmount=90.00","unreceivedCount=1");
        var all=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),permissions);
        var bySku=stock.balances(all,null,"",null,20,false,true,sku);
        assertThat(items(bySku)).hasSize(2);
        assertThat(bySku.get("summary").toString()).contains("onHandQty=29.000000","reservedQty=2.000000",
                "availableQty=27.000000","pendingDemandQty=15.000000");
        assertThat(items(stock.balances(all,null," ",null,20,false,false))).isEmpty();

        var noCost=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),
                Set.of("inventory:view","report:unsigned_shipments"));
        var limited=stock.balances(noCost,null,"",null,20,true,true);
        assertThat(items(limited).getFirst()).doesNotContainKeys("costAmount","unitCost");
        assertThat(limited.get("summary").toString()).contains("unreceivedCount=1")
                .doesNotContain("costAmount","unreceivedCostAmount");
        var department=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(warehouse),permissions,
                OrgScope.restricted(Set.of(1001L),Set.of(),1001));
        var withoutHiddenDemand=stock.balances(department,null,"",null,20,true,true);
        assertThat(items(withoutHiddenDemand)).isEmpty();
        assertThat(withoutHiddenDemand.get("summary").toString()).contains("shortageCount=0","unreceivedCount=0");
    }

    @Test
    void approvalRollsBackEveryReservationWhenLaterSkuIsInsufficient() {
        receive(warehouse,sku,"5");
        var input=new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,"3","10",null),
                line(null,secondSku,warehouse,"1","10",null)),List.of(),null,null,null);
        var order=service.create(maker,key(),input);command(maker,order,"submit");
        assertThatThrownBy(()->command(reviewer,order,"approve")).isInstanceOf(ApiException.class);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        assertThat(queries.get(maker,oid(order))).containsEntry("status","pending");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_record WHERE tenant_id=? AND action='approve'",Integer.class,tenant)).isZero();
    }

    @Test
    void T03ConcurrentApprovalHasOneEffectAcrossTwentyOrders() throws Exception {
        receive(warehouse,sku,"30");
        for(int round=0;round<20;round++) {
            var order=create("1");command(maker,order,"submit");
            long version=version(order);long orderId=oid(order);
            var start=new CountDownLatch(1);
            try(var executor=Executors.newFixedThreadPool(2)) {
                Callable<Boolean> approve=()->{start.await();try {
                    service.command(reviewer,orderId,"approve",key(),new Command(version,null,null));return true;
                }catch(ApiException error){assertThat(error.getStatus()).isEqualTo(409);return false;}};
                var first=executor.submit(approve);var second=executor.submit(approve);start.countDown();
                assertThat(List.of(first.get(20,TimeUnit.SECONDS),second.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation r JOIN sales_order_line l ON l.id=r.order_line_id WHERE l.order_id=?",Integer.class,orderId)).isEqualTo(1);
        }
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("20");
    }

    @Test
    void T05LostResponseRetryReturnsFirstResultWithoutRepeatingApproval() {
        receive(warehouse,sku,"5");
        var order=create("2");command(maker,order,"submit");
        var request=new Command(version(order),null,null);String requestKey=key();
        var first=service.command(reviewer,oid(order),"approve",requestKey,request);
        var retry=service.command(reviewer,oid(order),"approve",requestKey,request);
        assertThat(retry.get("id")).isEqualTo(first.get("id"));
        assertThat(retry.get("version").toString()).isEqualTo(first.get("version").toString());
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("2");
        assertThatThrownBy(()->service.command(reviewer,oid(order),"approve",requestKey,new Command(version(order),"不同内容",null)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void T14HistoricalPricesConversionAndTaxRemainAfterMasterDataChanges() {
        long box=Ids.next();
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'箱')",box,tenant);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,10,0)",Ids.next(),tenant,sku,box);
        var input=new Input(id(customer),date,null,true,"0",List.of(new Line(null,id(sku),id(warehouse),id(box),"2","100",null,null,null,null)),List.of(),null,null,null);
        var order=service.create(maker,key(),input);
        jdbc.update("UPDATE sku SET sale_price=999,default_tax_rate=.09 WHERE id=?",sku);
        jdbc.update("UPDATE unit_conversion SET rate_to_base=12 WHERE tenant_id=? AND sku_id=? AND unit_id=?",tenant,sku,box);
        var historical=queries.get(maker,oid(order));
        assertThat(lines(historical).getFirst()).containsEntry("unitPrice","100.00000000").containsEntry("unitRate","10.000000")
                .containsEntry("taxRate","0.130000").containsEntry("baseQty","20.000000");
        assertThat(historical).containsEntry("totalAmount","200.00");
    }

    @Test
    void B01ExpiredReservationsCanBeRetriedAndStateStaysApproved() {
        receive(warehouse,sku,"5");
        var order=approved("3");
        long reservation=jdbc.queryForObject("SELECT id FROM inventory_reservation WHERE tenant_id=? AND status='active'",Long.class,tenant);
        jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",reservation);
        reservations.expire(maker,reservation,Instant.now());
        assertThat(queries.get(maker,oid(order))).containsEntry("status","approved");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        command(maker,order,"reserve");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("3");
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("reservationState","reserved");
    }

    @Test
    void O06NewerExpiredHistoryCannotHideAnEarlierActiveReservation() {
        receive(warehouse,sku,"5");var order=approved("3");
        long line=Long.parseLong(lines(order).getFirst().get("id").toString());
        jdbc.update("""
            INSERT INTO inventory_reservation(id,tenant_id,company_id,order_line_id,warehouse_id,sku_id,
                reserved_qty,remaining_qty,expire_at,status,created_by)
            VALUES(?,?,?,?,?,?,1,0,DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND),'expired',?)
            """,Ids.next(),tenant,company,line,warehouse,sku,maker.userId());
        reservations.refreshLineState(maker,line);
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("reservationState","reserved").containsEntry("reservedQty","3.000000");
        assertThat(jdbc.queryForObject("SELECT reservation_state FROM sales_order_line WHERE id=?",String.class,line)).isEqualTo("reserved");
    }

    @Test
    void withdrawalRejectionSeparationAndRevokeApprovalPreserveDraftAndHistory() {
        receive(warehouse,sku,"5");var order=create("2");
        command(maker,order,"submit");command(maker,order,"withdraw");
        assertThat(queries.get(maker,oid(order))).containsEntry("status","draft");
        command(maker,order,"submit");
        service.command(reviewer,oid(order),"reject",key(),new Command(version(order),"资料不全",null));
        assertThat(queries.get(maker,oid(order))).containsEntry("status","draft");
        command(maker,order,"submit");
        assertThatThrownBy(()->command(maker,order,"approve")).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.getErrorCode()).isEqualTo("SAME_OPERATOR_NOT_ALLOWED"));
        command(reviewer,order,"approve");command(maker,order,"revoke_approval");
        assertThat(queries.get(maker,oid(order))).containsEntry("status","draft");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        service.edit(maker,oid(order),input("1",version(order),null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE tenant_id=?",Integer.class,tenant)).isEqualTo(1);
    }

    @Test
    void closingRemainderIsPermanentAndCancellationReleasesReservation() {
        receive(warehouse,sku,"10");var order=approved("3");
        String lineId=lines(queries.get(maker,oid(order))).getFirst().get("id").toString();
        service.close(maker,oid(order),key(),new Close(version(order),"客户取消余量",List.of(new CloseLine(lineId,"1"))));
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("2");
        service.close(maker,oid(order),key(),new Close(version(order),"关闭剩余",List.of(new CloseLine(lineId,"2"))));
        assertThat(queries.get(maker,oid(order))).containsEntry("status","closed");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        var other=approved("2");
        service.command(maker,oid(other),"cancel",key(),new Command(version(other),null,"客户撤单"));
        assertThat(queries.get(maker,oid(other))).containsEntry("status","cancelled");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
    }

    @Test
    void quantityDecreaseAppliesImmediatelyButIncreaseKeepsOriginalUntilApproved() {
        receive(warehouse,sku,"10");var order=approved("3");
        var reservation=reservation(order);
        var decrease=service.change(maker,oid(order),key(),input("2",version(order),"减少数量"));
        assertThat(change(decrease)).containsEntry("status","applied");
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("baseQty","2.000000");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("2");
        var increase=service.change(reviewer,oid(order),key(),input("4",version(order),"增加数量"));
        assertThat(increase).containsEntry("status","change_pending");
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("baseQty","2.000000");
        assertThat(queries.get(maker,oid(order)).get("allowedActions")).isEqualTo(List.of("change_approve","change_reject"));
        assertThat(queries.get(reviewer,oid(order)).get("allowedActions")).isEqualTo(List.of("change_reject"));
        service.decideChange(maker,Long.parseLong(change(increase).get("id").toString()),"approve",key(),new Command(0L,"同意",null));
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("baseQty","4.000000");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("4");
        assertThat(reservation(order)).containsEntry("id",reservation.get("id")).containsEntry("expire_at",reservation.get("expire_at"));
        assertThat(jdbc.queryForObject("SELECT SUM(qty_delta) FROM inventory_reservation_change WHERE reservation_id=?",BigDecimal.class,reservation.get("id"))).isEqualByComparingTo("4");
    }

    @Test
    void warehouseChangeRollsBackOriginalReservationWhenTargetStockIsInsufficient() {
        receive(warehouse,sku,"5");var order=approved("3");
        var reservation=reservation(order);
        var input=new Input(id(customer),date,null,true,"0",List.of(line(null,sku,otherWarehouse,"3","10",null)),List.of(),null,version(order),"改仓");
        assertThatThrownBy(()->service.change(maker,oid(order),key(),input)).isInstanceOf(ApiException.class);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("3");
        assertThat(lines(queries.get(maker,oid(order))).getFirst()).containsEntry("warehouseId",id(warehouse));
        assertThat(queries.get(maker,oid(order))).containsEntry("status","approved");
        receive(otherWarehouse,sku,"5");
        service.change(maker,oid(order),key(),input);
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        assertThat(inventory.balance(maker,new Key(otherWarehouse,sku)).reserved()).isEqualByComparingTo("3");
        assertThat(((Number)reservation(order).get("warehouse_id")).longValue()).isEqualTo(otherWarehouse);
        assertThat(reservation(order)).containsEntry("expire_at",reservation.get("expire_at"));
    }

    @Test
    void B18NewAndRemovedLinesOnlyChangeTheirOwnReservationsAndKeepHistoricalIds() {
        receive(warehouse,sku,"10");receive(warehouse,secondSku,"10");var order=approved("3");
        var original=reservation(order);String firstLine=lines(queries.get(maker,oid(order))).getFirst().get("id").toString();
        var changed=new Input(id(customer),date,null,true,"0",List.of(line(firstLine,sku,warehouse,"3","10",null),line(null,secondSku,warehouse,"2","20",null)),List.of(),null,version(order),"增加商品行");
        var pending=service.change(maker,oid(order),key(),changed);
        assertThat(pending).containsEntry("status","change_pending");
        service.decideChange(reviewer,Long.parseLong(change(pending).get("id").toString()),"approve",key(),new Command(0L,null,null));
        assertThat(jdbc.queryForMap("SELECT * FROM inventory_reservation WHERE id=?",original.get("id"))).containsEntry("status","active").containsEntry("expire_at",original.get("expire_at"));
        var current=lines(queries.get(maker,oid(order)));long removed=Long.parseLong(current.getLast().get("id").toString());
        assertThat(inventory.balance(maker,new Key(warehouse,secondSku)).reserved()).isEqualByComparingTo("2");
        service.change(maker,oid(order),key(),new Input(id(customer),date,null,true,"0",List.of(line(firstLine,sku,warehouse,"3","10",null)),List.of(),null,version(order),"删除未发商品行"));
        assertThat(lines(queries.get(maker,oid(order)))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT is_deleted FROM sales_order_line WHERE id=?",Integer.class,removed)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE order_line_id=? AND status='released'",Integer.class,removed)).isEqualTo(1);
        assertThat(inventory.balance(maker,new Key(warehouse,secondSku)).reserved()).isEqualByComparingTo("0");
    }

    @Test
    void B18PriceTaxAndDiscountApprovalsLeaveStockReservationAndExpiryUntouched() {
        receive(warehouse,sku,"30");
        for(String variant:List.of("price","tax","line_discount","order_discount")) {
            var order=approved("3");var before=reservation(order);
            long stockVersion=jdbc.queryForObject("SELECT version FROM inventory_balance WHERE tenant_id=? AND warehouse_id=? AND sku_id=?",Long.class,tenant,warehouse,sku);
            var line=new Line(null,id(sku),id(warehouse),id(unit),"3",variant.equals("price")?"12":"10",null,variant.equals("line_discount")?"1":null,null,variant.equals("tax")?"0.09":null);
            var input=new Input(id(customer),date,null,true,variant.equals("order_discount")?"1":"0",List.of(line),List.of(),null,version(order),"调整价格或优惠");
            var pending=service.change(maker,oid(order),key(),input);
            assertThat(pending).containsEntry("status","change_pending");
            service.decideChange(reviewer,Long.parseLong(change(pending).get("id").toString()),"approve",key(),new Command(0L,"同意",null));
            assertThat(reservation(order)).containsEntry("id",before.get("id")).containsEntry("remaining_qty",before.get("remaining_qty")).containsEntry("expire_at",before.get("expire_at"));
            assertThat(jdbc.queryForObject("SELECT version FROM inventory_balance WHERE tenant_id=? AND warehouse_id=? AND sku_id=?",Long.class,tenant,warehouse,sku)).isEqualTo(stockVersion);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation_change WHERE reservation_id=?",Integer.class,before.get("id"))).isEqualTo(1);
        }
    }

    @Test
    void B18RemarksKeepExpiryAndFeeIdsWhileDeliveryDateAloneRecalculatesExpiry() {
        receive(warehouse,sku,"10");
        var fees=List.of(new Fee(null,"freight","buyer","30","0.13"));
        var created=service.create(maker,key(),new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),fees,null,null,null));
        command(maker,created,"submit");command(reviewer,created,"approve");var before=reservation(created);
        long feeId=jdbc.queryForObject("SELECT id FROM sales_order_fee WHERE order_id=?",Long.class,oid(created));
        service.change(maker,oid(created),key(),new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),fees,"改备注",version(created),"更新备注"));
        assertThat(reservation(created)).containsEntry("id",before.get("id")).containsEntry("expire_at",before.get("expire_at"));
        assertThat(jdbc.queryForObject("SELECT id FROM sales_order_fee WHERE order_id=?",Long.class,oid(created))).isEqualTo(feeId);
        LocalDate delivery=date.plusDays(60);
        service.change(maker,oid(created),key(),new Input(id(customer),date,delivery,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),fees,"改备注",version(created),"推迟交货"));
        assertThat(reservation(created)).containsEntry("id",before.get("id")).doesNotContainEntry("expire_at",before.get("expire_at"));
        assertThat(jdbc.queryForObject("SELECT UNIX_TIMESTAMP(expire_at) FROM inventory_reservation WHERE id=?",BigDecimal.class,before.get("id")).longValue())
                .isEqualTo(delivery.plusDays(7).atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond());
        jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",before.get("id"));
        reservations.expire(maker,((Number)before.get("id")).longValue(),Instant.now());
        service.change(maker,oid(created),key(),new Input(id(customer),date,delivery,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),fees,"过期后备注",version(created),"只改备注"));
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE order_line_id=?",Integer.class,lines(queries.get(maker,oid(created))).getFirst().get("id"))).isEqualTo(1);
    }

    @Test
    void B18ExpiredReservationCanChangeWarehouseWithoutReusingPastExpiry() {
        receive(warehouse,sku,"5");receive(otherWarehouse,sku,"5");var order=approved("3");var previous=reservation(order);
        jdbc.update("UPDATE inventory_reservation SET expire_at=DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 SECOND) WHERE id=?",previous.get("id"));
        reservations.expire(maker,((Number)previous.get("id")).longValue(),Instant.now());
        service.change(maker,oid(order),key(),new Input(id(customer),date,null,true,"0",List.of(line(null,sku,otherWarehouse,"3","10",null)),List.of(),null,version(order),"到期后改仓"));
        assertThat(((Number)reservation(order).get("warehouse_id")).longValue()).isEqualTo(otherWarehouse);
        assertThat(reservation(order)).containsEntry("status","active");
        assertThat(inventory.balance(maker,new Key(otherWarehouse,sku)).reserved()).isEqualByComparingTo("3");
    }

    @Test
    void B18ApprovedChangesCannotReplaceCustomerOrTaxInclusiveFlag() {
        receive(warehouse,sku,"5");var order=approved("3");long other=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'OTHER','另一客户')",other,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,other);
        for(var input:List.of(new Input(id(other),date,null,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),List.of(),null,version(order),"换客户"),
                new Input(id(customer),date,null,false,"0",List.of(line(null,sku,warehouse,"3","10",null)),List.of(),null,version(order),"改含税口径"))) {
            assertThatThrownBy(()->service.change(maker,oid(order),key(),input)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("CHANGE_NOT_ALLOWED"));
        }
        assertThat(queries.get(maker,oid(order))).containsEntry("customerId",id(customer)).containsEntry("status","approved");
        assertThat(inventory.balance(maker,new Key(warehouse,sku)).reserved()).isEqualByComparingTo("3");
    }

    @Test
    void B18ChangesPreserveShippedLineSnapshotsAndRecognizedFeeReferences() {
        receive(warehouse,sku,"10");var fees=List.of(new Fee(null,"freight","buyer","30","0.13"));
        var order=service.create(maker,key(),new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,"3","10",null)),fees,null,null,null));
        command(maker,order,"submit");command(reviewer,order,"approve");
        long sourceLine=Long.parseLong(lines(queries.get(maker,oid(order))).getFirst().get("id").toString());
        var shipment=shipments.create(maker,key(),new cn.erp.fulfillment.ShipmentModels.Input(customer,warehouse,date,"pickup",null,
                List.of(new cn.erp.fulfillment.ShipmentModels.InputLine(sourceLine,unit,"1")),null));
        long shipmentId=oid(shipment);
        var submitted=shipments.submit(maker,shipmentId,key(),((Number)shipment.get("version")).longValue());
        shipments.post(maker,shipmentId,key(),((Number)submitted.get("version")).longValue());
        var fee=jdbc.queryForMap("SELECT * FROM sales_order_fee WHERE order_id=?",oid(order));
        var before=jdbc.queryForMap("SELECT * FROM sales_order_line WHERE id=?",sourceLine);var reservation=reservation(order);
        service.change(maker,oid(order),key(),new Input(id(customer),date,null,true,"0",List.of(line(id(sourceLine),sku,warehouse,"3","10",null)),fees,"补充备注",version(order),"只改备注"));
        var after=jdbc.queryForMap("SELECT * FROM sales_order_line WHERE id=?",sourceLine);
        for(String field:List.of("unit_rate","unit_price","tax_rate","shipped_base_qty","signed_base_qty","recognized_amount","recognized_tax")) {
            assertThat(after.get(field)).as(field+"保留原签收快照").isEqualTo(before.get(field));
        }
        var currentFee=jdbc.queryForMap("SELECT * FROM sales_order_fee WHERE order_id=?",oid(order));
        assertThat(currentFee).containsEntry("id",fee.get("id"));
        assertThat(((Number)currentFee.get("recognized_shipment_id")).longValue()).isEqualTo(shipmentId);
        assertThat(reservation(order)).containsEntry("id",reservation.get("id")).containsEntry("remaining_qty",reservation.get("remaining_qty")).containsEntry("expire_at",reservation.get("expire_at"));
        var changedPrice=new Input(id(customer),date,null,true,"0",List.of(line(id(sourceLine),sku,warehouse,"3","11",null)),fees,null,version(order),"修改已发价格");
        assertThatThrownBy(()->service.change(maker,oid(order),key(),changedPrice)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("CHANGE_NOT_ALLOWED"));
        var changedFee=new Input(id(customer),date,null,true,"0",List.of(line(id(sourceLine),sku,warehouse,"3","10",null)),List.of(new Fee(null,"freight","buyer","40","0.13")),null,version(order),"修改已确认费用");
        assertThatThrownBy(()->service.change(maker,oid(order),key(),changedFee)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("CHANGE_NOT_ALLOWED"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shipment_line WHERE order_line_id=?",Integer.class,sourceLine)).isEqualTo(1);
    }

    @Test
    void B20InvalidStatesAndVersionsAreRejectedAndWarehouseScopeDoesNotLeakOrders() {
        var order=create("2");
        assertThatThrownBy(()->command(reviewer,order,"approve")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(409));
        var limited=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(otherWarehouse),PERMISSIONS);
        assertThatThrownBy(()->queries.get(limited,oid(order))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThat(items(queries.list(limited,"",null,null,null,20))).isEmpty();
        var original=input("2",0L,null);
        service.edit(maker,oid(order),original);
        assertThatThrownBy(()->service.edit(maker,oid(order),original)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
        assertThatThrownBy(()->service.delete(maker,oid(order),0L)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
        long deleteVersion=version(order);
        service.delete(maker,oid(order),deleteVersion);service.delete(maker,oid(order),deleteVersion);
        assertThatThrownBy(()->queries.get(maker,oid(order))).isInstanceOf(ApiException.class);
    }

    @Test void paymentTermsAreFrozenAtApprovalAndWrittenByRealPickup() {
        jdbc.update("UPDATE partner_role SET term_type='days',term_value=30 WHERE tenant_id=? AND partner_id=?",tenant,customer);
        receive(warehouse,sku,"3");
        var order=approved("2");
        assertThat(queries.get(maker,oid(order))).containsEntry("termType","days").containsEntry("termValue",30);
        jdbc.update("UPDATE partner_role SET term_value=90 WHERE tenant_id=? AND partner_id=?",tenant,customer);
        long sourceLine=Long.parseLong(lines(queries.get(maker,oid(order))).getFirst().get("id").toString());
        var shipment=shipments.create(maker,key(),new cn.erp.fulfillment.ShipmentModels.Input(customer,warehouse,date,"pickup",null,
                List.of(new cn.erp.fulfillment.ShipmentModels.InputLine(sourceLine,unit,"1")),null));
        var submitted=shipments.submit(maker,oid(shipment),key(),0L);
        shipments.post(maker,oid(shipment),key(),((Number)submitted.get("version")).longValue());
        assertThat(jdbc.queryForObject("SELECT due_date FROM receivable WHERE tenant_id=? AND source_id=?",java.sql.Date.class,tenant,oid(shipment)))
                .isEqualTo(java.sql.Date.valueOf(date.plusDays(30)));
        assertThat(queries.get(maker,oid(order))).containsEntry("termValue",30);
    }

    @Test void orderCanOverrideTermsBeforeApprovalAndCannotChangeThemAfterwards() {
        receive(warehouse,sku,"3");
        jdbc.update("UPDATE company_policy SET default_term_type='monthly',default_term_value=28 WHERE tenant_id=? AND company_id=?",tenant,company);
        var inherited=approved("1");
        assertThat(queries.get(maker,oid(inherited))).containsEntry("termType","monthly").containsEntry("termValue",28);
        var custom=new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,"1","10",null)),List.of(),null,null,null,
                null,null,null,null,new PaymentTerms.Term("days",7),false);
        var order=service.create(maker,key(),custom);command(maker,order,"submit");command(reviewer,order,"approve");
        assertThat(queries.get(maker,oid(order))).containsEntry("termType","days").containsEntry("termValue",7);
        var item=lines(queries.get(maker,oid(order))).getFirst();
        var change=new Input(id(customer),date,null,true,"0",List.of(line(item.get("id").toString(),sku,warehouse,"1","10",null)),List.of(),null,version(order),"调整账期",
                null,null,null,null,new PaymentTerms.Term("days",60),false);
        assertThatThrownBy(()->service.change(maker,oid(order),key(),change)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("CHANGE_NOT_ALLOWED"));
    }

    private Map<String,Object> create(String qty) {return service.create(maker,key(),input(qty,null,null));}
    private Map<String,Object> reservation(Map<String,Object> order) {return jdbc.queryForMap("SELECT r.* FROM inventory_reservation r JOIN sales_order_line l ON l.id=r.order_line_id WHERE l.order_id=? AND r.status='active' ORDER BY r.id LIMIT 1",oid(order));}
    private Map<String,Object> approved(String qty) {var order=create(qty);command(maker,order,"submit");command(reviewer,order,"approve");return order;}
    private Input input(String qty,Long version,String reason) {return new Input(id(customer),date,null,true,"0",List.of(line(null,sku,warehouse,qty,"10",null)),List.of(),null,version,reason);}
    private Line line(String lineId,long skuId,long warehouseId,String qty,String price,String discount) {return new Line(lineId,id(skuId),id(warehouseId),id(unit),qty,price,null,discount,null,null);}
    private Map<String,Object> command(CompanyAccess actor,Map<String,Object> order,String action) {return service.command(actor,oid(order),action,key(),new Command(version(order),null,null));}
    private long version(Map<String,Object> order) {return ((Number)queries.get(maker,oid(order)).get("version")).longValue();}
    private long oid(Map<String,Object> order) {return Long.parseLong(order.get("id").toString());}
    private String id(long id) {return Long.toString(id);}
    private String key() {return UUID.randomUUID().toString();}
    private void receive(long wh,long sk,String qty) {inventory.receive(maker,new Posting(new Key(wh,sk),new Source("opening",Ids.next(),Ids.next()),date,period,"opening_in"),new BigDecimal(qty),new BigDecimal(qty).multiply(BigDecimal.TEN).setScale(2));}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> lines(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("lines");}
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("items");}
    @SuppressWarnings("unchecked") private Map<String,Object> change(Map<String,Object> result) {return (Map<String,Object>)result.get("change");}
}
