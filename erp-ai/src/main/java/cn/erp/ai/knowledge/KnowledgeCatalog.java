package cn.erp.ai.knowledge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.knowledge.KnowledgeParser.FileData;
import cn.erp.ai.knowledge.KnowledgeParser.Part;
import cn.erp.ai.knowledge.KnowledgeTypes.Adjustment;
import cn.erp.ai.knowledge.KnowledgeTypes.Document;
import cn.erp.ai.knowledge.KnowledgeTypes.Library;
import cn.erp.ai.knowledge.KnowledgeTypes.LibraryInput;
import cn.erp.ai.knowledge.KnowledgeTypes.Segment;

@Service
class KnowledgeCatalog {
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final String SEED_LIBRARY = "official-manuals";
    private static final List<String> SEED_FILES = List.of("业务规则", "用户使用手册", "运维手册");
    private final KnowledgeRepository repository;
    private final JdbcTemplate jdbc;
    private final KnowledgeParser parser;
    private final KnowledgeObjects objects;
    private final KnowledgeIndex index;

    KnowledgeCatalog(KnowledgeRepository repository, JdbcTemplate jdbc, KnowledgeParser parser,
                     KnowledgeObjects objects, KnowledgeIndex index) {
        this.repository = repository;
        this.jdbc = jdbc;
        this.parser = parser;
        this.objects = objects;
        this.index = index;
    }

    List<Library> libraries(AiActor actor) {
        List<Library> entries = repository.libraries(actor.tenantId());
        if (actor.has("ai_admin:manage")) {
            return entries;
        }
        Set<String> visible = repository.allowed(actor, null);
        return entries.stream().filter(library -> visible.contains(library.id()))
                .map(library -> new Library(library.id(), library.name(), library.description(), library.visibility(),
                        Set.of(), library.status())).toList();
    }

    @Transactional
    public Library saveLibrary(AiActor actor, String libraryId, LibraryInput input) {
        String name = required(input.name(), 160, "请输入有效的知识库名称");
        String description = optional(input.description(), 1000, "知识库描述超过 1000 字");
        String visibility = member(input.visibility(), Set.of("all", "roles", "departments"), "知识库可见范围无效");
        String status = member(input.status() == null ? "active" : input.status(), Set.of("active", "disabled"), "知识库状态无效");
        Set<String> targets = input.targetIds() == null ? Set.of() : input.targetIds();
        if (targets.size() > 200 || targets.stream().anyMatch(value -> value == null || !TARGET.matcher(value).matches())
                || ("all".equals(visibility) != targets.isEmpty())) {
            throw KnowledgeRepository.invalid("请为受限知识库选择有效的角色或部门");
        }
        var tenant = actor.tenantId();
        try {
            if (libraryId == null) {
                libraryId = id();
                jdbc.update("INSERT INTO kb_library (tenant_id,id,name,description,visibility,status) VALUES (?,?,?,?,?,?)",
                        tenant, libraryId, name, description, visibility, status);
            } else {
                updateLibrary(tenant, libraryId, name, description, visibility, status, targets);
                return repository.library(tenant, libraryId, false);
            }
            insertTargets(tenant, libraryId, visibility, targets);
            return repository.library(tenant, libraryId, false);
        } catch (DuplicateKeyException error) {
            throw KnowledgeRepository.conflict("同名知识库已经存在");
        }
    }

