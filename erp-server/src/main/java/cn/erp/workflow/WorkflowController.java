package cn.erp.workflow;

import java.time.LocalDate;
import java.util.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import cn.erp.identity.*;
import cn.erp.shared.*;

import static cn.erp.workflow.WorkflowDocuments.*;

@RestController
@RequestMapping("/api/v1/workflow")
class WorkflowController {
    private final AccessService access;
    private final WorkflowModels models;
    private final WorkflowQueries queries;
    private final WorkflowTasks tasks;
    private final JdbcTemplate jdbc;
    private final BusinessCommands commands;
    private final AuditService audit;

    WorkflowController(
            AccessService access,
            WorkflowModels models,
            WorkflowQueries queries,
            WorkflowTasks tasks,
            JdbcTemplate jdbc,
            BusinessCommands commands,
            AuditService audit) {
        this.access = access;
        this.models = models;
        this.queries = queries;
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.commands = commands;
        this.audit = audit;
    }

    @GetMapping("/models")
    ApiResponse<?> models(HttpServletRequest req) {
        return ApiResponse.success(models.list(actor(req)));
    }

    @GetMapping("/models/{id}")
    ApiResponse<?> model(HttpServletRequest req, @PathVariable long id) {
        return ApiResponse.success(models.get(actor(req), id));
    }

    @GetMapping("/models/{id}/versions/{version}")
    ApiResponse<?> version(
            HttpServletRequest req, @PathVariable long id, @PathVariable int version) {
        return ApiResponse.success(models.version(actor(req), id, version));
    }

    @PostMapping("/models")
    ApiResponse<?> create(
            HttpServletRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WorkflowModels.Input input) {
        return ApiResponse.success(models.save(actor(req), null, key, input));
    }

    @PutMapping("/models/{id}")
    ApiResponse<?> save(
            HttpServletRequest req,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WorkflowModels.Input input) {
        return ApiResponse.success(models.save(actor(req), id, key, input));
    }

    record Version(
            @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version,
            @Size(max = 1000, message = "意见最多1000字") String comment) {}

    @PostMapping("/models/{id}/{action}")
    ApiResponse<?> modelAction(
            HttpServletRequest req,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Version input) {
        return ApiResponse.success(models.change(actor(req), id, action, input.version(), key));
    }

    @GetMapping("/bindings")
    ApiResponse<?> bindings(HttpServletRequest req) {
        return ApiResponse.success(models.bindings(actor(req)));
    }

    @PostMapping("/bindings")
    ApiResponse<?> binding(
            HttpServletRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WorkflowModels.Binding input) {
        return ApiResponse.success(models.bind(actor(req), null, key, input));
    }

    @PutMapping("/bindings/{id}")
    ApiResponse<?> binding(
            HttpServletRequest req,
            @PathVariable long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WorkflowModels.Binding input) {
        return ApiResponse.success(models.bind(actor(req), id, key, input));
    }

    @GetMapping("/tasks/{kind:todo|done}")
    ApiResponse<?> taskList(
            HttpServletRequest req,
            @PathVariable String kind,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @ModelAttribute WorkflowQueries.Filter filter) {
        return ApiResponse.success(queries.list(actor(req), kind, cursor, limit, filter));
    }

    @GetMapping("/instances/{kind:mine|all}")
    ApiResponse<?> instanceList(
            HttpServletRequest req,
            @PathVariable String kind,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @ModelAttribute WorkflowQueries.Filter filter) {
        return ApiResponse.success(queries.list(actor(req), kind, cursor, limit, filter));
    }

    @GetMapping("/copies")
    ApiResponse<?> copies(
            HttpServletRequest req,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int limit,
            @ModelAttribute WorkflowQueries.Filter filter) {
        return ApiResponse.success(queries.list(actor(req), "copies", cursor, limit, filter));
    }

    @PutMapping("/copies/{id}/read")
    ApiResponse<?> read(HttpServletRequest req, @PathVariable long id) {
        return ApiResponse.success(queries.readCopy(actor(req), id));
    }

    @GetMapping("/instances/{id:[0-9]+}")
    ApiResponse<?> instance(HttpServletRequest req, @PathVariable long id) {
        return ApiResponse.success(queries.detail(actor(req), id));
    }

    @PostMapping("/instances/{id:[0-9]+}/{action:cancel|terminate}")
    ApiResponse<?> instanceAction(
            HttpServletRequest req,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Version input) {
        return ApiResponse.success(
                tasks.end(actor(req), id, action, key, input.version(), input.comment()));
    }

    @PostMapping("/tasks/{id:[0-9]+}/{action}")
    ApiResponse<?> taskAction(
            HttpServletRequest req,
            @PathVariable long id,
            @PathVariable String action,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody WorkflowTasks.Decision input) {
        return ApiResponse.success(tasks.decide(actor(req), id, action, key, input));
    }

    @GetMapping("/tasks/{id:[0-9]+}/candidates")
    ApiResponse<?> candidates(HttpServletRequest req, @PathVariable long id) {
        return ApiResponse.success(queries.candidates(actor(req), id));
    }

    @GetMapping("/progress")
    ApiResponse<?> progress(
            HttpServletRequest req,
            @RequestParam String businessType,
            @RequestParam long businessId) {
        return ApiResponse.success(queries.progress(actor(req), businessType, businessId));
    }

