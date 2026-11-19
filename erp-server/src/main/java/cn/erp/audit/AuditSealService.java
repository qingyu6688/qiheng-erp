package cn.erp.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditSealService {
    static final String JOB = "audit.seal";
    private static final int BATCH_SIZE = 200;
    private static final String GENESIS = "0".repeat(64);

    private final JdbcTemplate jdbc;

    public AuditSealService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public int seal(long tenant) {
        if (tenant <= 0) {
            throw new IllegalArgumentException("审计封存需要有效的租户编号");
        }
        // 任务租约可能在进程中断后转移。定义行锁保护实际写入事务和摘要链头。
        jdbc.queryForObject("SELECT code FROM job_definition WHERE tenant_id=? AND code=? FOR UPDATE",
                String.class, tenant, JOB);
        var rows = jdbc.query("""
                SELECT a.audit_id,a.row_hash FROM audit_seal_source a
                WHERE a.tenant_id=? AND NOT EXISTS (
                    SELECT 1 FROM audit_seal_member m WHERE m.tenant_id=a.tenant_id AND m.audit_id=a.audit_id
                ) ORDER BY a.audit_id LIMIT ?
                """, (rs, index) -> new Entry(rs.getLong(1), rs.getString(2)), tenant, BATCH_SIZE);
        if (rows.isEmpty()) {
            return 0;
        }
        var tail = jdbc.query("""
                SELECT batch_no,batch_hash FROM audit_seal WHERE tenant_id=?
                ORDER BY batch_no DESC LIMIT 1
                """, (rs, index) -> new Tail(rs.getLong(1), rs.getString(2)), tenant);
        long batch = tail.isEmpty() ? 1 : tail.getFirst().number() + 1;
        String previous = tail.isEmpty() ? GENESIS : tail.getFirst().hash();
        MessageDigest digest = sha256();
        digest.update(("qiheng-audit-v1:" + tenant + ":" + batch + ":" + previous + "\n")
                .getBytes(StandardCharsets.US_ASCII));
        for (var row : rows) {
            digest.update((row.id() + ":" + row.hash() + "\n").getBytes(StandardCharsets.US_ASCII));
        }
        jdbc.update("INSERT INTO audit_seal(tenant_id,batch_no,row_count,previous_hash,batch_hash) VALUES(?,?,?,?,?)",
                tenant, batch, rows.size(), previous, HexFormat.of().formatHex(digest.digest()));
        jdbc.batchUpdate("INSERT INTO audit_seal_member(tenant_id,audit_id,batch_no) VALUES(?,?,?)",
                rows, BATCH_SIZE, (statement, row) -> {
                    statement.setLong(1, tenant);
                    statement.setLong(2, row.id());
                    statement.setLong(3, batch);
                });
        return rows.size();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("当前运行环境缺少 SHA-256", failure);
        }
    }

    private record Entry(long id, String hash) {}
    private record Tail(long number, String hash) {}
}