    private void updateLibrary(String tenant, String libraryId, String name, String description, String visibility,
                               String status, Set<String> targets) {
        Library prior = repository.library(tenant, libraryId, true);
        boolean changed = !prior.visibility().equals(visibility) || !prior.status().equals(status)
                || !prior.targetIds().equals(targets);
        jdbc.update("""
                UPDATE kb_library SET name = ?, description = ?, visibility = ?, status = ?, access_version = access_version + ?
                    WHERE tenant_id = ? AND id = ?
                """, name, description, visibility, status, changed ? 1 : 0, tenant, libraryId);
        if (changed) {
            jdbc.update("DELETE FROM kb_library_acl WHERE tenant_id = ? AND library_id = ?", tenant, libraryId);
            insertTargets(tenant, libraryId, visibility, targets);
            // 保留正文供有权读者查个人最新引用，Wiki 则强制复审全部来源。
            jdbc.update("""
                    INSERT IGNORE INTO kb_index_cleanup (tenant_id, index_type, object_id)
                    SELECT DISTINCT p.tenant_id, 'wiki', p.current_revision_id
                      FROM wiki_page p
                      JOIN wiki_revision_source rs ON rs.tenant_id = p.tenant_id AND rs.revision_id = p.current_revision_id
                      JOIN kb_segment s ON s.tenant_id = rs.tenant_id AND s.id = rs.segment_id
                      JOIN kb_document d ON d.tenant_id = s.tenant_id AND d.id = s.document_id
                     WHERE p.tenant_id = ? AND d.library_id = ?
                    """, tenant, libraryId);
            jdbc.update("""
                    INSERT INTO wiki_task (id,tenant_id,type,trigger_code,document_id,version_no,status)
                    SELECT UUID(), tenant_id, 'update', 'acl_changed', id, live_version, 'queued'
                      FROM kb_document WHERE tenant_id = ? AND library_id = ? AND live_version IS NOT NULL AND disabled = FALSE
                    ON DUPLICATE KEY UPDATE status = 'queued'
                    """, tenant, libraryId);
        }
    }

    private void insertTargets(String tenant, String libraryId, String visibility, Set<String> targets) {
        String type = "roles".equals(visibility) ? "role" : "department";
        for (String target : targets) {
            jdbc.update("INSERT INTO kb_library_acl (tenant_id,library_id,target_type,target_id) VALUES (?,?,?,?)",
                    tenant, libraryId, type, target);
        }
    }

    List<Document> documents(AiActor actor, String libraryId) {
        List<Document> entries = repository.documents(actor.tenantId(), libraryId).stream()
                .map(KnowledgeRepository.DocRow::document).toList();
        if (actor.has("ai_admin:manage")) {
            return entries;
        }
        Set<String> visible = repository.allowed(actor, libraryId == null ? null : Set.of(libraryId));
        return entries.stream().filter(document -> visible.contains(document.libraryId())).toList();
    }

    @Transactional
    public Document upload(AiActor actor, String libraryId, String documentId, String title, MultipartFile file) {
        return upload(actor, libraryId, documentId, title, parser.validate(file), null);
    }

    private Document upload(AiActor actor, String libraryId, String documentId, String title, FileData file, String seedKey) {
        String tenant = actor.tenantId();
        Library library = repository.library(tenant, libraryId, true);
        if (!"active".equals(library.status())) {
            throw KnowledgeRepository.conflict("请先启用知识库");
        }
        String displayName = title == null || title.isBlank() ? file.fileName() : required(title, 200, "文档标题超过 200 字");
        int version = 1;
        if (documentId == null || documentId.isBlank()) {
            documentId = id();
            jdbc.update("INSERT INTO kb_document (id,tenant_id,library_id,version_no,seed_key) VALUES (?,?,?,?,?)",
                    documentId, tenant, libraryId, version, seedKey);
        } else {
            var prior = repository.document(tenant, documentId, true);
            if (!libraryId.equals(prior.document().libraryId())) {
                throw KnowledgeRepository.missing();
            }
            if (Set.of("uploaded", "parsing").contains(prior.document().status())) {
                throw KnowledgeRepository.conflict("当前文档版本仍在解析");
            }
            if (prior.hash().equals(file.hash()) && "indexed".equals(prior.document().status())
                    && prior.document().title().equals(displayName)) {
                return prior.document();
            }
            version = prior.document().versionNo() + 1;
            jdbc.update("UPDATE kb_document SET version_no = ?, disabled = FALSE WHERE tenant_id = ? AND id = ?",
                    version, tenant, documentId);
        }
        String key = objects.put(tenant, file.bytes());
        jdbc.update("""
                INSERT INTO kb_document_version (tenant_id,document_id,version_no,title,file_name,file_object_key,mime,size,
                                                hash,status,uploaded_by)
                VALUES (?,?,?,?,?,?,?,?,?,'uploaded',?)
                """, tenant, documentId, version, displayName, file.fileName(), key, file.mime(), file.bytes().length,
                file.hash(), actor.userId());
        return repository.document(tenant, documentId, false).document();
    }

