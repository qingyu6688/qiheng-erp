package cn.erp.openapi;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.AccessService;
import cn.erp.identity.PlatformAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/open-platform")
class OpenAdminController {
    private final AccessService access;
    private final OpenApplications apps;
    private final OpenWebhooks webhooks;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<MultipleOpenApiWebMvcResource> generated;
    private final ObjectMapper json;

    OpenAdminController(AccessService access,OpenApplications apps,OpenWebhooks webhooks,JdbcTemplate jdbc,
            ObjectProvider<MultipleOpenApiWebMvcResource> generated,ObjectMapper json) {
        this.access=access;
        this.apps=apps;
        this.webhooks=webhooks;
        this.jdbc=jdbc;
        this.generated=generated;
        this.json=json;
    }

    @GetMapping("/capabilities")
    ApiResponse<Map<String,List<OpenPolicy.Capability>>> capabilities(HttpServletRequest req) {
        PlatformAccess.require(jdbc,access.requireTenant(req,OpenPolicy.VIEW),OpenPolicy.VIEW);
        return ApiResponse.success(Map.of("scopes",OpenPolicy.SCOPES,"events",OpenPolicy.EVENTS));
    }

    @GetMapping("/apps")
    ApiResponse<List<OpenApplications.View>> apps(HttpServletRequest req) {
        return ApiResponse.success(apps.list(access.requireTenant(req,OpenPolicy.VIEW)));
    }

    @PostMapping("/apps")
    ResponseEntity<ApiResponse<OpenApplications.View>> createApp(HttpServletRequest req,@RequestBody OpenApplications.Input body) {
        var app=apps.create(access.requireTenant(req,OpenPolicy.MANAGE),body);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.created(app));
    }

    @PutMapping("/apps/{id}")
    ApiResponse<OpenApplications.View> updateApp(HttpServletRequest req,@PathVariable long id,@RequestBody OpenApplications.Input body) {
        return ApiResponse.success(apps.update(access.requireTenant(req,OpenPolicy.MANAGE),id,body));
    }

    @PostMapping("/apps/{id}/rotate-secret")
    ResponseEntity<ApiResponse<OpenApplications.Rotation>> rotateApp(HttpServletRequest req,@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(apps.rotate(access.requireTenant(req,OpenPolicy.MANAGE),id)));
    }

    @GetMapping("/calls")
    ApiResponse<List<OpenApplications.Call>> calls(HttpServletRequest req,@RequestParam(required=false) Long appId) {
        return ApiResponse.success(apps.calls(access.requireTenant(req,OpenPolicy.VIEW),appId));
    }

    @GetMapping("/webhooks")
    ApiResponse<List<OpenWebhooks.View>> webhooks(HttpServletRequest req) {
        return ApiResponse.success(webhooks.list(access.requireTenant(req,OpenPolicy.VIEW)));
    }

    @PostMapping("/webhooks")
    ResponseEntity<ApiResponse<OpenWebhooks.View>> createHook(HttpServletRequest req,@RequestBody OpenWebhooks.Input body) {
        var hook=webhooks.create(access.requireTenant(req,OpenPolicy.MANAGE),body);
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.created(hook));
    }

    @PutMapping("/webhooks/{id}")
    ApiResponse<OpenWebhooks.View> updateHook(HttpServletRequest req,@PathVariable long id,@RequestBody OpenWebhooks.Input body) {
        return ApiResponse.success(webhooks.update(access.requireTenant(req,OpenPolicy.MANAGE),id,body));
    }

    @PostMapping("/webhooks/{id}/rotate-secret")
    ResponseEntity<ApiResponse<OpenWebhooks.Rotation>> rotateHook(HttpServletRequest req,@PathVariable long id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(webhooks.rotate(access.requireTenant(req,OpenPolicy.MANAGE),id)));
    }

    @PostMapping("/webhooks/{id}/test")
    ApiResponse<Map<String,String>> testHook(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(webhooks.test(access.requireTenant(req,OpenPolicy.MANAGE),id));
    }

    @GetMapping("/deliveries")
    ApiResponse<List<OpenWebhooks.Delivery>> deliveries(HttpServletRequest req,@RequestParam(required=false) Long webhookId) {
        return ApiResponse.success(webhooks.deliveries(access.requireTenant(req,OpenPolicy.VIEW),webhookId));
    }

    @PostMapping("/deliveries/{id}/retry")
    ApiResponse<Map<String,String>> retry(HttpServletRequest req,@PathVariable long id) {
        return ApiResponse.success(webhooks.retry(access.requireTenant(req,OpenPolicy.MANAGE),id));
    }

    @GetMapping("/spec")
    ApiResponse<JsonNode> spec(HttpServletRequest req) throws com.fasterxml.jackson.core.JsonProcessingException {
        PlatformAccess.require(jdbc,access.requireTenant(req,OpenPolicy.VIEW),OpenPolicy.VIEW);
        var docs=generated.getIfAvailable();
        if (docs==null) throw new ApiException(503,"OPEN_SPEC_UNAVAILABLE","开放接口文档未启用");
        var documentationRequest=new jakarta.servlet.http.HttpServletRequestWrapper(req) {
            @Override public StringBuffer getRequestURL() { return new StringBuffer(req.getRequestURL()).append("/open"); }
        };
        return ApiResponse.success(json.readTree(docs.openapiJson(documentationRequest,req.getRequestURI(),"open",Locale.SIMPLIFIED_CHINESE)));
    }
}
