package cn.erp.reference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly=true)
class PartnerService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final cn.erp.sysadmin.AreaService areas;
    private final cn.erp.sysadmin.DictionaryService dictionaries;

    PartnerService(JdbcTemplate jdbc, AuditService audit,cn.erp.sysadmin.AreaService areas,cn.erp.sysadmin.DictionaryService dictionaries) { this.jdbc=jdbc; this.audit=audit;this.areas=areas;this.dictionaries=dictionaries; }

    Map<String,Object> list(CompanyAccess scope,String keyword,String role,String cursor,int limit) {
        long last = ReferenceRows.cursor(cursor,limit);
        if (role != null && !Set.of("customer","supplier").contains(role)) { throw ReferenceRows.invalid("往来角色无效"); }
        var rows=jdbc.queryForList("""
                SELECT p.id,p.code,p.name,p.short_name,p.tax_no,p.status,p.version,p.area_id,p.source_code FROM business_partner p
                WHERE p.tenant_id=? AND p.id<? AND (LOCATE(?,p.code)>0 OR LOCATE(?,p.name)>0)
                AND (? IS NULL OR EXISTS(SELECT 1 FROM partner_role r WHERE r.tenant_id=p.tenant_id
                    AND r.partner_id=p.id AND r.role=? AND r.status='active')) ORDER BY p.id DESC LIMIT ?
                """,scope.tenantId(),last,keyword,keyword,role,role,limit+1);
        var page=ReferenceRows.page(rows,limit);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)page.get("items");
        items.forEach(item -> enrich(scope,item));
        return page;
    }

    Map<String,Object> get(CompanyAccess scope,long id,boolean lock) {
        var row=ReferenceRows.one(jdbc.queryForList("""
                SELECT id,code,name,short_name,tax_no,status,version,area_id,source_code FROM business_partner
                WHERE tenant_id=? AND id=?
                """+(lock?" FOR UPDATE":""),scope.tenantId(),id));
        enrich(scope,row);
        return row;
    }

    private void enrich(CompanyAccess scope,Map<String,Object> item) {
        item.put("roles",jdbc.queryForList("SELECT role FROM partner_role WHERE tenant_id=? AND partner_id=? AND status='active' ORDER BY role",
                String.class,scope.tenantId(),item.get("id")));
        item.put("allowedActions",scope.hasPermission("partner:update")?List.of("update",
                "active".equals(item.get("status"))?"disable":"enable"):List.of());
    }

    @Transactional
    Map<String,Object> create(CompanyAccess scope,PartnerModels.Partner input) {
        areas.validate(input.areaId());dictionaries.validateValue(scope.tenantId(),"customer_source",input.sourceCode(),false);
        long id=Ids.next();
        jdbc.update("""
                INSERT INTO business_partner(id,tenant_id,code,name,short_name,tax_no,created_by,updated_by)
                VALUES(?,?,?,?,?,?,?,?)
                """,id,scope.tenantId(),input.code().trim(),input.name().trim(),input.shortName(),input.taxNo(),scope.userId(),scope.userId());
        jdbc.update("UPDATE business_partner SET area_id=?,source_code=? WHERE tenant_id=? AND id=?",input.areaId(),input.sourceCode(),scope.tenantId(),id);
        roles(scope,id,input.roles());
        return changed(scope,id,"partner.create",null);
    }

    @Transactional
    Map<String,Object> update(CompanyAccess scope,long id,PartnerModels.Partner input) {
        var before=get(scope,id,true); ReferenceRows.version(input.version(),before);
        areas.validate(input.areaId());dictionaries.validateValue(scope.tenantId(),"customer_source",input.sourceCode(),Objects.equals(input.sourceCode(),before.get("sourceCode")));
        jdbc.update("UPDATE business_partner SET area_id=COALESCE(?,area_id),source_code=COALESCE(?,source_code) WHERE tenant_id=? AND id=?",input.areaId(),input.sourceCode(),scope.tenantId(),id);
        jdbc.update("""
                UPDATE business_partner SET code=?,name=?,short_name=?,tax_no=?,updated_by=?,version=version+1
                WHERE tenant_id=? AND id=? AND version=?
                """,input.code().trim(),input.name().trim(),input.shortName(),input.taxNo(),scope.userId(),scope.tenantId(),id,input.version());
        roles(scope,id,input.roles());
        return changed(scope,id,"partner.update",before);
    }

    private void roles(CompanyAccess scope,long id,Set<String> roles) {
        jdbc.update("UPDATE partner_role SET status='disabled',version=version+1,updated_by=? WHERE tenant_id=? AND partner_id=?",
                scope.userId(),scope.tenantId(),id);
        for (String role:roles) {
            jdbc.update("""
                    INSERT INTO partner_role(id,tenant_id,partner_id,role,created_by,updated_by)
                    VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE status='active',updated_by=VALUES(updated_by)
                    """,Ids.next(),scope.tenantId(),id,role,scope.userId(),scope.userId());
        }
    }

    @Transactional
    Map<String,Object> status(CompanyAccess scope,long id,String action,long version) {
        var before=get(scope,id,true); ReferenceRows.version(version,before);
        jdbc.update("UPDATE business_partner SET status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",
                ReferenceRows.status(action),scope.userId(),scope.tenantId(),id);
        return changed(scope,id,"partner."+action,before);
    }

    private Map<String,Object> changed(CompanyAccess scope,long id,String action,Object before) {
        var after=get(scope,id,false); audit.record(scope,action,"partner",id,before,after); return after;
    }

    Map<String,Object> contacts(CompanyAccess scope,long partnerId) {
        get(scope,partnerId,false);
        return Map.of("items",jdbc.queryForList("SELECT id,name,mobile,email,address,is_default,version,area_id FROM partner_contact WHERE tenant_id=? AND partner_id=? ORDER BY is_default DESC,id",
                scope.tenantId(),partnerId).stream().map(ReferenceRows::publicRow).peek(row->hideContact(scope,row)).toList());
    }

    Map<String,Object> contact(CompanyAccess scope,long partnerId,long id) {
        get(scope,partnerId,false);
        var row=ReferenceRows.one(jdbc.queryForList("SELECT id,name,mobile,email,address,is_default,version,area_id FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=?",scope.tenantId(),partnerId,id));
        hideContact(scope,row);return row;
    }

    private void hideContact(CompanyAccess scope,Map<String,Object> row) {
        if (!scope.hasPermission("partner_contact:view")) { row.remove("mobile"); row.remove("address");row.remove("areaId"); }
    }

    @Transactional
    Map<String,Object> saveContact(CompanyAccess scope,long partnerId,Long id,PartnerModels.Contact input) {
        get(scope,partnerId,true);areas.validate(input.areaId());
        boolean creating=id==null;
        if ((input.mobile()!=null || input.address()!=null || input.areaId()!=null) && !scope.hasPermission("partner_contact:view")) {
            throw new ApiException(403,"FORBIDDEN","没有维护联系方式的权限");
        }
        Map<String,Object> before=null;
        if (id != null) {
            before=ReferenceRows.one(jdbc.queryForList("SELECT id,name,mobile,email,address,is_default,version,area_id FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=? FOR UPDATE",
                    scope.tenantId(),partnerId,id)); ReferenceRows.version(input.version(),before);
        }
        if (input.isDefault()) { jdbc.update("UPDATE partner_contact SET is_default=0,version=version+1 WHERE tenant_id=? AND partner_id=? AND is_default=1",scope.tenantId(),partnerId); }
        if (id==null) {
            id=Ids.next();
            jdbc.update("INSERT INTO partner_contact(id,tenant_id,partner_id,name,mobile,email,address,is_default,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
                    id,scope.tenantId(),partnerId,input.name(),input.mobile(),input.email(),input.address(),input.isDefault(),scope.userId());
        } else {
            Object mobile=scope.hasPermission("partner_contact:view")?input.mobile():before.get("mobile");
            Object address=scope.hasPermission("partner_contact:view")?input.address():before.get("address");
            jdbc.update("UPDATE partner_contact SET name=?,mobile=?,email=?,address=?,is_default=?,version=version+1,updated_by=? WHERE tenant_id=? AND partner_id=? AND id=?",
                    input.name(),mobile,input.email(),address,input.isDefault(),scope.userId(),scope.tenantId(),partnerId,id);
        }
        if(scope.hasPermission("partner_contact:view")) jdbc.update("UPDATE partner_contact SET area_id=COALESCE(?,area_id) WHERE tenant_id=? AND partner_id=? AND id=?",input.areaId(),scope.tenantId(),partnerId,id);
        var after=ReferenceRows.one(jdbc.queryForList("SELECT id,name,mobile,email,address,is_default,version,area_id FROM partner_contact WHERE tenant_id=? AND id=?",scope.tenantId(),id));
        // 审计保存非敏感属性，避免从审计权限旁路读取联系方式。
        var safeBefore=before==null?null:new LinkedHashMap<>(before);
        if (safeBefore!=null) { safeBefore.remove("mobile"); safeBefore.remove("address");safeBefore.remove("areaId"); }
        var safeAfter=new LinkedHashMap<>(after);safeAfter.remove("mobile");safeAfter.remove("address");safeAfter.remove("areaId");
        audit.record(scope,creating?"partner_contact.create":"partner_contact.update","partner_contact",id,safeBefore,safeAfter);
        hideContact(scope,after); return after;
    }

    @Transactional
    void deleteContact(CompanyAccess scope,long partnerId,long id,long version) {
        get(scope,partnerId,true);
        var rows=jdbc.queryForList("SELECT id,name,version FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=? FOR UPDATE",scope.tenantId(),partnerId,id);
        if(rows.isEmpty()) {return;}
        var before=ReferenceRows.one(rows);
        ReferenceRows.version(version,before);
        jdbc.update("DELETE FROM partner_contact WHERE tenant_id=? AND partner_id=? AND id=?",scope.tenantId(),partnerId,id);
        audit.record(scope,"partner_contact.delete","partner_contact",id,before,null);
    }
}
