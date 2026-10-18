package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;
import telegram.files.repository.SettingTimeLimitedDownload;

import java.time.LocalTime;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class AutoDownloadVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final int DEFAULT_LIMIT = 5;

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int MAX_HISTORY_SCAN_TIME = 10 * 1000;

    private static final int MAX_WAITING_LENGTH = 30;

    private static final int DOWNLOAD_INTERVAL = 10 * 1000;

    private static final List<String> DEFAULT_FILE_TYPE_ORDER = List.of("photo", "video", "audio", "file");

    // telegramId -> messages
    private final Map<Long, LinkedList<MessageWrapper>> waitingDownloadMessages = new ConcurrentHashMap<>();

    // telegramId -> waiting scan threads
    private final Map<Long, LinkedList<WaitingScanThread>> waitingScanThreads = new ConcurrentHashMap<>();

    private final SettingAutoRecords autoRecords;

    private int limit = DEFAULT_LIMIT;

    private SettingTimeLimitedDownload timeLimited;

    public AutoDownloadVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        AutomationsHolder.INSTANCE.registerOnRemoveListener(removedItems -> removedItems.forEach(item ->
                waitingDownloadMessages.getOrDefault(item.telegramId, new LinkedList<>())
                        .removeIf(m -> m.message.chatId == item.chatId)));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initAutoDownload()
                .compose(_ -> this.initEventConsumer())
                .onSuccess(_ -> {
                    vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL,
                            _ -> {
                                if (!isDownloadTime()) {
                                    log.debug("Auto download time limited! Skip scan history.");
                                    return;
                                }

                                autoRecords.getDownloadEnabledItems()
                                        .stream()
                                        .filter(auto -> auto.download.rule.downloadHistory
                                                        && auto.isNotComplete(SettingAutoRecords.HISTORY_DOWNLOAD_STATE))
                                        .forEach(auto -> {
                                            if (isDownloadCommentEnabled(auto)
                                                && CollUtil.isNotEmpty(waitingScanThreads.get(auto.telegramId))) {
                                                addCommentMessage(auto);
                                            } else {
                                                if (auto.isNotComplete(SettingAutoRecords.HISTORY_DOWNLOAD_SCAN_STATE)) {
                                                    addHistoryMessage(auto);
                                                } else {
                                                    LinkedList<MessageWrapper> messageWrappers = waitingDownloadMessages.get(auto.telegramId);
                                                    if (CollUtil.isEmpty(messageWrappers) ||
                                                        messageWrappers.stream().noneMatch(w -> w.isHistorical)) {
                                                        auto.complete(SettingAutoRecords.HISTORY_DOWNLOAD_STATE);
                                                    }
                                                }
                                            }
                                        });
                            });
                    vertx.setPeriodic(0, DOWNLOAD_INTERVAL,
                            _ -> {
                                if (!isDownloadTime()) {
                                    log.debug("Auto download time limited! Skip download.");
                                    return;
                                }
                                waitingDownloadMessages.keySet().forEach(this::download);
                            });

                    log.info("""
                            Auto download verticle started!
                            |History scan interval: %s ms
                            |Download interval: %s ms
                            |Download limit: %s per telegram account!
                            |Time limit: %s
                            |Auto chats: %s
                            """.formatted(HISTORY_SCAN_INTERVAL,
                            DOWNLOAD_INTERVAL,
                            limit,
                            timeLimited == null ? "" : Json.encode(timeLimited),
                            autoRecords.getDownloadEnabledItems().size()));

                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop() {
        log.info("Auto download verticle stopped!");
    }

    private Future<Void> initAutoDownload() {
        return Future.all(
                        DataVerticle.settingRepository.<Integer>getByKey(SettingKey.autoDownloadLimit),
                        DataVerticle.settingRepository.<SettingTimeLimitedDownload>getByKey(SettingKey.autoDownloadTimeLimited)
                )
                .onSuccess(results -> {
                    if (results.resultAt(0) != null) {
                        this.limit = results.resultAt(0);
                    }
                    this.timeLimited = results.resultAt(1);
                })
                .onFailure(e -> log.error("Get Auto download limit failed!", e))
                .mapEmpty();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadLimit.name()), message -> {
            log.debug("Auto download limit update: %s".formatted(message.body()));
            this.limit = Convert.toInt(message.body(), DEFAULT_LIMIT);
        });
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.autoDownloadTimeLimited.name()), message -> {
            log.debug("Auto download time limit update: %s".formatted(message.body()));
            this.timeLimited = (SettingTimeLimitedDownload) SettingKey.autoDownloadTimeLimited.converter.apply((String) message.body());
        });
        vertx.eventBus().consumer(EventEnum.MESSAGE_RECEIVED.address(), message -> {
            log.trace("Auto download message received: %s".formatted(message.body()));
            this.onNewMessage((JsonObject) message.body());
        });
        return Future.succeededFuture();
    }

    private void addCommentMessage(SettingAutoRecords.Automation auto) {
        LinkedList<WaitingScanThread> scanThreads = waitingScanThreads.get(auto.telegramId);
        if (CollUtil.isEmpty(scanThreads)) {
            return;
        }
        scanThreads.removeIf(scanThread -> scanThread.isComplete);
        waitingScanThreads.get(auto.telegramId).forEach(scanThread -> {
            ScanParams scanParams = new ScanParams(auto.uniqueKey() + ":" + scanThread.messageThreadId,
                    auto.download.rule,
                    auto.telegramId,
                    scanThread.threadChatId,
                    scanThread.nextFileType,
                    scanThread.nextFromMessageId);
            scanParams.messageThreadId = scanThread.messageThreadId;
            addHistoryMessage(scanParams,
                    result -> {
                        scanThread.nextFileType = result.nextFileType;
                        scanThread.nextFromMessageId = result.nextFromMessageId;
                        if (result.isComplete) {
                            scanThread.isComplete = true;
                        }
                    },
                    System.currentTimeMillis()
            );
        });
    }

    private void addHistoryMessage(SettingAutoRecords.Automation auto) {
        addHistoryMessage(new ScanParams(auto.uniqueKey(),
                        auto.download.rule,
                        auto.telegramId,
                        auto.chatId,
                        auto.download.nextFileType,
                        auto.download.nextFromMessageId),
                result -> {
                    auto.download.nextFileType = result.nextFileType;
                    auto.download.nextFromMessageId = result.nextFromMessageId;
                    if (result.isComplete) {
                        auto.complete(SettingAutoRecords.HISTORY_DOWNLOAD_SCAN_STATE);
                    }
                },
                System.currentTimeMillis()
        );
    }

    private void addHistoryMessage(ScanParams params,
                                   Consumer<ScanResult> callback,
                                   long currentTimeMillis) {
        String uniqueKey = params.uniqueKey;
        long telegramId = params.telegramId;
        long chatId = params.chatId;
        long nextFromMessageId = params.nextFromMessageId;
        String nextFileType = params.nextFileType;
        Tuple3<String, List<String>, String> rule = handleRule(params.rule);
        if (StrUtil.isBlank(nextFileType)) {
            nextFileType = rule.v2.getFirst();
        }

        log.debug("Start scan history! TelegramId: %d ChatId: %d FileType: %s".formatted(telegramId, chatId, nextFileType));
        if (System.currentTimeMillis() - currentTimeMillis > MAX_HISTORY_SCAN_TIME) {
            log.debug("Scan history timeout! TelegramId: %d ChatId: %d".formatted(telegramId, chatId));
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        if (isExceedLimit(telegramId)) {
            log.debug("Scan history exceed per telegram account limit! TelegramId: %d ChatId: %d".formatted(telegramId, chatId));
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }

        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        if (!telegramVerticle.authorized) {
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
        searchChatMessages.query = rule.v1;
        searchChatMessages.chatId = chatId;
        searchChatMessages.fromMessageId = nextFromMessageId;
        searchChatMessages.limit = Math.min(MAX_WAITING_LENGTH, 100);
        searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(nextFileType);
        searchChatMessages.topicId = params.messageThreadId > 0 ? new TdApi.MessageTopicThread(params.messageThreadId) : null;
        String finalNextFileType = nextFileType;
        TdApi.FoundChatMessages foundChatMessages = Future.await(telegramVerticle.client.execute(searchChatMessages)
                .onFailure(r -> {
                    log.warn("Search chat messages failed! TelegramId: %d ChatId: %d".formatted(telegramId, chatId), r);
                    if (r instanceof TelegramRunException tre) {
                        TdApi.Error error = tre.getError();
                        if (error.code == 400 && ("Can't access the chat".equals(error.message))) {
                            log.error("%s Can't access the chat, stop auto download!".formatted(uniqueKey));
                            callback.accept(new ScanResult(finalNextFileType, nextFromMessageId, true));
                        }
                    }
                })
        );
        if (foundChatMessages == null) {
            callback.accept(new ScanResult(nextFileType, nextFromMessageId, false));
            return;
        }
        if (foundChatMessages.messages.length == 0) {
            List<String> fileTypes = rule.v2;
            int nextTypeIndex = fileTypes.indexOf(nextFileType) + 1;
            if (nextTypeIndex < fileTypes.size()) {
                params.nextFileType = fileTypes.get(nextTypeIndex);
                params.nextFromMessageId = 0;
                log.debug("%s No more %s files found! Switch to %s".formatted(uniqueKey, nextFileType, params.nextFileType));
                addHistoryMessage(params, callback, currentTimeMillis);
            } else {
                log.debug("%s No more history files found! TelegramId: %d ChatId: %d".formatted(uniqueKey, telegramId, chatId));
                callback.accept(new ScanResult(nextFileType, nextFromMessageId, true));
            }
        } else {
            Predicate<TdApi.Message> predicate = MessageFilter.filter(rule.v3);
            DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
                    .onSuccess(existFiles -> {
                        List<TdApi.Message> messages = Stream.of(foundChatMessages.messages)
                                .parallel()
                                .filter(predicate)
                                .filter(message -> {
                                    String uniqueId = TdApiHelp.getFileUniqueId(message);
                                    if (!existFiles.containsKey(uniqueId)) {
                                        return true;
                                    } else {
                                        FileRecord fileRecord = existFiles.get(uniqueId);
                                        return fileRecord.isDownloadStatus(FileRecord.DownloadStatus.idle)
                                               && !fileRecord.isTransferStatus(FileRecord.TransferStatus.completed);
                                    }
                                })
                                .toList();
                        if (CollUtil.isEmpty(messages)) {
                            params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                            addHistoryMessage(params, callback, currentTimeMillis);
                        } else if (addWaitingDownloadMessages(telegramId, messages, false, true)) {
                            params.nextFromMessageId = foundChatMessages.nextFromMessageId;
                            addHistoryMessage(params, callback, currentTimeMillis);
                        }
                    });
        }
    }

    private Tuple3<String, List<String>, String> handleRule(SettingAutoRecords.DownloadRule rule) {
        String query = null;
        List<String> fileTypes = DEFAULT_FILE_TYPE_ORDER;
        String filterExpr = null;
        if (rule != null) {
            if (StrUtil.isNotBlank(rule.query)) {
                query = rule.query;
            }
            if (CollUtil.isNotEmpty(rule.fileTypes)) {
                fileTypes = rule.fileTypes;
            }
            if (StrUtil.isNotBlank(rule.filterExpr)) {
                filterExpr = rule.filterExpr;
            }
        }
        return new Tuple3<>(query, fileTypes, filterExpr);
    }

    private boolean isDownloadTime() {
        if (timeLimited == null) {
            return true;
        }
        LocalTime now = LocalTime.now();

        LocalTime startTime = LocalTime.parse(timeLimited.startTime);
        LocalTime endTime = LocalTime.parse(timeLimited.endTime);
        if (startTime.equals(LocalTime.MIN) && endTime.equals(LocalTime.MIN)) {
            return true;
        }

        if (startTime.isAfter(endTime)) {
            return now.isAfter(startTime) || now.isBefore(endTime);
        } else {
            return now.isAfter(startTime) && now.isBefore(endTime);
        }
    }

    private boolean isExceedLimit(long telegramId) {
        List<MessageWrapper> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        return getSurplusSize(telegramId) <= 0 || (waitingMessages != null && waitingMessages.size() > limit);
    }

    private int getSurplusSize(long telegramId) {
        Integer downloading = Future.await(DataVerticle.fileRepository.countByStatus(telegramId, FileRecord.DownloadStatus.downloading));
        return downloading == null ? limit : Math.max(0, limit - downloading);
    }

    private boolean isDownloadCommentEnabled(SettingAutoRecords.Automation auto) {
        if (!auto.download.enabled || !auto.download.rule.downloadCommentFiles) {
            return false;
        }
        return TelegramVerticles.get(auto.telegramId)
                .map(telegramVerticle -> telegramVerticle.getChat(auto.chatId))
                .map(chat -> chat.type.getConstructor() == TdApi.ChatTypeSupergroup.CONSTRUCTOR
                             && ((TdApi.ChatTypeSupergroup) chat.type).isChannel)
                .orElse(false);
    }

    private boolean addWaitingDownloadMessages(long telegramId,
                                               List<TdApi.Message> messages,
                                               boolean force,
                                               boolean isHistorical) {
        if (CollUtil.isEmpty(messages)) {
            return false;
        }
        LinkedList<MessageWrapper> waitingMessages = this.waitingDownloadMessages.get(telegramId);
        if (waitingMessages == null) {
            waitingMessages = new LinkedList<>();
        }
        if (!force && waitingMessages.size() > MAX_WAITING_LENGTH) {
            return false;
        } else {
            log.debug("Add waiting download messages: %d".formatted(messages.size()));
            waitingMessages.addAll(TdApiHelp.filterUniqueMessages(messages)
                    .stream()
                    .map(message -> new MessageWrapper(message, isHistorical))
                    .toList()
            );
        }
        this.waitingDownloadMessages.put(telegramId, waitingMessages);
        return true;
    }

    private void download(long telegramId) {
        if (CollUtil.isEmpty(waitingDownloadMessages)) {
            return;
        }
        LinkedList<MessageWrapper> messages = waitingDownloadMessages.get(telegramId);
        if (CollUtil.isEmpty(messages)) {
            return;
        }
        log.debug("Download start! TelegramId: %d size: %d".formatted(telegramId, messages.size()));
        TelegramVerticle telegramVerticle = TelegramVerticles.getOrElseThrow(telegramId);
        if (!telegramVerticle.authorized) {
            return;
        }
        int surplusSize = getSurplusSize(telegramId);
        if (surplusSize <= 0) {
            return;
        }

        List<MessageWrapper> downloadMessages = IntStream.range(0, Math.min(surplusSize, messages.size()))
                .mapToObj(_ -> messages.poll())
                .toList();
        downloadMessages.forEach(messageWrapper -> {
            TdApi.Message message = messageWrapper.message;
            Integer fileId = TdApiHelp.getFileId(message);
            log.debug("Start download file: %s".formatted(fileId));
            telegramVerticle.startDownload(message.chatId, message.id, fileId)
                    .onSuccess(fileRecord -> {
                        log.info("Start download file success! ChatId: %d MessageId:%d FileId:%d"
                                .formatted(message.chatId, message.id, fileId));
                        if (fileRecord.threadChatId() != 0
                            && fileRecord.messageThreadId() != 0
                            && fileRecord.threadChatId() != fileRecord.chatId()) {
                            waitingScanThreads.computeIfAbsent(telegramId, _ -> new LinkedList<>())
                                    .add(new WaitingScanThread(telegramId, fileRecord.threadChatId(), fileRecord.messageThreadId()));
                        }
                    })
                    .onFailure(e -> log.error("Download file failed! ChatId: %d MessageId:%d FileId:%d"
                            .formatted(message.chatId, message.id, fileId), e));
        });
        log.debug("Remaining download messages: %d".formatted(messages.size()));
    }

    private void onNewMessage(JsonObject jsonObject) {
        long telegramId = jsonObject.getLong("telegramId");
        long chatId = jsonObject.getLong("chatId");
        long messageId = jsonObject.getLong("messageId");
        autoRecords.getDownloadEnabledItems().stream()
                .filter(item -> item.telegramId == telegramId && item.chatId == chatId)
                .findFirst()
                .flatMap(_ -> TelegramVerticles.get(telegramId))
                .ifPresent(telegramVerticle -> {
                    if (telegramVerticle.authorized) {
                        telegramVerticle.client.execute(new TdApi.GetMessage(chatId, messageId))
                                .onSuccess(message -> addWaitingDownloadMessages(telegramId, List.of(message), true, false))
                                .onFailure(e -> log.error("Auto download fail. Get message failed: %s".formatted(e.getMessage())));
                    }
                });
    }

    private static class ScanParams {
        public String uniqueKey;

        public SettingAutoRecords.DownloadRule rule;

        public long telegramId;

        public long chatId;

        public long messageThreadId;

        public String nextFileType;

        public long nextFromMessageId;

        public ScanParams(String uniqueKey,
                          SettingAutoRecords.DownloadRule rule,
                          long telegramId,
                          long chatId,
                          String nextFileType,
                          long nextFromMessageId) {
            this.uniqueKey = uniqueKey;
            this.rule = rule;
            this.telegramId = telegramId;
            this.chatId = chatId;
            this.nextFileType = nextFileType;
            this.nextFromMessageId = nextFromMessageId;
        }
    }

    private static class ScanResult {
        public String nextFileType;

        public long nextFromMessageId;

        public boolean isComplete;

        public ScanResult(String nextFileType, long nextFromMessageId, boolean isComplete) {
            this.nextFileType = nextFileType;
            this.nextFromMessageId = nextFromMessageId;
            this.isComplete = isComplete;
        }
    }

    private static class WaitingScanThread {
        public long telegramId;

        public long threadChatId;

        public long messageThreadId;

        public String nextFileType;

        public long nextFromMessageId;

        public boolean isComplete;

        public WaitingScanThread(long telegramId, long threadChatId, long messageThreadId) {
            this.telegramId = telegramId;
            this.threadChatId = threadChatId;
            this.messageThreadId = messageThreadId;
        }
    }

    private record MessageWrapper(TdApi.Message message, boolean isHistorical) {
    }
}
