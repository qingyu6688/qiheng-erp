package cn.erp.workflow;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.*;
import static cn.erp.workflow.WorkflowDocuments.*;
import static cn.erp.workflow.GenericWorkflowBusiness.TYPE;
import static cn.erp.workflow.WorkflowFormSchema.Field;

@Service
@Transactional
class WorkflowForms {
    record FormInput(String code,String name,Long modelId,List<Field> fields,Boolean enabled,Long version) {}
    record ApplicationInput(Long formId,Long formVersion,String title,Map<String,Object> fields) {}

    private final WorkflowState state;
    private final WorkflowRuntime runtime;
    private final GenericWorkflowBusiness business;
    private final BusinessCommands commands;

    WorkflowForms(WorkflowState state,WorkflowRuntime runtime,GenericWorkflowBusiness business,BusinessCommands commands) {
        this.state = state;
        this.runtime = runtime;
        this.business = business;
        this.commands = commands;
    }

    List<Map<String,Object>> list(CompanyAccess actor,boolean manage) {
        if (manage) state.models.manage(actor,"workflow_form:manage",false);
        else requireStarter(actor);
        var rows = state.jdbc.queryForList("""
                SELECT f.id,f.code,f.name,f.model_id,f.schema_json,f.enabled,f.version,m.name model_name
                FROM wf_form f JOIN wf_model m ON m.tenant_id=f.tenant_id AND m.id=f.model_id
                   AND m.company_id IN (0,f.company_id) AND m.business_type='wf_generic_request'
                WHERE f.tenant_id=? AND f.company_id=?
                """ + (manage ? "" : " AND f.enabled=1 AND m.status='published' AND m.published_version>0")
                + " ORDER BY f.id DESC LIMIT 200",actor.tenantId(),actor.companyId());
        return rows.stream().map(row -> {
            var result = WorkflowModels.view(row);
            result.remove("schemaJson");
            result.put("fields",schema(row));
            return result;
        }).toList();
    }

    List<Map<String,Object>> models(CompanyAccess actor) {
        state.models.manage(actor,"workflow_form:manage",false);
        return state.jdbc.queryForList("""
                SELECT id,name FROM wf_model WHERE tenant_id=? AND company_id IN (0,?)
                    AND business_type='wf_generic_request' AND status='published' AND published_version>0
                ORDER BY name,id LIMIT 200
                """,actor.tenantId(),actor.companyId()).stream().map(WorkflowModels::view).toList();
    }

    Map<String,Object> save(CompanyAccess actor,Long id,String key,FormInput input) {
        state.models.manage(actor,"workflow_form:manage",true);
        if (!WorkflowFormSchema.validKey(input.code()) || input.modelId() == null || input.modelId() <= 0
                || input.enabled() == null || input.version() == null || input.version() < 0)
            throw SimpleModel.invalid("表单编码、关联流程、启用状态或版本无效");
        SimpleModel.safeLabel(input.name());
        var fields = WorkflowFormSchema.describe(input.fields());
        return command(actor,"workflow_form.save",key,Arrays.asList(id,input),() -> {
            var binding = state.models.genericBinding(actor,input.modelId());
            if (binding == null) throw missing();
            validateFieldPermissions(actor,binding,fields);
            long form = id == null ? Ids.next() : id;
            String schema = state.json.writeValueAsString(fields);
            if (id == null) {
                if (input.version() != 0) throw SimpleModel.invalid("新表单版本必须为0");
                state.jdbc.update("""
                        INSERT INTO wf_form(id,tenant_id,company_id,code,name,model_id,schema_json,enabled)
                        VALUES(?,?,?,?,?,?,?,?)
                        """,form,actor.tenantId(),actor.companyId(),input.code(),input.name(),input.modelId(),schema,input.enabled());
            } else {
                var current = form(actor,form,true);
                state.version(input.version(),current);
                if (!current.get("code").equals(input.code())) throw SimpleModel.invalid("表单编码创建后保持不变");
                state.jdbc.update("""
                        UPDATE wf_form SET name=?,model_id=?,schema_json=?,enabled=?,version=version+1
                        WHERE tenant_id=? AND company_id=? AND id=?
                        """,input.name(),input.modelId(),schema,input.enabled(),actor.tenantId(),actor.companyId(),form);
            }
            state.audit.record(actor,"workflow_form.save","wf_form",form,null,Map.of("code",input.code(),"enabled",input.enabled()));
            return Map.of("id",Long.toString(form),"version",id == null ? 0 : input.version()+1);
        });
    }

