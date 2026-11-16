package cn.erp.workbench;

import cn.erp.documents.DocumentAccess;
import cn.erp.identity.CompanyAccess;
import cn.erp.salesfinance.FinanceSupport;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkbenchService {
    private final DocumentBrowser browser;
    private final cn.erp.shared.TodoInbox inbox;
    private final DocumentAccess documents;
    private final NamedParameterJdbcTemplate jdbc;
    public WorkbenchService(DocumentBrowser browser,DocumentAccess documents,NamedParameterJdbcTemplate jdbc,cn.erp.shared.TodoInbox inbox) {this.browser=browser;this.documents=documents;this.jdbc=jdbc;this.inbox=inbox;}
    public Map<String,Object> summary(CompanyAccess actor) {
        var result=new LinkedHashMap<String,Object>();var blocks=new ArrayList<Map<String,Object>>();var todos=new ArrayList<Map<String,Object>>();
        var messages=inbox.summary(actor);
        if(messages.get("blocks") instanceof List<?> values) for(Object value:values) if(value instanceof Map<?,?> row) {var block=new LinkedHashMap<String,Object>();row.forEach((key,item)->block.put(key.toString(),item));blocks.add(block);}
        result.put("blocks",blocks);result.put("todos",messages.get("todos"));
        if(actor.hasPermission("inventory:view")) {
            var params=browser.params(actor);
            String scope=actor.allWarehouses()?"":" AND l.warehouse_id IN (:warehouses)";
            String alerts="""
                WITH demand AS (SELECT l.warehouse_id,l.sku_id,SUM(l.base_qty) pending_demand_qty
                  FROM sales_order_line l JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
                  WHERE l.tenant_id=:tenant AND l.company_id=:company AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending'
                """+scope+cn.erp.shared.OrgOwnership.filter(actor,"h")+"""
                  GROUP BY l.warehouse_id,l.sku_id),b AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty-reserved_qty-blocked_qty) available_qty
                  FROM inventory_balance WHERE tenant_id=:tenant AND company_id=:company GROUP BY warehouse_id,sku_id)
                SELECT d.warehouse_id,d.sku_id,w.name warehouse_name,s.code sku_code,s.name sku_name,COALESCE(b.available_qty,0) available_qty,d.pending_demand_qty
                FROM demand d LEFT JOIN b USING(warehouse_id,sku_id) JOIN warehouse w ON w.tenant_id=:tenant AND w.id=d.warehouse_id
                JOIN sku s ON s.tenant_id=:tenant AND s.id=d.sku_id WHERE d.pending_demand_qty>COALESCE(b.available_qty,0)
                """;
            long count=jdbc.queryForObject("SELECT COUNT(*) FROM ("+alerts+") alerts",params,Long.class);
            blocks.add(Map.of("key","inventory-shortage","label","待审需求超过可用","count",count,"url","/inventory-balances"));
            result.put("inventoryAlerts",jdbc.queryForList(alerts+" ORDER BY d.warehouse_id,d.sku_id LIMIT 10",params).stream().map(FinanceSupport::row).toList());
        }
        if(actor.hasPermission("period:view")) {
            var params=browser.params(actor);params.put("today",LocalDate.now(ZoneId.of("Asia/Shanghai")));
            result.put("periods",jdbc.queryForList("SELECT id,period,status,start_date,end_date FROM accounting_period WHERE tenant_id=:tenant AND company_id=:company AND start_date<=:today AND end_date>=:today",params).stream().map(FinanceSupport::row).toList());
        }
        if(actor.hasPermission("audit:view")&&actor.allWarehouses()&&actor.orgScope().allDepartments()) {
            var params=browser.params(actor);params.put("user",actor.userId());
            result.put("recentActions",jdbc.queryForList("SELECT action,object_type,object_id,created_at FROM audit_log WHERE tenant_id=:tenant AND company_id=:company AND operator_id=:user ORDER BY id DESC LIMIT 5",params).stream().map(FinanceSupport::row).toList());
        }
        result.put("asOf",Instant.now().toString());return result;
    }
}
