package cn.erp.catalog;

import cn.erp.ErpApplication;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.util.ArrayList;
import java.util.HashSet;
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
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
@Transactional
class ProductCategoryFilterIT {
    @Autowired CatalogService catalog;
    @Autowired JdbcTemplate jdbc;
    CompanyAccess actor;
    long unit;
    long root;

    @BeforeEach
    void seed() {
        long tenant=Ids.next();
        actor=new CompanyAccess(tenant,Ids.next(),0,true,Set.of(),Set.of("product:view","product_category:view"));
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",tenant,"CAT-"+tenant,"商品分类查询企业");
        unit=Long.parseLong(catalog.create(actor,"unit",key(),new CatalogModels.UnitInput("件","active",null)).id());
        root=category(actor,null,"ROOT");
    }

    @Test
    void selectedCategoryIncludesOnlyDirectProductsUnlessChildrenAreRequested() {
        long child=category(actor,root,"CHILD");
        long grandchild=category(actor,child,"GRANDCHILD");
        long sibling=category(actor,null,"SIBLING");
        String direct=product(actor,root,"DIRECT","本级商品","active");
        String below=product(actor,child,"CHILD-P","子级商品","active");
        String deep=product(actor,grandchild,"GRAND-P","孙级商品","active");
        product(actor,sibling,"OTHER","其他分类商品","active");

        assertThat(catalog.listProducts(actor,null,null,20,null,root,false).items())
                .extracting(CatalogModels.Detail::id).containsExactly(direct);
        assertThat(catalog.listProducts(actor,null,null,20,null,root,true).items())
                .extracting(CatalogModels.Detail::id).containsExactly(deep,below,direct);
        assertThat(catalog.listProducts(actor,null,null,20,null,child,true).items())
                .extracting(CatalogModels.Detail::id).containsExactly(deep,below);
    }

    @Test
    void categoryKeywordAndStatusAreAppliedBeforeCursorPaging() {
        long child=category(actor,root,"CHILD");
        long grandchild=category(actor,child,"GRANDCHILD");
        long sibling=category(actor,null,"SIBLING");
        String direct=product(actor,root,"P1","目标商品一","active");
        product(actor,sibling,"OTHER","目标商品四","active");
        String below=product(actor,child,"P2","目标商品二","active");
        product(actor,child,"DISABLED","目标停用商品","disabled");
        product(actor,child,"UNMATCHED","其他商品","active");
        String deep=product(actor,grandchild,"P3","目标商品三","active");
        var ids=new ArrayList<String>();
        var visited=new HashSet<String>();
        String cursor=null;
        do {
            var page=catalog.listProducts(actor,"目标","active",1,cursor,root,true);
            assertThat(page.items()).hasSize(1);
            ids.add(page.items().getFirst().id());
            cursor=page.nextCursor();
            if (cursor!=null) { assertThat(visited.add(cursor)).isTrue(); }
        } while (cursor!=null);
        assertThat(ids).containsExactly(deep,below,direct);
        assertThat(catalog.listProducts(actor,"目标","disabled",20,null,root,true).items())
                .extracting(CatalogModels.Detail::code).containsExactly("DISABLED");
    }

    @Test
    void missingCategoryKeepsExistingListAndDoesNotRequireCategoryPermission() {
        long sibling=category(actor,null,"SIBLING");
        product(actor,root,"P1","第一个商品","active");
        product(actor,sibling,"P2","第二个商品","active");
        product(actor,root,"P3","第三个商品","active");
        var withoutCategory=new CompanyAccess(actor.tenantId(),actor.userId(),0,true,Set.of(),Set.of("product:view"));
        var original=catalog.list(withoutCategory,"product",null,null,2,null);
        assertThat(catalog.listProducts(withoutCategory,null,null,2,null,null,false)).isEqualTo(original);
        assertThat(catalog.listProducts(withoutCategory,null,null,2,null,null,true)).isEqualTo(original);
        assertThatThrownBy(() -> catalog.listProducts(withoutCategory,null,null,20,null,root,false))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(403));
    }

    @Test
    void foreignTenantCategoriesAreHiddenAndSameTenantCompaniesShareCatalogs() {
        String own=product(actor,root,"OWN","本企业商品","active");
        long foreignTenant=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,?)",foreignTenant,"CAT-"+foreignTenant,"其他企业");
        var foreign=new CompanyAccess(foreignTenant,actor.userId(),0,true,Set.of(),actor.permissions());
        long foreignRoot=category(foreign,null,"ROOT");
        assertThatThrownBy(() -> catalog.listProducts(actor,null,null,20,null,foreignRoot,true))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(404));
        assertThatThrownBy(() -> catalog.listProducts(foreign,null,null,20,null,root,true))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(404));
        var first=new CompanyAccess(actor.tenantId(),actor.userId(),Ids.next(),true,Set.of(),actor.permissions());
        var second=new CompanyAccess(actor.tenantId(),actor.userId(),Ids.next(),true,Set.of(),actor.permissions());
        var firstPage=catalog.listProducts(first,null,null,20,null,root,true);
        assertThat(firstPage.items()).extracting(CatalogModels.Detail::id).containsExactly(own);
        assertThat(catalog.listProducts(second,null,null,20,null,root,true)).isEqualTo(firstPage);
    }

    @Test
    void invalidCategoryCursorAndPageSizeAreRejected() {
        for (long category:List.of(0L,-1L)) {
            assertThatThrownBy(() -> catalog.listProducts(actor,null,null,20,null,category,true))
                    .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(400));
        }
        for (String cursor:List.of("wrong","0","-1")) {
            assertThatThrownBy(() -> catalog.listProducts(actor,null,null,20,cursor,root,true))
                    .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(400));
        }
        assertThatThrownBy(() -> catalog.listProducts(actor,null,null,201,null,root,true))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(400));
        assertThatThrownBy(() -> catalog.listProducts(actor,null,null,20,null,Ids.next(),true))
                .isInstanceOfSatisfying(ApiException.class,error -> assertThat(error.getStatus()).isEqualTo(404));
    }

    long category(CompanyAccess scope,Long parent,String code) {
        return Long.parseLong(catalog.create(scope,"product_category",key(),
                new CatalogModels.CategoryInput(code,code,parent,0,"active",null)).id());
    }

    String product(CompanyAccess scope,long category,String code,String name,String status) {
        return catalog.create(scope,"product",key(),new CatalogModels.ProductInput(category,code,name,"goods",unit,status,null)).id();
    }

    String key() { return UUID.randomUUID().toString(); }
}
