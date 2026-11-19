package cn.erp.audit;

import cn.erp.ErpApplication;
import cn.erp.shared.Ids;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes=ErpApplication.class)
@ActiveProfiles("test")
class AuditSealIT {
    @Autowired AuditSealService seals;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager manager;
    long tenant;

    @BeforeEach void prepare() {
        tenant = Ids.next();
        jdbc.update("""
                INSERT INTO job_definition(tenant_id,code,name,schedule)
                VALUES(?,'audit.seal','审计封存测试','interval:300000')
                """, tenant);
    }

    @Test void lateCommitWithLowerIdIsSealedOnNextRun() throws Exception {
        long late = Ids.next();
        long early = Ids.next();
        try (var workers = Executors.newFixedThreadPool(2)) {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                add(late, tenant);
                // 模拟另一节点先提交更大的 ID 并完成第一批封存。
                try {
                    assertThat(workers.submit(() -> {
                        add(early, tenant);
                        return seals.seal(tenant);
                    }).get(15, TimeUnit.SECONDS)).isEqualTo(1);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
        assertThat(seals.seal(tenant)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT audit_id FROM audit_seal_member WHERE tenant_id=? ORDER BY batch_no",
                Long.class, tenant)).containsExactly(early, late);
    }

    @Test void twoWorkersSealOnceAndRespectTenantBoundary() throws Exception {
        add(Ids.next(), tenant);
        add(Ids.next(), Ids.next());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> seals.seal(tenant));
            var second = workers.submit(() -> seals.seal(tenant));
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(count("audit_seal")).isEqualTo(1);
        assertThat(count("audit_seal_member")).isEqualTo(1);
    }

    @Test void failureRollsBackBatchAndAllowsRetry() {
        add(Ids.next(), tenant);
        assertThatThrownBy(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            assertThat(seals.seal(tenant)).isEqualTo(1);
            throw new IllegalStateException("模拟封存事务失败");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("audit_seal")).isZero();
        assertThat(count("audit_seal_member")).isZero();
        assertThat(seals.seal(tenant)).isEqualTo(1);
    }

    private void add(long id, long owner) {
        jdbc.update("INSERT INTO audit_log(id,tenant_id,action,object_type) VALUES(?,?,'test.audit','test')",
                id, owner);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
    }
}
