package cn.erp.shared;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ApiVersionController {
    record Version(int apiMajor, String version) {}

    @GetMapping("/api/v1/system/version")
    ResponseEntity<ApiResponse<Version>> version() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.success(new Version(2, "2.1.0")));
    }
}
