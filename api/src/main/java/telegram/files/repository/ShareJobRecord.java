package telegram.files.repository;

public record ShareJobRecord(
        String id,
        String jobType,
        String idempotencyKey,
        String status,
        Long fileRecordId,
        Long recordVersion,
        Long telegramId,
        int attemptCount,
        long nextAttemptAt,
        String lastErrorCode,
        long createdAt,
        long updatedAt
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS share_job
            (
                id              VARCHAR(64) PRIMARY KEY,
                job_type        VARCHAR(64) NOT NULL,
                idempotency_key VARCHAR(255) NOT NULL UNIQUE,
                status          VARCHAR(32) NOT NULL,
                file_record_id  BIGINT,
                record_version  BIGINT,
                telegram_id     BIGINT,
                attempt_count   INT NOT NULL DEFAULT 0,
                next_attempt_at BIGINT NOT NULL,
                last_error_code VARCHAR(64),
                created_at      BIGINT NOT NULL,
                updated_at      BIGINT NOT NULL
            )
            """;

    public static class ShareJobRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
