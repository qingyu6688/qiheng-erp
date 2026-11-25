package cn.erp.einvoice;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/e-invoices")
class EInvoiceController {
    private final AccessService access;
    private final EInvoiceService service;
    private final EInvoiceSources sources;
    private final EInvoiceConfig config;
    private final EInvoiceInbound inbound;
    private final EInvoiceFiles files;
    private final EInvoiceTaxCodes taxCodes;
    private final InboundInvoiceMatching inboundMatching;
    private final InboundInvoiceQueries inboundQueries;

    EInvoiceController(AccessService access,EInvoiceService service,EInvoiceSources sources,EInvoiceConfig config,
                       EInvoiceInbound inbound,EInvoiceFiles files,EInvoiceTaxCodes taxCodes,InboundInvoiceMatching inboundMatching,InboundInvoiceQueries inboundQueries) {
        this.access=access;
        this.service=service;
        this.sources=sources;
        this.config=config;
        this.inbound=inbound;
        this.files=files;
        this.taxCodes=taxCodes;
        this.inboundMatching=inboundMatching;this.inboundQueries=inboundQueries;
    }

    @GetMapping("/requests")
    ApiResponse<?> requests(HttpServletRequest request,@RequestParam(required=false) Long cursor,
                            @RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(service.list(actor(request,"einv:view"),cursor,limit));
    }

    @GetMapping("/requests/{id}")
    ApiResponse<?> detail(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(service.detail(actor(request,"einv:view"),id));
    }

    @GetMapping("/candidates")
    ApiResponse<?> candidates(HttpServletRequest request,@RequestParam long partnerId) {
        var actor=actor(request,"einv:create");
        if(partnerId<=0)throw EInvoiceConfig.invalid("客户编号无效");
        return ApiResponse.success(sources.candidates(actor,partnerId));
    }

    @PostMapping("/requests")
    ApiResponse<?> create(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,
                          @RequestBody @Valid EInvoiceService.Draft draft) {
        return ApiResponse.success(service.create(actor(request,"einv:create"),key,draft));
    }

    @PostMapping("/requests/{id}/submit")
    ApiResponse<?> submit(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
                          @RequestBody @Valid EInvoiceService.Revision revision) {
        return ApiResponse.success(service.submit(actor(request,"einv:submit"),id,key,revision));
    }

    @PostMapping("/requests/{id}/red-issue")
    ApiResponse<?> redIssue(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
                            @RequestBody @Valid EInvoiceService.Revision revision) {
        return ApiResponse.success(service.redIssue(actor(request,"einv:red_issue"),id,key,revision));
    }

    @GetMapping("/requests/{id}/files/{variant}/{format}")
    ResponseEntity<byte[]> download(HttpServletRequest request,@PathVariable long id,@PathVariable String variant,@PathVariable String format) {
        var file=files.get(actor(request,"einv:download"),id,variant,format);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(file.content().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.name()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL,"no-store").header("X-Content-Type-Options","nosniff").body(file.content());
    }

    @GetMapping("/provider")
    ApiResponse<?> getProvider(HttpServletRequest request) {return ApiResponse.success(config.get(actor(request,"einv:manage")));}

    @PutMapping("/provider")
    ApiResponse<?> saveProvider(HttpServletRequest request,@RequestBody @Valid EInvoiceConfig.Input input) {
        return ApiResponse.success(config.save(actor(request,"einv:manage"),input));
    }

    @PostMapping("/provider/test")
    ApiResponse<?> testProvider(HttpServletRequest request) {
        config.test(actor(request,"einv:manage"));
        return ApiResponse.success(java.util.Map.of("connected",true));
    }

