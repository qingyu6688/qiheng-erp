package cn.erp.stocktransfer;

import cn.erp.accounting.LedgerService;
import cn.erp.catalog.CatalogAvailability;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.*;
import cn.erp.pricing.PricingEngine;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import static cn.erp.inventory.InventoryModels.*;
import static org.assertj.core.api.Assertions.*;

abstract class StockTransferTestData {
    static final LocalDate DAY=LocalDate.of(2026,11,15);
    @Autowired JdbcTemplate jdbc;
    @Autowired StockTransferService service;
    @Autowired InventoryService inventory;
    @Autowired TransferCosts costs;
    @Autowired LedgerService ledger;
    @Autowired FinanceSupport finance;
    @Autowired CatalogAvailability catalog;
    @Autowired PricingEngine pricing;
    @Autowired BusinessCommands commands;
    @Autowired DocumentNumbers numbers;
    @Autowired AuditService audit;
    @Autowired ReconciliationChecks checks;
    long tenant,company,otherCompany,from,to,outside,unit,sku,period,user;
    CompanyAccess maker;
    @BeforeEach void seedTransfer(){
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();from=Ids.next();to=Ids.next();outside=Ids.next();unit=Ids.next();sku=Ids.next();period=Ids.next();user=Ids.next();
        maker=new CompanyAccess(tenant,user,company,true,Set.of(),Set.of("stock_transfer:view","stock_transfer:create","stock_transfer:update","stock_transfer:submit","stock_transfer:ship","stock_transfer:receive","stock_transfer:resolve_diff","stock_transfer:void","cost:view"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'调拨验收')",tenant,"TRANSFER"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','公司甲')",company,tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','公司乙')",otherCompany,tenant);
        for(long id:List.of(from,to,outside))jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",id,tenant,id==outside?otherCompany:company,"W"+id,id==from?"调出仓":id==to?"调入仓":"其他公司仓");
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        long category=Ids.next(),product=Ids.next();
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'C','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'P','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name) VALUES(?,?,?,'SKU','调拨商品')",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        long opening=Ids.next();inventory.receive(maker,new Posting(new Key(from,sku),new Source("opening",opening,opening),DAY,period,"opening_in"),d("3"),d("1"));
        ledger.post(maker,new LedgerService.Posting("opening",opening,0,period,DAY,List.of(entry("INVENTORY","D","1",from),entry("OPENING_EQUITY","C","1",null))));
    }
    @AfterEach void cleanTransfer(){
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","cost_adjustment","inv_movement_hold","inventory_movement","inventory_cost","inventory_balance","stock_transfer_event_line","stock_transfer_line","stock_transfer","audit_log","idempotency_record","document_sequence","accounting_period","unit_conversion","sku","product","product_category","unit","sys_user_data_scope","warehouse","sys_role_permission","sys_user_role","sys_role","sys_user","company"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    StockTransferModels.Input input(String qty,Long version){return new StockTransferModels.Input(from,to,DAY,"调拨验收",List.of(new StockTransferModels.Line(sku,unit,qty)),version);}
    long draft(String qty){return id(service.create(maker,key(),input(qty,null)));}
    Map<String,Object> command(long id,String action){return service.command(maker,id,action,key(),new StockTransferModels.Version(version(id),null));}
    Map<String,Object> receive(long id,String qty,boolean last){return service.receive(maker,id,key(),new StockTransferModels.Receive(version(id),null,last,List.of(new StockTransferModels.ReceivedLine(line(id),qty))));}
    Map<String,Object> resolve(long id,String disposition){return service.difference(maker,id,key(),new StockTransferModels.Difference(version(id),null,"运输差异处理",List.of(new StockTransferModels.DifferenceLine(line(id),disposition))));}
    long shipped(String qty){long id=draft(qty);command(id,"submit");command(id,"ship");return id;}
    long line(long id){return jdbc.queryForObject("SELECT id FROM stock_transfer_line WHERE tenant_id=? AND transfer_id=?",Long.class,tenant,id);}
    long version(long id){return ((Number)service.get(maker,id).get("version")).longValue();}
    InventoryModels.Balance balance(long wh){return inventory.balance(maker,new Key(wh,sku));}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
    void zero(){for(String type:ReconciliationChecks.TYPES)assertThat(checks.check(maker,type,type.equals("period_close")?period:null)).as(type).isEmpty();}
    LedgerService.Line entry(String code,String direction,String amount,Long wh){return new LedgerService.Line(code,direction,d(amount),null,null,wh,wh==null?null:sku,null,"调拨期初");}
    static BigDecimal d(String value){return new BigDecimal(value);}
    static String key(){return UUID.randomUUID().toString();}
    static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> rows(Map<String,Object> row,String field){return (List<Map<String,Object>>)row.get(field);}
    void code(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String expected){assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo(expected));}
}