    @Transactional
    public Document retry(AiActor actor, String id) {
        var prior = repository.document(actor.tenantId(), id, true).document();
        if (!"failed".equals(prior.status())) {
            throw KnowledgeRepository.conflict("仅失败的文档可重试");
        }
        jdbc.update("""
                UPDATE kb_document_version SET status = 'uploaded', error = '', claim_id = NULL
                    WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """, actor.tenantId(), id, prior.versionNo());
        return repository.document(actor.tenantId(), id, false).document();
    }

    @Transactional
    public Document disable(AiActor actor, String id) {
        String tenant = actor.tenantId();
        var row = repository.document(tenant, id, true);
        jdbc.update("UPDATE kb_document SET disabled = TRUE WHERE tenant_id = ? AND id = ?", tenant, id);
        if (row.liveVersion() != null) {
            repository.invalidateOldSegments(tenant, id, row.liveVersion());
        }
        return repository.document(tenant, id, false).document();
    }

    List<Segment> segments(AiActor actor, String id) {
        var row = repository.document(actor.tenantId(), id, false);
        if (!repository.allowed(actor, Set.of(row.document().libraryId())).contains(row.document().libraryId())) {
            throw KnowledgeRepository.missing();
        }
        if (row.liveVersion() == null || "disabled".equals(row.document().status())) {
            return List.of();
        }
        List<Segment> metadata = repository.segments(actor.tenantId(), id, row.liveVersion(), true);
        Map<String, Segment> readable = new java.util.HashMap<>();
        repository.sources(actor, metadata.stream().map(Segment::id).toList(), Set.of(row.document().libraryId()))
                .forEach(source -> readable.put(source.segment().id(), source.segment()));
        if (metadata.size() != readable.size()) {
            throw KnowledgeRepository.missing();
        }
        return metadata.stream().map(segment -> readable.get(segment.id())).toList();
    }

    @Transactional
    public List<Segment> adjust(AiActor actor, String documentId, Adjustment input) {
        String tenant = actor.tenantId();
        var row = repository.document(tenant, documentId, true);
        if (row.liveVersion() == null || !"indexed".equals(row.document().status())) {
            throw KnowledgeRepository.conflict("仅可调整当前已完成解析的文档");
        }
        var library = repository.library(tenant, row.document().libraryId(), true);
        List<Segment> existing = segments(actor, documentId);
        Segment selected = existing.stream().filter(segment -> segment.id().equals(input.segmentId())).findFirst()
                .orElseThrow(KnowledgeRepository::missing);
        var create = new ArrayList<Part>();
        var disable = new HashSet<>(Set.of(selected.id()));
        buildAdjustment(input, existing, selected, create, disable);
        int version = row.liveVersion();
        int retiredSequence = jdbc.queryForObject("""
                SELECT LEAST(0, COALESCE(MIN(seq), 0)) FROM kb_segment
                    WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """, Integer.class, tenant, documentId, version);
        for (String id : disable) {
            jdbc.update("UPDATE kb_segment SET status = 'disabled', seq = ? WHERE tenant_id = ? AND id = ?",
                    --retiredSequence, tenant, id);
            repository.queueDelete(tenant, "segment", id);
            repository.invalidateWikiForSegment(tenant, id);
        }
        if (!create.isEmpty()) {
            jdbc.update("""
                    UPDATE kb_segment SET seq = seq + ? WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                        AND seq > ? ORDER BY seq DESC
                    """, create.size(), tenant, documentId, version, selected.seq());
        }
        var rows = new ArrayList<KnowledgeIndex.Entry>();
        for (int i = 0; i < create.size(); i++) {
            rows.add(insertSegment(tenant, documentId, version, selected.seq() + i, create.get(i), row.document().title(), library, "indexed"));
        }
        index.index(tenant, false, rows);
        jdbc.update("""
                INSERT INTO wiki_task (id,tenant_id,type,trigger_code,document_id,version_no,status)
                VALUES (?,?,'update','segment_changed',?,?,'queued') ON DUPLICATE KEY UPDATE status = 'queued'
                """, id(), tenant, documentId, version);
        return segments(actor, documentId);
    }

