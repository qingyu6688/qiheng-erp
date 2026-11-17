package cn.erp.upgrade;

import cn.erp.ErpApplication;
import cn.erp.shared.ApiException;
import cn.erp.shared.EnterpriseUpgradeMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.ObjectMapper;

/** 隔离升级入口，使用发布包内的 Flyway 迁移和业务校验。 */
public final class UpgradeMain {
    static final int LEGACY_VERSION = 87;
    static final int TARGET_VERSION = EnterpriseUpgradeMode.SCHEMA_VERSION;

    private UpgradeMain() {}

    public static void main(String[] args) {
        try {
            run(args);
        } catch (ApiException | IllegalArgumentException | IllegalStateException failure) {
            System.err.println(failure.getMessage());
            System.exit(1);
        } catch (Exception failure) {
            System.err.println("升级核验执行失败，请检查升级日志。异常类型 " + failure.getClass().getSimpleName());
            if (Boolean.parseBoolean(System.getenv("UPGRADE_DIAGNOSTICS"))) failure.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(String[] args) throws Exception {
        if (args.length != 5 || !args[1].matches("[a-z][a-z0-9_]{1,63}")
                || !args[2].matches("[0-9a-f-]{36}"))
            throw new IllegalArgumentException("请通过 2.0 升级工具运行核验入口");
        String mode = args[0];
        String database = args[1];
        Path objectRoot = Path.of(args[3]);
        if (!Files.isDirectory(objectRoot)) throw new IllegalArgumentException("私有对象目录不存在");
        Path report = Path.of(args[4]);
        String url = jdbcUrl(database);
        String username = required("DB_USER");
        String password = System.getenv().getOrDefault("DB_PASSWORD", "");
        var source = new DriverManagerDataSource(url, username, password);
        var jdbc = new JdbcTemplate(source);
        if (!args[2].equals(jdbc.queryForObject("SELECT @@server_uuid", String.class)))
            throw new IllegalArgumentException("升级连接指向了其他 MySQL 实例");
        var flyway = Flyway.configure().dataSource(source).ignoreMigrationPatterns("*:pending").load();
        var json = new ObjectMapper();
        if (mode.equals("schema") || mode.equals("migrate")) {
            write(json, report, schema(flyway, jdbc, mode.equals("migrate")));
            return;
        }
        try (var context = new SpringApplicationBuilder(ErpApplication.class)
                .web(WebApplicationType.NONE).logStartupInfo(false).run(
                        "--spring.config.location=classpath:/application.yaml",
                        "--spring.profiles.active=upgrade", "--spring.main.web-application-type=none",
                        "--spring.main.banner-mode=off", "--spring.flyway.enabled=false",
                        "--spring.datasource.url=" + url, "--spring.datasource.username=${DB_USER}",
                        "--spring.datasource.password=${DB_PASSWORD:}",
                        "--spring.datasource.hikari.minimum-idle=0",
                        "--spring.datasource.hikari.maximum-pool-size=4",
                        "--spring.datasource.hikari.read-only=" + !mode.equals("01-default-workflows"),
                        "--erp.worker.enabled=false", "--erp.observability.enabled=false",
                        "--erp.storage.local-root=" + objectRoot.toAbsolutePath(), "--erp.upgrade.cli=true")) {
            var checks = context.getBean(UpgradeChecks.class);
            boolean legacy = version(jdbc) < 98;
            Object result = switch (mode) {
                case "inspect" -> checks.inspect(legacy);
                case "scope" -> checks.scope(legacy);
                case "01-default-workflows" -> {
                    if (legacy) throw new IllegalStateException("请先完成目标库的 Flyway 迁移");
                    yield context.getBean(UpgradeWorkflows.class).convert();
                }
                case "workflow-check" -> context.getBean(UpgradeWorkflows.class).verify();
                default -> throw new IllegalArgumentException("升级核验步骤无效");
            };
            write(json, report, result);
        }
    }

    private static Map<String, Number> schema(Flyway flyway, JdbcTemplate jdbc, boolean migrate) {
        int current = version(jdbc);
        if (current < LEGACY_VERSION || current > TARGET_VERSION)
            throw new IllegalStateException("升级要求最后一个 1.x 基线 V87 或后续企业版候选库");
        long history = jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND type='SQL'
                """, Long.class);
        var validation = flyway.validateWithResult();
        if (!validation.validationSuccessful)
            throw new IllegalStateException("Flyway 库内脚本校验失败：" + validation.errorDetails.errorMessage);
        var appliedRange = MigrationVersion.fromVersion("" + current);
        var known = Arrays.stream(flyway.info().all())
                .filter(info -> info.getVersion() != null && info.getVersion().compareTo(appliedRange) <= 0).toList();
        if (history != known.size() || known.stream().anyMatch(info -> info.getState() != MigrationState.SUCCESS))
            throw new IllegalStateException("Flyway 历史存在缺项、基线覆盖或重复版本");
        if (!migrate) return Map.of("version", current, "validatedMigrations", history);
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT @@global.offline_mode", Boolean.class)))
            throw new IllegalStateException("数据库未停写，拒绝执行迁移");
        flyway.migrate();
        if (version(jdbc) != TARGET_VERSION) throw new IllegalStateException("目标迁移版本不完整");
        return Map.of("fromVersion", current, "version", TARGET_VERSION);
    }

    private static int version(JdbcTemplate jdbc) {
        return jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(version AS UNSIGNED)),0) FROM flyway_schema_history WHERE success=1
                """, Integer.class);
    }

    private static void write(ObjectMapper json, Path report, Object value) throws Exception {
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(value) + "\n");
    }

    private static String jdbcUrl(String database) {
        String host = required("DB_HOST");
        String port = required("DB_PORT");
        String tls = System.getenv().getOrDefault("DB_SSL_MODE", "REQUIRED");
        if (!host.matches("[a-zA-Z0-9.-]+") || !port.matches("[0-9]{1,5}")
                || !tls.matches("DISABLED|REQUIRED|VERIFY_CA|VERIFY_IDENTITY"))
            throw new IllegalArgumentException("数据库连接配置无效");
        return "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?sslMode=" + tls + "&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&characterEncoding=UTF-8";
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少升级配置 " + key);
        return value;
    }
}
