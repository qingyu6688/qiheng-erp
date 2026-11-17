package cn.erp.printdesign;

import cn.erp.identity.CompanyAccess;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import static cn.erp.printdesign.PrintFields.invalid;
import static cn.erp.printdesign.PrintLayout.*;

@Component
class PrintRendering {
    static final int MAX_PAGES=240;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));
    private static final String EMPTY="—";
    private static final Style CELL=new Style(9,"left",false);
    private final PrintFields fields;

    PrintRendering(PrintFields fields) {this.fields=fields;}

    record Ink(String type,double x,double y,double w,double h,List<String> lines,String image,int fontSize,String align,boolean bold) {}
    record Page(List<Ink> items) {}
    record Model(double width,double height,String background,List<Page> pages,Map<String,String> assets) {}

    Model render(CompanyAccess actor,String type,Layout layout,Map<String,Object> data,boolean limitToView) {
        var allowed=fields.fields(actor,type,true);
        var dimensions=validateLayout(layout,allowed,limitToView);
        var dictionary=new HashMap<String,PrintFields.Field>();
        for(var field:allowed) dictionary.put(field.id(),field);
        var ctx=new Context(layout,fields.document(type),dictionary,data,new java.util.LinkedHashMap<>(),new HashMap<>());
        Map<?,?> header=data.get("document") instanceof Map<?,?> document?document:Map.of();
        var lines=header.get("lines") instanceof List<?> list?list.stream().filter(Map.class::isInstance).map(Map.class::cast).toList():List.<Map>of();
        int rows=layout.table()==null?1:layout.table().rowsPerPage();
        int pages=layout.table()==null?1:Math.max(1,(lines.size()+rows-1)/rows);
        if(pages>MAX_PAGES) throw invalid("该模板页数超过限制，请增加每页明细行数");
        var result=new ArrayList<Page>();
        for(int index=0;index<pages;index++) {
            var items=new ArrayList<Ink>();
            for(var el:layout.header()) element(items,ctx,header,el,index,pages);
            if(layout.table()!=null) table(items,ctx,lines,index,pages);
            for(var el:layout.footer()) element(items,ctx,header,el,index,pages);
            result.add(new Page(List.copyOf(items)));
        }
        var assets=new java.util.LinkedHashMap<String,String>();
        ctx.assets().forEach((value,key)->assets.put(key,value));
        return new Model(dimensions.width(),dimensions.height(),layout.background(),List.copyOf(result),Map.copyOf(assets));
    }

    private record Context(Layout layout,PrintFields.Document type,Map<String,PrintFields.Field> fields,Map<String,Object> data,
                           Map<String,String> assets,Map<String,String> codes) {
        Margins margins(){return layout.paper().margins();}
        boolean english(){return layout.locale().equals("en-US");}
        String value(String field,Map<?,?> row,int number) {return PrintFields.value(fields.get(field),data,row,number,layout.locale(),type);}
        String code(String value,boolean qr) {return codes.computeIfAbsent((qr?"qr:":"code128:")+value,ignored->PrintAssets.barcode(value,qr));}
    }

    private void element(List<Ink> items,Context ctx,Map<?,?> header,Element el,int index,int total) {
        double x=el.x()+ctx.margins().left(), y=el.y()+ctx.margins().top();
        String value=el.field()==null?"":ctx.value(el.field(),header,0);
        switch(el.type()) {
            case "text" -> items.add(text(x,y,el.w(),el.h(),localized(ctx,el.text(),el.textEn()),el.style()));
            case "field" -> {
                String label=localized(ctx,el.label(),el.labelEn());
                String formatted=value.isBlank()?"":format(value,el.format(),ctx.fields().get(el.field()));
                items.add(text(x,y,el.w(),el.h(),formatted.isBlank()?"":label+formatted,el.style()));
            }
            case "line","rectangle" -> items.add(new Ink(el.type(),x,y,el.w(),el.h(),List.of(),null,0,"left",false));
            case "pageNumber" -> items.add(text(x,y,el.w(),el.h(),ctx.english()?"Page "+(index+1)+" / "+total:"第 "+(index+1)+" / "+total+" 页",el.style()));
            case "image" -> image(items,x,y,el,ctx.layout().images()==null?null:ctx.layout().images().get(el.source()),ctx.assets());
            case "barcode","qrcode" -> image(items,x,y,el,ctx.code(value,el.type().equals("qrcode") || "qr".equals(el.symbology())),ctx.assets());
            default -> throw invalid("模板包含不支持的元素");
        }
    }

    private static void image(List<Ink> items,double x,double y,Element el,String image,Map<String,String> assets) {
        if(image!=null) {
            var key=assets.computeIfAbsent(image,ignored->"asset-"+assets.size());
            items.add(new Ink("image",x,y,el.w(),el.h(),List.of(),key,0,"left",false));
        }
    }

    private void table(List<Ink> items,Context ctx,List<Map> lines,int page,int pages) {
        var table=ctx.layout().table();
        double x=table.x()+ctx.margins().left(), y=table.y()+ctx.margins().top();
        if(page==0 || !Boolean.FALSE.equals(table.repeatHeader())) {
            var heads=table.columns().stream().map(col->localized(ctx,col.title(),col.titleEn())).toList();
            tableRow(items,heads,table,x,y,true);y+=table.rowHeight();
        }
        int from=page*table.rowsPerPage(), to=Math.min(lines.size(),from+table.rowsPerPage());
        for(int index=from;index<to;index++) {
            var row=lines.get(index);int number=index+1;
            var rendered=table.columns().stream().map(col->format(ctx.value(col.field(),row,number),col.format(),ctx.fields().get(col.field()))).toList();
            tableRow(items,rendered,table,x,y,false);y+=table.rowHeight();
        }
        if(page==pages-1 && Boolean.TRUE.equals(table.showTotalRow())) {
            var totals=new ArrayList<String>();
            boolean first=true;
            for(var col:table.columns()) {
                var field=ctx.fields().get(col.field());
                boolean numeric=field!=null && List.of("money","quantity").contains(field.kind()) && Boolean.TRUE.equals(col.total());
                if(numeric) {
                    var sum=lines.stream().map(row->ctx.value(col.field(),row,0)).filter(PrintRendering::decimal).map(BigDecimal::new).reduce(BigDecimal.ZERO,BigDecimal::add);
                    totals.add(format(sum.toPlainString(),col.format(),field));
                } else totals.add(first?(ctx.english()?"Total":"合计"):"");
                first=false;
            }
            tableRow(items,totals,table,x,y,true);
        }
    }

    private static void tableRow(List<Ink> items,List<String> values,Table table,double x,double y,boolean bold) {
        for(int index=0;index<table.columns().size();index++) {
            double width=table.columns().get(index).w();
            items.add(new Ink("rectangle",x,y,width,table.rowHeight(),List.of(),null,0,"left",false));
            items.add(text(x+0.8,y+0.5,width-1.6,Math.max(2,table.rowHeight()-1),values.get(index),bold?new Style(9,"left",true):CELL));
            x+=width;
        }
    }

    private static Ink text(double x,double y,double w,double h,String text,Style style) {
        int font=style==null || style.fontSize()==null?10:style.fontSize();
        var align=style==null || style.align()==null?"left":style.align();
        var value=text==null?"":text;
        return new Ink("text",x,y,w,h,wrapped(value,w,h,font),null,font,align,style!=null && Boolean.TRUE.equals(style.bold()));
    }

    private static List<String> wrapped(String text,double width,double height,int font) {
        var lines=new ArrayList<String>();var current=new StringBuilder();
        double limit=width*72/25.4/font,lineWidth=0;
        int lineLimit=Math.max(1,(int)(height*72/25.4/(font*1.28)));
        for(int offset=0;offset<text.length() && lines.size()<lineLimit;) {
            int code=text.codePointAt(offset);offset+=Character.charCount(code);
            boolean newline=code=='\n';
            if(Character.isISOControl(code) && !newline && code!='\t') continue;
            if(code=='\t') code=' ';
            double advance=code>255?1:Character.isWhitespace(code)?0.35:0.59;
            if(newline || current.length()>0 && lineWidth+advance>limit) {lines.add(current.toString());current.setLength(0);lineWidth=0;}
            if(!newline && lines.size()<lineLimit) {current.appendCodePoint(code);lineWidth+=advance;}
        }
        if(lines.size()<lineLimit) lines.add(current.toString());
        return List.copyOf(lines);
    }

    private static String localized(Context ctx,String chinese,String english) {
        if(ctx.english() && english!=null && !english.isBlank()) return english;
        return chinese==null?"":chinese;
    }

    static String format(String value,String mode,PrintFields.Field field) {
        if(value==null || value.isBlank() || field==null) return "";
        var format=mode==null?field.kind():mode;
        return switch(format) {
            case "money","quantity" -> {
                if(!decimal(value)) yield EMPTY;
                var parts=value.split("\\.",-1);String whole=parts[0].replaceFirst("^-","");
                var grouped=whole.replaceAll("\\B(?=(\\d{3})+(?!\\d))",",");
                var fraction=parts.length==2?parts[1].replaceFirst("0+$",""):"";
                if(format.equals("money") && fraction.length()<2) fraction=(fraction+"00").substring(0,2);
                yield (value.startsWith("-")?"−":"")+grouped+(fraction.isEmpty()?"":"."+fraction);
            }
            case "datetime" -> {
                try {yield TIME.format(Instant.parse(value));}
                catch(DateTimeParseException error){yield EMPTY;}
            }
            default -> value;
        };
    }

    private static boolean decimal(String value) {return value.matches("-?[0-9]{1,24}(?:\\.[0-9]{1,12})?");}
}
