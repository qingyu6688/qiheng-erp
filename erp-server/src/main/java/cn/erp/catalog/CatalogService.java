package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CatalogService {
    private final JdbcTemplate jdbc;
    private final CatalogQueries queries;
    private final AuditService audit;
    private final BusinessCommands commands;

    CatalogService(JdbcTemplate jdbc,CatalogQueries queries,AuditService audit,BusinessCommands commands) {
        this.jdbc=jdbc; this.queries=queries; this.audit=audit; this.commands=commands;
    }

    CatalogModels.Page list(CompanyAccess scope,String kind,String keyword,String status,int limit,String cursor) {
        return kind.equals("product_category") ? queries.categories(scope) : queries.list(scope,kind,keyword,status,limit,cursor);
    }

    CatalogModels.Detail detail(CompanyAccess scope,String kind,long id) { return queries.detail(scope,kind,id); }
    CatalogModels.Scan scan(CompanyAccess scope,String barcode) { return queries.scan(scope,barcode); }

    @Transactional
    CatalogModels.Detail create(CompanyAccess scope,String kind,String key,Object input) {
        checkPurchasePrice(scope, input);
        return commands.execute(scope,kind+".create",key,input,201,
                () -> CatalogVisibility.withoutPurchasePrice(createRecord(scope,kind,input)),CatalogModels.Detail.class);
    }

    private CatalogModels.Detail createRecord(CompanyAccess scope,String kind,Object input) {
        long id=Ids.next();
        try {
            switch(kind) {
                case "unit" -> {
                    var v=(CatalogModels.UnitInput)input;
                    jdbc.update("INSERT INTO unit(id,tenant_id,name,status,created_by,updated_by) VALUES(?,?,?,?,?,?)",
                            id,scope.tenantId(),v.name().trim(),status(v.status()),scope.userId(),scope.userId());
                }
                case "product_category" -> insertCategory(scope,id,(CatalogModels.CategoryInput)input);
                case "product" -> insertProduct(scope,id,(CatalogModels.ProductInput)input);
                case "sku" -> insertSku(scope,id,(CatalogModels.SkuInput)input);
                default -> throw new IllegalArgumentException("商品资源类型无效");
            }
        } catch (DuplicateKeyException ex) { throw duplicate(); }
        return changed(scope,kind,id,"create",null);
    }

    private void insertCategory(CompanyAccess scope,long id,CatalogModels.CategoryInput v) {
        String path=parentPath(scope,v.parentId(),id);
        jdbc.update("""
                INSERT INTO product_category(id,tenant_id,parent_id,code,name,path,sort_no,status,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),v.parentId(),v.code().trim(),v.name().trim(),path,
                v.sortNo()==null?0:v.sortNo(),status(v.status()),scope.userId(),scope.userId());
    }

    private void insertProduct(CompanyAccess scope,long id,CatalogModels.ProductInput v) {
        active(scope,"product_category",v.categoryId());
        active(scope,"unit",v.baseUnitId());
        jdbc.update("""
                INSERT INTO product(id,tenant_id,category_id,code,name,item_type,base_unit_id,status,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),v.categoryId(),v.code().trim(),v.name().trim(),
                v.itemType()==null?"goods":v.itemType(),v.baseUnitId(),status(v.status()),scope.userId(),scope.userId());
    }

    private void insertSku(CompanyAccess scope,long id,CatalogModels.SkuInput v) {
        var product=active(scope,"product",v.productId());
        active(scope,"product_category",Long.parseLong(product.categoryId()));
        active(scope,"unit",Long.parseLong(product.baseUnitId()));
        jdbc.update("""
                INSERT INTO sku(id,tenant_id,product_id,code,name,spec,default_tax_rate,purchase_price,sale_price,status,created_by,updated_by,min_sale_price)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),v.productId(),v.code().trim(),v.name().trim(),v.spec(),
                v.defaultTaxRate()==null?"0.130000":v.defaultTaxRate(),v.purchasePrice(),v.salePrice(),
                status(v.status()),scope.userId(),scope.userId(),v.minSalePrice()==null?"0":v.minSalePrice());
        jdbc.update("""
                INSERT INTO unit_conversion(id,tenant_id,sku_id,unit_id,rate_to_base,is_base,created_by,updated_by)
                VALUES(?,?,?,?,1,1,?,?)
                """,Ids.next(),scope.tenantId(),id,product.baseUnitId(),scope.userId(),scope.userId());
    }

    @Transactional
    CatalogModels.Detail edit(CompanyAccess scope,String kind,long id,Object input,Long version) {
        checkPurchasePrice(scope, input);
        if (kind.equals("product_category")) { lockCategories(scope); }
        var before=queries.find(scope,kind,id,true);
        checkVersion(before,version);
        try {
            switch(kind) {
                case "unit" -> {
                    var v=(CatalogModels.UnitInput)input;
                    jdbc.update("UPDATE unit SET name=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=? AND version=?",
                            v.name().trim(),status(v.status()),scope.userId(),scope.tenantId(),id,version);
                }
                case "product_category" -> updateCategory(scope,id,(CatalogModels.CategoryInput)input);
                case "product" -> updateProduct(scope,id,(CatalogModels.ProductInput)input,before);
                case "sku" -> updateSku(scope,id,(CatalogModels.SkuInput)input,before);
                default -> throw new IllegalArgumentException("商品资源类型无效");
            }
        } catch (DuplicateKeyException ex) { throw duplicate(); }
        return changed(scope,kind,id,"update",before);
    }

    private void updateCategory(CompanyAccess scope,long id,CatalogModels.CategoryInput v) {
        String path=parentPath(scope,v.parentId(),id);
        String oldPath=jdbc.queryForObject("SELECT path FROM product_category WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),id);
        jdbc.update("""
                UPDATE product_category SET parent_id=?,code=?,name=?,path=?,sort_no=?,status=?,
                version=version+1,updated_by=? WHERE tenant_id=? AND id=? AND version=?
                """,v.parentId(),v.code().trim(),v.name().trim(),path,v.sortNo()==null?0:v.sortNo(),
                status(v.status()),scope.userId(),scope.tenantId(),id,v.version());
        if (!oldPath.equals(path)) {
            String prefix=oldPath+id+"/";
            String next=path+id+"/";
            var children=jdbc.queryForList("SELECT id,path FROM product_category WHERE tenant_id=? AND path LIKE ?",scope.tenantId(),prefix+"%");
            for (var child:children) {
                String childPath=next+child.get("path").toString().substring(prefix.length());
                if (childPath.length()>255) { throw invalid("分类层级过深"); }
                jdbc.update("UPDATE product_category SET path=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                        childPath,scope.userId(),scope.tenantId(),child.get("id"));
            }
        }
    }

    private void updateProduct(CompanyAccess scope,long id,CatalogModels.ProductInput v,CatalogModels.Detail before) {
        active(scope,"product_category",v.categoryId());
        active(scope,"unit",v.baseUnitId());
        if (!before.baseUnitId().equals(v.baseUnitId().toString()) &&
                jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE tenant_id=? AND product_id=?",Long.class,scope.tenantId(),id)>0) {
            throw dependency("商品已配置 SKU，基础单位不能更换");
        }
        jdbc.update("""
                UPDATE product SET category_id=?,code=?,name=?,item_type=?,base_unit_id=?,status=?,
                version=version+1,updated_by=? WHERE tenant_id=? AND id=? AND version=?
                """,v.categoryId(),v.code().trim(),v.name().trim(),v.itemType()==null?"goods":v.itemType(),
                v.baseUnitId(),status(v.status()),scope.userId(),scope.tenantId(),id,v.version());
    }

    private void updateSku(CompanyAccess scope,long id,CatalogModels.SkuInput v,CatalogModels.Detail before) {
        if (!before.productId().equals(v.productId().toString())) { throw dependency("SKU 创建后不能更换所属商品"); }
        var product=active(scope,"product",v.productId());
        active(scope,"product_category",Long.parseLong(product.categoryId()));
        jdbc.update("""
                UPDATE sku SET code=?,name=?,spec=?,default_tax_rate=?,purchase_price=COALESCE(?,purchase_price),sale_price=?,status=?,min_sale_price=COALESCE(?,min_sale_price),
                version=version+1,updated_by=? WHERE tenant_id=? AND id=? AND version=?
                """,v.code().trim(),v.name().trim(),v.spec(),v.defaultTaxRate()==null?"0.130000":v.defaultTaxRate(),
                v.purchasePrice(),v.salePrice(),status(v.status()),v.minSalePrice(),scope.userId(),scope.tenantId(),id,v.version());
    }

    @Transactional
    CatalogModels.Detail changeStatus(CompanyAccess scope,String kind,long id,String action,String key,long version) {
        return commands.execute(scope,kind+"."+action,key,List.of(id,version),200,() -> {
            var before=queries.find(scope,kind,id,true);
            checkVersion(before,version);
            String next=action.equals("enable")?"active":"disabled";
            if (next.equals(before.status())) { throw new ApiException(409,"STATE_CONFLICT","资料已处于目标状态"); }
            if (next.equals("active")) { checkActiveParents(scope,kind,before); }
            jdbc.update("UPDATE "+CatalogQueries.table(kind)+" SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                    next,scope.userId(),scope.tenantId(),id);
            return CatalogVisibility.withoutPurchasePrice(changed(scope,kind,id,action,before));
        },CatalogModels.Detail.class);
    }

    private void checkActiveParents(CompanyAccess scope,String kind,CatalogModels.Detail v) {
        switch(kind) {
            case "product_category" -> { if(v.parentId()!=null) { active(scope,"product_category",Long.parseLong(v.parentId())); } }
            case "product" -> { active(scope,"product_category",Long.parseLong(v.categoryId())); active(scope,"unit",Long.parseLong(v.baseUnitId())); }
            case "sku" -> {
                var product=active(scope,"product",Long.parseLong(v.productId()));
                active(scope,"product_category",Long.parseLong(product.categoryId()));
                active(scope,"unit",Long.parseLong(product.baseUnitId()));
            }
            default -> { }
        }
    }

    private String parentPath(CompanyAccess scope,Long parent,long id) {
        if (parent==null) { return "/"; }
        if (parent==id) { throw invalid("分类不能作为自己的上级"); }
        active(scope,"product_category",parent);
        String parentPath=jdbc.queryForObject("SELECT path FROM product_category WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),parent);
        if (parentPath.contains("/"+id+"/")) { throw invalid("分类上级不能是自己的下级"); }
        String path=parentPath+parent+"/";
        if (path.length()>235 || path.chars().filter(c -> c=='/').count()>16) { throw invalid("分类层级过深"); }
        return path;
    }

    private void lockCategories(CompanyAccess scope) {
        jdbc.queryForList("SELECT id FROM product_category WHERE tenant_id=? ORDER BY id FOR UPDATE",scope.tenantId());
    }

    CatalogModels.Detail active(CompanyAccess scope,String kind,long id) {
        var row=queries.find(scope,kind,id,true);
        if (!row.status().equals("active")) { throw new ApiException(422,"MASTER_DATA_DISABLED","所选资料已停用"); }
        return row;
    }

    private CatalogModels.Detail changed(CompanyAccess scope,String kind,long id,String action,Object before) {
        var result=queries.detail(scope,kind,id);
        audit.record(scope,kind+"."+action,kind,id,CatalogVisibility.safeAudit(before),CatalogVisibility.withoutPurchasePrice(result));
        return result;
    }

    static void checkVersion(CatalogModels.Detail value,Long version) {
        if (version==null || version<0) { throw invalid("请提供有效的版本号"); }
        if (version!=value.version()) {
            throw new ApiException(409,"VERSION_CONFLICT","资料已被他人修改，请刷新后重试",Map.of("currentVersion",value.version()));
        }
    }

    static String status(String value) { return value==null?"active":value; }
    private void checkPurchasePrice(CompanyAccess scope, Object input) {
        if (input instanceof CatalogModels.SkuInput sku && sku.purchasePrice() != null && !scope.hasPermission("cost:view")) {
            throw new ApiException(403,"FORBIDDEN","没有维护采购价格的权限");
        }
    }
    static ApiException invalid(String text) { return new ApiException(400,"VALIDATION_FAILED",text); }
    static ApiException dependency(String text) { return new ApiException(422,"DEPENDENCY_EXISTS",text); }
    static ApiException duplicate() { return new ApiException(409,"DUPLICATE","编码、名称或条码已存在"); }
}
