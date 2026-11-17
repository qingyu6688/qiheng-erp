package cn.erp.e2e;

import java.time.LocalDate;
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

/** 财务浏览器演练使用独立企业，账套和凭证都由界面创建。 */
@Component
@Profile("e2e & !prod & !production")
public class GlBrowserFixture {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String tenantCode, password, url;

    public GlBrowserFixture(
            JdbcTemplate jdbc,
            PasswordEncoder passwords,
            @Value("${ERP_E2E_TENANT_CODE:}") String tenantCode,
            @Value("${ERP_E2E_PASSWORD:}") String password,
            @Value("${spring.datasource.url}") String url) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tenantCode = tenantCode;
        this.password = password;
        this.url = url;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void prepare() {
        if (!tenantCode.matches("E2E-[a-f0-9]{12}")
                || password.length() < 16
                || !url.matches(
                        "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/erp_test(?:_[a-z0-9]+)*(?:\\?.*)?"))
            throw new IllegalStateException("财务浏览器夹具仅允许本机隔离测试库和随机测试企业");
        String code = tenantCode + "-GL";
        if (jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE code=?", Long.class, code) > 0)
            return;
        long tenant = Ids.next(), company = Ids.next(), role = Ids.next();
        jdbc.update("INSERT INTO tenant(id,code,name) VALUES(?,?,'财务浏览器验收企业')", tenant, code);
        jdbc.update(
                "INSERT INTO company(id,tenant_id,code,name) VALUES(?,?,'GL','财务浏览器验收公司')",
                company,
                tenant);
        jdbc.update(
                "INSERT INTO company_policy(id,tenant_id,company_id) VALUES(?,?,?)",
                Ids.next(),
                tenant,
                company);
        jdbc.update(
                "INSERT INTO sys_role(id,tenant_id,code,name) VALUES(?,?,'FINANCE','财务验收')",
                role,
                tenant);
        for (long permission : jdbc.queryForList("SELECT id FROM sys_permission", Long.class))
            jdbc.update(
                    "INSERT INTO sys_role_permission(id,tenant_id,role_id,permission_id)"
                        + " VALUES(?,?,?,?)",
                    Ids.next(),
                    tenant,
                    role,
                    permission);
        String hash = passwords.encode(password);
        for (String name : List.of("glmaker", "glreviewer")) {
            long user = Ids.next();
            jdbc.update(
                    "INSERT INTO sys_user(id,tenant_id,username,display_name,password_hash)"
                        + " VALUES(?,?,?,?,?)",
                    user,
                    tenant,
                    name,
                    name.equals("glmaker") ? "财务制单员" : "财务复核员",
                    hash);
            jdbc.update(
                    "INSERT INTO sys_user_role(id,tenant_id,user_id,role_id) VALUES(?,?,?,?)",
                    Ids.next(),
                    tenant,
                    user,
                    role);
            jdbc.update(
                    "INSERT INTO sys_user_data_scope(id,tenant_id,user_id,scope_type,company_id)"
                        + " VALUES(?,?,?,'company',?)",
                    Ids.next(),
                    tenant,
                    user,
                    company);
        }
        for (int month : List.of(10, 11))
            jdbc.update(
                    "INSERT INTO"
                        + " accounting_period(id,tenant_id,company_id,period,start_date,end_date)"
                        + " VALUES(?,?,?,?,?,?)",
                    Ids.next(),
                    tenant,
                    company,
                    "2026" + month,
                    LocalDate.of(2026, month, 1),
                    LocalDate.of(2026, month, 1).plusMonths(1).minusDays(1));
    }
}
