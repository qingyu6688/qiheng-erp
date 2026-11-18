package cn.erp.mfg;

import cn.erp.ErpApplication;
import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryModels.Key;
import cn.erp.inventory.InventoryModels.Posting;
import cn.erp.inventory.InventoryModels.Source;
import cn.erp.inventory.InventoryService;
import cn.erp.period.PeriodService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.OrgScope;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.transaction.annotation.Transactional;

import static cn.erp.mfg.MfgPlanningModels.*;
import static cn.erp.mfg.MfgStockModels.Type.ISSUE;
import static cn.erp.mfg.MfgStockModels.Type.RECEIPT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class MfgCostIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired MfgBomService boms;
    @Autowired MfgWorkOrderService orders;
    @Autowired MfgShortClose shortClose;
    @Autowired MfgStockReversal reversal;
    @Autowired MfgStockDocuments stock;
    @Autowired MfgCostService costs;
    @Autowired MfgReports reports;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired PeriodService periods;
    @Autowired ReconciliationChecks checks;

    private final YearMonth month=YearMonth.now(ZoneId.of("Asia/Shanghai")).minusMonths(1);
    private final LocalDate day=month.atDay(10);
    private long tenant,company,period,warehouse,unit,finished,semi,raw,alternate;
    private CompanyAccess maker,reviewer;
    private boolean committedFixture;

    @BeforeTransaction void createCompany() {
        tenant=Ids.next();company=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'生产成本验收企业')",tenant,"MC"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'MC','生产成本验收公司')",company,tenant);
    }

    @AfterTransaction void cleanCompany() {
        if (committedFixture) return;
        jdbc.update("DELETE FROM document_sequence WHERE tenant_id=? AND company_id=?",tenant,company);
        jdbc.update("DELETE FROM company WHERE tenant_id=? AND id=?",tenant,company);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }

    @BeforeEach void seed() {
        warehouse=Ids.next();unit=Ids.next();period=Ids.next();
        var permissions=Set.of("cost:view","mfg_report:view","mfg_cost:view","mfg_cost:manage","mfg_cost:post",
                "mfg_work_order:view","mfg_work_order:short_close","mfg_work_order:approve_short_close",
                "mfg_issue:view","mfg_issue:create","mfg_issue:submit","mfg_issue:approve","mfg_issue:post",
                "mfg_receipt:view","mfg_receipt:create","mfg_receipt:submit","mfg_receipt:approve","mfg_receipt:post");
        maker=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),permissions);
        long category=Ids.next(), product=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'MFG','生产仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,company,month.format(DateTimeFormatter.ofPattern("yyyyMM")),month.atDay(1),month.atEndOfMonth());
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'MC','生产验收分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'MC','生产验收产品',?)",
                product,tenant,category,unit);
        finished=sku(product,"FG");semi=sku(product,"HALF");raw=sku(product,"RAW");alternate=sku(product,"ALT");
    }

    @ParameterizedTest
    @ValueSource(strings={"expense","allocate"})
    void shortCloseRequiresSecondReviewerAndClearsWipAfterPartialReceipt(String disposition) {
        opening(raw,"100","1000.00");
        long definition=bom(finished,List.of(line(raw)));
        long orderId=id(orders.create(maker,key(),new WorkOrderDraft(finished,definition,unit,"100",
                warehouse,warehouse,day,day,"0",null,null)));
        var order=orders.command(maker,orderId,"release",key(),new Revision(0L));
        long material=material(order,raw);
        post(ISSUE,orderId,List.of(new MfgStockModels.InputLine(material,null,"100",null,null)));
        long receipt=post(RECEIPT,orderId,List.of(receive("60")));
        var active=orders.detail(maker,orderId);
        long version=((Number)active.get("version")).longValue();
        assertThat(active).containsEntry("status","in_progress");
        assertThatThrownBy(()->orders.command(maker,orderId,"close",key(),new Revision(version)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(()->shortClose.request(maker,orderId,key(),
                new ShortCloseDraft(version,day,"订单部分取消",disposition,List.of())))
                .isInstanceOf(ApiException.class).hasMessageContaining("尚有净领料");
        assertThatThrownBy(()->shortClose.request(maker,orderId,key(),new ShortCloseDraft(version,day,"订单部分取消",
                disposition,List.of(new ShortCloseMaterial(Long.toString(material),"60","40",null)))))
                .isInstanceOf(ApiException.class).hasMessageContaining("报废说明");

        var draft=new ShortCloseDraft(version,day,"订单部分取消",disposition,
                List.of(new ShortCloseMaterial(Long.toString(material),"60","40","剩余已领材料经确认报废")));
        var commandKey=key();
        var pending=shortClose.request(maker,orderId,commandKey,draft);
        assertThat(pending).containsEntry("status","in_progress");
        assertThat(shortClose.request(maker,orderId,commandKey,draft).get("version")).isEqualTo(pending.get("version"));
        assertThat(pending.get("allowedActions")).asList().doesNotContain("approve_short_close");
        assertThat(orders.detail(reviewer,orderId).get("allowedActions")).asList().contains("approve_short_close");
        long revision=((Number)pending.get("version")).longValue();
        assertThatThrownBy(()->shortClose.approve(maker,orderId,key(),new Revision(revision)))
                .isInstanceOf(ApiException.class).hasMessageContaining("另一位");
        var readOnly=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("mfg_work_order:view"));
        assertThatThrownBy(()->shortClose.approve(readOnly,orderId,key(),new Revision(revision)))
                .isInstanceOf(ApiException.class).hasMessageContaining("没有短交");
        var otherWarehouse=new CompanyAccess(tenant,reviewer.userId(),company,false,Set.of(Ids.next()),reviewer.permissions());
        assertThatThrownBy(()->shortClose.approve(otherWarehouse,orderId,key(),new Revision(revision)))
                .isInstanceOf(ApiException.class).hasMessageContaining("不存在或无权");
        var otherTenant=new CompanyAccess(Ids.next(),reviewer.userId(),company,true,Set.of(),reviewer.permissions());
        assertThatThrownBy(()->shortClose.approve(otherTenant,orderId,key(),new Revision(revision)))
                .isInstanceOf(ApiException.class).hasMessageContaining("不存在或无权");

        var closed=shortClose.approve(reviewer,orderId,key(),new Revision(revision));
        assertThat(closed).containsEntry("status","closed");
        assertThatThrownBy(()->stock.create(maker,RECEIPT,key(),
                new MfgStockModels.Draft(orderId,warehouse,day,null,null,null,List.of(receive("1")))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(()->stock.create(maker,ISSUE,key(),
                new MfgStockModels.Draft(orderId,warehouse,day,"issue",null,null,
                        List.of(new MfgStockModels.InputLine(material,null,"1",null,null)))))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reversal.source(reviewer,RECEIPT.head,receipt,true))
                .isInstanceOf(ApiException.class).hasMessageContaining("已短交关闭");

        costs.collect(maker,period,key(),new MfgCostService.Collect(0L,"equivalent",List.of()));
        costs.allocate(maker,period,key(),new MfgCostService.Allocate(1L,List.of()));
        var snapshot=costs.calculate(maker,period,"calculate",key(),new MfgCostService.Revision(2L));
        var row=findCost(snapshot,orderId);
        boolean expensed=disposition.equals("expense");
        money(row,"wipEnd","0");
        money(row,"shortCloseExpense",expensed?"400.00":"0");
        money(row,"finishedCost",expensed?"600.00":"1000.00");
        costs.post(maker,period,key(),new MfgCostService.Revision(3L));
        assertThat(ledgerBalance("WIP")).isZero();
        assertThat(ledgerBalance("PRODUCTION_VARIANCE")).isEqualByComparingTo(expensed?"400.00":"0");
        assertThat(inventory.balance(maker,new Key(warehouse,finished)).cost()).isEqualByComparingTo(expensed?"600.00":"1000.00");
        assertThat(rows(periods.closeCheck(maker,period),"items")).anyMatch(check->
                "production_wip".equals(check.get("check")) && Boolean.TRUE.equals(check.get("passed")));
    }

    @Test void fromTwoLevelBomToPostedCostWithSoldInventoryAndWip() {
        opening(raw,"10","100.00");opening(alternate,"5","50.00");
        long semiBom=bom(semi,List.of(new BomLine(raw,unit,"1","0",null,false,null,
                List.of(new Substitute(alternate,unit,"1",1)))));
        long finishedBom=bom(finished,List.of(line(semi),line(raw)));
        var semiOrder=release(semi,semiBom);
        var finishedOrder=release(finished,finishedBom);
        long semiId=id(semiOrder), itemId=id(finishedOrder);
        post(ISSUE,semiId,List.of(new MfgStockModels.InputLine(material(semiOrder,raw),alternate,"2",null,null)));
        post(RECEIPT,semiId,List.of(receive("2")));
        post(ISSUE,itemId,List.of(new MfgStockModels.InputLine(material(finishedOrder,semi),null,"1",null,null),
                new MfgStockModels.InputLine(material(finishedOrder,raw),null,"2",null,null)));
        post(RECEIPT,itemId,List.of(receive("0.5")));
        post(RECEIPT,itemId,List.of(receive("0.5")));
        sale(finished,"0.5");
        assertThat(periods.closeCheck(maker,period)).containsEntry("passed",false);
        assertThat(rows(periods.closeCheck(maker,period),"items")).anyMatch(row->row.get("check").equals("production_cost") && Boolean.FALSE.equals(row.get("passed")));

        costs.collect(maker,period,key(),new MfgCostService.Collect(0L,"materials",List.of(
                new MfgCostService.Expense("labor","manual","9.00",null,"hours"),
                new MfgCostService.Expense("overhead","manual","6.00",null,"quantity"))));
        costs.allocate(maker,period,key(),new MfgCostService.Allocate(1L,List.of(
                new MfgCostService.Allocation(semiId,"2",null),new MfgCostService.Allocation(itemId,"1",null))));
        var snapshot=costs.calculate(maker,period,"calculate",key(),new MfgCostService.Revision(2L));
        var half=findCost(snapshot,semiId);
        var finalProduct=findCost(snapshot,itemId);
        money(half,"finishedCost","30.00");money(half,"rolledVariance","5.00");
        money(finalProduct,"materialCost","30.00");money(finalProduct,"rolledMaterial","5.00");
        money(finalProduct,"finishedCost","22.50");money(finalProduct,"wipEnd","17.50");
        money(finalProduct,"inventoryVariance","3.75");money(finalProduct,"issuedVariance","3.75");

        var request=key();
        var posted=costs.post(maker,period,request,new MfgCostService.Revision(3L));
        assertThat(posted).containsEntry("status","posted");
        assertThat(costs.post(maker,period,request,new MfgCostService.Revision(3L)).get("version").toString()).isEqualTo(posted.get("version").toString());
        assertThat(inventory.balance(maker,new Key(warehouse,semi)).cost()).isEqualByComparingTo("15.00");
        assertThat(inventory.balance(maker,new Key(warehouse,finished)).cost()).isEqualByComparingTo("11.25");
        assertThat(ledgerBalance("WIP")).isEqualByComparingTo("17.50");
        assertThat(ledgerBalance("PRODUCTION_VARIANCE")).isEqualByComparingTo("3.75");
        assertThat(checks.check(maker,"ledger_inventory",null)).isEmpty();
        assertThat(checks.check(maker,"inventory_cost",null)).isEmpty();
        // 关账投影使用独立事务，验收租户保留在隔离测试库供实际关账和重开。
        committedFixture=true;
        TestTransaction.flagForCommit();
        TestTransaction.end();
        var close=periods.close(maker,period,key(),new PeriodService.Version(0L,null));
        assertThat(close).containsEntry("status","closed");
        periods.reopen(maker,period,key(),new PeriodService.Version(1L,"验收生产成本冲回"));
        var reversed=costs.reopen(maker,period,key(),new MfgCostService.Revision(4L));
        assertThat(reversed).containsEntry("status","open");
        assertThat(inventory.balance(maker,new Key(warehouse,finished)).cost()).isEqualByComparingTo("7.50");
        assertThat(ledgerBalance("PRODUCTION_VARIANCE")).isZero();
    }

    @Test void reportsAndCostsRequireScopeAndCurrentPermission() {
        long order=id(release(semi,bom(semi,List.of(line(raw)))));
        var companyOutside=new CompanyAccess(tenant,maker.userId(),Ids.next(),true,Set.of(),maker.permissions());
        var tenantOutside=new CompanyAccess(Ids.next(),maker.userId(),company,true,Set.of(),maker.permissions());
        var warehouseOutside=new CompanyAccess(tenant,maker.userId(),company,false,Set.of(Ids.next()),maker.permissions());
        var departmentOutside=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),maker.permissions(),
                OrgScope.restricted(Set.of(Ids.next()),Set.of(),0));
        assertThat(rows(reports.page(maker,"progress",null,order,null,null,20),"items")).hasSize(1);
        for (var actor : List.of(companyOutside,tenantOutside,warehouseOutside,departmentOutside))
            assertThat(rows(reports.page(actor,"progress",null,order,null,null,20),"items")).isEmpty();
        assertThatThrownBy(()->costs.detail(companyOutside,period)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->costs.detail(warehouseOutside,period)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        var noCost=new CompanyAccess(tenant,maker.userId(),company,true,Set.of(),Set.of("mfg_report:view","mfg_cost:view"));
        assertThatThrownBy(()->reports.page(noCost,"cost-detail",null,null,null,null,20)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
    }

    @Test void cannotSettleWithAnUnpostedProductionDocument() {
        var order=release(semi,bom(semi,List.of(line(raw))));
        var input=new MfgStockModels.Draft(id(order),warehouse,day,"issue",null,null,
                List.of(new MfgStockModels.InputLine(material(order,raw),null,"1",null,null)));
        long document=id(stock.create(maker,ISSUE,key(),input));
        stock.command(maker,ISSUE,document,"submit",key(),new MfgStockModels.Revision(0L,null,null));
        costs.collect(maker,period,key(),new MfgCostService.Collect(0L,"materials",List.of()));
        costs.allocate(maker,period,key(),new MfgCostService.Allocate(1L,List.of()));
        costs.calculate(maker,period,"calculate",key(),new MfgCostService.Revision(2L));
        assertThatThrownBy(()->costs.post(maker,period,key(),new MfgCostService.Revision(3L)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getMessage()).contains("待复核或待过账"));
        assertThat(costs.detail(maker,period)).containsEntry("status","calculated");
    }

    private long bom(long sku,List<BomLine> lines) {
        long id=id(boms.create(maker,key(),new BomDraft(sku,"V1",unit,"1",month.atDay(1),null,true,null,null,lines)));
        boms.command(maker,id,"submit",key(),new Revision(0L));
        boms.command(reviewer,id,"approve",key(),new Revision(1L));
        return id;
    }

    private BomLine line(long sku) {return new BomLine(sku,unit,"1","0",null,false,null,List.of());}

    private Map<String,Object> release(long sku,long bom) {
        long id=id(orders.create(maker,key(),new WorkOrderDraft(sku,bom,unit,"2",warehouse,warehouse,day,day,"0",null,null)));
        return orders.command(maker,id,"release",key(),new Revision(0L));
    }

    private long post(MfgStockModels.Type type,long order,List<MfgStockModels.InputLine> lines) {
        long id=id(stock.create(maker,type,key(),new MfgStockModels.Draft(order,warehouse,day,type==ISSUE?"issue":null,null,null,lines)));
        stock.command(maker,type,id,"submit",key(),new MfgStockModels.Revision(0L,null,null));
        stock.command(reviewer,type,id,"approve",key(),new MfgStockModels.Revision(1L,null,null));
        stock.command(maker,type,id,"post",key(),new MfgStockModels.Revision(2L,null,null));
        return id;
    }

    private MfgStockModels.InputLine receive(String qty) {return new MfgStockModels.InputLine(null,null,qty,null,null);}

    private long material(Map<String,Object> order,long sku) {
        return id(rows(order,"materials").stream().filter(row->row.get("componentSkuId").equals(Long.toString(sku))).findFirst().orElseThrow());
    }

    private long sku(long product,String code) {
        long id=Ids.next();
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,?,?)",id,tenant,product,code,code);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,id,unit);
        return id;
    }

    private void opening(long sku,String qty,String cost) {
        long id=Ids.next();
        var amount=new BigDecimal(cost);
        inventory.receive(maker,new Posting(new Key(warehouse,sku),new Source("mfg_test_opening",id,id),day,period,"opening_in"),new BigDecimal(qty),amount);
        journal(id,"mfg_test_opening","INVENTORY","OPENING_EQUITY",sku,amount);
    }

    private void sale(long sku,String qty) {
        long id=Ids.next();
        var result=inventory.issue(maker,new Posting(new Key(warehouse,sku),new Source("mfg_test_sale",id,id),day,period,"sales_out"),new BigDecimal(qty),null);
        journal(id,"mfg_test_sale","COGS","INVENTORY",sku,result.movementCost());
    }

    private void journal(long id,String type,String debit,String credit,long sku,BigDecimal amount) {
        ledger.post(maker,new LedgerService.Posting(type,id,0,period,day,List.of(
                new LedgerService.Line(debit,"D",amount,null,null,warehouse,sku,null,null),
                new LedgerService.Line(credit,"C",amount,null,null,warehouse,sku,null,null))));
    }

    private BigDecimal ledgerBalance(String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(IF(direction='D',amount,-amount)),0) FROM journal_line WHERE tenant_id=? AND company_id=? AND account_code=?",
                BigDecimal.class,tenant,company,account);
    }

    private static Map<String,Object> findCost(Map<String,Object> detail,long order) {
        return rows(detail,"items").stream().filter(row->row.get("workOrderId").equals(Long.toString(order))).findFirst().orElseThrow();
    }

    private static void money(Map<String,Object> row,String field,String expected) {assertThat(new BigDecimal(row.get(field).toString())).isEqualByComparingTo(expected);}
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> row,String field) {return (List<Map<String,Object>>)row.get(field);}
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    private static String key() {return UUID.randomUUID().toString();}
}
