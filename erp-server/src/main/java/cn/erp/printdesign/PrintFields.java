package cn.erp.printdesign;

import cn.erp.customfield.CustomFields;
import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
class PrintFields {
    record Document(String id,String label,String english) {}
    record Field(String id,String label,String english,String kind,String permission,boolean line) {}
    static final String COST="cost:view";
    static final String BANK="cash_account_no:view";
    private static final List<Document> DOCUMENTS=List.of(
            new Document("sales_order","销售订单","Sales order"),new Document("shipment","销售发货单","Sales shipment"),
            new Document("receipt","销售收款单","Customer receipt"),new Document("sales_return","销售退货单","Sales return"),
            new Document("refund_request","退款单","Refund"),new Document("purchase_requisition","采购请购单","Purchase requisition"),
            new Document("purchase_order","采购订单","Purchase order"),new Document("goods_receipt","采购收货单","Goods receipt"),
            new Document("purchase_return","采购退货单","Purchase return"),new Document("payment","付款单","Payment"),
            new Document("account_transfer","账户转账单","Account transfer"),new Document("stock_adjustment","库存调整单","Stock adjustment"),
            new Document("opening_entry","期初单","Opening balance"),new Document("stock_count","盘点单","Stock count"),
            new Document("stock_transfer","调拨单","Stock transfer"),new Document("document_reversal","冲销单","Reversal"),
            new Document("invoice_registration","开票登记单","Invoice registration"));
    private static final Set<String> CASH=Set.of("receipt","refund_request","payment","account_transfer");
    private static final Set<String> NO_LINES=Set.of("receipt","refund_request","payment","account_transfer","document_reversal");
    private static final Set<String> COSTED=Set.of("shipment","goods_receipt","purchase_return","sales_return","stock_transfer","stock_adjustment","stock_count","opening_entry");
    private static final List<Field> HEAD=List.of(
            field("documentNo","单号","Document no.","text"),field("businessDate","业务日期","Date","text"),
            field("documentTitle","单据类型","Document type","text"),field("status","状态","Status","text"),
            field("companyName","公司名称","Company","text"),field("companyAddress","公司地址","Company address","text"),
            field("companyPhone","公司电话","Company phone","text"),field("partnerName","往来单位","Partner","text"),
            field("warehouseName","仓库","Warehouse","text"),field("remark","备注","Remarks","text"),
            field("createdByName","制单人","Prepared by","text"),field("approvedByName","审批人","Approved by","text"),
            field("printedAt","打印时间","Printed at","datetime"),field("deliveryAddress","送货地址","Delivery address","text"),
            field("amount","金额","Amount","money"),field("totalAmount","合计","Total","money"),
            field("taxAmount","税额","Tax amount","money"),field("untaxedAmount","未税金额","Net amount","money"));
    private static final List<Field> LINE=List.of(
            line("number","行号","No.","text"),line("skuCode","商品编码","SKU","text"),line("skuName","商品名称","Item","text"),
            line("unitName","单位","Unit","text"),line("warehouseName","仓库","Warehouse","text"),
            line("qty","数量","Quantity","quantity"),line("baseQty","基本数量","Base quantity","quantity"),
            line("unitPrice","单价","Unit price","money"),line("amount","金额","Amount","money"),
            line("taxAmount","税额","Tax amount","money"),line("untaxedAmount","未税金额","Net amount","money"),
            line("batchNo","批次","Lot","text"),line("remark","备注","Remarks","text"));

    private final DocumentAccess documents;
    private final CustomFields custom;

    PrintFields(DocumentAccess documents,CustomFields custom) {this.documents=documents;this.custom=custom;}

    List<Document> documents(CompanyAccess actor) {
        return DOCUMENTS.stream().filter(type->canPrint(actor,type.id())).toList();
    }

    Document document(String type) {
        return DOCUMENTS.stream().filter(row->row.id().equals(type)).findFirst().orElseThrow(()->invalid("不支持的模板单据类型"));
    }

    void requirePrint(CompanyAccess actor,String type) {
        document(type);
        DocumentAccess.require(actor,documents.permission(type,"view"));
        DocumentAccess.require(actor,documents.permission(type,"print"));
    }

    private boolean canPrint(CompanyAccess actor,String type) {
        return actor.hasPermission(documents.permission(type,"view")) && actor.hasPermission(documents.permission(type,"print"));
    }

