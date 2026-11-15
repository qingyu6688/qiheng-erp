package cn.erp.identity;


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
    private final String tenantCode;

    DevAccountBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords,
                        @Value("${ERP_DEV_ADMIN_PASSWORD:}") String initialPassword,
                        @Value("${ERP_DEV_TENANT_CODE:DEMO}") String tenantCode) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.initialPassword = initialPassword;
        this.tenantCode = tenantCode;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (initialPassword.isEmpty()) {
            return;
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE code = ?", Long.class,tenantCode) > 0) {
            upgradeDemoPermissions();
            return;
        }
        CredentialsService.validateNewPassword(initialPassword);
        long tenant = Ids.next();
        long user = Ids.next();
        long role = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES (?,?,'演示企业')", tenant,tenantCode);
        jdbc.update("""
                INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)
                VALUES (?,?,'admin','仓库管理员',?)
                """, user, tenant, passwords.encode(initialPassword));
        jdbc.update("INSERT INTO sys_role(id,tenant_id,code,name) VALUES (?,?,'WAREHOUSE_ADMIN','仓库管理员')",
                role, tenant);
        jdbc.update("INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES (?,?,?,?)",
                Ids.next(), tenant, user, role);
        grantPermissions(tenant,role);
        addCompany(tenant, user, "DEMO-A", "演示一公司");
        addCompany(tenant, user, "DEMO-B", "演示二公司");
    }

    private void upgradeDemoPermissions() {
        var roles=jdbc.query("""
                SELECT DISTINCT r.tenant_id,r.id FROM tenant t JOIN sys_user u ON u.tenant_id=t.id
                JOIN sys_user_role ur ON ur.tenant_id=u.tenant_id AND ur.user_id=u.id
                JOIN sys_role r ON r.tenant_id=ur.tenant_id AND r.id=ur.role_id
                WHERE t.code=? AND u.username='admin' AND r.code='WAREHOUSE_ADMIN'
                """,(r,n)->new long[]{r.getLong(1),r.getLong(2)},tenantCode);
        roles.forEach(role->grantPermissions(role[0],role[1]));
    }

    private void grantPermissions(long tenant,long role) {
        // 仅升级 M1 初始化的角色，管理员后续的授权调整应持久保留。
        Long version=jdbc.queryForObject("SELECT version FROM sys_role WHERE tenant_id=? AND id=? FOR UPDATE",Long.class,tenant,role);
        if(version==null) return;
        if(version==1) {
            // 兼容 M2 早期本机演示角色，页面编辑会增加版本，后续授权调整按页面结果保留。
            Long missingCost=jdbc.queryForObject("""
                    SELECT COUNT(*) FROM sys_permission p WHERE p.code='cost:view' AND NOT EXISTS
                    (SELECT 1 FROM sys_role_permission rp WHERE rp.tenant_id=? AND rp.role_id=? AND rp.permission_id=p.id)
                    """,Long.class,tenant,role);
            if(missingCost!=null && missingCost==1) {
                jdbc.update("""
                        INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)
                        SELECT ?,?,?,id FROM sys_permission WHERE code='cost:view'
                        """,Ids.next(),tenant,role);
                jdbc.update("UPDATE sys_role SET version=version+1 WHERE tenant_id=? AND id=?",tenant,role);
            }
            return;
        }
        if(version!=0) return;
        var missing=jdbc.queryForList("""
                SELECT p.id FROM sys_permission p WHERE NOT EXISTS(SELECT 1 FROM sys_role_permission rp
                WHERE rp.tenant_id=? AND rp.role_id=? AND rp.permission_id=p.id)
                """,Long.class,tenant,role);
        for(Long permission:missing) {
            jdbc.update("INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id) VALUES(?,?,?,?)",
                    Ids.next(),tenant,role,permission);
        }
        jdbc.update("UPDATE sys_role SET version=version+1 WHERE tenant_id=? AND id=?",tenant,role);
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
