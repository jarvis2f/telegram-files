package telegram.files.repository.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.MessyUtils;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public final class TorrentRepositoryImpl extends AbstractSqlRepository implements TorrentRepository {

    private final Pool pool;

    public TorrentRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<TorrentRecord> save(TorrentRecord record) {
        Objects.requireNonNull(record, "record");
        validate(record);
        return pool.withTransaction(transaction -> getByResourceId(transaction, record.resourceId())
                .compose(existing -> {
                    if (existing == null) {
                        return preparedQuery(transaction, """
                                        INSERT INTO torrent_record
                                        (id, resource_id, content_sha256, info_hash_v1,
                                         torrent_relative_path, view_relative_path, file_name, file_size,
                                         mime_type, telegram_file_unique_id, acquired_via, completed_at, status,
                                         progress_permille, downloaded_bytes, uploaded_bytes,
                                         download_speed_bps, upload_speed_bps, connected_peers, save_path, tracker_base_url,
                                         seeding_seconds, last_synchronized_at, created_at, updated_at, version)
                                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                        """)
                                .execute(parameters(record))
                                .map(record);
                    }
                    if (!existing.infoHashV1().equals(record.infoHashV1())
                        || !existing.contentSha256().equals(record.contentSha256())) {
                        return Future.failedFuture(new IllegalStateException("Torrent identity conflict"));
                    }
                    return preparedQuery(transaction, """
                                    UPDATE torrent_record SET torrent_relative_path = ?, view_relative_path = ?,
                                      file_name = ?, file_size = ?, mime_type = ?, telegram_file_unique_id = ?,
                                      acquired_via = ?, completed_at = ?,
                                      status = ?, progress_permille = ?, downloaded_bytes = ?,
                                      uploaded_bytes = ?, download_speed_bps = ?, upload_speed_bps = ?,
                                      connected_peers = ?, save_path = ?, tracker_base_url = ?,
                                      seeding_seconds = ?, last_synchronized_at = ?,
                                      updated_at = ?, version = version + 1 WHERE resource_id = ?
                                    """)
                            .execute(Tuple.of(
                                    record.torrentRelativePath(), record.viewRelativePath(), record.fileName(),
                                    record.fileSize(), record.mimeType(), record.telegramFileUniqueId(),
                                    record.acquiredVia(), record.completedAt(), record.status(),
                                    record.progressPermille(), record.downloadedBytes(), record.uploadedBytes(),
                                    record.downloadSpeedBytesPerSecond(), record.uploadSpeedBytesPerSecond(),
                                    record.connectedPeers(), record.savePath(), record.trackerBaseUrl(),
                                    record.seedingSeconds(), record.lastSynchronizedAt(), record.updatedAt(),
                                    record.resourceId()
                            ))
                            .compose(_ -> getByResourceId(transaction, record.resourceId()));
                }));
    }

    @Override
    public Future<TorrentRecord> getByResourceId(String resourceId) {
        return getByResourceId(pool, resourceId);
    }

    @Override
    public Future<TorrentRecord> getByInfoHash(String infoHashV1) {
        return get(pool, "info_hash_v1", infoHashV1);
    }

    @Override
    public Future<List<TorrentRecord>> listActive(int limit) {
        if (limit < 1 || limit > 10_000) {
            return Future.failedFuture(new IllegalArgumentException("Torrent list limit is invalid"));
        }
        return preparedQuery(pool, "SELECT * FROM torrent_record WHERE status <> 'STOPPED' ORDER BY updated_at LIMIT ?")
                .execute(Tuple.of(limit))
                .map(rows -> {
                    java.util.ArrayList<TorrentRecord> records = new java.util.ArrayList<>();
                    rows.forEach(row -> records.add(map(row)));
                    return List.copyOf(records);
                });
    }

    @Override
    public Future<List<TorrentRecord>> listByTelegramFileUniqueIds(List<String> fileUniqueIds) {
        if (fileUniqueIds == null || fileUniqueIds.isEmpty()) {
            return Future.succeededFuture(List.of());
        }
        List<String> values = fileUniqueIds.stream().filter(Objects::nonNull).distinct().toList();
        if (values.isEmpty()) return Future.succeededFuture(List.of());
        String placeholders = values.stream().map(_ -> "?").collect(Collectors.joining(","));
        return preparedQuery(pool, "SELECT * FROM torrent_record WHERE telegram_file_unique_id IN ("
                                  + placeholders + ")")
                .execute(Tuple.from(values))
                .map(rows -> {
                    java.util.ArrayList<TorrentRecord> records = new java.util.ArrayList<>();
                    rows.forEach(row -> records.add(map(row)));
                    return List.copyOf(records);
                });
    }

    @Override
    public Future<Tuple2<List<TorrentRecord>, Long>> listSeedOnly(Map<String, String> filter) {
        int limit = Math.max(1, Math.min(Convert.toInt(filter.get("limit"), 20), 100));
        int offset = Math.max(0, Convert.toInt(filter.get("seedOffset"), 0));
        SeedOnlyQuery query = seedOnlyQuery(filter);
        String sortColumn = switch (filter.getOrDefault("sort", "date")) {
            case "size" -> "file_size";
            case "reaction_count" -> "0";
            default -> "COALESCE(completed_at, updated_at)";
        };
        String order = "asc".equalsIgnoreCase(filter.get("order")) ? "ASC" : "DESC";
        List<Object> pageParameters = new ArrayList<>(query.parameters());
        pageParameters.add(limit);
        pageParameters.add(offset);
        return Future.all(
                preparedQuery(pool, "SELECT * FROM torrent_record WHERE " + query.where()
                                   + " ORDER BY " + sortColumn + " " + order + ", resource_id " + order
                                   + " LIMIT ? OFFSET ?")
                        .execute(Tuple.from(pageParameters)),
                preparedQuery(pool, "SELECT COUNT(*) AS total FROM torrent_record WHERE " + query.where())
                        .execute(Tuple.from(query.parameters()))
        ).map(results -> {
            List<TorrentRecord> records = new ArrayList<>();
            results.<io.vertx.sqlclient.RowSet<Row>>resultAt(0).forEach(row -> records.add(map(row)));
            Row count = results.<io.vertx.sqlclient.RowSet<Row>>resultAt(1).iterator().next();
            return org.jooq.lambda.tuple.Tuple.tuple(List.copyOf(records), count.getLong("total"));
        });
    }

    @Override
    public Future<JsonObject> countSeedOnlyWithType(Map<String, String> filter) {
        SeedOnlyQuery query = seedOnlyQuery(filter);
        return preparedQuery(pool, "SELECT COUNT(*) AS total FROM torrent_record WHERE " + query.where())
                .execute(Tuple.from(query.parameters()))
                .map(rows -> {
                    Row row = rows.iterator().next();
                    Number value = (Number) row.getValue("total");
                    int total = value == null ? 0 : Math.max(0, value.intValue());
                    return new JsonObject()
                            .put("file", total)
                            .put("media", 0)
                            .put("photo", 0)
                            .put("video", 0)
                            .put("audio", 0);
                });
    }

    @Override
    public Future<Boolean> updateStatus(
            String infoHashV1,
            String status,
            int progressPermille,
            long downloadedBytes,
            long uploadedBytes,
            long downloadSpeedBytesPerSecond,
            long uploadSpeedBytesPerSecond,
            int connectedPeers,
            String savePath,
            long now
    ) {
        if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")
            || status == null || status.isBlank() || progressPermille < 0 || progressPermille > 1000
            || downloadedBytes < 0 || uploadedBytes < 0
            || downloadSpeedBytesPerSecond < 0 || uploadSpeedBytesPerSecond < 0
            || connectedPeers < 0 || savePath == null || savePath.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("Torrent status update is invalid"));
        }
        return pool.withTransaction(transaction -> preparedQuery(transaction, """
                                UPDATE torrent_record SET status = ?, progress_permille = ?,
                                  downloaded_bytes = ?, uploaded_bytes = ?, last_synchronized_at = ?,
                                  download_speed_bps = ?, upload_speed_bps = ?, connected_peers = ?,
                                  save_path = ?, updated_at = ?, version = version + 1 WHERE info_hash_v1 = ?
                                """)
                        .execute(Tuple.of(
                                status, progressPermille, downloadedBytes, uploadedBytes, now,
                                downloadSpeedBytesPerSecond, uploadSpeedBytesPerSecond, connectedPeers,
                                savePath, now, infoHashV1
                        )))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Integer> countByStatuses(List<String> statuses) {
        if (statuses == null || statuses.isEmpty()
            || statuses.stream().anyMatch(status -> status == null || !status.matches("[A-Z_]{2,32}"))) {
            return Future.failedFuture(new IllegalArgumentException("Torrent status list is invalid"));
        }
        String placeholders = statuses.stream().map(_ -> "?").collect(Collectors.joining(","));
        return preparedQuery(pool, "SELECT COUNT(*) AS total FROM torrent_record WHERE status IN ("
                                  + placeholders + ")")
                .execute(Tuple.from(statuses))
                .map(rows -> {
                    Number value = (Number) rows.iterator().next().getValue("total");
                    return value == null ? 0 : Math.max(0, value.intValue());
                });
    }

    private Future<TorrentRecord> getByResourceId(SqlClient client, String resourceId) {
        return get(client, "resource_id", resourceId);
    }

    private Future<TorrentRecord> get(SqlClient client, String column, String value) {
        if (value == null || value.isBlank()) {
            return Future.failedFuture(new IllegalArgumentException("Torrent lookup value is required"));
        }
        return preparedQuery(client, "SELECT * FROM torrent_record WHERE " + column + " = ?")
                .execute(Tuple.of(value))
                .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    private static SeedOnlyQuery seedOnlyQuery(Map<String, String> filter) {
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> parameters = new ArrayList<>();
        String search = filter.get("search");
        if (StrUtil.isNotBlank(search)) {
            where.append(" AND file_name LIKE ?");
            parameters.add("%" + search + "%");
        }
        String type = filter.get("type");
        if (StrUtil.isNotBlank(type) && !"all".equals(type) && !"file".equals(type)) {
            where.append(" AND 1 = 0");
        }
        String downloadStatus = filter.get("downloadStatus");
        if (StrUtil.isNotBlank(downloadStatus)) {
            switch (downloadStatus) {
                case "completed" -> where.append(" AND progress_permille = 1000");
                case "downloading" -> where.append(" AND progress_permille < 1000 AND status <> 'PAUSED'");
                case "paused" -> where.append(" AND status = 'PAUSED'");
                case "error" -> where.append(" AND status = 'ERROR'");
                default -> where.append(" AND 1 = 0");
            }
        }
        String transferStatus = filter.get("transferStatus");
        if (StrUtil.isNotBlank(transferStatus) && !"idle".equals(transferStatus)) {
            where.append(" AND 1 = 0");
        }
        if (StrUtil.isNotBlank(filter.get("tags"))
            || Convert.toLong(filter.get("messageThreadId"), 0L) != 0) {
            where.append(" AND 1 = 0");
        }
        String sizeRange = filter.get("sizeRange");
        String sizeUnit = filter.get("sizeUnit");
        if (StrUtil.isNotBlank(sizeRange) && StrUtil.isNotBlank(sizeUnit)) {
            String[] sizes = sizeRange.split(",");
            if (sizes.length == 2) {
                where.append(" AND file_size BETWEEN ? AND ?");
                parameters.add(MessyUtils.convertToByte(Convert.toLong(sizes[0]), sizeUnit));
                parameters.add(MessyUtils.convertToByte(Convert.toLong(sizes[1]), sizeUnit));
            }
        }
        String dateRange = filter.get("dateRange");
        if (StrUtil.isNotBlank(dateRange)) {
            String[] dates = dateRange.split(",");
            if (dates.length == 2) {
                long start = java.time.LocalDate.parse(dates[0]).atStartOfDay()
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                long end = java.time.LocalDate.parse(dates[1]).atTime(java.time.LocalTime.MAX)
                        .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                where.append(" AND COALESCE(completed_at, updated_at) BETWEEN ? AND ?");
                parameters.add(start);
                parameters.add(end);
            }
        }
        return new SeedOnlyQuery(where.toString(), List.copyOf(parameters));
    }

    private record SeedOnlyQuery(String where, List<Object> parameters) {
    }

    private static Tuple parameters(TorrentRecord record) {
        return Tuple.of(
                record.id(), record.resourceId(), record.contentSha256(), record.infoHashV1(),
                record.torrentRelativePath(), record.viewRelativePath(), record.fileName(), record.fileSize(),
                record.mimeType(), record.telegramFileUniqueId(), record.acquiredVia(), record.completedAt(),
                record.status(),
                record.progressPermille(), record.downloadedBytes(), record.uploadedBytes(),
                record.downloadSpeedBytesPerSecond(), record.uploadSpeedBytesPerSecond(),
                record.connectedPeers(), record.savePath(), record.trackerBaseUrl(),
                record.seedingSeconds(), record.lastSynchronizedAt(), record.createdAt(),
                record.updatedAt(), record.version()
        );
    }

    private static TorrentRecord map(Row row) {
        return new TorrentRecord(
                row.getString("id"), row.getString("resource_id"), row.getString("content_sha256"),
                row.getString("info_hash_v1"), row.getString("torrent_relative_path"),
                row.getString("view_relative_path"), row.getString("file_name"),
                number(row, "file_size").longValue(), row.getString("mime_type"),
                row.getString("telegram_file_unique_id"), row.getString("acquired_via"),
                nullableLong(row, "completed_at"), row.getString("status"),
                number(row, "progress_permille").intValue(),
                number(row, "downloaded_bytes").longValue(),
                number(row, "uploaded_bytes").longValue(),
                number(row, "download_speed_bps").longValue(),
                number(row, "upload_speed_bps").longValue(),
                number(row, "connected_peers").intValue(), row.getString("save_path"),
                row.getString("tracker_base_url"),
                number(row, "seeding_seconds").longValue(),
                number(row, "last_synchronized_at").longValue(),
                number(row, "created_at").longValue(), number(row, "updated_at").longValue(),
                number(row, "version").intValue()
        );
    }

    private static Number number(Row row, String column) {
        return (Number) row.getValue(column);
    }

    private static Long nullableLong(Row row, String column) {
        Number value = (Number) row.getValue(column);
        return value == null ? null : value.longValue();
    }

    private static void validate(TorrentRecord record) {
        if (record.id() == null || record.id().isBlank()
            || record.resourceId() == null || record.resourceId().isBlank()
            || record.contentSha256() == null || !record.contentSha256().matches("[a-f0-9]{64}")
            || record.infoHashV1() == null || !record.infoHashV1().matches("[a-f0-9]{40}")
            || record.torrentRelativePath() == null || record.viewRelativePath() == null
            || record.fileSize() < 0
            || record.acquiredVia() == null
            || !(record.acquiredVia().equals("TELEGRAM") || record.acquiredVia().equals("SEED"))
            || record.status() == null || record.progressPermille() < 0 || record.progressPermille() > 1000
            || record.downloadedBytes() < 0 || record.uploadedBytes() < 0
            || record.downloadSpeedBytesPerSecond() < 0 || record.uploadSpeedBytesPerSecond() < 0
            || record.connectedPeers() < 0 || record.savePath() == null
            || record.trackerBaseUrl() == null
            || record.seedingSeconds() < 0) {
            throw new IllegalArgumentException("Torrent record is invalid");
        }
    }
}
