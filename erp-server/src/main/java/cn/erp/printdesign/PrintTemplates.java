package cn.erp.printdesign;

import cn.erp.documents.DocumentAccess;
import cn.erp.documents.PrintService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.AuditService;
import cn.erp.shared.BusinessCommands;
import cn.erp.shared.Ids;
import cn.erp.workbench.DocumentBrowser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.printdesign.PrintFields.invalid;
import static cn.erp.printdesign.PrintLayout.*;

@Service
class PrintTemplates {
    static final String VIEW="print_template:view";
    static final String MANAGE="print_template:manage";
    static final String TENANT_MANAGE="print_template:tenant_manage";
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final PrintFields fields;
    private final PrintService printing;
    private final PrintRendering rendering;
    private final AuditService audit;
    private final BusinessCommands commands;
    private final DocumentBrowser browser;

    PrintTemplates(JdbcTemplate jdbc,ObjectMapper mapper,PrintFields fields,PrintService printing,PrintRendering rendering,
                   AuditService audit,BusinessCommands commands,DocumentBrowser browser) {
        this.jdbc=jdbc;this.mapper=mapper;this.fields=fields;this.printing=printing;this.rendering=rendering;this.audit=audit;this.commands=commands;this.browser=browser;
    }

    record Template(String id,String documentType,String name,boolean shared,String status,boolean isDefault,long version,Layout layout) {
        long key(){return Long.parseLong(id);}
    }
    record Summary(String id,String documentType,String name,boolean shared,String status,boolean isDefault,long version) {}
    record Output(List<Summary> templates,String templateId,Long version,PrintRendering.Model model) {}

    @Transactional(readOnly=true)
    List<PrintFields.Document> documents(CompanyAccess actor) {
        DocumentAccess.require(actor,VIEW);
        return fields.documents(actor);
    }

    @Transactional(readOnly=true)
    List<PrintFields.Field> definitions(CompanyAccess actor,String type) {
        DocumentAccess.require(actor,VIEW);
        return fields.fields(actor,type,true);
    }

    @Transactional(readOnly=true)
    List<Map<String,Object>> choices(CompanyAccess actor,String type,String keyword) {
        DocumentAccess.require(actor,VIEW);
        fields.requirePrint(actor,type);
        var kind=DocumentBrowser.SPECS.entrySet().stream().filter(e->e.getValue().type().equals(type)).map(Map.Entry::getKey).findFirst().orElseThrow();
        var query=new DocumentBrowser.Filter(keyword,null,null,null,null,null,null,null,false);
        var page=browser.page(actor,kind,query,null,25,false);
        var result=new ArrayList<Map<String,Object>>();
        if(page.get("items") instanceof List<?> rows) for(Object item:rows) {
            if(!(item instanceof Map<?,?> row) || row.get("id")==null) continue;
            String id=row.get("id").toString();
            browser.visibleNumber(actor,kind,Long.parseLong(id)).ifPresent(number->result.add(Map.of("id",id,"documentNo",number)));
        }
        return List.copyOf(result);
    }

    @Transactional(readOnly=true)
    Map<String,Object> list(CompanyAccess actor,String type,Long cursor,int limit) {
        DocumentAccess.require(actor,VIEW);
        if(cursor!=null && cursor<=0 || limit<1 || limit>100) throw invalid("模板分页参数无效");
        if(type!=null) fields.requirePrint(actor,type);
        List<String> allowed=type==null?fields.documents(actor).stream().map(PrintFields.Document::id).toList():List.of(type);
        var result=new LinkedHashMap<String,Object>();
        result.put("items",List.of());result.put("nextCursor",null);
        if(allowed.isEmpty()) return result;
        var params=new ArrayList<Object>(List.of(actor.tenantId(),actor.companyId()));
        params.addAll(allowed);params.add(cursor==null?Long.MAX_VALUE:cursor);params.add(limit+1);
        var rows=query("""
                SELECT id,company_id,document_type,name,is_default,status,version FROM print_template
                WHERE tenant_id=? AND company_id IN (0,?) AND document_type IN (%s) AND id<? ORDER BY id DESC LIMIT ?
                """.formatted(String.join(",",Collections.nCopies(allowed.size(),"?"))),false,params.toArray());
        int size=Math.min(rows.size(),limit);
        result.put("items",rows.subList(0,size).stream().map(PrintTemplates::summary).toList());
        if(rows.size()>limit) result.put("nextCursor",rows.get(size-1).id());
        return result;
    }

