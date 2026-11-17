package cn.erp.catalog;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.jdbc.SqlConfig;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
@Sql(scripts="/constraints/fixtures.sql",config=@SqlConfig(encoding="UTF-8"))
class SupplyCatalogIT {
    private final CompanyAccess actor=new CompanyAccess(70101,1,70201,true,Set.of(),Set.of("brand:view"));
    private final CompanyAccess foreign=new CompanyAccess(70102,1,70203,true,Set.of(),Set.of("brand:view"));
    @Autowired SupplyCatalogService catalog;

    @Test
    void brandIdentityIsTenantBoundAndSkuConfigurationAllowsAnEmptySku() {
        var brand=catalog.saveBrand(actor,new SupplyCatalogService.BrandInput(null,"CO-1","仓储品牌",0,true,null));
        var id=brand.get("id").toString();
        var config=new SupplyCatalogService.SkuInput(new BigDecimal("0.120000"),id,true,true,30,7,"fefo",0);
        assertThat(catalog.saveSku(actor,71002,config)).containsEntry("brandId",id).containsEntry("batchManaged",true)
                .containsEntry("serialManaged",true).containsEntry("issueStrategy","fefo");
        assertThat(catalog.brands(foreign,null,false)).isEmpty();
        assertThatThrownBy(()->catalog.saveBrand(foreign,new SupplyCatalogService.BrandInput(id,"CO-1","替换名称",0,true,0L)))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->catalog.sku(foreign,71002))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.getStatus()).isEqualTo(404));
        assertThatThrownBy(()->catalog.saveSku(actor,71001,config)).isInstanceOf(ApiException.class).hasMessageContaining("存量");
    }
}
