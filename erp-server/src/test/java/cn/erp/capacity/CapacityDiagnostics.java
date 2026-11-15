package cn.erp.capacity;

import cn.erp.identity.CompanyAccess;
import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import org.apache.coyote.AbstractProtocol;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 只读采集实际列表 SQL 的执行计划，禁止据此自动修改索引或实例参数。 */
@Component
@Profile("dev & load & !prod & !production")
public class CapacityDiagnostics {
    private final JdbcTemplate jdbc;
    private final ServletWebServerApplicationContext web;
    public CapacityDiagnostics(JdbcTemplate jdbc,ServletWebServerApplicationContext web){this.jdbc=jdbc;this.web=web;}

    public Map<String,Object> inspect(CompanyAccess actor) {
        long tenant=actor.tenantId(),company=actor.companyId();
        LocalDate to=LocalDate.now(ZoneId.of("Asia/Shanghai")),from=to.withDayOfMonth(1);
        var plans=new ArrayList<Map<String,Object>>();
        for(String table:List.of("sales_order","purchase_order")) {
            plans.add(plan(table+"_cursor","SELECT * FROM "+table+" WHERE tenant_id=? AND company_id=? AND is_deleted=0 AND id<? AND (LOCATE(?,order_no)>0 OR LOCATE(?,COALESCE(remark,''))>0) ORDER BY id DESC LIMIT ?",tenant,company,Long.MAX_VALUE,"","",21));
            plans.add(plan(table+"_browser_date_status","SELECT h.* FROM "+table+" h WHERE h.tenant_id=? AND h.company_id=? AND h.is_deleted=0 AND h.business_date>=? AND h.business_date<=? AND h.status=? ORDER BY h.id DESC LIMIT ?",tenant,company,Date.valueOf(from),Date.valueOf(to),table.equals("sales_order")?"approved":"cancelled",21));
            plans.add(plan(table+"_browser_date_counts","SELECT h.status,COUNT(*) total FROM "+table+" h WHERE h.tenant_id=? AND h.company_id=? AND h.is_deleted=0 AND h.business_date>=? AND h.business_date<=? GROUP BY h.status",tenant,company,Date.valueOf(from),Date.valueOf(to)));
        }
        plans.add(plan("sku_contains","SELECT * FROM sku WHERE tenant_id=? AND (LOCATE(?,name)>0 OR LOCATE(?,code)>0) ORDER BY id DESC LIMIT ?",tenant,"LOAD-199","LOAD-199",21));
        plans.add(plan("inventory_summary","""
            WITH balances AS (SELECT warehouse_id,sku_id,SUM(on_hand_qty) on_hand_qty,SUM(reserved_qty) reserved_qty,SUM(blocked_qty) blocked_qty
              FROM inventory_balance WHERE tenant_id=? AND company_id=? GROUP BY warehouse_id,sku_id),
            demand AS (SELECT l.warehouse_id,l.sku_id,SUM(l.base_qty) pending_demand_qty FROM sales_order_line l
              JOIN sales_order h ON h.tenant_id=l.tenant_id AND h.company_id=l.company_id AND h.id=l.order_id
              WHERE l.tenant_id=? AND l.company_id=? AND l.is_deleted=0 AND h.is_deleted=0 AND h.status='pending'
                AND l.warehouse_id IS NOT NULL GROUP BY l.warehouse_id,l.sku_id),
            k AS (SELECT warehouse_id,sku_id FROM balances UNION SELECT warehouse_id,sku_id FROM demand)
            SELECT k.warehouse_id,k.sku_id,w.code warehouse_code,w.name warehouse_name,s.code sku_code,s.name sku_name,
              COALESCE(b.on_hand_qty,0) on_hand_qty,COALESCE(b.reserved_qty,0) reserved_qty,COALESCE(b.blocked_qty,0) blocked_qty,
              COALESCE(b.on_hand_qty,0)-COALESCE(b.reserved_qty,0)-COALESCE(b.blocked_qty,0) available_qty,COALESCE(d.pending_demand_qty,0) pending_demand_qty
            FROM k LEFT JOIN balances b USING(warehouse_id,sku_id) LEFT JOIN demand d USING(warehouse_id,sku_id)
            JOIN warehouse w ON w.id=k.warehouse_id JOIN sku s ON s.id=k.sku_id
            WHERE (LOCATE(?,s.code)>0 OR LOCATE(?,s.name)>0) ORDER BY k.warehouse_id,k.sku_id LIMIT ?
            """,tenant,company,tenant,company,"","",21));
        var counts=new LinkedHashMap<String,Object>();
        for(String table:List.of("sales_order","sales_order_line","purchase_order","purchase_order_line","inventory_balance","journal"))counts.put(table,jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE tenant_id=? AND company_id=?",Long.class,tenant,company));
        counts.put("sku",jdbc.queryForObject("SELECT COUNT(*) FROM sku WHERE tenant_id=?",Long.class,tenant));
        return Map.of("databaseVersion",Objects.requireNonNull(jdbc.queryForObject("SELECT VERSION()",String.class)),"counts",counts,"plans",plans,"httpConnector",connector(),
            "instanceStatus",jdbc.queryForList("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_running','Questions','Slow_queries','Innodb_row_lock_waits','Innodb_row_lock_time','Created_tmp_disk_tables')"));
    }
    private Map<String,Object> connector() {
        if(!(web.getWebServer() instanceof TomcatWebServer server)||!(server.getTomcat().getConnector().getProtocolHandler() instanceof AbstractProtocol<?> protocol))throw new IllegalStateException("容量诊断需要实际Tomcat连接器");
        return Map.of("acceptCount",protocol.getAcceptCount(),"maxConnections",protocol.getMaxConnections(),"maxThreads",protocol.getMaxThreads(),"keepAliveTimeout",protocol.getKeepAliveTimeout(),"maxQueueSize",protocol.getMaxQueueSize(),"port",protocol.getLocalPort(),"protocol",protocol.getClass().getSimpleName());
    }
    private Map<String,Object> plan(String name,String sql,Object... args) {
        return Map.of("name",name,"sql",sql,"explain",jdbc.queryForList("EXPLAIN "+sql,args));
    }
}
