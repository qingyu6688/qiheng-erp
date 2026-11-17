package cn.erp.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.gateway.ModelGateway;
import cn.erp.ai.gateway.ModelResult;
import cn.erp.ai.knowledge.KnowledgeTypes.Adjustment;
import cn.erp.ai.knowledge.KnowledgeTypes.LibraryInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiDraftInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiReviewInput;

@EnabledIfEnvironmentVariable(named = "ERP_AI_DB_URL",
        matches = "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1])(?::[0-9]+)?/erp_ai_test(?:\\?.*)?")
@SpringBootTest(properties = {"erp.ai.kb-worker-enabled=false", "erp.ai.search-mode=local"})
class KnowledgeIsolationIT {
    private final String tenant = testTenant();
    private final String other = testTenant();
    private final AiActor operator = actor(tenant, Set.of("101"), Set.of("201"));
    @Autowired JdbcTemplate jdbc;
    @Autowired KnowledgeService knowledge;
    @Autowired KnowledgeCatalog catalog;
    @Autowired KnowledgeWork worker;
    @Autowired WikiService wiki;
    @Autowired WikiEnrichment enrichment;
    @MockitoBean ModelGateway model;

    @Test
    void intersectTenantRoleDepartmentAndAssistantScopeForAllWikiSources() {
        var role = catalog.saveLibrary(operator, null, library("销售口径", "roles", "101"));
        var department = catalog.saveLibrary(operator, null, library("区域口径", "departments", "201"));
        var first = catalog.upload(operator, role.id(), null, "退款流程", file("退款流程需要提交审批。"));
        var second = catalog.upload(operator, department.id(), null, "复核流程", file("复核流程只能由主管执行。"));
        worker.processUploads();
        wiki.processPending();
        String sales = catalog.segments(operator, first.id()).getFirst().id();
        String north = catalog.segments(operator, second.id()).getFirst().id();
        var pending = wiki.prepareDraft(operator, new WikiDraftInput("退款和复核", List.of(sales, north), null));
        assertThat(wiki.browse(operator, "")).isEmpty();
        assertThat(knowledge.search(operator, "退款流程", Set.of(role.id()))).extracting(KnowledgeService.Evidence::segmentId)
                .contains(sales);
        assertThat(knowledge.search(actor(other, Set.of("101"), Set.of("201")), "退款流程", null)).isEmpty();
        assertThat(knowledge.canRead(actor(tenant, Set.of("101"), Set.of()), north, null)).isFalse();
        assertThat(knowledge.canRead(actor(tenant, Set.of(), Set.of("201")), sales, null)).isFalse();
        assertThatThrownBy(() -> wiki.publish(operator, pending.id(), new WikiReviewInput("stale", null, null)))
                .isInstanceOf(AiException.class);
        assertThatThrownBy(() -> wiki.prepareDraft(actor(tenant, Set.of("101"), Set.of()),
                new WikiDraftInput("越权组合", List.of(sales, north), null))).isInstanceOf(AiException.class);
        var published = wiki.publish(operator, pending.id(), new WikiReviewInput(pending.pendingRevision().id(), null, null));
        String wikiId = "wiki:" + published.currentRevision().id();
        assertThat(knowledge.citation(operator, wikiId).sourceIds()).containsExactly(sales, north);
        assertThat(knowledge.search(operator, "退款和复核", null).getFirst().segmentId()).isEqualTo(wikiId);
        assertThat(knowledge.canRead(operator, wikiId, Set.of(role.id()))).isFalse();
        assertThat(wiki.browse(actor(tenant, Set.of("101"), Set.of()), "")).isEmpty();
        assertThatThrownBy(() -> wiki.read(actor(other, Set.of("101"), Set.of("201")), pending.slug()))
                .isInstanceOf(AiException.class);
        var fingerprint = knowledge.citation(operator, sales).fingerprint();
        catalog.saveLibrary(operator, role.id(), library("销售口径", "all"));
        assertThat(knowledge.citation(operator, sales).fingerprint()).isNotEqualTo(fingerprint);
        assertThat(knowledge.canRead(operator, wikiId, null)).isFalse();
        assertThat(wiki.browse(operator, "")).isEmpty();
        wiki.lint(tenant);
        assertThat(wiki.issues(operator)).extracting(KnowledgeTypes.WikiLint::type).contains("stale_source");
    }

