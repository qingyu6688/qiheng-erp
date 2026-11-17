package cn.erp.wms;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.Code128Writer;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
class WmsLabels {
    private static final int MAX_LABELS=100;
    private static final float POINTS_PER_MM=72f/25.4f;
    private static final Map<String,Paper> SIZES=Map.of("50x30",new Paper(50,30),"70x40",new Paper(70,40),"100x100",new Paper(100,100));
    private final WmsCodes codes;
    private final String fontFile;

    WmsLabels(WmsCodes codes,@Value("${ERP_PORTAL_PDF_FONT:}") String fontFile) {
        this.codes=codes;
        this.fontFile=fontFile;
    }

    record Item(String type,String id,Integer copies) {}
    record PrintInput(Long warehouseId,String size,String format,List<Item> items) {}
    record FileData(byte[] bytes,String format) {}
    private record Paper(int width,int height) {}
    private record Label(String code,String name,int copies) {}

    FileData generate(CompanyAccess actor,PrintInput input) {
        var size=SIZES.get(Objects.toString(input.size(),""));
        if (size==null || input.items()==null || input.items().isEmpty() || input.items().size()>MAX_LABELS)
            throw invalid("请选择可用的标签尺寸，每次提交 1 到 100 个标签对象");
        var batch=resolve(actor,input);
        String format=Objects.toString(input.format(),"pdf").toLowerCase(Locale.ROOT);
        return new FileData(switch (format) {
            case "pdf" -> pdf(size,batch);
            case "zpl", "tspl" -> instructions(size,batch,format);
            default -> throw invalid("标签仅支持 PDF、ZPL 或 TSPL");
        },format);
    }

    private List<Label> resolve(CompanyAccess actor,PrintInput input) {
        int total=0;
        var labels=new ArrayList<Label>();
        for (var item:input.items()) {
            if (item==null || item.id()==null || !item.id().matches("[1-9][0-9]{0,18}"))
                throw invalid("标签对象编号无效");
            long id;
            try { id=Long.parseLong(item.id()); }
            catch (NumberFormatException tooLarge) { throw invalid("标签对象编号无效"); }
            int copies=item.copies()==null ? 1 : item.copies();
            if (copies<=0 || copies>MAX_LABELS-total) throw invalid("每次最多打印 100 张标签");
            total+=copies;
            var data=codes.label(actor,input.warehouseId(),item.type(),id);
            labels.add(new Label(data.get("code").toString(),data.get("name").toString(),copies));
        }
        return labels;
    }

    private byte[] pdf(Paper paper,List<Label> labels) {
        try(var doc=new PDDocument();var result=new ByteArrayOutputStream()) {
            PDFont font=fontFile.isBlank() ? new PDType1Font(Standard14Fonts.FontName.HELVETICA)
                    : PDType0Font.load(doc,new File(fontFile));
            for (var label:labels) {
                var bars=plainCode128(label.code()) ? new Code128Writer().encode(label.code(),BarcodeFormat.CODE_128,1,1) : null;
                // 长编码换用二维码，避免缩窄条码导致小纸张标签无法扫描。
                boolean code128=bars!=null && bars.getWidth()*0.52f<=paper.width()*POINTS_PER_MM-16;
                var bits=code128 ? bars : new QRCodeWriter().encode(label.code(),BarcodeFormat.QR_CODE,1,1,
                        Map.of(EncodeHintType.CHARACTER_SET,"UTF-8"));
                for(int i=0;i<label.copies();i++) drawPage(doc,paper,font,label,bits,code128);
            }
            doc.save(result);
            return result.toByteArray();
        } catch(WriterException | IOException | IllegalArgumentException e) {
            throw new ApiException(503,"WMS_LABEL_UNAVAILABLE","标签生成失败，请检查标签资料和字体配置");
        }
    }

    private void drawPage(PDDocument doc,Paper paper,PDFont font,Label label,BitMatrix bits,boolean bars) throws IOException {
        float width=paper.width()*POINTS_PER_MM,height=paper.height()*POINTS_PER_MM;
        var page=new PDPage(new PDRectangle(width,height));
        doc.addPage(page);
        try(var out=new PDPageContentStream(doc,page)) {
            text(out,font,fit(font,label.name(),width-16),8,height-13);
            text(out,font,fit(font,label.code(),width-16),8,6);
            float innerHeight=height-34, available=width-16;
            float unitX=bars ? available/bits.getWidth() : Math.min(available/bits.getWidth(),innerHeight/bits.getHeight());
            float unitY=bars ? innerHeight : unitX;
            float fromX=(width-bits.getWidth()*unitX)/2;
            for(int y=0;y<bits.getHeight();y++) for(int x=0;x<bits.getWidth();x++) if(bits.get(x,y))
                out.addRect(fromX+x*unitX,17+(bits.getHeight()-y-1)*unitY,unitX,unitY);
            out.fill();
        }
    }

    private String fit(PDFont font,String value,float maxWidth) throws IOException {
        var output=new StringBuilder();
        float length=0;
        for(int cp:value.codePoints().toArray()) {
            if (Character.isISOControl(cp)) continue;
            String glyph=Character.toString(cp);
            float advance;
            try { advance=font.getStringWidth(glyph)*0.008f; }
            catch(IllegalArgumentException unavailable) { glyph="?";advance=font.getStringWidth(glyph)*0.008f; }
            if (length+advance>maxWidth) break;
            output.append(glyph);
            length+=advance;
        }
        return output.toString();
    }

    private void text(PDPageContentStream out,PDFont font,String value,float x,float y) throws IOException {
        out.beginText();
        out.setFont(font,8);
        out.newLineAtOffset(x,y);
        out.showText(value);
        out.endText();
    }

    private byte[] instructions(Paper size,List<Label> labels,String format) {
        var printer=new StringBuilder();
        for(var label:labels) {
            if (!label.code().matches("[A-Za-z0-9._:/|\\-]{1,80}")
                    || new Code128Writer().encode(label.code(),BarcodeFormat.CODE_128,1,1).getWidth()*2>size.width()*8-48)
                throw invalid("该编码请改用较大的纸张或 PDF 标签");
            if ("zpl".equals(format)) {
                printer.append("^XA\n^PW").append(size.width()*8).append("\n^LL").append(size.height()*8)
                        .append("\n^FO24,28^BY2^BCN,").append(size.height()*4).append(",Y,N,N^FD").append(label.code())
                        .append("^FS\n^PQ").append(label.copies()).append("\n^XZ\n");
            } else {
                printer.append("SIZE ").append(size.width()).append(" mm,").append(size.height())
                        .append(" mm\nCLS\nBARCODE 24,28,\"128\",").append(size.height()*4)
                        .append(",1,0,2,2,\"").append(label.code()).append("\"\nPRINT ")
                        .append(label.copies()).append(",1\n");
            }
        }
        return printer.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private boolean plainCode128(String code) {
        return code.length()<=80 && code.chars().allMatch(ch -> ch>=32 && ch<127);
    }

    private static ApiException invalid(String message) { return new ApiException(422,"VALIDATION_FAILED",message); }
}
