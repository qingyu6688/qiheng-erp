package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WmsLabelsTest {
    private final CompanyAccess scope=new CompanyAccess(1,2,3,true,Set.of(),Set.of("label:print"));
    private final WmsCodes codes=mock(WmsCodes.class);
    private final WmsLabels labels=new WmsLabels(codes,"");

    @Test void labelsKeepConfiguredPrintDimensionsAndCopies() throws Exception {
        when(codes.label(scope,4L,"location",5L)).thenReturn(Map.of("code","L:A-01","name","Rack A"));
        var input=new WmsLabels.PrintInput(4L,"50x30","pdf",List.of(new WmsLabels.Item("location","5",2)));
        try(var pdf=Loader.loadPDF(labels.generate(scope,input).bytes())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(2);
            var dimensions=pdf.getPage(0).getMediaBox();
            assertThat(Math.round(dimensions.getWidth()*25.4/72)).isEqualTo(50);
            assertThat(Math.round(dimensions.getHeight()*25.4/72)).isEqualTo(30);
            assertThat(new PDFTextStripper().getText(pdf)).contains("L:A-01");
        }
    }

    @Test void printerInstructionsPermitBarcodesAndRejectControlSyntax() {
        when(codes.label(scope,4L,"location",5L)).thenReturn(Map.of("code","L:A-01","name","Rack A"));
        var item=new WmsLabels.Item("location","5",1);
        var zpl=new WmsLabels.PrintInput(4L,"70x40","zpl",List.of(item));
        var tspl=new WmsLabels.PrintInput(4L,"70x40","tspl",List.of(item));
        assertThat(new String(labels.generate(scope,zpl).bytes(),StandardCharsets.US_ASCII)).contains("^FDL:A-01^FS");
        assertThat(new String(labels.generate(scope,tspl).bytes(),StandardCharsets.US_ASCII)).contains("SIZE 70 mm,40 mm","\"L:A-01\"");
        when(codes.label(scope,4L,"location",5L)).thenReturn(Map.of("code","L:A^FS","name","Rack A"));
        assertThatThrownBy(() -> labels.generate(scope,zpl)).isInstanceOf(ApiException.class);
    }

    @Test void longBatchCodeRemainsScannableOnSmallPaper() throws Exception {
        String barcode="B:ERP-SKU-100|ABCDEFGHIJKLMNOPQRSTUVWXYZ-ABCDEFGHIJKLMNOPQRSTUVWXYZ-12345678901234567890";
        when(codes.label(scope,4L,"batch",5L)).thenReturn(Map.of("code",barcode,"name","Long batch"));
        var input=new WmsLabels.PrintInput(4L,"50x30","pdf",List.of(new WmsLabels.Item("batch","5",1)));
        try(var pdf=Loader.loadPDF(labels.generate(scope,input).bytes())) {
            var image=new PDFRenderer(pdf).renderImageWithDPI(0,300);
            int width=image.getWidth(),height=image.getHeight();
            int[] pixels=image.getRGB(0,0,width,height,null,0,width);
            var bitmap=new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(width,height,pixels)));
            assertThat(new QRCodeReader().decode(bitmap).getText()).isEqualTo(barcode);
        }
    }
}
