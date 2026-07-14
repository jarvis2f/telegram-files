package telegram.files.repository;

public record AdminAccountRecord(
        String id,
        String username,
        String passwordHash,
        String passwordParameters,
        String status,
        long sessionVersion,
        long createdAt,
        long updatedAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS admin_account
            (
                id                  VARCHAR(64) PRIMARY KEY,
                username            VARCHAR(128) NOT NULL UNIQUE,
                password_hash       VARCHAR(1024) NOT NULL,
                password_parameters VARCHAR(1024) NOT NULL,
                status              VARCHAR(32) NOT NULL,
                session_version     BIGINT NOT NULL DEFAULT 0,
                created_at          BIGINT NOT NULL,
                updated_at          BIGINT NOT NULL
            )
            """;

    public static class AdminAccountRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
