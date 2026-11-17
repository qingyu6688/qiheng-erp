package cn.erp.ai.knowledge;

import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.ApiResponse;
import cn.erp.ai.knowledge.KnowledgeService.Evidence;
import cn.erp.ai.knowledge.KnowledgeTypes.Adjustment;
import cn.erp.ai.knowledge.KnowledgeTypes.Document;
import cn.erp.ai.knowledge.KnowledgeTypes.Library;
import cn.erp.ai.knowledge.KnowledgeTypes.LibraryInput;
import cn.erp.ai.knowledge.KnowledgeTypes.Recall;
import cn.erp.ai.knowledge.KnowledgeTypes.SearchInput;
import cn.erp.ai.knowledge.KnowledgeTypes.Segment;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiLint;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiDraftInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiPage;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiReview;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiReviewInput;
import cn.erp.ai.knowledge.KnowledgeTypes.WikiEnrichmentResult;

@RestController
@RequestMapping("/ai/v1")
class KnowledgeController {
    private static final String USE = "ai:use";
    private static final String MANAGE = "ai_admin:manage";
    private static final String VIEW = "ai_admin:view";
    private static final String REVIEW = "ai_admin:review";
    private final AiRequest identity;
    private final KnowledgeCatalog catalog;
    private final KnowledgeService knowledge;
    private final WikiService wiki;
    private final WikiEnrichment enrichment;

    KnowledgeController(AiRequest identity, KnowledgeCatalog catalog, KnowledgeService knowledge, WikiService wiki,
                        WikiEnrichment enrichment) {
        this.identity = identity;
        this.catalog = catalog;
        this.knowledge = knowledge;
        this.wiki = wiki;
        this.enrichment = enrichment;
    }

    @GetMapping("/admin/libraries")
    ApiResponse<List<Library>> libraries(HttpServletRequest request) {
        return ApiResponse.ok(catalog.libraries(actor(request, VIEW, MANAGE)));
    }

    @PostMapping("/admin/libraries")
    ApiResponse<Library> createLibrary(HttpServletRequest request, @RequestBody LibraryInput input) {
        return ApiResponse.ok(catalog.saveLibrary(actor(request, MANAGE), null, input));
    }

    @PutMapping("/admin/libraries/{id}")
    ApiResponse<Library> updateLibrary(HttpServletRequest request, @PathVariable String id, @RequestBody LibraryInput input) {
        return ApiResponse.ok(catalog.saveLibrary(actor(request, MANAGE), id, input));
    }

    @GetMapping("/admin/documents")
    ApiResponse<List<Document>> documents(HttpServletRequest request, @RequestParam(required = false) String libraryId) {
        return ApiResponse.ok(catalog.documents(actor(request, VIEW, MANAGE), libraryId));
    }

    @PostMapping(path = "/admin/documents", consumes = "multipart/form-data")
    ApiResponse<Document> upload(HttpServletRequest request, @RequestParam String libraryId, @RequestParam MultipartFile file,
                                 @RequestParam(required = false) String title, @RequestParam(required = false) String documentId) {
        return ApiResponse.ok(catalog.upload(actor(request, MANAGE), libraryId, documentId, title, file));
    }

    @PostMapping("/admin/documents/{id}/retry")
    ApiResponse<Document> retry(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(catalog.retry(actor(request, MANAGE), id));
    }

    @PostMapping("/admin/documents/{id}/disable")
    ApiResponse<Document> disable(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(catalog.disable(actor(request, MANAGE), id));
    }

    @GetMapping("/admin/documents/{id}/segments")
    ApiResponse<List<Segment>> segments(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(catalog.segments(actor(request, VIEW, MANAGE), id));
    }

    @PostMapping("/admin/documents/{id}/segments/adjust")
    ApiResponse<List<Segment>> adjust(HttpServletRequest request, @PathVariable String id, @RequestBody Adjustment input) {
        return ApiResponse.ok(catalog.adjust(actor(request, MANAGE), id, input));
    }

    @PostMapping("/admin/libraries/search")
    ApiResponse<List<Recall>> recall(HttpServletRequest request, @RequestBody SearchInput input) {
        return ApiResponse.ok(knowledge.diagnostics(actor(request, VIEW, MANAGE), input.query(), input.libraryIds()));
    }

