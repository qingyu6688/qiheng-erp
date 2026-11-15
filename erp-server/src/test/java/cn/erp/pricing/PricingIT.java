package cn.erp.pricing;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class PricingIT {
    @Autowired PricingService pricing;
    @Autowired JdbcTemplate jdbc;
    private CompanyAccess scope;
    private long partner,sku,unit,warehouse;

    @BeforeEach
    void prepare() {
        long tenant=Ids.next(),company=Ids.next(),category=Ids.next(),product=Ids.next();
        partner=Ids.next();sku=Ids.next();unit=Ids.next();warehouse=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"P"+tenant,"试算测试");
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,?,?)",company,tenant,"P","测试公司");
        jdbc.update("INSERT INTO warehouse(id,tenant_id,company_id,code,name) VALUES(?,?,?,?,?)",warehouse,tenant,company,"W","主仓");
        jdbc.update("INSERT INTO business_partner(id,tenant_id,code,name) VALUES(?,?,?,?)",partner,tenant,"C","客户供应商");
        jdbc.update("INSERT INTO partner_role(id,tenant_id,partner_id,role,price_tax_inclusive) VALUES(?,?,?,'customer',0),(?,?,?,'supplier',1)",
                Ids.next(),tenant,partner,Ids.next(),tenant,partner);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,?)",unit,tenant,"箱");
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,?,?,?)",category,tenant,"C","分类","/");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,?,?,?)",product,tenant,category,"P","商品",unit);
        jdbc.update("INSERT INTO sku(id,tenant_id,product_id,code,name,sale_price,purchase_price) VALUES(?,?,?,?,?,15,10)",sku,tenant,product,"S","规格");
        jdbc.update("INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base) VALUES(?,?,?,?,1,1)",Ids.next(),tenant,sku,unit);
        scope=new CompanyAccess(tenant,Ids.next(),company,true,Set.of(),Set.of("sales_order:create","purchase_order:create","cost:view"));
    }

    @Test
    void defaultsComeFromPartnerSkuAndUnitAndInputCalculationFieldsCannotOverrideThem() {
        var result=pricing.preview(scope,input("sales_order",null,null,null));
        assertThat(result.priceTaxInclusive()).isFalse();
        assertThat(result.totalAmount()).isEqualTo("33.90");
        assertThat(result.lines().getFirst().unitPrice()).isEqualTo("15.00000000");
        assertThat(result.lines().getFirst().baseQty()).isEqualTo("2.000000");
        assertThat(result.lines().getFirst().taxRate()).isEqualTo("0.130000");
    }

    @Test
    void taxChangesRequireIndependentPermissionAndAllReferencesStayInsideTenantAndCompany() {
        assertThatThrownBy(()->pricing.preview(scope,input("sales_order","10","0.09",null)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("TAX_RATE_CHANGE_FORBIDDEN"));
        var allowed=new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),true,Set.of(),
                Set.of("sales_order:create","document:change_tax_rate"));
        assertThat(pricing.preview(allowed,input("sales_order","10","0.09",null)).taxAmount()).isEqualTo("1.80");
        assertThatThrownBy(()->pricing.preview(scope,input("sales_order","10",null,Long.toString(Ids.next()))))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        var other=new CompanyAccess(Ids.next(),scope.userId(),scope.companyId(),true,Set.of(),scope.permissions());
        assertThatThrownBy(()->pricing.preview(other,input("sales_order","10",null,null)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void disabledSkuIsRejectedAndPurchaseReferencePriceNeedsFieldPermission() {
        var limited=new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),true,Set.of(),Set.of("purchase_order:create"));
        assertThatThrownBy(()->pricing.preview(limited,input("purchase_order",null,null,null)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(403));
        assertThat(pricing.preview(limited,input("purchase_order","10",null,null)).totalAmount()).isEqualTo("20.00");
        jdbc.update("UPDATE sku SET status='disabled' WHERE id=?",sku);
        assertThatThrownBy(()->pricing.preview(scope,input("sales_order","10",null,null)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getErrorCode()).isEqualTo("MASTER_DATA_DISABLED"));
    }

    private PricingModels.PreviewRequest input(String type,String price,String tax,String warehouseOverride) {
        return new PricingModels.PreviewRequest(type,Long.toString(partner),Long.toString(partner),null,"0",
                List.of(new PricingModels.PreviewLine(Long.toString(sku),Long.toString(unit),
                        warehouseOverride==null?Long.toString(warehouse):warehouseOverride,"2",price,null,null,null,tax)),List.of());
    }
}
