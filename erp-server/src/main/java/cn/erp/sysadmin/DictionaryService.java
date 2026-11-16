package cn.erp.sysadmin;

import cn.erp.identity.*;
import cn.erp.shared.*;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static cn.erp.sysadmin.PlatformRows.*;

@Service
@Transactional(readOnly=true)
public class DictionaryService {
    public record TypeInput(@NotBlank(message="请输入字典编码") @Pattern(regexp="[a-z][a-z0-9_]{0,63}",message="字典编码须为小写字母、数字和下划线") String code,@NotBlank(message="请输入字典名称") @Size(max=100,message="字典名称最多100字") String name,@Pattern(regexp="active|disabled",message="状态无效") String status,Long version) {}
    public record ItemInput(@NotBlank(message="请选择字典") String typeCode,@NotBlank(message="请输入字典值") @Size(max=64,message="字典值最多64字") String value,@NotBlank(message="请输入显示文字") @Size(max=100,message="显示文字最多100字") String label,@Pattern(regexp="neutral|primary|success|warning|danger",message="颜色无效") String color,Integer sortNo,@Pattern(regexp="active|disabled",message="状态无效") String status,Long version) {}
    private final JdbcTemplate jdbc;
    private final BusinessCommands commands;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    public DictionaryService(JdbcTemplate jdbc,BusinessCommands commands,AuditService audit,ApplicationEventPublisher events) {this.jdbc=jdbc;this.commands=commands;this.audit=audit;this.events=events;}
    public Map<String,Object> types(CompanyAccess actor,String keyword,Long cursor,int limit) {
        PlatformAccess.require(jdbc,actor,"dict:view");long before=cursor(cursor,limit);
        if(keyword.length()>100) throw invalid("搜索内容最多100字");
        return page(jdbc.queryForList("SELECT *,tenant_id=0 system_item FROM sys_dict_type WHERE tenant_id IN(0,?) AND id<? AND (LOCATE(?,code)>0 OR LOCATE(?,name)>0) ORDER BY id DESC LIMIT ?",actor.tenantId(),before,keyword,keyword,limit+1),limit);
    }
    public Map<String,Object> items(CompanyAccess actor,String type,Long cursor,int limit) {
        PlatformAccess.require(jdbc,actor,"dict:view");long before=cursor(cursor,limit);requireType(actor,type,false);
        return page(jdbc.queryForList("SELECT i.*,i.tenant_id=0 system_item,IF(COALESCE(o.disabled,0)=1,'disabled',i.status) effective_status,IF(i.tenant_id=0,COALESCE(o.version,0),i.version) edit_version FROM sys_dict_item i LEFT JOIN sys_dict_item_override o ON o.tenant_id=? AND o.item_id=i.id WHERE i.tenant_id IN(0,?) AND i.type_code=? AND i.id<? ORDER BY i.id DESC LIMIT ?",actor.tenantId(),actor.tenantId(),type,before,limit+1),limit);
    }
    public Map<String,Object> dictionaries(CompanyAccess actor,String types,String known) {
        var codes=Arrays.stream(types.split(",")).map(String::strip).filter(s->!s.isBlank()).distinct().toList();
        if(codes.isEmpty()||codes.size()>30||codes.stream().anyMatch(c->!c.matches("[a-z][a-z0-9_]{0,63}"))) throw invalid("请指定1到30个有效字典编码");
        String revision=jdbc.queryForObject("SELECT COALESCE(SUM(version),0) FROM sys_dict_revision WHERE tenant_id IN(0,?)",String.class,actor.tenantId());
        if(revision.equals(known)) return Map.of("version",revision,"unchanged",true);
        var data=new LinkedHashMap<String,Object>();
        for(String code:codes) {
            if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_dict_type WHERE tenant_id IN(0,?) AND code=? AND status='active'",Long.class,actor.tenantId(),code)==0) {data.put(code,List.of());continue;}
            var rows=jdbc.queryForList("SELECT CAST(i.id AS CHAR) id,i.value,i.label,i.color,i.sort_no,IF(i.status='active' AND COALESCE(o.disabled,0)=0,1,0) enabled FROM sys_dict_item i LEFT JOIN sys_dict_item_override o ON o.tenant_id=? AND o.item_id=i.id WHERE i.tenant_id IN(0,?) AND i.type_code=? ORDER BY i.sort_no,i.id",actor.tenantId(),actor.tenantId(),code);
            data.put(code,rows.stream().map(ApiRows::row).toList());
        }
        return Map.of("version",revision,"unchanged",false,"items",data);
    }
    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> saveType(CompanyAccess actor,Long id,String key,TypeInput input) {
        PlatformAccess.lock(jdbc,actor,id==null?"dict:create":"dict:update");
        if(input.code().endsWith("_status")||input.code().endsWith("_state")||Set.of("status","state","approval","posting","shipment_status").contains(input.code())) throw invalid("审批、过账、签收等业务状态由系统固定维护，不能建立状态字典");
        return commands.execute(actor,"dict_type."+(id==null?"create":"update"),key,Arrays.asList(id,input),200,()->{
            long target=id==null?Ids.next():id;
            var before=id==null?null:type(actor,id);
            if(before!=null) {
                if(((Number)before.get("tenant_id")).longValue()==0) throw new ApiException(422,"SYSTEM_ITEM_IMMUTABLE","系统预置字典不能修改");
                version(input.version(),before.get("version"));
                if(!before.get("code").equals(input.code())) throw invalid("字典编码不能修改");
            } else if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_dict_type WHERE tenant_id IN(0,?) AND code=?",Long.class,actor.tenantId(),input.code())>0) throw invalid("字典编码已存在");
            if(id==null) jdbc.update("INSERT INTO sys_dict_type(id,tenant_id,code,name,status,created_by,updated_by) VALUES(?,?,?,?,?,?,?)",target,actor.tenantId(),input.code(),input.name(),state(input.status()),actor.userId(),actor.userId());
            else jdbc.update("UPDATE sys_dict_type SET name=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",input.name(),state(input.status()),actor.userId(),actor.tenantId(),target);
            changed(actor);var after=type(actor,target);audit.record(actor,"dict_type.save","sys_dict_type",target,before,after);return ApiRows.row(after);
        },(Class)Map.class);
    }
    @Transactional
    @SuppressWarnings({"unchecked","rawtypes"})
    public Map<String,Object> saveItem(CompanyAccess actor,Long id,String key,ItemInput input) {
        PlatformAccess.lock(jdbc,actor,id==null?"dict:create":"dict:update");requireType(actor,input.typeCode(),true);
        if(input.sortNo()!=null&&(input.sortNo()<0||input.sortNo()>1_000_000)) throw invalid("排序须在0到1000000之间");
        return commands.execute(actor,"dict_item."+(id==null?"create":"update"),key,Arrays.asList(id,input),200,()->{
            long target=id==null?Ids.next():id;var before=id==null?null:item(actor,id);
            if(before!=null&&!before.get("type_code").equals(input.typeCode())) throw missing();
            if(before!=null&&((Number)before.get("tenant_id")).longValue()==0) {
                if(!before.get("value").equals(input.value())||!before.get("label").equals(input.label())||!before.get("color").equals(input.color()==null?"neutral":input.color())||((Number)before.get("sort_no")).intValue()!=(input.sortNo()==null?0:input.sortNo())) throw new ApiException(422,"SYSTEM_ITEM_IMMUTABLE","预置字典项只能启用或停用，不能修改内容");
                long current=jdbc.queryForObject("SELECT COALESCE(MAX(version),0) FROM sys_dict_item_override WHERE tenant_id=? AND item_id=?",Long.class,actor.tenantId(),id);version(input.version(),current);
                jdbc.update("INSERT INTO sys_dict_item_override(tenant_id,item_id,disabled,version) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE disabled=VALUES(disabled),version=version+1",actor.tenantId(),id,"disabled".equals(input.status()));
            } else {
                if(before!=null) {version(input.version(),before.get("version"));if(!before.get("value").equals(input.value())) throw invalid("字典值不能修改，历史引用需要保留");}
                else if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_dict_item WHERE tenant_id IN(0,?) AND type_code=? AND value=?",Long.class,actor.tenantId(),input.typeCode(),input.value())>0) throw invalid("字典值已存在");
                if(id==null) jdbc.update("INSERT INTO sys_dict_item(id,tenant_id,type_code,value,label,color,sort_no,status,created_by,updated_by) VALUES(?,?,?,?,?,?,?,?,?,?)",target,actor.tenantId(),input.typeCode(),input.value(),input.label(),input.color()==null?"neutral":input.color(),input.sortNo()==null?0:input.sortNo(),state(input.status()),actor.userId(),actor.userId());
                else jdbc.update("UPDATE sys_dict_item SET label=?,color=?,sort_no=?,status=?,version=version+1,updated_by=? WHERE tenant_id=? AND id=?",input.label(),input.color()==null?"neutral":input.color(),input.sortNo()==null?0:input.sortNo(),state(input.status()),actor.userId(),actor.tenantId(),id);
            }
            changed(actor);var after=item(actor,target);audit.record(actor,"dict_item.save","sys_dict_item",target,before,after);return ApiRows.row(after);
        },(Class)Map.class);
    }
    public void validateValue(long tenant,String type,String value,boolean unchanged) {
        if(value==null||unchanged) return;
        jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR SHARE",Long.class,tenant);
        if(jdbc.queryForList("SELECT i.id FROM sys_dict_item i JOIN sys_dict_type t ON t.code=i.type_code AND t.tenant_id IN(0,?) AND t.status='active' LEFT JOIN sys_dict_item_override o ON o.tenant_id=? AND o.item_id=i.id WHERE i.tenant_id IN(0,?) AND i.type_code=? AND i.value=? AND i.status='active' AND COALESCE(o.disabled,0)=0 FOR SHARE",Long.class,tenant,tenant,tenant,type,value).isEmpty()) throw invalid("字典项不存在或已停用，请重新选择");
    }
    @Transactional public Map<String,Object> delete(CompanyAccess actor,boolean type,long id,long expected,String key) {
        PlatformAccess.lock(jdbc,actor,"dict:delete");
        return commands.execute(actor,type?"dict_type.delete":"dict_item.delete",key,Map.of("id",id,"version",expected),200,()->{
            var row=type?type(actor,id):item(actor,id);if(((Number)row.get("tenant_id")).longValue()==0) throw new ApiException(422,"SYSTEM_ITEM_IMMUTABLE","系统预置字典不能删除");version(expected,row.get("version"));
            String code=row.get(type?"code":"type_code").toString();
            if(type&&!jdbc.queryForList("SELECT id FROM sys_dict_item WHERE tenant_id=? AND type_code=? LIMIT 1 FOR SHARE",Long.class,actor.tenantId(),code).isEmpty()) throw invalid("字典仍有字典项，请先处理字典项");
            if(!type&&code.equals("customer_source")&&!jdbc.queryForList("SELECT id FROM business_partner WHERE tenant_id=? AND source_code=? LIMIT 1 FOR SHARE",Long.class,actor.tenantId(),row.get("value")).isEmpty()) throw invalid("字典项已被往来单位引用，请使用停用保留历史名称");
            jdbc.update("DELETE FROM "+(type?"sys_dict_type":"sys_dict_item")+" WHERE tenant_id=? AND id=?",actor.tenantId(),id);changed(actor);audit.record(actor,"dict.delete",type?"sys_dict_type":"sys_dict_item",id,row,null);return Map.<String,Object>of("id",Long.toString(id),"deleted",true);
        },Map.class);
    }
    private Map<String,Object> type(CompanyAccess actor,long id) {var rows=jdbc.queryForList("SELECT * FROM sys_dict_type WHERE tenant_id IN(0,?) AND id=?",actor.tenantId(),id);if(rows.isEmpty()) throw missing();return rows.getFirst();}
    private Map<String,Object> item(CompanyAccess actor,long id) {var rows=jdbc.queryForList("SELECT * FROM sys_dict_item WHERE tenant_id IN(0,?) AND id=?",actor.tenantId(),id);if(rows.isEmpty()) throw missing();return rows.getFirst();}
    private void requireType(CompanyAccess actor,String code,boolean active) {if(jdbc.queryForObject("SELECT COUNT(*) FROM sys_dict_type WHERE tenant_id IN(0,?) AND code=?"+(active?" AND status='active'":""),Long.class,actor.tenantId(),code)==0) throw missing();}
    private void changed(CompanyAccess actor) {jdbc.update("INSERT INTO sys_dict_revision(tenant_id,version) VALUES(?,1) ON DUPLICATE KEY UPDATE version=version+1",actor.tenantId());events.publishEvent(new UserSignal(actor.tenantId(),0,"dict.changed",Map.of()));}
    private String state(String value) {return value==null?"active":value;}
}
