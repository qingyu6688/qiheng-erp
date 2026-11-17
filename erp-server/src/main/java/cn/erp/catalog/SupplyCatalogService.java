package cn.erp.catalog;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.ApiRows;
import cn.erp.shared.AuditService;
import cn.erp.shared.Ids;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class SupplyCatalogService {
    private static final Set<String> STRATEGIES=Set.of("fifo","fefo","manual");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    SupplyCatalogService(JdbcTemplate jdbc,AuditService audit) {this.jdbc=jdbc;this.audit=audit;}

    record BrandInput(String id,String code,String name,int sortNo,boolean enabled,Long version) {}
    record SkuInput(BigDecimal weightKg,String brandId,boolean batchManaged,boolean serialManaged,
                    int shelfLifeDays,int nearExpiryDays,String issueStrategy,long version) {}

    Map<String,Object> sku(CompanyAccess actor,long id) {return ApiRows.row(sku(actor,id,false));}

    @Transactional
    Map<String,Object> saveSku(CompanyAccess actor,long id,SkuInput input) {
        var before=sku(actor,id,true);
        if(input.version()!=((Number)before.get("version")).longValue()) throw conflict("SKU 已更新，请刷新后重试");
        if (input.issueStrategy()==null||!STRATEGIES.contains(input.issueStrategy())||input.shelfLifeDays()<0||input.shelfLifeDays()>36500
                ||input.nearExpiryDays()<0||input.nearExpiryDays()>36500
                ||input.weightKg()!=null&&(input.weightKg().signum()<0||input.weightKg().scale()>6||input.weightKg().precision()-input.weightKg().scale()>18))
            throw invalid("重量、保质期、临期天数或出库策略不符合要求");
        if(!input.batchManaged()&&(input.shelfLifeDays()>0||input.nearExpiryDays()>0||"fefo".equals(input.issueStrategy())))
            throw invalid("请先启用批次管理再配置效期与近效期先出");
        if(input.shelfLifeDays()>0&&input.nearExpiryDays()>input.shelfLifeDays()) throw invalid("临期提前天数不能超过保质期");
        Long brand=id(input.brandId());
        if(!Objects.toString(before.get("brand_id"),"").equals(Objects.toString(brand,""))&&!actor.hasPermission("brand:view"))
            throw new ApiException(403,"FORBIDDEN","没有查看品牌的权限");
        if(brand!=null) {
            var choice=one("SELECT id,status FROM catalog_brand WHERE tenant_id=? AND id=? FOR SHARE",actor.tenantId(),brand);
            if(!"active".equals(choice.get("status"))&&!Objects.toString(before.get("brand_id"),"").equals(brand.toString()))
                throw invalid("请选择启用的品牌");
        }
        if(input.serialManaged()&&!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM unit_conversion WHERE tenant_id=? AND sku_id=? AND is_base=1 AND rate_to_base=1)
                """,Boolean.class,actor.tenantId(),id))) throw invalid("序列号管理需要已配置的基础单位");
        if(input.batchManaged()!=flag(before,"batch_managed")||input.serialManaged()!=flag(before,"serial_managed")) {
            var quantities=jdbc.queryForList("""
                    SELECT on_hand_qty,reserved_qty FROM inventory_balance
                    WHERE tenant_id=? AND sku_id=? AND location_key='' AND lot_key='' ORDER BY company_id,warehouse_id FOR UPDATE
                    """,actor.tenantId(),id);
            if(quantities.stream().anyMatch(row->((BigDecimal)row.get("on_hand_qty")).signum()!=0||((BigDecimal)row.get("reserved_qty")).signum()!=0))
                throw conflict("变更批次或序列号管理前请清空该 SKU 的存量与预留");
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM inv_serial WHERE tenant_id=? AND sku_id=? AND status IN ('in_transit','reserved'))
                    """,Boolean.class,actor.tenantId(),id))) throw conflict("还有在途或预留序列号，请先处理相关单据");
            if(hasOpenLoans(actor.tenantId(),id)) throw conflict("该 SKU 还有借出未归还数量，请先处理相关单据");
        }
        jdbc.update("""
                UPDATE sku SET weight_kg=?,brand_id=?,batch_managed=?,serial_managed=?,shelf_life_days=?,near_expiry_days=?,issue_strategy=?,
                    version=version+1,updated_by=? WHERE tenant_id=? AND id=?
                """,input.weightKg(),brand,input.batchManaged(),input.serialManaged(),input.shelfLifeDays(),input.nearExpiryDays(),
                input.issueStrategy(),actor.userId(),actor.tenantId(),id);
        var after=sku(actor,id,false);
        audit.record(actor,"sku_tracking.save","sku",id,before,after);
        return ApiRows.row(after);
    }

    private boolean hasOpenLoans(long tenant,long sku) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM stock_misc_line l JOIN stock_misc_doc loan
                    ON loan.tenant_id=l.tenant_id AND loan.company_id=l.company_id AND loan.id=l.doc_id
                    WHERE l.tenant_id=? AND l.sku_id=? AND loan.purpose='loan' AND loan.status='posted'
                    AND l.base_qty>COALESCE((
                        SELECT SUM(r.base_qty) FROM stock_misc_line r JOIN stock_misc_doc returned
                          ON returned.tenant_id=r.tenant_id AND returned.company_id=r.company_id AND returned.id=r.doc_id
                        WHERE r.tenant_id=l.tenant_id AND r.company_id=l.company_id AND r.original_loan_line_id=l.id AND returned.status='posted'
                    ),0))
                """,Boolean.class,tenant,sku));
    }

    List<Map<String,Object>> brands(CompanyAccess actor,String keyword,boolean onlyActive) {
        String search=keyword==null?"":keyword.strip();
        if(search.length()>100)throw invalid("品牌搜索词过长");
        return jdbc.queryForList("""
                SELECT id,code,name,sort_no,status,version FROM catalog_brand
                WHERE tenant_id=? AND (?=0 OR status='active') AND (LOCATE(?,code)>0 OR LOCATE(?,name)>0)
                ORDER BY sort_no,code,id LIMIT 200
                """,actor.tenantId(),onlyActive,search,search).stream().map(ApiRows::row).toList();
    }

    @Transactional
    Map<String,Object> saveBrand(CompanyAccess actor,BrandInput input) {
        String code=input.code()==null?"":input.code().strip(),name=input.name()==null?"":input.name().strip();
        if(!code.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,31}")||name.isBlank()||name.length()>100||name.indexOf(0)>=0||input.sortNo()<0)
            throw invalid("品牌编码、名称或排序无效");
        Long existing=id(input.id());long key=existing==null?Ids.next():existing;
        var before=existing==null?null:brand(actor,existing);
        String state=input.enabled()?"active":"disabled";
        try {
            if(existing==null) jdbc.update("INSERT INTO catalog_brand(id,tenant_id,code,name,sort_no,status) VALUES(?,?,?,?,?,?)",key,actor.tenantId(),code,name,input.sortNo(),state);
            else if(jdbc.update("""
                    UPDATE catalog_brand SET code=?,name=?,sort_no=?,status=?,version=version+1 WHERE tenant_id=? AND id=? AND version=?
                    """,code,name,input.sortNo(),state,actor.tenantId(),key,input.version())!=1) throw conflict("品牌已更新，请刷新后重试");
        } catch(DuplicateKeyException duplicate) {throw conflict("品牌编码已存在");}
        var after=brand(actor,key);
        audit.record(actor,"brand.save","catalog_brand",key,before,after);
        return ApiRows.row(after);
    }

    private Map<String,Object> sku(CompanyAccess actor,long id,boolean lock) {
        return one("SELECT id,code,name,weight_kg,brand_id,batch_managed,serial_managed,shelf_life_days,near_expiry_days,issue_strategy,version "
                +"FROM sku WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""),actor.tenantId(),id);
    }
    private Map<String,Object> brand(CompanyAccess actor,long id) {
        return one("SELECT id,code,name,sort_no,status,version FROM catalog_brand WHERE tenant_id=? AND id=?",actor.tenantId(),id);
    }
    private Map<String,Object> one(String sql,Object... params) {
        var items=jdbc.queryForList(sql,params);
        if(items.isEmpty()) throw new ApiException(404,"NOT_FOUND","商品或品牌不存在或无权查看");
        return items.getFirst();
    }
    private static boolean flag(Map<String,Object> row,String key) {return Boolean.TRUE.equals(row.get(key))||"1".equals(String.valueOf(row.get(key)));}
    private static Long id(String value) {
        if(value==null||value.isBlank())return null;
        try {long number=Long.parseLong(value);if(number>0&&Long.toString(number).equals(value))return number;}
        catch(NumberFormatException bad) {throw invalid("编号无效");}
        throw invalid("编号无效");
    }
    private static ApiException invalid(String text) {return new ApiException(400,"VALIDATION_FAILED",text);}
    private static ApiException conflict(String text) {return new ApiException(409,"SUPPLY_CATALOG_CONFLICT",text);}
}
