package cn.erp.documents;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;

@Service
public class AttachmentService {
    private static final int MAX_FILES = 20;
    private final JdbcTemplate jdbc;
    private final DocumentAccess documents;
    private final AttachmentFiles validator;
    private final ObjectFiles files;
    private final ObjectCompensation compensation;
    private final BusinessCommands commands;
    private final AuditService audit;

    public AttachmentService(JdbcTemplate jdbc, DocumentAccess documents, AttachmentFiles validator, ObjectFiles files,
            ObjectCompensation compensation, BusinessCommands commands, AuditService audit) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.validator = validator;
        this.files = files;
        this.compensation = compensation;
        this.commands = commands;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(CompanyAccess scope, String type, long ownerId) {
        documents.view(scope, type, ownerId);
        return jdbc.queryForList("""
                SELECT id,owner_type,owner_id,file_name,content_type,size_bytes,sha256,status,created_by,created_at
                FROM attachment WHERE tenant_id=? AND company_id=? AND owner_type=? AND owner_id=? AND status='active' ORDER BY id
                """, scope.tenantId(), scope.companyId(), type, ownerId).stream().map(FinanceSupport::row).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, Object> upload(CompanyAccess scope, String type, long ownerId, MultipartFile multipart, String key) {
        documents.writable(scope, type, ownerId);
        var file = validator.validate(multipart);
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new ApiException(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的幂等键");
        }
        long candidateId = Ids.next();
        String candidate = scope.tenantId() + "/" + scope.companyId() + "/attachment_" + UUID.randomUUID();
        compensation.stage(candidate);
        boolean associated = false;
        try {
            files.put(candidate, file.bytes(), file.contentType());
            Map<String, Object> result = commands.execute(scope, "attachment.upload", key,
                    Map.of("ownerType", type, "ownerId", Long.toString(ownerId), "fileName", file.fileName(),
                            "sha256", file.sha256(), "sizeBytes", file.bytes().length), 201, () -> {
                        documents.lock(scope, type, ownerId);
                        Integer count = jdbc.queryForObject("""
                                SELECT COUNT(*) FROM attachment WHERE tenant_id=? AND company_id=?
                                AND owner_type=? AND owner_id=? AND status='active'
                                """, Integer.class, scope.tenantId(), scope.companyId(), type, ownerId);
                        if (count != null && count >= MAX_FILES) {
                            throw new ApiException(409, "ATTACHMENT_LIMIT_REACHED", "每张单据最多保留20个附件");
                        }
                        jdbc.update("""
                                INSERT INTO attachment(id,tenant_id,company_id,owner_type,owner_id,storage_key,
                                file_name,content_type,size_bytes,sha256,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                                """, candidateId, scope.tenantId(), scope.companyId(), type, ownerId, candidate,
                                file.fileName(), file.contentType(), file.bytes().length, file.sha256(), scope.userId());
                        compensation.associated(candidate);
                        Map<String, Object> created = metadata(find(scope, candidateId, true));
                        audit.record(scope, "attachment.upload", "attachment", candidateId, null, created);
                        return created;
                    }, (Class) Map.class);
            associated = Long.toString(candidateId).equals(result.get("id"));
            var current = find(scope, Long.parseLong(result.get("id").toString()), true);
            documents.writable(scope, current.get("owner_type").toString(), ((Number) current.get("owner_id")).longValue());
            return metadata(current);
        } finally {
            if (!associated) { compensation.discard(candidate); }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Map<String, Object> delete(CompanyAccess scope, long id, String key) {
        var current = find(scope, id, false);
        String type = current.get("owner_type").toString();
        long ownerId = ((Number) current.get("owner_id")).longValue();
        documents.writable(scope, type, ownerId);
        return commands.execute(scope, "attachment.delete", key, Map.of("id", Long.toString(id)), 200, () -> {
            documents.lock(scope, type, ownerId);
            var original = find(scope, id, true);
            var before = metadata(original);
            jdbc.update("UPDATE attachment SET status='deleted' WHERE tenant_id=? AND company_id=? AND id=? AND status='active'",
                    scope.tenantId(), scope.companyId(), id);
            compensation.retire(original.get("storage_key").toString());
            var after = metadata(find(scope, id, false));
            audit.record(scope, "attachment.delete", "attachment", id, before, after);
            return after;
        }, (Class) Map.class);
    }

    public Map<String, Object> readable(CompanyAccess scope, long id) {
        var row = find(scope, id, true);
        documents.view(scope, row.get("owner_type").toString(), ((Number) row.get("owner_id")).longValue());
        return row;
    }

    private Map<String, Object> find(CompanyAccess scope, long id, boolean active) {
        var rows = jdbc.queryForList("SELECT * FROM attachment WHERE tenant_id=? AND company_id=? AND id=?" + (active ? " AND status='active'" : ""),
                scope.tenantId(), scope.companyId(), id);
        if (rows.isEmpty()) { throw missing(); }
        documents.permission(rows.getFirst().get("owner_type").toString(), "view");
        return rows.getFirst();
    }

    private Map<String, Object> metadata(Map<String, Object> raw) {
        var copy = new java.util.LinkedHashMap<>(raw);
        copy.keySet().removeAll(java.util.Set.of("storage_key", "tenant_id", "company_id"));
        return FinanceSupport.row(copy);
    }

    private ApiException missing() { return new ApiException(404, "NOT_FOUND", "附件不存在或无权查看"); }
}