    Map<String,Object> create(CompanyAccess actor,String key,ApplicationInput input) {
        requireStarter(actor);
        if (input.formId() == null || input.formId() <= 0 || input.formVersion() == null || input.formVersion() < 0)
            throw SimpleModel.invalid("请选择当前版本的申请表单");
        SimpleModel.safeLabel(input.title());
        return command(actor,"wf_generic_request.create",key,input,() -> {
            var form = form(actor,input.formId(),true);
            state.version(input.formVersion(),form);
            if (number(form.get("enabled")) != 1) throw missing();
            long model = number(form.get("model_id"));
            var binding = state.models.genericBinding(actor,model);
            if (binding == null) throw missing();
            var fields = schema(form);
            validateFieldPermissions(actor,binding,fields);
            var values = WorkflowFormSchema.values(fields,input.fields(),keys(fields),true);
            long id = Ids.next();
            String requestNo = "REQ-" + id;
            state.jdbc.update("""
                    INSERT INTO wf_generic_request(id,tenant_id,company_id,form_id,form_version,model_id,request_no,title,
                        schema_json,data_json,created_by,owner_user_id,owner_department_id)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,actor.tenantId(),actor.companyId(),input.formId(),input.formVersion(),model,requestNo,input.title(),
                    form.get("schema_json").toString(),state.json.writeValueAsString(values),actor.userId(),actor.userId(),OrgOwnership.department(actor));
            runtime.start(actor,TYPE,id,Map.of());
            var request = business.row(actor,id,false);
            return Map.of("id",Long.toString(id),"requestNo",requestNo,"instanceId",request.get("wf_instance_id").toString());
        });
    }

    Map<String,Object> detail(CompanyAccess actor,long requestId,Long taskId) {
        if (!actor.hasPermission(TYPE+":view")) throw missing();
        var request = business.row(actor,requestId,false);
        long instanceId = number(request.get("wf_instance_id"));
        var instance = state.instance(actor,instanceId,false);
        boolean starter = number(request.get("created_by")) == actor.userId();
        var task = ownTask(actor,instanceId,taskId);
        if (!starter && task.isEmpty()) throw missing();
        var access = task.map(row -> nodeAccess(instance,row)).orElse(Map.of());
        boolean active = task.filter(row -> row.get("status").equals("pending") && row.get("owner_id") == null
                && row.get("parent_task_id") == null).isPresent() && instance.get("status").equals("running");
        var values = values(request);
        var visible = new ArrayList<Map<String,Object>>();
        for (var field : schema(request)) {
            String mode = access.getOrDefault(field.key(),"hidden");
            if (!starter && mode.equals("hidden")) continue;
            var result = new LinkedHashMap<String,Object>();
            result.put("field",field);
            result.put("value",values.get(field.key()));
            result.put("access",active && mode.equals("write") ? "write" : "read");
            visible.add(result);
        }
        return Map.of("id",Long.toString(requestId),"requestNo",request.get("request_no"),"status",request.get("status"),"fields",visible,
                "canUpload",starter && request.get("status").equals("pending") && actor.hasPermission(TYPE+":create"));
    }

    void patchTask(CompanyAccess actor,Map<String,Object> instance,Map<String,Object> task,Map<String,Object> changes) {
        if (changes == null || changes.isEmpty()) return;
        if (!TYPE.equals(instance.get("business_type")) || task.get("owner_id") != null || task.get("parent_task_id") != null)
            throw new ApiException(403,"FORBIDDEN","此任务不能修改表单字段");
        long requestId = number(instance.get("business_id"));
        var request = business.row(actor,requestId,true);
        var allowed = nodeAccess(instance,task).entrySet().stream().filter(entry -> entry.getValue().equals("write"))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        if (!allowed.containsAll(changes.keySet())) throw new ApiException(403,"FORBIDDEN","存在当前审批节点不可写的字段");
        var values = values(request);
        values.putAll(WorkflowFormSchema.values(schema(request),changes,allowed,false));
        state.jdbc.update("UPDATE wf_generic_request SET data_json=? WHERE tenant_id=? AND company_id=? AND id=? AND status='pending'",
                state.json.writeValueAsString(values),actor.tenantId(),actor.companyId(),requestId);
        state.audit.record(actor,"workflow_form.fields.update","wf_generic_request",requestId,null,
                Map.of("keys",changes.keySet().stream().sorted().toList()));
    }

    private java.util.Optional<Map<String,Object>> ownTask(CompanyAccess actor,long instance,Long taskId) {
        var rows = state.jdbc.queryForList("""
                SELECT * FROM wf_task_ext WHERE tenant_id=? AND company_id=? AND instance_id=? AND assignee_id=?
                   AND (? IS NULL OR id=?) AND status IN ('pending','waiting_sign','approved','rejected')
                ORDER BY status='pending' DESC,id DESC LIMIT 1
                """,actor.tenantId(),actor.companyId(),instance,actor.userId(),taskId,taskId);
        return rows.stream().filter(row -> !row.get("node_kind").equals("approver") || actor.hasPermission(TYPE+":approve")).findFirst();
    }

    private Map<String,String> nodeAccess(Map<String,Object> instance,Map<String,Object> task) {
        var node = state.node(instance,task.get("node_key").toString());
        return node.fieldAccess() == null ? Map.of() : node.fieldAccess();
    }

    private void validateFieldPermissions(CompanyAccess actor,Map<String,Object> binding,List<Field> fields) {
        var frozen = state.models.published(actor.tenantId(),number(binding.get("model_id")),Math.toIntExact(number(binding.get("published_version"))));
        var keys = keys(fields);
        var nodes = SimpleModel.validate(state.models.tree(frozen),TYPE);
        if (nodes.values().stream().anyMatch(node -> node.fieldAccess() != null && !keys.containsAll(node.fieldAccess().keySet())))
            throw SimpleModel.invalid("当前流程引用了表单未登记的字段，请先同步字段编码");
        if (nodes.values().stream().anyMatch(node -> node.assignee() != null && node.assignee().strategy().equals("starter_select")))
            throw SimpleModel.invalid("通用审批表单请配置固定的组织选人规则");
    }

    private Map<String,Object> form(CompanyAccess actor,long id,boolean lock) {
        return state.jdbc.queryForList("SELECT * FROM wf_form WHERE tenant_id=? AND company_id=? AND id=?"
                + (lock ? " FOR UPDATE" : ""),actor.tenantId(),actor.companyId(),id).stream().findFirst().orElseThrow(WorkflowDocuments::missing);
    }

    private List<Field> schema(Map<String,Object> row) {
        return state.json.readValue(row.get("schema_json").toString(),new TypeReference<List<Field>>() {});
    }

    private Map<String,Object> values(Map<String,Object> row) {
        return state.json.readValue(row.get("data_json").toString(),new TypeReference<Map<String,Object>>() {});
    }

    private Set<String> keys(List<Field> fields) {
        return fields.stream().map(Field::key).collect(Collectors.toSet());
    }

    private void requireStarter(CompanyAccess actor) {
        if (!actor.hasPermission(TYPE+":create") || !actor.hasPermission(TYPE+":view"))
            throw new ApiException(403,"FORBIDDEN","没有发起通用审批的权限");
    }

    @SuppressWarnings({"unchecked","rawtypes"})
    private Map<String,Object> command(CompanyAccess actor,String code,String key,Object input,Supplier<Map<String,Object>> action) {
        return commands.execute(actor,code,key,input,200,action,(Class) Map.class);
    }
}
