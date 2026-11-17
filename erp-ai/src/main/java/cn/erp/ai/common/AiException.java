package cn.erp.ai.common;

public class AiException extends RuntimeException {
    private final int status;
    private final String code;

    public AiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static AiException invalid(String message) {
        return new AiException(400, "AI_INVALID_ARGUMENT", message);
    }

    public static AiException missing() {
        return new AiException(404, "AI_NOT_FOUND", "资料不存在或无权查看");
    }

    public static AiException forbidden() {
        return new AiException(403, "AI_FORBIDDEN", "没有执行此操作的权限");
    }
}
