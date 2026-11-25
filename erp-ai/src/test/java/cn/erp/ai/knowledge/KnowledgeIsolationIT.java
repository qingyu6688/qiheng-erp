package cn.erp.ai.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

import cn.erp.ai.assistant.AssistantCatalog;
import cn.erp.ai.assistant.AssistantRuntime;
import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.gateway.ModelGateway;
import cn.erp.ai.gateway.ModelResult;
import cn.erp.ai.knowledge.KnowledgeTypes.Adjustment;
import cn.erp.ai.knowledge.KnowledgeTypes.LibraryInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiDraftInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiReviewInput;

@EnabledIfEnvironmentVariable(named = "ERP_AI_DB_URL",
        matches = "jdbc:mysql://(?:127\\.0\\.0\\.1|localhost|\\[::1])(?::[0-9]+)?/erp_ai_test(?:_[a-z0-9]+)?(?:\\?.*)?")
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
    @Autowired AssistantCatalog assistants;
    @Autowired AssistantRuntime runtime;
    @Autowired ObjectMapper json;
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
        assertThat(seeded).extracting(KnowledgeTypes.Document::title).containsExactly("业务规则", "用户使用手册", "运维手册", "优化文档");
        assertThat(catalog.seed(operator)).extracting(KnowledgeTypes.Document::id)
                .containsExactlyElementsOf(seeded.stream().map(KnowledgeTypes.Document::id).toList());
        worker.processUploads();
        wiki.processPending();
        assertThat(wiki.reviews(operator)).hasSize(4).allMatch(item -> item.pendingRevision() != null);
        assertThat(wiki.browse(operator, "")).isEmpty();
    }

    @Test
    void refreshesChangedSeedInPlaceAndRetiresOldEvidenceOnlyAfterSuccessfulIndexing() {
        var seeded = catalog.seed(operator);
        worker.processUploads();
        var rules = seeded.getFirst();
        catalog.upload(operator, rules.libraryId(), rules.id(), rules.title(), file("旧版本库存范围尚未包含批次管理。"));
        worker.processUploads();
        String old = catalog.segments(operator, rules.id()).getFirst().id();
        var refreshed = catalog.seed(operator);
        assertThat(refreshed).extracting(KnowledgeTypes.Document::id)
                .containsExactlyElementsOf(seeded.stream().map(KnowledgeTypes.Document::id).toList());
        assertThat(refreshed.getFirst().versionNo()).isEqualTo(3);
        assertThat(knowledge.canRead(operator, old, null)).isTrue();
        worker.processUploads();
        assertThat(knowledge.canRead(operator, old, null)).isFalse();
        assertThat(catalog.segments(operator, rules.id())).anyMatch(segment -> segment.content().contains("批次、序列号、保质期和库位已支持"));
        assertThat(catalog.seed(operator)).extracting(KnowledgeTypes.Document::versionNo)
                .containsExactly(3, 1, 1, 1);
        assertThat(jdbc.queryForList("SELECT seed_key FROM kb_document WHERE tenant_id=? ORDER BY seed_key", String.class, tenant))
                .containsExactly("official-manual-0", "official-manual-1", "official-manual-2", "official-manual-3");
    }

    @Test
    void answersUpdatedScopeAndPaymentTermsUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(257,272,"B09-知识问答回归.json");
    }

    @Test
    void answersPurchasePriceRulesUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(273,280,"B02-采购调价知识问答.json");
    }

    @Test
    void answersPurchaseFeeRulesUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(281,288,"B03-采购费用知识问答.json");
    }

    @Test
    void answersInboundInvoiceRulesUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(289,296,"B04-进项核对知识问答.json");
    }

    @Test
    void answersSalesPriceAndWholeInvoiceRedRulesUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(297,304,"B02-销售调价知识问答.json");
    }

    @Test
    void answersReturnCreditAndCompletenessRulesUsingActuallyImportedOfficialEvidence() throws Exception {
        answersOfficialEvidence(305,320,"B05-B08-业务完整性知识问答.json");
    }

    private void answersOfficialEvidence(int first,int last,String reportName) throws Exception {
        catalog.seed(operator);
        worker.processUploads();
        assertThat(catalog.documents(operator, "official-manuals")).hasSize(4)
                .allMatch(document -> "indexed".equals(document.status()));
        var config = new AssistantCatalog.Config(null, "internal", "官方手册回归助手", "", Set.of("official-manuals"), Set.of(), Set.of(), true);
        assistants.save(operator, config);
        var current = assistants.require(operator, "internal");
        // 受控模型只转述实际召回原文，评测覆盖导入、检索、引用及权限，不代表真实模型质量。
        when(model.generate(eq(operator), eq("kb_chat"), anyString(), anyString(), any(), any())).thenAnswer(call -> {
            call.getArgument(4, Runnable.class).run();
            var evidence = json.readTree(call.getArgument(3, String.class)).path("evidence");
            var paragraphs = new ArrayList<String>();
            for (var source : evidence) {
                String text = source.path("content").asString().replace("```", "").replace("<", "").replace(">", "").replaceAll("\\s+", " ");
                paragraphs.add(text + "[" + source.path("number").asInt() + "]");
            }
            return new ModelResult(String.join("\n\n", paragraphs), "controlled-extractive", 0, 0);
        });
        var results = new ArrayList<Map<String, Object>>();
        try (var input = new ClassPathResource("eval/m13-kb.json").getInputStream()) {
            for (var test : json.readTree(input)) {
                int number=Integer.parseInt(test.path("id").asString().substring(4));
                if (number<first || number>last) continue;
                var answer = runtime.answer(operator, current, test.path("question").asString(), List.of(), () -> operator);
                boolean facts = !answer.refused();
                for (var group : test.path("answerAnchors")) {
                    boolean found = false;
                    for (var anchor : group) found |= answer.text().contains(anchor.asString());
                    facts &= found;
                }
                String expected = test.path("expectedDocuments").get(0).asString();
                boolean cited = answer.citations().stream().anyMatch(citation -> expected.equals(citation.title()));
                results.add(Map.of("id", test.path("id").asString(), "question", test.path("question").asString(),
                        "factsCorrect", facts, "citationCorrect", cited, "refused", answer.refused(),
                        "citations", answer.citations(), "receiptsValid", runtime.receiptsValid(operator, current, answer.receipts())));
            }
        }
        Path report = Path.of(System.getenv().getOrDefault("ERP_TEST_ROOT", System.getProperty("java.io.tmpdir")), "erp-ai", reportName);
        Files.createDirectories(report.getParent());
        Files.writeString(report, json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "mode", "受控摘录模型与本机检索", "total", results.size(), "results", results)), StandardCharsets.UTF_8);
        assertThat(results).hasSize(last-first+1).allSatisfy(result -> {
            assertThat(result.get("factsCorrect")).as("%s 答案要点", result.get("id")).isEqualTo(true);
            assertThat(result.get("citationCorrect")).as("%s 资料引用", result.get("id")).isEqualTo(true);
            assertThat(result.get("receiptsValid")).as("%s 当前权限", result.get("id")).isEqualTo(true);
        });
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
        jdbc.update("DELETE FROM ai_assistant WHERE tenant_id IN (?, ?)", tenant, other);
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
