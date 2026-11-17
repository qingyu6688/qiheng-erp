package cn.erp.printdesign;

import cn.erp.shared.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PrintPdf {
    private static final double POINTS=72d/25.4d;
    private final String configuredFont;

    PrintPdf(@Value("${ERP_PRINT_PDF_FONT:${ERP_PORTAL_PDF_FONT:}}") String configuredFont) {this.configuredFont=configuredFont;}

    record Copy(PrintRendering.Model model,int count) {}

    byte[] write(List<Copy> copies) {
        long pages=copies.stream().mapToLong(copy->(long)copy.model().pages().size()*copy.count()).sum();
        if(pages<1 || pages>PrintRendering.MAX_PAGES) throw PrintFields.invalid("批量打印共有过多页，请分批提交");
        try(var document=new PDDocument();var buffer=new ByteArrayOutputStream()) {
            var faces=fonts(document);
            var images=new HashMap<String,PDImageXObject>();
            for(var copy:copies) for(int count=0;count<copy.count();count++) for(var modelPage:copy.model().pages()) {
                var page=new PDPage(new PDRectangle(pt(copy.model().width()),pt(copy.model().height())));
                document.addPage(page);
                try(var content=new PDPageContentStream(document,page)) {
                    content.setLineWidth(0.55f);
                    for(var item:modelPage.items()) draw(document,content,faces,images,item,copy.model().assets(),page.getMediaBox().getHeight());
                }
            }
            document.save(buffer);
            return buffer.toByteArray();
        } catch (IOException error) {
            throw new ApiException(503,"PRINT_PDF_UNAVAILABLE","打印 PDF 生成失败，请检查服务端打印字体和图片配置");
        }
    }

    private record Faces(PDFont regular,PDFont bold,boolean same) {}

    private Faces fonts(PDDocument document) throws IOException {
        var paths=new ArrayList<Path>();
        if(!configuredFont.isBlank()) paths.add(Path.of(configuredFont));
        var windows=System.getenv("WINDIR");
        if(windows!=null && !windows.isBlank()) paths.add(Path.of(windows,"Fonts","simhei.ttf"));
        paths.add(Path.of("/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf"));
        paths.add(Path.of("/usr/share/fonts/truetype/arphic/uming.ttf"));
        var font=paths.stream().filter(Files::isRegularFile).findFirst();
        if(font.isPresent()) {var china=PDType0Font.load(document,font.get().toFile());return new Faces(china,china,true);}
        return new Faces(new PDType1Font(Standard14Fonts.FontName.HELVETICA),new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD),false);
    }

    private void draw(PDDocument document,PDPageContentStream content,Faces fonts,HashMap<String,PDImageXObject> images,
                      PrintRendering.Ink item,java.util.Map<String,String> assets,float paperHeight) throws IOException {
        float x=pt(item.x()), y=paperHeight-pt(item.y()), width=pt(item.w()), height=pt(item.h());
        switch(item.type()) {
            case "rectangle" -> {content.addRect(x,y-height,width,height);content.stroke();}
            case "line" -> {content.moveTo(x,y-height/2);content.lineTo(x+width,y-height/2);content.stroke();}
            case "image" -> {
                var source=assets.get(item.image());
                var image=images.get(source);
                if(image==null) {
                    image=LosslessFactory.createFromImage(document,PrintAssets.image(source));
                    images.put(source,image);
                }
                float scale=Math.min(width/image.getWidth(),height/image.getHeight());
                float renderedWidth=image.getWidth()*scale,renderedHeight=image.getHeight()*scale;
                content.drawImage(image,x+(width-renderedWidth)/2,y-(height+renderedHeight)/2,renderedWidth,renderedHeight);
            }
            case "text" -> drawText(content,fonts,item,x,y,width,height);
            default -> throw new IllegalStateException("不支持的打印图元");
        }
    }

    private void drawText(PDPageContentStream content,Faces fonts,PrintRendering.Ink ink,float x,float y,float width,float height) throws IOException {
        var font=ink.bold()?fonts.bold():fonts.regular();
        float size=ink.fontSize(), leading=size*1.28f;
        content.saveGraphicsState();
        content.addRect(x,y-height,width,height);
        content.clip();
        for(int index=0;index<ink.lines().size();index++) {
            var text=ink.lines().get(index);
            if(text.isBlank()) continue;
            float textWidth;
            try {textWidth=font.getStringWidth(text)/1000*size;}
            catch(IllegalArgumentException unsupported) {
                throw new ApiException(422,"PRINT_PDF_FONT_REQUIRED","当前打印字体缺少所需字符，请为服务端配置支持该语言的打印字体");
            }
            float offset=switch(ink.align()){case "center"->Math.max(0,(width-textWidth)/2);case "right"->Math.max(0,width-textWidth);default->0;};
            content.beginText();content.setFont(font,size);content.newLineAtOffset(x+offset,y-size-index*leading);content.showText(text);content.endText();
            if(ink.bold() && fonts.same()) {
                content.beginText();content.setFont(font,size);content.newLineAtOffset(x+offset+0.18f,y-size-index*leading);content.showText(text);content.endText();
            }
        }
        content.restoreGraphicsState();
    }

    private static float pt(double mm){return (float)(mm*POINTS);}
}
