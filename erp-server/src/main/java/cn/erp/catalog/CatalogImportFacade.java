package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 导入仍经商品领域创建，沿用资料状态、价格权限和审计规则。 */
@Service
public class CatalogImportFacade {
    public record Input(String productCode,String code,String name,String spec,String defaultTaxRate,
                        String purchasePrice,String salePrice,String status) {}
    private final JdbcTemplate jdbc;
    private final CatalogService service;
    private final Validator validator;
    public CatalogImportFacade(JdbcTemplate jdbc,CatalogService service,Validator validator) {
        this.jdbc=jdbc;this.service=service;this.validator=validator;
    }
    @Transactional
    public String create(CompanyAccess scope,String key,Input input) {
        if(!scope.hasPermission("sku:create"))throw new ApiException(403,"FORBIDDEN","没有创建 SKU 的权限");
        var products=jdbc.queryForList("SELECT id FROM product WHERE tenant_id=? AND code=?",Long.class,scope.tenantId(),input.productCode());
        if(products.isEmpty())throw new ApiException(400,"VALIDATION_FAILED","所属商品编码不存在",java.util.Map.of("columnName","productCode"));
        var value=new CatalogModels.SkuInput(products.getFirst(),input.code(),input.name(),input.spec(),
                input.defaultTaxRate(),input.purchasePrice(),input.salePrice(),input.status(),null);
        var errors=validator.validate(value);
        if(!errors.isEmpty()) {
            var error=errors.stream().sorted(java.util.Comparator.comparing(v->v.getPropertyPath().toString())).findFirst().orElseThrow();
            throw new ApiException(400,"VALIDATION_FAILED",error.getMessage(),java.util.Map.of("columnName",error.getPropertyPath().toString()));
        }
        return service.create(scope,"sku",key,value).id();
    }
}
