package telegram.files;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.StatisticRecord;

import java.util.*;
import java.util.function.Function;

public class TelegramConverter {

    public static Future<JsonArray> convertChat(long telegramId, List<TdApi.Chat> chats) {
        Map<Long, SettingAutoRecords.Item> enableAutoChats = AutoRecordsHolder.INSTANCE.autoRecords().getItems(telegramId);
        return Future.succeededFuture(new JsonArray(chats.stream()
                .map(chat -> {
                    SettingAutoRecords.Item auto = enableAutoChats.get(chat.id);
                    return new JsonObject()
                            .put("id", Convert.toStr(chat.id))
                            .put("name", chat.id == telegramId ? "Saved Messages" : chat.title)
                            .put("type", TdApiHelp.getChatType(chat.type))
                            .put("avatar", Base64.encode((byte[]) BeanUtil.getProperty(chat, "photo.minithumbnail.data")))
                            .put("unreadCount", chat.unreadCount)
                            .put("lastMessage", "")
                            .put("lastMessageTime", "")
                            .put("auto", auto);
                })
                .toList()
        ));
    }

    public static Future<JsonObject> convertFiles(long telegramId, TdApi.FoundChatMessages foundChatMessages) {
        return DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
                .compose(fileRecords ->
                        FileRecordRetriever.getThumbnails(fileRecords.values())
                                .map(thumbnails -> Tuple.tuple(fileRecords, thumbnails))
                )
                .compose(t -> DataVerticle.settingRepository.<Boolean>getByKey(SettingKey.uniqueOnly).map(t::concat))
                .map(t -> {
                    Map<String, FileRecord> fileRecords = t.v1;
                    Map<String, FileRecord> thumbnails = t.v2;
                    List<TdApi.Message> messages = t.v3 ? TdApiHelp.filterUniqueMessages(Arrays.asList(foundChatMessages.messages))
                            : Arrays.asList(foundChatMessages.messages);

                    List<JsonObject> fileObjects = messages.stream()
                            .filter(message -> TdApiHelp.FILE_CONTENT_CONSTRUCTORS.contains(message.content.getConstructor()))
                            .map(message -> {
                                //TODO Processing of the same file under different accounts

                                FileRecord fileRecord = fileRecords.get(TdApiHelp.getFileUniqueId(message));
                                return withSource(telegramId,
                                        fileRecord,
                                        fileRecord == null || StrUtil.isBlank(fileRecord.thumbnailUniqueId()) ? null : thumbnails.get(fileRecord.thumbnailUniqueId()),
                                        message);
                            })
                            .filter(Objects::nonNull)
                            .toList();
                    return new JsonObject()
                            .put("files", new JsonArray(fileObjects))
                            .put("count", foundChatMessages.totalCount)
                            .put("size", fileObjects.size())
                            .put("nextFromMessageId", foundChatMessages.nextFromMessageId);
                });
    }

    public static List<JsonObject> convertRangedSpeedStats(List<StatisticRecord> statisticRecords, int timeRange) {
        TreeMap<String, List<JsonObject>> groupedSpeedStats = new TreeMap<>(Comparator.comparing(
                switch (timeRange) {
                    case 1, 2 -> (Function<? super String, ? extends DateTime>) time ->
                            DateUtil.parse(time, DatePattern.NORM_DATETIME_MINUTE_FORMAT);
                    case 3, 4 -> DateUtil::parseDate;
                    default -> throw new IllegalStateException("Unexpected value: " + timeRange);
                }
        ));
        for (StatisticRecord record : statisticRecords) {
            JsonObject data = new JsonObject(record.data());
            long timestamp = record.timestamp();
            String time = switch (timeRange) {
                case 1 ->
                        MessyUtils.withGrouping5Minutes(DateUtil.toLocalDateTime(DateUtil.date(timestamp))).format(DatePattern.NORM_DATETIME_MINUTE_FORMATTER);
                case 2 -> DateUtil.date(timestamp).setField(DateField.MINUTE, 0).toString(DatePattern.NORM_DATETIME_MINUTE_FORMAT);
                case 3, 4 -> DateUtil.date(timestamp).setField(DateField.MINUTE, 0).toString(DatePattern.NORM_DATE_FORMAT);
                default -> throw new IllegalStateException("Unexpected value: " + timeRange);
            };
            groupedSpeedStats.computeIfAbsent(time, k -> new ArrayList<>()).add(data);
        }
        return groupedSpeedStats.entrySet().stream()
                .map(entry -> {
                    JsonObject speedStat = entry.getValue().stream().reduce(new JsonObject()
                                    .put("avgSpeed", 0)
                                    .put("medianSpeed", 0)
                                    .put("maxSpeed", 0)
                                    .put("minSpeed", 0),
                            (a, b) -> new JsonObject()
                                    .put("avgSpeed", a.getLong("avgSpeed") + b.getLong("avgSpeed"))
                                    .put("medianSpeed", a.getLong("medianSpeed") + b.getLong("medianSpeed"))
                                    .put("maxSpeed", a.getLong("maxSpeed") + b.getLong("maxSpeed"))
                                    .put("minSpeed", a.getLong("minSpeed") + b.getLong("minSpeed"))
                    );
                    int size = entry.getValue().size();
                    speedStat.put("avgSpeed", speedStat.getLong("avgSpeed") / size)
                            .put("medianSpeed", speedStat.getLong("medianSpeed") / size)
                            .put("maxSpeed", speedStat.getLong("maxSpeed") / size)
                            .put("minSpeed", speedStat.getLong("minSpeed") / size);
                    return new JsonObject()
                            .put("time", entry.getKey())
                            .put("data", speedStat);
                })
                .toList();
    }

    public static JsonObject withSource(long telegramId,
                                        FileRecord fileRecord,
                                        FileRecord thumbnailRecord,
                                        TdApi.Message message) {
        TdApiHelp.FileHandler<? extends TdApi.MessageContent> fileHandler = TdApiHelp.getFileHandler(message)
                .orElse(null);
        boolean loaded = fileRecord != null;
        if (!loaded && fileHandler == null) {
            return null;
        }

        if (fileHandler != null) {
            FileRecord source = fileHandler.convertFileRecord(telegramId);
            if (fileRecord == null) {
                fileRecord = source;
            } else {
                fileRecord = fileRecord.withSourceField(source.id(), source.downloadedSize());
            }
        }

        if (fileRecord == null) {
            return null;
        }
        JsonObject extra = fileRecord.extra() == null ? null : (JsonObject) Json.decodeValue(fileRecord.extra());
        if (extra == null && fileHandler != null) {
            extra = fileHandler.getExtraInfo();
        }

        JsonObject fileObject = JsonObject.mapFrom(fileRecord);
        fileObject.put("loaded", loaded);
        fileObject.put("formatDate", DateUtil.date(fileObject.getLong("date") * 1000).toString());
        fileObject.put("extra", extra);
        fileObject.put("originalDeleted", message == null);

        // Put thumbnail information
        if (thumbnailRecord != null) {
            fileObject.put("thumbnailFile", JsonObject.of(
                    "uniqueId", thumbnailRecord.uniqueId(),
                    "mimeType", thumbnailRecord.mimeType(),
                    "extra", StrUtil.isBlank(thumbnailRecord.extra()) ? null : Json.decodeValue(thumbnailRecord.extra())
            ));
        }

        return fileObject;
    }
}
