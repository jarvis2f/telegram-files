package telegram.files.repository;

public record TorrentUploadSessionRecord(
        String sessionKey,
        String peerKey,
        String resourceId,
        String infoHashV1,
        long startedAt,
        long lastObservedAt,
        Long endedAt,
        long uploadedBytes,
        long lastClientCounter,
        int counterEpoch,
        String status,
        long createdAt,
        long updatedAt
) {
    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS torrent_upload_session
            (
                session_key        VARCHAR(128) PRIMARY KEY,
                peer_key           VARCHAR(128) NOT NULL,
                resource_id        VARCHAR(128) NOT NULL,
                info_hash_v1       VARCHAR(40) NOT NULL,
                started_at         BIGINT NOT NULL,
                last_observed_at   BIGINT NOT NULL,
                ended_at           BIGINT,
                uploaded_bytes     BIGINT NOT NULL,
                last_client_counter BIGINT NOT NULL,
                counter_epoch      INT NOT NULL,
                status             VARCHAR(16) NOT NULL,
                created_at         BIGINT NOT NULL,
                updated_at         BIGINT NOT NULL
            )
            """;

    public static final class TorrentUploadSessionRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
