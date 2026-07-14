package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.ShareSourceRecord;
import telegram.files.repository.ShareSourceRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class ShareSourceRepositoryImpl extends AbstractSqlRepository implements ShareSourceRepository {

    private final Pool pool;

    public ShareSourceRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = pool;
    }

    @Override
    public Future<ShareSourceRecord> getById(String id) {
        return preparedQuery("SELECT * FROM share_source WHERE id = ?")
                .execute(Tuple.of(id))
                .map(ShareSourceRepositoryImpl::first);
    }

    @Override
    public Future<ShareSourceRecord> getBySourceKey(String sourceKey) {
        return preparedQuery("SELECT * FROM share_source WHERE source_key = ?")
                .execute(Tuple.of(sourceKey))
                .map(ShareSourceRepositoryImpl::first);
    }

    @Override
    public Future<ShareSourceRecord> getByPlatformResourceId(String platformResourceId) {
        return preparedQuery("SELECT * FROM share_source WHERE platform_resource_id = ?")
                .execute(Tuple.of(platformResourceId))
                .map(ShareSourceRepositoryImpl::first);
    }

    @Override
    public Future<List<ShareSourceRecord>> list() {
        return sqlClient.query("SELECT * FROM share_source ORDER BY created_at DESC, id DESC")
                .execute()
                .map(ShareSourceRepositoryImpl::mapRows);
    }

    @Override
    public Future<List<ShareSourceRecord>> listPage(int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 1000) {
            return Future.failedFuture(new IllegalArgumentException("Share source page is invalid"));
        }
        return preparedQuery("""
                        SELECT * FROM share_source
                        ORDER BY created_at DESC, id DESC
                        LIMIT ? OFFSET ?
                        """)
                .execute(Tuple.of(limit, offset))
                .map(ShareSourceRepositoryImpl::mapRows);
    }

    @Override
    public Future<Long> count() {
        return preparedQuery("SELECT COUNT(*) AS total FROM share_source")
                .execute()
                .map(rows -> number(rows.iterator().next(), "total").longValue());
    }

    @Override
    public Future<List<ShareSourceRecord>> listByFileUniqueIds(List<String> fileUniqueIds) {
        if (fileUniqueIds == null || fileUniqueIds.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        List<String> values = fileUniqueIds.stream().filter(Objects::nonNull).distinct().toList();
        if (values.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        String placeholders = values.stream().map(_ -> "?").collect(Collectors.joining(","));
        return preparedQuery("SELECT * FROM share_source WHERE file_unique_id IN ("
                                       + placeholders + ")")
                .execute(Tuple.from(values))
                .map(ShareSourceRepositoryImpl::mapRows);
    }

    @Override
    public Future<List<ShareSourceRecord>> listRetryable(long now, int limit) {
        if (limit < 1 || limit > 1000) {
            return Future.failedFuture(new IllegalArgumentException("Retry limit is invalid"));
        }
        return preparedQuery("""
                        SELECT * FROM share_source
                        WHERE status IN ('PUBLISH_PENDING', 'UPDATE_PENDING', 'REVOKE_PENDING')
                          AND next_attempt_at <= ?
                        ORDER BY next_attempt_at, id
                        LIMIT ?
                        """)
                .execute(Tuple.of(now, limit))
                .map(ShareSourceRepositoryImpl::mapRows);
    }

    @Override
    public Future<ShareSourceRecord> save(ShareSourceRecord record) {
        return getBySourceKey(record.sourceKey()).compose(existing -> {
            if (existing == null) {
                return preparedQuery("""
                                INSERT INTO share_source
                                (id, source_key, platform_resource_id, file_record_id, file_unique_id,
                                 telegram_id, chat_id, message_id, file_name, file_size, mime_type,
                                 downloaded, access_scope, public_message_url, opaque_token_ciphertext,
                                 opaque_token_digest, title, description, tags_json, category,
                                 immediate_reseed, index_only, auto_download_on_demand,
                                 upload_limit_bytes_per_second, minimum_seed_seconds, status,
                                 create_idempotency_key, update_idempotency_key, revoke_idempotency_key,
                                 attempt_count, next_attempt_at, last_error_code, created_at, updated_at, version)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .execute(parameters(record))
                        .map(record)
                        .recover(failure -> getBySourceKey(record.sourceKey()).compose(raced ->
                                raced == null ? Future.failedFuture(failure) : Future.succeededFuture(raced)));
            }
            ShareSourceRecord updated = withIdentity(record, existing.id(), existing.createdAt(), existing.version());
            return preparedQuery("""
                            UPDATE share_source SET
                              platform_resource_id = ?, file_record_id = ?, file_unique_id = ?,
                              telegram_id = ?, chat_id = ?, message_id = ?, file_name = ?, file_size = ?,
                              mime_type = ?, downloaded = ?, access_scope = ?, public_message_url = ?,
                              opaque_token_ciphertext = ?, opaque_token_digest = ?, title = ?, description = ?,
                              tags_json = ?, category = ?, immediate_reseed = ?, index_only = ?,
                              auto_download_on_demand = ?, upload_limit_bytes_per_second = ?,
                              minimum_seed_seconds = ?, status = ?, create_idempotency_key = ?,
                              update_idempotency_key = ?, revoke_idempotency_key = ?, attempt_count = ?,
                              next_attempt_at = ?, last_error_code = ?, updated_at = ?, version = version + 1
                            WHERE id = ?
                            """)
                    .execute(updateParameters(updated))
                    .compose(_ -> getById(existing.id()));
        });
    }

    @Override
    public Future<Void> markPublished(String id, String platformResourceId, long now) {
        return preparedQuery("""
                        UPDATE share_source SET platform_resource_id = ?, status = 'PUBLISHED',
                          attempt_count = 0, next_attempt_at = ?, last_error_code = NULL,
                          updated_at = ?, version = version + 1 WHERE id = ?
                        """)
                .execute(Tuple.of(platformResourceId, now, now, id))
                .mapEmpty();
    }

    @Override
    public Future<Void> markPending(
            String id,
            String status,
            String errorCode,
            int attemptCount,
            long nextAttemptAt,
            long now
    ) {
        return preparedQuery("""
                        UPDATE share_source SET status = ?, last_error_code = ?, attempt_count = ?,
                          next_attempt_at = ?, updated_at = ?, version = version + 1 WHERE id = ?
                        """)
                .execute(Tuple.of(status, errorCode, attemptCount, nextAttemptAt, now, id))
                .mapEmpty();
    }

    @Override
    public Future<Void> markRevoked(String id, long now) {
        return preparedQuery("""
                        UPDATE share_source SET status = 'REVOKED', attempt_count = 0,
                          next_attempt_at = ?, last_error_code = NULL, updated_at = ?,
                          version = version + 1 WHERE id = ?
                        """)
                .execute(Tuple.of(now, now, id))
                .mapEmpty();
    }

    @Override
    public Future<Void> markDownloaded(String id, long now) {
        return pool.withTransaction(transaction -> preparedQuery(transaction, """
                                UPDATE share_source SET downloaded = 1, updated_at = ?, version = version + 1
                                WHERE id = ? AND status = 'PUBLISHED'
                                """)
                .execute(Tuple.of(now, id)))
                .mapEmpty();
    }

    private static Tuple parameters(ShareSourceRecord record) {
        Tuple tuple = Tuple.tuple();
        tuple.addString(record.id());
        tuple.addString(record.sourceKey());
        tuple.addString(record.platformResourceId());
        tuple.addLong(record.fileRecordId());
        tuple.addString(record.fileUniqueId());
        tuple.addLong(record.telegramId());
        tuple.addLong(record.chatId());
        tuple.addLong(record.messageId());
        tuple.addString(record.fileName());
        tuple.addLong(record.fileSize());
        tuple.addString(record.mimeType());
        tuple.addInteger(record.downloaded() ? 1 : 0);
        tuple.addString(record.accessScope());
        tuple.addString(record.publicMessageUrl());
        tuple.addString(record.opaqueTokenCiphertext());
        tuple.addString(record.opaqueTokenDigest());
        tuple.addString(record.title());
        tuple.addString(record.description());
        tuple.addString(record.tagsJson());
        tuple.addString(record.category());
        tuple.addInteger(record.immediateReseed() ? 1 : 0);
        tuple.addInteger(record.indexOnly() ? 1 : 0);
        tuple.addInteger(record.autoDownloadOnDemand() ? 1 : 0);
        tuple.addString(record.uploadLimitBytesPerSecond());
        tuple.addLong(record.minimumSeedSeconds());
        tuple.addString(record.status());
        tuple.addString(record.createIdempotencyKey());
        tuple.addString(record.updateIdempotencyKey());
        tuple.addString(record.revokeIdempotencyKey());
        tuple.addInteger(record.attemptCount());
        tuple.addLong(record.nextAttemptAt());
        tuple.addString(record.lastErrorCode());
        tuple.addLong(record.createdAt());
        tuple.addLong(record.updatedAt());
        tuple.addInteger(record.version());
        return tuple;
    }

    private static Tuple updateParameters(ShareSourceRecord record) {
        Tuple insert = parameters(record);
        Tuple update = Tuple.tuple();
        for (int index = 2; index <= 31; index++) {
            update.addValue(insert.getValue(index));
        }
        update.addValue(insert.getValue(33));
        update.addString(record.id());
        return update;
    }

    private static ShareSourceRecord first(RowSet<Row> rows) {
        Row row = rows.iterator().hasNext() ? rows.iterator().next() : null;
        return row == null ? null : map(row);
    }

    private static List<ShareSourceRecord> mapRows(RowSet<Row> rows) {
        List<ShareSourceRecord> records = new ArrayList<>();
        for (Row row : rows) {
            records.add(map(row));
        }
        return records;
    }

    private static ShareSourceRecord map(Row row) {
        return new ShareSourceRecord(
                row.getString("id"),
                row.getString("source_key"),
                row.getString("platform_resource_id"),
                number(row, "file_record_id").longValue(),
                row.getString("file_unique_id"),
                number(row, "telegram_id").longValue(),
                number(row, "chat_id").longValue(),
                number(row, "message_id").longValue(),
                row.getString("file_name"),
                number(row, "file_size").longValue(),
                row.getString("mime_type"),
                number(row, "downloaded").intValue() == 1,
                row.getString("access_scope"),
                row.getString("public_message_url"),
                row.getString("opaque_token_ciphertext"),
                row.getString("opaque_token_digest"),
                row.getString("title"),
                row.getString("description"),
                row.getString("tags_json"),
                row.getString("category"),
                number(row, "immediate_reseed").intValue() == 1,
                number(row, "index_only").intValue() == 1,
                number(row, "auto_download_on_demand").intValue() == 1,
                row.getString("upload_limit_bytes_per_second"),
                number(row, "minimum_seed_seconds").longValue(),
                row.getString("status"),
                row.getString("create_idempotency_key"),
                row.getString("update_idempotency_key"),
                row.getString("revoke_idempotency_key"),
                number(row, "attempt_count").intValue(),
                number(row, "next_attempt_at").longValue(),
                row.getString("last_error_code"),
                number(row, "created_at").longValue(),
                number(row, "updated_at").longValue(),
                number(row, "version").intValue()
        );
    }

    private static Number number(Row row, String column) {
        return (Number) row.getValue(column);
    }

    private static ShareSourceRecord withIdentity(
            ShareSourceRecord record,
            String id,
            long createdAt,
            int version
    ) {
        return new ShareSourceRecord(
                id, record.sourceKey(), record.platformResourceId(), record.fileRecordId(),
                record.fileUniqueId(), record.telegramId(), record.chatId(), record.messageId(),
                record.fileName(), record.fileSize(), record.mimeType(), record.downloaded(),
                record.accessScope(), record.publicMessageUrl(), record.opaqueTokenCiphertext(),
                record.opaqueTokenDigest(), record.title(), record.description(), record.tagsJson(),
                record.category(), record.immediateReseed(), record.indexOnly(),
                record.autoDownloadOnDemand(), record.uploadLimitBytesPerSecond(),
                record.minimumSeedSeconds(), record.status(), record.createIdempotencyKey(),
                record.updateIdempotencyKey(), record.revokeIdempotencyKey(), record.attemptCount(),
                record.nextAttemptAt(), record.lastErrorCode(), createdAt, record.updatedAt(), version
        );
    }
}
