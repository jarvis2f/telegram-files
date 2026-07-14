package telegram.files.repository;

public record AdminRecoveryTokenRecord(
        String id,
        String adminAccountId,
        String tokenDigest,
        long expiresAt,
        Long consumedAt,
        long createdAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS admin_recovery_token
            (
                id               VARCHAR(64) PRIMARY KEY,
                admin_account_id VARCHAR(64) NOT NULL UNIQUE,
                token_digest     VARCHAR(128) NOT NULL UNIQUE,
                expires_at       BIGINT NOT NULL,
                consumed_at      BIGINT,
                created_at       BIGINT NOT NULL,
                FOREIGN KEY (admin_account_id) REFERENCES admin_account(id)
            )
            """;

    public static class AdminRecoveryTokenRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
