package cn.erp.catalog;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import cn.erp.shared.CommandResults;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class CatalogIT {
    @Autowired CatalogService catalog;
    @Autowired SkuDetailsService details;
    @Autowired CatalogAvailability availability;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired CommandResults commandResults;
    CompanyAccess scope;
    long unit;
    long category;
    long product;

    @BeforeEach
    void seed() {
        long tenant=Ids.next();
        scope=new CompanyAccess(tenant,Ids.next(),0,true,Set.of(),Set.of("unit:update","product_category:update","product:update","sku:update","cost:view"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"T"+tenant,"商品测试企业");
        unit=Long.parseLong(catalog.create(scope,"unit",key(),new CatalogModels.UnitInput("件","active",null)).id());
        category=Long.parseLong(catalog.create(scope,"product_category",key(),
                new CatalogModels.CategoryInput("C","商品分类",null,0,"active",null)).id());
        product=Long.parseLong(catalog.create(scope,"product",key(),
                new CatalogModels.ProductInput(category,"P","测试商品","goods",unit,"active",null)).id());
    }

    @Test
    void createsCatalogWithDurableIdempotencyAndAudit() {
        String key=key();
        var input=new CatalogModels.SkuInput(product,"SKU1","规格一","红色","0.130000","12.00000000","15.00000000","active",null);
        var first=catalog.create(scope,"sku",key,input);
        var again=catalog.create(scope,"sku",key,input);
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(first.units()).singleElement().satisfies(v -> {
            assertThat(v.unitId()).isEqualTo(Long.toString(unit));
            assertThat(v.rateToBase()).isEqualTo("1.000000");
            assertThat(v.isBase()).isTrue();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND object_type='sku'",
                Long.class,scope.tenantId())).isEqualTo(1);
    }

    @Test
    void managesConversionsBarcodesAndScan() {
        var sku=sku();
        long id=Long.parseLong(sku.id());
        long box=Long.parseLong(catalog.create(scope,"unit",key(),new CatalogModels.UnitInput("箱","active",null)).id());
        var updated=details.units(scope,id,new CatalogModels.UnitsInput(0L,List.of(
                new CatalogModels.Conversion(unit,"1",true),new CatalogModels.Conversion(box,"12",false))));
        assertThat(updated.version()).isEqualTo(1);
        var barcoded=details.barcodes(scope,id,new CatalogModels.BarcodesInput(1L,List.of(new CatalogModels.Barcode("690000001",box))));
        assertThat(barcoded.version()).isEqualTo(2);
        var scan=catalog.scan(scope,"690000001");
        assertThat(scan.sku().id()).isEqualTo(sku.id());
        assertThat(scan.rateToBase()).isEqualTo("12.000000");
        assertThat(scan.unitName()).isEqualTo("箱");
    }

    @Test
    void refusesReferencedConversionChanges() {
        var sku=sku();
        long id=Long.parseLong(sku.id());
        long box=Long.parseLong(catalog.create(scope,"unit",key(),new CatalogModels.UnitInput("箱","active",null)).id());
        details.units(scope,id,new CatalogModels.UnitsInput(0L,List.of(
                new CatalogModels.Conversion(unit,"1",true),new CatalogModels.Conversion(box,"12",false))));
        long company=Ids.next(), requester=Ids.next(), requisition=Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'C','公司')",company,scope.tenantId());
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,'u','申请人','test')",
                requester,scope.tenantId());
        jdbc.update("""
                INSERT INTO purchase_requisition(id,tenant_id,company_id,requisition_no,requester_id,business_date)
                VALUES(?,?,?,'PR1',?,CURRENT_DATE)
                """,requisition,scope.tenantId(),company,requester);
        jdbc.update("""
                INSERT INTO purchase_requisition_line(id,tenant_id,company_id,requisition_id,line_no,sku_id,unit_id,unit_rate,qty,base_qty)
                VALUES(?,?,?,?,1,?,?,12,1,12)
                """,Ids.next(),scope.tenantId(),company,requisition,id,box);
        assertThatThrownBy(() -> details.units(scope,id,new CatalogModels.UnitsInput(1L,List.of(
                new CatalogModels.Conversion(unit,"1",true),new CatalogModels.Conversion(box,"10",false)))))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo("DEPENDENCY_EXISTS"));
        assertThat(jdbc.queryForObject("SELECT rate_to_base FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND unit_id=?",
                String.class,scope.tenantId(),id,box)).isEqualTo("12.000000");
        assertThat(catalog.detail(scope,"sku",id).units()).filteredOn(v -> v.unitId().equals(Long.toString(box)))
                .singleElement().satisfies(v -> assertThat(v.referenced()).isTrue());
    }

    @Test
    void rejectsClassificationCycleAndKeepsTreeIntact() {
        long child=Long.parseLong(catalog.create(scope,"product_category",key(),
                new CatalogModels.CategoryInput("C2","子分类",category,0,"active",null)).id());
        assertThat(catalog.list(scope,"product_category",null,null,20,null).items().getFirst().children()).hasSize(1);
        assertThatThrownBy(() -> catalog.edit(scope,"product_category",category,
                new CatalogModels.CategoryInput("C","商品分类",child,0,"active",0L),0L))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus()).isEqualTo(400));
    }

    @Test
    void isolatesTenantAndChecksOptimisticVersion() {
        var foreign=new CompanyAccess(Ids.next(),scope.userId(),0,true,Set.of(),scope.permissions());
        assertThatThrownBy(() -> catalog.detail(foreign,"product",product))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getStatus()).isEqualTo(404));
        catalog.edit(scope,"unit",unit,new CatalogModels.UnitInput("只","active",0L),0L);
        assertThatThrownBy(() -> catalog.edit(scope,"unit",unit,new CatalogModels.UnitInput("个","active",0L),0L))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo("VERSION_CONFLICT"));
    }

    @Test
    void rejectsDisabledReferencesForNewSkuAndBusinessUse() {
        var sku=sku();
        catalog.changeStatus(scope,"product",product,"disable",key(),0);
        assertThatThrownBy(() -> catalog.create(scope,"sku",key(),
                new CatalogModels.SkuInput(product,"S2","规格二",null,"0.13",null,null,"active",null)))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo("MASTER_DATA_DISABLED"));
        assertThatThrownBy(() -> availability.requireActiveSku(scope,Long.parseLong(sku.id()),unit))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo("MASTER_DATA_DISABLED"));
    }

    @Test
    void cannotReplaceBaseUnitAfterSkuCreated() {
        sku();
        long other=Long.parseLong(catalog.create(scope,"unit",key(),new CatalogModels.UnitInput("公斤","active",null)).id());
        assertThatThrownBy(() -> catalog.edit(scope,"product",product,
                new CatalogModels.ProductInput(category,"P","测试商品","goods",other,"active",0L),0L))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.getErrorCode()).isEqualTo("DEPENDENCY_EXISTS"));
    }

    @Test
    void hidesPurchasePriceInListsProductDetailsAndBarcodeResults() {
        var sku = pricedSku();
        long id = Long.parseLong(sku.id());
        details.barcodes(scope,id,new CatalogModels.BarcodesInput(0L,List.of(new CatalogModels.Barcode("PRICE-SCAN",unit))));
        var restricted = withoutCostPermission();
        assertThat(catalog.detail(scope,"sku",id).purchasePrice()).isEqualTo("12.00000000");
        assertThat(json.readTree(json.writeValueAsString(catalog.detail(restricted,"sku",id))).has("purchasePrice")).isFalse();
        var products = json.readTree(json.writeValueAsString(catalog.detail(restricted,"product",product)));
        assertThat(products.path("skus").get(0).has("purchasePrice")).isFalse();
        var list = json.readTree(json.writeValueAsString(catalog.list(restricted,"sku",null,null,20,null)));
        assertThat(list.path("items").get(0).has("purchasePrice")).isFalse();
        var scan = json.readTree(json.writeValueAsString(catalog.scan(restricted,"PRICE-SCAN")));
        assertThat(scan.path("sku").has("purchasePrice")).isFalse();
        assertThat(scan.path("sku").path("salePrice").asText()).isEqualTo("15.00000000");
    }

    @Test
    void omittedPurchasePricePreservesValueAndUnauthorisedWritesFail() {
        var sku = pricedSku();
        long id = Long.parseLong(sku.id());
        var restricted = withoutCostPermission();
        catalog.edit(restricted,"sku",id,new CatalogModels.SkuInput(product,"PRICE","新名称",null,
                "0.13",null,"16.00","active",0L),0L);
        assertThat(catalog.detail(scope,"sku",id).purchasePrice()).isEqualTo("12.00000000");
        assertThatThrownBy(() -> catalog.edit(restricted,"sku",id,new CatalogModels.SkuInput(product,"PRICE","新名称",null,
                "0.13","20.00","16.00","active",1L),1L))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(403));
        assertThatThrownBy(() -> catalog.create(restricted,"sku",key(),new CatalogModels.SkuInput(product,"FORBIDDEN","规格",null,
                "0.13","20.00","16.00","active",null)))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(403));
    }

    @Test
    void commandResultsAndAuditKeepSensitivePricesOutOfStoredResponses() {
        var sku = pricedSku();
        long id = Long.parseLong(sku.id());
        String commandKey = key();
        var first = catalog.changeStatus(scope,"sku",id,"disable",commandKey,0L);
        var replay = catalog.changeStatus(withoutCostPermission(),"sku",id,"disable",commandKey,0L);
        assertThat(json.writeValueAsString(replay)).isEqualTo(json.writeValueAsString(first));
        assertThat(json.readTree(json.writeValueAsString(replay)).has("purchasePrice")).isFalse();
        var confirmed = commandResults.get(withoutCostPermission(),"sku.disable",commandKey);
        assertThat(json.readTree(json.writeValueAsString(confirmed)).path("response").has("purchasePrice")).isFalse();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM audit_log WHERE tenant_id=? AND
                (JSON_CONTAINS_PATH(before_data,'one','$.purchasePrice') OR JSON_CONTAINS_PATH(after_data,'one','$.purchasePrice'))
                """,Long.class,scope.tenantId())).isZero();
    }

    private CatalogModels.Detail pricedSku() {
        return catalog.create(scope,"sku",key(),new CatalogModels.SkuInput(product,"PRICE","有采购价规格",null,
                "0.13","12.00","15.00","active",null));
    }

    private CompanyAccess withoutCostPermission() {
        var permissions = new java.util.HashSet<>(scope.permissions());
        permissions.remove("cost:view");
        return new CompanyAccess(scope.tenantId(),scope.userId(),scope.companyId(),true,Set.of(),permissions);
    }

    private CatalogModels.Detail sku() {
        return catalog.create(scope,"sku",key(),new CatalogModels.SkuInput(product,"S","规格一",null,"0.13",null,null,"active",null));
    }

    private static String key() { return UUID.randomUUID().toString(); }
}
