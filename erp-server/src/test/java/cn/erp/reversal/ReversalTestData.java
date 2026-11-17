package cn.erp.reversal;

import cn.erp.accounting.*;
import cn.erp.fulfillment.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.invoicing.InvoiceService;
import cn.erp.opening.OpeningService;
import cn.erp.purchasefinance.*;
import cn.erp.purchasing.*;
import cn.erp.receiving.*;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesfinance.*;
import cn.erp.salesorder.*;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

abstract class ReversalTestData {
    static final LocalDate DAY=LocalDate.of(2026,11,1);
    static final String PASSWORD="ReversalTest!2026";
    @Autowired JdbcTemplate jdbc;
    @Autowired ReversalService reversals;
    @Autowired OpeningService opening;
    @Autowired InventoryService inventory;
    @Autowired LedgerService ledger;
    @Autowired SettlementService settlement;
    @Autowired SalesOrderService sales;
    @Autowired SalesOrderQueries salesQueries;
    @Autowired ShipmentService shipments;
    @Autowired ShipmentQueries shipmentQueries;
    @Autowired SalesReturnService salesReturns;
    @Autowired PurchaseOrderService purchases;
    @Autowired PurchaseOrderQueries purchaseQueries;
    @Autowired ReceivingService receiving;
    @Autowired ReceivingQueries receivingQueries;
    @Autowired ReceiptService receipts;
    @Autowired PaymentService payments;
    @Autowired AccountTransferService transfers;
    @Autowired SalesFinanceQueries finances;
    @Autowired RefundApplicationService refunds;
    @Autowired InvoiceService invoices;
    @Autowired ReconciliationChecks checks;
    @Autowired PasswordEncoder passwords;
    @Autowired CommandResults results;
    long tenant,company,otherCompany,warehouse,otherWarehouse,unit,sku,partner,account,target,period,user,reviewerId,role,expense,income;
    CompanyAccess maker,reviewer;

    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();unit=Ids.next();sku=Ids.next();partner=Ids.next();account=Ids.next();target=Ids.next();period=Ids.next();user=Ids.next();reviewerId=Ids.next();role=Ids.next();expense=Ids.next();income=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'冲销验收')",tenant,"REV"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','冲销公司')",company,tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','另一公司')",otherCompany,tenant);
        jdbc.update("INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",Ids.next(),tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','冲销双角色往来')",partner,tenant);
        for(String businessRole:List.of("customer","supplier"))jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,partner,businessRole);
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'REV','冲销操作员')",role,tenant);
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        for(String code:permissions)jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,code);
        String hash=passwords.encode(PASSWORD);
        for(long operator:List.of(user,reviewerId)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",operator,tenant,operator==user?"operator":"reviewer","冲销验收用户",hash);
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,operator,role);
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,operator,otherCompany);
        }
        for(long wh:List.of(warehouse,otherWarehouse)) {
            jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",wh,tenant,company,"W"+wh,"冲销仓");
            for(long operator:List.of(user,reviewerId))jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,operator,company,wh);
        }
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','冲销分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','冲销商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate,purchase_price,sale_price) VALUES(?,?,?,'SKU','冲销规格',0,14,20)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        for(long bank:List.of(account,target)) {
            jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",bank,tenant,company,"BANK"+bank,"冲销资金账户");
            jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,bank);
        }
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(?,?,'expense','EXP','冲销支出')",expense,tenant);
        jdbc.update("INSERT INTO income_expense_item(id,tenant_id,item_type,code,name) VALUES(?,?,'income','INC','冲销收入')",income,tenant);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        maker=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);reviewer=new CompanyAccess(tenant,reviewerId,company,true,Set.of(),permissions);
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        jdbc.update("UPDATE sales_order_fee SET recognized_shipment_id=NULL WHERE tenant_id=?",tenant);
        jdbc.update("UPDATE purchase_order_fee SET recognized_receipt_id=NULL WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM inv_movement_hold WHERE tenant_id=?",tenant);
        for(String fact:List.of("receivable","payable","inventory_movement","journal")) {
            if(fact.equals("journal"))jdbc.update("DELETE FROM journal_line WHERE tenant_id=?",tenant);
            if(fact.equals("receivable")) {
                jdbc.update("DELETE FROM invoice_registration_line WHERE tenant_id=?",tenant);
                jdbc.update("DELETE FROM settlement_allocation WHERE tenant_id=?",tenant);
                jdbc.update("DELETE FROM partner_open_item WHERE tenant_id=?",tenant);
            }
            jdbc.update("DELETE FROM "+fact+" WHERE tenant_id=? AND posting_action='reverse'",tenant);
        }
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item","invoice_registration_line","invoice_registration","receivable","payable","document_reversal","refund_request",
            "inventory_reservation_change","inventory_reservation","cost_adjustment","inventory_movement","inventory_balance","inventory_cost","sales_return_line","sales_return","shipment_line","shipment","purchase_return_line","purchase_return","goods_receipt_line","goods_receipt","receipt","payment","account_transfer",
            "stock_count_line","stock_count","stock_adjustment_line","stock_adjustment","opening_entry","document_change","sales_order_fee","sales_order_line","sales_order","purchase_order_fee","purchase_order_line","purchase_order","audit_log","approval_record","idempotency_record","document_sequence","cash_account_balance","cash_account","income_expense_item",
            "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","sys_user_data_scope","warehouse","accounting_period","sys_role_permission","sys_user_role","sys_role","sys_user","company_policy","company"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    long stock(String qty,String cost) {return stock(qty,cost,DAY);}
    long stock(String qty,String cost,LocalDate day) {
        var draft=opening.createStock(maker,key(),new OpeningService.Stock("opening",warehouse,day,"冲销期初",List.of(new OpeningService.StockLine(sku,unit,qty,cost)),null));long id=id(draft);
        opening.command(maker,true,id,"submit",key(),new OpeningService.Version(0L,null));opening.command(reviewer,true,id,"approve",key(),new OpeningService.Version(1L,null));opening.command(maker,true,id,"post",key(),new OpeningService.Version(2L,null));return id;
    }
    void cash() {
        var draft=opening.createEntry(maker,key(),new OpeningService.Entry("cash",null,account,"D","1000",DAY,null,null));long id=id(draft);
        opening.command(reviewer,false,id,"approve",key(),new OpeningService.Version(0L,null));opening.command(maker,false,id,"post",key(),new OpeningService.Version(1L,null));
    }
    long order(String qty,boolean fee) {
        var draft=sales.create(maker,key(),new SalesOrderModels.Input(Long.toString(partner),DAY,null,true,"0",List.of(new SalesOrderModels.Line(null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),qty,"20",null,null,null,"0")),fee?List.of(new SalesOrderModels.Fee(null,"freight","buyer","5","0")):List.of(),null,null,null));long id=id(draft);
        var submitted=sales.command(maker,id,"submit",key(),new SalesOrderModels.Command(version(draft),null,null));sales.command(reviewer,id,"approve",key(),new SalesOrderModels.Command(version(submitted),null,null));return id;
    }
    long shipment(long order,String qty,String signed) {
        long source=jdbc.queryForObject("SELECT id FROM sales_order_line WHERE order_id=? AND is_deleted=0",Long.class,order);
        var draft=shipments.create(maker,key(),new ShipmentModels.Input(partner,warehouse,DAY,"delivery",null,List.of(new ShipmentModels.InputLine(source,unit,qty)),null));long id=id(draft);
        shipments.submit(maker,id,key(),0);shipments.post(maker,id,key(),1);
        if(signed!=null)shipments.sign(maker,id,key(),new ShipmentModels.Signed(2L,DAY,List.of(new ShipmentModels.SignedLine(line("shipment_line","shipment_id",id),signed)),List.of()));return id;
    }
    long purchase(boolean fee) {
        var draft=purchases.create(maker,key(),new PurchaseOrderModels.Input(Long.toString(partner),DAY,null,true,"0",List.of(new PurchaseOrderModels.Line(null,null,Long.toString(sku),Long.toString(warehouse),Long.toString(unit),"10","14",null,null,null,"0")),fee?List.of(new PurchaseOrderModels.Fee(null,"freight","buyer","5","0")):List.of(),null,null,null));long id=id(draft);
        var submitted=purchases.command(maker,id,"submit",key(),new PurchaseOrderModels.Command(version(draft),null,null));purchases.command(reviewer,id,"approve",key(),new PurchaseOrderModels.Command(version(submitted),null,null));return id;
    }
    long receive(long order) {
        long source=jdbc.queryForObject("SELECT id FROM purchase_order_line WHERE order_id=? AND is_deleted=0",Long.class,order);
        var draft=receiving.createReceipt(maker,key(),new ReceivingModels.ReceiptInput(partner,warehouse,DAY,null,List.of(new ReceivingModels.ReceiptLine(source,unit,"10")),null));long id=id(draft);
        receiving.command(maker,false,id,"submit",key(),0L);receiving.command(maker,false,id,"post",key(),1L);return id;
    }
    long receipt(String type) {
        var draft=receipts.create(maker,key(),new FinanceModels.Receipt(type,type.equals("other_income")?null:partner,type.equals("other_income")?income:null,account,"100",DAY,null,null,null,null));long id=id(draft);
        receipts.command(maker,id,"submit",key(),new FinanceModels.Version(0L,null,null));receipts.command(reviewer,id,"confirm",key(),new FinanceModels.Version(1L,null,null));return id;
    }
    long payment(String type) {
        var draft=payments.create(maker,key(),new TreasuryModels.Payment(type,type.equals("supplier")?partner:null,type.equals("supplier")?null:expense,account,"100",DAY,null,null,null,null));long id=id(draft);
        payments.command(maker,id,"submit",key(),new TreasuryModels.Version(0L,null));payments.command(reviewer,id,"confirm",key(),new TreasuryModels.Version(1L,null));return id;
    }
    long apply(String type,long source) {return id(reversals.create(maker,key(),new ReversalModels.Input(type,source,DAY,"验收录入错误")));}
    void reverse(String type,long source) {long id=apply(type,source);reversals.command(reviewer,id,"approve",key(),new ReversalModels.Version(0L,null));reversals.command(maker,id,"post",key(),new ReversalModels.Version(1L,null));}
    long line(String table,String parent,long source){return jdbc.queryForObject("SELECT id FROM "+table+" WHERE "+parent+"=?",Long.class,source);}
    long open(String type,long source){return jdbc.queryForObject("SELECT id FROM partner_open_item WHERE tenant_id=? AND item_type=? AND source_id=?",Long.class,tenant,type,source);}
    long fact(String table,long source){return jdbc.queryForObject("SELECT id FROM "+table+" WHERE tenant_id=? AND source_id=? AND posting_action='post'",Long.class,tenant,source);}
    BigDecimal value(String sql,Object... args){return jdbc.queryForObject(sql,BigDecimal.class,args);}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
    void zero(){for(String type:ReconciliationChecks.TYPES)assertThat(checks.check(maker,type,type.equals("period_close")?period:null)).as(type).isEmpty();}
    static String key(){return UUID.randomUUID().toString();}
    static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,String expected){assertThatThrownBy(action).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(expected));}
}
