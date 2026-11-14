package cn.erp.identity;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.shared.Ids;

/** 仅在首次本地开发时创建演示组织和仓库管理账号，已有企业的数据由业务页面维护。 */
@Component
@Profile("dev & !prod & !production")
class DevAccountBootstrap implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String initialPassword;

    DevAccountBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords,
                        @Value("${ERP_DEV_ADMIN_PASSWORD:}") String initialPassword) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.initialPassword = initialPassword;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (initialPassword.isEmpty()) {
            return;
        }
        CredentialsService.validateNewPassword(initialPassword);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE code = 'DEMO'", Long.class) > 0) {
            return;
        }
        long tenant = Ids.next();
        long user = Ids.next();
        long role = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES (?,'DEMO','演示企业')", tenant);
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)
                VALUES (?,?,'admin','仓库管理员',?)
                """, user, tenant, passwords.encode(initialPassword));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,'WAREHOUSE_ADMIN','仓库管理员')",
                role, tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES (?,?,?,?)",
                Ids.next(), tenant, user, role);
        for (String action : List.of("view", "create", "update")) {
            int inserted = jdbc.update("""
                    INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                    SELECT ?,?,?,id FROM sys_permission WHERE code = ?
                    """, Ids.next(), tenant, role, "warehouse:" + action);
            if (inserted != 1) {
                throw new IllegalStateException("仓库权限初始化不完整，请先执行数据库迁移");
            }
        }
        addCompany(tenant, user, "DEMO-A", "演示一公司");
        addCompany(tenant, user, "DEMO-B", "演示二公司");
    }

    private void addCompany(long tenant, long user, String code, String name) {
        long company = Ids.next();
        jdbc.update("INSERT INTO company(id,tenant_id,code,name) VALUES (?,?,?,?)", company, tenant, code, name);
        jdbc.update("""
                INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id)
                VALUES (?,?,?,'company',?)
                """, Ids.next(), tenant, user, company);
    }
}
