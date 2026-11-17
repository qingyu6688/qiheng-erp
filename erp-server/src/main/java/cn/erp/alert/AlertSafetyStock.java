package cn.erp.alert;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AlertSafetyStock {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    AlertSafetyStock(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    record Input(Long version,String quantity) {}

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,String keyword,Long cursor,int limit) {
        AlertRules.configure(actor);
        if (cursor!=null && cursor<=0 || limit<1 || limit>100 || keyword!=null && keyword.length()>100) throw AlertRules.invalid("商品查询参数无效");
        String search=keyword==null?"":keyword.strip();
        var rows=jdbc.queryForList("""
                SELECT s.id,s.code,s.name,COALESCE(v.quantity,s.safety_stock) quantity,COALESCE(v.version,0) version
                FROM sku s LEFT JOIN alert_sku_safety_stock v ON v.tenant_id=s.tenant_id AND v.sku_id=s.id AND v.company_id=?
                WHERE s.tenant_id=? AND s.status='active' AND (? IS NULL OR s.id<?)
                    AND (LOCATE(?,s.code)>0 OR LOCATE(?,s.name)>0) ORDER BY s.id DESC LIMIT ?
                """,actor.companyId(),actor.tenantId(),cursor,cursor,search,search,limit+1);
        var records=rows.stream().limit(limit).map(ApiRows::row).toList();
        var result=new LinkedHashMap<String,Object>();
        result.put("items",records);result.put("nextCursor",rows.size()>limit?records.getLast().get("id"):null);
        return result;
    }

    @Transactional
    Map<String,Object> update(CompanyAccess actor,long sku,Input input) {
        AlertRules.configure(actor);
        if (input.quantity()==null || !input.quantity().matches("[0-9]{1,18}(?:\\.[0-9]{1,6})?"))
            throw AlertRules.invalid("安全库存须为非负数量，最多六位小数");
        if (jdbc.queryForList("SELECT id FROM sku WHERE tenant_id=? AND id=? AND status='active' FOR SHARE",Long.class,actor.tenantId(),sku).isEmpty())
            throw AlertRules.missing();
        rulesLock(actor);
        var old=detail(actor,sku,true);
        AlertRules.version(input.version(),((Number)old.get("version")).longValue());
        jdbc.update("""
                INSERT INTO alert_sku_safety_stock(tenant_id,company_id,sku_id,quantity,version) VALUES(?,?,?,?,1)
                ON DUPLICATE KEY UPDATE quantity=?,version=version+1
                """,actor.tenantId(),actor.companyId(),sku,new BigDecimal(input.quantity()),new BigDecimal(input.quantity()));
        jdbc.update("UPDATE alert_rule SET next_run_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND company_id=? AND code='stock_below_safety'",
                actor.tenantId(),actor.companyId());
        var current=detail(actor,sku,false);
        audit.record(actor,"alert_safety_stock.update","sku",sku,ApiRows.row(old),ApiRows.row(current));
        return ApiRows.row(current);
    }

    private void rulesLock(CompanyAccess actor) {
        jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? AND id=? AND status='active' FOR UPDATE",Long.class,actor.tenantId(),actor.companyId());
    }

    private Map<String,Object> detail(CompanyAccess actor,long sku,boolean lock) {
        return jdbc.queryForList("""
                SELECT s.id,s.code,s.name,COALESCE(v.quantity,s.safety_stock) quantity,COALESCE(v.version,0) version
                FROM sku s LEFT JOIN alert_sku_safety_stock v ON v.tenant_id=s.tenant_id AND v.sku_id=s.id AND v.company_id=?
                WHERE s.tenant_id=? AND s.id=?
                """+(lock?" FOR UPDATE OF v":""),actor.companyId(),actor.tenantId(),sku).stream().findFirst().orElseThrow(AlertRules::missing);
    }
}
