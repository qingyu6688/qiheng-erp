package cn.erp.gl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.util.HashSet;
import java.util.Set;
import cn.erp.identity.CompanyAccess;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.adjustment.PriceAdjustmentModels;
import cn.erp.adjustment.SalesPriceAdjustmentQueries;
import cn.erp.adjustment.SalesPriceAdjustmentModels;
import cn.erp.adjustment.SalesPriceAdjustmentService;
import cn.erp.fulfillment.ShipmentModels;
import cn.erp.fulfillment.ShipmentService;
import cn.erp.jobs.OutboxDelivery;
import cn.erp.jobs.OutboxService;
import cn.erp.purchasing.PurchaseOrderModels;
import cn.erp.purchasing.PurchaseOrderService;
import cn.erp.receiving.ReceivingModels;
import cn.erp.receiving.ReceivingService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.reversal.ReversalModels;
import cn.erp.reversal.ReversalService;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.salesorder.SalesOrderService;
import cn.erp.shared.Ids;
import static cn.erp.gl.GlTestData.key;
import static cn.erp.gl.GlSupport.number;
import static org.assertj.core.api.Assertions.assertThat;

/** 从真实销售调价和冲销命令生成总账，不改原签收货款或销售成本。 */
@SpringBootTest
@ActiveProfiles("test")
class SalesPriceGlIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlQueryService financial;
    @Autowired PurchaseOrderService purchases;
    @Autowired SalesOrderService sales;
    @Autowired ReceivingService receiving;
    @Autowired ShipmentService shipments;
    @Autowired SalesPriceAdjustmentService prices;
    @Autowired SalesPriceAdjustmentQueries priceQueries;
    @Autowired GlMappingResolver mappings;
    @Autowired ReversalService reversals;
    @Autowired OutboxService outbox;
    @Autowired OutboxDelivery delivery;
    @Autowired ReconciliationChecks checks;
    private GlTestData d;
    private long warehouse, unit, sku;
    private static final LocalDate DAY=LocalDate.of(2026,11,15);

    @BeforeEach void prepare() {
        d=new GlTestData(jdbc);
        warehouse=Ids.next();unit=Ids.next();sku=Ids.next();
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),d.tenant,d.company);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,term_type,term_value) VALUES(?,?,?,'supplier','days',30)",Ids.next(),d.tenant,d.customer);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'W','调价总账仓')",warehouse,d.tenant,d.company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,d.tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,d.tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','调价商品',?)",product,d.tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'S','调价商品',0)",sku,d.tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),d.tenant,sku,unit);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),d.tenant,d.company,d.bank);
        books.create(d.actor(d.maker),key(),new GlModels.Book("enterprise",d.october,null,0L));
        var confirmed=books.confirm(d.actor(d.reviewer),key(),new GlModels.Confirmation(0L,"核对零期初和销售业务默认映射"));
        books.activate(d.actor(d.maker),"activate",key(),new GlModels.Version(number(confirmed.get("version")),null));
    }

    @AfterEach void cleanup() {
        if(d==null)return;
        jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=NULL WHERE tenant_id=?",d.tenant);
        for(String table:List.of("document_reversal","price_adjustment_cost","price_adjustment_line","price_adjustment",
                "purchase_return_line","purchase_return","payable","goods_receipt_line","goods_receipt","purchase_order_fee","purchase_order_line","purchase_order","approval_record")) {
            if(table.equals("payable"))jdbc.update("DELETE FROM payable WHERE tenant_id=? AND posting_action='reverse'",d.tenant);
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",d.tenant);
        }
        jdbc.update("DELETE FROM receivable WHERE tenant_id=? AND reverses_receivable_id IS NOT NULL",d.tenant);
        d.cleanup();
    }

    @ParameterizedTest
    @CsvSource({"24.86,149.16,-132.00,-17.16", "20.34,122.04,-108.00,-14.04"})
    void salesPriceAndItsReversalReachTheGeneralLedgerWithoutRepricingCost(
            String newPrice,String gross,String revenue,String tax) {
        purchase();
        long source=ship();
        long price=id(prices.create(priceActor(d.maker),key(),new SalesPriceAdjustmentModels.Input(null,d.customer,warehouse,DAY.plusDays(1),"销售结算调价",
                List.of(new SalesPriceAdjustmentModels.LineInput(source,newPrice,null)))));
        long department=Ids.next();
        jdbc.update("INSERT INTO org_department(id,tenant_id,company_id,code,name,path,depth) VALUES(?,?,?,'D','销售部门',?,1)",department,d.tenant,d.company,"/"+department+"/");
        jdbc.update("UPDATE price_adjustment SET owner_department_id=? WHERE id=?",department,price);
        change(price,"submit",d.maker);change(price,"approve",d.reviewer);change(price,"post",d.maker);
        var journal=jdbc.queryForMap("SELECT * FROM journal WHERE tenant_id=? AND source_type='sales_price_adjustment' AND source_id=?",d.tenant,price);
        var line=jdbc.queryForMap("SELECT * FROM journal_line WHERE journal_id=? AND account_code='AR'",journal.get("id"));
        assertThat(mappings.context(d.actor(d.maker),journal,line)).containsEntry("deptId",Long.toString(department));
        dispatch();
        assertThat(postedBalance("AR")).isEqualByComparingTo(gross);
        assertThat(postedBalance("SALES_REVENUE")).isEqualByComparingTo(revenue);
        assertThat(postedBalance("OUTPUT_VAT")).isEqualByComparingTo(tax);
        originalCostsAndInvoices(source);
        reconcile();
        for(String report:List.of("balance_sheet","income_statement","cash_flow"))
            assertThat(financial.statement(d.actor(d.maker),report,d.november)).containsEntry("passed",true);
        long reversal=id(reversals.create(priceActor(d.maker),key(),new ReversalModels.Input("sales_price_adjustment",price,DAY.plusDays(2),"冲回本次销售调价")));
        reversals.command(priceActor(d.reviewer),reversal,"approve",key(),new ReversalModels.Version(0L,null));
        reversals.command(priceActor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
        dispatch();
        assertThat(postedBalance("AR")).isEqualByComparingTo("135.60");
        assertThat(postedBalance("SALES_REVENUE")).isEqualByComparingTo("-120.00");
        assertThat(postedBalance("OUTPUT_VAT")).isEqualByComparingTo("-15.60");
        originalCostsAndInvoices(source);
        assertThat(value("SELECT price_adjusted_amount FROM shipment_line WHERE id=?",source)).isZero();
        assertThat(value("SELECT price_adjusted_tax FROM shipment_line WHERE id=?",source)).isZero();
        assertThat(jdbc.queryForObject("SELECT pricing_return_basis IS NULL FROM shipment_line WHERE id=?",Boolean.class,source)).isTrue();
        reconcile();
        for(String report:List.of("balance_sheet","income_statement","cash_flow"))
            assertThat(financial.statement(d.actor(d.maker),report,d.november)).containsEntry("passed",true);
    }

    private void originalCostsAndInvoices(long source) {
        assertThat(postedBalance("INVENTORY")).isEqualByComparingTo("40.00");
        assertThat(postedBalance("COGS")).isEqualByComparingTo("60.00");
        assertThat(value("SELECT cost_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("60.00");
        assertThat(value("SELECT signed_cost_amount FROM shipment_line WHERE id=?",source)).isEqualByComparingTo("60.00");
        assertThat(value("SELECT amount FROM receivable WHERE source_type='shipment_line' AND posting_action='post' AND source_line_id=?",source)).isEqualByComparingTo("135.60");
        assertThat(value("SELECT tax_amount FROM receivable WHERE source_type='shipment_line' AND posting_action='post' AND source_line_id=?",source)).isEqualByComparingTo("15.60");
    }

    private CompanyAccess priceActor(long user) {
        var permissions=new HashSet<>(d.permissions);
        permissions.remove("cost:view");
        return new CompanyAccess(d.tenant,user,d.company,true,Set.of(),permissions);
    }

    private long purchase() {
        long order=id(purchases.create(d.actor(d.maker),key(),new PurchaseOrderModels.Input(Long.toString(d.customer),DAY,null,true,"0",
                List.of(new PurchaseOrderModels.Line(null,null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"10","11.30",null,null,null,"0.13")),List.of(),null,null,null)));
        purchases.command(d.actor(d.maker),order,"submit",key(),new PurchaseOrderModels.Command(0L,null,null));
        purchases.command(d.actor(d.reviewer),order,"approve",key(),new PurchaseOrderModels.Command(1L,null,null));
        long line=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=?",Long.class,order);
        long receipt=id(receiving.createReceipt(d.actor(d.maker),key(),new ReceivingModels.ReceiptInput(d.customer,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(line,unit,"10")),null)));
        receiving.command(d.actor(d.maker),false,receipt,"submit",key(),0L);receiving.command(d.actor(d.maker),false,receipt,"post",key(),1L);
        return jdbc.queryForObject("SELECT id FROM goods_receipt_line WHERE receipt_id=?",Long.class,receipt);
    }

    private long ship() {
        long order=id(sales.create(d.actor(d.maker),key(),new SalesOrderModels.Input(Long.toString(d.customer),DAY,null,true,"0",
                List.of(new SalesOrderModels.Line(null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"6","22.60",null,"0",null,"0.13")),List.of(),null,null,null)));
        sales.command(d.actor(d.maker),order,"submit",key(),new SalesOrderModels.Command(0L,null,null));
        sales.command(d.actor(d.reviewer),order,"approve",key(),new SalesOrderModels.Command(1L,null,null));
        long line=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=?",Long.class,order);
        long shipment=id(shipments.create(d.actor(d.maker),key(),new ShipmentModels.Input(d.customer,warehouse,DAY,"pickup",null,List.of(new ShipmentModels.InputLine(line,unit,"6")),null)));
        shipments.submit(d.actor(d.maker),shipment,key(),0L);shipments.post(d.actor(d.reviewer),shipment,key(),1L);
        return jdbc.queryForObject("SELECT id FROM shipment_line WHERE shipment_id=?",Long.class,shipment);
    }

    private void change(long id,String action,long user) {
        prices.command(priceActor(user),id,action,key(),new PriceAdjustmentModels.Command(number(priceQueries.get(priceActor(user),id).get("version")),null));
    }
    private void dispatch() {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline) {
            var events=outbox.claim(100,d.tenant);
            if(events.isEmpty())return;
            for(var event:events)assertThat(delivery.deliver(event)).as(event.type()).isTrue();
        }
        throw new AssertionError("销售调价总账事件未在测试时限内同步完成");
    }
    private BigDecimal postedBalance(String account) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(IF(g.direction='D',g.amount,-g.amount)),0) FROM gl_voucher_line g JOIN gl_voucher v ON v.tenant_id=g.tenant_id AND v.company_id=g.company_id AND v.id=g.voucher_id JOIN journal_line l ON l.tenant_id=g.tenant_id AND l.company_id=g.company_id AND l.id=g.journal_line_id WHERE g.tenant_id=? AND v.status IN('posted','reversed') AND l.account_code=?",BigDecimal.class,d.tenant,account);
    }
    private void reconcile() {
        for(String type:List.of("inventory_balance","inventory_cost","reservation","cash_balance","open_item","journal_balance","ledger_inventory","period_close","gl_trial","gl_operational"))
            assertThat(checks.check(d.actor(d.maker),type,type.equals("period_close")?d.november:null)).as(type).isEmpty();
    }
    private BigDecimal value(String sql,long id) {return jdbc.queryForObject(sql,BigDecimal.class,id);}
    private static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
}
