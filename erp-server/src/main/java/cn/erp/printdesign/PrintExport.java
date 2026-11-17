package cn.erp.printdesign;

import cn.erp.documents.DocumentAccess;
import cn.erp.documents.PrintService;
import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ActorScope;
import cn.erp.shared.ApiException;
import cn.erp.shared.DocumentPrintExportPort;
import cn.erp.shared.FinancialExportPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import static cn.erp.printdesign.PrintFields.invalid;

@Service
class PrintExport implements DocumentPrintExportPort {
    private static final int MAX_DOCUMENTS=50;
    private final ObjectMapper json;
    private final DocumentAccess documents;
    private final PrintService printing;
    private final PrintTemplates templates;
    private final PrintFields fields;
    private final PrintRendering rendering;
    private final PrintPdf pdf;

    PrintExport(ObjectMapper json,DocumentAccess documents,PrintService printing,PrintTemplates templates,PrintFields fields,
                PrintRendering rendering,PrintPdf pdf) {
        this.json=json;this.documents=documents;this.printing=printing;this.templates=templates;this.fields=fields;this.rendering=rendering;this.pdf=pdf;
    }

    private record Selection(String documentType,Long templateId,List<Long> documentIds,Integer copies) {}
    private record Frozen(Selection selection,long version,PrintLayout.Layout layout,boolean cost,boolean bank) {}

    @Override
    @Transactional(readOnly=true)
    public Map<String,Object> freeze(ActorScope scope,Map<String,Object> query) {
        if(query==null) throw invalid("请选择模板和打印单据");
        Selection selected;
        try {selected=json.convertValue(query,Selection.class);}
        catch(IllegalArgumentException error){throw invalid("批量打印条件无效");}
        if(selected.templateId()==null || selected.documentIds()==null || selected.documentIds().isEmpty() || selected.documentIds().size()>MAX_DOCUMENTS
                || selected.documentIds().stream().anyMatch(id->id==null || id<=0)
                || selected.documentIds().size()!=new HashSet<>(selected.documentIds()).size()) throw invalid("请选择一个模板及 1 至 50 张不重复的同类单据");
        PrintTemplates.copies(selected.copies());
        var actor=company(scope);
        fields.requirePrint(actor,selected.documentType());checkDocuments(actor,selected);
        var template=templates.active(actor,selected.documentType(),selected.templateId());
        var refs=references(template.layout());
        var definitions=fields.fields(actor,selected.documentType(),true);
        boolean cost=definitions.stream().anyMatch(f->PrintFields.COST.equals(f.permission()) && refs.contains(f.id()));
        boolean bank=definitions.stream().anyMatch(f->PrintFields.BANK.equals(f.permission()) && refs.contains(f.id()));
        var frozen=new Frozen(selected,template.version(),template.layout(),cost,bank);
        return Map.of("print",frozen);
    }

    @Override
    @Transactional(readOnly=true)
    public void authorize(ActorScope scope,Map<String,Object> query,boolean checkDocuments) {
        var actor=company(scope);var frozen=decode(query);
        fields.requirePrint(actor,frozen.selection().documentType());
        if(frozen.cost() && !actor.hasPermission(PrintFields.COST)
                || frozen.bank() && (!actor.hasPermission(PrintFields.BANK)||!actor.hasPermission("cash_account:view")))
            throw new ApiException(403,"FORBIDDEN","敏感字段权限已变更，请重新生成打印文件");
        if(checkDocuments) {
            try { checkDocuments(actor,frozen.selection()); }
            catch(ApiException problem) {
                if(problem.getStatus()!=403 && problem.getStatus()!=404) throw problem;
                throw new ApiException(403,"FORBIDDEN","打印单据的访问权限或数据范围已变更");
            }
        }
    }

    @Override
    @Transactional(readOnly=true)
    public FinancialExportPort.File render(ActorScope scope,Map<String,Object> query) {
        authorize(scope,query,false);
        var frozen=decode(query);var actor=company(scope);var selected=frozen.selection();
        var permissions=new HashSet<>(actor.permissions());
        if(!frozen.cost()) permissions.remove(PrintFields.COST);
        if(!frozen.bank()) permissions.remove(PrintFields.BANK);
        var authorized=new CompanyAccess(actor.tenantId(),actor.userId(),actor.companyId(),actor.allWarehouses(),actor.warehouseIds(),permissions,actor.orgScope());
        var models=new ArrayList<PrintPdf.Copy>();
        int copies=PrintTemplates.copies(selected.copies()), pages=0;
        for(long document:selected.documentIds()) {
            var data=printing.templateData(authorized,selected.documentType(),document);
            var model=rendering.render(authorized,selected.documentType(),frozen.layout(),data,false);
            pages+=model.pages().size()*copies;
            if(pages>PrintRendering.MAX_PAGES) throw invalid("批量打印共有过多页，请分批提交");
            models.add(new PrintPdf.Copy(model,copies));
        }
        String name=fields.document(selected.documentType()).label()+"-批量打印.pdf";
        return new FinancialExportPort.File(name,"application/pdf",pdf.write(models),models.size(),Instant.now().toString());
    }

    @Override
    public void completed(ActorScope scope,long job,Map<String,Object> query) {
        var frozen=decode(query);var selected=frozen.selection();
        var actor=company(scope);
        for(long document:selected.documentIds()) templates.log(actor,selected.documentType(),document,selected.templateId(),frozen.version(),job,PrintTemplates.copies(selected.copies()));
    }

    private Frozen decode(Map<String,Object> query) {
        if(query==null || query.get("print")==null) throw invalid("打印任务参数缺失");
        return json.convertValue(query.get("print"),Frozen.class);
    }

    private void checkDocuments(CompanyAccess actor,Selection selected) {
        for(long document:selected.documentIds()) documents.view(actor,selected.documentType(),document);
    }

    private static HashSet<String> references(PrintLayout.Layout layout) {
        var refs=new HashSet<String>();
        for(var element:layout.header()) if(element.field()!=null) refs.add(element.field());
        for(var element:layout.footer()) if(element.field()!=null) refs.add(element.field());
        if(layout.table()!=null) for(var column:layout.table().columns()) refs.add(column.field());
        return refs;
    }

    private static CompanyAccess company(ActorScope actor) {
        if(actor instanceof CompanyAccess company) return company;
        throw new ApiException(403,"FORBIDDEN","打印任务需要已授权的公司范围");
    }
}
