package cn.erp.reporting;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.*;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class ReportIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired ReportService reports;
    @Autowired ReportReadDatabase database;
    @Autowired StringRedisTemplate redis;
    @Autowired ObjectMapper json;
    private static final LocalDate FROM=LocalDate.of(2026,11,2),TO=LocalDate.of(2026,11,15);
    private final Set<String> permissions=new HashSet<>();
    private TradeSummaryService trade(String time) {
        return new TradeSummaryService(database,redis,json,"test:trade:"+UUID.randomUUID(),Clock.fixed(Instant.parse(time),ZoneOffset.UTC));
    }
    @Test void tradeSummaryMatchesReportsAndFillsTwelveMonths() {
        var result=trade("2026-11-06T01:00:00Z").summary(actor(),12);
        assertThat(result).containsEntry("dataSource","primary").containsKeys("asOf","sales","purchase");
        assertThat(result.get("sales")).isEqualTo(Map.of("today","100.00","yesterday","-100.00","month","300.00","year","300.00"));
        assertThat(result.get("purchase")).isEqualTo(Map.of("today","0.00","yesterday","-200.00","month","800.00","year","800.00"));
        @SuppressWarnings("unchecked") var trend=(List<Map<String,Object>>)result.get("trend");
        assertThat(trend).hasSize(12);
        assertThat(trend.getFirst()).containsEntry("month","2025-12").containsEntry("sales","0.00");
        assertThat(trend.getLast()).containsEntry("month","2026-11").containsEntry("sales","300.00").containsEntry("purchase","800.00");
        assertThat(result.get("sales")).isInstanceOfSatisfying(Map.class,amounts->
                assertThat(amounts.get("month")).isEqualTo(reports.snapshot(actor(),ReportType.SALES_SUMMARY,new ReportQuery(FROM,TO,null,null,null,null,false)).summary().get("amount")));
    }
    @Test void tradeCacheSeparatesWarehouseCompanyAndRevokedPermissions() {
        var service=trade("2026-11-06T01:00:00Z");
        var first=service.summary(actor(),12);
        jdbc.update("UPDATE shipment_line SET receivable_amount=900,untaxed_amount=870 WHERE id=71601");
        assertThat(service.summary(actor(),12)).containsEntry("sales",first.get("sales"));
        var restricted=new CompanyAccess(actor().tenantId(),actor().userId(),actor().companyId(),false,Set.of(),permissions);
        assertThat(service.summary(restricted,12).get("sales")).isEqualTo(Map.of("today","0.00","yesterday","0.00","month","0.00","year","0.00"));
        var onlySales=new CompanyAccess(actor().tenantId(),actor().userId(),actor().companyId(),true,Set.of(),Set.of("report:sales_summary"));
        assertThat(service.summary(onlySales,12)).doesNotContainKey("purchase");
        assertThat(service.summary(new CompanyAccess(actor().tenantId(),actor().userId(),70202,true,Set.of(),permissions),12).get("sales"))
                .isEqualTo(Map.of("today","0.00","yesterday","0.00","month","0.00","year","0.00"));
        var denied=new CompanyAccess(actor().tenantId(),actor().userId(),actor().companyId(),true,Set.of(),Set.of());
        assertThatThrownBy(()->service.summary(denied,12)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getStatus()).isEqualTo(403));
        assertThatThrownBy(()->service.summary(actor(),13)).isInstanceOf(ApiException.class);
    }
    @Test void tradeDatesUseBeijingDayAndHandleNewYear() {
        jdbc.update("UPDATE shipment SET sign_date='2026-12-31' WHERE id=71501");
        var beforeMidnight=trade("2026-12-31T15:59:59Z").summary(actor(),1);
        assertThat(beforeMidnight.get("sales")).isEqualTo(Map.of("today","300.00","yesterday","0.00","month","300.00","year","300.00"));
        var afterMidnight=trade("2026-12-31T16:00:00Z").summary(actor(),12);
        assertThat(afterMidnight.get("sales")).isEqualTo(Map.of("today","0.00","yesterday","300.00","month","0.00","year","0.00"));
    }
    @BeforeEach void facts() {
        for(var type:ReportType.values()) { permissions.add(type.permission());permissions.add(type.exportPermission()); }permissions.add("cost:view");
        jdbc.update("UPDATE inventory_movement SET movement_type='opening_in',on_hand_delta=10,blocked_delta=1,cost_delta=100,business_date='2026-11-01' WHERE id=72301");
        jdbc.update("UPDATE inventory_cost SET total_cost=102 WHERE id=72001");
        jdbc.update("INSERT INTO cost_adjustment(id,tenant_id,company_id,warehouse_id,sku_id,adjust_type,amount,source_type,source_id,source_line_id,business_date,period_id) VALUES(75101,70101,70201,70301,71001,'revaluation',2,'stock_adjustment',75102,75103,'2026-11-03',70601)");
        jdbc.update("UPDATE shipment SET business_date='2026-11-03',status='signed_with_diff',posted_at=UTC_TIMESTAMP(3),period_id=70601,sign_date='2026-11-04',sign_period_id=70601,signed_at=UTC_TIMESTAMP(3),cost_amount=40,signed_cost_amount=30,receivable_amount=300,untaxed_amount=270,tax_amount=30 WHERE id=71501");
        jdbc.update("UPDATE shipment_line SET unit_cost=10,cost_amount=40,signed_base_qty=3,diff_base_qty=1,diff_disposition='pending',signed_cost_amount=30,diff_cost_amount=10,receivable_amount=300,untaxed_amount=270,tax_amount=30 WHERE id=71601");
        jdbc.update("INSERT INTO sales_return(id,tenant_id,company_id,return_no,customer_id,warehouse_id,business_date,status,credit_amount,untaxed_amount,tax_amount,cost_amount,period_id,posted_at) VALUES(75201,70101,70201,'SR1',70401,70301,'2026-11-05','reversed',100,90,10,10,70601,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO sales_return_line(id,tenant_id,company_id,return_id,line_no,shipment_line_id,sku_id,unit_id,unit_rate,qty,base_qty,quality_result,unit_cost,cost_amount,credit_amount,untaxed_amount,tax_amount) VALUES(75202,70101,70201,75201,1,71601,71001,70701,1,1,1,'passed',10,10,100,90,10)");
        reversal(75203,"sales_return",75201,"2026-11-06");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,payable_amount,untaxed_amount,tax_amount,cost_amount,period_id,posted_at) VALUES(75301,70101,70201,'GR1',70401,70301,'2026-11-04','posted',1000,1000,0,100,70601,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO goods_receipt_line(id,tenant_id,company_id,receipt_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,payable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount) VALUES(75302,70101,70201,75301,1,71801,71001,70701,1,10,10,1000,1000,0,10,100)");
        jdbc.update("INSERT INTO purchase_return(id,tenant_id,company_id,return_no,supplier_id,warehouse_id,business_date,status,debit_amount,untaxed_amount,tax_amount,cost_amount,period_id,posted_at) VALUES(75401,70101,70201,'PR1',70401,70301,'2026-11-05','posted',200,200,0,20,70601,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO purchase_return_line(id,tenant_id,company_id,return_id,line_no,receipt_line_id,sku_id,unit_id,unit_rate,qty,base_qty,unit_cost,cost_amount,cost_adjust_amount,debit_amount,untaxed_amount,tax_amount) VALUES(75402,70101,70201,75401,1,75302,71001,70701,1,2,2,10,20,0,200,200,0)");
        jdbc.update("UPDATE partner_open_item SET open_amount=90,aging_date='2026-10-01' WHERE id=72701");
        jdbc.update("UPDATE journal SET business_date='2026-11-01' WHERE id=73001");
        jdbc.update("INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,amount,partner_id,cash_account_id) VALUES(75501,70101,70201,73001,1,'FUNDS','D',100,NULL,70501),(75502,70101,70201,73001,2,'AR','C',100,70401,NULL)");
        jdbc.update("INSERT INTO journal(id,tenant_id,company_id,period_id,business_date,source_type,source_id,total_debit,total_credit,posted_at) VALUES(75601,70101,70201,70601,'2026-11-10','payment',75603,20,20,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO journal_line(id,tenant_id,company_id,journal_id,line_no,account_code,direction,amount,partner_id,cash_account_id) VALUES(75602,70101,70201,75601,1,'FUNDS','C',20,NULL,70501),(75604,70101,70201,75601,2,'AP','D',20,70401,NULL)");
    }
    @Test void inventoryFlowIncludesCostAdjustmentsAndAgreesWithCurrentSnapshot() {
        var flow=snapshot(ReportType.INVENTORY_FLOW);assertNumber(flow.summary(),"openingQty","10");assertNumber(flow.summary(),"closingQty","10");assertNumber(flow.summary(),"openingCost","100");assertNumber(flow.summary(),"inCost","2");assertNumber(flow.summary(),"closingCost","102");
        var stock=snapshot(ReportType.INVENTORY_SUMMARY);assertNumber(stock.summary(),"onHandQty","10");assertNumber(stock.summary(),"availableQty","5");assertNumber(stock.summary(),"costAmount","102");
        assertThat(stock.items().getFirst()).containsEntry("warehouseName","测试仓库").containsEntry("skuCode","SKU-A").containsKey("detailUrl");
        var details=reports.snapshot(actor(),ReportType.INVENTORY_FLOW,new ReportQuery(FROM,TO,null,null,null,null,true));
        assertThat(details.items()).hasSize(1);assertThat(details.items().getFirst().get("costAmount")).isEqualTo("2.00");
    }
    @Test void salesRecognitionReturnsAndTheirReversalUseOriginalCostExactlyOnce() {
        var sale=snapshot(ReportType.SALES_SUMMARY);assertThat(sale.items()).hasSize(3);assertNumber(sale.summary(),"qty","3");assertNumber(sale.summary(),"amount","300");assertNumber(sale.summary(),"costAmount","30");
        var margin=snapshot(ReportType.GROSS_MARGIN);assertNumber(margin.summary(),"grossMargin","240");
        jdbc.update("UPDATE inventory_cost SET total_cost=99999 WHERE id=72001");assertNumber(snapshot(ReportType.GROSS_MARGIN).summary(),"grossMargin","240");
        assertThat(sale.items()).allSatisfy(row->assertThat(row).containsKeys("detailUrl","partnerName","skuName"));
        assertThat(reports.snapshot(actor(),ReportType.SALES_SUMMARY,new ReportQuery(FROM,TO,null,null,null,null,true)).items()).allSatisfy(row->assertThat(row).containsKeys("sourceId","sourceUrl"));
        reversal(75701,"shipment",71501,"2026-11-07");jdbc.update("UPDATE shipment SET status='reversed' WHERE id=71501");
        assertNumber(snapshot(ReportType.SALES_SUMMARY).summary(),"amount","0");assertNumber(snapshot(ReportType.GROSS_MARGIN).summary(),"grossMargin","0");
    }
    @Test void purchasesSubtractReturnsAndRestoreWhenTheReturnIsReversed() {
        assertNumber(snapshot(ReportType.PURCHASE_SUMMARY).summary(),"qty","8");assertNumber(snapshot(ReportType.PURCHASE_SUMMARY).summary(),"amount","800");
        jdbc.update("UPDATE purchase_return SET status='reversed' WHERE id=75401");reversal(75702,"purchase_return",75401,"2026-11-08");
        assertNumber(snapshot(ReportType.PURCHASE_SUMMARY).summary(),"qty","10");assertNumber(snapshot(ReportType.PURCHASE_SUMMARY).summary(),"amount","1000");
    }
    @Test void summariesGroupMultipleDocumentsByPartnerSkuAndDayWhileDetailRetainsEachSource() {
        jdbc.update("INSERT INTO shipment(id,tenant_id,company_id,shipment_no,customer_id,warehouse_id,business_date,status,receivable_amount,untaxed_amount,tax_amount,cost_amount,signed_cost_amount,period_id,posted_at,sign_date,sign_period_id,signed_at) VALUES(75881,70101,70201,'S2',70401,70301,'2026-11-03','signed',200,180,20,20,20,70601,UTC_TIMESTAMP(3),'2026-11-04',70601,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO shipment_line(id,tenant_id,company_id,shipment_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,unit_cost,cost_amount,signed_base_qty,receivable_amount,untaxed_amount,tax_amount,signed_cost_amount,diff_cost_amount) VALUES(75882,70101,70201,75881,1,71301,71001,70701,1,2,2,10,20,2,200,180,20,20,0)");
        var day=LocalDate.of(2026,11,4);var grouped=reports.snapshot(actor(),ReportType.SALES_SUMMARY,new ReportQuery(day,day,null,71001L,70401L,null,false));
        assertThat(grouped.items()).hasSize(1);assertNumber(grouped.items().getFirst(),"qty","5");assertNumber(grouped.items().getFirst(),"amount","500");
        assertThat(((Number)grouped.items().getFirst().get("sourceCount")).longValue()).isEqualTo(2L);
        assertThat(grouped.items().getFirst().get("detailUrl").toString()).contains("detail=true","skuId=71001","dateFrom=2026-11-04");
        var detail=reports.snapshot(actor(),ReportType.SALES_SUMMARY,new ReportQuery(day,day,null,71001L,70401L,null,true));assertThat(detail.items()).hasSize(2);
        assertNumber(detail.summary(),"amount","500");assertNumber(grouped.summary(),"costAmount","50");
        jdbc.update("INSERT INTO goods_receipt(id,tenant_id,company_id,receipt_no,supplier_id,warehouse_id,business_date,status,payable_amount,untaxed_amount,tax_amount,cost_amount,period_id,posted_at) VALUES(75883,70101,70201,'GR2',70401,70301,'2026-11-04','posted',500,500,0,50,70601,UTC_TIMESTAMP(3))");
        jdbc.update("INSERT INTO goods_receipt_line(id,tenant_id,company_id,receipt_id,line_no,order_line_id,sku_id,unit_id,unit_rate,qty,base_qty,payable_amount,untaxed_amount,tax_amount,unit_cost,cost_amount) VALUES(75884,70101,70201,75883,1,71801,71001,70701,1,5,5,500,500,0,10,50)");
        var purchases=reports.snapshot(actor(),ReportType.PURCHASE_SUMMARY,new ReportQuery(day,day,null,71001L,70401L,null,false));assertThat(purchases.items()).hasSize(1);assertNumber(purchases.summary(),"qty","15");assertNumber(purchases.summary(),"amount","1500");
        assertThat(reports.snapshot(actor(),ReportType.PURCHASE_SUMMARY,new ReportQuery(day,day,null,71001L,70401L,null,true)).items()).hasSize(2);
    }
    @Test void unsignedSnapshotIncludesPendingDifferenceAndExcludesResolvedAndReversedShipments() {
        assertNumber(snapshot(ReportType.UNSIGNED_SHIPMENTS).summary(),"unsignedQty","1");assertNumber(snapshot(ReportType.UNSIGNED_SHIPMENTS).summary(),"costAmount","10");
        for(String disposition:List.of("returned","lost")) { jdbc.update("UPDATE shipment_line SET diff_disposition=? WHERE id=71601",disposition);assertThat(snapshot(ReportType.UNSIGNED_SHIPMENTS).items()).isEmpty(); }
        jdbc.update("UPDATE shipment_line SET diff_disposition='pending' WHERE id=71601");jdbc.update("UPDATE shipment SET status='reversed' WHERE id=71501");assertThat(snapshot(ReportType.UNSIGNED_SHIPMENTS).items()).isEmpty();
    }
    @Test void agingUsesRealOpenAmountAndShanghaiCalendarAndFinancialLedgersCarryOpeningBalances() {
        var age=snapshot(ReportType.RECEIVABLE_AGING);assertNumber(age.summary(),"openAmount","90");assertNumber(age.summary(),"age3160","90");
        assertThat(snapshot(ReportType.PAYABLE_AGING).items()).isEmpty();
        var cash=snapshot(ReportType.CASH_JOURNAL);assertNumber(cash.summary(),"openingBalance","100");assertNumber(cash.summary(),"paymentAmount","20");assertNumber(cash.summary(),"closingBalance","80");
        var statement=snapshot(ReportType.PARTNER_STATEMENT);assertNumber(statement.summary(),"openingBalance","-100");assertNumber(statement.summary(),"debitAmount","20");assertNumber(statement.summary(),"closingBalance","-80");
    }
    @Test void permissionsScopeCostVisibilityPaginationAndDateLimitsAreEnforced() {
        var noCost=new HashSet<>(permissions);noCost.remove("cost:view");var actor=new CompanyAccess(70101,1,70201,false,Set.of(70301L),noCost);
        assertThat(reports.snapshot(actor,ReportType.SALES_SUMMARY,query()).items()).allSatisfy(row->assertThat(row.keySet()).noneMatch(k->k.toLowerCase().contains("cost")||k.toLowerCase().contains("margin")));
        assertThatThrownBy(()->reports.snapshot(actor,ReportType.GROSS_MARGIN,query())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.snapshot(actor,ReportType.CASH_JOURNAL,query())).isInstanceOf(ApiException.class);
        assertThat(reports.snapshot(new CompanyAccess(70101,1,70201,false,Set.of(),permissions),ReportType.SALES_SUMMARY,query()).items()).isEmpty();
        assertThat(reports.snapshot(new CompanyAccess(70101,1,70202,true,Set.of(),permissions),ReportType.SALES_SUMMARY,query()).items()).isEmpty();
        var page=reports.page(actor(),ReportType.SALES_SUMMARY,query(),null,1);assertThat(page.get("nextCursor")).isEqualTo("1");
        assertThat(reports.page(actor(),ReportType.SALES_SUMMARY,query(),1L,1).get("items")).isNotEqualTo(page.get("items"));
        assertThatThrownBy(()->reports.snapshot(actor(),ReportType.SALES_SUMMARY,new ReportQuery(TO.minusYears(2),TO,null,null,null,null,false))).isInstanceOf(ApiException.class);
    }
    private void reversal(long id,String type,long source,String day) {
        jdbc.update("INSERT INTO document_reversal(id,tenant_id,company_id,reversal_no,source_type,source_id,business_date,reason,status,period_id,posted_at) VALUES(?,70101,70201,?,?,?,?,'报表冲销验收','posted',70601,UTC_TIMESTAMP(3))",id,"REV"+id,type,source,day);
    }
    private CompanyAccess actor() { return new CompanyAccess(70101,1,70201,true,Set.of(),permissions); }
    private ReportQuery query() { return new ReportQuery(FROM,TO,null,null,null,null,false); }
    private ReportService.Snapshot snapshot(ReportType type) { return reports.snapshot(actor(),type,query()); }
    private static void assertNumber(Map<String,Object> map,String key,String value) { assertThat(new BigDecimal(map.get(key).toString())).isEqualByComparingTo(value); }
}
