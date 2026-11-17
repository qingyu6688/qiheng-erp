package cn.erp.ai.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.ai.common.AiException;
import cn.erp.ai.knowledge.KnowledgeParser.Part;

@Component
class KnowledgeWork {
    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeWork.class);
    private static final String CLAIMABLE = "(v.status = 'uploaded' OR (v.status = 'parsing' AND v.updated_at < NOW(6) - INTERVAL 30 MINUTE))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final KnowledgeRepository repository;
    private final KnowledgeCatalog catalog;
    private final KnowledgeObjects objects;
    private final KnowledgeParser parser;
    private final KnowledgeIndex index;
    private final WikiService wiki;
    private final boolean enabled;

    KnowledgeWork(JdbcTemplate jdbc, TransactionTemplate transaction, KnowledgeRepository repository,
                  KnowledgeCatalog catalog, KnowledgeObjects objects, KnowledgeParser parser, KnowledgeIndex index,
                  WikiService wiki, @Value("${erp.ai.kb-worker-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.repository = repository;
        this.catalog = catalog;
        this.objects = objects;
        this.parser = parser;
        this.index = index;
        this.wiki = wiki;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${erp.ai.kb-worker-delay-ms:3000}")
    void run() {
        if (enabled) {
            processUploads();
            wiki.processPending();
            cleanupIndex();
        }
    }

    @Scheduled(cron = "${erp.ai.wiki-lint-cron:0 30 3 * * MON}", zone = "Asia/Shanghai")
    void lint() {
        if (enabled) {
            wiki.scheduledLint();
        }
    }

    void processUploads() {
        List<Work> uploads = jdbc.query("""
                SELECT d.tenant_id, d.id, d.version_no
                  FROM kb_document d
                  JOIN kb_document_version v ON v.tenant_id = d.tenant_id AND v.document_id = d.id AND v.version_no = d.version_no
                  JOIN kb_library l ON l.tenant_id = d.tenant_id AND l.id = d.library_id AND l.status = 'active'
                 WHERE d.disabled = FALSE AND
                """ + CLAIMABLE + " ORDER BY v.updated_at LIMIT 8",
                (result, row) -> new Work(result.getString(1), result.getString(2), result.getInt(3), KnowledgeCatalog.id()));
        for (Work work : uploads) {
            int claimed = jdbc.update("""
                    UPDATE kb_document_version v JOIN kb_document d
                       ON v.tenant_id = d.tenant_id AND v.document_id = d.id AND v.version_no = d.version_no
                       SET v.status = 'parsing', v.claim_id = ?, v.updated_at = NOW(6)
                     WHERE v.tenant_id = ? AND v.document_id = ? AND v.version_no = ? AND d.disabled = FALSE AND
                    """ + CLAIMABLE, work.claim(), work.tenant(), work.document(), work.version());
            if (claimed == 1) {
                process(work);
            }
        }
    }

    private void process(Work work) {
        try {
            var row = repository.document(work.tenant(), work.document(), false);
            byte[] data = objects.read(work.tenant(), row.fileKey());
            if (!row.hash().equals(KnowledgeParser.digest(data))) {
                throw KnowledgeRepository.invalid("文件校验失败，请重新上传");
            }
            List<Part> parts = parser.parse(data, row.document().mime());
            List<KnowledgeIndex.Entry> staged = transaction.execute(status -> stage(work, parts));
            index.index(work.tenant(), false, staged);
            transaction.executeWithoutResult(status -> finish(work));
        } catch (RuntimeException error) {
            String description = error instanceof AiException ? error.getMessage() : "解析或建立索引失败，请重试";
            LOG.warn("知识库文档处理失败，原因类型={}", error.getClass().getSimpleName());
            transaction.executeWithoutResult(status -> fail(work, description));
        }
    }

    private List<KnowledgeIndex.Entry> stage(Work work, List<Part> parts) {
        var row = repository.document(work.tenant(), work.document(), true);
        if (!owns(work, row) || !"parsing".equals(row.document().status())) {
            throw KnowledgeRepository.conflict("解析任务状态已变化");
        }
        clearStaged(work);
        var library = repository.library(work.tenant(), row.document().libraryId(), false);
        var segments = new ArrayList<KnowledgeIndex.Entry>();
        for (int i = 0; i < parts.size(); i++) {
            segments.add(catalog.insertSegment(work.tenant(), work.document(), work.version(), i + 1, parts.get(i),
                    row.document().title(), library, "staged"));
        }
        return segments;
    }

    private void finish(Work work) {
        var row = repository.document(work.tenant(), work.document(), true);
        if (!owns(work, row)) {
            return;
        }
        if (!"parsing".equals(row.document().status())) {
            clearStaged(work);
            return;
        }
        if (row.liveVersion() != null) {
            repository.invalidateOldSegments(work.tenant(), work.document(), row.liveVersion());
        }
        jdbc.update("""
                UPDATE kb_segment SET status = 'indexed' WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """, work.tenant(), work.document(), work.version());
        jdbc.update("UPDATE kb_document SET live_version = ? WHERE tenant_id = ? AND id = ?",
                work.version(), work.tenant(), work.document());
        jdbc.update("""
                UPDATE kb_document_version SET status = 'indexed', error = '', claim_id = NULL
                  WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """, work.tenant(), work.document(), work.version());
        jdbc.update("""
                INSERT IGNORE INTO wiki_task (id,tenant_id,type,trigger_code,document_id,version_no,status)
                    VALUES (?,?,?,'document_indexed',?,?,'queued')
                """, KnowledgeCatalog.id(), work.tenant(), row.liveVersion() == null ? "create" : "update",
                work.document(), work.version());
    }

    private void fail(Work work, String description) {
        var row = repository.document(work.tenant(), work.document(), true);
        if (owns(work, row)) {
            clearStaged(work);
            String message = Objects.toString(description, "文档处理失败");
            jdbc.update("""
                    UPDATE kb_document_version SET status = 'failed', error = ?, claim_id = NULL
                      WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                    """, message.substring(0, Math.min(255, message.length())), work.tenant(), work.document(), work.version());
        }
    }

    private void clearStaged(Work work) {
        jdbc.update("""
                INSERT IGNORE INTO kb_index_cleanup (tenant_id,index_type,object_id)
                SELECT tenant_id,'segment',id FROM kb_segment
                  WHERE tenant_id = ? AND document_id = ? AND version_no = ? AND status = 'staged'
                """, work.tenant(), work.document(), work.version());
        jdbc.update("""
                DELETE FROM kb_segment WHERE tenant_id = ? AND document_id = ? AND version_no = ? AND status = 'staged'
                """, work.tenant(), work.document(), work.version());
    }

    private boolean owns(Work work, KnowledgeRepository.DocRow row) {
        return work.version() == row.document().versionNo() && work.claim().equals(row.claimId());
    }

    private void cleanupIndex() {
        List<Cleanup> rows = jdbc.query("""
                SELECT tenant_id,index_type,object_id FROM kb_index_cleanup ORDER BY created_at LIMIT 100
                """, (result, row) -> new Cleanup(result.getString(1), result.getString(2), result.getString(3)));
        for (Cleanup row : rows) {
            try {
                index.delete(row.tenant(), "wiki".equals(row.type()), row.id());
                jdbc.update("DELETE FROM kb_index_cleanup WHERE tenant_id = ? AND index_type = ? AND object_id = ?",
                        row.tenant(), row.type(), row.id());
            } catch (RuntimeException error) {
                LOG.warn("知识索引清理暂不可用，等待下轮重试");
                break;
            }
        }
    }

    private record Work(String tenant, String document, int version, String claim) {}
    private record Cleanup(String tenant, String type, String id) {}
}
