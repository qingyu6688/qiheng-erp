package cn.erp.workflow;

import java.nio.charset.StandardCharsets;
import java.util.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cn.erp.identity.CompanyAccess;
import cn.erp.shared.ApiException;
import cn.erp.shared.BusinessCommands;

@Service
class WorkflowBatchApproval {
    record Item(
            @NotNull(message = "请提供任务编号") @Positive(message = "任务编号无效") Long id,
            @NotNull(message = "请提供任务版本") @PositiveOrZero(message = "版本号无效") Long version) {}

    record Input(
            @NotBlank(message = "请提供批量审批环节") @Size(max = 128, message = "批量审批环节无效") String group,
            @NotNull(message = "请选择待办任务") @Size(min = 2, max = 20, message = "每批请选择2至20项待办")
                    List<@NotNull(message = "任务不能为空") @Valid Item> items) {}

    record Result(String id, boolean approved, String message) {}

    private final WorkflowTasks tasks;
    private final BusinessCommands commands;

    WorkflowBatchApproval(WorkflowTasks tasks, BusinessCommands commands) {
        this.tasks = tasks;
        this.commands = commands;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Result> approve(CompanyAccess actor, String key, Input input) {
        var ids = new HashSet<Long>();
        for (var item : input.items())
            if (!ids.add(item.id())) throw SimpleModel.invalid("批量审批中存在重复任务");

        // 先提交完整批次的指纹，随后逐条命令独立提交。
        commands.execute(actor, "workflow_task.batch_approve_request", key, input, 200,
                () -> Boolean.TRUE, Boolean.class);
        var results = new ArrayList<Result>();
        for (var item : input.items()) {
            String itemKey = UUID.nameUUIDFromBytes(
                    ("workflow-batch:" + key + ":" + item.id()).getBytes(StandardCharsets.UTF_8)).toString();
            // 每条命令独立提交，重试同一批次时沿用同一任务幂等键。
            try {
                tasks.approveBatchItem(actor, item.id(), item.version(), itemKey, input.group());
                results.add(new Result(item.id().toString(), true, null));
            } catch (ApiException error) {
                if (error.getStatus() < 400 || error.getStatus() >= 500
                        || "REQUEST_IN_PROGRESS".equals(error.getErrorCode())) throw error;
                results.add(new Result(item.id().toString(), false, error.getMessage()));
            }
        }
        return results;
    }

    static String group(
            Map<String, Object> instance, Object status, Object kind, Object node, Object owner, Object parent) {
        // 通用表单默认按审批节点隐藏字段，始终保留逐项处理。
        if ("wf_generic_request".equals(instance.get("business_type"))
                || !"running".equals(instance.get("status"))
                || !"pending".equals(status)
                || !"approver".equals(kind)
                || owner != null || parent != null || node == null) return null;
        return instance.get("model_id") + ":" + instance.get("model_version") + ":" + node;
    }
}
