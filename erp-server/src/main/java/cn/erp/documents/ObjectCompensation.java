package cn.erp.documents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import cn.erp.shared.Ids;

@Service
public class ObjectCompensation {
    private static final Logger LOG = LoggerFactory.getLogger(ObjectCompensation.class);
    private final JdbcTemplate jdbc;
    private final ObjectFiles files;

    public ObjectCompensation(JdbcTemplate jdbc, ObjectFiles files) {
        this.jdbc = jdbc;
        this.files = files;
    }

    /** 先登记再写对象，进程退出后也能找回未完成关联的候选对象。 */
    public void stage(String key) {
        jdbc.update("INSERT INTO object_cleanup(id,storage_key,reason) VALUES(?,?,'待关联候选文件')", Ids.next(), key);
    }

    public void associated(String key) {
        jdbc.update("DELETE FROM object_cleanup WHERE storage_key=?", key);
    }

    /** 随元数据删除一同提交，延后回收文件便于失败后安全重试。 */
    public void retire(String key) {
        jdbc.update("INSERT INTO object_cleanup(id,storage_key,reason) VALUES(?,?,'附件已删除，待回收文件') ON DUPLICATE KEY UPDATE reason='附件已删除，待回收文件'", Ids.next(), key);
    }

    public void discard(String key) {
        try {
            files.delete(key);
            associated(key);
        } catch (RuntimeException error) {
            LOG.warn("候选对象清理失败，将由补偿任务重试，对象 {}", key);
        }
    }

    public void retry() {retry(null);}
    public void retry(Long tenant) {
        var candidates = jdbc.queryForList("SELECT id,storage_key FROM object_cleanup WHERE (? IS NULL OR storage_key LIKE CONCAT(?,'/%')) AND created_at<DATE_SUB(UTC_TIMESTAMP(3),INTERVAL 1 HOUR) ORDER BY id LIMIT 100",tenant,tenant);
        for (var row : candidates) {
            String key = row.get("storage_key").toString();
            Integer linked = jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE storage_key=? AND status='active'", Integer.class, key);
            if (linked != null && linked > 0) {
                associated(key);
                continue;
            }
            jdbc.update("UPDATE object_cleanup SET attempts=attempts+1 WHERE id=?", row.get("id"));
            discard(key);
        }
        jdbc.update("DELETE FROM file_ticket WHERE (? IS NULL OR tenant_id=?) AND expires_at<UTC_TIMESTAMP(3)",tenant,tenant);
    }
}
