package cn.erp.catalog;

import java.util.List;
import org.springframework.stereotype.Service;
import cn.erp.identity.CompanyAccess;

/** 开放平台只读取公开的商品字段，沿用已有 SKU 命令。 */
@Service
public class OpenCatalog {
    public record Sku(String id,String productId,String code,String name,String spec,String status) {}
    public record Page(List<Sku> items,String nextCursor) {}
    private final CatalogService catalog;

    OpenCatalog(CatalogService catalog) { this.catalog=catalog; }

    public Page list(CompanyAccess actor,String keyword,String status,int limit,String cursor) {
        var page=catalog.list(actor,"sku",keyword,status,limit,cursor);
        return new Page(page.items().stream().map(OpenCatalog::view).toList(),page.nextCursor());
    }

    public Sku get(CompanyAccess actor,long id) { return view(catalog.detail(actor,"sku",id)); }

    public Sku create(CompanyAccess actor,String key,long product,String code,String name,String spec,tools.jackson.databind.JsonNode ext) {
        return view(catalog.create(actor,"sku",key,new CatalogModels.SkuInput(product,code,name,spec,null,null,null,"active",null,null,ext)));
    }

    private static Sku view(CatalogModels.Detail value) {
        return new Sku(value.id(),value.productId(),value.code(),value.name(),value.spec(),value.status());
    }
}
