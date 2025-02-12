package telegram.files;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.codec.Base64;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.date.DateField;
import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.impl.NoStackTraceException;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import org.jooq.lambda.tuple.Tuple;
import org.jooq.lambda.tuple.Tuple2;
import telegram.files.repository.*;

import java.io.File;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TelegramVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    public TelegramClient client;

    private TelegramChats telegramChats;

    public boolean authorized = false;

    public TdApi.AuthorizationState lastAuthorizationState;

    public String rootPath;

    private String proxyName;

    private String rootId;

    private boolean needDelete = false;

    public TelegramRecord telegramRecord;

    private AvgSpeed avgSpeed = new AvgSpeed();

    private long avgSpeedPersistenceTimerId;

    private long lastFileEventTime;

    private long lastFileDownloadEventTime;

    public TelegramVerticle(String rootPath) {
        this.rootPath = rootPath;
    }

    public TelegramVerticle(TelegramRecord telegramRecord) {
        this.telegramRecord = telegramRecord;
        this.rootPath = telegramRecord.rootPath();
        this.proxyName = telegramRecord.proxy();
    }

    public String getRootId() {
        if (StrUtil.isNotBlank(this.rootId)) return rootId;

        this.rootId = StrUtil.subAfter(this.rootPath, '-', true);
        return this.rootId;
    }

    public Object getId() {
        return telegramRecord == null ? this.getRootId() : telegramRecord.id();
    }

    public void setProxy(String proxyName) {
        this.proxyName = proxyName;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        client = new TelegramClient();
        telegramChats = new TelegramChats(client);
        TelegramUpdateHandler telegramUpdateHandler = new TelegramUpdateHandler();
        telegramUpdateHandler.setOnAuthorizationStateUpdated(this::onAuthorizationStateUpdated);
        telegramUpdateHandler.setOnFileUpdated(this::onFileUpdated);
        telegramUpdateHandler.setOnFileDownloadsUpdated(this::onFileDownloadsUpdated);
        telegramUpdateHandler.setOnChatUpdated(telegramChats::onChatUpdated);
        telegramUpdateHandler.setOnMessageReceived(this::onMessageReceived);

        client.initialize(telegramUpdateHandler, this::handleException, this::handleException);
        Future.all(initEventConsumer(), initAvgSpeed())
                .compose(r -> this.enableProxy(this.proxyName))
                .onSuccess(r -> startPromise.complete())
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        this.close(false)
                .onComplete(stopPromise);
    }

    public Future<Void> close(boolean needDelete) {
        return client.execute(new TdApi.Close())
                .onSuccess(r -> {
                    log.info("[%s] Telegram account closed".formatted(this.getRootId()));
                    this.needDelete = needDelete;
                })
                .onFailure(e -> log.error("[%s] Failed to close telegram account: %s".formatted(this.getRootId(), e.getMessage())))
                .mapEmpty();
    }

    public boolean check() {
        if (StrUtil.isBlank(this.rootPath) || !FileUtil.exist(this.rootPath)) {
            log.error("[%s] Telegram account is invalid, root path: %s not exist.".formatted(this.getRootId(), this.rootPath));
            return false;
        }
        return true;
    }

    public Future<JsonObject> getTelegramAccount() {
        return Future.future(promise -> {
            if (!authorized) {
                JsonObject jsonObject = new JsonObject()
                        .put("id", this.getRootId())
                        .put("name", this.getRootId())
                        .put("phoneNumber", "")
                        .put("avatar", "")
                        .put("status", "inactive")
                        .put("rootPath", this.rootPath)
                        .put("isPremium", false)
                        .put("lastAuthorizationState", lastAuthorizationState)
                        .put("proxy", this.proxyName);
                if (this.telegramRecord != null) {
                    jsonObject.put("id", Convert.toStr(this.telegramRecord.id()))
                            .put("name", this.telegramRecord.firstName());
                }
                promise.complete(jsonObject);
                return;
            }
            client.execute(new TdApi.GetMe())
                    .onSuccess(user -> {
                        JsonObject result = new JsonObject()
                                .put("id", Convert.toStr(user.id))
                                .put("name", StrUtil.join(user.firstName, " ", user.lastName))
                                .put("phoneNumber", user.phoneNumber)
                                .put("avatar", Base64.encode((byte[]) BeanUtil.getProperty(user, "profilePhoto.minithumbnail.data")))
                                .put("status", "active")
                                .put("rootPath", this.rootPath)
                                .put("isPremium", user.isPremium)
                                .put("proxy", this.proxyName);
                        promise.complete(result);
                    })
                    .onFailure(e -> {
                        log.error("[%s] Failed to get telegram account: %s".formatted(this.getRootId(), e.getMessage()));
                        promise.fail(e);
                    });
        });
    }

    public Future<JsonArray> getChats(Long activatedChatId, String query, boolean archived) {
        return this.convertChat(telegramChats.getChatList(activatedChatId, query, 100, archived));
    }

    public Future<JsonObject> getChatFiles(long chatId, Map<String, String> filter) {
        String downloadStatus = filter.get("downloadStatus");
        if (Arrays.asList("downloading", "paused", "completed", "error").contains(downloadStatus)) {
            return DataVerticle.fileRepository.getFiles(chatId, filter)
                    .compose(r -> {
                        long[] messageIds = r.v1.stream().mapToLong(FileRecord::messageId).toArray();
                        return client.execute(new TdApi.GetMessages(chatId, messageIds))
                                .map(m -> {
                                    Map<Long, TdApi.Message> messageMap = Arrays.stream(m.messages)
                                            .filter(Objects::nonNull)
                                            .collect(Collectors.toMap(message -> message.id, Function.identity()));
                                    List<JsonObject> fileRecords = r.v1.stream()
                                            .map(fileRecord ->
                                                    this.withSource(fileRecord, messageMap.get(fileRecord.messageId())))
                                            .filter(Objects::nonNull)
                                            .toList();
                                    return Tuple.tuple(fileRecords, r.v2, r.v3);
                                });
                    })
                    .map(tuple -> new JsonObject()
                            .put("files", tuple.v1)
                            .put("nextFromMessageId", tuple.v2)
                            .put("count", tuple.v3)
                            .put("size", tuple.v1.size())
                    );
        } else {
            TdApi.SearchChatMessages searchChatMessages = new TdApi.SearchChatMessages();
            searchChatMessages.chatId = chatId;
            searchChatMessages.query = filter.get("search");
            searchChatMessages.fromMessageId = Convert.toLong(filter.get("fromMessageId"), 0L);
            searchChatMessages.offset = Convert.toInt(filter.get("offset"), 0);
            searchChatMessages.limit = Convert.toInt(filter.get("limit"), 20);
            searchChatMessages.filter = TdApiHelp.getSearchMessagesFilter(filter.get("type"));

            return (Objects.equals(filter.get("downloadStatus"), FileRecord.DownloadStatus.idle.name()) ?
                    this.getIdleChatFiles(searchChatMessages, 0) :
                    client.execute(searchChatMessages))
                    .compose(foundChatMessages ->
                            DataVerticle.fileRepository.getFilesByUniqueId(TdApiHelp.getFileUniqueIds(Arrays.asList(foundChatMessages.messages)))
                                    .map(fileRecords -> Tuple.tuple(foundChatMessages, fileRecords)))
                    .compose(this::convertFiles);
        }
    }

    private Future<TdApi.FoundChatMessages> getIdleChatFiles(TdApi.SearchChatMessages searchChatMessages, int seq) {
        if (seq != 0) {
            // Increase the limit and reduce the number of requests
            searchChatMessages.limit = 100;
        }
        return client.execute(searchChatMessages)
                .compose(foundChatMessages -> {
                    TdApi.Message[] messages = Stream.of(foundChatMessages.messages)
                            .filter(message ->
                                    TdApiHelp.getFileHandler(message)
                                            .map(TdApiHelp.FileHandler::getFile)
                                            .map(file -> file.local == null || (
                                                    !file.local.isDownloadingActive
                                                    && !file.local.isDownloadingCompleted
                                                    && file.local.downloadedSize == 0
                                            ))
                                            .orElse(false)
                            )
                            .toArray(TdApi.Message[]::new);
                    if (ArrayUtil.isEmpty(messages) && foundChatMessages.nextFromMessageId != 0) {
                        searchChatMessages.fromMessageId = foundChatMessages.nextFromMessageId;
                        return getIdleChatFiles(searchChatMessages, seq + 1);
                    } else {
                        foundChatMessages.messages = messages;
                        return Future.succeededFuture(foundChatMessages);
                    }
                });
    }

    public Future<JsonObject> getChatFilesCount(long chatId) {
        return Future.all(
                Stream.of(new TdApi.SearchMessagesFilterPhotoAndVideo(),
                                new TdApi.SearchMessagesFilterPhoto(),
                                new TdApi.SearchMessagesFilterVideo(),
                                new TdApi.SearchMessagesFilterAudio(),
                                new TdApi.SearchMessagesFilterDocument())
                        .map(filter -> client.execute(
                                                new TdApi.GetChatMessageCount(chatId,
                                                        filter,
                                                        0,
                                                        false)
                                        )
                                        .map(count -> new JsonObject()
                                                .put("type", TdApiHelp.getSearchMessagesFilterType(filter))
                                                .put("count", count.count)
                                        )
                        )
                        .toList()
        ).map(counts -> {
            JsonObject result = new JsonObject();
            counts.<JsonObject>list().forEach(count -> result.put(count.getString("type"), count.getInteger("count")));
            return result;
        });
    }

    public Future<Tuple2<String, String>> loadPreview(String uniqueId) {
        return DataVerticle.fileRepository
                .getByUniqueId(uniqueId)
                .compose(fileRecord -> {
                    if (fileRecord == null || !fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
                        || !FileUtil.exist(fileRecord.localPath())) {
                        return Future.failedFuture("File not found or not downloaded");
                    }
                    return Future.succeededFuture(Tuple.tuple(fileRecord.localPath(), fileRecord.mimeType()));
                });
    }

    public Future<TdApi.File> startDownload(Long chatId, Long messageId, Integer fileId) {
        return Future.all(
                        client.execute(new TdApi.GetFile(fileId)),
                        client.execute(new TdApi.GetMessage(chatId, messageId))
                )
                .compose(results -> {
                    TdApi.File file = results.resultAt(0);
                    TdApi.Message message = results.resultAt(1);
                    if (file.local != null) {
                        if (file.local.isDownloadingCompleted) {
                            return DataVerticle.fileRepository.updateDownloadStatus(
                                    file.id,
                                    file.remote.uniqueId,
                                    file.local.path,
                                    FileRecord.DownloadStatus.completed,
                                    System.currentTimeMillis()
                            ).compose(r -> Future.failedFuture("File is already downloaded successfully"));
                        }
                        if (file.local.isDownloadingActive) {
                            return Future.failedFuture("File is downloading");
                        }
//                        return Future.failedFuture("Unknown file download status");
                    }

                    TdApiHelp.FileHandler<? extends TdApi.MessageContent> fileHandler = TdApiHelp.getFileHandler(message)
                            .orElseThrow(() -> new NoStackTraceException("not support message type"));
                    FileRecord fileRecord = fileHandler.convertFileRecord(telegramRecord.id());
                    return DataVerticle.fileRepository.create(fileRecord)
                            .compose(r ->
                                    client.execute(new TdApi.AddFileToDownloads(fileId, chatId, messageId, 32))
                            )
                            .onSuccess(r ->
                                    sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                            .put("fileId", fileId)
                                            .put("uniqueId", fileRecord.uniqueId())
                                            .put("downloadStatus", FileRecord.DownloadStatus.downloading)
                                    ))
                            );
                });
    }

    public Future<Void> cancelDownload(Integer fileId) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }

                    return client.execute(new TdApi.CancelDownloadFile(fileId, false))
                            .map(file);
                })
                .compose(file -> client.execute(new TdApi.DeleteFile(fileId)).map(file))
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(file.remote.uniqueId).map(file))
                .onSuccess(file ->
                        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileId)
                                .put("uniqueId", file.remote.uniqueId)
                                .put("downloadStatus", FileRecord.DownloadStatus.idle)
                        )))
                .mapEmpty();
    }

    public Future<Void> togglePauseDownload(Integer fileId, boolean isPaused) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .updateFileId(file.id, file.remote.uniqueId)
                        .map(file)
                )
                .compose(file -> {
                    if (file.local == null) {
                        return Future.failedFuture("File not started downloading");
                    }
                    if (file.local.isDownloadingCompleted) {
                        return DataVerticle.fileRepository.updateDownloadStatus(
                                file.id,
                                file.remote.uniqueId,
                                file.local.path,
                                FileRecord.DownloadStatus.completed,
                                System.currentTimeMillis()
                        ).compose(r -> {
                            sendFileStatusHttpEvent(file, r);
                            if (r == null || r.isEmpty()) {
                                return Future.failedFuture("File is downloaded completed, but update status failed");
                            } else {
                                return Future.failedFuture("File is already downloaded successfully");
                            }
                        });
                    }
                    if (isPaused && !file.local.isDownloadingActive) {
                        return Future.failedFuture("File is not downloading");
                    }
                    if (!isPaused && file.local.isDownloadingActive) {
                        return Future.failedFuture("File is downloading");
                    }
                    if (!isPaused && !file.local.canBeDeleted) {
                        // Maybe the file is not exist, so we need to redownload it
                        return DataVerticle.fileRepository.getByUniqueId(file.remote.uniqueId)
                                .compose(fileRecord ->
                                        client.execute(new TdApi.AddFileToDownloads(fileId, fileRecord.chatId(), fileRecord.messageId(), 32)))
                                .mapEmpty();
                    }

                    return client.execute(new TdApi.ToggleDownloadIsPaused(fileId, isPaused));
                })
                .mapEmpty();
    }

    public Future<Void> removeFile(Integer fileId) {
        return client.execute(new TdApi.GetFile(fileId))
                .compose(file -> DataVerticle.fileRepository
                        .getByUniqueId(file.remote.uniqueId)
                        .map(fileRecord -> Tuple.tuple(file, fileRecord))
                )
                .compose(tuple2 -> {
                    TdApi.File file = tuple2.v1;
                    FileRecord fileRecord = tuple2.v2;
                    if (fileRecord == null) {
                        return Future.failedFuture("File not found");
                    }

                    if (fileRecord.isTransferStatus(FileRecord.TransferStatus.completed)) {
                        if (FileUtil.del(fileRecord.localPath())) {
                            log.debug("[%s] Remove file success: %s".formatted(this.getRootId(), fileRecord.localPath()));
                        }
                    }

                    if (file.local != null && StrUtil.isNotBlank(file.local.path)) {
                        return client.execute(new TdApi.DeleteFile(fileId))
                                .map(file);
                    }
                    return Future.succeededFuture(file);
                })
                .compose(file -> DataVerticle.fileRepository.deleteByUniqueId(file.remote.uniqueId).map(file))
                .onSuccess(file -> sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                        .put("fileId", fileId)
                        .put("uniqueId", file.remote.uniqueId)
                        .put("removed", true)
                )))
                .mapEmpty();
    }

    public Future<Void> toggleAutoDownload(Long chatId, JsonObject params) {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.autoDownload)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    if (settingAutoRecords.exists(this.telegramRecord.id(), chatId)) {
                        settingAutoRecords.remove(this.telegramRecord.id(), chatId);
                    } else {
                        SettingAutoRecords.Rule rule = null;
                        if (params != null && params.containsKey("rule")) {
                            rule = params.getJsonObject("rule").mapTo(SettingAutoRecords.Rule.class);
                            if (StrUtil.isBlank(rule.query) && CollUtil.isEmpty(rule.fileTypes) && rule.transferRule == null) {
                                rule = null;
                            }
                        }
                        settingAutoRecords.add(this.telegramRecord.id(), chatId, rule);
                    }
                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.autoDownload.name(), Json.encode(settingAutoRecords));
                })
                .onSuccess(r -> vertx.eventBus().publish(EventEnum.AUTO_DOWNLOAD_UPDATE.name(), r.value()))
                .mapEmpty();
    }

    public Future<JsonObject> getDownloadStatistics() {
        return Future.all(DataVerticle.fileRepository.getDownloadStatistics(this.telegramRecord.id()),
                client.execute(new TdApi.GetNetworkStatistics())
        ).map(r -> {
            JsonObject jsonObject = r.resultAt(0);
            TdApi.NetworkStatistics networkStatistics = r.resultAt(1);
            Tuple2<Long, Long> bytes = Arrays.stream(networkStatistics.entries)
                    .filter(e -> e instanceof TdApi.NetworkStatisticsEntryFile)
                    .map(e -> {
                        TdApi.NetworkStatisticsEntryFile entry = (TdApi.NetworkStatisticsEntryFile) e;
                        return Tuple.tuple(entry.sentBytes, entry.receivedBytes);
                    })
                    .reduce((a, b) -> Tuple.tuple(a.v1 + b.v1, a.v2 + b.v2))
                    .orElse(Tuple.tuple(0L, 0L));

            jsonObject.put("networkStatistics", JsonObject.of()
                    .put("sinceDate", networkStatistics.sinceDate)
                    .put("sentBytes", bytes.v1)
                    .put("receivedBytes", bytes.v2)
            );

            jsonObject.put("speedStats", avgSpeed.getSpeedStats());
            return jsonObject;
        });
    }

    public Future<JsonObject> getDownloadStatisticsByPhase(Integer timeRange) {
        // 1: 1 hour, 2: 1 day, 3: 1 week, 4: 1 month
        long endTime = System.currentTimeMillis();
        long startTime = switch (timeRange) {
            case 1 -> DateUtil.offsetHour(DateUtil.date(), -1).getTime();
            case 2 -> DateUtil.offsetDay(DateUtil.date(), -1).getTime();
            case 3 -> DateUtil.offsetWeek(DateUtil.date(), -1).getTime();
            case 4 -> DateUtil.offsetMonth(DateUtil.date(), -1).getTime();
            default -> throw new IllegalStateException("Unexpected value: " + timeRange);
        };

        return Future.all(
                        DataVerticle.statisticRepository.getRangeStatistics(StatisticRecord.Type.speed, this.telegramRecord.id(), startTime, endTime)
                                .map(statisticRecords -> convertRangedSpeedStats(statisticRecords, timeRange)),
                        DataVerticle.fileRepository.getCompletedRangeStatistics(this.telegramRecord.id(), startTime, endTime, timeRange)
                )
                .map(r -> new JsonObject()
                        .put("speedStats", r.resultAt(0))
                        .put("completedStats", r.resultAt(1))
                );
    }

    public Future<TdApi.Proxy> enableProxy(String proxyName) {
        if (StrUtil.isBlank(proxyName)) return Future.succeededFuture();
        return DataVerticle.settingRepository.<SettingProxyRecords>getByKey(SettingKey.proxys)
                .map(settingProxyRecords -> Optional.ofNullable(settingProxyRecords)
                        .flatMap(r -> r.getProxy(proxyName))
                        .orElseThrow(() -> new NoStackTraceException("Proxy %s not found".formatted(proxyName)))
                )
                .compose(proxy -> this.getTdProxy(proxy)
                        .map(r -> Tuple.tuple(proxy, r))
                )
                .compose(tuple -> {
                    SettingProxyRecords.Item proxy = tuple.v1;
                    TdApi.Proxy tdProxy = tuple.v2;
                    boolean edit = false;
                    if (tdProxy != null) {
                        if (tdProxy.isEnabled) {
                            return Future.succeededFuture(tdProxy);
                        }
                        edit = true;
                    }

                    TdApi.ProxyType proxyType;
                    if (Objects.equals(proxy.type, "http")) {
                        proxyType = new TdApi.ProxyTypeHttp(proxy.username, proxy.password, false);
                    } else if (Objects.equals(proxy.type, "socks5")) {
                        proxyType = new TdApi.ProxyTypeSocks5(proxy.username, proxy.password);
                    } else {
                        return Future.failedFuture("Unsupported proxy type: %s".formatted(proxy.type));
                    }
                    return edit ? client.execute(new TdApi.EditProxy(tdProxy.id, proxy.server, proxy.port, true, proxyType))
                            : client.execute(new TdApi.AddProxy(proxy.server, proxy.port, true, proxyType));
                })
                .compose(r -> {
                    if (this.telegramRecord != null) {
                        return DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(proxyName)).map(r);
                    } else {
                        return Future.succeededFuture(r);
                    }
                })
                .compose(r -> {
                    this.proxyName = proxyName;
                    return Future.succeededFuture(r);
                });
    }

    public Future<TdApi.Proxy> toggleProxy(JsonObject jsonObject) {
        String toggleProxyName = jsonObject.getString("proxyName");
        if (Objects.equals(toggleProxyName, this.proxyName)) {
            return Future.succeededFuture();
        }

        if (StrUtil.isBlank(toggleProxyName) && StrUtil.isNotBlank(this.proxyName)) {
            // disable proxy
            return client.execute(new TdApi.DisableProxy())
                    .compose(r -> DataVerticle.telegramRepository.update(this.telegramRecord.withProxy(null)))
                    .andThen(r -> {
                        this.proxyName = null;
                        this.telegramRecord = r.result();
                    })
                    .mapEmpty();
        } else {
            return this.enableProxy(toggleProxyName);
        }
    }

    public Future<TdApi.Proxy> getTdProxy(SettingProxyRecords.Item proxy) {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(proxy::equalsTdProxy)
                        .findFirst()
                        .orElse(null));
    }

    public Future<TdApi.Proxy> getTdProxy() {
        return client.execute(new TdApi.GetProxies())
                .map(proxies -> Stream.of(proxies.proxies)
                        .filter(p -> p.isEnabled)
                        .findFirst()
                        .orElse(null));
    }

    public Future<Double> ping() {
        return this.getTdProxy()
                .compose(proxy -> client.execute(new TdApi.PingProxy(proxy == null ? 0 : proxy.id)))
                .map(r -> r.seconds);
    }

    public Future<String> execute(String method, Object params) {
        String code = RandomUtil.randomString(10);
        log.trace("[%s] Execute code: %s method: %s, params: %s".formatted(getRootId(), code, method, params));
        return Future.future(promise -> {
            TdApi.Function<?> func = TdApiHelp.getFunction(method, params);
            if (func == null) {
                promise.fail("Unsupported method: " + method);
                return;
            }
            client.getNativeClient().send(func, object -> {
                log.debug("[%s] Execute: [%s] Receive result: %s".formatted(getRootId(), code, object));
                handleDefaultResult(object, code);
            });
            promise.complete(code);
        });
    }

    private void sendEvent(EventPayload payload) {
        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                JsonObject.of("telegramId", this.getId(), "payload", JsonObject.mapFrom(payload)));
    }

    private void sendFileStatusHttpEvent(TdApi.File file, JsonObject fileUpdated) {
        if (fileUpdated == null || fileUpdated.isEmpty()) return;
        sendEvent(EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                .put("fileId", file.id)
                .put("uniqueId", file.remote.uniqueId)
                .put("downloadStatus", fileUpdated.getString("downloadStatus"))
                .put("localPath", fileUpdated.getString("localPath"))
                .put("completionDate", fileUpdated.getLong("completionDate"))
                .put("downloadedSize", file.local.downloadedSize)
        ));
    }

    private void handleAuthorizationResult(TdApi.Object object) {
        switch (object.getConstructor()) {
            case TdApi.Error.CONSTRUCTOR:
                sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, object));
                break;
            case TdApi.Ok.CONSTRUCTOR:
                break;
            default:
                log.warn("[%s] Receive UpdateAuthorizationState with invalid authorization state%s".formatted(getRootId(), object));
        }
    }

    private void handleDefaultResult(TdApi.Object object, String code) {
        if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
            sendEvent(EventPayload.build(EventPayload.TYPE_ERROR, code, object));
        } else {
            sendEvent(EventPayload.build(EventPayload.TYPE_METHOD_RESULT, code, object));
        }
    }

    private void handleException(Throwable e) {
        log.error(e);
    }

    private void handleSaveAvgSpeed() {
        if (!authorized || telegramRecord == null) return;
        AvgSpeed.SpeedStats speedStats = avgSpeed.getSpeedStats();
        if (speedStats.avgSpeed() == 0
            && speedStats.minSpeed() == 0
            && speedStats.medianSpeed() == 0
            && speedStats.maxSpeed() == 0) {
            return;
        }
        JsonObject data = JsonObject.mapFrom(speedStats);
        data.remove("interval");
        DataVerticle.statisticRepository.create(new StatisticRecord(Convert.toStr(telegramRecord.id()),
                StatisticRecord.Type.speed,
                System.currentTimeMillis(),
                data.encode()));

        // Avoid speed not being updated for a long time
        avgSpeed.update(0, System.currentTimeMillis());
    }

    private Future<Void> initAvgSpeed() {
        return DataVerticle.settingRepository.<Integer>getByKey(SettingKey.avgSpeedInterval)
                .compose(interval -> {
                    if (Objects.equals(interval, avgSpeed.getSpeedStats().interval())) {
                        if (avgSpeedPersistenceTimerId == 0) {
                            avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, id -> handleSaveAvgSpeed());
                        }
                        return Future.succeededFuture();
                    }

                    avgSpeed = new AvgSpeed(interval);
                    if (avgSpeedPersistenceTimerId != 0) {
                        vertx.cancelTimer(avgSpeedPersistenceTimerId);
                    }
                    avgSpeedPersistenceTimerId = vertx.setPeriodic(interval * 1000, id -> handleSaveAvgSpeed());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.SETTING_UPDATE.address(SettingKey.avgSpeedInterval.name()), message -> {
            log.debug("Avg Speed Interval update: %s".formatted(message.body()));
            this.initAvgSpeed();
        });

        return Future.succeededFuture();
    }

    private void onAuthorizationStateUpdated(TdApi.AuthorizationState authorizationState) {
        log.debug("[%s] Receive authorization state update: %s".formatted(getRootId(), authorizationState));
        this.lastAuthorizationState = authorizationState;
        switch (authorizationState.getConstructor()) {
            case TdApi.AuthorizationStateWaitTdlibParameters.CONSTRUCTOR:
                TdApi.SetTdlibParameters request = new TdApi.SetTdlibParameters();
                request.databaseDirectory = this.rootPath;
                request.useMessageDatabase = true;
                request.useFileDatabase = true;
                request.useChatInfoDatabase = true;
                request.useSecretChats = true;
                request.apiId = Config.TELEGRAM_API_ID;
                request.apiHash = Config.TELEGRAM_API_HASH;
                request.systemLanguageCode = "en";
                request.deviceModel = "Telegram Files";
                request.applicationVersion = Start.VERSION;
                log.trace("[%s] Send SetTdlibParameters: %s".formatted(getRootId(), request));

                client.execute(request).onSuccess(this::handleAuthorizationResult);
                break;
            case TdApi.AuthorizationStateWaitPhoneNumber.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitOtherDeviceConfirmation.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailAddress.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitEmailCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitCode.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitRegistration.CONSTRUCTOR:
            case TdApi.AuthorizationStateWaitPassword.CONSTRUCTOR:
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                break;
            case TdApi.AuthorizationStateReady.CONSTRUCTOR:
                authorized = true;
                if (telegramRecord == null) {
                    client.execute(new TdApi.GetMe())
                            .compose(user ->
                                    DataVerticle.telegramRepository.create(new TelegramRecord(user.id, user.firstName, this.rootPath, this.proxyName))
                            )
                            .onSuccess(o -> {
                                telegramRecord = o;
                                log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                            })
                            .onFailure(e -> log.error("[%s] Authorization Ready, but failed to create telegram record: %s".formatted(getRootId(), e.getMessage())));
                } else {
                    log.info("[%s] %s Authorization Ready".formatted(getRootId(), this.telegramRecord.firstName()));
                }
                sendEvent(EventPayload.build(EventPayload.TYPE_AUTHORIZATION, authorizationState));
                telegramChats.loadMainChatList();
                telegramChats.loadArchivedChatList();
                break;
            case TdApi.AuthorizationStateLoggingOut.CONSTRUCTOR:
                break;
            case TdApi.AuthorizationStateClosing.CONSTRUCTOR:
                break;
            case TdApi.AuthorizationStateClosed.CONSTRUCTOR:
                if (needDelete) {
                    File root = FileUtil.file(this.rootPath);
                    if (root.exists()) {
                        FileUtil.del(root);
                    }
                    log.info("[%s] Telegram account deleted".formatted(this.getRootId()));
                }
                break;
            default:
                log.warn("[%s] Unsupported authorization state received:%s".formatted(this.getRootId(), authorizationState));
        }
    }

    private void onFileUpdated(TdApi.UpdateFile updateFile) {
        log.trace("[%s] Receive file update: %s".formatted(getRootId(), updateFile));
        TdApi.File file = updateFile.file;
        if (file != null) {
            String localPath = null;
            Long completionDate = null;
            if (file.local != null && file.local.isDownloadingCompleted) {
                localPath = file.local.path;
                completionDate = System.currentTimeMillis();
            }
            FileRecord.DownloadStatus downloadStatus = TdApiHelp.getDownloadStatus(file);
            DataVerticle.fileRepository.updateDownloadStatus(file.id,
                            file.remote.uniqueId,
                            localPath,
                            downloadStatus,
                            completionDate)
                    .onSuccess(r -> sendFileStatusHttpEvent(file, r));

            if (completionDate != null || lastFileEventTime == 0 || System.currentTimeMillis() - lastFileEventTime > 1000) {
                sendEvent(EventPayload.build(EventPayload.TYPE_FILE, updateFile));
                lastFileEventTime = System.currentTimeMillis();
            }
        }
    }

    private void onFileDownloadsUpdated(TdApi.UpdateFileDownloads updateFileDownloads) {
        log.trace("[%s] Receive file downloads update: %s".formatted(getRootId(), updateFileDownloads));
        avgSpeed.update(updateFileDownloads.downloadedSize, System.currentTimeMillis());
        if (lastFileDownloadEventTime == 0 || System.currentTimeMillis() - lastFileDownloadEventTime > 1000) {
            sendEvent(EventPayload.build(EventPayload.TYPE_FILE_DOWNLOAD, updateFileDownloads));
            lastFileDownloadEventTime = System.currentTimeMillis();
        }
    }

    private void onMessageReceived(TdApi.Message message) {
        log.trace("[%s] Receive message: %s".formatted(getRootId(), message));
        vertx.eventBus().publish(EventEnum.MESSAGE_RECEIVED.address(), JsonObject.of()
                .put("telegramId", telegramRecord.id())
                .put("chatId", message.chatId)
                .put("messageId", message.id)
        );
    }

    //<-----------------------------convert---------------------------------->

    private Future<JsonArray> convertChat(List<TdApi.Chat> chats) {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.autoDownload)
                .map(settingAutoRecords -> settingAutoRecords == null ? new HashMap<Long, SettingAutoRecords.Item>()
                        : settingAutoRecords.getItems(this.telegramRecord.id()))
                .map(enableAutoChats -> new JsonArray(chats.stream()
                        .map(chat -> {
                            SettingAutoRecords.Item autoItem = enableAutoChats.get(chat.id);
                            return new JsonObject()
                                    .put("id", Convert.toStr(chat.id))
                                    .put("name", chat.id == this.telegramRecord.id() ? "Saved Messages" : chat.title)
                                    .put("type", TdApiHelp.getChatType(chat.type))
                                    .put("avatar", Base64.encode((byte[]) BeanUtil.getProperty(chat, "photo.minithumbnail.data")))
                                    .put("unreadCount", chat.unreadCount)
                                    .put("lastMessage", "")
                                    .put("lastMessageTime", "")
                                    .put("autoEnabled", autoItem != null)
                                    .put("autoRule", autoItem == null ? null : autoItem.rule);
                        })
                        .toList()
                ));
    }

    private Future<JsonObject> convertFiles(Tuple2<TdApi.FoundChatMessages, Map<String, FileRecord>> tuple) {
        TdApi.FoundChatMessages foundChatMessages = tuple.v1;
        Map<String, FileRecord> fileRecords = tuple.v2;

        return DataVerticle.settingRepository.<Boolean>getByKey(SettingKey.uniqueOnly)
                .map(uniqueOnly -> {
                    if (!uniqueOnly) {
                        return Arrays.asList(foundChatMessages.messages);
                    }
                    return TdApiHelp.filterUniqueMessages(Arrays.asList(foundChatMessages.messages));
                })
                .map(messages -> {
                    List<JsonObject> fileObjects = messages.stream()
                            .filter(message -> TdApiHelp.FILE_CONTENT_CONSTRUCTORS.contains(message.content.getConstructor()))
                            .map(message -> {
                                //TODO Processing of the same file under different accounts

                                return this.withSource(fileRecords.get(TdApiHelp.getFileUniqueId(message)), message);
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

    private JsonObject withSource(FileRecord fileRecord, TdApi.Message message) {
        TdApiHelp.FileHandler<? extends TdApi.MessageContent> fileHandler = TdApiHelp.getFileHandler(message)
                .orElse(null);
        if (fileHandler == null) {
            return null;
        }

        FileRecord source = fileHandler.convertFileRecord(telegramRecord.id());
        if (fileRecord == null) {
            fileRecord = source;
        } else {
            fileRecord = fileRecord.withSourceField(source.id(), source.downloadedSize());
        }

        JsonObject fileObject = JsonObject.mapFrom(fileRecord);
        fileObject.put("formatDate", DateUtil.date(fileObject.getLong("date") * 1000).toString());
        fileObject.put("extra", fileHandler.getExtraInfo());
        return fileObject;
    }

    private List<JsonObject> convertRangedSpeedStats(List<StatisticRecord> statisticRecords, int timeRange) {
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
                case 2 ->
                        DateUtil.date(timestamp).setField(DateField.MINUTE, 0).toString(DatePattern.NORM_DATETIME_MINUTE_FORMAT);
                case 3, 4 ->
                        DateUtil.date(timestamp).setField(DateField.MINUTE, 0).toString(DatePattern.NORM_DATE_FORMAT);
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
}
