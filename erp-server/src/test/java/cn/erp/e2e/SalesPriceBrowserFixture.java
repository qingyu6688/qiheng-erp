package cn.erp.e2e;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import cn.erp.shared.Ids;

/** 仅准备无成本权限的验收用户，购销、调价与红字事实全部通过真实接口产生。 */
@Component
@Profile("e2e & !prod & !production")
class SalesPriceBrowserFixture {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String tenantCode, password, databaseUrl;
    SalesPriceBrowserFixture(JdbcTemplate jdbc, PasswordEncoder passwords,
            @Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,
            @Value("${ERP_E2E_PASSWORD:}") String password,
            @Value("${spring.datasource.url}") String databaseUrl) {
        this.jdbc=jdbc;this.passwords=passwords;this.tenantCode=tenantCode;this.password=password;this.databaseUrl=databaseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepare() {
        if(!tenantCode.matches("E2E-[a-f0-9]{12}") || password.length()<16
                || !databaseUrl.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?"))
            throw new IllegalStateException("销售调价浏览器夹具仅允许本机隔离测试库及随机测试企业");
        long tenant=jdbc.queryForObject("SELECT id FROM tenant WHERE code=?",Long.class,tenantCode);
        long company=jdbc.queryForObject("SELECT id FROM company WHERE tenant_id=? AND code='DEMO-A'",Long.class,tenant);
        var roles=jdbc.queryForList("SELECT id FROM sys_role WHERE tenant_id=? AND code='E2E_SALES_PRICE'",Long.class,tenant);
        long role=roles.isEmpty()?Ids.next():roles.getFirst();
        if(roles.isEmpty())jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'E2E_SALES_PRICE','无成本销售验收角色')",role,tenant);
        for(long permission:jdbc.queryForList("SELECT id FROM sys_permission WHERE code<>'cost:view'",Long.class))
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE tenant_id=? AND role_id=? AND permission_id=?",Long.class,tenant,role,permission)==0)
                jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",Ids.next(),tenant,role,permission);
        for(String name:List.of("sales-price-maker","sales-price-reviewer")) {
            var users=jdbc.queryForList("SELECT id FROM sys_user WHERE tenant_id=? AND username=?",Long.class,tenant,name);
            if(!users.isEmpty())continue;
            long user=Ids.next();
            jdbc.update("INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash) VALUES(?,?,?,?,?)",user,tenant,name,
                    name.endsWith("maker")?"销售调价制单员":"销售调价复核员",passwords.encode(password));
            jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",Ids.next(),tenant,user,role);
            jdbc.update("INSERT INTO org_user_data_scope(id,tenant_id,user_id,scope_type,company_id) VALUES(?,?,?,'company',?)",Ids.next(),tenant,user,company);
        }
    }
}
