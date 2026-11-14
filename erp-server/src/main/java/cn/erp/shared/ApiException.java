package cn.erp.shared;

public class ApiException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final transient Object details;

    public ApiException(int status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public ApiException(int status, String errorCode, String message, Object details) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.details = details;
    }

    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public Object getDetails() { return details; }
}
