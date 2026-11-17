package cn.erp.importing;

import cn.erp.documents.ObjectFiles;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

abstract class ImportTestData {
    static final LocalDate DAY=LocalDate.of(2026,11,1);
    static final String PASSWORD="ImportTest!2026";
    @Autowired JdbcTemplate jdbc;
    @Autowired ImportJobs jobs;
    @Autowired ImportProcessor processor;
    @Autowired ObjectFiles files;
    @Autowired PasswordEncoder passwords;
    long tenant,company,otherCompany,warehouse,otherWarehouse,unit,category,product,sku,user,reviewerId,role,period;
    CompanyAccess maker,reviewer;
    @BeforeEach void seed() {
        tenant=Ids.next();company=Ids.next();otherCompany=Ids.next();warehouse=Ids.next();otherWarehouse=Ids.next();unit=Ids.next();category=Ids.next();product=Ids.next();sku=Ids.next();user=Ids.next();reviewerId=Ids.next();role=Ids.next();period=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'导入验收')",tenant,"IMPORT"+tenant);
        for(long id:List.of(company,otherCompany))jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,'导入公司')",id,tenant,id==company?"A":"B");
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'IMPORT','导入操作员')",role,tenant);
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        for(String code:permissions)jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",Ids.next(),tenant,role,code);
        for(long operator:List.of(user,reviewerId)) {
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",operator,tenant,operator==user?"operator":"reviewer","导入验收用户",passwords.encode(PASSWORD));
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,operator,role);
            jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,operator,otherCompany);
        }
        for(long wh:List.of(warehouse,otherWarehouse)) {
            jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",wh,tenant,company,"W"+wh,"导入仓");
            for(long operator:List.of(user,reviewerId))jdbc.update("INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,'warehouse',?,?)",Ids.next(),tenant,operator,company,wh);
        }
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'CAT','分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'PRODUCT','商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,default_tax_rate) VALUES(?,?,?,'SKU','已有SKU',0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,'202611','2026-11-01','2026-11-30')",period,tenant,company);
        maker=new CompanyAccess(tenant,user,company,false,Set.of(warehouse,otherWarehouse),permissions);
        reviewer=new CompanyAccess(tenant,reviewerId,company,false,Set.of(warehouse,otherWarehouse),permissions);
    }
    @AfterEach void cleanup() {
        var keys=jdbc.queryForList("SELECT storage_key FROM attachment WHERE tenant_id=?",String.class,tenant);
        jdbc.update("DELETE FROM inbox_event WHERE event_id IN(SELECT id FROM outbox_event WHERE tenant_id=?)",tenant);
        for(String table:List.of("org_role_data_scope_dept","org_role_data_scope","org_user_department","org_user_company","org_department","import_job_target","import_job_error","import_job","attachment","outbox_event","ledger_balance","journal_line","journal","partner_open_item",
                "inventory_reservation_change","inventory_reservation","cost_adjustment","inv_movement_hold","inventory_movement","inventory_balance","inventory_cost",
                "stock_adjustment_line","stock_adjustment","audit_log","approval_record","idempotency_record","document_sequence",
                "unit_conversion","sku","product","product_category","unit","partner_role","business_partner","sys_user_data_scope","warehouse","accounting_period",
                "sys_role_permission","sys_user_role","sys_role","sys_user","company_policy","company"))jdbc.update("DELETE FROM "+table+" WHERE tenant_id=?",tenant);
        jdbc.update("DELETE FROM tenant WHERE id=?",tenant);for(String value:keys)files.delete(value);
    }
    Map<String,Object> create(String type,String data) {return jobs.create(maker,key(),type,type.equals("opening_stock")?warehouse:null,type.equals("opening_stock")?DAY:null,"input.csv",csv(type,data));}
    byte[] csv(String type,String data){return ("\uFEFF"+String.join(",",ImportCsv.headers(type))+"\r\n"+data).getBytes(StandardCharsets.UTF_8);}
    long count(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=?",Long.class,tenant);}
    static String key(){return UUID.randomUUID().toString();}
    static long id(Map<String,Object> row){return Long.parseLong(row.get("id").toString());}
    static long version(Map<String,Object> row){return ((Number)row.get("version")).longValue();}
}
