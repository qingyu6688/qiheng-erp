package cn.erp.quotation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import cn.erp.crm.CrmScope;
import cn.erp.identity.CompanyAccess;
import cn.erp.pricing.MinimumSalePrice;
import cn.erp.pricing.PricingModels;
import cn.erp.salesorder.SalesOrderModels;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.OrgOwnership;

@Component
@Transactional(readOnly=true)
class SalesQuoteQueries {
    static final String TYPE="sales_quote";
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final CrmScope crm;
    private final MinimumSalePrice minimum;
    SalesQuoteQueries(JdbcTemplate jdbc,ObjectMapper json,CrmScope crm,MinimumSalePrice minimum) {
        this.jdbc=jdbc; this.json=json; this.crm=crm; this.minimum=minimum;
    }
    static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    static long number(Map<String,Object> row,String field) { return row.get(field)==null?0:((Number)row.get(field)).longValue(); }
    static BigDecimal amount(Map<String,Object> row,String field) { return (BigDecimal)row.get(field); }
    static LocalDate date(Map<String,Object> row,String field) { return ((java.sql.Date)row.get(field)).toLocalDate(); }
    static ApiException invalid(String message) { return new ApiException(400,"VALIDATION_FAILED",message); }
    static ApiException missing() { return new ApiException(404,"NOT_FOUND","报价不存在或不在当前可见范围"); }
    static void require(CompanyAccess actor,String action) {
        if(!actor.hasPermission(TYPE+":"+(action.equals("reject")?"approve":action)))throw new ApiException(403,"FORBIDDEN","没有对应报价操作权限");
    }

