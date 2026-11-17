package cn.erp.ai.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

public record ApiResponse<T>(int code, String message, T data, String errorCode) {
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(200, "操作成功", data, null); }

    @RestControllerAdvice
    static class Errors {
        private static final System.Logger LOG = System.getLogger(Errors.class.getName());

        @ExceptionHandler(AiException.class)
        ResponseEntity<ApiResponse<Void>> known(AiException error) {
            return ResponseEntity.status(error.status())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ApiResponse<>(error.status(), error.getMessage(), null, error.code()));
        }

        @ExceptionHandler({MissingRequestValueException.class, HttpMessageNotReadableException.class,
                MethodArgumentNotValidException.class, MethodArgumentTypeMismatchException.class})
        ResponseEntity<ApiResponse<Void>> invalid(Exception error) {
            return known(AiException.invalid("请检查请求参数"));
        }

        @ExceptionHandler(MaxUploadSizeExceededException.class)
        ResponseEntity<ApiResponse<Void>> upload(MaxUploadSizeExceededException error) {
            return known(new AiException(413, "AI_FILE_TOO_LARGE", "资料文件大小不能超过20MB"));
        }

        @ExceptionHandler(NoResourceFoundException.class)
        ResponseEntity<ApiResponse<Void>> missing(NoResourceFoundException error) {
            return known(AiException.missing());
        }

        @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
        ResponseEntity<ApiResponse<Void>> method(HttpRequestMethodNotSupportedException error) {
            return known(new AiException(405,"AI_METHOD_NOT_ALLOWED","该接口不支持此操作"));
        }

        @ExceptionHandler(DataIntegrityViolationException.class)
        ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException error) {
            return known(new AiException(409,"AI_DATA_CONFLICT","资料已存在或当前状态不允许修改"));
        }

        @ExceptionHandler(Exception.class)
        ResponseEntity<ApiResponse<Void>> unexpected(Exception error) {
            // 上游错误可能回显资料、模型密钥或请求头，日志只存异常类型。
            LOG.log(System.Logger.Level.ERROR, "AI 请求失败，异常类型：{0}", error.getClass().getSimpleName());
            return known(new AiException(HttpStatus.INTERNAL_SERVER_ERROR.value(),
                    "AI_INTERNAL", "AI 服务暂不可用，请稍后重试"));
        }
    }
}
