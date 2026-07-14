package telegram.files.repository;

public record TorrentStatisticEventRecord(
        String eventId,
        String resourceId,
        String infoHashV1,
        int counterEpoch,
        long uploadedBytes,
        long downloadedBytes,
        long seedingSeconds,
        int activeUploadCount,
        int completedUploadCount,
        long cumulativeUploadSeconds,
        long cumulativeDownloadSeconds,
        long uploadSpeedBytesPerSecond,
        long downloadSpeedBytesPerSecond,
        String uploadSessionsJson,
        String torrentStatus,
        long observedAt,
        String deliveryState,
        int attempts,
        long createdAt,
        long updatedAt
) {
    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS torrent_statistic_event
            (
                event_id          VARCHAR(128) PRIMARY KEY,
                resource_id       VARCHAR(128) NOT NULL,
                info_hash_v1      VARCHAR(40) NOT NULL,
                counter_epoch     INT NOT NULL,
                uploaded_bytes    BIGINT NOT NULL,
                downloaded_bytes  BIGINT NOT NULL,
                seeding_seconds   BIGINT NOT NULL,
                active_upload_count INT NOT NULL,
                completed_upload_count INT NOT NULL,
                cumulative_upload_seconds BIGINT NOT NULL,
                cumulative_download_seconds BIGINT NOT NULL,
                upload_speed_bps BIGINT NOT NULL,
                download_speed_bps BIGINT NOT NULL,
                upload_sessions_json TEXT NOT NULL,
                torrent_status    VARCHAR(32) NOT NULL,
                observed_at       BIGINT NOT NULL,
                delivery_state    VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                attempts          INT NOT NULL DEFAULT 0,
                created_at        BIGINT NOT NULL,
                updated_at        BIGINT NOT NULL
            )
            """;

    public static final class TorrentStatisticEventRecordDefinition implements Definition {

        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
