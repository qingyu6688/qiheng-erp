package cn.erp.workflow;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

import jakarta.validation.constraints.*;
import org.flowable.engine.ProcessEngine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@Service
@Transactional
public class WorkflowModels {
    public record Input(
            @NotNull(message = "请提供非负版本号或公司编号") @PositiveOrZero(message = "编号或版本号不能为负数")
                    Long companyId,
            @NotBlank(message = "请填写编码")
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,39}", message = "编码格式无效")
                    String code,
            @NotBlank(message = "请填写名称") @Size(max = 100, message = "名称最多100字") String name,
            Long categoryId,
            @NotBlank(message = "请选择业务类型") String businessType,
            @NotNull(message = "请配置流程节点") SimpleModel.Node simple,
            @NotNull(message = "请提供非负版本号或公司编号") @PositiveOrZero(message = "编号或版本号不能为负数")
                    Long version) {}

    public record Binding(
            @NotNull(message = "请提供非负版本号或公司编号") @PositiveOrZero(message = "编号或版本号不能为负数")
                    Long companyId,
            @NotBlank(message = "请选择业务类型") String businessType,
            @NotNull(message = "请选择模型") @Positive(message = "模型编号无效") Long modelId,
            @Min(value = -1000, message = "优先级不能小于-1000")
                    @Max(value = 1000, message = "优先级不能大于1000")
                    int priority,
            SimpleModel.Condition condition,
            boolean enabled,
            @NotNull(message = "请提供非负版本号或公司编号") @PositiveOrZero(message = "编号或版本号不能为负数")
                    Long version) {}

    private final JdbcTemplate jdbc;
    private final ProcessEngine engine;
    private final ObjectMapper json;
    private final BusinessCommands commands;
    private final AuditService audit;

    public WorkflowModels(
            JdbcTemplate jdbc,
            ProcessEngine engine,
            ObjectMapper json,
            BusinessCommands commands,
            AuditService audit) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.json = json;
        this.commands = commands;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(CompanyAccess actor) {
        manage(actor, "workflow_model:view", false);
        return jdbc
                .queryForList(
                        "SELECT"
                            + " id,company_id,code,name,category_id,business_type,status,published_version,version,updated_at"
                            + " FROM wf_model WHERE tenant_id=? AND company_id IN(0,?) ORDER BY id"
                            + " DESC",
                        actor.tenantId(),
                        actor.companyId())
                .stream()
                .map(WorkflowModels::view)
                .toList();
    }

    public Map<String, Object> get(CompanyAccess actor, long id) {
        manage(actor, "workflow_model:view", false);
        var raw = head(actor, id, false);
        var result = view(raw);
        result.put(
                "simple",
                json.readValue(raw.get("simple_json").toString(), SimpleModel.Node.class));
        result.put(
                "versions",
                jdbc
                        .queryForList(
                                "SELECT version_no,published_by,published_at,content_hash FROM"
                                    + " wf_model_version WHERE tenant_id=? AND model_id=? ORDER BY"
                                    + " version_no DESC",
                                actor.tenantId(),
                                id)
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        return result;
    }

    public Map<String, Object> version(CompanyAccess actor, long id, int version) {
        manage(actor, "workflow_model:view", false);
        head(actor, id, false);
        var raw = published(actor.tenantId(), id, version);
        return Map.of(
                "versionNo", version, "simple", tree(raw), "contentHash", raw.get("content_hash"));
    }

    public Map<String, Object> save(CompanyAccess actor, Long id, String key, Input input) {
        manage(actor, id == null ? "workflow_model:create" : "workflow_model:update", true);
        checkEditable(input.code());
        company(actor, input.companyId());
        SimpleModel.safeLabel(input.name());
        SimpleModel.validate(input.simple(), input.businessType());
        if (input.categoryId() != null
                && jdbc.queryForList(
                                "SELECT id FROM wf_category WHERE tenant_id=? AND id=?",
                                Long.class,
                                actor.tenantId(),
                                input.categoryId())
                        .isEmpty()) throw missing();
        return execute(
                actor,
                "workflow_model.save",
                key,
                Arrays.asList(id, input),
                () -> {
                    long model = id == null ? Ids.next() : id;
                    if (id != null) {
                        var old = head(actor, id, true);
                        checkEditable(old.get("code").toString());
                        version(input.version(), old);
                        if (!old.get("code").equals(input.code())
                                || number(old.get("company_id")) != input.companyId()
                                || !old.get("business_type").equals(input.businessType()))
                            throw SimpleModel.invalid("模型编码、公司和业务类型发布后保持不变，请新建模型");
                        jdbc.update(
                                "UPDATE wf_model SET"
                                    + " name=?,category_id=?,simple_json=?,version=version+1,updated_by=?"
                                    + " WHERE tenant_id=? AND id=?",
                                input.name(),
                                input.categoryId(),
                                json.writeValueAsString(input.simple()),
                                actor.userId(),
                                actor.tenantId(),
                                model);
                    } else {
                        if (input.version() != 0) throw SimpleModel.invalid("新模型版本号应为0");
                        jdbc.update(
                                "INSERT INTO"
                                    + " wf_model(id,tenant_id,company_id,code,name,category_id,business_type,simple_json,created_by)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                model,
                                actor.tenantId(),
                                input.companyId(),
                                input.code(),
                                input.name(),
                                input.categoryId(),
                                input.businessType(),
                                json.writeValueAsString(input.simple()),
                                actor.userId());
                    }
                    audit.record(
                            actor,
                            "workflow_model.save",
                            "wf_model",
                            model,
                            null,
                            Map.of("name", input.name(), "businessType", input.businessType()));
                    return Map.of(
                            "id",
                            Long.toString(model),
                            "version",
                            id == null ? 0 : input.version() + 1);
                });
    }

    public Map<String, Object> change(
            CompanyAccess actor, long id, String action, long expected, String key) {
        if (!Set.of("publish", "disable").contains(action)) throw SimpleModel.invalid("模型操作无效");
        manage(actor, "workflow_model:" + action, true);
        return execute(
                actor,
                "workflow_model." + action,
                key,
                Map.of("id", id, "version", expected),
                () -> {
                    var raw = head(actor, id, true);
                    checkEditable(raw.get("code").toString());
                    version(expected, raw);
                    int published = ((Number) raw.get("published_version")).intValue();
                    if (action.equals("publish")) {
                        var node =
                                json.readValue(
                                        raw.get("simple_json").toString(), SimpleModel.Node.class);
                        String type = raw.get("business_type").toString();
                        SimpleModel.validate(node, type);
                        published++;
                        deploy(actor.tenantId(), number(raw.get("company_id")), id, type,
                                raw.get("name").toString(), node, published, actor.userId());
                    }
                    jdbc.update(
                            "UPDATE wf_model SET"
                                + " status=?,published_version=?,version=version+1,updated_by=?"
                                + " WHERE tenant_id=? AND id=?",
                            action.equals("publish") ? "published" : "disabled",
                            published,
                            actor.userId(),
                            actor.tenantId(),
                            id);
                    audit.record(
                            actor,
                            "workflow_model." + action,
                            "wf_model",
                            id,
                            null,
                            Map.of("publishedVersion", published));
                    return Map.of(
                            "id",
                            Long.toString(id),
                            "version",
                            expected + 1,
                            "publishedVersion",
                            published);
                });
    }

    public List<Map<String, Object>> bindings(CompanyAccess actor) {
        manage(actor, "workflow_model:view", false);
        return jdbc
                .queryForList(
                        "SELECT b.*,m.name model_name FROM wf_business_binding b JOIN wf_model m ON"
                            + " m.id=b.model_id AND m.tenant_id=b.tenant_id WHERE b.tenant_id=? AND"
                            + " b.company_id IN(0,?) ORDER BY b.company_id DESC,b.priority"
                            + " DESC,b.id",
                        actor.tenantId(),
                        actor.companyId())
                .stream()
                .map(WorkflowModels::view)
                .toList();
    }

    public Map<String, Object> bind(CompanyAccess actor, Long id, String key, Binding input) {
        manage(actor, "workflow_binding:update", true);
        if (input.priority() == SystemWorkflowPlan.PRIORITY) throw SimpleModel.invalid("默认流程优先级由系统管理");
        company(actor, input.companyId());
        if (!SimpleModel.TYPES.contains(input.businessType()) || input.businessType().equals(GenericWorkflowBusiness.TYPE))
            throw SimpleModel.invalid("请选择业务单据类型，通用审批通过表单关联流程");
        if (input.condition() != null)
            SimpleModel.validateFields(input.condition(), input.businessType());
        var model = head(actor, input.modelId(), true);
        if (!model.get("business_type").equals(input.businessType())
                || number(model.get("company_id")) != 0
                        && number(model.get("company_id")) != input.companyId())
            throw SimpleModel.invalid("模型与绑定的业务类型、公司不一致");
        if (!model.get("status").equals("published") && input.enabled())
            throw SimpleModel.invalid("启用绑定前须先发布模型");
        return execute(
                actor,
                "workflow_binding.save",
                key,
                Arrays.asList(id, input),
                () -> {
                    long binding = id == null ? Ids.next() : id;
                    if (id != null) {
                        var rows =
                                jdbc.queryForList(
                                        "SELECT * FROM wf_business_binding WHERE tenant_id=? AND"
                                            + " id=? AND company_id IN(0,?) FOR UPDATE",
                                        actor.tenantId(),
                                        id,
                                        actor.companyId());
                        if (rows.isEmpty()) throw missing();
                        if (number(rows.getFirst().get("priority")) == SystemWorkflowPlan.PRIORITY)
                            throw SimpleModel.invalid("系统默认流程绑定由系统管理，可新增自定义流程覆盖");
                        version(input.version(), rows.getFirst());
                        if (number(rows.getFirst().get("company_id")) != input.companyId()
                                || !rows.getFirst()
                                        .get("business_type")
                                        .equals(input.businessType()))
                            throw SimpleModel.invalid("绑定的公司和业务类型不能修改，请新建绑定");
                        jdbc.update(
                                "UPDATE wf_business_binding SET"
                                    + " model_id=?,priority=?,condition_json=?,enabled=?,version=version+1,updated_by=?"
                                    + " WHERE tenant_id=? AND id=?",
                                input.modelId(),
                                input.priority(),
                                input.condition() == null
                                        ? null
                                        : json.writeValueAsString(input.condition()),
                                input.enabled(),
                                actor.userId(),
                                actor.tenantId(),
                                id);
                    } else {
                        if (input.version() != 0) throw SimpleModel.invalid("新绑定版本号应为0");
                        jdbc.update(
                                "INSERT INTO"
                                    + " wf_business_binding(id,tenant_id,company_id,business_type,model_id,priority,condition_json,enabled,created_by)"
                                    + " VALUES(?,?,?,?,?,?,?,?,?)",
                                binding,
                                actor.tenantId(),
                                input.companyId(),
                                input.businessType(),
                                input.modelId(),
                                input.priority(),
                                input.condition() == null
                                        ? null
                                        : json.writeValueAsString(input.condition()),
                                input.enabled(),
                                actor.userId());
                    }
                    audit.record(
                            actor,
                            "workflow_binding.save",
                            "wf_business_binding",
                            binding,
                            null,
                            Map.of("modelId", input.modelId(), "enabled", input.enabled()));
                    return Map.of(
                            "id",
                            Long.toString(binding),
                            "version",
                            id == null ? 0 : input.version() + 1);
                });
    }

    Map<String, Object> selected(CompanyAccess actor, String type, Map<String, Object> summary) {
        for (var row :
                jdbc.queryForList(
                        "SELECT b.*,m.status model_status,m.published_version FROM"
                            + " wf_business_binding b JOIN wf_model m ON m.tenant_id=b.tenant_id"
                            + " AND m.id=b.model_id WHERE b.tenant_id=? AND b.company_id IN(0,?)"
                            + " AND b.business_type=? AND b.enabled=1 ORDER BY (b.priority=" + SystemWorkflowPlan.PRIORITY + "),b.company_id"
                            + " DESC,b.priority DESC,b.id",
                        actor.tenantId(),
                        actor.companyId(),
                        type)) {
            var condition =
                    row.get("condition_json") == null
                            ? null
                            : json.readValue(
                                    row.get("condition_json").toString(),
                                    SimpleModel.Condition.class);
            if (!SimpleModel.matches(condition, summary)) continue;
            if (!row.get("model_status").equals("published"))
                throw new ApiException(409, "WORKFLOW_MODEL_DISABLED", "匹配的流程已停用，请管理员调整绑定后重试");
            return row;
        }
        return null;
    }

    public long installDefault(long tenant, long company, String type) {
        String label = SystemWorkflowPlan.LABELS.get(type);
        if (label == null) throw SimpleModel.invalid("此类单据没有系统默认流程");
        // 公司行串行化首次提交与补建，防止重复部署流程。
        if (jdbc.queryForList("SELECT id FROM company WHERE tenant_id=? AND id=? FOR UPDATE",
                Long.class, tenant, company).isEmpty()) throw missing();
        String code = SystemWorkflowPlan.CODE_PREFIX + type;
        var existing = jdbc.queryForList("""
                SELECT m.id FROM wf_model m JOIN wf_business_binding b
                    ON b.tenant_id=m.tenant_id AND b.company_id=m.company_id AND b.model_id=m.id
                WHERE m.tenant_id=? AND m.company_id=? AND m.code=? AND m.status='published'
                  AND b.priority=? AND b.enabled=1 AND b.condition_json IS NULL AND b.business_type=?
                """, Long.class, tenant, company, code, SystemWorkflowPlan.PRIORITY, type);
        if (!existing.isEmpty()) return existing.getFirst();
        var node = SystemWorkflowPlan.tree(type);
        SimpleModel.validate(node, type);
        long model = Ids.next();
        String name = label + "默认审批";
        jdbc.update("""
                INSERT INTO wf_model(id,tenant_id,company_id,code,name,business_type,simple_json,status,published_version,created_by)
                VALUES(?,?,?,?,?,?,?,'published',1,0)
                """, model, tenant, company, code, name, type, json.writeValueAsString(node));
        deploy(tenant, company, model, type, name, node, 1, 0);
        jdbc.update("""
                INSERT INTO wf_business_binding(id,tenant_id,company_id,business_type,model_id,priority,created_by)
                VALUES(?,?,?,?,?,?,0)
                """, Ids.next(), tenant, company, type, model, SystemWorkflowPlan.PRIORITY);
        return model;
    }

    private void deploy(long tenant, long company, long id, String type, String name,
                        SimpleModel.Node node, int version, long operator) {
        String key = "erp_" + id;
        String xml = SimpleCompiler.compile(key, name, node, type);
        String engineTenant = Long.toString(tenant);
        var repository = engine.getRepositoryService();
        var deployment = repository.createDeployment().tenantId(engineTenant).name(name)
                .addString(key + ".bpmn20.xml", xml).deploy();
        var definition = repository.createProcessDefinitionQuery().deploymentId(deployment.getId())
                .processDefinitionTenantId(engineTenant).singleResult();
        jdbc.update("""
                INSERT INTO wf_model_version(id,tenant_id,company_id,model_id,version_no,flowable_definition_id,
                                             deployment_id,simple_json,bpmn_xml,content_hash,published_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """, Ids.next(), tenant, company, id, version, definition.getId(), deployment.getId(),
                json.writeValueAsString(node), xml, digest(xml), operator);
    }

    private static void checkEditable(String code) {
        if (code.toLowerCase(Locale.ROOT).startsWith(SystemWorkflowPlan.CODE_PREFIX))
            throw SimpleModel.invalid("系统默认流程由系统管理，可新建自定义流程覆盖");
    }

    Map<String,Object> genericBinding(CompanyAccess actor,long modelId) {
        var matches = jdbc.queryForList("""
                SELECT id AS model_id,published_version FROM wf_model
                WHERE tenant_id=? AND company_id IN (0,?) AND id=?
                  AND business_type='wf_generic_request' AND status='published' AND published_version>0
                """,actor.tenantId(),actor.companyId(),modelId);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    Map<String, Object> published(long tenant, long model, int version) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM wf_model_version WHERE tenant_id=? AND model_id=? AND"
                            + " version_no=?",
                        tenant,
                        model,
                        version);
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    SimpleModel.Node tree(Map<String, Object> version) {
        return json.readValue(version.get("simple_json").toString(), SimpleModel.Node.class);
    }

    private Map<String, Object> head(CompanyAccess actor, long id, boolean lock) {
        var rows =
                jdbc.queryForList(
                        "SELECT * FROM wf_model WHERE tenant_id=? AND id=? AND company_id IN(0,?)"
                                + (lock ? " FOR UPDATE" : ""),
                        actor.tenantId(),
                        id,
                        actor.companyId());
        if (rows.isEmpty()) throw missing();
        return rows.getFirst();
    }

    void manage(CompanyAccess actor, String permission, boolean lock) {
        if (lock) PlatformAccess.lock(jdbc, actor, permission);
        else PlatformAccess.require(jdbc, actor, permission);
    }

    private void company(CompanyAccess actor, long company) {
        if (company != 0 && company != actor.companyId()) throw missing();
    }

    private static void version(long expected, Map<String, Object> row) {
        if (number(row.get("version")) != expected)
            throw new ApiException(409, "VERSION_CONFLICT", "配置已变化，请刷新后重试");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<String, Object> execute(
            CompanyAccess actor,
            String operation,
            String key,
            Object input,
            java.util.function.Supplier<Map<String, Object>> work) {
        return commands.execute(actor, operation, key, input, 200, work, (Class) Map.class);
    }

    static Map<String, Object> view(Map<String, Object> raw) {
        var result = ApiRows.row(raw);
        if (raw.get("company_id") != null)
            result.put("companyId", raw.get("company_id").toString());
        return result;
    }

    private static String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("缺少模型摘要算法", error);
        }
    }
}
