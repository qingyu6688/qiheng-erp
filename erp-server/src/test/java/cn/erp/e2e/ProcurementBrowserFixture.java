package cn.erp.e2e;

import cn.erp.shared.Ids;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 采购验收只准备主数据，库存与资金必须通过真实期初页面录入。 */
@Component
@Profile("e2e & !prod & !production")
public class ProcurementBrowserFixture {
    private final JdbcTemplate jdbc;
    private final String tenantCode,password,databaseUrl;
    public ProcurementBrowserFixture(JdbcTemplate jdbc,@Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,
            @Value("${ERP_E2E_PASSWORD:}") String password,@Value("${spring.datasource.url}") String databaseUrl) {
        this.jdbc=jdbc;this.tenantCode=tenantCode;this.password=password;this.databaseUrl=databaseUrl;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepareProcurementData() {
        if(!tenantCode.matches("E2E-[a-f0-9]{12}")||password.length()<16||
            !databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?")) {
            throw new IllegalStateException("采购验收夹具仅允许本机独立测试库、随机测试企业和临时口令");
        }
        long tenant=jdbc.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,tenantCode);
        long company=jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='DEMO-A'",Long.class,tenant);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM business_partner WHERE tenant_id=? AND code='E2E-M5-SUPPLIER'",Long.class,tenant)>0) {return;}
        long supplier=Ids.next(),warehouse=Ids.next(),unit=Ids.next(),category=Ids.next(),product=Ids.next(),sku=Ids.next();
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,'E2E-M5-SUPPLIER','采购验收供应商')",supplier,tenant);
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'supplier',1)",Ids.next(),tenant,supplier);
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,'E2E-M5-WAREHOUSE','采购验收仓')",warehouse,tenant,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'采购验收件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'E2E-M5-CATEGORY','采购验收分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'E2E-M5-PRODUCT','采购验收商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,purchase_price,sale_price,default_tax_rate) VALUES(?,?,?,'E2E-M5-SKU','采购验收商品',14,20,0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        for(String code:List.of("A","B")) {
            long account=Ids.next();
            jdbc.update("INSERT INTO cash_account(id,tenant_id,company_id,code,name,account_type) VALUES(?,?,?,?,?,'bank')",account,tenant,company,"E2E-M5-BANK-"+code,"采购验收银行"+(code.equals("A")?"甲":"乙"));
            jdbc.update("INSERT INTO cash_account_balance(id,tenant_id,company_id,cash_account_id) VALUES(?,?,?,?)",Ids.next(),tenant,company,account);
        }
    }
}
