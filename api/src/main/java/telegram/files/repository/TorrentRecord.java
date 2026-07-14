package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;

import java.util.List;

public record TorrentRecord(
        String id,
        String resourceId,
        String contentSha256,
        String infoHashV1,
        String torrentRelativePath,
        String viewRelativePath,
        String fileName,
        long fileSize,
        String mimeType,
        String telegramFileUniqueId,
        String acquiredVia,
        Long completedAt,
        String status,
        int progressPermille,
        long downloadedBytes,
        long uploadedBytes,
        long downloadSpeedBytesPerSecond,
        long uploadSpeedBytesPerSecond,
        int connectedPeers,
        String savePath,
        String trackerBaseUrl,
        long seedingSeconds,
        long lastSynchronizedAt,
        long createdAt,
        long updatedAt,
        int version
) {

    /**
     * Compatibility constructor for M4 callers while the M4.5 columns are backfilled.
     */
    public TorrentRecord(
            String id, String resourceId, String contentSha256, String infoHashV1,
            String torrentRelativePath, String viewRelativePath, String status,
            int progressPermille, long downloadedBytes, long uploadedBytes,
            long downloadSpeedBytesPerSecond, long uploadSpeedBytesPerSecond,
            int connectedPeers, String savePath, String trackerBaseUrl,
            long seedingSeconds, long lastSynchronizedAt, long createdAt, long updatedAt, int version
    ) {
        this(id, resourceId, contentSha256, infoHashV1, torrentRelativePath, viewRelativePath,
                null, 0, null, null, "TELEGRAM", null, status, progressPermille,
                downloadedBytes, uploadedBytes, downloadSpeedBytesPerSecond,
                uploadSpeedBytesPerSecond, connectedPeers, savePath, trackerBaseUrl,
                seedingSeconds, lastSynchronizedAt, createdAt, updatedAt, version);
    }

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS torrent_record
            (
                id                   VARCHAR(64) PRIMARY KEY,
                resource_id          VARCHAR(128) NOT NULL UNIQUE,
                content_sha256       VARCHAR(64) NOT NULL,
                info_hash_v1         VARCHAR(40) NOT NULL UNIQUE,
                torrent_relative_path VARCHAR(1024) NOT NULL,
                view_relative_path   VARCHAR(1024) NOT NULL,
                file_name            VARCHAR(255),
                file_size            BIGINT NOT NULL DEFAULT 0,
                mime_type            VARCHAR(255),
                telegram_file_unique_id VARCHAR(512),
                acquired_via         VARCHAR(16) NOT NULL DEFAULT 'TELEGRAM',
                completed_at         BIGINT,
                status               VARCHAR(32) NOT NULL,
                progress_permille    INT NOT NULL DEFAULT 0,
                downloaded_bytes     BIGINT NOT NULL DEFAULT 0,
                uploaded_bytes       BIGINT NOT NULL DEFAULT 0,
                download_speed_bps   BIGINT NOT NULL DEFAULT 0,
                upload_speed_bps     BIGINT NOT NULL DEFAULT 0,
                connected_peers      INT NOT NULL DEFAULT 0,
                save_path            VARCHAR(2048) NOT NULL,
                tracker_base_url     VARCHAR(2048) NOT NULL,
                seeding_seconds      BIGINT NOT NULL DEFAULT 0,
                last_synchronized_at BIGINT NOT NULL,
                created_at           BIGINT NOT NULL,
                updated_at           BIGINT NOT NULL,
                version              INT NOT NULL DEFAULT 0
            )
            """;

    public static final class TorrentRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public Future<Void> createTable(SqlClient sqlClient) {
            List<String> columns = List.of(
                    "CREATE INDEX idx_torrent_telegram_file ON torrent_record (telegram_file_unique_id, file_size)",
                    "CREATE INDEX idx_torrent_content ON torrent_record (content_sha256, file_size)"
            );
            return Definition.super.createTable(sqlClient).compose(_ -> Future.all(
                    columns.stream()
                            .map(sql -> sqlClient.query(sql).execute()
                                    .recover(_ -> Future.succeededFuture()))
                            .toList()
            ).mapEmpty());
        }
    }
}
