package telegram.files;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.convert.TypeConverter;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.ClassUtil;
import cn.hutool.core.util.ReflectUtil;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;

import java.util.*;

@SuppressWarnings("unchecked")
public class TdApiHelp {

    // name -> class
    private static final Map<String, Class<TdApi.Function<?>>> FUNCTIONS = new HashMap<>();

    // CONSTRUCTOR -> class
    private static final Map<Integer, Class<TdApi.Object>> CONSTRUCTORS = new HashMap<>();

    public static final List<Integer> FILE_CONTENT_CONSTRUCTORS = Arrays.asList(
            TdApi.MessagePhoto.CONSTRUCTOR,
            TdApi.MessageVideo.CONSTRUCTOR,
            TdApi.MessageAudio.CONSTRUCTOR,
            TdApi.MessageDocument.CONSTRUCTOR
    );

    private static final TypeConverter TD_API_TYPE_CONVERTER = (targetType, value) -> {
        if (value == null) {
            return null;
        }
        if (value instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) value;
            if (!map.containsKey("@type")) {
                return Convert.convertWithCheck(targetType, value, null, false);
            }
            Integer constructor = Convert.toInt(map.get("@type"));
            Class<TdApi.Object> objClazz = CONSTRUCTORS.get(constructor);
            if (objClazz == null) {
                return null;
            }
            return BeanUtil.toBean(map, objClazz);
        }
        return Convert.convertWithCheck(targetType, value, null, false);
    };

    private static final CopyOptions COPY_OPTIONS = new CopyOptions().setConverter(TD_API_TYPE_CONVERTER);

    static {
        Arrays.stream(TdApi.class.getClasses())
                .filter(ClassUtil::isNormalClass)
                .filter(TdApi.Object.class::isAssignableFrom)
                .forEach(clazz -> {
                    if (TdApi.Function.class.isAssignableFrom(clazz)) {
                        FUNCTIONS.put(clazz.getSimpleName(), (Class<TdApi.Function<?>>) clazz);
                    }

                    Object constructor = ReflectUtil.getStaticFieldValue(ReflectUtil.getField(clazz, "CONSTRUCTOR"));
                    if (constructor != null) {
                        CONSTRUCTORS.put(Convert.toInt(constructor), (Class<TdApi.Object>) clazz);
                    }
                });
    }

    public static Map<String, Class<TdApi.Function<?>>> getFunctions() {
        return FUNCTIONS;
    }

    public static TdApi.Function<?> getFunction(String method, Object params) {
        Class<TdApi.Function<?>> func = FUNCTIONS.get(method);
        if (func == null) {
            return null;
        }
        if (params == null) {
            return ReflectUtil.newInstance(func);
        }
        return BeanUtil.toBean(params, func, COPY_OPTIONS);
    }

    public static String getChatType(TdApi.ChatType type) {
        return switch (type.getConstructor()) {
            case TdApi.ChatTypePrivate.CONSTRUCTOR -> "private";
            case TdApi.ChatTypeBasicGroup.CONSTRUCTOR -> "group";
            case TdApi.ChatTypeSupergroup.CONSTRUCTOR -> ((TdApi.ChatTypeSupergroup) type).isChannel ? "channel" : "group";
            case TdApi.ChatTypeSecret.CONSTRUCTOR -> "secret";
            default -> "unknown";
        };
    }

    public static TdApi.SearchMessagesFilter getSearchMessagesFilter(String fileType) {
        return switch (fileType) {
            case "media" -> new TdApi.SearchMessagesFilterPhotoAndVideo();
            case "photo" -> new TdApi.SearchMessagesFilterPhoto();
            case "video" -> new TdApi.SearchMessagesFilterVideo();
            case "audio" -> new TdApi.SearchMessagesFilterAudio();
            case "file" -> new TdApi.SearchMessagesFilterDocument();
            default -> null;
        };
    }

    public static String getSearchMessagesFilterType(TdApi.SearchMessagesFilter filter) {
        return switch (filter.getConstructor()) {
            case TdApi.SearchMessagesFilterPhotoAndVideo.CONSTRUCTOR -> "media";
            case TdApi.SearchMessagesFilterPhoto.CONSTRUCTOR -> "photo";
            case TdApi.SearchMessagesFilterVideo.CONSTRUCTOR -> "video";
            case TdApi.SearchMessagesFilterAudio.CONSTRUCTOR -> "audio";
            case TdApi.SearchMessagesFilterDocument.CONSTRUCTOR -> "file";
            default -> "unknown";
        };
    }

    public static String getThumbnailMimeType(TdApi.ThumbnailFormat format) {
        return switch (format.getConstructor()) {
            // 图片格式
            case TdApi.ThumbnailFormatJpeg.CONSTRUCTOR -> "image/jpeg";
            case TdApi.ThumbnailFormatPng.CONSTRUCTOR -> "image/png";
            // 动画格式
            case TdApi.ThumbnailFormatGif.CONSTRUCTOR -> "image/gif";
            case TdApi.ThumbnailFormatWebp.CONSTRUCTOR -> "image/webp";
            case TdApi.ThumbnailFormatTgs.CONSTRUCTOR -> "application/x-tgsticker";
            // 视频格式
            case TdApi.ThumbnailFormatWebm.CONSTRUCTOR -> "video/webm";
            case TdApi.ThumbnailFormatMpeg4.CONSTRUCTOR -> "video/mp4";
            default -> "unknown";
        };
    }

    public static List<Integer> getFileIds(List<TdApi.Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        return messages.stream()
                .filter(message -> FILE_CONTENT_CONSTRUCTORS.contains(message.content.getConstructor()))
                .map(TdApiHelp::getFileId)
                .filter(Objects::nonNull)
                .toList();
    }

    public static List<String> getFileUniqueIds(List<TdApi.Message> messages) {
        if (CollUtil.isEmpty(messages)) {
            return new ArrayList<>();
        }
        return messages.stream()
                .filter(message -> FILE_CONTENT_CONSTRUCTORS.contains(message.content.getConstructor()))
                .map(TdApiHelp::getFileUniqueId)
                .filter(Objects::nonNull)
                .toList();
    }

    public static Integer getFileId(TdApi.Message message) {
        return getFileHandler(message).map(FileHandler::getFileId).orElse(null);
    }

    public static String getFileUniqueId(TdApi.Message message) {
        return getFileHandler(message).map(FileHandler::getFileUniqueId).orElse(null);
    }

    public static List<TdApi.Message> filterUniqueMessages(List<TdApi.Message> messages) {
        if (CollUtil.isEmpty(messages)) return messages;

        Set<Integer> fileIds = new HashSet<>();
        return messages.stream()
                .filter(message -> {
                    Integer fileId = TdApiHelp.getFileId(message);
                    if (fileId == null) return false;
                    if (fileIds.contains(fileId)) {
                        return false;
                    }
                    fileIds.add(fileId);
                    return true;
                })
                .toList();
    }

    public static FileRecord.DownloadStatus getDownloadStatus(TdApi.File file) {
        if (file == null || file.local == null) {
            return null;
        }
        if (file.local.isDownloadingActive) {
            return FileRecord.DownloadStatus.downloading;
        } else if (file.local.isDownloadingCompleted) {
            return FileRecord.DownloadStatus.completed;
        } else {
            return FileRecord.DownloadStatus.paused;
        }
    }

    public static <T extends FileHandler<? extends TdApi.MessageContent>> Optional<T> getFileHandler(TdApi.Message message) {
        if (message == null) return Optional.empty();
        switch (message.content.getConstructor()) {
            case TdApi.MessagePhoto.CONSTRUCTOR -> {
                return Optional.of((T) new PhotoHandler(message));
            }
            case TdApi.MessageVideo.CONSTRUCTOR -> {
                return Optional.of((T) new VideoHandler(message));
            }
            case TdApi.MessageAudio.CONSTRUCTOR -> {
                return Optional.of((T) new AudioHandler(message));
            }
            case TdApi.MessageDocument.CONSTRUCTOR -> {
                return Optional.of((T) new DocumentHandler(message));
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    public static abstract class FileHandler<T extends TdApi.MessageContent> {
        protected TdApi.Message message;

        protected T content;

        public FileHandler(TdApi.Message message) {
            this.message = message;
            this.content = (T) message.content;
        }

        public abstract Integer getFileId();

        public abstract String getFileUniqueId();

        public abstract FileRecord convertFileRecord(long telegramId);

        public T getContent() {
            return content;
        }

        public abstract TdApi.File getFile();

        public JsonObject getExtraInfo() {
            return JsonObject.of();
        }

        public TdApi.Thumbnail getThumbnail() {
            return null;
        }

        public String getThumbnailFileUniqueId() {
            TdApi.Thumbnail thumbnail = getThumbnail();
            if (thumbnail == null) {
                return null;
            }
            return thumbnail.file.remote.uniqueId;
        }

        public FileRecord convertThumbnailRecord(long telegramId) {
            TdApi.Thumbnail thumbnail = getThumbnail();
            if (thumbnail == null) {
                return null;
            }
            FileRecord.DownloadStatus downloadStatus = getDownloadStatus(thumbnail.file);
            return new FileRecord(
                    thumbnail.file.id,
                    thumbnail.file.remote.uniqueId,
                    telegramId,
                    message.chatId,
                    message.id,
                    message.mediaAlbumId,
                    message.date,
                    message.hasSensitiveContent,
                    thumbnail.file.size == 0 ? thumbnail.file.expectedSize : thumbnail.file.size,
                    thumbnail.file.local == null ? 0 : thumbnail.file.local.downloadedSize,
                    "thumbnail",
                    getThumbnailMimeType(thumbnail.format),
                    null,
                    null,
                    null,
                    null,
                    Json.encode(Map.of("width", thumbnail.width,
                            "height", thumbnail.height)),
                    null,
                    downloadStatus == null ? "idle" : downloadStatus.name(),
                    "idle",
                    System.currentTimeMillis(),
                    null,
                    null
            );
        }
    }

    public static class PhotoHandler extends FileHandler<TdApi.MessagePhoto> {

        public PhotoHandler(TdApi.Message message) {
            super(message);
        }

        @Override
        public Integer getFileId() {
            return content.photo.sizes[content.photo.sizes.length - 1].photo.id;
        }

        @Override
        public String getFileUniqueId() {
            return content.photo.sizes[content.photo.sizes.length - 1].photo.remote.uniqueId;
        }

        @Override
        public FileRecord convertFileRecord(long telegramId) {
            TdApi.File file = getFile();
            return new FileRecord(
                    getFileId(),
                    file.remote.uniqueId,
                    telegramId,
                    message.chatId,
                    message.id,
                    message.mediaAlbumId,
                    message.date,
                    message.hasSensitiveContent,
                    file.size == 0 ? file.expectedSize : file.size,
                    file.local == null ? 0 : file.local.downloadedSize,
                    "photo",
                    null,
                    null,
                    Base64.encode((byte[]) BeanUtil.getProperty(content, "photo.minithumbnail.data")),
                    getThumbnailFileUniqueId(),
                    content.caption.text,
                    Json.encode(getExtraInfo()),
                    null,
                    "idle",
                    "idle",
                    System.currentTimeMillis(),
                    null,
                    null
            );
        }

        @Override
        public TdApi.File getFile() {
            return content.photo.sizes[content.photo.sizes.length - 1].photo;
        }

        @Override
        public JsonObject getExtraInfo() {
            TdApi.PhotoSize photo = content.photo.sizes[content.photo.sizes.length - 1];
            return JsonObject.of("width", photo.width,
                    "height", photo.height,
                    "type", photo.type);
        }
    }

    public static class VideoHandler extends FileHandler<TdApi.MessageVideo> {

        public VideoHandler(TdApi.Message message) {
            super(message);
        }

        @Override
        public Integer getFileId() {
            return content.video.video.id;
        }

        @Override
        public String getFileUniqueId() {
            return content.video.video.remote.uniqueId;
        }

        @Override
        public FileRecord convertFileRecord(long telegramId) {
            TdApi.File file = getFile();
            return new FileRecord(
                    file.id,
                    file.remote.uniqueId,
                    telegramId,
                    message.chatId,
                    message.id,
                    message.mediaAlbumId,
                    message.date,
                    message.hasSensitiveContent,
                    file.size == 0 ? file.expectedSize : file.size,
                    file.local == null ? 0 : file.local.downloadedSize,
                    "video",
                    content.video.mimeType,
                    content.video.fileName,
                    Base64.encode((byte[]) BeanUtil.getProperty(content, "video.minithumbnail.data")),
                    getThumbnailFileUniqueId(),
                    content.caption.text,
                    Json.encode(getExtraInfo()),
                    null,
                    "idle",
                    "idle",
                    System.currentTimeMillis(),
                    null,
                    null
            );
        }

        @Override
        public TdApi.File getFile() {
            return content.video.video;
        }

        @Override
        public JsonObject getExtraInfo() {
            TdApi.Video video = content.video;
            return JsonObject.of("width", video.width,
                    "height", video.height,
                    "duration", video.duration,
                    "mimeType", video.mimeType);
        }

        @Override
        public TdApi.Thumbnail getThumbnail() {
            return content.video.thumbnail;
        }
    }

    public static class AudioHandler extends FileHandler<TdApi.MessageAudio> {

        public AudioHandler(TdApi.Message message) {
            super(message);
        }

        @Override
        public Integer getFileId() {
            return content.audio.audio.id;
        }

        @Override
        public String getFileUniqueId() {
            return content.audio.audio.remote.uniqueId;
        }

        @Override
        public FileRecord convertFileRecord(long telegramId) {
            TdApi.File file = getFile();
            return new FileRecord(
                    file.id,
                    file.remote.uniqueId,
                    telegramId,
                    message.chatId,
                    message.id,
                    message.mediaAlbumId,
                    message.date,
                    message.hasSensitiveContent,
                    file.size == 0 ? file.expectedSize : file.size,
                    file.local == null ? 0 : file.local.downloadedSize,
                    "audio",
                    content.audio.mimeType,
                    content.audio.fileName,
                    Base64.encode((byte[]) BeanUtil.getProperty(content, "audio.albumCoverMinithumbnail.data")),
                    getThumbnailFileUniqueId(),
                    content.caption.text,
                    Json.encode(getExtraInfo()),
                    null,
                    "idle",
                    "idle",
                    System.currentTimeMillis(),
                    null,
                    null
            );
        }

        @Override
        public TdApi.File getFile() {
            return content.audio.audio;
        }

        @Override
        public TdApi.Thumbnail getThumbnail() {
            TdApi.Thumbnail thumbnail = content.audio.albumCoverThumbnail;
            if (thumbnail == null && ArrayUtil.isNotEmpty(content.audio.externalAlbumCovers)) {
                thumbnail = content.audio.externalAlbumCovers[0];
            }
            return thumbnail;
        }
    }

    public static class DocumentHandler extends FileHandler<TdApi.MessageDocument> {

        public DocumentHandler(TdApi.Message message) {
            super(message);
        }

        @Override
        public Integer getFileId() {
            return content.document.document.id;
        }

        @Override
        public String getFileUniqueId() {
            return content.document.document.remote.uniqueId;
        }

        @Override
        public FileRecord convertFileRecord(long telegramId) {
            TdApi.File file = getFile();
            return new FileRecord(
                    file.id,
                    file.remote.uniqueId,
                    telegramId,
                    message.chatId,
                    message.id,
                    message.mediaAlbumId,
                    message.date,
                    message.hasSensitiveContent,
                    file.size == 0 ? file.expectedSize : file.size,
                    file.local == null ? 0 : file.local.downloadedSize,
                    "file",
                    content.document.mimeType,
                    content.document.fileName,
                    Base64.encode((byte[]) BeanUtil.getProperty(content, "document.minithumbnail.data")),
                    getThumbnailFileUniqueId(),
                    content.caption.text,
                    Json.encode(getExtraInfo()),
                    null,
                    "idle",
                    "idle",
                    System.currentTimeMillis(),
                    null,
                    null
            );
        }

        @Override
        public TdApi.File getFile() {
            return content.document.document;
        }

        @Override
        public TdApi.Thumbnail getThumbnail() {
            return content.document.thumbnail;
        }
    }

    public static class ComparablePhotoSize implements Comparable<TdApi.PhotoSize> {
        private final TdApi.PhotoSize photoSize;

        private final String[] sizes = {"s", "m", "x", "y", "w", "a", "b", "c", "d"};

        public ComparablePhotoSize(TdApi.PhotoSize photoSize) {
            this.photoSize = photoSize;
        }

        @Override
        public int compareTo(TdApi.PhotoSize o) {
            return Integer.compare(Arrays.binarySearch(sizes, photoSize.type), Arrays.binarySearch(sizes, o.type));
        }

        public int compareTo(String size) {
            return Integer.compare(Arrays.binarySearch(sizes, photoSize.type), Arrays.binarySearch(sizes, size));
        }

        public TdApi.PhotoSize getPhotoSize() {
            return photoSize;
        }
    }
}
