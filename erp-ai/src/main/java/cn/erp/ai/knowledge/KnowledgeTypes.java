package cn.erp.ai.knowledge;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class KnowledgeTypes {
    private KnowledgeTypes() {}

    public record Library(String id, String name, String description, String visibility,
                          Set<String> targetIds, String status) {}
    public record LibraryInput(String name, String description, String visibility, Set<String> targetIds, String status) {}
    public record Document(String id, String libraryId, String title, String fileName, String mime, long size,
                           int versionNo, String status, String error, String uploadedBy, Instant updatedAt) {}
    public record Segment(String id, String documentId, int seq, String heading, Integer pageFrom, Integer pageTo,
                          int charCount, String status, String content) {}
    public record Adjustment(String operation, String segmentId, String nextSegmentId, Integer offset) {}
    public record SearchInput(String query, Set<String> libraryIds) {}
    public record Recall(KnowledgeService.Evidence evidence, double score, boolean selected) {}
    public record WikiPage(String id, String slug, String title, String summary, String content, String status,
                           String revisionId, List<String> sourceIds) {}
    public record WikiRevision(String id, String content, List<String> sourceIds, String authorType, String authorId,
                               String changeNote, Instant createdAt) {}
    public record WikiReview(String id, String slug, String title, String summary, String status,
                             WikiRevision currentRevision, WikiRevision pendingRevision, boolean stale) {}
    public record WikiReviewInput(String revisionId, String content, String changeNote) {}
    public record WikiDraftInput(String title, List<String> sourceIds, String pageId) {}
    public record WikiEnrichmentResult(String status, WikiReview review) {}
    public record WikiLint(String id, String pageId, String slug, String title, String type, String detail,
                            String status, Instant createdAt) {}
}
