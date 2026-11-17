package cn.erp.portal;

import cn.erp.crm.CrmScope;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PortalAccounts {
    private static final String ACTIVE_ACCOUNTS = """
            SELECT u.id,u.tenant_id,u.company_id,u.partner_id,u.contact_id,u.login_name,u.password_hash,u.version,
              p.name customer_name,c.name company_name,pc.name contact_name,pc.mobile contact_mobile,pc.email contact_email
            FROM portal_user u
            JOIN tenant t ON t.id=u.tenant_id AND t.status='active'
            JOIN company c ON c.tenant_id=u.tenant_id AND c.id=u.company_id AND c.status='active'
            JOIN business_partner p ON p.tenant_id=u.tenant_id AND p.id=u.partner_id AND p.status='active'
            JOIN partner_role pr ON pr.tenant_id=u.tenant_id AND pr.partner_id=u.partner_id AND pr.role='customer' AND pr.status='active'
            JOIN partner_contact pc ON pc.tenant_id=u.tenant_id AND pc.partner_id=u.partner_id AND pc.id=u.contact_id
            WHERE u.status='active'
            """;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final CrmScope crm;
    private final String dummyPasswordHash;

    PortalAccounts(JdbcTemplate jdbc, PasswordEncoder passwords, CrmScope crm) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.crm = crm;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    record Create(Long partnerId, Long contactId, String username, String password) {}
    record Change(Integer version, String status, String password, String username) {}
    record Account(String id, String partnerId, String contactId, String username, String customerName,
                   String contactName, String status, int version, String lastLoginAt) {}
    record Credential(PortalActor actor, int version, String login, String passwordHash, String contactMobile, String contactEmail) {}

    List<Account> list(CompanyAccess actor, long cursor, int limit) {
        return jdbc.query("""
                SELECT u.id,u.partner_id,u.contact_id,u.login_name,p.name customer_name,c.name contact_name,
                  u.status,u.version,u.last_login_at FROM portal_user u
                JOIN crm_customer_profile cp ON cp.tenant_id=u.tenant_id AND cp.company_id=u.company_id AND cp.partner_id=u.partner_id
                JOIN business_partner p ON p.tenant_id=u.tenant_id AND p.id=u.partner_id
                LEFT JOIN partner_contact c ON c.tenant_id=u.tenant_id AND c.id=u.contact_id AND c.partner_id=u.partner_id
                WHERE u.tenant_id=? AND u.company_id=? AND u.id<? AND (
                """ + crm.customerFilter(actor,"cp") + ") ORDER BY u.id DESC LIMIT ?",
                (row,n) -> new Account(row.getString("id"),row.getString("partner_id"),row.getString("contact_id"),
                        row.getString("login_name"),row.getString("customer_name"),row.getString("contact_name"),
                        row.getString("status"),row.getInt("version"),row.getString("last_login_at")),
                actor.tenantId(),actor.companyId(),cursor,limit);
    }

    @Transactional
    public Account create(CompanyAccess actor, Create input) {
        long partner = PortalValues.id(input.partnerId());
        long contact = PortalValues.id(input.contactId());
        crm.requireCustomer(actor,partner,true,true);
        var person = contact(actor,partner,contact);
        String login = normalizeLogin(input.username());
        requireContactLogin(login,person);
        requireNewPassword(input.password());
        long id = Ids.next();
        try {
            jdbc.update("""
                    INSERT INTO portal_user(id,tenant_id,company_id,partner_id,contact_id,login_name,password_hash,created_by)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,id,actor.tenantId(),actor.companyId(),partner,contact,login,passwords.encode(input.password()),actor.userId());
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409,"PORTAL_ACCOUNT_EXISTS","联系人或登录名已开通门户账号");
        }
        return view(actor,id);
    }

    @Transactional
    public Account change(CompanyAccess actor, long id, Change input) {
        var rows = jdbc.queryForList("SELECT partner_id,contact_id,login_name FROM portal_user WHERE tenant_id=? AND company_id=? AND id=?",
                actor.tenantId(),actor.companyId(),id);
        if (rows.isEmpty()) throw PortalValues.missing("门户账号");
        var selected = rows.getFirst();
        long partner = ((Number)selected.get("partner_id")).longValue();
        crm.requireCustomer(actor,partner,true,true);
        if (input.version() == null || input.version() < 0 || input.status() == null || !List.of("active","disabled").contains(input.status()))
            throw PortalValues.invalid("请填写有效的门户账号状态和版本");
        String login = input.username()==null ? selected.get("login_name").toString() : normalizeLogin(input.username());
        if ("active".equals(input.status()) || input.username()!=null) {
            var person = contact(actor,partner,((Number)selected.get("contact_id")).longValue());
            requireContactLogin(login,person);
        }
        String password = input.password();
        String hash = null;
        if (password != null) {
            requireNewPassword(password);
            hash = passwords.encode(password);
        }
        int changed;
        try {
            changed = jdbc.update("""
                    UPDATE portal_user SET status=?,login_name=?,password_hash=COALESCE(?,password_hash),version=version+1
                    WHERE tenant_id=? AND company_id=? AND id=? AND version=?
                    """,input.status(),login,hash,actor.tenantId(),actor.companyId(),id,input.version());
        } catch (DuplicateKeyException conflict) {
            throw new ApiException(409,"PORTAL_ACCOUNT_EXISTS","此登录名已开通其他门户账号");
        }
        if (changed != 1) throw new ApiException(409,"VERSION_CONFLICT","账号已变化，请刷新后重试");
        return view(actor,id);
    }

    Credential login(String tenantCode, String login) {
        return query(" AND t.code=? AND u.login_name=?",tenantCode,login);
    }

    Credential live(long user) {
        return query(" AND u.id=?",user);
    }

    boolean matches(String password, Credential credential) {
        if (password == null || password.isBlank() || password.getBytes(StandardCharsets.UTF_8).length > 72) return false;
        boolean matches = passwords.matches(password, credential == null ? dummyPasswordHash : credential.passwordHash());
        return matches && credential != null;
    }

    void recordLogin(Credential credential) {
        int updated = jdbc.update("UPDATE portal_user SET last_login_at=UTC_TIMESTAMP(3) WHERE id=? AND version=? AND status='active'",
                credential.actor().userId(),credential.version());
        if (updated != 1) throw PortalActor.expired();
    }

    static String normalizeLogin(String value) {
        String login = maybeLogin(value);
        if (login == null) throw PortalValues.invalid("请输入有效的手机号或邮箱");
        return login;
    }

    private Credential query(String condition, Object... args) {
        return jdbc.query(ACTIVE_ACCOUNTS + condition,(row,n) -> new Credential(
                new PortalActor(row.getLong("tenant_id"),row.getLong("company_id"),row.getLong("partner_id"),
                        row.getLong("contact_id"),row.getLong("id"),row.getString("company_name"),row.getString("customer_name"),row.getString("contact_name")),
                row.getInt("version"),row.getString("login_name"),row.getString("password_hash"),
                maybeLogin(row.getString("contact_mobile")),maybeLogin(row.getString("contact_email"))),args)
                .stream().filter(credential->credential.login().equals(credential.contactMobile()) || credential.login().equals(credential.contactEmail()))
                .findFirst().orElse(null);
    }

    private Map<String,Object> contact(CompanyAccess actor, long partner, long contact) {
        return jdbc.queryForList("""
                SELECT c.mobile,c.email FROM partner_contact c
                JOIN business_partner p ON p.tenant_id=c.tenant_id AND p.id=c.partner_id AND p.status='active'
                JOIN partner_role r ON r.tenant_id=c.tenant_id AND r.partner_id=c.partner_id AND r.role='customer' AND r.status='active'
                WHERE c.tenant_id=? AND c.partner_id=? AND c.id=?
                """,actor.tenantId(),partner,contact).stream().findFirst().orElseThrow(() -> PortalValues.missing("客户联系人"));
    }

    private void requireContactLogin(String login,Map<String,Object> person) {
        if(!login.equals(maybeLogin(person.get("mobile"))) && !login.equals(maybeLogin(person.get("email"))))
            throw PortalValues.invalid("登录名须为该联系人当前的手机号或邮箱");
    }

    private Account view(CompanyAccess actor, long id) {
        return jdbc.query("""
                SELECT u.id,u.partner_id,u.contact_id,u.login_name,p.name customer_name,c.name contact_name,u.status,u.version,u.last_login_at
                FROM portal_user u JOIN business_partner p ON p.tenant_id=u.tenant_id AND p.id=u.partner_id
                LEFT JOIN partner_contact c ON c.tenant_id=u.tenant_id AND c.id=u.contact_id AND c.partner_id=u.partner_id
                WHERE u.tenant_id=? AND u.company_id=? AND u.id=?
                """,(row,n) -> new Account(row.getString("id"),row.getString("partner_id"),row.getString("contact_id"),row.getString("login_name"),
                        row.getString("customer_name"),row.getString("contact_name"),row.getString("status"),row.getInt("version"),row.getString("last_login_at")),
                actor.tenantId(),actor.companyId(),id).stream().findFirst().orElseThrow(() -> PortalValues.missing("门户账号"));
    }

    private static void requireNewPassword(String password) {
        if (password == null || password.strip().codePointCount(0,password.strip().length()) < 12
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw PortalValues.invalid("门户密码至少含 12 个字符且不得超过 72 字节");
        }
    }

    private static String maybeLogin(Object value) {
        if (value == null) return null;
        String normalized = value.toString().strip().toLowerCase(Locale.ROOT);
        String phone = normalized.replace(" ","").replace("-","");
        if (phone.matches("1[3-9][0-9]{9}")) return "+86"+phone;
        if (phone.matches("\\+861[3-9][0-9]{9}")) return phone;
        if (normalized.length() <= 100 && normalized.matches("[a-z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-z0-9-]+(?:\\.[a-z0-9-]+)+")) return normalized;
        return null;
    }
}
