package cn.erp.e2e;

import cn.erp.shared.Ids;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 仅建立库存作业的主数据，库存数量和成本必须经过浏览器业务录入。 */
@Component
@Profile("dev & e2e & !prod & !production")
public class InventoryBrowserFixture {
    private final JdbcTemplate jdbc;
    private final String tenantCode,password,databaseUrl;
    public InventoryBrowserFixture(JdbcTemplate jdbc,@Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,
            @Value("${ERP_E2E_PASSWORD:}") String password,@Value("${spring.datasource.url}") String databaseUrl) {
        this.jdbc=jdbc;this.tenantCode=tenantCode;this.password=password;this.databaseUrl=databaseUrl;
    }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepareInventoryData() {
        if(!tenantCode.matches("E2E-[a-f0-9]{12}")||password.length()<16||
            !databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?")) {
            throw new IllegalStateException("库存浏览器验收夹具仅允许本机独立测试库、随机测试企业和临时口令");
        }
        long tenant=jdbc.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,tenantCode);
        long company=jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='DEMO-A'",Long.class,tenant);
        if(jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE tenant_id=? AND code='E2E-M6-SKU'",Long.class,tenant)>0)return;
        for(String code:new String[]{"A","B"})jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",Ids.next(),tenant,company,"E2E-M6-WH-"+code,"库存验收"+(code.equals("A")?"甲":"乙")+"仓");
        long unit=Ids.next(),category=Ids.next(),product=Ids.next(),sku=Ids.next();
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'库存验收件')",unit,tenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'E2E-M6-CAT','库存验收分类','/')",category,tenant);
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'E2E-M6-PROD','库存验收商品',?)",product,tenant,category,unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,purchase_price,sale_price,default_tax_rate) VALUES(?,?,?,'E2E-M6-SKU','库存验收商品',10,20,0)",sku,tenant,product);
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
    }
}
