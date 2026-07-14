package telegram.files.repository;

public record DiskReservationRecord(
        String id,
        String taskId,
        long reservedBytes,
        String status,
        long expiresAt,
        Long releasedAt,
        long createdAt,
        long updatedAt,
        int version
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS disk_reservation
            (
                id                VARCHAR(64) PRIMARY KEY,
                task_id           VARCHAR(128) NOT NULL UNIQUE,
                reserved_bytes    BIGINT NOT NULL,
                status            VARCHAR(32) NOT NULL,
                expires_at        BIGINT NOT NULL,
                released_at       BIGINT,
                created_at        BIGINT NOT NULL,
                updated_at        BIGINT NOT NULL,
                version           INT NOT NULL DEFAULT 0
            )
            """;

    public static final class DiskReservationRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
