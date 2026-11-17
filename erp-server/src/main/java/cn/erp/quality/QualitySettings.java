package cn.erp.quality;

import cn.erp.identity.CompanyAccess;
import cn.erp.identity.PlatformAccess;
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
import static cn.erp.quality.QualityModels.*;

@Service
@Transactional(readOnly=true)
class QualitySettings {
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    QualitySettings(JdbcTemplate jdbc,AuditService audit){this.jdbc=jdbc;this.audit=audit;}

    List<Map<String,Object>> items(CompanyAccess actor,long before,int limit){
        return page("qc_item",actor,before,limit,false).stream().map(ApiRows::row).toList();
    }
    List<Map<String,Object>> templates(CompanyAccess actor,long before,int limit){
        return page("qc_template",actor,before,limit,false).stream().map(row->template(actor,row)).toList();
    }
    List<Map<String,Object>> rules(CompanyAccess actor,long before,int limit){
        return page("qc_sku_rule",actor,before,limit,true).stream().map(ApiRows::row).toList();
    }

    @Transactional
    Map<String,Object> item(CompanyAccess actor,Long id,Item input){
        PlatformAccess.lock(jdbc,actor,"qc_setting:manage");
        if(input==null)throw invalid("请填写检验项目");
        String code=code(input.code()),name=text(input.name(),80,"项目名称",true);
        String type=option(input.type(),Set.of("quantitative","qualitative"),"检验类型");
        String unit=text(input.unit(),24,"单位",false),method=text(input.method(),200,"检验方法",false);
        String status=status(input.status());
        long key=id==null?Ids.next():id;
        Map<String,Object> old=id==null?null:row("qc_item",actor,id,false,true);
        try {
            if(old==null) jdbc.update("INSERT INTO qc_item(id,tenant_id,code,name,type,unit,method,status) VALUES(?,?,?,?,?,?,?,?)",
                    key,actor.tenantId(),code,name,type,unit,method,status);
            else {
                version(input.version(),old);
                if(!old.get("type").equals(type)&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qc_template_item WHERE tenant_id=? AND item_id=?)",
                        Boolean.class,actor.tenantId(),key)))throw conflict("项目已被模板引用，不能修改检验类型");
                if(status.equals("disabled")&&Boolean.TRUE.equals(jdbc.queryForObject("""
                        SELECT EXISTS(SELECT 1 FROM qc_template_item i JOIN qc_template t ON t.tenant_id=i.tenant_id AND t.id=i.template_id
                          WHERE i.tenant_id=? AND i.item_id=? AND t.status='active')
                        """,Boolean.class,actor.tenantId(),key)))throw conflict("请先从启用的检验模板移除该项目");
                jdbc.update("UPDATE qc_item SET code=?,name=?,type=?,unit=?,method=?,status=?,version=version+1 WHERE tenant_id=? AND id=?",
                        code,name,type,unit,method,status,actor.tenantId(),key);
            }
        }catch(DuplicateKeyException duplicate){throw conflict("检验项目编码已存在");}
        return changed(actor,"qc_item",key,old,false);
    }

    @Transactional
    Map<String,Object> template(CompanyAccess actor,Long id,Template input){
        PlatformAccess.lock(jdbc,actor,"qc_setting:manage");
        if(input==null||input.checks()==null||input.checks().isEmpty()||input.checks().size()>100)throw invalid("模板须配置1至100个项目");
        String code=code(input.code()),name=text(input.name(),80,"模板名称",true);
        String sampling=option(input.sampling(),Set.of("full","fixed_qty","percent"),"抽样方式"),status=status(input.status());
        BigDecimal size=sampling.equals("full")?BigDecimal.ZERO:number(input.samplingValue(),"抽样值",false);
        if(!sampling.equals("full")&&(size.signum()<=0||sampling.equals("percent")&&size.compareTo(BigDecimal.valueOf(100))>0))
            throw invalid("抽样值须为正数，百分比不能超过100");
        long key=id==null?Ids.next():id;
        Map<String,Object> old=id==null?null:row("qc_template",actor,id,false,true);
        var checks=checks(actor,key,input.checks());
        try {
            if(old==null)jdbc.update("INSERT INTO qc_template(id,tenant_id,code,name,sampling,sampling_value,status) VALUES(?,?,?,?,?,?,?)",
                    key,actor.tenantId(),code,name,sampling,size,status);
            else {
                version(input.version(),old);
                if(status.equals("disabled")&&Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM qc_sku_rule WHERE tenant_id=? AND template_id=? AND inspection_required=1 AND status='active')",
                        Boolean.class,actor.tenantId(),key)))throw conflict("请先停用引用模板的有效检验规则");
                jdbc.update("UPDATE qc_template SET code=?,name=?,sampling=?,sampling_value=?,status=?,version=version+1 WHERE tenant_id=? AND id=?",
                        code,name,sampling,size,status,actor.tenantId(),key);
                jdbc.update("DELETE FROM qc_template_item WHERE tenant_id=? AND template_id=?",actor.tenantId(),key);
            }
        }catch(DuplicateKeyException duplicate){throw conflict("检验模板编码已存在");}
        jdbc.batchUpdate("INSERT INTO qc_template_item(tenant_id,template_id,item_id,seq_no,standard_value,lower_limit,upper_limit,required) VALUES(?,?,?,?,?,?,?,?)",checks);
        var result=template(actor,row("qc_template",actor,key,false,false));
        audit.record(actor,"qc_template.save","qc_template",key,old==null?null:ApiRows.row(old),result);
        return result;
    }

    @Transactional
    Map<String,Object> rule(CompanyAccess actor,Long id,Rule input){
        if(input==null||input.inspectionRequired()==null)throw invalid("请选择是否检验");
        if(!actor.allWarehouses()||!actor.orgScope().allDepartments())throw forbidden();
        String type=option(input.targetType(),Set.of("sku","category"),"规则对象类型"),status=status(input.status());
        long target=id(input.targetId()),supplier=blank(input.supplierId())||"0".equals(input.supplierId())?0:id(input.supplierId());
        Long template=blank(input.templateId())?null:id(input.templateId());
        if(input.inspectionRequired()&&template==null)throw invalid("请选择检验模板");
        String table=type.equals("sku")?"sku":"product_category";
        active(actor,table,target);
        if(supplier>0&&!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM business_partner p JOIN partner_role r ON r.tenant_id=p.tenant_id AND r.partner_id=p.id
                WHERE p.tenant_id=? AND p.id=? AND p.status='active' AND r.role='supplier' AND r.status='active')
                """,Boolean.class,actor.tenantId(),supplier)))throw missing();
        if(template!=null)active(actor,"qc_template",template);
        long key=id==null?Ids.next():id;
        var old=id==null?null:row("qc_sku_rule",actor,id,true,true);
        try {
            if(old==null)jdbc.update("INSERT INTO qc_sku_rule(id,tenant_id,company_id,target_type,target_id,supplier_id,template_id,inspection_required,status) VALUES(?,?,?,?,?,?,?,?,?)",
                    key,actor.tenantId(),actor.companyId(),type,target,supplier,template,input.inspectionRequired(),status);
            else {
                version(input.version(),old);
                jdbc.update("UPDATE qc_sku_rule SET target_type=?,target_id=?,supplier_id=?,template_id=?,inspection_required=?,status=?,version=version+1 WHERE tenant_id=? AND company_id=? AND id=?",
                        type,target,supplier,template,input.inspectionRequired(),status,actor.tenantId(),actor.companyId(),key);
            }
        }catch(DuplicateKeyException duplicate){throw conflict("该对象与供应商已有检验规则");}
        return changed(actor,"qc_sku_rule",key,old,true);
    }

    Snapshot matching(CompanyAccess actor,long sku,long supplier){
        var matches=jdbc.queryForList("""
                SELECT r.inspection_required,r.template_id FROM qc_sku_rule r
                WHERE r.tenant_id=? AND r.company_id=? AND r.status='active' AND r.supplier_id IN (0,?)
                AND ((r.target_type='sku' AND r.target_id=?) OR (r.target_type='category' AND EXISTS(
                  SELECT 1 FROM sku s JOIN product p ON p.tenant_id=s.tenant_id AND p.id=s.product_id
                  WHERE s.tenant_id=r.tenant_id AND s.id=? AND p.category_id=r.target_id)))
                ORDER BY (r.target_type='sku') DESC,(r.supplier_id<>0) DESC LIMIT 1 FOR SHARE
                """,actor.tenantId(),actor.companyId(),supplier,sku,sku);
        if(matches.isEmpty()||!flag(matches.getFirst().get("inspection_required")))return null;
        var header=jdbc.queryForMap("SELECT * FROM qc_template WHERE tenant_id=? AND id=? FOR SHARE",actor.tenantId(),id(matches.getFirst(),"template_id"));
        if(!"active".equals(header.get("status")))throw conflict("来料检规则的模板已停用");
        var criteria=criteria(actor,id(header,"id"),true);
        if(criteria.stream().anyMatch(item->item.required()&&item.standardValue().isBlank()))throw conflict("来料检模板缺少必检标准");
        return new Snapshot(header.get("id").toString(),header.get("code").toString(),header.get("name").toString(),
                header.get("sampling").toString(),header.get("sampling_value").toString(),criteria);
    }

    private List<Object[]> checks(CompanyAccess actor,long key,List<Check> checks){
        var seen=new HashSet<Long>();var rows=new ArrayList<Object[]>();
        for(var input:checks){
            if(input==null)throw invalid("模板项目不能为空");
            long item=id(input.itemId());
            if(!seen.add(item))throw invalid("模板项目不能重复");
            var criterion=row("qc_item",actor,item,false,false);
            if(!"active".equals(criterion.get("status")))throw invalid("检验项目已停用");
            String standard=text(input.standardValue(),160,"判定标准",true);
            BigDecimal lower=optionalNumber(input.lowerLimit(),"下限",true),upper=optionalNumber(input.upperLimit(),"上限",true);
            boolean numeric="quantitative".equals(criterion.get("type"));
            if(numeric&&lower==null&&upper==null||!numeric&&(lower!=null||upper!=null)||lower!=null&&upper!=null&&lower.compareTo(upper)>0)
                throw invalid("数值项目须设置有效的判定上下限");
            rows.add(new Object[]{actor.tenantId(),key,item,rows.size()+1,standard,lower,upper,input.required()==null||input.required()});
        }
        return rows;
    }

    private List<Criterion> criteria(CompanyAccess actor,long id,boolean lock){
        return jdbc.query("""
                SELECT c.*,i.code,i.name,i.type,i.unit,i.method FROM qc_template_item c
                JOIN qc_item i ON i.tenant_id=c.tenant_id AND i.id=c.item_id
                WHERE c.tenant_id=? AND c.template_id=? ORDER BY c.seq_no,c.item_id
                """+(lock?" FOR SHARE":""),(rs,index)->new Criterion(rs.getString("item_id"),rs.getString("code"),rs.getString("name"),rs.getString("type"),rs.getString("unit"),
                rs.getString("method"),rs.getString("standard_value"),rs.getString("lower_limit"),rs.getString("upper_limit"),rs.getBoolean("required")),actor.tenantId(),id);
    }

    private Map<String,Object> template(CompanyAccess actor,Map<String,Object> row){
        var result=ApiRows.row(row);result.put("checks",criteria(actor,id(row,"id"),false));return result;
    }
    private Map<String,Object> changed(CompanyAccess actor,String table,long key,Map<String,Object> old,boolean company){
        var result=ApiRows.row(row(table,actor,key,company,false));
        audit.record(actor,table+".save",table,key,old==null?null:ApiRows.row(old),result);return result;
    }
    private Map<String,Object> row(String table,CompanyAccess actor,long id,boolean company,boolean lock){
        String sql="SELECT * FROM "+table+" WHERE tenant_id=? AND id=?"+(company?" AND company_id=?":"")+(lock?" FOR UPDATE":"");
        var rows=company?jdbc.queryForList(sql,actor.tenantId(),id,actor.companyId()):jdbc.queryForList(sql,actor.tenantId(),id);
        return rows.stream().findFirst().orElseThrow(QualityModels::missing);
    }
    private List<Map<String,Object>> page(String table,CompanyAccess actor,long before,int limit,boolean company){
        if(limit<1||limit>200||before<=0)throw invalid("分页条件无效");
        if(company&&!actor.orgScope().allDepartments())throw forbidden();
        String sql="SELECT * FROM "+table+" WHERE tenant_id=? AND id<?"+(company?" AND company_id=?":"")+" ORDER BY id DESC LIMIT ?";
        return company?jdbc.queryForList(sql,actor.tenantId(),before,actor.companyId(),limit):jdbc.queryForList(sql,actor.tenantId(),before,limit);
    }
    private void active(CompanyAccess actor,String table,long key){
        if(jdbc.queryForList("SELECT id FROM "+table+" WHERE tenant_id=? AND id=? AND status='active' FOR SHARE",Long.class,actor.tenantId(),key).isEmpty())
            throw new ApiException(404,"NOT_FOUND","规则引用的资料不存在或已停用");
    }
    static boolean flag(Object value){return value instanceof Boolean b?b:((Number)value).intValue()!=0;}
}
