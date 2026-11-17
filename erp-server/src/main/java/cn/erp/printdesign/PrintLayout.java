package cn.erp.printdesign;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static cn.erp.printdesign.PrintFields.invalid;

final class PrintLayout {
    private static final Set<String> TYPES=Set.of("text","field","barcode","qrcode","image","line","rectangle","pageNumber");
    private static final Set<String> FORMATS=Set.of("text","money","quantity","datetime");
    private static final Set<String> LANGUAGES=Set.of("zh-CN","en-US");
    private static final Set<String> ALIGNMENTS=Set.of("left","center","right");

    record Margins(double top,double right,double bottom,double left) {}
    record Paper(String kind,String orientation,Double width,Double height,Margins margins) {}
    record Dimension(double width,double height,double contentWidth,double contentHeight) {}
    record Style(Integer fontSize,String align,Boolean bold) {}
    record Element(String type,double x,double y,double w,double h,String text,String textEn,String label,
                   String labelEn,String field,String format,String symbology,String source,Style style) {}
    record Column(String field,String title,String titleEn,double w,String format,Boolean total) {}
    record Table(double x,double y,double rowHeight,int rowsPerPage,Boolean repeatHeader,Boolean showTotalRow,List<Column> columns) {}
    record Layout(Paper paper,String locale,List<Element> header,Table table,List<Element> footer,
                  Map<String,String> images,String background) {}
    record Input(String documentType,String name,Boolean shared,String status,Boolean isDefault,Long version,Layout layout) {}
    record Preview(String documentType,Long documentId,Layout layout) {}
    record Log(Long templateId,Long version,Integer copies) {}

    private PrintLayout() {}

    static Dimension validateLayout(Layout layout,List<PrintFields.Field> fields,boolean authoring) {
        if(layout==null || !present(LANGUAGES,layout.locale())) throw invalid("请选择模板语言和纸张");
        Dimension dimensions=dimensions(layout.paper());
        if(layout.header()==null || layout.footer()==null || layout.header().size()+layout.footer().size()>80)
            throw invalid("模板元素数量不能超过 80");
        var definitions=new HashMap<String,PrintFields.Field>();
        fields.forEach(field->definitions.put(field.id(),field));
        validateAssets(layout);
        for(var element:layout.header()) validateElement(element,dimensions,definitions,layout,authoring);
        for(var element:layout.footer()) validateElement(element,dimensions,definitions,layout,authoring);
        validateTable(layout,dimensions,definitions,authoring);
        return dimensions;
    }

    static Dimension dimensions(Paper paper) {
        if(paper==null || !present(Set.of("portrait","landscape"),paper.orientation()) || paper.kind()==null || paper.margins()==null)
            throw invalid("纸张方向或页边距无效");
        double width,height;
        switch(paper.kind()) {
            case "A4" -> {width=210;height=297;}
            case "A5" -> {width=148;height=210;}
            case "third" -> {width=241;height=93;}
            case "custom" -> {
                if(paper.width()==null || paper.height()==null || !between(paper.width(),30,500) || !between(paper.height(),30,500))
                    throw invalid("自定义纸张宽高须为 30 至 500 毫米");
                width=paper.width();height=paper.height();
            }
            default -> throw invalid("不支持的纸型");
        }
        if(paper.orientation().equals("landscape")) {double previous=width;width=height;height=previous;}
        var margin=paper.margins();
        if(!between(margin.left(),0,50) || !between(margin.top(),0,50) || !between(margin.right(),0,50) || !between(margin.bottom(),0,50)
                || width-margin.left()-margin.right()<20 || height-margin.top()-margin.bottom()<20)
            throw invalid("页边距过大或超出允许范围");
        return new Dimension(width,height,width-margin.left()-margin.right(),height-margin.top()-margin.bottom());
    }

