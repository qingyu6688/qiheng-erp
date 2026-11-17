package cn.erp.wms;

import cn.erp.identity.AccessService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/labels")
class WmsLabelController {
    private final AccessService access;
    private final WmsLabels labels;

    WmsLabelController(AccessService access,WmsLabels labels) { this.access=access;this.labels=labels; }

    @PostMapping("/print-jobs")
    ResponseEntity<byte[]> print(HttpServletRequest request,@RequestBody WmsLabels.PrintInput body) {
        var file=labels.generate(access.requireCompany(request,"label:print"),body);
        return ResponseEntity.ok().contentType("pdf".equals(file.format()) ? MediaType.APPLICATION_PDF : MediaType.APPLICATION_OCTET_STREAM)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename("wms-labels."+file.format()).build().toString())
                .body(file.bytes());
    }
}
