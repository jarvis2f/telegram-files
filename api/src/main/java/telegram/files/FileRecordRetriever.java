package telegram.files;

import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.repository.FileRecord;

import java.util.*;
import java.util.stream.Collectors;

public class FileRecordRetriever {

    public static Future<JsonObject> getFiles(long chatId, Map<String, String> filter) {
        if (chatId == 0 && Convert.toBool(filter.get("seedOnly"), false)) {
            return DataVerticle.torrentRepository.listSeedOnly(filter).map(page -> {
                int offset = Math.max(0, Convert.toInt(filter.get("seedOffset"), 0));
                int nextOffset = offset + page.v1.size();
                return new JsonObject()
                        .put("files", TelegramConverter.convertSeedOnlyFiles(page.v1))
                        .put("nextFromMessageId", 0)
                        .put("nextSeedOffset", nextOffset)
                        .put("hasMore", nextOffset < page.v2)
                        .put("count", page.v2)
                        .put("size", page.v1.size());
            });
        }
        return DataVerticle.fileRepository.getFiles(chatId, filter)
                .compose(r -> getTdMessages(r.v1).map(r::concat))
                .compose(r -> getThumbnails(r.v1).map(r::concat))
                .compose(r -> {
                    Map<String, TdApi.Message> messageMap = r.v4;
                    Map<String, FileRecord> thumbnailMap = r.v5;
                    List<JsonObject> fileRecords = r.v1.stream()
                            .map(fileRecord -> TelegramConverter.withSource(fileRecord.telegramId(),
                                    fileRecord,
                                    StrUtil.isBlank(fileRecord.thumbnailUniqueId()) ? null : thumbnailMap.get(fileRecord.thumbnailUniqueId()),
                                    messageMap.get(fileRecord.uniqueId())
                            ))
                            .filter(Objects::nonNull)
                            .toList();
                    return TelegramConverter.enrichSeedAssociations(fileRecords).map(enriched ->
                            new JsonObject()
                                    .put("files", enriched)
                                    .put("nextFromMessageId", r.v2)
                                    .put("count", r.v3)
                                    .put("size", enriched.size()));
                });
    }

    /**
     * Get messages from telegram
     *
     * @return Future of map of message uniqueId and message
     */
    public static Future<Map<String, TdApi.Message>> getTdMessages(List<FileRecord> fileRecords) {
        if (fileRecords == null || fileRecords.isEmpty()) {
            return Future.succeededFuture(Collections.emptyMap());
        }

        Map<Long, List<FileRecord>> groupingByTelegramIdMap = fileRecords.stream()
                .collect(Collectors.groupingBy(FileRecord::telegramId));

        return Future.all(groupingByTelegramIdMap
                .entrySet()
                .stream()
                .map(entry -> getTdMessages(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList())
        ).map(compositeFuture -> {
            Map<String, TdApi.Message> combinedMessageMap = new HashMap<>();
            for (int i = 0; i < compositeFuture.size(); i++) {
                Map<String, TdApi.Message> partialMessageMap = compositeFuture.resultAt(i);
                combinedMessageMap.putAll(partialMessageMap);
            }
            return combinedMessageMap;
        }).recover(throwable -> Future.failedFuture(new RuntimeException("Failed to get Telegram message", throwable)));
    }

    /**
     * Get messages from telegram
     *
     * @return Future of map of message uniqueId and message
     */
    public static Future<Map<String, TdApi.Message>> getTdMessages(long telegramId, List<FileRecord> fileRecords) {
        if (fileRecords == null || fileRecords.isEmpty()) {
            return Future.succeededFuture(Collections.emptyMap());
        }

        Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            return Future.failedFuture("Telegram verticle not found，unable to get the message. telegramId: " + telegramId);
        }

        Map<Long, List<FileRecord>> groupingByChatIdMap = fileRecords.stream()
                .collect(Collectors.groupingBy(FileRecord::chatId));

        return Future.all(groupingByChatIdMap
                .entrySet()
                .stream()
                .map(entry -> {
                    long chatId = entry.getKey();
                    List<FileRecord> records = entry.getValue();
                    long[] messageIds = records.stream()
                            .mapToLong(FileRecord::messageId)
                            .toArray();

                    return telegramVerticleOptional
                            .get()
                            .client
                            .execute(new TdApi.GetMessages(chatId, messageIds), true)
                            .map(m -> m == null ? Collections.emptyMap() : createMessageMap(chatId, m.messages, records));
                })
                .collect(Collectors.toList())
        ).map(compositeFuture -> {
            Map<String, TdApi.Message> combinedMessageMap = new HashMap<>();
            for (int i = 0; i < compositeFuture.size(); i++) {
                Map<String, TdApi.Message> partialMessageMap = compositeFuture.resultAt(i);
                if (partialMessageMap == null || partialMessageMap.isEmpty()) {
                    continue;
                }
                combinedMessageMap.putAll(partialMessageMap);
            }
            return combinedMessageMap;
        }).recover(throwable -> Future.failedFuture(new RuntimeException("Failed to get Telegram message", throwable)));
    }

    private static Map<String, TdApi.Message> createMessageMap(long chatId, TdApi.Message[] messages, List<FileRecord> records) {
        Map<String, TdApi.Message> messageMap = new HashMap<>();

        for (TdApi.Message message : messages) {
            if (message == null) {
                continue;
            }
            String uniqueId = TdApiHelp.getFileUniqueId(message);

            boolean matchesFileRecord = records.stream()
                    .anyMatch(record -> record.messageId() == message.id && record.chatId() == chatId);

            if (matchesFileRecord) {
                messageMap.put(uniqueId, message);
            }
        }

        return messageMap;
    }

    public static Future<Map<String, FileRecord>> getThumbnails(Collection<FileRecord> fileRecords) {
        if (fileRecords == null || fileRecords.isEmpty()) {
            return Future.succeededFuture(Collections.emptyMap());
        }

        List<String> thumbnailUniqueIds = fileRecords.stream()
                .map(FileRecord::thumbnailUniqueId)
                .filter(Objects::nonNull)
                .toList();

        return DataVerticle.fileRepository
                .getFilesByUniqueId(thumbnailUniqueIds);
    }

    public static Future<TdApi.Message[]> getAlbumMessages(long telegramId, TdApi.Message message) {
        if (telegramId == 0 || message == null) {
            return Future.failedFuture("Message is null, unable to get album messages.");
        }
        long mediaAlbumId = message.mediaAlbumId;
        if (mediaAlbumId == 0) {
            return Future.succeededFuture(new TdApi.Message[]{message});
        }
        Optional<TelegramVerticle> telegramVerticleOptional = TelegramVerticles.get(telegramId);
        if (telegramVerticleOptional.isEmpty()) {
            return Future.failedFuture("Telegram verticle not found，unable to get the message. telegramId: " + telegramId);
        }

        TelegramGateway telegramClient = telegramVerticleOptional.get().client;

        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.chatId = message.chatId;
        searchChatMessages.fromMessageId = message.id;
        searchChatMessages.offset = -10;
        searchChatMessages.limit = 11; // maximum number of messages to retrieve
        return telegramClient.execute(searchChatMessages)
                .map(foundChatMessages -> {
                    TdApi.Message[] albumMessages = Arrays.stream(foundChatMessages.messages)
                            .filter(msg -> msg.mediaAlbumId == mediaAlbumId)
                            .toArray(TdApi.Message[]::new);
                    ArrayUtil.insert(albumMessages, 0, message); // include the original message
                    return albumMessages;
                });
    }
}