    private static void validateElement(Element el,Dimension dimension,Map<String,PrintFields.Field> fields,Layout layout,boolean authoring) {
        if(el==null || !present(TYPES,el.type()) || !between(el.x(),0,dimension.contentWidth()) || !between(el.y(),0,dimension.contentHeight())
                || !between(el.w(),0.5,dimension.contentWidth()-el.x()+0.0001) || !between(el.h(),0.5,dimension.contentHeight()-el.y()+0.0001))
            throw invalid("元素超出纸张可用区域或尺寸无效");
        validText(el.text(),1000);validText(el.textEn(),1000);validText(el.label(),100);validText(el.labelEn(),100);
        format(el.format());
        if(el.style()!=null && (el.style().fontSize()!=null && !between(el.style().fontSize(),6,40)
                || el.style().align()!=null && !present(ALIGNMENTS,el.style().align()))) throw invalid("元素字号或对齐方式无效");
        switch(el.type()) {
            case "field","barcode","qrcode" -> validateField(el.field(),false,fields,authoring);
            case "image" -> {
                if(!present(Set.of("companyLogo","seal"),el.source())) throw invalid("不支持的图片来源");
            }
            default -> { }
        }
        if(el.type().equals("barcode") && !(Set.of("code128","qr").contains(el.symbology()==null?"code128":el.symbology())))
            throw invalid("不支持的条码类型");
    }

    private static void validateTable(Layout layout,Dimension dimension,Map<String,PrintFields.Field> fields,boolean authoring) {
        var table=layout.table();
        if(table==null) return;
        if(table.columns()==null || table.columns().isEmpty() || table.columns().size()>16 || !between(table.rowHeight(),3,30)
                || table.rowsPerPage()<1 || table.rowsPerPage()>80 || !between(table.x(),0,dimension.contentWidth())
                || !between(table.y(),0,dimension.contentHeight())) throw invalid("表格位置、行高、页行数或列数无效");
        double width=0;
        for(var column:table.columns()) {
            if(column==null || !between(column.w(),5,dimension.contentWidth())) throw invalid("表格列宽无效");
            validateField(column.field(),true,fields,authoring);validText(column.title(),80);validText(column.titleEn(),80);format(column.format());
            width+=column.w();
        }
        int rows=table.rowsPerPage()+1+(Boolean.TRUE.equals(table.showTotalRow())?1:0);
        double max=layout.footer().stream().mapToDouble(Element::y).min().orElse(dimension.contentHeight());
        if(table.x()+width>dimension.contentWidth()+0.0001 || table.y()+table.rowHeight()*rows>max+0.0001)
            throw invalid("表格超出纸张可用区域或与页尾重叠，请缩小表格");
    }

    private static void validateAssets(Layout layout) {
        var images=layout.images();
        if(images!=null) {
            if(images.size()>2 || !Set.of("companyLogo","seal").containsAll(images.keySet())) throw invalid("仅支持公司标识和签章图片");
            for(var data:images.values()) PrintAssets.image(data);
        }
        if(layout.background()!=null) PrintAssets.image(layout.background());
    }

    private static void validateField(String key,boolean line,Map<String,PrintFields.Field> list,boolean authoring) {
        if(key==null || !key.matches("[a-zA-Z][a-zA-Z0-9_.]{0,63}")) throw invalid("模板字段编码无效");
        var definition=list.get(key);
        if(authoring && (definition==null || definition.line()!=line)) throw invalid("模板字段不在当前单据的可用字段中");
    }

    private static void format(String format) {if(format!=null && !present(FORMATS,format)) throw invalid("字段显示格式无效");}
    private static boolean present(Set<String> values,String value) {return value!=null && values.contains(value);}
    private static boolean between(double x,double min,double max) {return Double.isFinite(x) && x>=min && x<=max;}
    private static void validText(String value,int max) {
        if(value!=null && (value.length()>max || value.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')))
            throw invalid("模板文字过长或包含无效字符");
    }
}
