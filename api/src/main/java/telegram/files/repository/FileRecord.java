package telegram.files.repository;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.map.MapUtil;
import io.vertx.sqlclient.templates.RowMapper;
import io.vertx.sqlclient.templates.TupleMapper;

public record FileRecord(int id, //file id will change
                         String uniqueId, // unique id of the file, if empty, it means the file is cant be downloaded
                         long telegramId,
                         long chatId,
                         long messageId,
                         int date, // date when the file was uploaded
                         boolean hasSensitiveContent,
                         long size, // file size in bytes
                         long downloadedSize, // always 0 from db, should be got from telegram client
                         String type, // 'photo' | 'video' | 'audio' | 'file'
                         String mimeType,
                         String fileName,
                         String thumbnail,
                         String caption,
                         String localPath,
                         String downloadStatus // 'idle' | 'downloading' | 'paused' | 'completed' | 'error'
) {

    public enum DownloadStatus {
        idle, downloading, paused, completed, error
    }

    public static final String SCHEME = """
            CREATE TABLE IF NOT EXISTS file_record
            (
                id                  INT,
                unique_id           VARCHAR(255),
                telegram_id         BIGINT,
                chat_id             BIGINT,
                message_id          BIGINT,
                date                INT,
                has_sensitive_content BOOLEAN,
                size                BIGINT,
                downloaded_size     BIGINT,
                type                VARCHAR(255),
                mime_type           VARCHAR(255),
                file_name           VARCHAR(255),
                thumbnail           VARCHAR(255),
                caption             VARCHAR(255),
                local_path          VARCHAR(255),
                download_status     VARCHAR(255),
                PRIMARY KEY (id, unique_id)
            )
            """;

    public static RowMapper<FileRecord> ROW_MAPPER = row ->
            new FileRecord(row.getInteger("id"),
                    row.getString("unique_id"),
                    row.getLong("telegram_id"),
                    row.getLong("chat_id"),
                    row.getLong("message_id"),
                    row.getInteger("date"),
                    Convert.toBool(row.getInteger("has_sensitive_content")),
                    row.getLong("size"),
                    row.getLong("downloaded_size"),
                    row.getString("type"),
                    row.getString("mime_type"),
                    row.getString("file_name"),
                    row.getString("thumbnail"),
                    row.getString("caption"),
                    row.getString("local_path"),
                    row.getString("download_status")
            );

    public static TupleMapper<FileRecord> PARAM_MAPPER = TupleMapper.mapper(r ->
            MapUtil.ofEntries(
                    MapUtil.entry("id", r.id),
                    MapUtil.entry("unique_id", r.uniqueId()),
                    MapUtil.entry("telegram_id", r.telegramId()),
                    MapUtil.entry("chat_id", r.chatId()),
                    MapUtil.entry("message_id", r.messageId()),
                    MapUtil.entry("date", r.date()),
                    MapUtil.entry("has_sensitive_content", r.hasSensitiveContent()),
                    MapUtil.entry("size", r.size()),
                    MapUtil.entry("downloaded_size", r.downloadedSize()),
                    MapUtil.entry("type", r.type()),
                    MapUtil.entry("mime_type", r.mimeType()),
                    MapUtil.entry("file_name", r.fileName()),
                    MapUtil.entry("thumbnail", r.thumbnail()),
                    MapUtil.entry("caption", r.caption()),
                    MapUtil.entry("local_path", r.localPath()),
                    MapUtil.entry("download_status", r.downloadStatus())
            ));

    public FileRecord withSourceField(int id, long downloadedSize) {
        if (this.downloadedSize == downloadedSize) {
            return this;
        }
        return new FileRecord(id, uniqueId, telegramId, chatId, messageId, date, hasSensitiveContent, size, downloadedSize, type, mimeType, fileName, thumbnail, caption, localPath, downloadStatus);
    }
}
