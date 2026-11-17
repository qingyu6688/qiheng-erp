package cn.erp.printdesign;

import cn.erp.identity.AccessService;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/print-templates")
class PrintDesignController {
    private final AccessService access;
    private final PrintTemplates templates;

    PrintDesignController(AccessService access,PrintTemplates templates) {this.access=access;this.templates=templates;}

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@RequestParam(required=false) String documentType,@RequestParam(required=false) Long cursor,
                        @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(templates.list(access.requireCompany(request,PrintTemplates.VIEW),documentType,cursor,limit));
    }

    @GetMapping("/document-types")
    ApiResponse<?> documents(HttpServletRequest request) {
        return ApiResponse.success(templates.documents(access.requireCompany(request,PrintTemplates.VIEW)));
    }

    @GetMapping("/fields/{type}")
    ApiResponse<?> fields(HttpServletRequest request,@PathVariable String type) {
        return ApiResponse.success(templates.definitions(access.requireCompany(request,PrintTemplates.VIEW),type));
    }

    @GetMapping("/documents/{type}")
    ApiResponse<?> choices(HttpServletRequest request,@PathVariable String type,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(templates.choices(access.requireCompany(request,PrintTemplates.VIEW),type,keyword));
    }

    @GetMapping("/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(templates.detail(access.requireCompany(request,PrintTemplates.VIEW),id));
    }

    @PostMapping
    ApiResponse<?> create(HttpServletRequest request,@RequestBody PrintLayout.Input input) {
        return ApiResponse.success(templates.save(access.requireCompany(request,PrintTemplates.MANAGE),null,input));
    }

    @PutMapping("/{id}")
    ApiResponse<?> update(HttpServletRequest request,@PathVariable long id,@RequestBody PrintLayout.Input input) {
        return ApiResponse.success(templates.save(access.requireCompany(request,PrintTemplates.MANAGE),id,input));
    }

    @PostMapping("/preview")
    ApiResponse<?> preview(HttpServletRequest request,@RequestBody PrintLayout.Preview input) {
        return ApiResponse.success(templates.preview(access.requireCompany(request,PrintTemplates.VIEW),input));
    }

    @GetMapping("/output/{type}/{id}")
    ApiResponse<?> output(HttpServletRequest request,@PathVariable String type,@PathVariable long id,@RequestParam(required=false) Long templateId) {
        return ApiResponse.success(templates.output(access.requireCompany(request,null),type,id,templateId));
    }

    @PostMapping("/output/{type}/{id}/log")
    ApiResponse<?> log(HttpServletRequest request,@PathVariable String type,@PathVariable long id,
                       @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody PrintLayout.Log input) {
        templates.browserPrint(access.requireCompany(request,null),type,id,key,input);
        return ApiResponse.success(null);
    }
}
