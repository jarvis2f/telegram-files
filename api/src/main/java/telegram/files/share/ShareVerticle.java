package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.DataVerticle;
import telegram.files.EventEnum;
import telegram.files.EventPayload;
import telegram.files.repository.FileRecord;

import java.time.Duration;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

public final class ShareVerticle extends AbstractVerticle {

    private static final Log log = LogFactory.get();

    private static final long RECONCILE_INTERVAL_MILLIS = Duration.ofMinutes(1).toMillis();

    private final ShareConfiguration configuration;

    private final ShareService service;

    private final NodeIdentityService identityService;

    private final NodeHeartbeatService heartbeatService;

    private final ResourcePublishingService resourcePublishingService;

    private final NodeTaskPoller taskPoller;

    private final TorrentReconciler torrentReconciler;

    private final TorrentStatisticsReporter torrentStatisticsReporter;

    private MessageConsumer<JsonObject> fileReadyConsumer;

    private MessageConsumer<JsonObject> telegramEventConsumer;

    private long reconcileTimerId = -1;

    private final List<MessageConsumer<JsonObject>> identityConsumers = new ArrayList<>();

    public ShareVerticle(ShareConfiguration configuration, ShareService service) {
        this(configuration, service, null, null, null, null, null, null);
    }

    public ShareVerticle(
            ShareConfiguration configuration,
            ShareService service,
            NodeIdentityService identityService,
            NodeHeartbeatService heartbeatService,
            ResourcePublishingService resourcePublishingService,
            NodeTaskPoller taskPoller,
            TorrentReconciler torrentReconciler,
            TorrentStatisticsReporter torrentStatisticsReporter
    ) {
        this.configuration = configuration;
        this.service = service;
        this.identityService = identityService;
        this.heartbeatService = heartbeatService;
        this.resourcePublishingService = resourcePublishingService;
        this.taskPoller = taskPoller;
        this.torrentReconciler = torrentReconciler;
        this.torrentStatisticsReporter = torrentStatisticsReporter;
    }

    @Override
    public void start(Promise<Void> startPromise) {
        if (!configuration.enabled()) {
            log.info("Share module is disabled");
            startPromise.complete();
            return;
        }

        recover()
                .onSuccess(_ -> {
                    registerConsumers();
                    registerIdentityConsumers();
                    if (heartbeatService != null) {
                        heartbeatService.start();
                    }
                    reconcileTimerId = vertx.setPeriodic(
                            RECONCILE_INTERVAL_MILLIS,
                            _ -> recover().onFailure(failure ->
                                    log.error("Share reconciliation failed: {}", failure.getMessage())
                            )
                    );
                    log.info("Share module started");
                    startPromise.complete();
                })
                .onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        if (reconcileTimerId >= 0) {
            vertx.cancelTimer(reconcileTimerId);
            reconcileTimerId = -1;
        }
        if (heartbeatService != null) {
            heartbeatService.stop();
        }
        if (identityService != null) {
            identityService.close();
        }
        Future<Void> first = fileReadyConsumer == null
                ? Future.succeededFuture()
                : fileReadyConsumer.unregister();
        Future<Void> second = telegramEventConsumer == null
                ? Future.succeededFuture()
                : telegramEventConsumer.unregister();
        List<Future<Void>> unregister = new ArrayList<>();
        unregister.add(first);
        unregister.add(second);
        identityConsumers.forEach(consumer -> unregister.add(consumer.unregister()));
        Future.all(unregister)
                .onSuccess(_ -> stopPromise.complete())
                .onFailure(stopPromise::fail);
    }