    @GetMapping("/todo-count")
    ApiResponse<?> count(HttpServletRequest req) {
        return ApiResponse.success(Map.of("count", queries.count(actor(req))));
    }

    @GetMapping("/start-options")
    ApiResponse<?> startOptions(
            HttpServletRequest req,
            @RequestParam String businessType,
            @RequestParam long businessId) {
        return ApiResponse.success(queries.startOptions(actor(req), businessType, businessId));
    }

    @GetMapping("/reversal-start-options")
    ApiResponse<?> reversalOptions(
            HttpServletRequest req,
            @RequestParam String sourceType,
            @RequestParam long sourceId,
            @RequestParam LocalDate businessDate) {
        return ApiResponse.success(
                queries.reversalStartOptions(actor(req), sourceType, sourceId, businessDate));
    }

    @GetMapping("/metadata")
    ApiResponse<?> metadata(HttpServletRequest req) {
        var actor = actor(req);
        models.manage(actor, "workflow_model:view", false);
        var result = new LinkedHashMap<String, Object>();
        result.put("businessTypes", SimpleModel.TYPES.stream().sorted().toList());
        result.put("fields", SimpleModel.FIELDS);
        result.put(
                "fieldsByType",
                SimpleModel.TYPES.stream()
                        .collect(
                                java.util.stream.Collectors.toMap(
                                        type -> type, SimpleModel::fields)));
        result.put(
                "users",
                jdbc
                        .queryForList(
                                "SELECT DISTINCT u.id,u.display_name name FROM sys_user u JOIN"
                                    + " org_user_data_scope s ON s.tenant_id=u.tenant_id AND"
                                    + " s.user_id=u.id AND s.company_id=? WHERE u.tenant_id=? AND"
                                    + " u.status='active' ORDER BY u.id",
                                actor.companyId(),
                                actor.tenantId())
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        result.put(
                "roles",
                jdbc
                        .queryForList(
                                "SELECT id,code,name FROM sys_role WHERE tenant_id=? AND"
                                    + " status='active' ORDER BY id",
                                actor.tenantId())
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        result.put(
                "departments",
                jdbc
                        .queryForList(
                                "SELECT id,parent_id,name,leader_user_id FROM org_department WHERE"
                                    + " tenant_id=? AND company_id=? AND status='active' ORDER BY"
                                    + " id",
                                actor.tenantId(),
                                actor.companyId())
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        result.put(
                "posts",
                jdbc
                        .queryForList(
                                "SELECT id,code,name FROM org_post WHERE tenant_id=? AND"
                                    + " status='active' ORDER BY id",
                                actor.tenantId())
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
        return ApiResponse.success(result);
    }

    @GetMapping("/categories")
    ApiResponse<?> categories(HttpServletRequest req) {
        var actor = actor(req);
        models.manage(actor, "workflow_model:view", false);
        return ApiResponse.success(
                jdbc
                        .queryForList(
                                "SELECT * FROM wf_category WHERE tenant_id=? ORDER BY sort,id",
                                actor.tenantId())
                        .stream()
                        .map(WorkflowModels::view)
                        .toList());
    }

    record Category(
            @NotBlank(message = "请填写分类编码")
                    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]{0,39}", message = "分类编码无效")
                    String code,
            @NotBlank(message = "请填写分类名称") @Size(max = 100, message = "分类名称最多100字") String name,
            int sort,
            @NotNull(message = "请提供版本号") @PositiveOrZero(message = "版本号无效") Long version) {}

    @PostMapping("/categories")
    @Transactional
    ApiResponse<?> category(
            HttpServletRequest req,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Category input) {
        return category(req, null, key, input);
    }

    @PutMapping("/categories/{id}")
    @Transactional
    ApiResponse<?> category(
            HttpServletRequest req,
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @Valid @RequestBody Category input) {
        var actor = actor(req);
        models.manage(actor, "workflow_category:update", true);
        return ApiResponse.success(
                commands.execute(
                        actor,
                        "workflow_category.save",
                        key,
                        Arrays.asList(id, input),
                        200,
                        () -> {
                            long category = id == null ? Ids.next() : id;
                            if (id == null) {
                                if (input.version() != 0) throw SimpleModel.invalid("新分类版本号应为0");
                                jdbc.update(
                                        "INSERT INTO wf_category(id,tenant_id,code,name,sort)"
                                            + " VALUES(?,?,?,?,?)",
                                        category,
                                        actor.tenantId(),
                                        input.code(),
                                        input.name(),
                                        input.sort());
                            } else {
                                if (jdbc.update(
                                                "UPDATE wf_category SET"
                                                    + " name=?,sort=?,version=version+1 WHERE"
                                                    + " tenant_id=? AND id=? AND code=? AND"
                                                    + " version=?",
                                                input.name(),
                                                input.sort(),
                                                actor.tenantId(),
                                                id,
                                                input.code(),
                                                input.version())
                                        != 1)
                                    throw new ApiException(409, "VERSION_CONFLICT", "分类已变化，请刷新后重试");
                            }
                            audit.record(
                                    actor,
                                    "workflow_category.save",
                                    "wf_category",
                                    category,
                                    null,
                                    Map.of("name", input.name()));
                            return Map.of(
                                    "id",
                                    Long.toString(category),
                                    "version",
                                    id == null ? 0 : input.version() + 1);
                        },
                        Map.class));
    }

    private CompanyAccess actor(HttpServletRequest req) {
        return access.requireCompany(req, null);
    }
}
