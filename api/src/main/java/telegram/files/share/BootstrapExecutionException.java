package telegram.files.share;

public final class BootstrapExecutionException extends RuntimeException {

    private final String errorCode;

    private final boolean retryable;

    public BootstrapExecutionException(String errorCode, boolean retryable, String message) {
        super(message);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public BootstrapExecutionException(
            String errorCode,
            boolean retryable,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryable = retryable;
    }

    public String errorCode() {
        return errorCode;
    }

    public boolean retryable() {
        return retryable;
    }
}
