package cn.erp.shared;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponse<Void>> api(ApiException error) {
        return ResponseEntity.status(error.getStatus()).body(ApiResponse.error(error.getStatus(),
                error.getErrorCode(), error.getMessage(), error.getDetails()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> validation(MethodArgumentNotValidException error) {
        var details = error.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(), "message",
                        field.getDefaultMessage() == null ? "字段填写有误" : field.getDefaultMessage())).toList();
        return api(new ApiException(400, "VALIDATION_FAILED", "请检查填写的内容", details));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponse<Void>> format() {
        return api(new ApiException(400, "VALIDATION_FAILED", "请求字段格式有误"));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> constraint(DataIntegrityViolationException error) {
        ApiException result = ConstraintErrors.from(error.getMostSpecificCause().getMessage());
        if (result.getStatus() == 500) { LOG.error("未映射的数据约束异常，请求号 {}", RequestIds.current()); }
        return api(result);
    }

    @ExceptionHandler(org.springframework.jdbc.UncategorizedSQLException.class)
    public ResponseEntity<ApiResponse<Void>> mysqlCheck(org.springframework.jdbc.UncategorizedSQLException error) {
        if(error.getSQLException().getErrorCode()==3819) {
            return api(ConstraintErrors.from(error.getSQLException().getMessage()));
        }
        return unexpected(error);
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    public ResponseEntity<ApiResponse<Void>> unavailable() {
        return api(new ApiException(503, "SERVICE_UNAVAILABLE", "数据服务暂不可用，请稍后重试", Map.of("retryable", true)));
    }

    @ExceptionHandler(org.springframework.dao.TransientDataAccessException.class)
    public ResponseEntity<ApiResponse<Void>> contention() {
        return api(new ApiException(503,"SERVICE_UNAVAILABLE","当前操作发生并发竞争，请使用原请求标识稍后重试",Map.of("retryable",true)));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
        if (error instanceof ErrorResponse webError) {
            int status = webError.getStatusCode().value();
            return api(new ApiException(status, status == 404 ? "NOT_FOUND" : "INVALID_REQUEST",
                    status == 404 ? "访问的地址不存在" : "请求方式或参数有误"));
        }
        LOG.error("请求处理失败，请求号 {}", RequestIds.current(), error);
        return api(new ApiException(500, "INTERNAL_ERROR", "处理请求时发生错误，请联系管理员"));
    }
}
