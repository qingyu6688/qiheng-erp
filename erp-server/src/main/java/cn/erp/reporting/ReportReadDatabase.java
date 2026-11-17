package cn.erp.reporting;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 报表读取使用独立连接池和事务，配置空缺的本机环境使用主数据源。 */
@Component
public class ReportReadDatabase {
    private static final int READ_POOL_SIZE=5;
    private static final long CONNECTION_TIMEOUT_MILLIS=3000;
    private final NamedParameterJdbcTemplate jdbc;
    private final HikariDataSource replica;
    private final TransactionTemplate transactions;

    ReportReadDatabase(NamedParameterJdbcTemplate primary,PlatformTransactionManager manager,Environment environment) {
        String url=environment.getProperty("ERP_REPORT_JDBC_URL","").strip();
        if (url.isEmpty()) {
            replica=null;
            jdbc=primary;
            transactions=new TransactionTemplate(manager);
        } else {
            var config=new HikariConfig();
            config.setPoolName("ERPReportRead");
            config.setJdbcUrl(url);
            config.setUsername(required(environment,"ERP_REPORT_DB_USER"));
            config.setPassword(required(environment,"ERP_REPORT_DB_PASSWORD"));
            config.setMaximumPoolSize(READ_POOL_SIZE);
            config.setMinimumIdle(0);
            config.setReadOnly(true);
            config.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);
            config.setInitializationFailTimeout(-1);
            config.addDataSourceProperty("cachePrepStmts","true");
            config.addDataSourceProperty("prepStmtCacheSize","128");
            config.addDataSourceProperty("prepStmtCacheSqlLimit","8192");
            config.addDataSourceProperty("connectTimeout","3000");
            config.addDataSourceProperty("socketTimeout","30000");
            replica=new HikariDataSource(config);
            jdbc=new NamedParameterJdbcTemplate(replica);
            transactions=new TransactionTemplate(new JdbcTransactionManager(replica));
        }
        transactions.setReadOnly(true);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    NamedParameterJdbcTemplate jdbc() { return jdbc; }

    private static String required(Environment environment,String name) {
        String value=environment.getProperty(name,"");
        if (value.isBlank()) throw new IllegalStateException("请配置报表备库连接参数："+name);
        return value;
    }

    public <T> T read(Supplier<T> query) {
        return transactions.execute(status->query.get());
    }

    public <T> T readJdbc(java.util.function.Function<NamedParameterJdbcTemplate,T> query) {
        return read(()->query.apply(jdbc));
    }

    public boolean replicaEnabled() { return replica!=null; }

    String asOf() {
        if (replica==null) return Instant.now().toString();
        LocalDateTime watermark=jdbc.getJdbcTemplate().queryForObject(
                "SELECT observed_at FROM report_snapshot_watermark WHERE id=1",LocalDateTime.class);
        if (watermark==null) throw new IllegalStateException("报表备库数据水位尚未初始化");
        return watermark.toInstant(ZoneOffset.UTC).toString();
    }

    @PreDestroy
    void close() { if (replica!=null) replica.close(); }
}
