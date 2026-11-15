package cn.erp.fulfillment;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.accounting.SettlementService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.inventory.ReservationService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesfinance.FinanceModels;
import cn.erp.salesfinance.ReceiptService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class ShipmentIT {
    private static final LocalDate DAY=LocalDate.of(2026,11,15);
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentService service;
    @Autowired ShipmentQueries queries;
    @Autowired InventoryService inventory;
    @Autowired ReservationService reservations;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    @Autowired ReceiptService receipts;
    @Autowired SalesOrderService orders;
    @Autowired cn.erp.salesorder.SalesOrderQueries orderQueries;
    @Autowired ReconciliationChecks checks;
    private long tenant,company,warehouse,customer,unit,category,product,period,account,order;
    private final List<Long> skus=new ArrayList<>();
    private CompanyAccess scope;

    @BeforeEach
    void seedMasterDataAndRealOpeningFacts() {
        tenant=Ids.next();company=Ids.next();warehouse=Ids.next();customer=Ids.next();unit=Ids.next();
        category=Ids.next();product=Ids.next();period=Ids.next();account=Ids.next();order=Ids.next();skus.clear();
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("cost:view","shipment:update","shipment:submit","shipment:post","shipment:void","shipment:sign","shipment:resolve_diff"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'销售履约测试')",tenant,"SHIP"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲')",company,tenant);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','销售仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'C','客户甲')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,'customer')",Ids.next(),tenant,customer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','测试商品','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'BANK','收款银行','bank')",account,tenant,company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        for(int i=0;i<3;i++) {
            long sku=Ids.next();skus.add(sku);
            jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,?,?)",sku,tenant,product,"SKU"+i,"商品"+i);
            jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
            long source=Ids.next();
            inventory.receive(scope,new Posting(new Key(warehouse,sku),new Source("opening_entry",source,source),DAY,period,"opening_in"),d("12"),d("120"));
            ledger.post(scope,new LedgerService.Posting("opening_entry",source,0,period,DAY,List.of(
                    entry("INVENTORY","D","120",null,null,warehouse,sku),entry("OPENING_EQUITY","C","120",null,null,null,null))));
        }
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        jdbc.update("UPDATE sales_order_fee SET recognized_shipment_id=NULL WHERE tenant_id=?",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item",
                "receivable","receipt","inventory_reservation_change","inventory_reservation","cost_adjustment","inventory_movement",
                "inventory_cost","inventory_balance","shipment_line","shipment","sales_order_fee","sales_order_line","sales_order",
                "audit_log","idempotency_record","document_sequence","cash_account_balance","cash_account","accounting_period",
                "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","warehouse","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @Test
    void SO3ShipmentOnlyMovesCostAndSigningAllocatesPrepaymentThenAllowsRejectedReplacement() {
        approvedOrder("SO-3","1000","0","0","1000","1000","0");
        long source=line(1,0,"10","100","0","0","1000","0");reserve(source,0,"10");
        prepayment("300");
        long first=ship(source,"4","delivery");
        @SuppressWarnings("unchecked") var partialOrderLines=(List<Map<String,Object>>)orderQueries.get(scope,order).get("lines");
        assertThat(partialOrderLines.getFirst()).containsEntry("reservationState","reserved").containsEntry("reservedQty","6.000000");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(value("SELECT SUM(IF(direction='D',amount,-amount)) FROM journal_line WHERE tenant_id=? AND account_code='GOODS_SHIPPED'",tenant)).isEqualByComparingTo("40");
        var firstSigned=sign(first,"4");
        assertThat(firstSigned.get("receivableAmount")).isEqualTo("400.00");
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=? AND direction='D'",tenant)).isEqualByComparingTo("100");
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=? AND direction='C'",tenant)).isZero();
        long paid=prepayment("100");
        long firstDebit=jdbc.queryForObject("SELECT p.id FROM partner_open_item p JOIN receivable r ON r.id=p.source_id AND p.item_type='receivable' WHERE r.tenant_id=? AND r.source_id=?",Long.class,tenant,first);
        settlement.allocate(scope,firstDebit,paid,d("100"),"manual",null,null);
        long second=ship(source,"6","delivery");
        @SuppressWarnings("unchecked") var consumedOrderLines=(List<Map<String,Object>>)orderQueries.get(scope,order).get("lines");
        assertThat(consumedOrderLines.getFirst()).containsEntry("reservationState","consumed").containsEntry("reservedQty","0.000000");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,order)).isEqualTo("closed");
        var secondSigned=sign(second,"5");
        assertThat(secondSigned.get("status")).isEqualTo("signed_with_diff");
        assertThat(secondSigned.get("receivableAmount")).isEqualTo("500.00");
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,order)).isEqualTo("approved");
        assertThat(value("SELECT base_qty-shipped_base_qty-pending_ship_base_qty-closed_base_qty FROM sales_order_line WHERE id=?",source)).isEqualByComparingTo("1");
        long secondLine=lineId(second);
        service.resolve(scope,second,key(),new ShipmentModels.Resolution(version(second),DAY,List.of(new ShipmentModels.ResolveLine(secondLine,"returned","客户拒收，原仓待检"))));
        assertThat(inventory.balance(scope,new Key(warehouse,skus.getFirst())).blocked()).isEqualByComparingTo("1");
        long replacement=ship(source,"1","pickup");
        assertThat(queries.get(scope,replacement).get("status")).isEqualTo("signed");
        assertThat(queries.get(scope,replacement).get("receivableAmount")).isEqualTo("100.00");
        assertThat(value("SELECT recognized_amount FROM sales_order_line WHERE id=?",source)).isEqualByComparingTo("1000");
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=? AND direction='D'",tenant)).isEqualByComparingTo("600");
        assertThat(value("SELECT SUM(IF(direction='D',amount,-amount)) FROM journal_line WHERE tenant_id=? AND account_code='GOODS_SHIPPED'",tenant)).isZero();
        assertZeroDifferences();
    }

    @Test
    void SO4ThreeBatchesUseCumulativeRoundingAndRecognizeBuyerFeeExactlyOnce() {
        var lines=so4();prepayment("300");
        long first=ship(lines.get(1),"3","delivery");
        var firstSigned=sign(first,"3");
        assertThat(firstSigned.get("receivableAmount")).isEqualTo("167.50");
        assertThat(firstSigned.get("taxAmount")).isEqualTo("19.27");
        assertThat(onlyLine(firstSigned).get("receivableAmount")).isEqualTo("137.50");
        assertThat(onlyLine(firstSigned).get("taxAmount")).isEqualTo("15.82");
        assertThat((List<?>)firstSigned.get("recognizedFees")).hasSize(1);
        long second=ship(lines.get(1),"3","delivery");
        var secondSigned=sign(second,"3");
        assertThat(secondSigned.get("receivableAmount")).isEqualTo("137.50");
        assertThat(secondSigned.get("taxAmount")).isEqualTo("15.82");
        long third=ship(lines.get(1),"2","delivery");
        var thirdSigned=sign(third,"2");
        assertThat(thirdSigned.get("receivableAmount")).isEqualTo("91.67");
        assertThat(thirdSigned.get("taxAmount")).isEqualTo("10.54");
        ship(lines.get(0),"4","pickup");ship(lines.get(2),"2","pickup");
        assertThat(value("SELECT SUM(amount) FROM receivable WHERE tenant_id=?",tenant)).isEqualByComparingTo("1130");
        assertThat(value("SELECT SUM(tax_amount) FROM receivable WHERE tenant_id=?",tenant)).isEqualByComparingTo("129.99");
        assertThat(value("SELECT SUM(open_amount) FROM partner_open_item WHERE tenant_id=? AND direction='D'",tenant)).isEqualByComparingTo("830");
        assertZeroDifferences();
    }

    @Test
    void MultiLineDifferencesCanBeResolvedSeparatelyWithoutChangingReceivable() {
        var sources=so4();
        long shipment=create(List.of(new ShipmentModels.InputLine(sources.get(0),unit,"2"),new ShipmentModels.InputLine(sources.get(1),unit,"3")),"delivery");
        service.submit(scope,shipment,key(),version(shipment));service.post(scope,shipment,key(),version(shipment));
        var rows=queries.lines(scope,shipment,false);
        service.sign(scope,shipment,key(),new ShipmentModels.Signed(version(shipment),DAY,List.of(
                new ShipmentModels.SignedLine(id(rows.get(0)),"1"),new ShipmentModels.SignedLine(id(rows.get(1)),"2")),List.of()));
        BigDecimal recognized=value("SELECT receivable_amount FROM shipment WHERE id=?",shipment);
        var partial=service.resolve(scope,shipment,key(),new ShipmentModels.Resolution(version(shipment),DAY,List.of(
                new ShipmentModels.ResolveLine(id(rows.get(0)),"returned","外箱破损，退回待检"))));
        assertThat(partial.get("status")).isEqualTo("signed_with_diff");
        var complete=service.resolve(scope,shipment,key(),new ShipmentModels.Resolution(version(shipment),DAY,List.of(
                new ShipmentModels.ResolveLine(id(rows.get(1)),"lost","承运方确认运输丢失"))));
        assertThat(complete.get("status")).isEqualTo("signed");
        assertThat(value("SELECT receivable_amount FROM shipment WHERE id=?",shipment)).isEqualByComparingTo(recognized);
        assertThat(value("SELECT SUM(amount) FROM journal_line WHERE tenant_id=? AND account_code='INVENTORY_ADJUSTMENT'",tenant)).isEqualByComparingTo("10");
        assertZeroDifferences();
    }

    @Test
    void OccupancyVersionIdempotencyAndWarehouseVisibilityRemainServerSide() {
        var sources=so4();long source=sources.get(0);
        long first=create(List.of(new ShipmentModels.InputLine(source,unit,"4")),"delivery");
        String submitKey=key();var submitted=service.submit(scope,first,submitKey,0);
        assertThat(service.submit(scope,first,submitKey,0).get("version").toString()).isEqualTo(submitted.get("version").toString());
        long competing=create(List.of(new ShipmentModels.InputLine(source,unit,"1")),"delivery");
        assertCode(()->service.submit(scope,competing,key(),0),"SHIP_QTY_EXCEEDED");
        assertCode(()->service.post(scope,first,key(),0),"VERSION_CONFLICT");
        service.voidSubmitted(scope,first,key(),version(first));
        service.submit(scope,competing,key(),0);
        var noWarehouse=new CompanyAccess(tenant,scope.userId(),company,false,Set.of(),scope.permissions());
        assertCode(()->service.submit(noWarehouse,first,submitKey,0),"NOT_FOUND");
        var noCost=new CompanyAccess(tenant,scope.userId(),company,true,Set.of(),Set.of("shipment:post","shipment:sign"));
        String postKey=key();long submittedVersion=version(competing);
        var posted=service.post(scope,competing,postKey,submittedVersion);
        assertThat(posted).doesNotContainKeys("costAmount","signedCostAmount");
        assertThat(onlyLine(posted)).doesNotContainKeys("costAmount","unitCost");
        assertThat(service.post(noCost,competing,postKey,submittedVersion)).doesNotContainKey("costAmount");
        assertThat(queries.get(scope,competing)).containsKey("costAmount");
        assertThat(queries.get(noCost,competing)).doesNotContainKey("costAmount");
        assertCode(()->sign(competing,"2"),"SIGN_QTY_EXCEEDED");
        var signed=sign(competing,"1");
        assertThat(signed.get("status")).isEqualTo("signed");
        assertCode(()->sign(competing,"1"),"STATE_CONFLICT");
        var foreign=new CompanyAccess(tenant,scope.userId(),Ids.next(),true,Set.of(),scope.permissions());
        assertCode(()->queries.get(foreign,competing),"NOT_FOUND");
        assertZeroDifferences();
    }

    @Test
    void B02ClosingEveryUnshippedLineDoesNotRecognizeBuyerFreight() {
        var sources=so4();
        long orderVersion=jdbc.queryForObject("SELECT version FROM sales_order WHERE id=?",Long.class,order);
        orders.close(scope,order,key(),new SalesOrderModels.Close(orderVersion,"客户取消，关闭所有未发数量",List.of(
                new SalesOrderModels.CloseLine(sources.get(0).toString(),"4"),
                new SalesOrderModels.CloseLine(sources.get(1).toString(),"8"),
                new SalesOrderModels.CloseLine(sources.get(2).toString(),"2"))));
        assertThat(jdbc.queryForObject("SELECT status FROM sales_order WHERE id=?",String.class,order)).isEqualTo("closed");
        assertThat(value("SELECT SUM(signed_base_qty) FROM sales_order_line WHERE order_id=?",order)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales_order_fee WHERE order_id=? AND recognized_shipment_id IS NOT NULL",Long.class,order)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal_line WHERE tenant_id=? AND account_code='FEE_INCOME'",Long.class,tenant)).isZero();
        assertZeroDifferences();
    }

    @Test
    void B26SigningBeforeShipmentOrInClosedPeriodLeavesEveryProjectionUnchanged() {
        var sources=so4();long shipment=ship(sources.get(0),"1","delivery");
        long version=version(shipment),line=lineId(shipment);
        assertCode(()->service.sign(scope,shipment,key(),new ShipmentModels.Signed(version,DAY.minusDays(1),
                List.of(new ShipmentModels.SignedLine(line,"1")),List.of())),"SIGN_DATE_INVALID");
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->service.sign(scope,shipment,key(),new ShipmentModels.Signed(version,DAY,
                List.of(new ShipmentModels.SignedLine(line,"1")),List.of())),"PERIOD_CLOSED");
        assertThat(queries.get(scope,shipment).get("status")).isEqualTo("posted");
        assertThat(version(shipment)).isEqualTo(version);
        assertThat(value("SELECT signed_base_qty FROM shipment_line WHERE id=?",line)).isZero();
        assertThat(value("SELECT recognized_amount FROM sales_order_line WHERE id=?",sources.get(0))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?",Long.class,tenant)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='shipment_sign'",Long.class,tenant)).isZero();
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        assertThat(sign(shipment,"1").get("status")).isEqualTo("signed");
        assertZeroDifferences();
    }

    @Test
    void B29OverdueListIncludesOnlyUnsignedShipmentsOlderThanSevenShanghaiDays() {
        var sources=so4();long source=sources.get(0);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Shanghai")),old=today.minusDays(8);
        jdbc.update("UPDATE sales_order SET business_date=? WHERE id=?",old,order);
        long overdue=shipAt(source,old),boundary=shipAt(source,today.minusDays(7)),recent=shipAt(source,today);
        long signed=shipAt(source,old);
        service.sign(scope,signed,key(),new ShipmentModels.Signed(version(signed),today,
                List.of(new ShipmentModels.SignedLine(lineId(signed),"1")),List.of()));
        long draft=createAt(source,old);
        var reminder=queries.list(scope,"posted",true,customer,null,20);
        assertThat(documentIds(reminder)).containsExactly(Long.toString(overdue));
        assertThat(documentIds(queries.list(scope,"posted",false,customer,null,20)))
                .containsExactlyInAnyOrder(Long.toString(overdue),Long.toString(boundary),Long.toString(recent))
                .doesNotContain(Long.toString(signed),Long.toString(draft));
        var noWarehouse=new CompanyAccess(tenant,scope.userId(),company,false,Set.of(),scope.permissions());
        assertThat(documentIds(queries.list(noWarehouse,"posted",true,customer,null,20))).isEmpty();
        assertThat(documentIds(queries.list(scope,"posted",true,Ids.next(),null,20))).isEmpty();
        assertZeroDifferences();
    }

    @Test
    void LongShipmentSplitsBalancedJournalsWithinTheLedgerLineLimit() {
        approvedOrder("SO-LONG","251","0","0","251","251","0");
        long opening=Ids.next();
        inventory.receive(scope,new Posting(new Key(warehouse,skus.getFirst()),new Source("opening_entry",opening,opening),DAY,period,"opening_in"),d("250"),d("2500"));
        ledger.post(scope,new LedgerService.Posting("opening_entry",opening,0,period,DAY,List.of(
                entry("INVENTORY","D","2500",null,null,warehouse,skus.getFirst()),entry("OPENING_EQUITY","C","2500",null,null,null,null))));
        var input=new ArrayList<ShipmentModels.InputLine>();
        for(int number=1;number<=251;number++) {input.add(new ShipmentModels.InputLine(line(number,0,"1","1","0","0","1","0"),unit,"1"));}
        long shipment=create(input,"delivery");
        service.submit(scope,shipment,key(),version(shipment));service.post(scope,shipment,key(),version(shipment));
        var signed=queries.lines(scope,shipment,false).stream().map(row->new ShipmentModels.SignedLine(id(row),"1")).toList();
        service.sign(scope,shipment,key(),new ShipmentModels.Signed(version(shipment),DAY,signed,List.of()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM journal WHERE tenant_id=? AND source_type='shipment_sign' AND source_id=?",Long.class,tenant,shipment)).isEqualTo(2);
        assertThat(queries.get(scope,shipment).get("receivableAmount")).isEqualTo("251.00");
        assertZeroDifferences();
    }

    @Test
    void ExistingSubmittedShipmentCanPostDuringOrderChangeReview() {
        var sources=so4();long shipment=create(List.of(new ShipmentModels.InputLine(sources.get(0),unit,"1")),"delivery");
        service.submit(scope,shipment,key(),version(shipment));
        jdbc.update("UPDATE sales_order SET status='change_pending' WHERE id=?",order);
        service.post(scope,shipment,key(),version(shipment));
        assertThat(queries.get(scope,shipment).get("status")).isEqualTo("posted");
        assertCode(()->create(List.of(new ShipmentModels.InputLine(sources.get(0),unit,"1")),"delivery"),"CHANGE_PENDING");
        sign(shipment,"1");assertZeroDifferences();
    }

    @Test
    void FullRejectionCreatesNoReceivableAndClosedPeriodRollsBackPosting() {
        var sources=so4();long shipment=create(List.of(new ShipmentModels.InputLine(sources.get(0),unit,"1")),"delivery");
        service.submit(scope,shipment,key(),version(shipment));
        jdbc.update("UPDATE accounting_period SET status='closed',closed_at=UTC_TIMESTAMP(3) WHERE id=?",period);
        assertCode(()->service.post(scope,shipment,key(),version(shipment)),"PERIOD_CLOSED");
        assertThat(queries.get(scope,shipment).get("status")).isEqualTo("submitted");
        jdbc.update("UPDATE accounting_period SET status='open',closed_at=NULL WHERE id=?",period);
        service.post(scope,shipment,key(),version(shipment));
        assertThat(sign(shipment,"0").get("receivableAmount")).isEqualTo("0.00");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM receivable WHERE tenant_id=?",Long.class,tenant)).isZero();
        service.resolve(scope,shipment,key(),new ShipmentModels.Resolution(version(shipment),DAY,List.of(new ShipmentModels.ResolveLine(lineId(shipment),"lost","运输丢失"))));
        assertZeroDifferences();
    }

    private List<Long> so4() {
        approvedOrder("SO-4","1320","120","100","1130","1000.01","129.99");
        jdbc.update("UPDATE sales_order SET fee_amount=30 WHERE id=?",order);
        long a=line(1,0,"4","115","60","33.34","366.66","42.18");
        long b=line(2,1,"8","55","40","33.33","366.67","42.18");
        long c=line(3,2,"2","210","20","33.33","366.67","42.18");
        jdbc.update("INSERT INTO sales_order_fee(id,tenant_id,company_id,order_id,fee_type,bearer,amount,tax_rate,untaxed_amount,tax_amount) VALUES(?,?,?,?,'freight','buyer',30,.13,26.55,3.45)",Ids.next(),tenant,company,order);
        reserve(a,0,"4");reserve(b,1,"8");reserve(c,2,"2");return List.of(a,b,c);
    }

    private void approvedOrder(String number,String goods,String discount,String orderDiscount,String total,String untaxed,String tax) {
        BigDecimal fee=d(total).subtract(d(goods)).add(d(discount)).add(d(orderDiscount));
        jdbc.update("""
                INSERT INTO sales_order(id,tenant_id,company_id,order_no,customer_id,business_date,status,revision,approved_at,
                price_tax_inclusive,pricing_version,goods_amount,line_discount_amount,order_discount_amount,fee_amount,total_amount,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,?,?,'approved',1,UTC_TIMESTAMP(3),1,2,?,?,?,?,?,?,?)
                """,order,tenant,company,number,customer,DAY,d(goods),d(discount),d(orderDiscount),fee,d(total),d(untaxed),d(tax));
    }

    private long line(int number,int sku,String qty,String price,String discount,String alloc,String net,String tax) {
        long id=Ids.next();BigDecimal amount=d(qty).multiply(d(price)).setScale(2);
        jdbc.update("""
                INSERT INTO sales_order_line(id,tenant_id,company_id,order_id,line_no,sku_id,warehouse_id,unit_id,unit_rate,qty,base_qty,
                unit_price,amount,line_discount,line_net,order_discount_alloc,receivable_net,tax_rate,untaxed_amount,tax_amount)
                VALUES(?,?,?,?,?,?,?,?,1,?,?,?,?,?,?,?,?,?,?,?)
                """,id,tenant,company,order,number,skus.get(sku),warehouse,unit,d(qty),d(qty),d(price),amount,d(discount),amount.subtract(d(discount)),d(alloc),d(net),d(tax).signum()==0?BigDecimal.ZERO:d(".13"),d(net).subtract(d(tax)),d(tax));
        return id;
    }

    private void reserve(long line,int sku,String qty) {
        reservations.reserve(scope,List.of(new ReservationRequest(line,new Key(warehouse,skus.get(sku)),d(qty),Instant.now().plusSeconds(86400))));
    }

    private long prepayment(String amount) {
        var created=receipts.create(scope,key(),new FinanceModels.Receipt("customer",customer,null,account,amount,DAY,order,null,null,null));
        long receipt=Long.parseLong(created.get("id").toString());
        receipts.command(scope,receipt,"submit",key(),new FinanceModels.Version(0L,null,null));
        var confirmer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),scope.permissions());
        receipts.command(confirmer,receipt,"confirm",key(),new FinanceModels.Version(1L,null,null));
        return jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND company_id=? AND item_type='receipt' AND source_id=?",Long.class,tenant,company,receipt);
    }

    private long create(List<ShipmentModels.InputLine> lines,String delivery) {
        return Long.parseLong(service.create(scope,key(),new ShipmentModels.Input(customer,warehouse,DAY,delivery,null,lines,null)).get("id").toString());
    }

    private long createAt(long source,LocalDate day) {
        YearMonth month=YearMonth.from(day);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_period WHERE tenant_id=? AND company_id=? AND start_date<=? AND end_date>=?",Long.class,tenant,company,day,day)==0) {
            jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                    Ids.next(),tenant,company,month.format(java.time.format.DateTimeFormatter.ofPattern("uuuuMM")),month.atDay(1),month.atEndOfMonth());
        }
        return Long.parseLong(service.create(scope,key(),new ShipmentModels.Input(customer,warehouse,day,"delivery",null,
                List.of(new ShipmentModels.InputLine(source,unit,"1")),null)).get("id").toString());
    }

    private long shipAt(long source,LocalDate day) {
        long shipment=createAt(source,day);
        service.submit(scope,shipment,key(),version(shipment));service.post(scope,shipment,key(),version(shipment));return shipment;
    }

    private long ship(long source,String qty,String delivery) {
        long shipment=create(List.of(new ShipmentModels.InputLine(source,unit,qty)),delivery);
        service.submit(scope,shipment,key(),version(shipment));service.post(scope,shipment,key(),version(shipment));return shipment;
    }

    private Map<String,Object> sign(long shipment,String qty) {
        return service.sign(scope,shipment,key(),new ShipmentModels.Signed(version(shipment),DAY,List.of(new ShipmentModels.SignedLine(lineId(shipment),qty)),List.of()));
    }

    private long lineId(long shipment) {return id(queries.lines(scope,shipment,false).getFirst());}
    private long version(long shipment) {return ((Number)queries.header(scope,shipment,false).get("version")).longValue();}
    private long id(Map<String,Object> row) {return ((Number)row.get("id")).longValue();}
    private String key() {return UUID.randomUUID().toString();}
    private BigDecimal value(String sql,Object... args) {return jdbc.queryForObject(sql,BigDecimal.class,args);}
    private static BigDecimal d(String value) {return new BigDecimal(value);}
    private LedgerService.Line entry(String code,String direction,String amount,Long partner,Long cash,Long wh,Long sku) {
        return new LedgerService.Line(code,direction,d(amount),partner,cash,wh,sku,null,null);
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> onlyLine(Map<String,Object> document) {return ((List<Map<String,Object>>)document.get("lines")).getFirst();}
    @SuppressWarnings("unchecked")
    private List<String> documentIds(Map<String,Object> page) {return ((List<Map<String,Object>>)page.get("items")).stream().map(row->row.get("id").toString()).toList();}
    private void assertCode(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));
    }
    private void assertZeroDifferences() {
        for(String type:ReconciliationChecks.TYPES) {assertThat(checks.check(scope,type,type.equals("period_close")?period:null)).as(type).isEmpty();}
    }
}
