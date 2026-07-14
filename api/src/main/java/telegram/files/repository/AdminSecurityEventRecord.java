package telegram.files.repository;

public record AdminSecurityEventRecord(
        String id,
        String adminAccountId,
        String eventType,
        String result,
        String safeSummary,
        long occurredAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS admin_security_event
            (
                id               VARCHAR(64) PRIMARY KEY,
                admin_account_id VARCHAR(64),
                event_type       VARCHAR(64) NOT NULL,
                result           VARCHAR(32) NOT NULL,
                safe_summary     VARCHAR(1024),
                occurred_at      BIGINT NOT NULL
            )
            """;

    public static class AdminSecurityEventRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
