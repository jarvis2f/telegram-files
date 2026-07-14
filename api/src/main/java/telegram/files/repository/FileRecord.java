package telegram.files.repository;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Version;
import cn.hutool.core.map.MapUtil;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.templates.RowMapper;
import io.vertx.sqlclient.templates.TupleMapper;
import org.drinkless.tdlib.TdApi;
import telegram.files.Config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record FileRecord(int id, //file id will change
                         String uniqueId, // unique id of the file, if empty, it means the file is cant be downloaded
                         long telegramId,
                         long chatId,
                         long messageId,
                         long mediaAlbumId,
                         int date, // date when the file was uploaded
                         boolean hasSensitiveContent,
                         long size, // file size in bytes
                         long downloadedSize, // always 0 from db, should be got from telegram client
                         String type, // 'thumbnail' | 'photo' | 'video' | 'audio' | 'file'
                         String mimeType,
                         String fileName,
                         String thumbnail,
                         String thumbnailUniqueId, // unique id of the thumbnail, usually the video thumbnail
                         String caption,
                         String extra, // extra data for the file
                         String localPath,
                         String downloadStatus, // 'idle' | 'downloading' | 'paused' | 'completed' | 'error'
                         String transferStatus, // 'idle' | 'transferring' | 'completed' | 'error'
                         long startDate, // date when the file was started to download
                         Long completionDate, // date when the file was downloaded
                         String tags,
                         long threadChatId, // Comment thread chat id, if the file has a comment thread
                         long messageThreadId, // The message belongs to the thread.
                         long reactionCount // The number of reactions to the file, if applicable
) {

    public enum DownloadStatus {
        idle, downloading, paused, completed, error
    }

    public enum TransferStatus {
        idle, transferring, completed, error
    }

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS file_record
            (
                id                  INT,
                unique_id           VARCHAR(255),
                telegram_id         BIGINT,
                chat_id             BIGINT,
                message_id          BIGINT,
                media_album_id      BIGINT,
                date                INT,
                has_sensitive_content BOOLEAN,
                size                BIGINT,
                downloaded_size     BIGINT,
                type                VARCHAR(255),
                mime_type           VARCHAR(255),
                file_name           VARCHAR(255),
                thumbnail           VARCHAR(2056),
                thumbnail_unique_id  VARCHAR(255),
                caption             VARCHAR(4096),
                extra               VARCHAR(4096),
                local_path          VARCHAR(1024),
                download_status     VARCHAR(255),
                transfer_status     VARCHAR(255),
                start_date          BIGINT,
                completion_date     BIGINT,
                tags                VARCHAR(2056),
                thread_chat_id      BIGINT,
                message_thread_id   BIGINT,
                reaction_count      BIGINT DEFAULT 0,
                PRIMARY KEY (id, unique_id)
            )
            """;

    private static final List<String> INDEXES = List.of(
            indexSql("idx_file_unique_id", "(unique_id)"),
            indexSql("idx_file_message", "(message_id)"),
            indexSql("idx_file_chat_message_plain", "(chat_id, message_id)"),
            indexSql("idx_file_chat_message", "(chat_id, type, message_id)"),
            indexSql("idx_file_telegram_type_status", "(telegram_id, type, download_status)"),
            indexSql("idx_file_telegram_completion", "(telegram_id, type, completion_date)"),
            indexSql("idx_file_album", "(media_album_id)"),
            indexSql("idx_file_thread", "(telegram_id, thread_chat_id, message_thread_id, type)"),
            indexSql("idx_file_type_size_message", "(type, size, message_id)"),
            indexSql("idx_file_type_date_message", "(type, date, message_id)"),
            indexSql("idx_file_type_completion_message", "(type, completion_date, message_id)"),
            indexSql("idx_file_type_reaction_message", "(type, reaction_count, message_id)")
    );

    public static final TreeMap<Version, String[]> MIGRATIONS = new TreeMap<>(MapUtil.ofEntries(
            MapUtil.entry(new Version("0.1.7"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN start_date BIGINT;",
                    "ALTER TABLE file_record ADD COLUMN completion_date BIGINT;",
            }),
            MapUtil.entry(new Version("0.1.12"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN transfer_status VARCHAR(255) DEFAULT 'idle';",
            }),
            MapUtil.entry(new Version("0.1.15"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN media_album_id BIGINT;",
            }),
            MapUtil.entry(new Version("0.2.0"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN extra VARCHAR(4096);",
                    "ALTER TABLE file_record ADD COLUMN thumbnail_unique_id VARCHAR(255);",
            }),
            MapUtil.entry(new Version("0.2.1"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN tags VARCHAR(2056);",
                    "ALTER TABLE file_record ADD COLUMN thread_chat_id BIGINT;",
                    "ALTER TABLE file_record ADD COLUMN message_thread_id BIGINT;",
            }),
            MapUtil.entry(new Version("0.2.4"), new String[]{
                    "ALTER TABLE file_record ADD COLUMN reaction_count BIGINT DEFAULT 0;",
            }),
            MapUtil.entry(new Version("0.4.0"), INDEXES.toArray(String[]::new))
    ));

    public static class FileRecordDefinition implements Definition {
        @Override
        public String getScheme() {
            return SCHEME;
        }

        @Override
        public TreeMap<Version, String[]> getMigrations() {
            return MIGRATIONS;
        }

        @Override
        public Future<Void> createTable(SqlClient sqlClient) {
            return Definition.super.createTable(sqlClient).compose(_ -> Future.all(
                    INDEXES.stream()
                            .map(sql -> sqlClient.query(sql).execute()
                                    .recover(_ -> Future.succeededFuture()))
                            .toList()
            ).mapEmpty());
        }
    }

    private static String indexSql(String name, String columns) {
        String ifNotExists = Config.isMysql() ? "" : "IF NOT EXISTS ";
        return "CREATE INDEX %s%s ON file_record %s".formatted(ifNotExists, name, columns);
    }

    public static RowMapper<FileRecord> ROW_MAPPER = row ->
            new FileRecord(row.getInteger("id"),
                    row.getString("unique_id"),
                    row.getLong("telegram_id"),
                    row.getLong("chat_id"),
                    row.getLong("message_id"),
                    Objects.requireNonNullElse(row.getLong("media_album_id"), 0L),
                    row.getInteger("date"),
                    Config.isPostgres() ? row.getBoolean("has_sensitive_content") : Convert.toBool(row.getInteger("has_sensitive_content")),
                    row.getLong("size"),
                    row.getLong("downloaded_size"),
                    row.getString("type"),
                    row.getString("mime_type"),
                    row.getString("file_name"),
                    row.getString("thumbnail"),
                    row.getString("thumbnail_unique_id"),
                    row.getString("caption"),
                    row.getString("extra"),
                    row.getString("local_path"),
                    row.getString("download_status"),
                    row.getString("transfer_status"),
                    Objects.requireNonNullElse(row.getLong("start_date"), 0L),
                    row.getLong("completion_date"),
                    row.getString("tags"),
                    Objects.requireNonNullElse(row.getLong("thread_chat_id"), 0L),
                    Objects.requireNonNullElse(row.getLong("message_thread_id"), 0L),
                    row.getLong("reaction_count")
            );

    public static TupleMapper<FileRecord> PARAM_MAPPER = TupleMapper.mapper(FileRecord::toMap);

    public static Map<String, Object> toMap(FileRecord r) {
        return MapUtil.ofEntries(
                MapUtil.entry("id", r.id),
                MapUtil.entry("unique_id", r.uniqueId()),
                MapUtil.entry("telegram_id", r.telegramId()),
                MapUtil.entry("chat_id", r.chatId()),
                MapUtil.entry("message_id", r.messageId()),
                MapUtil.entry("media_album_id", r.mediaAlbumId()),
                MapUtil.entry("date", r.date()),
                MapUtil.entry("has_sensitive_content", r.hasSensitiveContent()),
                MapUtil.entry("size", r.size()),
                MapUtil.entry("downloaded_size", r.downloadedSize()),
                MapUtil.entry("type", r.type()),
                MapUtil.entry("mime_type", r.mimeType()),
                MapUtil.entry("file_name", r.fileName()),
                MapUtil.entry("thumbnail", r.thumbnail()),
                MapUtil.entry("thumbnail_unique_id", r.thumbnailUniqueId()),
                MapUtil.entry("caption", r.caption()),
                MapUtil.entry("extra", r.extra()),
                MapUtil.entry("local_path", r.localPath()),
                MapUtil.entry("download_status", r.downloadStatus()),
                MapUtil.entry("transfer_status", r.transferStatus()),
                MapUtil.entry("start_date", r.startDate()),
                MapUtil.entry("completion_date", r.completionDate()),
                MapUtil.entry("tags", r.tags()),
                MapUtil.entry("thread_chat_id", r.threadChatId()),
                MapUtil.entry("message_thread_id", r.messageThreadId()),
                MapUtil.entry("reaction_count", r.reactionCount())
        );
    }

    public FileRecord withSourceField(int id, long downloadedSize) {
        return new FileRecord(id, uniqueId, telegramId, chatId, messageId, mediaAlbumId, date, hasSensitiveContent, size, downloadedSize, type, mimeType, fileName, thumbnail, thumbnailUniqueId, caption, extra, localPath, downloadStatus, transferStatus, startDate, completionDate, tags, threadChatId, messageThreadId, reactionCount);
    }

    public FileRecord withThreadInfo(TdApi.MessageThreadInfo threadInfo) {
        if (threadInfo == null) {
            return this;
        }
        return new FileRecord(id, uniqueId, telegramId, chatId, messageId, mediaAlbumId, date, hasSensitiveContent, size, downloadedSize, type, mimeType, fileName, thumbnail, thumbnailUniqueId, caption, extra, localPath, downloadStatus, transferStatus, startDate, completionDate, tags,
                threadInfo.chatId, threadInfo.messageThreadId, reactionCount);
    }

    public boolean isDownloadStatus(DownloadStatus status) {
        if (status == null && downloadStatus == null) {
            return true;
        }
        return downloadStatus != null && DownloadStatus.valueOf(downloadStatus) == status;
    }

    public boolean isTransferStatus(TransferStatus status) {
        if (status == null && transferStatus == null) {
            return true;
        }
        return transferStatus != null && TransferStatus.valueOf(transferStatus) == status;
    }
}
