package cn.erp.shared;

public record ApiResponse<T>(int code, String message, T data, String requestId, String errorCode, Object details) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "操作成功", data, RequestIds.current(), null, null);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "创建成功", data, RequestIds.current(), null, null);
    }

    public static ApiResponse<Void> error(int status, String errorCode, String message, Object details) {
        return new ApiResponse<>(status, message, null, RequestIds.current(), errorCode, details);
    }
}
