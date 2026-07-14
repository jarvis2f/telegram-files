package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.TorrentStatisticEventRecord;
import telegram.files.repository.TorrentStatisticEventRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TorrentStatisticEventRepositoryImpl extends AbstractSqlRepository implements TorrentStatisticEventRepository {
    private final Pool pool;

    public TorrentStatisticEventRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<TorrentStatisticEventRecord> latest(String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("resourceId is required"));
        }
        return preparedQuery("""
                        SELECT * FROM torrent_statistic_event
                        WHERE resource_id = ? ORDER BY observed_at DESC, created_at DESC LIMIT 1
                        """)
                .execute(Tuple.of(resourceId))
                .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    @Override
    public Future<Void> create(TorrentStatisticEventRecord record) {
        validate(record);
        return preparedQuery("""
                        INSERT INTO torrent_statistic_event
                        (event_id, resource_id, info_hash_v1, counter_epoch, uploaded_bytes,
                         downloaded_bytes, seeding_seconds, active_upload_count,
                         completed_upload_count, cumulative_upload_seconds,
                         cumulative_download_seconds, upload_speed_bps, download_speed_bps,
                         upload_sessions_json, torrent_status, observed_at,
                         delivery_state, attempts, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .execute(Tuple.of(
                        record.eventId(), record.resourceId(), record.infoHashV1(),
                        record.counterEpoch(), record.uploadedBytes(), record.downloadedBytes(),
                        record.seedingSeconds(), record.activeUploadCount(), record.completedUploadCount(),
                        record.cumulativeUploadSeconds(), record.cumulativeDownloadSeconds(),
                        record.uploadSpeedBytesPerSecond(), record.downloadSpeedBytesPerSecond(),
                        record.uploadSessionsJson(), record.torrentStatus(), record.observedAt(),
                        record.deliveryState(), record.attempts(), record.createdAt(), record.updatedAt()
                ))
                .mapEmpty();
    }

    @Override
    public Future<List<TorrentStatisticEventRecord>> listPending(int limit) {
        if (limit < 1 || limit > 100) {
            return Future.failedFuture(new IllegalArgumentException("Pending event limit is invalid"));
        }
        return preparedQuery("""
                        SELECT * FROM torrent_statistic_event
                        WHERE delivery_state = 'PENDING'
                        ORDER BY created_at, event_id LIMIT ?
                        """)
                .execute(Tuple.of(limit))
                .map(rows -> {
                    List<TorrentStatisticEventRecord> result = new ArrayList<>();
                    rows.forEach(row -> result.add(map(row)));
                    return List.copyOf(result);
                });
    }

    @Override
    public Future<Void> markDelivered(List<String> eventIds, long now) {
        if (eventIds == null || eventIds.isEmpty()) return Future.succeededFuture();
        List<String> ids = eventIds.stream().filter(Objects::nonNull).distinct().toList();
        String placeholders = ids.stream().map(_ -> "?").collect(Collectors.joining(","));
        List<Object> parameters = new ArrayList<>();
        parameters.add(now);
        parameters.addAll(ids);
        return preparedQuery("""
                        UPDATE torrent_statistic_event
                        SET delivery_state = 'DELIVERED', attempts = attempts + 1, updated_at = ?
                        WHERE event_id IN (%s)
                        """.formatted(placeholders))
                .execute(Tuple.from(parameters))
                .mapEmpty();
    }

    private static TorrentStatisticEventRecord map(Row row) {
        return new TorrentStatisticEventRecord(
                row.getString("event_id"), row.getString("resource_id"), row.getString("info_hash_v1"),
                number(row, "counter_epoch").intValue(), number(row, "uploaded_bytes").longValue(),
                number(row, "downloaded_bytes").longValue(), number(row, "seeding_seconds").longValue(),
                number(row, "active_upload_count").intValue(),
                number(row, "completed_upload_count").intValue(),
                number(row, "cumulative_upload_seconds").longValue(),
                number(row, "cumulative_download_seconds").longValue(),
                number(row, "upload_speed_bps").longValue(),
                number(row, "download_speed_bps").longValue(),
                row.getString("upload_sessions_json"),
                row.getString("torrent_status"), number(row, "observed_at").longValue(),
                row.getString("delivery_state"), number(row, "attempts").intValue(),
                number(row, "created_at").longValue(), number(row, "updated_at").longValue()
        );
    }

    private static Number number(Row row, String name) {
        return (Number) row.getValue(name);
    }

    private static void validate(TorrentStatisticEventRecord record) {
        if (record == null || record.eventId() == null || record.eventId().isBlank()
            || record.resourceId() == null || record.resourceId().isBlank()
            || record.infoHashV1() == null || !record.infoHashV1().matches("[a-f0-9]{40}")
            || record.counterEpoch() < 0 || record.uploadedBytes() < 0 || record.downloadedBytes() < 0
            || record.seedingSeconds() < 0 || record.torrentStatus() == null
            || record.activeUploadCount() < 0 || record.completedUploadCount() < 0
            || record.cumulativeUploadSeconds() < 0 || record.cumulativeDownloadSeconds() < 0
            || record.uploadSpeedBytesPerSecond() < 0 || record.downloadSpeedBytesPerSecond() < 0
            || record.uploadSessionsJson() == null
            || !record.deliveryState().matches("PENDING|DELIVERED")
            || record.attempts() < 0 || record.observedAt() <= 0) {
            throw new IllegalArgumentException("Torrent statistic event is invalid");
        }
    }
}
