package cn.erp.identity;

import cn.erp.shared.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(IdentitySecurity.AUTH+"/devices")
class DeviceController {
    private final DeviceSessions devices;

    DeviceController(DeviceSessions devices) { this.devices=devices; }

    @PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DeviceSessions.Pairing>> pair(HttpServletRequest request,@RequestBody DeviceName body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(devices.pair(request,body.deviceName())));
    }

    @PostMapping(value="/activate",consumes=MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ApiResponse<DeviceSessions.Issued>> activate(HttpServletRequest request,@RequestBody PairingCode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(devices.activate(request,body.pairingCode())));
    }

    @DeleteMapping("/current")
    ApiResponse<Void> logout(HttpServletRequest request) {
        devices.logout(request);
        return ApiResponse.success(null);
    }

    record DeviceName(String deviceName) {}
    record PairingCode(String pairingCode) {}
}
