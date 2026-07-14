package telegram.files.repository;

public record AdminSessionRecord(
        String id,
        String adminAccountId,
        String tokenDigest,
        String csrfDigest,
        long idleExpiresAt,
        long absoluteExpiresAt,
        long lastSeenAt,
        Long revokedAt,
        long createdAt,
        long accountSessionVersion
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS admin_session
            (
                id                  VARCHAR(64) PRIMARY KEY,
                admin_account_id    VARCHAR(64) NOT NULL,
                token_digest        VARCHAR(128) NOT NULL UNIQUE,
                csrf_digest         VARCHAR(128) NOT NULL,
                idle_expires_at     BIGINT NOT NULL,
                absolute_expires_at BIGINT NOT NULL,
                last_seen_at        BIGINT NOT NULL,
                revoked_at          BIGINT,
                created_at          BIGINT NOT NULL,
                account_session_version BIGINT NOT NULL DEFAULT 0,
                FOREIGN KEY (admin_account_id) REFERENCES admin_account(id)
            )
            """;

    public static class AdminSessionRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
