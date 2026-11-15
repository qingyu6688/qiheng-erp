package cn.erp.documents;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.Ids;

/** 数据库保存票据摘要，兑换方还必须重新核对业务权限和对象状态。 */
@Service
public class FileTickets {
    private final JdbcTemplate jdbc;
    private final SecureRandom random = new SecureRandom();

    public FileTickets(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String issue(CompanyAccess scope, String purpose, long resourceId) {
        byte[] value = new byte[32];
        random.nextBytes(value);
        String ticket = HexFormat.of().formatHex(value);
        jdbc.update("""
                INSERT INTO file_ticket(id,tenant_id,company_id,user_id,purpose,resource_id,token_hash,expires_at)
                VALUES(?,?,?,?,?,?,?,DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 5 MINUTE))
                """, Ids.next(), scope.tenantId(), scope.companyId(), scope.userId(), purpose, resourceId, digest(ticket));
        return ticket;
    }

    public void verify(CompanyAccess scope, String purpose, long resourceId, String ticket) {
        if (ticket == null || !ticket.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM file_ticket WHERE tenant_id=? AND company_id=? AND user_id=?
                AND purpose=? AND resource_id=? AND token_hash=? AND expires_at>UTC_TIMESTAMP(3)
                """, Integer.class, scope.tenantId(), scope.companyId(), scope.userId(), purpose, resourceId, digest(ticket));
        if (count == null || count != 1) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(403, "DOWNLOAD_TICKET_INVALID", "下载链接已失效，请重新获取");
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("运行环境缺少摘要算法", error);
        }
    }
}
