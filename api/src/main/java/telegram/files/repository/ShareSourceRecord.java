package telegram.files.repository;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public record ShareSourceRecord(
        String id,
        String sourceKey,
        String platformResourceId,
        long fileRecordId,
        String fileUniqueId,
        long telegramId,
        long chatId,
        long messageId,
        String fileName,
        long fileSize,
        String mimeType,
        boolean downloaded,
        String accessScope,
        String publicMessageUrl,
        String opaqueTokenCiphertext,
        String opaqueTokenDigest,
        String title,
        String description,
        String tagsJson,
        String category,
        boolean immediateReseed,
        boolean indexOnly,
        boolean autoDownloadOnDemand,
        String uploadLimitBytesPerSecond,
        long minimumSeedSeconds,
        String status,
        String createIdempotencyKey,
        String updateIdempotencyKey,
        String revokeIdempotencyKey,
        int attemptCount,
        long nextAttemptAt,
        String lastErrorCode,
        long createdAt,
        long updatedAt,
        int version
) {

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS share_source
            (
                id                            VARCHAR(64) PRIMARY KEY,
                source_key                    VARCHAR(64) NOT NULL UNIQUE,
                platform_resource_id          VARCHAR(128),
                file_record_id                BIGINT NOT NULL,
                file_unique_id                VARCHAR(512) NOT NULL,
                telegram_id                   BIGINT NOT NULL,
                chat_id                       BIGINT NOT NULL,
                message_id                    BIGINT NOT NULL,
                file_name                     VARCHAR(255) NOT NULL,
                file_size                     BIGINT NOT NULL,
                mime_type                     VARCHAR(255) NOT NULL,
                downloaded                    INT NOT NULL,
                access_scope                  VARCHAR(32) NOT NULL,
                public_message_url            VARCHAR(2048),
                opaque_token_ciphertext       TEXT NOT NULL,
                opaque_token_digest           VARCHAR(64) NOT NULL,
                title                         VARCHAR(255) NOT NULL,
                description                   VARCHAR(4096),
                tags_json                     TEXT NOT NULL,
                category                      VARCHAR(64),
                immediate_reseed              INT NOT NULL,
                index_only                    INT NOT NULL,
                auto_download_on_demand       INT NOT NULL,
                upload_limit_bytes_per_second VARCHAR(32),
                minimum_seed_seconds          BIGINT NOT NULL,
                status                        VARCHAR(32) NOT NULL,
                create_idempotency_key        VARCHAR(128) NOT NULL,
                update_idempotency_key        VARCHAR(128) NOT NULL,
                revoke_idempotency_key        VARCHAR(128) NOT NULL,
                attempt_count                 INT NOT NULL DEFAULT 0,
                next_attempt_at               BIGINT NOT NULL,
                last_error_code               VARCHAR(64),
                created_at                    BIGINT NOT NULL,
                updated_at                    BIGINT NOT NULL,
                version                       INT NOT NULL DEFAULT 0
            )
            """;

    public JsonObject toPublicJson() {
        return new JsonObject()
                .put("sourceId", id)
                .put("resourceId", platformResourceId)
                .put("fileUniqueId", fileUniqueId)
                .put("fileName", fileName)
                .put("fileSize", Long.toString(fileSize))
                .put("mimeType", mimeType)
                .put("downloaded", downloaded)
                .put("accessScope", accessScope)
                .put("publicMessageUrl", publicMessageUrl)
                .put("title", title)
                .put("description", description)
                .put("tags", new JsonArray(tagsJson))
                .put("category", category)
                .put("status", status)
                .put("attemptCount", attemptCount)
                .put("lastErrorCode", lastErrorCode)
                .put("createdAt", createdAt)
                .put("updatedAt", updatedAt);
    }

    public static final class ShareSourceRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }
    }
}
