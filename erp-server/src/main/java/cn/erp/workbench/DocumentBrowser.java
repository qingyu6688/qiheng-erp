package cn.erp.workbench;

import cn.erp.customfield.CustomFields;
import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 列表、状态计数及工作台使用同一筛选条件，详情仍由所属领域检查权限和字段。 */
@Service
@Transactional(readOnly=true)
public class DocumentBrowser {
    public record Spec(String type,String number,String partner,String date,boolean deleted,String warehouse) {
        String textColumn() {return Set.of("sales_return","purchase_return","refund_request","stock_adjustment","document_reversal").contains(type)?"reason":"remark";}
    }
    public record Filter(String keyword,String status,Long partnerId,Long warehouseId,LocalDate dateFrom,LocalDate dateTo,
                         String fulfillmentStatus,String settlementStatus,boolean signOverdue,String documentNo) {
        public Filter(String keyword,String status,Long partnerId,Long warehouseId,LocalDate dateFrom,LocalDate dateTo,
                      String fulfillmentStatus,String settlementStatus,boolean signOverdue) {
            this(keyword,status,partnerId,warehouseId,dateFrom,dateTo,fulfillmentStatus,settlementStatus,signOverdue,null);
        }
        public static Filter empty() {return new Filter(null,null,null,null,null,null,null,null,false);}
    }
    public static final Map<String,Spec> SPECS=Map.ofEntries(
        spec("sales-orders","sales_order","order_no","customer_id",true,"lines"),
        spec("shipments","shipment","shipment_no","customer_id",false,"warehouse_id"),
        spec("sales-returns","sales_return","return_no","customer_id",false,"warehouse_id"),
        spec("receipts","receipt","receipt_no","partner_id",false,null),
        spec("refund-requests","refund_request","refund_no","customer_id",false,null),
        spec("invoice-registrations","invoice_registration","invoice_no","partner_id",false,null),
        spec("purchase-requisitions","purchase_requisition","requisition_no",null,true,null),
        spec("purchase-orders","purchase_order","order_no","supplier_id",true,"lines"),
        spec("goods-receipts","goods_receipt","receipt_no","supplier_id",false,"warehouse_id"),
        spec("purchase-returns","purchase_return","return_no","supplier_id",false,"warehouse_id"),
        spec("payments","payment","payment_no","partner_id",false,null),
        spec("account-transfers","account_transfer","transfer_no",null,false,null),
        spec("opening-entries","opening_entry","entry_no","partner_id",false,null),
        spec("stock-adjustments","stock_adjustment","adjust_no",null,false,"warehouse_id"),
        spec("stock-transfers","stock_transfer","transfer_no",null,false,"transfer"),
        spec("stock-counts","stock_count","count_no",null,false,"warehouse_id"),
        spec("document-reversals","document_reversal","reversal_no",null,false,"reversal"));
    private final NamedParameterJdbcTemplate jdbc;
    private final DocumentAccess documents;
    private final CustomFields customFields;
    public DocumentBrowser(NamedParameterJdbcTemplate jdbc,DocumentAccess documents,CustomFields customFields) {this.jdbc=jdbc;this.documents=documents;this.customFields=customFields;}
    private static Map.Entry<String,Spec> spec(String path,String type,String no,String party,boolean deleted,String warehouse) {
        return Map.entry(path,new Spec(type,no,party,type.equals("invoice_registration")?"invoice_date":"business_date",deleted,warehouse));
    }
    public Spec spec(String kind) {var value=SPECS.get(kind);if(value==null)throw invalid("单据类型无效");return value;}
    public String permission(String kind) {return documents.permission(spec(kind).type(),"view");}