    private void registerConsumers() {
        fileReadyConsumer = vertx.eventBus().consumer(EventEnum.FILE_READY_FOR_SHARE.address());
        fileReadyConsumer.handler(message -> {
            try {
                FileReadyForShare event = FileReadyForShare.fromJson(message.body());
                service.handleFileReady(event).onComplete(result -> {
                    if (result.succeeded()) {
                        message.reply(JsonObject.of("accepted", true));
                    } else {
                        message.fail(500, "Share job could not be persisted");
                    }
                });
            } catch (IllegalArgumentException exception) {
                message.fail(400, exception.getMessage());
            }
        });

        telegramEventConsumer = vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address());
        telegramEventConsumer.handler(message -> publishStableFileReady(message.body()));
    }

    private void registerIdentityConsumers() {
        if (identityService == null) {
            return;
        }
        registerIdentityConsumer(EventEnum.SHARE_DEVICE_AUTHORIZE, body ->
                identityService.authorize(body == null ? null : body.getString("nodeName")));
        registerIdentityConsumer(EventEnum.SHARE_DEVICE_STATUS, _ -> identityService.status());
        registerIdentityConsumer(EventEnum.SHARE_DEVICE_CANCEL, _ ->
                identityService.cancel().map(new JsonObject().put("status", "cancelled")));
        registerIdentityConsumer(EventEnum.SHARE_NODE_UNBIND, _ ->
                identityService.unbind().map(new JsonObject().put("status", "UNBOUND")));
        registerIdentityConsumer(EventEnum.SHARE_NODE_RENAME, body ->
                identityService.rename(body == null ? null : body.getString("nodeName"))
                        .compose(_ -> identityService.status()));
        if (resourcePublishingService != null) {
            registerIdentityConsumer(EventEnum.SHARE_PUBLICATION_POLICY, _ ->
                    resourcePublishingService.publicationPolicy());
            registerIdentityConsumer(EventEnum.SHARE_RESOURCE_PUBLISH, body ->
                    resourcePublishingService.publish(body).onSuccess(this::publishSharePatch));
            registerIdentityConsumer(EventEnum.SHARE_RESOURCE_LIST, resourcePublishingService::list);
            registerIdentityConsumer(EventEnum.SHARE_RESOURCE_UPDATE, body ->
                    resourcePublishingService.update(
                            body == null ? null : body.getString("sourceId"),
                            withoutSourceId(body)
                    ).onSuccess(this::publishSharePatch));
            registerIdentityConsumer(EventEnum.SHARE_RESOURCE_REVOKE, body ->
                    resourcePublishingService.revoke(body == null ? null : body.getString("sourceId"))
                            .onSuccess(this::publishSharePatch));
        }
    }

    private void publishSharePatch(JsonObject source) {
        if (source == null) {
            return;
        }
        String fileUniqueId = source.getString("fileUniqueId");
        if (fileUniqueId == null || fileUniqueId.isBlank()) {
            return;
        }
        DataVerticle.fileRepository.getByUniqueId(fileUniqueId)
                .onSuccess(file -> {
                    if (file == null) {
                        return;
                    }
                    JsonObject patch = new JsonObject()
                            .put("fileId", file.id())
                            .put("uniqueId", file.uniqueId())
                            .put("telegramId", Long.toString(file.telegramId()))
                            .put("shareStatus", source.getString("status"))
                            .put("sharedByMe", "PUBLISHED".equals(source.getString("status")))
                            .put("sharedSourceId", source.getString("sourceId"))
                            .put("sharedResourceId", source.getString("resourceId"))
                            .put("shareTitle", source.getString("title"))
                            .put("shareDescription", source.getString("description"))
                            .put("shareTags", source.getJsonArray("tags", new JsonArray()))
                            .put("shareCategory", source.getString("category"))
                            .put("shareAccessScope", source.getString("accessScope"))
                            .put("sharePublicMessageUrl", source.getString("publicMessageUrl"))
                            .put("shareErrorCode", source.getString("lastErrorCode"));
                    vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(), new JsonObject()
                            .put("telegramId", Long.toString(file.telegramId()))
                            .put("payload", JsonObject.mapFrom(EventPayload.build(
                                    EventPayload.TYPE_FILE_STATUS,
                                    patch
                            ))));
                })
                .onFailure(failure -> log.warn(
                        "Failed to publish share status patch: {}",
                        failure.getMessage()
                ));
    }

    private void registerIdentityConsumer(
            EventEnum event,
            java.util.function.Function<JsonObject, Future<JsonObject>> handler
    ) {
        MessageConsumer<JsonObject> consumer = vertx.eventBus().consumer(event.address());
        consumer.handler(message -> {
            try {
                handler.apply(message.body()).onComplete(result -> {
                    if (result.succeeded()) {
                        message.reply(result.result());
                    } else {
                        log.warn("Share identity command {} failed: {}", event, result.cause().getMessage());
                        message.fail(500, "Share identity command failed");
                    }
                });
            } catch (IllegalArgumentException | IllegalStateException exception) {
                message.fail(400, exception.getMessage());
            }
        });
        identityConsumers.add(consumer);
    }

    private Future<Void> recover() {
        Future<Void> jobs = service.recoverPendingJobs();
        Future<Void> sources = resourcePublishingService == null
                ? Future.succeededFuture()
                : resourcePublishingService.recoverPending();
        Future<Void> torrents = torrentReconciler == null
                ? Future.succeededFuture()
                : torrentReconciler.runOnce();
        return Future.all(jobs, sources, torrents).mapEmpty();
    }

    private Future<Void> runStatistics() {
        return torrentStatisticsReporter == null
                ? Future.succeededFuture()
                : torrentStatisticsReporter.runOnce();
    }

    private static JsonObject withoutSourceId(JsonObject body) {
        if (body == null) {
            return new JsonObject();
        }
        JsonObject copy = body.copy();
        copy.remove("sourceId");
        return copy;
    }

    private void publishStableFileReady(JsonObject envelope) {
        if (envelope == null) {
            return;
        }
        JsonObject rawPayload = envelope.getJsonObject("payload");
        if (rawPayload == null) {
            return;
        }
        EventPayload payload = rawPayload.mapTo(EventPayload.class);
        if (payload.type() != EventPayload.TYPE_FILE_STATUS
            || !(payload.data() instanceof Map<?, ?> data)
            || !FileRecord.DownloadStatus.completed.name().equals(data.get("downloadStatus"))
            || !(data.get("uniqueId") instanceof String uniqueId)) {
            return;
        }
        DataVerticle.fileRepository.getByUniqueId(uniqueId)
                .onSuccess(record -> {
                    if (record == null || record.id() <= 0 || record.telegramId() <= 0
                        || "thumbnail".equals(record.type())
                        || !record.isDownloadStatus(FileRecord.DownloadStatus.completed)
                        || record.localPath() == null || record.localPath().isBlank()) {
                        return;
                    }
                    long recordVersion = record.completionDate() == null
                            ? 0
                            : record.completionDate();
                    vertx.eventBus().publish(
                            EventEnum.FILE_READY_FOR_SHARE.address(),
                            new FileReadyForShare(
                                    record.id(),
                                    recordVersion,
                                    record.telegramId()
                            ).toJson()
                    );
                })
                .onFailure(failure -> log.error(
                        "Failed to load stable file record for sharing: {}",
                        failure.getMessage()
                ));
    }
}
