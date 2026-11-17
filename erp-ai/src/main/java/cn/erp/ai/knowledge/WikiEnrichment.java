package cn.erp.ai.knowledge;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiEnrichmentResult;

@Service
class WikiEnrichment {
    private static final String REVIEW = "ai_admin:review";
    private static final String AVAILABLE = """
            status = 'done' AND enrich_status IN ('queued','running')
                AND (enrich_after IS NULL OR enrich_after <= NOW(6))
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate transaction;
    private final KnowledgeRepository repository;
    private final WikiService wiki;

    WikiEnrichment(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, TransactionTemplate transaction,
                   KnowledgeRepository repository, WikiService wiki) {
        this.jdbc = jdbc;
        this.named = named;
        this.transaction = transaction;
        this.repository = repository;
        this.wiki = wiki;
    }

    WikiEnrichmentResult enrichNext(AiActor initial, Supplier<AiActor> verifySession) {
        initial.require(REVIEW);
        Set<String> allowed = repository.allowed(initial, null);
        if (allowed.isEmpty()) {
            return idle();
        }
        Work work = claim(initial, allowed);
        if (work == null) {
            return idle();
        }
        try {
            // 作业只存主题与修订编号，当前会话负责生成前后的组织和权限复核。
            AiActor before = verify(initial, verifySession);
            String generated = wiki.generateDraft(before, work.page(), work.revision(), verifySession);
            AiActor after = verify(initial, verifySession);
            return transaction.execute(status -> commit(after, work, generated));
        } catch (AiException error) {
            if (error.status() == 401 || error.status() == 403) {
                defer(initial.tenantId(), work, "queued", 1);
                throw error;
            }
            int retry = 0;
            if (error.status() == 404) {
                retry = 1;
            } else if (error.status() >= 500 || error.status() == 429) {
                retry = 15;
            }
            defer(initial.tenantId(), work, retry > 0 ? "queued" : "failed", retry);
            return new WikiEnrichmentResult("unavailable", null);
        } catch (RuntimeException error) {
            defer(initial.tenantId(), work, "queued", 15);
            throw error;
        }
    }

    private Work claim(AiActor actor, Set<String> allowed) {
        List<Work> work = named.query("""
                SELECT t.id, t.result_page_id, t.enrich_revision_id
                  FROM (SELECT * FROM wiki_task WHERE tenant_id = :tenant AND
                """ + AVAILABLE + """
                       ) t JOIN wiki_page p
                    ON p.tenant_id = t.tenant_id AND p.id = t.result_page_id AND p.pending_revision_id = t.enrich_revision_id
                 WHERE NOT EXISTS (
                    SELECT 1 FROM wiki_revision_source s
                    JOIN kb_segment k ON k.tenant_id = s.tenant_id AND k.id = s.segment_id
                    JOIN kb_document d ON d.tenant_id = k.tenant_id AND d.id = k.document_id
                    WHERE s.tenant_id = t.tenant_id AND s.revision_id = t.enrich_revision_id AND d.library_id NOT IN (:libraries)
                 )
                 ORDER BY t.created_at, t.id LIMIT 12
                """, new MapSqlParameterSource("tenant", actor.tenantId()).addValue("libraries", allowed),
                (result, row) -> new Work(result.getString(1), result.getString(2), result.getString(3), KnowledgeCatalog.id()));
        for (Work candidate : work) {
            int count = jdbc.update("""
                    UPDATE wiki_task SET enrich_status = 'running', enrich_claim_id = ?, enrich_after = NOW(6) + INTERVAL 3 MINUTE
                    WHERE tenant_id = ? AND id = ? AND enrich_revision_id = ? AND
                    """ + AVAILABLE, candidate.claim(), actor.tenantId(), candidate.id(), candidate.revision());
            if (count == 1) {
                return candidate;
            }
        }
        return null;
    }

    private WikiEnrichmentResult commit(AiActor actor, Work work, String generated) {
        int count = jdbc.update("""
                UPDATE wiki_task SET enrich_status = 'done', enrich_claim_id = NULL, enrich_after = NULL
                WHERE tenant_id = ? AND id = ? AND enrich_claim_id = ? AND enrich_revision_id = ? AND status = 'done'
                    AND enrich_status = 'running' AND enrich_after > NOW(6)
                """, actor.tenantId(), work.id(), work.claim(), work.revision());
        if (count != 1) {
            return idle();
        }
        return new WikiEnrichmentResult("generated", wiki.saveGenerated(actor, work.page(), work.revision(), generated));
    }

    private void defer(String tenant, Work work, String status, int retryMinutes) {
        jdbc.update("""
                UPDATE wiki_task SET enrich_status = ?, enrich_claim_id = NULL, enrich_after = NOW(6) + INTERVAL ? MINUTE
                WHERE tenant_id = ? AND id = ? AND enrich_claim_id = ?
                """, status, retryMinutes, tenant, work.id(), work.claim());
    }

    private AiActor verify(AiActor initial, Supplier<AiActor> refresh) {
        AiActor verified = refresh.get();
        verified.require(REVIEW);
        if (!verified.tenantId().equals(initial.tenantId()) || !verified.userId().equals(initial.userId())
                || !verified.companyId().equals(initial.companyId())) {
            throw AiException.forbidden();
        }
        return verified;
    }

    private WikiEnrichmentResult idle() {
        return new WikiEnrichmentResult("idle", null);
    }

    private record Work(String id, String page, String revision, String claim) {}
}
