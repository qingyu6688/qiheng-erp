import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.security.crypto.bcrypt.BCrypt;

import cn.erp.shared.Ids;

public final class InitializeTenant {
    private static final Set<String> SCHEMA_VERSIONS = Set.of("31", "32", "33");
    private static final int BOOTSTRAP_LOCK_WAIT_SECONDS = 5;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final Pattern DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]{1,63}");
    private static final Pattern DATABASE_HOST = Pattern.compile("(?:[A-Za-z0-9.-]+|\\[[0-9A-Fa-f:]+\\])");
    private static final Pattern TLS_MODE = Pattern.compile("REQUIRED|VERIFY_CA|VERIFY_IDENTITY");
    private static final Pattern TENANT_CODE = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern COMPANY_CODE = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private InitializeTenant() {}

    public static void main(String[] args) {
        try {
            var input = readInput(args);
            String hash = BCrypt.hashpw(requiredPassword(), BCrypt.gensalt());
            try (Connection connection = openDatabase(input.database())) {
                createFirstTenant(connection, input, hash);
            }
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        } catch (SQLException failure) {
            System.err.printf("首个企业初始化失败，请核对数据库连通性、迁移版本和引导账号权限。SQL 状态 %s，错误号 %d。%n",
                    failure.getSQLState(), failure.getErrorCode());
            System.exit(1);
        }
    }

    private static Input readInput(String[] args) {
        if (args.length != 7) {
            throw new IllegalArgumentException("请通过 initialize-tenant.ps1 指定数据库、企业、公司和管理员。");
        }
        String database = matching(args[0], DATABASE_NAME, "数据库名须为 2 至 64 位小写字母、数字或下划线，并以字母开头。");
        if (!database.equals(System.getenv("DB_NAME"))) {
            throw new IllegalArgumentException("指定库名与数据库配置 DB_NAME 不一致。");
        }
        return new Input(database,
                matching(args[1], TENANT_CODE, "企业编码限 32 位字母、数字、下划线和短横线。"),
                name(args[2], 100, "企业名称"),
                matching(args[3], COMPANY_CODE, "公司编码限 16 位字母、数字、下划线和短横线。"),
                name(args[4], 100, "公司名称"),
                matching(args[5], USERNAME, "用户名限 64 位字母、数字、点、短横线和下划线。"),
                name(args[6], 64, "管理员姓名"));
    }

    private static String matching(String value, Pattern pattern, String message) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private static String name(String value, int maxLength, String field) {
        String trimmed = value.strip();
        if (trimmed.isEmpty() || trimmed.length() > maxLength || trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + "不能为空、包含控制字符或超过 " + maxLength + " 字。");
        }
        return trimmed;
    }

    private static String requiredPassword() {
        String password = System.getenv("ERP_BOOTSTRAP_ADMIN_PASSWORD");
        if (password == null || password.isBlank() || password.length() < MIN_PASSWORD_LENGTH
                || password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new IllegalArgumentException("请在 ERP_BOOTSTRAP_ADMIN_PASSWORD 设置临时口令，至少 8 个字符且不超过 72 个 UTF-8 字节。");
        }
        return password;
    }

    private static Connection openDatabase(String database) throws SQLException {
        String host = matching(System.getenv("DB_HOST"), DATABASE_HOST, "请配置有效的数据库主机 DB_HOST。");
        int port;
        try {
            port = Integer.parseInt(System.getenv("DB_PORT"));
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException("请配置有效的数据库端口 DB_PORT。");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("数据库端口须在 1 至 65535 之间。");
        }
        String tls = matching(System.getenv("DB_SSL_MODE"), TLS_MODE,
                "DB_SSL_MODE 须配置为 REQUIRED、VERIFY_CA 或 VERIFY_IDENTITY。");
        String user = System.getenv("ERP_BOOTSTRAP_DB_USER");
        String password = System.getenv("ERP_BOOTSTRAP_DB_PASSWORD");
        if (user == null || user.isBlank() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请配置完整的数据库引导账号和密码。");
        }
        var options = new Properties();
        options.setProperty("user", user);
        options.setProperty("password", password);
        options.setProperty("sslMode", tls);
        options.setProperty("characterEncoding", "UTF-8");
        options.setProperty("connectionTimeZone", "UTC");
        options.setProperty("forceConnectionTimeZoneToSession", "true");
        options.setProperty("connectTimeout", "5000");
        configureTrustStore(options);
        return DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database, options);
    }

    private static void configureTrustStore(Properties options) {
        String trustStore = System.getenv("ERP_BOOTSTRAP_DB_TRUSTSTORE");
        String password = System.getenv("ERP_BOOTSTRAP_DB_TRUSTSTORE_PASSWORD");
        if (trustStore == null || trustStore.isBlank()) {
            if (password != null && !password.isEmpty()) {
                throw new IllegalArgumentException("配置了信任库密码，请同时提供 PKCS12 信任库路径。");
            }
            return;
        }
        Path file = Path.of(trustStore);
        if (!Files.isRegularFile(file) || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("请提供有效的 PKCS12 信任库路径和密码。");
        }
        options.setProperty("trustCertificateKeyStoreUrl", file.toUri().toString());
        options.setProperty("trustCertificateKeyStoreType", "PKCS12");
        options.setProperty("trustCertificateKeyStorePassword", password);
    }

    private static void createFirstTenant(Connection connection, Input input, String hash) throws SQLException {
        // 连接级锁覆盖空库检查和提交，避免两次并发引导各自创建第一个企业。
        try (var lock = connection.prepareStatement(
                "SELECT GET_LOCK(CONCAT('erp.initial-tenant.', LEFT(SHA2(DATABASE(), 256), 32)), ?)")) {
            lock.setInt(1, BOOTSTRAP_LOCK_WAIT_SECONDS);
            try (var result = lock.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) {
                    throw new IllegalArgumentException("另一个引导操作正在运行，或无法取得数据库引导锁。");
                }
            }
        }
        connection.setAutoCommit(false);
        try {
            checkEmptySchema(connection);
            long tenant = Ids.next();
            long user = Ids.next();
            long company = Ids.next();
            long role = Ids.next();
            insertOrganization(connection, input, hash, tenant, company, user, role);
            int permissionCount = grantPermissions(connection, tenant, user, role);
            insert(connection, """
                    INSERT INTO audit_log(id, tenant_id, company_id, action, object_type, object_id, reason)
                    VALUES (?, ?, ?, 'tenant.bootstrap', 'tenant', ?, '首次部署初始化')
                    """, Ids.next(), tenant, company, tenant);
            connection.commit();
            System.out.printf("首个企业初始化完成。企业 ID %d，公司 ID %d，管理员 ID %d，权限 %d 项。首次登录须修改密码。%n",
                    tenant, company, user, permissionCount);
        } catch (SQLException | RuntimeException failure) {
            try {
                connection.rollback();
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private static void checkEmptySchema(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL
                     ORDER BY installed_rank DESC LIMIT 1
                     """)) {
            if (!result.next() || !SCHEMA_VERSIONS.contains(result.getString(1))) {
                throw new IllegalArgumentException("请先使用对应发布包完成 Flyway V31、V32 或 V33 迁移。");
            }
        }
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT id FROM tenant LIMIT 1")) {
            if (result.next()) {
                throw new IllegalArgumentException("指定数据库已有企业，引导已停止。");
            }
        }
    }

    private static void insertOrganization(Connection connection, Input input, String hash, long tenant,
                                           long company, long user, long role) throws SQLException {
        insert(connection, "INSERT INTO tenant(id, code, name) VALUES (?, ?, ?)",
                tenant, input.tenantCode(), input.tenantName());
        insert(connection, """
                INSERT INTO sys_user(id, tenant_id, username, display_name, password_hash, must_change_password)
                VALUES (?, ?, ?, ?, ?, 1)
                """, user, tenant, input.username(), input.displayName(), hash);
        insert(connection, """
                INSERT INTO company(id, tenant_id, code, name, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?)
                """, company, tenant, input.companyCode(), input.companyName(), user, user);
        insert(connection, """
                INSERT INTO company_policy(id, tenant_id, company_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?)
                """, Ids.next(), tenant, company, user, user);
        insert(connection, """
                INSERT INTO sys_role(id, tenant_id, code, name, created_by, updated_by)
                VALUES (?, ?, 'SYSTEM_ADMIN', '系统管理员', ?, ?)
                """, role, tenant, user, user);
        insert(connection, """
                INSERT INTO sys_user_role(id, tenant_id, user_id, role_id, created_by) VALUES (?, ?, ?, ?, ?)
                """, Ids.next(), tenant, user, role, user);
        insert(connection, """
                INSERT INTO sys_user_data_scope(id, tenant_id, user_id, scope_type, company_id, created_by)
                VALUES (?, ?, ?, 'company', ?, ?)
                """, Ids.next(), tenant, user, company, user);
    }

    private static int grantPermissions(Connection connection, long tenant, long user, long role) throws SQLException {
        int count = 0;
        try (var statement = connection.createStatement();
             var permissions = statement.executeQuery("SELECT id FROM sys_permission ORDER BY id");
             var grant = connection.prepareStatement("""
                     INSERT INTO sys_role_permission(id, tenant_id, role_id, permission_id, created_by)
                     VALUES (?, ?, ?, ?, ?)
                     """)) {
            while (permissions.next()) {
                bind(grant, Ids.next(), tenant, role, permissions.getLong(1), user);
                grant.addBatch();
                count++;
            }
            if (count == 0) {
                throw new IllegalArgumentException("权限目录为空，请检查数据库迁移。");
            }
            grant.executeBatch();
        }
        return count;
    }

    private static void insert(Connection connection, String sql, Object... values) throws SQLException {
        try (var statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object... values) throws SQLException {
        for (int i = 0; i < values.length; i++) {
            statement.setObject(i + 1, values[i]);
        }
    }

    private record Input(String database, String tenantCode, String tenantName, String companyCode,
                         String companyName, String username, String displayName) {}
}