    @GetMapping("/inbound")
    ApiResponse<?> listInbound(HttpServletRequest request,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="30") int limit,
            @RequestParam(required=false) String matchStatus,@RequestParam(required=false) String source,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(inboundQueries.list(actor(request,"einv:inbound"),cursor,limit,matchStatus,source,keyword));
    }

    @GetMapping("/inbound/{id}")
    ApiResponse<?> inboundDetail(HttpServletRequest request,@PathVariable long id) {return ApiResponse.success(inboundMatching.detail(actor(request,"einv:inbound"),id));}

    @GetMapping("/inbound/suppliers")
    ApiResponse<?> inboundSuppliers(HttpServletRequest request,@RequestParam(required=false) String keyword,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(inboundMatching.suppliers(actor(request,"einv:inbound_register"),keyword,cursor,limit));
    }

    @GetMapping("/inbound/{id}/history")
    ApiResponse<?> inboundHistory(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(inboundMatching.history(actor(request,"einv:inbound"),id,cursor,limit));
    }

    @GetMapping("/inbound/{id}/sources")
    ApiResponse<?> inboundSources(HttpServletRequest request,@PathVariable long id,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        return ApiResponse.success(inboundMatching.candidates(actor(request,"einv:inbound"),id,cursor,limit));
    }

    @PostMapping("/inbound")
    ApiResponse<?> registerInbound(HttpServletRequest request,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody @Valid InboundInvoiceMatching.Manual input) {
        return ApiResponse.success(inboundMatching.register(actor(request,"einv:inbound_register"),key,input));
    }

    @PutMapping("/inbound/{id}")
    ApiResponse<?> updateInbound(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody @Valid InboundInvoiceMatching.Manual input) {
        return ApiResponse.success(inboundMatching.update(actor(request,"einv:inbound_register"),id,key,input));
    }

    @PostMapping("/inbound/{id}/void")
    ApiResponse<?> voidInbound(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody @Valid InboundInvoiceMatching.Void input) {
        return ApiResponse.success(inboundMatching.voidManual(actor(request,"einv:inbound_void"),id,key,input));
    }

    @PostMapping("/inbound/{id}/preview")
    ApiResponse<?> previewInbound(HttpServletRequest request,@PathVariable long id,@RequestBody @Valid InboundInvoiceMatching.Allocation input) {
        return ApiResponse.success(inboundMatching.preview(actor(request,"einv:inbound"),id,input));
    }

    @PostMapping("/inbound/{id}/allocations")
    ApiResponse<?> allocateInbound(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,@RequestBody @Valid InboundInvoiceMatching.Allocation input) {
        return ApiResponse.success(inboundMatching.allocate(actor(request,"einv:inbound"),id,key,input));
    }

    @PostMapping("/inbound/sync")
    ApiResponse<?> syncInbound(HttpServletRequest request,@RequestBody @Valid EInvoiceInbound.Range range) {
        return ApiResponse.success(inbound.sync(actor(request,"einv:inbound"),range));
    }

    @GetMapping("/inbound/{id}/payables")
    ApiResponse<?> payableCandidates(HttpServletRequest request,@PathVariable long id) {
        return ApiResponse.success(inbound.candidates(actor(request,"einv:inbound"),id));
    }

    @PostMapping("/inbound/{id}/match")
    ApiResponse<?> matchInbound(HttpServletRequest request,@PathVariable long id,@RequestHeader(value="Idempotency-Key",required=false) String key,
                                @RequestBody @Valid EInvoiceInbound.Match match) {
        return ApiResponse.success(inbound.match(actor(request,"einv:inbound"),id,match,key));
    }

    @GetMapping("/skus")
    ApiResponse<?> listSkus(HttpServletRequest request,@RequestParam(required=false) String keyword) {
        return ApiResponse.success(taxCodes.list(actor(request,"einv:manage"),keyword));
    }

    @PutMapping("/skus/{id}/tax-category")
    ApiResponse<?> setTaxCategory(HttpServletRequest request,@PathVariable long id,@RequestBody @Valid EInvoiceTaxCodes.Input input) {
        return ApiResponse.success(taxCodes.save(actor(request,"einv:manage"),id,input));
    }

    private CompanyAccess actor(HttpServletRequest request,String permission) {
        var actor=access.requireCompany(request,"einv:view");
        if(!actor.hasPermission(permission))throw new ApiException(403,"FORBIDDEN","没有执行当前发票操作的权限");
        return actor;
    }
}