    @Transactional(readOnly=true)
    Template detail(CompanyAccess actor,long id) {
        DocumentAccess.require(actor,VIEW);
        var template=find(actor,id,false);fields.requirePrint(actor,template.documentType());
        return template;
    }

    @Transactional
    Template save(CompanyAccess actor,Long id,Input input) {
        DocumentAccess.require(actor,VIEW);DocumentAccess.require(actor,MANAGE);
        if(input==null || input.name()==null || input.name().isBlank() || input.name().strip().length()>100
                || !List.of("draft","active","disabled").contains(input.status()==null?"":input.status()))
            throw invalid("请输入有效的模板名称和状态");
        var definitions=fields.fields(actor,input.documentType(),true);
        validateLayout(input.layout(),definitions,true);
        boolean shared=Boolean.TRUE.equals(input.shared()), primary=Boolean.TRUE.equals(input.isDefault());
        if(primary && !input.status().equals("active")) throw invalid("启用的模板才能设为默认");
        requireWriteScope(actor,shared);
        jdbc.queryForObject("SELECT id FROM tenant WHERE id=? FOR UPDATE",Long.class,actor.tenantId());
        var previous=id==null?null:find(actor,id,true);
        if(previous!=null && (previous.shared()!=shared || !previous.documentType().equals(input.documentType())))
            throw invalid("已有模板不能更改单据类型或适用范围，请新建模板");
        if(previous==null?input.version()!=null:input.version()==null || previous.version()!=input.version())
            throw new ApiException(409,"VERSION_CONFLICT","模板已被修改，请刷新后再保存");
        long company=shared?0:actor.companyId(), target=id==null?Ids.next():id;
        if(primary) jdbc.update("""
                UPDATE print_template SET is_default=0,version=version+1,updated_by=?,updated_at=CURRENT_TIMESTAMP(3)
                WHERE tenant_id=? AND company_id=? AND document_type=? AND id<>? AND is_default=1
                """,actor.userId(),actor.tenantId(),company,input.documentType(),target);
        String raw=mapper.writeValueAsString(input.layout());
        try {
            if(previous==null) jdbc.update("""
                    INSERT INTO print_template(id,tenant_id,company_id,document_type,name,layout_json,is_default,status,created_by,updated_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,target,actor.tenantId(),company,input.documentType(),input.name().strip(),raw,primary,input.status(),actor.userId(),actor.userId());
            else jdbc.update("""
                    UPDATE print_template SET name=?,layout_json=?,is_default=?,status=?,version=version+1,updated_by=?
                    WHERE tenant_id=? AND company_id=? AND id=?
                    """,input.name().strip(),raw,primary,input.status(),actor.userId(),actor.tenantId(),company,target);
        } catch(DuplicateKeyException conflict) {throw new ApiException(409,"PRINT_TEMPLATE_EXISTS","该范围已有同名打印模板");}
        var saved=find(actor,target,false);
        audit.record(actor,"print_template.save","print_template",target,previous==null?null:summary(previous),summary(saved));
        return saved;
    }

    private void requireWriteScope(CompanyAccess actor,boolean shared) {
        if(!actor.allWarehouses()||!actor.orgScope().allDepartments()) throw new ApiException(403,"FORBIDDEN","维护模板需要当前公司完整数据范围");
        if(!shared) return;
        DocumentAccess.require(actor,TENANT_MANAGE);
        var missing=jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM company c WHERE c.tenant_id=? AND c.status='active' AND NOT EXISTS(
                    SELECT 1 FROM sys_user_data_scope s WHERE s.tenant_id=c.tenant_id AND s.company_id=c.id AND s.user_id=? AND s.scope_type='company'))
                """,Boolean.class,actor.tenantId(),actor.userId());
        if(Boolean.TRUE.equals(missing)) throw new ApiException(403,"FORBIDDEN","维护企业共享模板需要全部公司的授权");
    }

    @Transactional(readOnly=true)
    PrintRendering.Model preview(CompanyAccess actor,Preview input) {
        DocumentAccess.require(actor,VIEW);
        if(input==null) throw invalid("请选择预览模板");
        var definitions=fields.fields(actor,input.documentType(),true);
        var data=input.documentId()==null?sample(definitions,input.layout()!=null && "en-US".equals(input.layout().locale()))
                :printing.templateData(actor,input.documentType(),input.documentId());
        return rendering.render(actor,input.documentType(),input.layout(),data,false);
    }

    @Transactional(readOnly=true)
    Output output(CompanyAccess actor,String type,long document,Long template) {
        fields.requirePrint(actor,type);
        var templates=query("""
                SELECT id,company_id,document_type,name,is_default,status,version FROM print_template
                WHERE tenant_id=? AND company_id IN (0,?) AND document_type=? AND status='active'
                ORDER BY is_default DESC,company_id DESC,id DESC LIMIT 100
                """,false,actor.tenantId(),actor.companyId(),type);
        var selected=template==null?templates.stream().filter(Template::isDefault).findFirst().orElse(null):active(actor,type,template);
        var models=templates.stream().map(PrintTemplates::summary).toList();
        var data=printing.templateData(actor,type,document);
        if(selected==null) return new Output(models,null,null,null);
        var full=selected.layout()==null?find(actor,selected.key(),false):selected;
        return new Output(models,full.id(),full.version(),rendering.render(actor,type,full.layout(),data,false));
    }

    @Transactional
    void browserPrint(CompanyAccess actor,String type,long document,String key,Log input) {
        if(input==null) throw invalid("请选择打印份数");
        int count=copies(input.copies());
        fields.requirePrint(actor,type);
        printing.templateData(actor,type,document);
        var template=input.templateId()==null?null:active(actor,type,input.templateId());
        if(template!=null && (input.version()==null || input.version()!=template.version()))
            throw new ApiException(409,"VERSION_CONFLICT","打印模板已修改，请刷新预览后重试");
        commands.execute(actor,"print_design.browser",key,List.of(type,document,input),200,()->{
            log(actor,type,document,input.templateId(),input.version(),null,count);
            return Boolean.TRUE;
        },Boolean.class);
    }

    Template active(CompanyAccess actor,String type,long id) {
        var template=find(actor,id,false);
        if(!template.documentType().equals(type) || !template.status().equals("active")) throw missing();
        return template;
    }

    private Template find(CompanyAccess actor,long id,boolean lock) {
        if(id<=0) throw invalid("打印模板编号无效");
        var rows=query("""
                SELECT id,company_id,document_type,name,is_default,status,version,layout_json FROM print_template
                WHERE tenant_id=? AND company_id IN (0,?) AND id=?
                """+(lock?" FOR UPDATE":""),true,actor.tenantId(),actor.companyId(),id);
        if(rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    void log(CompanyAccess actor,String type,long document,Long template,Long version,Long job,int count) {
        jdbc.update("""
                INSERT INTO print_log(id,tenant_id,company_id,document_type,document_id,template_id,template_version,export_job_id,printed_by,copies)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,Ids.next(),actor.tenantId(),actor.companyId(),type,document,template,version,job,actor.userId(),count);
    }

    private List<Template> query(String sql,boolean includeLayout,Object... args) {
        return jdbc.query(sql,(row,index)->new Template(row.getString("id"),row.getString("document_type"),row.getString("name"),
                row.getLong("company_id")==0,row.getString("status"),row.getBoolean("is_default"),row.getLong("version"),
                includeLayout?mapper.readValue(row.getString("layout_json"),Layout.class):null),args);
    }

    static int copies(Integer value) {
        if(value==null) return 1;
        if(value<1 || value>10) throw invalid("打印份数须为 1 至 10 份");
        return value;
    }

    private static Summary summary(Template row) {return new Summary(row.id(),row.documentType(),row.name(),row.shared(),row.status(),row.isDefault(),row.version());}

    private Map<String,Object> sample(List<PrintFields.Field> fields,boolean en) {
        var document=new LinkedHashMap<String,Object>();
        var rows=new ArrayList<Map<String,Object>>();
        for(int index=1;index<=3;index++) rows.add(new LinkedHashMap<>());
        for(var field:fields) {
            if(field.id().contains("custom.")) continue;
            String value=switch(field.kind()) {case "money"->"1234.56";case "quantity"->"2.500000";default->en?field.english():field.label();};
            if(field.line()) rows.forEach(row->row.put(field.id().substring(6),value));
            else document.put(field.id(),value);
        }
        document.put("documentNo","SAMPLE-001");document.put("businessDate","2026-01-01");document.put("lines",rows);
        return Map.of("company",Map.of("name",en?"Sample company":"示例公司","address",en?"Sample address":"示例地址","phone","00000000000"),
                "document",document,"printedAt",Instant.now().toString());
    }

    static ApiException missing(){return new ApiException(404,"NOT_FOUND","打印模板不存在或无权查看");}
}
