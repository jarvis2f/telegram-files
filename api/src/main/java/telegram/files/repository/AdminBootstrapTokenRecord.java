package telegram.files.repository;

public record AdminBootstrapTokenRecord(
        String id,
        String tokenDigest,
        long expiresAt,
        Long consumedAt,
        long createdAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS admin_bootstrap_token
            (
                id           VARCHAR(64) PRIMARY KEY,
                token_digest VARCHAR(128) NOT NULL UNIQUE,
                expires_at   BIGINT NOT NULL,
                consumed_at  BIGINT,
                created_at   BIGINT NOT NULL
            )
            """;

    public static class AdminBootstrapTokenRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
