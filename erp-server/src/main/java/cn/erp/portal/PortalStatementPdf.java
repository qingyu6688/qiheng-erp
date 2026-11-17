package cn.erp.portal;

import cn.erp.shared.ApiException;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class PortalStatementPdf {
    private static final int ROWS_PER_PAGE=38;
    private static final float[] COLUMNS={43,117,310,465,620};
    private static final float[] WIDTHS={70,181,149,149,170};
    private final String fontFile;

    PortalStatementPdf(@Value("${ERP_PORTAL_PDF_FONT:}") String fontFile) {this.fontFile=fontFile;}

    byte[] render(PortalActor actor,PortalStatements.Statement statement) {
        try(var document=new PDDocument();var output=new ByteArrayOutputStream()) {
            boolean chinese=!fontFile.isBlank();
            PDFont font=chinese?PDType0Font.load(document,new File(fontFile)):new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            int pages=Math.max(1,(statement.items().size()+ROWS_PER_PAGE-1)/ROWS_PER_PAGE);
            for(int page=0;page<pages;page++) writePage(document,font,chinese,actor,statement,page,pages);
            document.save(output);
            return output.toByteArray();
        } catch(IOException|IllegalArgumentException failed) {
            throw new ApiException(503,"PORTAL_PDF_UNAVAILABLE","对账 PDF 暂不可用，请稍后重试");
        }
    }

    private void writePage(PDDocument document,PDFont font,boolean chinese,PortalActor actor,
                           PortalStatements.Statement statement,int index,int pages) throws IOException {
        var page=new PDPage(new PDRectangle(PDRectangle.A4.getHeight(),PDRectangle.A4.getWidth()));
        document.addPage(page);
        try(var out=new PDPageContentStream(document,page)) {
            text(out,font,18,43,551,chinese?"客户应收对账单":"Customer receivable statement",700);
            String holder=chinese?actor.companyName()+"  /  "+actor.customerName():"Company "+actor.companyId()+"  /  Customer "+actor.partnerId();
            text(out,font,10,43,530,holder,700);
            text(out,font,10,43,515,statement.dateFrom()+" - "+statement.dateTo()+"    "+(index+1)+" / "+pages,700);
            var totals=statement.summary();
            text(out,font,10,43,500,(chinese?"期初 ":"Opening ")+totals.openingBalance()+
                    (chinese?"    期末 ":"    Closing ")+totals.closingBalance(),750);
            line(out,487);
            row(out,font,473,chinese?List.of("日期","单号","应收增加","应收减少","余额"):
                    List.of("Date","Document","Debit","Credit","Balance"));
            int from=index*ROWS_PER_PAGE,to=Math.min(from+ROWS_PER_PAGE,statement.items().size());
            float y=458;
            for(var item:statement.items().subList(from,to)) {
                row(out,font,y,List.of(item.businessDate(),item.sourceNo(),item.debitAmount(),item.creditAmount(),item.runningBalance()));
                y-=11;
            }
            line(out,y+2);
            text(out,font,8,43,25,statement.digest(),750);
        }
    }

    private void row(PDPageContentStream out,PDFont font,float y,List<String> values) throws IOException {
        for(int cell=0;cell<values.size();cell++) text(out,font,cell<2?9:8,COLUMNS[cell],y,values.get(cell),WIDTHS[cell]);
    }

    private void line(PDPageContentStream out,float y) throws IOException {
        out.moveTo(42,y);out.lineTo(795,y);out.stroke();
    }

    private void text(PDPageContentStream out,PDFont font,int size,float x,float y,String value,float width) throws IOException {
        String safe=fit(font,value,width*1000/size);
        out.beginText();out.setFont(font,size);out.newLineAtOffset(x,y);out.showText(safe);out.endText();
    }

    private String fit(PDFont font,String text,float width) throws IOException {
        var value=new StringBuilder();
        float used=0;
        for(int point:text.codePoints().toArray()) {
            if(Character.isISOControl(point)) continue;
            String glyph=Character.toString(point);
            float length;
            try {length=font.getStringWidth(glyph);}
            catch(IllegalArgumentException unsupported) {glyph="?";length=font.getStringWidth(glyph);}
            if(used+length>width) break;
            value.append(glyph);used+=length;
        }
        return value.toString();
    }
}
