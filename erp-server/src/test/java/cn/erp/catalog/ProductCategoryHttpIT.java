package cn.erp.catalog;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes=ErpApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ProductCategoryHttpIT {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    long tenant,foreignTenant,company,user,role,root,child,foreignCategory,directProduct,childProduct;
    String password=UUID.randomUUID().toString();
    final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
    final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeEach
    void seed() {
        tenant=Ids.next();foreignTenant=Ids.next();company=Ids.next();user=Ids.next();role=Ids.next();
        root=Ids.next();child=Ids.next();foreignCategory=Ids.next();directProduct=Ids.next();childProduct=Ids.next();
        long unit=Ids.next(),foreignUnit=Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'分类查询企业'),(?,?,'其他企业')",
                tenant,"CAT-HTTP-"+tenant,foreignTenant,"CAT-HTTP-"+foreignTenant);
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'A','甲公司')",company,tenant);
        jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash,must_change_password) VALUES(?,?,'catalog','商品资料员',?,0)",
                user,tenant,passwords.encode(password));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'CATALOG','商品资料查看')",role,tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
        for (String permission:List.of("product:view","product_category:view")) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) SELECT ?,?,?,id FROM sys_permission WHERE code=?",
                    Ids.next(),tenant,role,permission);
        }
        jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",
                Ids.next(),tenant,user,company);
        jdbc.update("INSERT INTO unit(id,tenant_id,name) VALUES(?,?,'件'),(?,?,'件')",unit,tenant,foreignUnit,foreignTenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,code,name,path) VALUES(?,?,'ROOT','本级分类','/'),(?,?,'FOREIGN','其他企业分类','/')",
                root,tenant,foreignCategory,foreignTenant);
        jdbc.update("INSERT INTO product_category(id,tenant_id,parent_id,code,name,path) VALUES(?,?,?,'CHILD','子分类',?)",
                child,tenant,root,"/"+root+"/");
        jdbc.update("INSERT INTO product(id,tenant_id,category_id,code,name,base_unit_id) VALUES(?,?,?,'ROOT-P','本级商品',?),(?,?,?,'CHILD-P','子级商品',?),(?,?,?,'FOREIGN-P','其他企业商品',?)",
                directProduct,tenant,root,unit,childProduct,tenant,child,unit,Ids.next(),foreignTenant,foreignCategory,foreignUnit);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM product WHERE tenant_id IN (?,?)",tenant,foreignTenant);
        for (long id:jdbc.queryForList("SELECT id FROM product_category WHERE tenant_id IN (?,?) ORDER BY id DESC",Long.class,tenant,foreignTenant)) {
            jdbc.update("DELETE FROM product_category WHERE id=?",id);
        }
        for (String table:List.of("unit","audit_log","idempotency_record","org_user_data_scope","sys_role_permission","sys_user_role","sys_role","sys_user","company")) {
            jdbc.update("DELETE FROM "+table+" WHERE tenant_id IN (?,?)",tenant,foreignTenant);
        }
        jdbc.update("DELETE FROM tenant WHERE id IN (?,?)",tenant,foreignTenant);
    }

    @Test
    void categoryFilteringPagesOnServerAndHidesForeignTenantCategories() throws Exception {
        login();
        var unfiltered=get("/products",200).path("data").path("items");
        assertThat(unfiltered.size()).isEqualTo(2);
        var direct=get("/products?categoryId="+root,200).path("data").path("items");
        assertThat(direct.size()).isEqualTo(1);
        assertThat(direct.get(0).path("id").asString()).isEqualTo(Long.toString(directProduct));
        String path="/products?categoryId="+root+"&includeChildren=true&limit=1";
        var first=get(path,200).path("data");
        assertThat(first.path("items").get(0).path("id").asString()).isEqualTo(Long.toString(childProduct));
        var last=get(path+"&cursor="+first.path("nextCursor").asString(),200).path("data");
        assertThat(last.path("items").get(0).path("id").asString()).isEqualTo(Long.toString(directProduct));
        assertThat(last.path("nextCursor").isNull()).isTrue();
        assertThat(get("/products?includeChildren=true",200).path("data").path("items").size()).isEqualTo(2);
        get("/products?categoryId="+foreignCategory+"&includeChildren=true",404);
        for (String query:List.of("categoryId=wrong","categoryId=0","categoryId=-1","includeChildren=wrong","categoryId="+root+"&cursor=wrong")) {
            get("/products?"+query,400);
        }
    }

    @Test
    void productAndCategoryPermissionsAreCheckedOnEveryRequest() throws Exception {
        get("/products",401);
        login();
        get("/products?categoryId="+root,200);
        revoke("product_category:view");
        get("/products",200);
        get("/products?includeChildren=true",200);
        get("/product-categories",403);
        get("/products?categoryId="+root,403);
        revoke("product:view");
        get("/products",403);
    }

    void revoke(String permission) {
        jdbc.update("DELETE rp FROM sys_role_permission rp JOIN sys_permission p ON p.id=rp.permission_id WHERE rp.tenant_id=? AND rp.role_id=? AND p.code=?",
                tenant,role,permission);
    }

    void login() throws Exception {
        String body=json.createObjectNode().put("tenantCode","CAT-HTTP-"+tenant).put("username","catalog").put("password",password).toString();
        var request=HttpRequest.newBuilder(endpoint("/auth/login")).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    JsonNode get(String path,int expected) throws Exception {
        var request=HttpRequest.newBuilder(endpoint(path)).timeout(Duration.ofSeconds(15)).GET().build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path+"："+response.body()).isEqualTo(expected);
        return json.readTree(response.body());
    }

    URI endpoint(String path) { return URI.create("http://127.0.0.1:"+port+"/api/v1"+path); }
}
