package de.alexanderlindhorst.imapmcp.core.error;

/** Tool error codes (v1 and the v2 write codes). The retryable flag is fixed per code. */
public enum ErrorCode {
    INVALID_ARGUMENT(false),
    ACCOUNT_NOT_FOUND(false),
    INVALID_HANDLE(false),
    INVALID_CURSOR(false),
    STALE_REFERENCE(false),
    MESSAGE_NOT_FOUND(false),
    FOLDER_NOT_FOUND(false),
    AUTH_FAILED(false),
    SERVER_BUSY(true),
    TIMEOUT(true),
    UPSTREAM_UNAVAILABLE(true),
    UPSTREAM_ERROR(false),
    UNSUPPORTED(false),
    INTERNAL_ERROR(false),
    PERMISSION_DENIED(false),
    QUOTA_EXCEEDED(false),
    OUTCOME_UNKNOWN(false);

    private final boolean retryable;

    ErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