    List<Field> fields(CompanyAccess actor,String type,boolean currentOnly) {
        requirePrint(actor,type);
        var list=new ArrayList<>(HEAD);
        if(!NO_LINES.contains(type)) list.addAll(LINE);
        if(COSTED.contains(type)) {
            list.add(new Field("costAmount","成本金额","Cost amount","money",COST,false));
            list.add(new Field("lines.costAmount","成本金额","Cost amount","money",COST,true));
            list.add(new Field("lines.unitCost","单位成本","Unit cost","money",COST,true));
        }
        if(CASH.contains(type)) {
            list.add(field("cashAccountName","资金账户","Cash account","text"));
            list.add(new Field("accountNo","银行账号","Bank account no.","text",BANK,false));
            if(type.equals("account_transfer")) {
                list.add(new Field("fromAccountNo","转出账号","From account no.","text",BANK,false));
                list.add(new Field("toAccountNo","转入账号","To account no.","text",BANK,false));
            }
        }
        if(Set.of("sales_order","purchase_order").contains(type)) {
            for(var customField:custom.fields(actor.tenantId(),type)) if(customField.printVisible()) list.add(custom(customField,false));
            if(type.equals("sales_order")) for(var field:custom.fields(actor.tenantId(),"sales_order_line"))
                if(field.printVisible()) list.add(custom(field,true));
        }
        return currentOnly?list.stream().filter(f->allows(actor,f)).toList():List.copyOf(list);
    }

    private static Field custom(CustomFields.Field field,boolean line) {
        return new Field((line?"lines.":"")+"custom."+field.fieldKey(),field.label(),field.label(),"text",null,line);
    }

    static boolean allows(CompanyAccess actor,Field field) {
        return field.permission()==null || actor.hasPermission(field.permission())
                && (!BANK.equals(field.permission()) || actor.hasPermission("cash_account:view"));
    }

    static String value(Field field,Map<?,?> response,Map<?,?> row,int number,String language,Document document) {
        if(field==null) return "";
        String key=field.line()?field.id().substring(6):field.id();
        Object raw=switch(key) {
            case "documentNo" -> first(row.get("documentNo"),row.get("invoiceNo"));
            case "documentTitle" -> language.equals("en-US")?document.english():document.label();
            case "businessDate" -> first(row.get("businessDate"),row.get("invoiceDate"));
            case "companyName" -> child(response,"company","name");
            case "companyAddress" -> child(response,"company","address");
            case "companyPhone" -> child(response,"company","phone");
            case "printedAt" -> response.get("printedAt");
            case "partnerName" -> first(row.get("partnerName"),row.get("customerName"),row.get("supplierName"),child(row,"partner","name"));
            case "warehouseName" -> first(row.get("warehouseName"),child(row,"warehouse","name"));
            case "skuCode" -> first(row.get("skuCode"),child(row,"sku","code"));
            case "skuName" -> first(row.get("skuName"),child(row,"sku","name"));
            case "unitName" -> first(row.get("unitName"),child(row,"unit","name"));
            case "amount" -> first(row.get("amount"),row.get("receivableNet"),row.get("payableNet"),row.get("lineAmount"));
            case "number" -> number;
            default -> key.startsWith("custom.")?child(row,"printCustomFields",key.substring(7)):row.get(key);
        };
        if(raw instanceof List<?> values) raw=String.join("、",values.stream().filter(PrintFields::scalar).map(Object::toString).toList());
        if(!scalar(raw)) return "";
        if(raw instanceof Boolean flag) return flag ? language.equals("en-US")?"Yes":"是" : language.equals("en-US")?"No":"否";
        return raw.toString();
    }

    private static Object first(Object... values) {
        for(Object value:values) if(scalar(value) && !value.toString().isBlank()) return value;
        return null;
    }

    private static Object child(Map<?,?> row,String name,String key) {return row.get(name) instanceof Map<?,?> child?child.get(key):null;}
    private static boolean scalar(Object value) {return value instanceof String || value instanceof Number || value instanceof Boolean;}
    private static Field field(String id,String label,String en,String kind){return new Field(id,label,en,kind,null,false);}
    private static Field line(String id,String label,String en,String kind){return new Field("lines."+id,label,en,kind,null,true);}
    static ApiException invalid(String message){return new ApiException(400,"VALIDATION_FAILED",message);}
}
