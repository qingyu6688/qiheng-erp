package cn.erp.opening;

import cn.erp.accounting.*;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.reconciliation.ReconciliationChecks;
import cn.erp.shared.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

abstract class OpeningTestData {
    static final LocalDate DAY=LocalDate.of(2026,11,1);
    static final String PASSWORD="OpeningTest!2026";
    @Autowired JdbcTemplate jdbc;
    @Autowired OpeningService service;
    @Autowired InventoryService inventory;
    @Autowired ReconciliationChecks checks;
    @Autowired PasswordEncoder passwords;
    @Autowired CommandResults results;
    long tenant,company,otherCompany,warehouse,otherWarehouse,unit,box,sku,zeroSku,category,product,zeroProduct,partner,account,period,user,reviewerId,role;
    CompanyAccess maker,reviewer;

    @BeforeEach void seedOpening() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();unit=Ids.next();box=Ids.next();sku=Ids.next();zeroSku=Ids.next();
        category=Ids.next();product=Ids.next();zeroProduct=Ids.next();partner=Ids.next();account=Ids.next();period=Ids.next();user=Ids.next();reviewerId=Ids.next();role=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'期初验收')",tenant,"OPENING"+tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','期初公司')",company,tenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'B','另一公司')",otherCompany,tenant);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'P','期初双角色往来')",partner,tenant);
        for(String businessRole:List.of("customer","supplier")) {jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role) VALUES(?,?,?,?)",Ids.next(),tenant,partner,businessRole);}
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'OPENING','期初操作员')",role,tenant);
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        for(String code:permissions) {jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,code);}
        String hash=passwords.encode(PASSWORD);
        for(long operator:List.of(user,reviewerId)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",operator,tenant,operator==user?"operator":"reviewer","期初验收用户",hash);
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,operator,role);
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,operator,otherCompany);
        }
        for(long wh:List.of(warehouse,otherWarehouse)) {
            jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",wh,tenant,company,"W"+wh,"期初仓");
            for(long operator:List.of(user,reviewerId)) {jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,operator,company,wh);}
        }
        for(long id:List.of(unit,box)) {jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,?)",id,tenant,id==unit?"件":"箱");}
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','期初分类','/')",category,tenant);
        for(long id:List.of(product,zeroProduct)) {jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,?,?,?)",id,tenant,category,"P"+id,"期初商品",unit);}
        for(long id:List.of(sku,zeroSku)) {
            jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,?,?,0)",id,tenant,id==sku?product:zeroProduct,"S"+id,id==sku?"期初商品":"零成本商品");
            jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,id,unit);
        }
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,10,0)",Ids.next(),tenant,sku,box);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'BANK','期初资金账户','bank')",account,tenant,company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        maker=new CompanyAccess(tenant,user,company,true,Set.of(),permissions);
        reviewer=new CompanyAccess(tenant,reviewerId,company,true,Set.of(),permissions);
    }
    @AfterEach void cleanupOpening() {
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("outbox_event","ledger_balance","journal_line","journal","settlement_allocation","partner_open_item","receivable","payable",
                "inventory_reservation_change","inventory_reservation","cost_adjustment","inventory_movement","inventory_balance","inventory_cost",
                "stock_count_line","stock_count","stock_adjustment_line","stock_adjustment","opening_entry","audit_log","approval_record","idempotency_record","document_sequence","cash_account_balance","cash_account",
                "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","sys_user_data_scope","warehouse","accounting_period",
                "sys_role_permission","sys_user_role","sys_role","sys_user","company_policy","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);
    }
    OpeningService.Stock stock(long wh,String qty,String total,Long version) {return new OpeningService.Stock("opening",wh,DAY,"期初建账",List.of(new OpeningService.StockLine(sku,unit,qty,total)),version);}
    OpeningService.Entry entry(String type,String direction,String value,Long version) {return new OpeningService.Entry(type,type.equals("cash")?null:partner,type.equals("cash")?account:null,direction,value,DAY,"期初建账",version);}
    Map<String,Object> command(CompanyAccess scope,boolean stock,long id,String action,long version) {return service.command(scope,stock,id,action,key(),new OpeningService.Version(version,null));}
    long count(String table) {return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
    BigDecimal cash() {return jdbc.queryForObject("SELECT balance FROM cash_account_balance WHERE cash_account_id=?",BigDecimal.class,account);}
    void assertZero() {for(String type:ReconciliationChecks.TYPES) {assertThat(checks.check(maker,type,type.equals("period_close")?period:null)).as(type).isEmpty();}}
    static String key() {return UUID.randomUUID().toString();}
    static long id(Map<String,Object> row) {return Long.parseLong(row.get("id").toString());}
    static long version(Map<String,Object> row) {return ((Number)row.get("version")).longValue();}
    @SuppressWarnings("unchecked") static List<Map<String,Object>> lines(Map<String,Object> row) {return (List<Map<String,Object>>)row.get("lines");}
    void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable work,String code) {assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.getErrorCode()).isEqualTo(code));}
}
