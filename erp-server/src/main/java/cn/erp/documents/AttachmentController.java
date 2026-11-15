package cn.erp.documents;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/attachments")
public class AttachmentController {
    private final AccessService access;
    private final DocumentAccess documents;
    private final AttachmentService attachments;
    private final FileTickets tickets;
    private final ObjectFiles files;

    public AttachmentController(AccessService access, DocumentAccess documents, AttachmentService attachments, FileTickets tickets, ObjectFiles files) {
        this.access = access;
        this.documents = documents;
        this.attachments = attachments;
        this.tickets = tickets;
        this.files = files;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(HttpServletRequest request, @RequestParam String ownerType, @RequestParam long ownerId) {
        var scope = access.requireCompany(request, documents.permission(ownerType, "view"));
        return ApiResponse.success(attachments.list(scope, ownerType, ownerId));
    }

    @PostMapping(consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<Map<String, Object>>> upload(HttpServletRequest request,
            @RequestParam String ownerType, @RequestParam long ownerId, @RequestParam MultipartFile file,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        var scope = access.requireCompany(request, documents.writePermission(ownerType));
        return ResponseEntity.status(201).body(ApiResponse.created(attachments.upload(scope, ownerType, ownerId, file, key)));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(HttpServletRequest request, @PathVariable long id,
            @RequestHeader(value="Idempotency-Key", required=false) String key) {
        var scope = access.requireCompany(request, null);
        return ApiResponse.success(attachments.delete(scope, id, key));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Void> download(HttpServletRequest request, @PathVariable long id) {
        var scope = access.requireCompany(request, null);
        attachments.readable(scope, id);
        String ticket = tickets.issue(scope, "attachment", id);
        return ResponseEntity.status(302).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create("/api/v1/attachments/" + id + "/content?companyId=" + scope.companyId() + "&ticket=" + ticket)).build();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(HttpServletRequest request, @PathVariable long id,
            @RequestParam long companyId, @RequestParam String ticket) {
        if (companyId <= 0) { throw new ApiException(400, "VALIDATION_FAILED", "公司标识无效"); }
        // 原生下载重定向没有自定义请求头，仍用同一AccessService校验所选公司和当前角色。
        var scope = access.requireCompany(companyRequest(request, companyId), null);
        tickets.verify(scope, "attachment", id, ticket);
        var row = attachments.readable(scope, id);
        byte[] bytes = files.read(row.get("storage_key").toString());
        // 对象仓读取可能较慢，返回字节前再次核对当前账号、角色、范围和附件状态。
        var current = access.requireCompany(companyRequest(request, companyId), null);
        tickets.verify(current, "attachment", id, ticket);
        attachments.readable(current, id);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(row.get("file_name").toString(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(row.get("content_type").toString())).body(bytes);
    }

    private HttpServletRequest companyRequest(HttpServletRequest request, long companyId) {
        return new HttpServletRequestWrapper(request) {
            @Override public String getHeader(String name) {
                return name.equalsIgnoreCase("X-Company-Id") ? Long.toString(companyId) : super.getHeader(name);
            }
            @Override public Object getAttribute(String name) {
                return name.equals(AccessService.class.getName() + ".profile") ? null : super.getAttribute(name);
            }
        };
    }
}
