package cn.erp.e2e;

import cn.erp.accounting.LedgerService;
import cn.erp.identity.CompanyAccess;
import cn.erp.inventory.InventoryService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.inventory.InventoryModels.*;

/** 只随测试 classpath 启动，生产包不包含浏览器验收数据。 */
@Component
@Profile("e2e & !prod & !production")
public class SalesBrowserFixture {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final InventoryService inventory;
    private final LedgerService ledger;
    private final String tenantCode,password,databaseUrl;

    public SalesBrowserFixture(JdbcTemplate jdbc,PasswordEncoder passwords,InventoryService inventory,LedgerService ledger,
            @Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,@Value("${ERP_E2E_PASSWORD:}") String password,
            @Value("${spring.datasource.url}") String databaseUrl) {
        this.jdbc=jdbc;this.passwords=passwords;this.inventory=inventory;this.ledger=ledger;
        this.tenantCode=tenantCode;this.password=password;this.databaseUrl=databaseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepareSalesData() {
        if(!tenantCode.matches("E2E-[a-f0-9]{12}") || password.length()<16 ||
                !databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?")) {
            throw new IllegalStateException("浏览器验收夹具仅允许本机隔离测试库、随机测试企业和临时口令");
        }
        var admins=jdbc.queryForList("""
                SELECT t.id tenant_id,u.id user_id FROM tenant t JOIN sys_user u ON u.tenant_id=t.id
                WHERE t.code=? AND u.username='admin' AND u.status='active'
                """,tenantCode);
        if(admins.size()!=1) {throw new IllegalStateException("浏览器验收企业尚未完成开发账号初始化");}
        long tenant=number(admins.getFirst(),"tenant_id"),admin=number(admins.getFirst(),"user_id");
        long company=jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='DEMO-A'",Long.class,tenant);
        prepareMfaUser(tenant,company);
        prepareSupplyUser(tenant,company);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM business_partner WHERE tenant_id=? AND code='E2E-CUSTOMER'",Long.class,tenant)>0) {return;}
        long reviewer=Ids.next();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'reviewer','验收复核员',?)",reviewer,tenant,passwords.encode(password));
        for(long role:jdbc.queryForList("SELECT role_id FROM sys_user_role WHERE tenant_id=? AND user_id=?",Long.class,tenant,admin)) {
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,reviewer,role);
        }
        for(var range:jdbc.queryForList("SELECT scope_type,company_id,warehouse_id FROM org_user_data_scope WHERE tenant_id=? AND user_id=?",tenant,admin)) {
            jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id,warehouse_id) VALUES(?,?,?,?,?,?)",
                    Ids.next(),tenant,reviewer,range.get("scope_type"),range.get("company_id"),range.get("warehouse_id"));
        }
        long warehouse=Ids.next(),customer=Ids.next(),unit=Ids.next(),category=Ids.next(),product=Ids.next(),sku=Ids.next(),account=Ids.next(),period=Ids.next();
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'E2E-WAREHOUSE','验收销售仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'E2E-CUSTOMER','验收销售客户')",customer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'customer',1)",Ids.next(),tenant,customer);
        // 调价等用例会留下未核销往来，销售闭环的结清断言使用独立客户。
        long flowCustomer=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'E2E-SALES-FLOW-CUSTOMER','销售闭环验收客户')",flowCustomer,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'customer',1)",Ids.next(),tenant,flowCustomer);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'验收件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'E2E-CATEGORY','验收分类','/E2E-CATEGORY')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'E2E-PRODUCT','验收商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price,purchase_price,default_tax_rate) VALUES(?,?,?,'E2E-SKU','验收商品',100,10,.13)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,'E2E-BANK','验收银行','bank')",account,tenant,company);
        jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        LocalDate day=LocalDate.now(ZoneId.of("Asia/Shanghai"));YearMonth month=YearMonth.from(day);
        jdbc.update("INSERT INTO accounting_period(id,tenant_id,company_id,period,start_date,end_date) VALUES(?,?,?,?,?,?)",
                period,tenant,company,month.format(DateTimeFormatter.ofPattern("uuuuMM")),month.atDay(1),month.atEndOfMonth());
        var permissions=new HashSet<>(jdbc.queryForList("SELECT code FROM sys_permission",String.class));
        var scope=new CompanyAccess(tenant,admin,company,true,Set.of(),permissions);
        long opening=Ids.next();
        inventory.receive(scope,new Posting(new Key(warehouse,sku),new Source("opening_entry",opening,opening),day,period,"opening_in"),new BigDecimal("20"),new BigDecimal("200.00"));
        ledger.post(scope,new LedgerService.Posting("opening_entry",opening,0,period,day,List.of(
                new LedgerService.Line("INVENTORY","D",new BigDecimal("200.00"),null,null,warehouse,sku,opening,"浏览器验收库存期初"),
                new LedgerService.Line("OPENING_EQUITY","C",new BigDecimal("200.00"),null,null,null,null,opening,"浏览器验收库存期初"))));
    }

    /** 独立账号仅访问验收公司，避免安全用例消耗其他业务场景的登录配额。 */
    private void prepareMfaUser(long tenant,long company) {
        if (!jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND username='mfa_tester'",Long.class,tenant).isEmpty()) return;
        long user=Ids.next(), role=Ids.next();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'mfa_tester','验收安全账号',?)",
                user,tenant,passwords.encode(password));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'E2E_MFA_TESTER','安全验收角色')",role,tenant);
        jdbc.update("""
                INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                SELECT ?,?,?,id FROM sys_permission WHERE code='warehouse:view'
                """,Ids.next(),tenant,role);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",
                Ids.next(),tenant,user,company);
    }

    /** 供应链浏览器用例使用独立账号，避免与其它用例争用管理员的登录限额。 */
    private void prepareSupplyUser(long tenant,long company) {
        if (!jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND username='supply_tester'",Long.class,tenant).isEmpty()) return;
        long user=Ids.next(),role=Ids.next();
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'supply_tester','供应链验收员',?)",
                user,tenant,passwords.encode(password));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'E2E_SUPPLY_TESTER','供应链验收角色')",role,tenant);
        var permissions=jdbc.queryForList("""
                SELECT id FROM sys_permission WHERE code IN ('warehouse:view','warehouse:create','warehouse:update','wms_location:view',
                    'wms_location:manage','wms_task:view','wms_task:configure','partner:view','einv:view','einv:create',
                    'einv:inbound','einv:manage','label:print','wms_move:view','wms_pack:view','wms_wave:view','stock_count:view')
                """,Long.class);
        for(long permission:permissions)
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",
                Ids.next(),tenant,user,company);
    }

    private long number(Map<String,Object> row,String field) {return ((Number)row.get(field)).longValue();}
}
