package cn.erp.receiving;

import cn.erp.identity.CompanyAccess;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.receiving.ReceivingSupport.*;

@Service
@Transactional(readOnly=true)
public class ReceivingQueries {
    private final JdbcTemplate jdbc;
    public ReceivingQueries(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    static String table(boolean returning) {return returning?"purchase_return":"goods_receipt";}
    static String parent(boolean returning) {return returning?"return_id":"receipt_id";}
    static String source(boolean returning) {return returning?"receipt_line_id":"order_line_id";}

    Map<String,Object> header(CompanyAccess scope,boolean returning,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM "+table(returning)+" WHERE tenant_id=? AND company_id=? AND id=?"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
        if(rows.isEmpty()||!scope.allowsWarehouse(id(rows.getFirst(),"warehouse_id"))) {throw missing();}return rows.getFirst();
    }
    List<Map<String,Object>> lines(CompanyAccess scope,boolean returning,long id,boolean lock) {
        return jdbc.queryForList("SELECT * FROM "+table(returning)+"_line WHERE tenant_id=? AND company_id=? AND "+parent(returning)+"=? ORDER BY "+source(returning)+",id"+(lock?" FOR UPDATE":""),scope.tenantId(),scope.companyId(),id);
    }
    public Map<String,Object> get(CompanyAccess scope,boolean returning,long id) {
        var header=header(scope,returning,id,false);var result=view(header);
        result.put("documentNo",header.get(returning?"return_no":"receipt_no"));
        result.put("supplierName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),header.get("supplier_id")));
        result.put("warehouseName",jdbc.queryForObject("SELECT name FROM warehouse WHERE tenant_id=? AND company_id=? AND id=?",String.class,scope.tenantId(),scope.companyId(),header.get("warehouse_id")));
        result.put("lines",lines(scope,returning,id,false).stream().map(raw->{
            var row=view(raw);
            row.put("skuName",jdbc.queryForObject("SELECT name FROM sku WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("sku_id")));
            row.put("unitName",jdbc.queryForObject("SELECT name FROM unit WHERE tenant_id=? AND id=?",String.class,scope.tenantId(),raw.get("unit_id")));
            if(!scope.hasPermission("cost:view")) {hideCost(row);}return row;
        }).toList());
        if(!scope.hasPermission("cost:view")) {hideCost(result);}
        result.put("allowedActions",actions(scope,returning,header.get("status").toString()));return result;
    }
    private List<String> actions(CompanyAccess scope,boolean returning,String status) {
        var names=status.equals("draft")?List.of("update","submit"):status.equals("submitted")?List.of("void","post"):List.<String>of();
        return names.stream().filter(name->scope.hasPermission(table(returning)+":"+name)).toList();
    }
    public Map<String,Object> list(CompanyAccess scope,boolean returning,Long supplier,String status,Long cursor,int limit) {
        validatePage(cursor,limit);
        if(status!=null&&!Set.of("draft","submitted","posted","voided","reversed").contains(status)) {throw invalid("单据状态无效");}
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String sql="SELECT h.id FROM "+table(returning)+" h WHERE h.tenant_id=? AND h.company_id=? AND h.id<?";
        if(supplier!=null) {sql+=" AND h.supplier_id=?";args.add(supplier);}
        if(status!=null) {sql+=" AND h.status=?";args.add(status);}
        sql+=warehouseFilter(scope,"h.",args);args.add(limit+1);
        var rows=jdbc.queryForList(sql+" ORDER BY h.id DESC LIMIT ?",Long.class,args.toArray());
        var result=new LinkedHashMap<String,Object>();result.put("items",rows.stream().limit(limit).map(id->get(scope,returning,id)).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).toString():null);return result;
    }
    public Map<String,Object> returnable(CompanyAccess scope,Long supplier,Long warehouse,boolean only,Long cursor,int limit) {
        validatePage(cursor,limit);
        var args=new ArrayList<Object>(List.of(scope.tenantId(),scope.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String sql="""
            SELECT l.*,h.receipt_no,h.warehouse_id,h.supplier_id,s.name sku_name,u.name unit_name
            FROM goods_receipt_line l JOIN goods_receipt h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.receipt_id
            JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id
            WHERE l.tenant_id=? AND l.company_id=? AND l.id<? AND h.status='posted'
            """;
        if(supplier!=null) {sql+=" AND h.supplier_id=?";args.add(supplier);}
        if(warehouse!=null) {sql+=" AND h.warehouse_id=?";args.add(warehouse);}
        if(only) {sql+=" AND l.base_qty-l.returned_base_qty-l.pending_return_base_qty>0";}
        sql+=warehouseFilter(scope,"h.",args);args.add(limit+1);
        var rows=jdbc.queryForList(sql+" ORDER BY l.id DESC LIMIT ?",args.toArray());
        var items=rows.stream().limit(limit).map(raw->{var row=view(raw);
            row.put("returnableQty",decimal(raw,"base_qty").subtract(decimal(raw,"returned_base_qty")).subtract(decimal(raw,"pending_return_base_qty")).toPlainString());
            if(!scope.hasPermission("cost:view")) {hideCost(row);}return row;
        }).toList();
        var result=new LinkedHashMap<String,Object>();result.put("items",items);result.put("nextCursor",rows.size()>limit?items.getLast().get("id"):null);return result;
    }
    private static void validatePage(Long cursor,int limit) {if(limit<1||limit>200||cursor!=null&&cursor<=0) {throw invalid("分页参数无效");}}
    static void hideCost(Map<String,Object> row) {for(String name:List.of("costAmount","unitCost","costAdjustAmount")) {row.remove(name);}}
}