    private void buildAdjustment(Adjustment input, List<Segment> existing, Segment source, List<Part> create, Set<String> disable) {
        switch (member(input.operation(), Set.of("disable", "split", "merge"), "分段调整类型无效")) {
            case "disable" -> { }
            case "split" -> {
                int count = source.content().codePointCount(0, source.content().length());
                if (input.offset() == null || input.offset() <= 0 || input.offset() >= count) {
                    throw KnowledgeRepository.invalid("拆分位置必须在片段正文内");
                }
                int offset = source.content().offsetByCodePoints(0, input.offset());
                for (String text : List.of(source.content().substring(0, offset), source.content().substring(offset))) {
                    if (text.isBlank()) {
                        throw KnowledgeRepository.invalid("拆分后的片段不能只包含空白字符");
                    }
                    create.add(new Part(source.heading(), source.pageFrom(), source.pageTo(), text.strip()));
                }
            }
            case "merge" -> {
                int nextIndex = existing.indexOf(source) + 1;
                if (nextIndex >= existing.size() || !existing.get(nextIndex).id().equals(input.nextSegmentId())) {
                    throw KnowledgeRepository.invalid("仅可向后合并相邻片段");
                }
                Segment next = existing.get(nextIndex);
                if (!source.heading().equals(next.heading())) {
                    throw KnowledgeRepository.invalid("请在同一章节内合并片段");
                }
                String text = source.content() + "\n" + next.content();
                if (text.codePointCount(0, text.length()) > KnowledgeParser.MAX_SEGMENT) {
                    throw KnowledgeRepository.invalid("合并后正文不能超过 800 字");
                }
                disable.add(next.id());
                create.add(new Part(source.heading(), source.pageFrom(), next.pageTo(), text));
            }
        }
    }

    KnowledgeIndex.Entry insertSegment(String tenant, String documentId, int version, int seq, Part part, String title,
                                       Library library, String status) {
        String segmentId = id();
        jdbc.update("""
                INSERT INTO kb_segment (id,tenant_id,document_id,version_no,seq,heading_path,page_from,page_to,char_count,content,status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, segmentId, tenant, documentId, version, seq, part.heading(), part.pageFrom(), part.pageTo(),
                part.content().codePointCount(0, part.content().length()), part.content(), status);
        return new KnowledgeIndex.Entry(segmentId, documentId, title, part.heading(), part.content(), Set.of(library.id()),
                "roles".equals(library.visibility()) ? library.targetIds() : Set.of(),
                "departments".equals(library.visibility()) ? library.targetIds() : Set.of());
    }

    @Transactional
    public List<Document> seed(AiActor actor) {
        String tenant = actor.tenantId();
        jdbc.update("""
                INSERT INTO kb_library (id,tenant_id,name,description,visibility,status)
                VALUES (?,?,'柒衡 ERP 官方手册','仓库随版本发布的官方手册','all','active')
                ON DUPLICATE KEY UPDATE id = id
                """, SEED_LIBRARY, tenant);
        repository.library(tenant, SEED_LIBRARY, true);
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < SEED_FILES.size(); i++) {
            String title = SEED_FILES.get(i);
            String key = "official-manual-" + i;
            String documentId = repository.seedId(tenant, key);
            try (var input = new ClassPathResource("kb-seed/" + title + ".md").getInputStream()) {
                byte[] bytes = input.readNBytes(KnowledgeParser.MAX_BYTES + 1);
                FileData file = parser.validate(title + ".md", bytes);
                if (documentId != null) {
                    var row = repository.document(tenant, documentId, true);
                    if (row.hash().equals(file.hash())) {
                        result.add(row.document());
                        continue;
                    }
                }
                result.add(upload(actor, SEED_LIBRARY, documentId, title, file, key));
            } catch (IOException error) {
                throw KnowledgeHttp.unavailable();
            }
        }
        return result;
    }

    static String id() {
        return UUID.randomUUID().toString();
    }

    static String required(String value, int max, String message) {
        String text = optional(value, max, message);
        if (text.isBlank()) {
            throw KnowledgeRepository.invalid(message);
        }
        return text;
    }

    static String optional(String value, int max, String message) {
        String text = value == null ? "" : value.strip();
        if (text.codePointCount(0, text.length()) > max || text.indexOf('\0') >= 0) {
            throw KnowledgeRepository.invalid(message);
        }
        return text;
    }

    private static String member(String value, Set<String> values, String message) {
        if (value == null || !values.contains(value)) {
            throw KnowledgeRepository.invalid(message);
        }
        return value;
    }
}