    public Optional<String> visibleNumber(CompanyAccess actor,String kind,long id) {
        var spec=spec(kind);
        if(!actor.hasPermission(permission(kind))) return Optional.empty();
        var bindings=params(actor);
        bindings.put("referenceId",id);
        boolean reversal="document_reversal".equals(spec.type());
        String columns="h."+spec.number()+" document_no"+(reversal?",h.source_type,h.source_id":"");
        var rows=jdbc.queryForList("SELECT "+columns+" FROM "+spec.type()+" h WHERE "
                +where(actor,spec,Filter.empty(),bindings)+" AND h.id=:referenceId",bindings);
        if(rows.isEmpty()) return Optional.empty();
        var row=rows.getFirst();
        if(reversal) {
            String sourceType=row.get("source_type").toString();
            var source=SPECS.entrySet().stream().filter(entry->sourceType.equals(entry.getValue().type()))
                    .map(Map.Entry::getKey).findFirst();
            if(source.isEmpty()||kind.equals(source.get())||visibleNumber(actor,source.get(),((Number)row.get("source_id")).longValue()).isEmpty())
                return Optional.empty();
        }
        return Optional.of(row.get("document_no").toString());
    }

    public Map<String,Object> page(CompanyAccess actor,String kind,Filter filter,Long cursor,int limit,boolean withTotal) {
        return page(actor,kind,filter,cursor,limit,withTotal,Map.of(),false);
    }
    public Map<String,Object> page(CompanyAccess actor,String kind,Filter filter,Long cursor,int limit,boolean withTotal,Map<String,String[]> custom,boolean export) {
        if(limit<1||limit>200||cursor!=null&&cursor<=0)throw invalid("分页条件无效");
        var spec=spec(kind);DocumentAccess.require(actor,permission(kind));
        var parameters=params(actor);String where=where(actor,spec,filter,parameters);
        if(order(spec)) {
            var bindings=new ArrayList<Object>();
            String extension=customFields.predicates(actor.tenantId(),spec.type(),"h",custom,bindings);
            for(int i=0;i<bindings.size();i++) {String name="custom"+i;extension=extension.replaceFirst("\\?",":"+name);parameters.put(name,bindings.get(i));}
            where+=extension;
        }
        var counts=counts(spec,where,parameters);String list=where;
        if(filter.status()!=null&&!filter.status().isBlank()) {list+=" AND h.status=:status";parameters.put("status",filter.status());}
        if(cursor!=null) {list+=" AND h.id<:cursor";parameters.put("cursor",cursor);}
        parameters.put("limit",limit+1);
        // 只调整销售订单列表的索引选择，状态计数继续使用独立的聚合计划。
        String hint = "sales_order".equals(spec.type()) ? " IGNORE INDEX (PRIMARY)" : "";
        var rows=jdbc.queryForList("SELECT h.* FROM "+spec.type()+" h"+hint+" WHERE "+list+" ORDER BY h.id DESC LIMIT :limit",parameters);
        var visible=rows.stream().limit(limit).toList();var names=referenceNames(actor,visible);
        var fields=order(spec)?customFields.fields(actor.tenantId(),spec.type()).stream()
                .filter(field->export?field.importExport():field.listVisible()).toList():List.<CustomFields.Field>of();
        var items=visible.stream().map(row->{
            var result=listRow(spec,row,names);
            if(!fields.isEmpty())result.put("extJson",customFields.visible((String)row.get("ext_json"),fields));
            return result;
        }).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);result.put("statusCounts",counts);
        if(withTotal)result.put("total",filter.status()==null||filter.status().isBlank()?counts.values().stream().mapToLong(Long::longValue).sum():counts.getOrDefault(filter.status(),0L));
        return result;
    }
    public byte[] customExport(CompanyAccess actor,String kind,Filter filter,Long cursor,int limit,Map<String,String[]> parameters) {
        var spec=spec(kind);
        if(!order(spec))throw invalid("仅购销订单支持扩展字段列表导出");
        var fields=customFields.fields(actor.tenantId(),spec.type()).stream().filter(CustomFields.Field::importExport).toList();
        var body=new StringBuilder("\uFEFF");
        var columns=new ArrayList<>(List.of("单号","状态","业务日期"));
        fields.forEach(field->columns.add(field.label()));
        csv(body,columns);
        if(page(actor,kind,filter,cursor,limit,false,parameters,true).get("items") instanceof List<?> rows)
            for(Object item:rows) if(item instanceof Map<?,?> row) {
                var cells=new ArrayList<>(List.of(Objects.toString(row.get("documentNo"),""),Objects.toString(row.get("status"),""),Objects.toString(row.get("businessDate"),"")));
                var values=row.get("extJson") instanceof Map<?,?> map?map:Map.of();
                for(var field:fields) {
                    Object value=values.get(field.fieldKey());
                    cells.add(value instanceof Collection<?> many?String.join("|",many.stream().map(Object::toString).toList()):Objects.toString(value,""));
                }
                csv(body,cells);
            }
        return body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public byte[] exportSelection(CompanyAccess actor, String kind, List<Long> ids) {
        String amount = switch (kind) {
            case "sales-orders", "purchase-orders" -> "total_amount";
            case "receipts" -> "amount";
            default -> throw invalid("该单据类型暂不支持导出所选");
        };
        if (ids == null || ids.isEmpty() || ids.size() > 100
                || ids.stream().anyMatch(id -> id == null || id <= 0)
                || new HashSet<>(ids).size() != ids.size()) {
            throw invalid("请选择 1 至 100 张不重复的单据");
        }
        var spec = spec(kind);
        DocumentAccess.require(actor, permission(kind));
        var parameters = params(actor);
        parameters.put("selection", ids);
        var rows = jdbc.queryForList(
                "SELECT h.id,h." + spec.number() + " document_no,h." + spec.date() + " business_date,"
                + "h.status,h." + amount + " amount FROM " + spec.type() + " h WHERE "
                + where(actor, spec, Filter.empty(), parameters) + " AND h.id IN (:selection)", parameters);
        if (rows.size() != ids.size()) {
            throw new ApiException(404, "NOT_FOUND", "所选单据已变更或无权查看，请刷新后重试");
        }
        var byId = new HashMap<Long, Map<String, Object>>();
        for (var row : rows) byId.put(((Number) row.get("id")).longValue(), row);
        var body = new StringBuilder("\uFEFF");
        csv(body, List.of("单号", "业务日期", "状态", "金额"));
        for (var id : ids) {
            var row = byId.get(id);
            csv(body, List.of(
                    Objects.toString(row.get("document_no"), ""), Objects.toString(row.get("business_date"), ""),
                    Objects.toString(row.get("status"), ""), Objects.toString(row.get("amount"), "")));
        }
        return body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void csv(StringBuilder body,List<String> cells) {
        body.append(String.join(",",cells.stream().map(cell->{
            String trimmed=cell.stripLeading();
            String value=!trimmed.isEmpty()&&"=+-@".indexOf(trimmed.charAt(0))>=0?"'"+cell:cell;
            return "\""+value.replace("\"","\"\"")+"\"";
        }).toList())).append("\r\n");
    }
    private boolean order(Spec spec) {return List.of("sales_order","purchase_order").contains(spec.type());}
    // 列表只读取表头白名单，避免逐单加载明细、预留和历史变更；操作许可仍在详情及命令检查。
    private static final Set<String> LIST_FIELDS=Set.of("id","version","status","business_date","invoice_date","fulfillment_status","settlement_status","sign_date","snapshot_at","total_amount","receivable_amount","payable_amount","credit_amount","debit_amount","amount","created_at","receipt_type","payment_type","entry_type","adjust_type");
    private static final Map<String,String> REFERENCES=Map.ofEntries(Map.entry("customer_id","business_partner"),Map.entry("supplier_id","business_partner"),Map.entry("partner_id","business_partner"),Map.entry("warehouse_id","warehouse"),Map.entry("from_warehouse_id","warehouse"),Map.entry("to_warehouse_id","warehouse"),Map.entry("cash_account_id","cash_account"),Map.entry("from_account_id","cash_account"),Map.entry("to_account_id","cash_account"),Map.entry("requester_id","sys_user"));
    private Map<String,Map<String,String>> referenceNames(CompanyAccess actor,List<Map<String,Object>> rows) {
        var ids=new HashMap<String,Set<Object>>();
        for(var row:rows)REFERENCES.forEach((field,table)->{if(row.get(field)!=null)ids.computeIfAbsent(table,key->new HashSet<>()).add(row.get(field));});
        var result=new HashMap<String,Map<String,String>>();
        ids.forEach((table,values)->{
            String label=table.equals("sys_user")?"display_name":"name";var names=new HashMap<String,String>();
            var params=new HashMap<String,Object>();params.put("tenant",actor.tenantId());params.put("ids",values);params.put("company",actor.companyId());
            String company=Set.of("warehouse","cash_account").contains(table)?" AND company_id=:company":"";
            jdbc.query("SELECT id,"+label+" name FROM "+table+" WHERE tenant_id=:tenant AND id IN (:ids)"+company,params,(org.springframework.jdbc.core.RowCallbackHandler)row->names.put(row.getString("id"),row.getString("name")));result.put(table,names);
        });return result;
    }
    private Map<String,Object> listRow(Spec spec,Map<String,Object> raw,Map<String,Map<String,String>> names) {
        var fields=new LinkedHashMap<String,Object>();raw.forEach((key,value)->{if(LIST_FIELDS.contains(key))fields.put(key,value);});
        var result=cn.erp.salesorder.SalesOrderQueries.camel(fields);result.put("documentNo",raw.get(spec.number()));result.put("allowedActions",List.of());
        REFERENCES.forEach((field,table)->{if(raw.get(field)!=null){String name=names.getOrDefault(table,Map.of()).get(raw.get(field).toString());String key=field.substring(0,field.length()-3)+"_name";result.putAll(cn.erp.salesorder.SalesOrderQueries.camel(Collections.singletonMap(key,name)));}});
        return result;
    }
    public Map<String,Long> statusCounts(CompanyAccess actor,String kind,Filter filter) {
        DocumentAccess.require(actor,permission(kind));var parameters=params(actor);var spec=spec(kind);return counts(spec,where(actor,spec,filter,parameters),parameters);
    }
    private Map<String,Long> counts(Spec spec,String where,Map<String,Object> params) {
        var result=new LinkedHashMap<String,Long>();jdbc.query("SELECT h.status,COUNT(*) total FROM "+spec.type()+" h WHERE "+where+" GROUP BY h.status",params,(org.springframework.jdbc.core.RowCallbackHandler) row->result.put(row.getString(1),row.getLong(2)));return result;
    }
    Map<String,Object> params(CompanyAccess actor) {var values=new HashMap<String,Object>();values.put("tenant",actor.tenantId());values.put("company",actor.companyId());values.put("warehouses",actor.warehouseIds().isEmpty()?Set.of(-1L):actor.warehouseIds());return values;}
    String where(CompanyAccess actor,Spec spec,Filter filter,Map<String,Object> params) {
        if(filter.keyword()!=null&&filter.keyword().length()>100)throw invalid("单号筛选最多100个字符");
        if(filter.documentNo()!=null&&(filter.documentNo().isBlank()||filter.documentNo().length()>100))
            throw invalid("精确单号筛选须填写1至100个字符");
        if(filter.dateFrom()!=null&&filter.dateTo()!=null&&filter.dateFrom().isAfter(filter.dateTo()))throw invalid("开始日期不能晚于结束日期");
        if(filter.partnerId()!=null&&filter.partnerId()<=0||filter.warehouseId()!=null&&filter.warehouseId()<=0)throw invalid("筛选对象编号无效");
        String sql="h.tenant_id=:tenant AND h.company_id=:company"+cn.erp.shared.OrgOwnership.filter(actor,"h")+(spec.deleted()?" AND h.is_deleted=0":"");
        if(filter.documentNo()!=null) {sql+=" AND h."+spec.number()+"=:documentNo";params.put("documentNo",filter.documentNo());}
        if(filter.keyword()!=null&&!filter.keyword().isBlank()) {sql+=" AND (LOCATE(:keyword,h."+spec.number()+")>0 OR LOCATE(:keyword,COALESCE(h."+spec.textColumn()+",''))>0)";params.put("keyword",filter.keyword());}
        if(filter.dateFrom()!=null) {sql+=" AND h."+spec.date()+">=:from";params.put("from",filter.dateFrom());}
        if(filter.dateTo()!=null) {sql+=" AND h."+spec.date()+"<=:to";params.put("to",filter.dateTo());}
        if(filter.partnerId()!=null) {if(spec.partner()==null)throw invalid("此单据不支持往来筛选");sql+=" AND h."+spec.partner()+"=:partner";params.put("partner",filter.partnerId());}
        if(filter.fulfillmentStatus()!=null&&!filter.fulfillmentStatus().isBlank()) {if(!spec.type().endsWith("_order"))throw invalid("此单据不支持履约筛选");sql+=" AND h.fulfillment_status=:fulfillment";params.put("fulfillment",filter.fulfillmentStatus());}
        if(filter.settlementStatus()!=null&&!filter.settlementStatus().isBlank()) {if(!spec.type().endsWith("_order"))throw invalid("此单据不支持结算筛选");sql+=" AND h.settlement_status=:settlement";params.put("settlement",filter.settlementStatus());}
        if(filter.signOverdue()) {if(!spec.type().equals("shipment"))throw invalid("此单据不支持签收超期筛选");sql+=" AND h.status='posted' AND h.business_date<DATE_SUB(DATE(CONVERT_TZ(UTC_TIMESTAMP(),'+00:00','+08:00')),INTERVAL 7 DAY)";}
        sql+=scope(actor,spec,params);
        if(filter.warehouseId()!=null) {
            if(!actor.allowsWarehouse(filter.warehouseId()))throw new ApiException(404,"NOT_FOUND","仓库不存在或无权查看");params.put("warehouse",filter.warehouseId());
            if("lines".equals(spec.warehouse()))sql+=" AND EXISTS(SELECT 1 FROM "+spec.type()+"_line l WHERE l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.order_id=h.id AND l.is_deleted=0 AND l.warehouse_id=:warehouse)";
            else if("transfer".equals(spec.warehouse()))sql+=" AND (h.from_warehouse_id=:warehouse OR h.to_warehouse_id=:warehouse)";
            else if("warehouse_id".equals(spec.warehouse()))sql+=" AND h.warehouse_id=:warehouse";
            else throw invalid("此单据不支持仓库筛选");
        }
        return sql;
    }
    private String scope(CompanyAccess actor,Spec spec,Map<String,Object> params) {
        if("reversal".equals(spec.warehouse())) {
            var parts=new ArrayList<String>();
            for(String type:List.of("shipment","sales_return","goods_receipt","purchase_return","receipt","payment","account_transfer","stock_adjustment")) {
                if(!actor.hasPermission(type+":view"))continue;
                String condition="h.source_type='"+type+"'";
                if(!actor.allWarehouses()&&!Set.of("receipt","payment","account_transfer").contains(type))condition+=" AND EXISTS(SELECT 1 FROM "+type+" source WHERE source.tenant_id=h.tenant_id AND source.company_id=h.company_id AND source.id=h.source_id AND source.warehouse_id IN (:warehouses))";
                parts.add("("+condition+")");
            }
            return " AND ("+(parts.isEmpty()?"1=0":String.join(" OR ",parts))+")";
        }
        if(actor.allWarehouses()||spec.warehouse()==null)return "";
        return switch(spec.warehouse()) {
            case "lines" -> " AND EXISTS(SELECT 1 FROM "+spec.type()+"_line l WHERE l.order_id=h.id AND l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.is_deleted=0) AND NOT EXISTS(SELECT 1 FROM "+spec.type()+"_line l WHERE l.order_id=h.id AND l.tenant_id=h.tenant_id AND l.company_id=h.company_id AND l.is_deleted=0 AND (l.warehouse_id IS NULL OR l.warehouse_id NOT IN (:warehouses)))";
            case "transfer" -> " AND h.from_warehouse_id IN (:warehouses) AND h.to_warehouse_id IN (:warehouses)";
            default -> " AND h.warehouse_id IN (:warehouses)";
        };
    }
    private ApiException invalid(String message) {return new ApiException(400,"VALIDATION_FAILED",message);}
}
