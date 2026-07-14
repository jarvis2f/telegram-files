package telegram.files.repository;

public record NodeTaskExecutionRecord(
        String taskId,
        String attemptId,
        String taskType,
        int schemaVersion,
        String payloadDigest,
        String envelopeCiphertext,
        String state,
        int progressSequence,
        int reportedSequence,
        String progressJson,
        String resultJson,
        String errorCode,
        long createdAt,
        long updatedAt,
        int version
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS node_task_execution
            (
                task_id                VARCHAR(128) PRIMARY KEY,
                attempt_id             VARCHAR(128) NOT NULL,
                task_type              VARCHAR(64) NOT NULL,
                schema_version         INT NOT NULL,
                payload_digest         VARCHAR(64) NOT NULL,
                envelope_ciphertext    TEXT NOT NULL,
                state                  VARCHAR(64) NOT NULL,
                progress_sequence      INT NOT NULL DEFAULT -1,
                reported_sequence      INT NOT NULL DEFAULT -1,
                progress_json          TEXT,
                result_json            TEXT,
                error_code             VARCHAR(64),
                created_at             BIGINT NOT NULL,
                updated_at             BIGINT NOT NULL,
                version                INT NOT NULL DEFAULT 0
            )
            """;

    public boolean terminal() {
        return "COMPLETED".equals(state) || "FAILED".equals(state) || "OBSOLETE".equals(state);
    }

    public boolean pendingTerminalReport() {
        return "COMPLETED_PENDING_REPORT".equals(state)
               || "FAILED_PENDING_REPORT".equals(state);
    }

    public static final class NodeTaskExecutionRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
