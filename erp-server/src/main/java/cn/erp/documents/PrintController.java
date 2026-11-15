package cn.erp.documents;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;

@RestController
@RequestMapping("/api/v1/print-data")
public class PrintController {
    private final AccessService access;
    private final DocumentAccess documents;
    private final PrintService printing;

    public PrintController(AccessService access, DocumentAccess documents, PrintService printing) {
        this.access = access;
        this.documents = documents;
        this.printing = printing;
    }

    @GetMapping("/{documentType}/{id}")
    public ApiResponse<Map<String, Object>> get(HttpServletRequest request, @PathVariable String documentType, @PathVariable long id) {
        var scope = access.requireCompany(request, documents.permission(documentType, "print"));
        return ApiResponse.success(printing.get(scope, documentType, id));
    }
}
