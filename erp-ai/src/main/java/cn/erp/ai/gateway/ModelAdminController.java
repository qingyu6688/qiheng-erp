package cn.erp.ai.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import cn.erp.ai.assistant.AssistantCatalog;
import cn.erp.ai.common.AiRequest;
import cn.erp.ai.common.ApiResponse;

@RestController
@RequestMapping("/ai/v1/admin")
class ModelAdminController {
    private final AiRequest requests;
    private final ModelCatalog models;
    private final AssistantCatalog assistants;
    private final UsageLedger ledger;

    ModelAdminController(AiRequest requests,ModelCatalog models,AssistantCatalog assistants,UsageLedger ledger) {
        this.requests=requests;this.models=models;this.assistants=assistants;this.ledger=ledger;
    }

    @GetMapping("/provider-endpoints")
    ApiResponse<?> endpoints(HttpServletRequest r) { return ApiResponse.ok(models.endpoints(requests.actor(r))); }

    @GetMapping("/providers")
    ApiResponse<?> providers(HttpServletRequest r) { return ApiResponse.ok(models.providers(requests.actor(r))); }

    @PostMapping("/providers")
    ApiResponse<?> provider(HttpServletRequest r,@RequestBody ModelCatalog.ProviderInput input) {
        return ApiResponse.ok(models.saveProvider(requests.actor(r),input));
    }

    @GetMapping("/models")
    ApiResponse<?> models(HttpServletRequest r) { return ApiResponse.ok(models.models(requests.actor(r))); }

    @PostMapping("/models")
    ApiResponse<?> model(HttpServletRequest r,@RequestBody ModelCatalog.ModelInput input) {
        return ApiResponse.ok(models.saveModel(requests.actor(r),input));
    }

    @GetMapping("/routes")
    ApiResponse<?> routes(HttpServletRequest r) { return ApiResponse.ok(models.routes(requests.actor(r))); }

    @PostMapping("/routes")
    ApiResponse<?> route(HttpServletRequest r,@RequestBody ModelCatalog.RouteInput input) {
        return ApiResponse.ok(models.saveRoute(requests.actor(r),input));
    }

    @GetMapping("/assistants")
    ApiResponse<?> assistants(HttpServletRequest r) { return ApiResponse.ok(assistants.adminList(requests.actor(r))); }

    @PostMapping("/assistants")
    ApiResponse<?> assistant(HttpServletRequest r,@RequestBody AssistantCatalog.Config input) {
        return ApiResponse.ok(assistants.save(requests.actor(r),input));
    }

    @GetMapping("/tools")
    ApiResponse<?> tools(HttpServletRequest r) { return ApiResponse.ok(assistants.tools(requests.actor(r))); }

    @GetMapping("/quota")
    ApiResponse<?> quota(HttpServletRequest r) { return ApiResponse.ok(ledger.quota(requests.actor(r))); }

    @PostMapping("/quota")
    ApiResponse<?> quota(HttpServletRequest r,@RequestBody UsageLedger.Quota input) {
        return ApiResponse.ok(ledger.saveQuota(requests.actor(r),input));
    }

    @GetMapping("/usage")
    ApiResponse<?> usage(HttpServletRequest r) { return ApiResponse.ok(ledger.usage(requests.actor(r))); }

    @GetMapping("/logs")
    ApiResponse<?> logs(HttpServletRequest r) { return ApiResponse.ok(ledger.logs(requests.actor(r))); }
}
