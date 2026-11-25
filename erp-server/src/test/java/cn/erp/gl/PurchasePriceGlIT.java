package cn.erp.gl;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import cn.erp.adjustment.PriceAdjustmentModels;
import cn.erp.adjustment.PriceAdjustmentQueries;
import cn.erp.adjustment.PriceAdjustmentService;
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

/** 从实际采购、发货、调价和冲销命令生成凭证，不用手填总账行替代业务同步。 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasePriceGlIT {
    @Autowired JdbcTemplate jdbc;
    @Autowired GlBookService books;
    @Autowired GlQueryService financial;
    @Autowired PurchaseOrderService purchases;
    @Autowired SalesOrderService sales;
    @Autowired ReceivingService receiving;
    @Autowired ShipmentService shipments;
    @Autowired PriceAdjustmentService prices;
    @Autowired PriceAdjustmentQueries priceQueries;
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
        var confirmed=books.confirm(d.actor(d.reviewer),key(),new GlModels.Confirmation(0L,"核对零期初和采购业务默认映射"));
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
        d.cleanup();
    }

    @Test void purchasePriceAndItsReversalReachTheGeneralLedgerWithUnchangedOriginalCostSnapshots() {
        long source=purchase();
        ship();
        long price=id(prices.create(d.actor(d.maker),key(),new PriceAdjustmentModels.Input(null,d.customer,warehouse,DAY.plusDays(1),"结算补价",
                List.of(new PriceAdjustmentModels.LineInput(source,"12.43",null)))));
        change(price,"submit",d.maker);change(price,"approve",d.reviewer);change(price,"post",d.maker);
        dispatch();
        assertThat(postedBalance("INVENTORY")).isEqualByComparingTo("44.00");
        assertThat(postedBalance("COST_VARIANCE")).isEqualByComparingTo("6.00");
        assertThat(postedBalance("INPUT_VAT")).isEqualByComparingTo("14.30");
        assertThat(value("SELECT cost_amount FROM goods_receipt_line WHERE id=?",source)).isEqualByComparingTo("100.00");
        assertThat(value("SELECT cost_amount FROM shipment_line WHERE tenant_id=?",d.tenant)).isEqualByComparingTo("60.00");
        reconcile();
        for(String report:List.of("balance_sheet","income_statement","cash_flow"))
            assertThat(financial.statement(d.actor(d.maker),report,d.november)).containsEntry("passed",true);
        long reversal=id(reversals.create(d.actor(d.maker),key(),new ReversalModels.Input("price_adjustment",price,DAY.plusDays(2),"冲回本次补价")));
        reversals.command(d.actor(d.reviewer),reversal,"approve",key(),new ReversalModels.Version(0L,null));
        reversals.command(d.actor(d.maker),reversal,"post",key(),new ReversalModels.Version(1L,null));
        dispatch();
        assertThat(postedBalance("INVENTORY")).isEqualByComparingTo("40.00");
        assertThat(postedBalance("COST_VARIANCE")).isZero();
        assertThat(postedBalance("INPUT_VAT")).isEqualByComparingTo("13.00");
        reconcile();
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

    private void ship() {
        long order=id(sales.create(d.actor(d.maker),key(),new SalesOrderModels.Input(Long.toString(d.customer),DAY,null,true,"0",
                List.of(new SalesOrderModels.Line(null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"6","20",null,"0",null,"0")),List.of(),null,null,null)));
        sales.command(d.actor(d.maker),order,"submit",key(),new SalesOrderModels.Command(0L,null,null));
        sales.command(d.actor(d.reviewer),order,"approve",key(),new SalesOrderModels.Command(1L,null,null));
        long line=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=?",Long.class,order);
        long shipment=id(shipments.create(d.actor(d.maker),key(),new ShipmentModels.Input(d.customer,warehouse,DAY,"pickup",null,List.of(new ShipmentModels.InputLine(line,unit,"6")),null)));
        shipments.submit(d.actor(d.maker),shipment,key(),0L);shipments.post(d.actor(d.reviewer),shipment,key(),1L);
    }

    private void change(long id,String action,long user) {
        prices.command(d.actor(user),id,action,key(),new PriceAdjustmentModels.Command(number(priceQueries.get(d.actor(user),id).get("version")),null));
    }
    private void dispatch() {
        long deadline=System.nanoTime()+Duration.ofSeconds(45).toNanos();
        while(System.nanoTime()<deadline) {
            var events=outbox.claim(100,d.tenant);
            if(events.isEmpty())return;
            for(var event:events)assertThat(delivery.deliver(event)).as(event.type()).isTrue();
        }
        throw new AssertionError("采购调价总账事件未在测试时限内同步完成");
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
