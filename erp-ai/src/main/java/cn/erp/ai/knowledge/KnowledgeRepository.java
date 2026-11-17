package cn.erp.ai.knowledge;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import cn.erp.ai.common.AiActor;
import cn.erp.ai.common.AiException;
import cn.erp.ai.knowledge.KnowledgeTypes.Document;
import cn.erp.ai.knowledge.KnowledgeTypes.Library;
import cn.erp.ai.knowledge.KnowledgeTypes.Segment;

@Repository
class KnowledgeRepository {
    private static final String SOURCES = """
            SELECT s.*, d.library_id, v.title, l.access_version
            FROM kb_segment s
            JOIN kb_document d ON d.tenant_id = s.tenant_id AND d.id = s.document_id AND d.live_version = s.version_no
            JOIN kb_document_version v ON v.tenant_id = d.tenant_id AND v.document_id = d.id AND v.version_no = s.version_no
            JOIN kb_library l ON l.tenant_id = d.tenant_id AND l.id = d.library_id
            WHERE s.tenant_id = :tenant AND d.disabled = FALSE AND s.status = 'indexed' AND v.status = 'indexed'
              AND l.status = 'active'
            """;
    private static final String ACL = """
            AND (l.visibility = 'all' OR EXISTS (
                SELECT 1 FROM kb_library_acl a WHERE a.tenant_id = l.tenant_id AND a.library_id = l.id
                AND ((l.visibility = 'roles' AND a.target_type = 'role' AND a.target_id IN (:roles))
                  OR (l.visibility = 'departments' AND a.target_type = 'department' AND a.target_id IN (:departments)))
            ))
            """;
    private static final String DOCUMENTS = """
            SELECT d.id, d.library_id, d.version_no, d.live_version, d.disabled, d.seed_key,
                   v.title, v.file_name, v.mime, v.size, v.status, v.error, v.uploaded_by, v.updated_at,
                   v.file_object_key, v.hash, v.claim_id
              FROM kb_document d JOIN kb_document_version v
                ON v.tenant_id = d.tenant_id AND v.document_id = d.id AND v.version_no = d.version_no
             WHERE d.tenant_id = ?
            """;
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    KnowledgeRepository(JdbcTemplate jdbc, NamedParameterJdbcTemplate named) {
        this.jdbc = jdbc;
        this.named = named;
    }

    List<Library> libraries(String tenant) {
        return jdbc.query("SELECT * FROM kb_library WHERE tenant_id = ? ORDER BY name, id",
                (result, row) -> library(tenant, result), tenant);
    }

    Library library(String tenant, String id, boolean lock) {
        return jdbc.query("SELECT * FROM kb_library WHERE tenant_id = ? AND id = ?" + (lock ? " FOR UPDATE" : ""),
                (result, row) -> library(tenant, result), tenant, id).stream().findFirst().orElseThrow(KnowledgeRepository::missing);
    }

    private Library library(String tenant, ResultSet result) throws SQLException {
        String id = result.getString("id");
        return new Library(id, result.getString("name"), result.getString("description"),
                result.getString("visibility"), Set.copyOf(jdbc.queryForList("""
                        SELECT target_id FROM kb_library_acl WHERE tenant_id = ? AND library_id = ?
                        """, String.class, tenant, id)), result.getString("status"));
    }

    Set<String> allowed(AiActor actor, Set<String> libraryFilter) {
        if (libraryFilter != null && libraryFilter.isEmpty()) {
            return Set.of();
        }
        String specific = libraryFilter == null ? "" : " AND l.id IN (:libraries)";
        var values = actorParams(actor).addValue("libraries", libraryFilter);
        return new LinkedHashSet<>(named.queryForList(
                "SELECT id FROM kb_library l WHERE tenant_id = :tenant AND status = 'active' " + ACL + specific,
                values, String.class));
    }

    List<DocRow> documents(String tenant, String libraryId) {
        String filter = libraryId == null ? "" : " AND d.library_id = ?";
        Object[] args = libraryId == null ? new Object[]{tenant} : new Object[]{tenant, libraryId};
        return jdbc.query(DOCUMENTS + filter + " ORDER BY v.updated_at DESC, d.id LIMIT 500", this::doc, args);
    }

    DocRow document(String tenant, String id, boolean lock) {
        return jdbc.query(DOCUMENTS + " AND d.id = ?" + (lock ? " FOR UPDATE" : ""), this::doc, tenant, id)
                .stream().findFirst().orElseThrow(KnowledgeRepository::missing);
    }

    String seedId(String tenant, String seedKey) {
        return jdbc.queryForList("SELECT id FROM kb_document WHERE tenant_id = ? AND seed_key = ?",
                String.class, tenant, seedKey).stream().findFirst().orElse(null);
    }

    private DocRow doc(ResultSet result, int row) throws SQLException {
        boolean disabled = result.getBoolean("disabled");
        Document document = new Document(result.getString("id"), result.getString("library_id"),
                result.getString("title"), result.getString("file_name"), result.getString("mime"), result.getLong("size"),
                result.getInt("version_no"), disabled ? "disabled" : result.getString("status"), result.getString("error"),
                result.getString("uploaded_by"), result.getTimestamp("updated_at").toInstant());
        return new DocRow(document, result.getObject("live_version", Integer.class), result.getString("file_object_key"),
                result.getString("hash"), result.getString("claim_id"), result.getString("seed_key"));
    }

    List<Segment> segments(String tenant, String documentId, int version, boolean onlyIndexed) {
        return jdbc.query("""
                SELECT * FROM kb_segment WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """ + (onlyIndexed ? " AND status = 'indexed'" : "") + " ORDER BY seq",
                (result, row) -> segment(result), tenant, documentId, version);
    }

