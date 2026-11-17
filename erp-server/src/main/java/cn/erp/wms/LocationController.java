package cn.erp.wms;

import cn.erp.identity.AccessService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiResponse;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.oned.Code128Writer;
import jakarta.servlet.http.HttpServletRequest;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/warehouses/{warehouse}/locations")
class LocationController {
    private final AccessService access;
    private final LocationService locations;

    LocationController(AccessService access,LocationService locations) {this.access=access;this.locations=locations;}

    @GetMapping
    ApiResponse<?> list(HttpServletRequest request,@PathVariable long warehouse) {
        return ApiResponse.success(locations.layout(read(request),warehouse));
    }

    @PutMapping("/zones")
    ApiResponse<?> zone(HttpServletRequest request,@PathVariable long warehouse,@RequestBody LocationService.ZoneInput input) {
        return ApiResponse.success(locations.saveZone(write(request),warehouse,input));
    }

    @PutMapping
    ApiResponse<?> slot(HttpServletRequest request,@PathVariable long warehouse,@RequestBody LocationService.LocationInput input) {
        return ApiResponse.success(locations.saveLocation(write(request),warehouse,input));
    }

    @PostMapping("/generate")
    ApiResponse<?> generate(HttpServletRequest request,@PathVariable long warehouse,@RequestBody LocationService.BatchInput input) {
        return ApiResponse.success(locations.generate(write(request),warehouse,input));
    }

    @PutMapping("/settings")
    ApiResponse<?> settings(HttpServletRequest request,@PathVariable long warehouse,@RequestBody LocationService.WarehouseInput input) {
        return ApiResponse.success(locations.configure(write(request),warehouse,input));
    }

    @GetMapping("/balances")
    ApiResponse<?> balances(HttpServletRequest request,@PathVariable long warehouse,@RequestParam(required=false) Long locationId,
                            @RequestParam(required=false) Long skuId,@RequestParam(required=false) Long cursor,@RequestParam(defaultValue="50") int limit) {
        var actor=read(request);
        permission(actor,"inventory:view");
        return ApiResponse.success(locations.balances(actor,warehouse,locationId,skuId,cursor,limit));
    }

    @GetMapping("/{id}/label")
    ApiResponse<?> label(HttpServletRequest request,@PathVariable long warehouse,@PathVariable long id) {
        return ApiResponse.success(locations.label(read(request),warehouse,id));
    }

    @GetMapping(path="/{id}/barcode.png",produces=MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> barcode(HttpServletRequest request,@PathVariable long warehouse,@PathVariable long id) throws IOException {
        var label=locations.label(read(request),warehouse,id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_PNG).body(barcode(label));
    }

    static byte[] barcode(Map<String,Object> label) throws IOException {
        var matrix=new Code128Writer().encode(label.get("code").toString(),BarcodeFormat.CODE_128,760,120);
        var image=new BufferedImage(matrix.getWidth(),matrix.getHeight(),BufferedImage.TYPE_BYTE_BINARY);
        for(int y=0;y<matrix.getHeight();y++) for(int x=0;x<matrix.getWidth();x++) image.setRGB(x,y,matrix.get(x,y)?0xff000000:0xffffffff);
        var output=new ByteArrayOutputStream();
        ImageIO.write(image,"png",output);
        return output.toByteArray();
    }

    private CompanyAccess read(HttpServletRequest request) {
        var scope=access.requireCompany(request,"wms_location:view");permission(scope,"warehouse:view");return scope;
    }
    private CompanyAccess write(HttpServletRequest request) {
        var scope=access.requireCompany(request,"wms_location:manage");permission(scope,"warehouse:update");return scope;
    }
    private void permission(CompanyAccess scope,String code) {
        if(!scope.hasPermission(code)) throw new ApiException(403,"FORBIDDEN","没有执行此操作的权限");
    }
}
