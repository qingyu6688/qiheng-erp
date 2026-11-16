package cn.erp.workflow;

import org.flowable.common.engine.api.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import cn.erp.shared.*;

@RestControllerAdvice
class WorkflowErrors {
    private final ApiErrors errors;

    WorkflowErrors(ApiErrors errors) {
        this.errors = errors;
    }

    @ExceptionHandler(FlowableException.class)
    ResponseEntity<ApiResponse<Void>> engine(FlowableException error) {
        Throwable cause = error;
        for (int i = 0; i < 15 && cause != null; i++, cause = cause.getCause())
            if (cause instanceof ApiException business) return errors.api(business);
        if (error instanceof FlowableOptimisticLockingException)
            return errors.api(new ApiException(409, "WORKFLOW_CONFLICT", "此任务已被其他请求更新，请刷新后重试"));
        if (error instanceof FlowableObjectNotFoundException)
            return errors.api(new ApiException(404, "NOT_FOUND", "流程或任务不存在，请刷新后查看"));
        return errors.api(
                new ApiException(500, "WORKFLOW_ENGINE_ERROR", "审批处理未完成，请保留原请求标识重试或联系管理员"));
    }
}