    Map<String,Object> head(CompanyAccess actor,long id,boolean lock) {
        var rows=jdbc.queryForList("SELECT * FROM sales_quote h WHERE h.tenant_id=? AND h.company_id=? AND h.id=?"
                +visible(actor,"h")+(lock?" FOR UPDATE":""),actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty())throw missing();
        return rows.getFirst();
    }
    Map<String,Object> get(CompanyAccess actor,long id) {
        require(actor,"view");
        var head=head(actor,id,false);
        var result=ApiRows.row(head);
        result.remove("orderInput"); result.remove("pricingSnapshot");
        result.put("input",json.readValue(head.get("order_input").toString(),SalesOrderModels.Input.class));
        result.put("pricing",json.readValue(head.get("pricing_snapshot").toString(),PricingModels.Preview.class));
        result.put("customerName",jdbc.queryForObject("SELECT name FROM business_partner WHERE tenant_id=? AND id=?",String.class,actor.tenantId(),head.get("customer_id")));
        if(head.get("opportunity_id")!=null)result.put("opportunityName",jdbc.queryForObject("SELECT name FROM crm_business WHERE tenant_id=? AND company_id=? AND id=?",
                String.class,actor.tenantId(),actor.companyId(),head.get("opportunity_id")));
        result.put("lines",jdbc.queryForList("SELECT l.*,s.name sku_name,u.name unit_name,w.name warehouse_name,l.untaxed_amount+l.tax_amount amount"
                +" FROM sales_quote_line l JOIN sku s ON s.tenant_id=l.tenant_id AND s.id=l.sku_id"
                +" JOIN unit u ON u.tenant_id=l.tenant_id AND u.id=l.unit_id LEFT JOIN warehouse w ON w.tenant_id=l.tenant_id AND w.company_id=l.company_id AND w.id=l.warehouse_id"
                +" WHERE l.tenant_id=? AND l.company_id=? AND l.quote_id=? AND l.is_deleted=0 ORDER BY l.line_no",actor.tenantId(),actor.companyId(),id).stream().map(ApiRows::row).toList());
        var price=minimum.inspectQuotation(actor,id,false);
        result.putAll(price);
        String state=effectiveStatus(head);
        result.put("status",state);
        boolean blocked=Boolean.TRUE.equals(price.get("belowMinPrice")) && "block".equals(price.get("belowMinPricePolicy"));
        result.put("allowedActions",actions(actor,head,state,blocked));
        if(head.get("converted_order_id")!=null && actor.hasPermission("sales_order:view")) {
            String warehouses="";
            if(!actor.allWarehouses()) {
                String permitted=actor.warehouseIds().isEmpty()?"1=0":"ol.warehouse_id IN("+actor.warehouseIds().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")";
                warehouses=" AND NOT EXISTS(SELECT 1 FROM sales_order_line ol WHERE ol.tenant_id=o.tenant_id AND ol.company_id=o.company_id AND ol.order_id=o.id AND ol.is_deleted=0 AND (ol.warehouse_id IS NULL OR NOT("+permitted+")))";
            }
            var orders=jdbc.queryForList("SELECT order_no FROM sales_order o WHERE o.tenant_id=? AND o.company_id=? AND o.id=?"
                    +OrgOwnership.filter(actor,"o")+warehouses,String.class,actor.tenantId(),actor.companyId(),head.get("converted_order_id"));
            if(!orders.isEmpty())result.put("convertedOrderNo",orders.getFirst());
        }
        return result;
    }
    Map<String,Object> list(CompanyAccess actor,String status,Long cursor,int limit) {
        require(actor,"view");
        if(limit<1 || limit>100 || cursor!=null && cursor<=0)throw invalid("分页参数无效");
        if(status!=null && !Set.of("draft","pending","issued","rejected","converted","expired","voided").contains(status))throw invalid("报价状态无效");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId(),cursor==null?Long.MAX_VALUE:cursor));
        String filter="h.tenant_id=? AND h.company_id=? AND h.id<?"+visible(actor,"h");
        // 日期由北京时间显式传入，避免数据库会话时区改变截止日。
        String state="CASE WHEN h.valid_to<'"+today()+"' AND h.status NOT IN('converted','voided') THEN 'expired' ELSE h.status END";
        if(status!=null) {filter+=" AND ("+state+")=?"; args.add(status);}
        args.add(limit+1);
        var rows=jdbc.queryForList("SELECT h.id,h.document_no,h.customer_id,p.name customer_name,h.business_date,h.valid_to,h.total_amount,h.version,"
                +state+" status FROM sales_quote h JOIN business_partner p ON p.tenant_id=h.tenant_id AND p.id=h.customer_id"
                +" WHERE "+filter+" ORDER BY h.id DESC LIMIT ?",args.toArray());
        var result=new LinkedHashMap<String,Object>();
        result.put("items",rows.stream().limit(limit).map(ApiRows::row).toList());
        result.put("nextCursor",rows.size()>limit?rows.get(limit-1).get("id").toString():null);return result;
    }

    Map<String,Object> metadata(CompanyAccess actor,String search,Long customer) {
        require(actor,"view");
        if(search!=null && search.length()>100)throw invalid("搜索内容最多100字");
        var args=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        String warehouses="";
        if(!actor.allWarehouses()) {
            if(actor.warehouseIds().isEmpty())warehouses=" AND 1=0";
            else {warehouses=" AND id IN("+String.join(",",Collections.nCopies(actor.warehouseIds().size(),"?"))+")";args.addAll(actor.warehouseIds());}
        }
        var choices=new LinkedHashMap<String,Object>();
        choices.put("warehouses",jdbc.queryForList("SELECT id,name FROM warehouse WHERE tenant_id=? AND company_id=? AND status='active'"+warehouses+" ORDER BY id",args.toArray()).stream().map(ApiRows::row).toList());
        choices.put("customers",jdbc.queryForList("SELECT p.id,p.name FROM business_partner p JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id AND r.role='customer' AND r.status='active'"
                +" WHERE p.tenant_id=? AND p.status='active' AND p.name LIKE ? ORDER BY p.id LIMIT 200",actor.tenantId(),"%"+(search==null?"":search)+"%").stream().map(ApiRows::row).toList());
        choices.put("skus",jdbc.queryForList("SELECT id,name FROM sku WHERE tenant_id=? AND status='active' AND name LIKE ? ORDER BY id LIMIT 200",actor.tenantId(),"%"+(search==null?"":search)+"%").stream().map(ApiRows::row).toList());
        choices.put("opportunities",customer==null || !actor.hasPermission("crm_business:view")?List.of():jdbc.queryForList(
                "SELECT b.id,b.name FROM crm_business b JOIN crm_customer_profile c ON c.tenant_id=b.tenant_id AND c.company_id=b.company_id AND c.partner_id=b.partner_id"
                    +" WHERE b.tenant_id=? AND b.company_id=? AND b.partner_id=? AND "+crm.customerFilter(actor,"c")+" ORDER BY b.id DESC LIMIT 200",
                actor.tenantId(),actor.companyId(),customer).stream().map(ApiRows::row).toList());
        return choices;
    }
    List<Map<String,Object>> units(CompanyAccess actor,long sku) {
        require(actor,"view");
        if(sku<=0)throw invalid("请选择有效商品");
        return jdbc.queryForList("SELECT cv.unit_id,u.name unit_name,cv.rate_to_base,cv.is_base FROM unit_conversion cv"
                +" JOIN unit u ON u.tenant_id=cv.tenant_id AND u.id=cv.unit_id AND u.status='active'"
                +" JOIN sku s ON s.tenant_id=cv.tenant_id AND s.id=cv.sku_id AND s.status='active'"
                +" WHERE cv.tenant_id=? AND cv.sku_id=? ORDER BY cv.is_base DESC,cv.unit_id",actor.tenantId(),sku).stream().map(ApiRows::row).toList();
    }
    String visible(CompanyAccess actor,String alias) {
        if(alias==null || !alias.matches("[a-z][a-z0-9_]*"))throw invalid("报价范围别名无效");
        String filter=OrgOwnership.filter(actor,alias);
        if(!actor.allWarehouses()) {
            String permitted=actor.warehouseIds().isEmpty()?"1=0":"l.warehouse_id IN("+actor.warehouseIds().stream().sorted().map(String::valueOf).collect(java.util.stream.Collectors.joining(","))+")";
            filter+=" AND NOT EXISTS(SELECT 1 FROM sales_quote_line l WHERE l.tenant_id="+alias+".tenant_id AND l.company_id="+alias+".company_id AND l.quote_id="+alias+".id AND l.is_deleted=0 AND (l.warehouse_id IS NULL OR NOT("+permitted+")))";
        }
        String opportunity=actor.hasPermission("crm_business:view")?"EXISTS(SELECT 1 FROM crm_business b JOIN crm_customer_profile c ON c.tenant_id=b.tenant_id AND c.company_id=b.company_id AND c.partner_id=b.partner_id WHERE b.tenant_id="
                +alias+".tenant_id AND b.company_id="+alias+".company_id AND b.id="+alias+".opportunity_id AND b.partner_id="+alias+".customer_id AND "+crm.customerFilter(actor,"c")+")":"1=0";
        return filter+" AND ("+alias+".opportunity_id IS NULL OR "+opportunity+")";
    }
    private String effectiveStatus(Map<String,Object> head) {
        return date(head,"valid_to").isBefore(today()) && !Set.of("converted","voided").contains(head.get("status"))?"expired":head.get("status").toString();
    }
    private List<String> actions(CompanyAccess actor,Map<String,Object> head,String status,boolean blocked) {
        List<String> actions=switch(status) {
            case "draft","rejected" -> List.of("update","submit","void");
            case "issued" -> List.of("update","convert","void");
            case "expired" -> head.get("status").equals("pending")?List.of():List.of("update","void");
            default -> List.<String>of();
        };
        return actions.stream().filter(action->actor.hasPermission(TYPE+":"+action))
                .filter(action->!blocked || !Set.of("submit","convert").contains(action))
                .filter(action->!action.equals("convert") || actor.hasPermission("sales_order:create")).toList();
    }
}