    List<Source> sources(AiActor actor, Collection<String> ids, Set<String> libraryFilter) {
        if (ids.isEmpty() || (libraryFilter != null && libraryFilter.isEmpty())) {
            return List.of();
        }
        String filter = libraryFilter == null ? "" : " AND d.library_id IN (:libraries)";
        return named.query(SOURCES + ACL + " AND s.id IN (:ids)" + filter,
                actorParams(actor).addValue("ids", ids).addValue("libraries", libraryFilter), this::source);
    }

    List<Source> localSources(AiActor actor, Set<String> libraries) {
        if (libraries.isEmpty()) {
            return List.of();
        }
        return named.query(SOURCES + ACL + " AND d.library_id IN (:libraries) ORDER BY s.id LIMIT 10000",
                actorParams(actor).addValue("libraries", libraries), this::source);
    }

    List<Source> activeDocumentSources(String tenant, String documentId) {
        return named.query(SOURCES + " AND d.id = :id ORDER BY s.seq",
                new MapSqlParameterSource("tenant", tenant).addValue("id", documentId), this::source);
    }

    List<Source> currentRevisionSources(String tenant, String revisionId, AiActor actor, Set<String> libraryFilter) {
        if (libraryFilter != null && libraryFilter.isEmpty()) {
            return List.of();
        }
        var values = actor == null ? new MapSqlParameterSource("tenant", tenant) : actorParams(actor);
        String filter = libraryFilter == null ? "" : " AND d.library_id IN (:libraries)";
        String permission = actor == null ? "" : ACL;
        return named.query(SOURCES + permission + filter + """
                AND EXISTS (
                    SELECT 1 FROM wiki_revision_source rs WHERE rs.tenant_id = s.tenant_id
                       AND rs.revision_id = :revision AND rs.segment_id = s.id AND rs.access_version = l.access_version
                )
                ORDER BY s.document_id, s.seq
                """, values.addValue("revision", revisionId).addValue("libraries", libraryFilter), this::source);
    }

    private MapSqlParameterSource actorParams(AiActor actor) {
        return new MapSqlParameterSource("tenant", actor.tenantId())
                .addValue("roles", actor.roleIds().isEmpty() ? List.of("") : actor.roleIds())
                .addValue("departments", actor.departmentIds().isEmpty() ? List.of("") : actor.departmentIds());
    }

    private Source source(ResultSet result, int row) throws SQLException {
        return new Source(segment(result), result.getString("library_id"), result.getString("title"),
                result.getInt("version_no"), result.getLong("access_version"));
    }

    private Segment segment(ResultSet result) throws SQLException {
        return new Segment(result.getString("id"), result.getString("document_id"), result.getInt("seq"),
                result.getString("heading_path"), result.getObject("page_from", Integer.class),
                result.getObject("page_to", Integer.class), result.getInt("char_count"),
                result.getString("status"), result.getString("content"));
    }

    void queueDelete(String tenant, String type, String id) {
        jdbc.update("INSERT IGNORE INTO kb_index_cleanup (tenant_id, index_type, object_id) VALUES (?, ?, ?)",
                tenant, type, id);
    }

    void invalidateOldSegments(String tenant, String documentId, int oldVersion) {
        jdbc.update("""
                INSERT IGNORE INTO kb_index_cleanup (tenant_id, index_type, object_id)
                SELECT tenant_id, 'segment', id FROM kb_segment
                WHERE tenant_id = ? AND document_id = ? AND version_no = ?
                """, tenant, documentId, oldVersion);
        jdbc.update("""
                INSERT IGNORE INTO kb_index_cleanup (tenant_id, index_type, object_id)
                SELECT DISTINCT w.tenant_id, 'wiki', w.current_revision_id
                FROM wiki_page w
                JOIN wiki_revision_source ws ON ws.tenant_id = w.tenant_id AND ws.revision_id = w.current_revision_id
                JOIN kb_segment s ON s.tenant_id = ws.tenant_id AND ws.segment_id = s.id
                WHERE w.tenant_id = ? AND s.document_id = ? AND s.version_no = ?
                """, tenant, documentId, oldVersion);
        jdbc.update("UPDATE kb_segment SET status = 'disabled' WHERE tenant_id = ? AND document_id = ? AND version_no = ?",
                tenant, documentId, oldVersion);
    }

    void invalidateWikiForSegment(String tenant, String segment) {
        jdbc.update("""
                INSERT IGNORE INTO kb_index_cleanup (tenant_id, index_type, object_id)
                SELECT DISTINCT w.tenant_id, 'wiki', w.current_revision_id
                  FROM wiki_page w JOIN wiki_revision_source rs
                    ON rs.tenant_id = w.tenant_id AND rs.revision_id = w.current_revision_id
                 WHERE w.tenant_id = ? AND rs.segment_id = ?
                """, tenant, segment);
    }

    static AiException missing() {
        return new AiException(404, "KNOWLEDGE_NOT_FOUND", "记录不存在或不可访问");
    }

    static AiException conflict(String message) {
        return new AiException(409, "KNOWLEDGE_CHANGED", message);
    }

    static AiException invalid(String message) {
        return new AiException(400, "INVALID_KNOWLEDGE_INPUT", message);
    }

    record DocRow(Document document, Integer liveVersion, String fileKey, String hash, String claimId, String seedKey) {}
    record Source(Segment segment, String libraryId, String title, int version, long accessVersion) {
        String fingerprint() {
            return segment.documentId() + ":" + version + ":" + accessVersion;
        }
    }
}
