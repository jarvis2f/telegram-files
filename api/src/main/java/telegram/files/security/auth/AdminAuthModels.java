package telegram.files.security.auth;

public final class AdminAuthModels {

    private AdminAuthModels() {
    }

    public record BootstrapState(boolean required, String oneTimeToken, long expiresAt) {
        @Override
        public String toString() {
            return "BootstrapState[required=%s, oneTimeToken=[REDACTED], expiresAt=%d]"
                    .formatted(required, expiresAt);
        }
    }

    public record AdminPrincipal(
            String sessionId,
            String accountId,
            String username,
            String csrfDigest,
            long idleExpiresAt,
            long absoluteExpiresAt
    ) {
    }

    public record PasswordRecovery(String username, String oneTimeToken, long expiresAt) {
        @Override
        public String toString() {
            return "PasswordRecovery[username=%s, oneTimeToken=[REDACTED], expiresAt=%d]"
                    .formatted(username, expiresAt);
        }
    }

    public record IssuedSession(
            AdminPrincipal principal,
            String sessionToken,
            String csrfToken
    ) {
        @Override
        public String toString() {
            return "IssuedSession[principal=%s, sessionToken=[REDACTED], csrfToken=[REDACTED]]"
                    .formatted(principal);
        }
    }

    public static final class AuthException extends RuntimeException {
        private final int statusCode;
        private final String errorCode;

        public AuthException(int statusCode, String errorCode, String message) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
        }

        public int statusCode() {
            return statusCode;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
