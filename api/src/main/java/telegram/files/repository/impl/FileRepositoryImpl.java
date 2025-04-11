package telegram.files.repository.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.IterUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.map.MapUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlResult;
import io.vertx.sqlclient.templates.SqlTemplate;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.Config;
import telegram.files.MessyUtils;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class FileRepositoryImpl extends AbstractSqlRepository implements FileRepository {

    private static final Log log = LogFactory.get();

    public FileRepositoryImpl(SqlClient sqlClient) {
        super(sqlClient);
    }

    @Override
    public Future<FileRecord> create(FileRecord fileRecord) {
        return SqlTemplate
                .forUpdate(sqlClient, """
                        INSERT INTO file_record(id, unique_id, telegram_id, chat_id, message_id, media_album_id, date, has_sensitive_content,
                                                size, downloaded_size,
                                                type, mime_type,
                                                file_name, thumbnail, thumbnail_unique_id, caption, extra, local_path,
                                                download_status, start_date, transfer_status)
                        values (#{id}, #{unique_id}, #{telegram_id}, #{chat_id}, #{message_id}, #{media_album_id}, #{date},
                                #{has_sensitive_content}, #{size}, #{downloaded_size}, #{type},
                                #{mime_type}, #{file_name}, #{thumbnail}, #{thumbnail_unique_id}, #{caption}, #{extra}, #{local_path},
                                #{download_status}, #{start_date}, #{transfer_status})
                        """)
                .mapFrom(FileRecord.PARAM_MAPPER)
                .execute(fileRecord)
                .map(r -> fileRecord)
                .compose(r -> {
                    if (Objects.equals(r.type(), "thumbnail")) {
                        return Future.succeededFuture(r);
                    } else {
                        return this.updateCaptionByMediaAlbumId(fileRecord.mediaAlbumId(), fileRecord.caption()).map(r);
                    }
                })
                .onSuccess(r -> log.trace("Successfully created file record: %s".formatted(fileRecord.id())))
                .onFailure(err -> log.error("Failed to create file record: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Boolean> createIfNotExist(FileRecord fileRecord) {
        return this.getByUniqueId(fileRecord.uniqueId())
                .compose(record -> {
                    if (record != null) {
                        return Future.succeededFuture(false);
                    }
                    return this.create(fileRecord).map(true);
                });
    }

    @Override
    public Future<Tuple3<List<FileRecord>, Long, Long>> getFiles(long chatId, Map<String, String> filter) {
        String search = filter.get("search");
        String type = filter.get("type");
        String downloadStatus = filter.get("downloadStatus");
        String transferStatus = filter.get("transferStatus");
        List<String> tags = StrUtil.split(filter.get("tags"), ",");
        String dateType = filter.get("dateType");
        String dateRange = filter.get("dateRange");
        String sizeRange = filter.get("sizeRange");
        String sizeUnit = filter.get("sizeUnit");
        String sort = filter.get("sort");
        String order = filter.get("order");

        Long fromMessageId = Convert.toLong(filter.get("fromMessageId"), 0L);
        int limit = Convert.toInt(filter.get("limit"), 20);

        String whereClause = "type != 'thumbnail'";
        Map<String, Object> params = new HashMap<>();
        params.put("limit", limit);
        if (chatId != 0) {
            whereClause += " AND chat_id = #{chatId}";
            params.put("chatId", chatId);
        }
        if (StrUtil.isNotBlank(search)) {
            whereClause += " AND (file_name LIKE #{search} OR caption LIKE #{search})";
            params.put("search", "%%" + search + "%%");
        }
        if (StrUtil.isNotBlank(type) && !Objects.equals(type, "all")) {
            if (Objects.equals(type, "media")) {
                whereClause += " AND type IN ('photo', 'video')";
            } else {
                whereClause += " AND type = #{type}";
                params.put("type", type);
            }
        }
        if (StrUtil.isNotBlank(downloadStatus)) {
            whereClause += " AND download_status = #{downloadStatus}";
            params.put("downloadStatus", downloadStatus);
        }
        if (StrUtil.isNotBlank(transferStatus)) {
            whereClause += " AND transfer_status = #{transferStatus}";
            params.put("transferStatus", transferStatus);
        }
        if (CollUtil.isNotEmpty(tags)) {
            String tagClause = tags.stream()
                    .filter(StrUtil::isNotBlank)
                    .map(tag -> "tags LIKE '%%" + tag + "%%'")
                    .collect(Collectors.joining(" OR "));
            whereClause += " AND (%s)".formatted(tagClause);
        }
        if (StrUtil.isNotBlank(dateType) && StrUtil.isNotBlank(dateRange)) {
            String[] dates = dateRange.split(",");
            if (dates.length == 2) {
                long startTime = LocalDate.parse(dates[0], DateTimeFormatter.ISO_DATE)
                        .atStartOfDay()
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                long endTime = LocalDate.parse(dates[1], DateTimeFormatter.ISO_DATE)
                        .atTime(LocalTime.MAX)
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                if (Objects.equals(dateType, "sent")) {
                    whereClause += " AND date >= #{startTime} AND date <= #{endTime}";
                    startTime = startTime / 1000;
                    endTime = endTime / 1000;
                } else {
                    whereClause += " AND completion_date >= #{startTime} AND completion_date <= #{endTime}";
                }
                params.put("startTime", startTime);
                params.put("endTime", endTime);
            }
        }
        if (StrUtil.isNotBlank(sizeRange) && StrUtil.isNotBlank(sizeUnit)) {
            String[] sizes = sizeRange.split(",");
            if (sizes.length == 2) {
                long minSize = MessyUtils.convertToByte(Convert.toLong(sizes[0]), sizeUnit);
                long maxSize = MessyUtils.convertToByte(Convert.toLong(sizes[1]), sizeUnit);
                whereClause += " AND size >= #{minSize} AND size <= #{maxSize}";
                params.put("minSize", minSize);
                params.put("maxSize", maxSize);
            }
        }
        String countClause = whereClause;
        String orderBy = "message_id DESC";
        boolean customSort = StrUtil.isNotBlank(sort) && StrUtil.isNotBlank(order);
        if (customSort) {
            orderBy = "%s %s".formatted(sort, order);
        }
        if (fromMessageId > 0) {
            params.put("fromMessageId", fromMessageId);
            if (customSort) {
                long fromSortField = Convert.toLong(filter.get("fromSortField"));
                whereClause += " AND (%s %s %s OR (%s = %s AND message_id < #{fromMessageId}))".formatted(sort,
                        Objects.equals(order, "asc") ? ">" : "<",
                        fromSortField,
                        sort,
                        fromSortField);
            } else {
                whereClause += " AND message_id < #{fromMessageId}";
            }
        }
        log.trace("Get files with where: %s params: %s".formatted(whereClause, params));
        return Future.all(
                SqlTemplate
                        .forQuery(sqlClient, """
                                SELECT * FROM file_record WHERE %s ORDER BY %s LIMIT #{limit}
                                """.formatted(whereClause, orderBy))
                        .mapTo(FileRecord.ROW_MAPPER)
                        .execute(params)
                        .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage())))
                        .map(IterUtil::toList)
                ,
                SqlTemplate
                        .forQuery(sqlClient, """
                                SELECT COUNT(*) FROM file_record WHERE %s
                                """.formatted(countClause))
                        .mapTo(rs -> rs.getLong(0))
                        .execute(params)
                        .onFailure(err -> log.error("Failed to get file record count: %s".formatted(err.getMessage())))
                        .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0L)
        ).map(r -> {
            List<FileRecord> fileRecords = r.resultAt(0);
            long nextFromMessageId = CollUtil.isEmpty(fileRecords) ? 0 : fileRecords.getLast().messageId();
            return Tuple.tuple(fileRecords, nextFromMessageId, r.resultAt(1));
        });
    }

    @Override
    public Future<Map<String, FileRecord>> getFilesByUniqueId(List<String> uniqueIds) {
        uniqueIds = uniqueIds.stream()
                .filter(StrUtil::isNotBlank)
                .distinct().collect(Collectors.toList());
        if (CollUtil.isEmpty(uniqueIds)) {
            return Future.succeededFuture(new HashMap<>());
        }
        String uniqueIdPlaceholders = IntStream.range(0, uniqueIds.size())
                .mapToObj(i -> "#{uniqueId" + i + "}")
                .collect(Collectors.joining(","));
        Map<String, Object> params = new HashMap<>();
        for (int i = 0; i < uniqueIds.size(); i++) {
            params.put("uniqueId" + i, uniqueIds.get(i));
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE unique_id IN (%s)
                        """.formatted(uniqueIdPlaceholders))
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(params)
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage())))
                .map(rs -> {
                    Map<String, FileRecord> map = new HashMap<>();
                    for (FileRecord record : rs) {
                        map.put(record.uniqueId(), record);
                    }
                    return map;
                });
    }

    @Override
    public Future<FileRecord> getByPrimaryKey(int fileId, String uniqueId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE id = #{fileId} AND unique_id = #{uniqueId}
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("fileId", fileId, "uniqueId", uniqueId))
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage()))
                )
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    @Override
    public Future<FileRecord> getByUniqueId(String uniqueId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT * FROM file_record WHERE unique_id = #{uniqueId} LIMIT 1
                        """)
                .mapTo(FileRecord.ROW_MAPPER)
                .execute(Map.of("uniqueId", uniqueId))
                .onFailure(err -> log.error("Failed to get file record: %s".formatted(err.getMessage()))
                )
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null);
    }

    @Override
    public Future<String> getCaptionByMediaAlbumId(long mediaAlbumId) {
        if (mediaAlbumId <= 0) {
            return Future.succeededFuture(null);
        }
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT caption FROM file_record WHERE media_album_id = #{mediaAlbumId} LIMIT 1
                        """)
                .mapTo(row -> row.getString("caption"))
                .execute(Map.of("mediaAlbumId", mediaAlbumId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : null)
                .onFailure(err -> log.error("Failed to get caption: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> getDownloadStatistics(long telegramId) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(*)                                                                     AS total,
                               COUNT(CASE WHEN download_status = 'downloading' THEN 1 END)                  AS downloading,
                               COUNT(CASE WHEN download_status = 'paused' THEN 1 END)                       AS paused,
                               COUNT(CASE WHEN download_status = 'completed' THEN 1 END)                    AS completed,
                               COUNT(CASE WHEN download_status = 'error' THEN 1 END)                        AS error,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'photo' THEN 1 END) AS photo,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'video' THEN 1 END) AS video,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'audio' THEN 1 END) AS audio,
                               COUNT(CASE WHEN download_status = 'completed' and type = 'file' THEN 1 END)  AS file
                        FROM file_record
                        WHERE telegram_id = #{telegramId} and type != 'thumbnail'
                        """)
                .mapTo(row -> {
                    JsonObject result = JsonObject.of();
                    result.put("total", row.getInteger("total"));
                    result.put("downloading", row.getInteger("downloading"));
                    result.put("paused", row.getInteger("paused"));
                    result.put("completed", row.getInteger("completed"));
                    result.put("error", row.getInteger("error"));
                    result.put("photo", row.getInteger("photo"));
                    result.put("video", row.getInteger("video"));
                    result.put("audio", row.getInteger("audio"));
                    result.put("file", row.getInteger("file"));
                    return result;
                })
                .execute(Map.of("telegramId", telegramId))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of())
                .onFailure(err -> log.error("Failed to get download statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> getDownloadStatistics() {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(CASE WHEN download_status = 'downloading' THEN 1 END)                  AS downloading,
                               COUNT(CASE WHEN download_status = 'completed' THEN 1 END)                    AS completed,
                               SUM(CASE WHEN download_status = 'completed' THEN size ELSE 0 END)            AS downloaded_size
                        FROM file_record
                        WHERE type != 'thumbnail'
                        """)
                .mapTo(row -> {
                    JsonObject result = JsonObject.of();
                    result.put("downloading", row.getInteger("downloading"));
                    result.put("completed", row.getInteger("completed"));
                    result.put("downloadedSize", Objects.requireNonNullElse(row.getLong("downloaded_size"), 0));
                    return result;
                })
                .execute(Map.of())
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : JsonObject.of())
                .onFailure(err -> log.error("Failed to get download statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonArray> getCompletedRangeStatistics(long telegramId, long startTime, long endTime, int timeRange) {
        String query;
        if (Config.isSqlite()) {
            query = """
                    SELECT strftime(
                                       CASE
                                           WHEN #{timeRange} = 1 THEN '%Y-%m-%d %H:%M'
                                           WHEN #{timeRange} = 2 THEN '%Y-%m-%d %H:00'
                                           WHEN #{timeRange} IN (3, 4) THEN '%Y-%m-%d'
                                       END,
                                       datetime(completion_date / 1000, 'unixepoch'),
                                       'localtime'
                               )        AS time,
                               COUNT(*) AS total
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND completion_date IS NOT NULL
                          AND completion_date >= #{startTime}
                          AND completion_date <= #{endTime}
                          AND type != 'thumbnail'
                        GROUP BY time
                        ORDER BY time;
                    """;
        } else if (Config.isPostgres()) {
            query = """
                    SELECT TO_CHAR(
                               TO_TIMESTAMP(completion_date / 1000),
                               CASE
                                   WHEN #{timeRange} = 1 THEN 'YYYY-MM-DD HH24:MI'
                                   WHEN #{timeRange} = 2 THEN 'YYYY-MM-DD HH24:00'
                                   WHEN #{timeRange} IN (3, 4) THEN 'YYYY-MM-DD'
                               END
                           ) AS time,
                           COUNT(*) AS total
                    FROM file_record
                    WHERE telegram_id = #{telegramId}
                      AND completion_date IS NOT NULL
                      AND completion_date >= #{startTime}
                      AND completion_date <= #{endTime}
                      AND type != 'thumbnail'
                    GROUP BY time
                    ORDER BY time;
                    """;
        } else {
            query = """
                    SELECT DATE_FORMAT(
                               FROM_UNIXTIME(completion_date / 1000),
                               CASE
                                   WHEN #{timeRange} = 1 THEN '%Y-%m-%d %H:%i'
                                   WHEN #{timeRange} = 2 THEN '%Y-%m-%d %H:00'
                                   WHEN #{timeRange} IN (3, 4) THEN '%Y-%m-%d'
                               END
                           ) AS time,
                           COUNT(*) AS total
                    FROM file_record
                    WHERE telegram_id = #{telegramId}
                      AND completion_date IS NOT NULL
                      AND completion_date >= #{startTime}
                      AND completion_date <= #{endTime}
                      AND type != 'thumbnail'
                    GROUP BY time
                    ORDER BY time;
                    """;
        }
        return SqlTemplate
                .forQuery(sqlClient, query)
                .mapTo(row -> new JsonObject()
                        .put("time", row.getString("time"))
                        .put("total", row.getInteger("total"))
                )
                .execute(Map.of("telegramId", telegramId, "startTime", startTime, "endTime", endTime, "timeRange", timeRange))
                .map(IterUtil::toList)
                .map(rs -> {
                    if (CollUtil.isEmpty(rs)) {
                        return JsonArray.of();
                    }
                    if (timeRange == 1) {
                        // Statistics grouped by five minutes
                        return rs.stream()
                                .peek(c -> c.put("time", MessyUtils.withGrouping5Minutes(
                                        DateUtil.parseLocalDateTime(c.getString("time"), DatePattern.NORM_DATETIME_MINUTE_PATTERN)
                                ).format(DatePattern.NORM_DATETIME_MINUTE_FORMATTER)))
                                .collect(Collectors.groupingBy(c -> c.getString("time"),
                                        Collectors.summingInt(c -> c.getInteger("total"))
                                ))
                                .entrySet().stream()
                                .map(e -> new JsonObject()
                                        .put("time", e.getKey())
                                        .put("total", e.getValue())
                                )
                                .sorted(Comparator.comparing(o -> o.getString("time")))
                                .collect(JsonArray::new, JsonArray::add, JsonArray::addAll);
                    } else {
                        JsonArray jsonArray = new JsonArray();
                        rs.forEach(jsonArray::add);
                        return jsonArray;
                    }
                })
                .onFailure(err -> log.error("Failed to get completed statistics: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<Integer> countByStatus(long telegramId, FileRecord.DownloadStatus downloadStatus) {
        return SqlTemplate
                .forQuery(sqlClient, """
                        SELECT COUNT(*)
                        FROM file_record
                        WHERE telegram_id = #{telegramId}
                          AND download_status = #{downloadStatus}
                          AND type != 'thumbnail'
                        """)
                .mapTo(rs -> rs.getInteger(0))
                .execute(Map.of("telegramId", telegramId, "downloadStatus", downloadStatus.name()))
                .map(rs -> rs.size() > 0 ? rs.iterator().next() : 0)
                .onFailure(err -> log.error("Failed to count file record: %s".formatted(err.getMessage())));
    }

    @Override
    public Future<JsonObject> updateDownloadStatus(int fileId,
                                                   String uniqueId,
                                                   String localPath,
                                                   FileRecord.DownloadStatus downloadStatus,
                                                   Long completionDate) {
        if (StrUtil.isBlank(localPath) && downloadStatus == null) {
            return Future.succeededFuture(null);
        }
        return getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null
                        // Because if file transfer is completed or message is deleted,
                        // the telegram client will detect the file and change the download status to paused
                        || (downloadStatus == FileRecord.DownloadStatus.paused
                            && (record.isTransferStatus(FileRecord.TransferStatus.completed)
                                || record.isDownloadStatus(FileRecord.DownloadStatus.completed)))
                    ) {
                        return Future.succeededFuture(null);
                    }
                    boolean pathUpdated = !Objects.equals(record.localPath(), localPath);
                    boolean downloadStatusUpdated = !record.isDownloadStatus(downloadStatus);
                    if (!pathUpdated && !downloadStatusUpdated) {
                        return Future.succeededFuture(null);
                    }

                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record SET id = #{fileId},
                                                           local_path = #{localPath},
                                                           download_status = #{downloadStatus},
                                                           completion_date = #{completionDate}
                                    WHERE unique_id = #{uniqueId}
                                    """)
                            .execute(MapUtil.ofEntries(MapUtil.entry("fileId", fileId),
                                    MapUtil.entry("uniqueId", uniqueId),
                                    MapUtil.entry("localPath", pathUpdated ? localPath : record.localPath()),
                                    MapUtil.entry("downloadStatus", downloadStatusUpdated ? downloadStatus.name() : record.downloadStatus()),
                                    MapUtil.entry("completionDate", completionDate)
                            ))
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .map(r -> {
                                JsonObject result = JsonObject.of();
                                if (pathUpdated) {
                                    result.put("localPath", localPath);
                                    result.put("completionDate", completionDate);
                                }
                                if (downloadStatusUpdated) {
                                    result.put("downloadStatus", downloadStatus.name());
                                }
                                log.debug("Successfully updated file record: %s, path: %s, status: %s, before: %s, %s"
                                        .formatted(uniqueId, localPath, downloadStatus.name(), record.localPath(), record.downloadStatus()));
                                return result;
                            });
                });
    }

    @Override
    public Future<JsonObject> updateTransferStatus(String uniqueId,
                                                   FileRecord.TransferStatus transferStatus,
                                                   String localPath) {
        if (StrUtil.isBlank(localPath) && transferStatus == null) {
            return Future.succeededFuture(null);
        }
        return getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null) {
                        return Future.succeededFuture(null);
                    }
                    boolean pathUpdated = StrUtil.isNotBlank(localPath) && !Objects.equals(record.localPath(), localPath);
                    boolean transferStatusUpdated = !record.isTransferStatus(transferStatus);
                    if (!pathUpdated && !transferStatusUpdated) {
                        return Future.succeededFuture(null);
                    }

                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record
                                    SET transfer_status = #{transferStatus},
                                        local_path = #{localPath}
                                    WHERE unique_id = #{uniqueId}
                                    """)
                            .execute(MapUtil.ofEntries(MapUtil.entry("uniqueId", uniqueId),
                                    MapUtil.entry("localPath", pathUpdated ? localPath : record.localPath()),
                                    MapUtil.entry("transferStatus", transferStatusUpdated ? transferStatus.name() : record.transferStatus())
                            ))
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .map(r -> {
                                JsonObject result = JsonObject.of();
                                if (pathUpdated) {
                                    result.put("localPath", localPath);
                                }
                                if (transferStatusUpdated) {
                                    result.put("transferStatus", transferStatus.name());
                                }
                                log.debug("Successfully updated file record: %s, path: %s, transfer status: %s, before: %s %s"
                                        .formatted(uniqueId, localPath, transferStatus.name(), record.localPath(), record.transferStatus()));
                                return result;
                            });
                });
    }

    @Override
    public Future<Void> updateFileId(int fileId, String uniqueId) {
        if (fileId <= 0 || StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return this.getByUniqueId(uniqueId)
                .compose(record -> {
                    if (record == null || record.id() == fileId) {
                        return Future.succeededFuture();
                    }
                    return SqlTemplate
                            .forUpdate(sqlClient, """
                                    UPDATE file_record SET id = #{fileId} WHERE unique_id = #{uniqueId}
                                    """)
                            .execute(Map.of("fileId", fileId, "uniqueId", uniqueId))
                            .onFailure(err ->
                                    log.error("Failed to update file record: %s".formatted(err.getMessage()))
                            )
                            .mapEmpty();
                });
    }

    @Override
    public Future<Integer> updateCaptionByMediaAlbumId(long mediaAlbumId, String caption) {
        if (mediaAlbumId <= 0) {
            return Future.succeededFuture(0);
        }

        return Future.<String>future(promise -> {
            if (StrUtil.isBlank(caption)) {
                this.getCaptionByMediaAlbumId(mediaAlbumId)
                        .onComplete(result -> {
                            if (result.succeeded()) {
                                promise.complete(result.result());
                            } else {
                                promise.complete(null);
                            }
                        });
            } else {
                promise.complete(caption);
            }
        }).compose(theCaption -> {
            if (StrUtil.isBlank(theCaption)) {
                return Future.succeededFuture(0);
            }
            return SqlTemplate
                    .forUpdate(sqlClient, """
                            UPDATE file_record SET caption = #{caption} WHERE media_album_id = #{mediaAlbumId}
                            """)
                    .execute(Map.of("mediaAlbumId", mediaAlbumId, "caption", theCaption))
                    .onFailure(err -> log.error("Failed to update file record: %s".formatted(err.getMessage()))
                    )
                    .map(SqlResult::rowCount);
        });
    }

    @Override
    public Future<Void> updateTags(String uniqueId, String tags) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return SqlTemplate
                .forUpdate(sqlClient, """
                        UPDATE file_record SET tags = #{tags} WHERE unique_id = #{uniqueId}
                        """)
                .execute(Map.of("uniqueId", uniqueId, "tags", tags))
                .onFailure(err -> log.error("Failed to update file record: %s".formatted(err.getMessage())))
                .mapEmpty();
    }

    @Override
    public Future<Void> deleteByUniqueId(String uniqueId) {
        if (StrUtil.isBlank(uniqueId)) {
            return Future.succeededFuture();
        }
        return SqlTemplate
                .forUpdate(sqlClient, """
                        DELETE FROM file_record WHERE unique_id = #{uniqueId}
                        """)
                .execute(Map.of("uniqueId", uniqueId))
                .onFailure(err -> log.error("Failed to delete file record: %s".formatted(err.getMessage()))
                )
                .mapEmpty();
    }
}