    @Test
    void keepsOldVersionUntilIndexingSucceedsAndInvalidatesAdjustedSegments() {
        var library = catalog.saveLibrary(operator, null, library("库存规则", "all"));
        var document = catalog.upload(operator, library.id(), null, "库存口径", file("盘点调整需要人工复核。"));
        worker.processUploads();
        String original = catalog.segments(operator, document.id()).getFirst().id();
        catalog.upload(operator, library.id(), document.id(), "库存口径", file("# 仅标题"));
        worker.processUploads();
        assertThat(catalog.documents(operator, library.id()).getFirst().status()).isEqualTo("failed");
        assertThat(knowledge.canRead(operator, original, null)).isTrue();
        catalog.upload(operator, library.id(), document.id(), "库存口径", file("盘点差异应先核对，再发起库存调整。"));
        worker.processUploads();
        var selected = catalog.segments(operator, document.id()).getFirst();
        assertThat(knowledge.canRead(operator, original, null)).isFalse();
        var halves = catalog.adjust(operator, document.id(), new Adjustment("split", selected.id(), null, 7));
        assertThat(halves).hasSize(2);
        assertThat(knowledge.canRead(operator, selected.id(), null)).isFalse();
        var merged = catalog.adjust(operator, document.id(), new Adjustment("merge", halves.get(0).id(), halves.get(1).id(), null));
        assertThat(merged).hasSize(1);
        assertThat(knowledge.canRead(operator, halves.get(0).id(), null)).isFalse();
        assertThat(knowledge.canRead(operator, merged.getFirst().id(), null)).isTrue();
        catalog.disable(operator, document.id());
        assertThat(knowledge.canRead(operator, merged.getFirst().id(), null)).isFalse();
    }

    @Test
    void importsOfficialManualsIdempotentlyAndRequiresReviewBeforePublishing() {
        var seeded = catalog.seed(operator);
        assertThat(seeded).extracting(KnowledgeTypes.Document::title).containsExactly("业务规则", "用户使用手册", "运维手册");
        assertThat(catalog.seed(operator)).extracting(KnowledgeTypes.Document::id)
                .containsExactlyElementsOf(seeded.stream().map(KnowledgeTypes.Document::id).toList());
        worker.processUploads();
        wiki.processPending();
        assertThat(wiki.reviews(operator)).hasSize(3).allMatch(item -> item.pendingRevision() != null);
        assertThat(wiki.browse(operator, "")).isEmpty();
    }

    @Test
    void queuedEnrichmentChecksCurrentReviewerAndDoesNotSaveAfterRoleRevocation() {
        String page = indexedDraft();
        AiActor denied = actor(tenant, Set.of(), Set.of());
        assertThat(enrichment.enrichNext(denied, () -> denied).status()).isEqualTo("idle");
        assertThat(enrichment.enrichNext(actor(other, Set.of("101"), Set.of()), () -> operator).status()).isEqualTo("idle");
        verifyNoInteractions(model);

        when(model.generate(eq(operator), eq("wiki_maintain"), anyString(), anyString(), any(), any())).thenReturn(
                new ModelResult("复核需要保留过程记录。[1]", "901", 25, 8));
        String prior = wiki.review(operator, page).pendingRevision().id();
        var verifications = new AtomicInteger();
        assertThat(enrichment.enrichNext(operator, () -> verifications.incrementAndGet() == 1 ? operator : denied).status())
                .isEqualTo("unavailable");
        assertThat(wiki.review(operator, page).pendingRevision().id()).isEqualTo(prior);
        assertThat(wiki.browse(operator, "")).isEmpty();
    }

    @Test
    void onlyOneReviewerClaimsTheSameEnrichmentAndResultStillRequiresApproval() throws Exception {
        String page = indexedDraft();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(model.generate(any(), eq("wiki_maintain"), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(4, Runnable.class).run();
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new ModelResult("复核需要保留过程记录。[1]", "901", 25, 8);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> enrichment.enrichNext(operator, () -> operator));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(enrichment.enrichNext(operator, () -> operator).status()).isEqualTo("idle");
            } finally {
                release.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS).status()).isEqualTo("generated");
        }
        assertThat(wiki.review(operator, page).pendingRevision().authorType()).isEqualTo("ai");
        assertThat(wiki.browse(operator, "")).isEmpty();
    }

    private String indexedDraft() {
        var library = catalog.saveLibrary(operator, null, library("复核说明", "roles", "101"));
        catalog.upload(operator, library.id(), null, "复核办法", file("复核需要保留过程记录。"));
        worker.processUploads();
        wiki.processPending();
        return wiki.reviews(operator).getFirst().id();
    }

    @AfterEach
    void removeOnlyThisTestTenants() {
        for (String table : List.of("wiki_lint_issue", "wiki_link", "wiki_revision_source", "wiki_revision",
                "wiki_task", "wiki_page", "kb_index_cleanup", "kb_segment", "kb_document_version",
                "kb_document", "kb_library_acl", "kb_library")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_id IN (?, ?)", tenant, other);
        }
    }

    private static AiActor actor(String tenant, Set<String> roles, Set<String> departments) {
        return new AiActor(tenant, "301", "401", Set.of("ai:use", "ai_admin:manage", "ai_admin:review"), roles, departments);
    }

    private static String testTenant() {
        return Long.toString(ThreadLocalRandom.current().nextLong(8_000_000_000_000_000_000L, 9_000_000_000_000_000_000L));
    }

    private static LibraryInput library(String name, String visibility, String... targets) {
        return new LibraryInput(name, "", visibility, Set.of(targets), "active");
    }

    private static MockMultipartFile file(String body) {
        return new MockMultipartFile("file", "测试.md", "text/markdown",
                ("# 规则\n" + body).getBytes(StandardCharsets.UTF_8));
    }
}