    @GetMapping("/citations/{id}")
    ApiResponse<Evidence> citation(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(knowledge.citation(actor(request, USE, REVIEW), id));
    }

    @GetMapping("/wiki")
    ApiResponse<List<WikiPage>> wiki(HttpServletRequest request, @RequestParam(name = "q", required = false) String query) {
        return ApiResponse.ok(wiki.browse(actor(request, USE), KnowledgeCatalog.optional(query, 4000, "检索问题超过 4000 字")));
    }

    @GetMapping("/wiki/{slug}")
    ApiResponse<WikiPage> read(HttpServletRequest request, @PathVariable String slug) {
        return ApiResponse.ok(wiki.read(actor(request, USE), slug));
    }

    @GetMapping("/admin/wiki/review")
    ApiResponse<List<WikiReview>> reviewList(HttpServletRequest request) {
        return ApiResponse.ok(wiki.reviews(actor(request, REVIEW)));
    }

    @GetMapping("/admin/wiki/{id}/review")
    ApiResponse<WikiReview> review(HttpServletRequest request, @PathVariable String id) {
        return ApiResponse.ok(wiki.review(actor(request, REVIEW), id));
    }

    @PostMapping("/admin/wiki/drafts")
    ApiResponse<WikiReview> draft(HttpServletRequest request, @RequestBody WikiDraftInput input) {
        return ApiResponse.ok(wiki.prepareDraft(actor(request, REVIEW), input));
    }

    @PostMapping("/admin/wiki/{id}/publish")
    ApiResponse<WikiReview> publish(HttpServletRequest request, @PathVariable String id, @RequestBody WikiReviewInput input) {
        return ApiResponse.ok(wiki.publish(actor(request, REVIEW), id, input));
    }

    @PostMapping("/admin/wiki/{id}/reject")
    ApiResponse<WikiReview> reject(HttpServletRequest request, @PathVariable String id, @RequestBody WikiReviewInput input) {
        return ApiResponse.ok(wiki.reject(actor(request, REVIEW), id, input));
    }

    @PostMapping("/admin/wiki/{id}/archive")
    ApiResponse<Map<String, String>> archive(HttpServletRequest request, @PathVariable String id) {
        wiki.archive(actor(request, REVIEW), id);
        return ApiResponse.ok(Map.of("id", id));
    }

    @PostMapping("/admin/wiki/{id}/generate")
    ApiResponse<WikiReview> generate(HttpServletRequest request, @PathVariable String id, @RequestBody WikiReviewInput input) {
        var access = identity.access(request);
        access.actor().require(REVIEW);
        String draft = wiki.generateDraft(access.actor(), id, input.revisionId(), () -> identity.refresh(access));
        var current = identity.refresh(access);
        current.require(REVIEW);
        return ApiResponse.ok(wiki.saveGenerated(current, id, input.revisionId(), draft));
    }

    @PostMapping("/admin/wiki/enrich-next")
    ApiResponse<WikiEnrichmentResult> enrichNext(HttpServletRequest request) {
        var access = identity.access(request);
        access.actor().require(REVIEW);
        return ApiResponse.ok(enrichment.enrichNext(access.actor(), () -> identity.refresh(access)));
    }

    @GetMapping("/admin/wiki/lint")
    ApiResponse<List<WikiLint>> lintList(HttpServletRequest request) {
        return ApiResponse.ok(wiki.issues(actor(request, REVIEW)));
    }

    @PostMapping("/admin/wiki/lint")
    ApiResponse<List<WikiLint>> lint(HttpServletRequest request) {
        AiActor actor = actor(request, REVIEW);
        wiki.lint(actor.tenantId());
        return ApiResponse.ok(wiki.issues(actor));
    }

    @PostMapping("/admin/wiki/seed")
    ApiResponse<List<Document>> seed(HttpServletRequest request) {
        return ApiResponse.ok(catalog.seed(actor(request, MANAGE)));
    }

    private AiActor actor(HttpServletRequest request, String permission) {
        var actor = identity.actor(request);
        actor.require(permission);
        return actor;
    }

    private AiActor actor(HttpServletRequest request, String first, String second) {
        var actor = identity.actor(request);
        if (!actor.permissions().contains(first) && !actor.permissions().contains(second)) {
            throw AiException.forbidden();
        }
        return actor;
    }
}
