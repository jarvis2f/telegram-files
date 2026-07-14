package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TorrentControlExecutor {
    private final TorrentRepository repository;

    private final TorrentClient client;

    private final Clock clock;

    private final NodeIdentityService identityService;

    private final Vertx vertx;

    private final TorrentConfiguration configuration;

    private final LocalTorrentMetadataStore metadataStore;

    private final V1TorrentService torrentService;

    public TorrentControlExecutor(Vertx vertx, TorrentConfiguration configuration,
                                  TorrentRepository repository, TorrentClient client,
                                  NodeIdentityService identityService,
                                  LocalTorrentMetadataStore metadataStore,
                                  V1TorrentService torrentService) {
        this(repository, client, identityService, vertx, configuration, metadataStore, torrentService, Clock.systemUTC());
    }

    public TorrentControlExecutor(Vertx vertx, TorrentConfiguration configuration,
                                  TorrentRepository repository, TorrentClient client,
                                  NodeIdentityService identityService) {
        this(repository, client, identityService, vertx, configuration, null, null, Clock.systemUTC());
    }

    TorrentControlExecutor(TorrentRepository repository, TorrentClient client, Clock clock) {
        this(repository, client, null, null, null, null, null, clock);
    }

    private TorrentControlExecutor(TorrentRepository repository, TorrentClient client,
                                   NodeIdentityService identityService, Vertx vertx,
                                   TorrentConfiguration configuration,
                                   LocalTorrentMetadataStore metadataStore,
                                   V1TorrentService torrentService, Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.client = Objects.requireNonNull(client, "client");
        this.identityService = identityService;
        this.vertx = vertx;
        this.configuration = configuration;
        this.metadataStore = metadataStore;
        this.torrentService = torrentService;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<JsonObject> execute(TorrentControlTask task) {
        if ("ROTATE_TRACKER_CREDENTIAL_V1".equals(task.controlType())) {
            return rotateTrackerCredential(task);
        }
        return repository.getByInfoHash(task.infoHashV1()).compose(record -> {
            if (record == null || !record.resourceId().equals(task.resourceId())) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "FORBIDDEN", false, "Torrent is not owned by this node"
                ));
            }
            return execute(record, task.controlType(), task.uploadLimitBytesPerSecond());
        });
    }

    public Future<JsonObject> executeLocal(
            String resourceId,
            String controlType,
            long uploadLimitBytesPerSecond
    ) {
        if (resourceId == null || resourceId.isBlank() || uploadLimitBytesPerSecond < 0) {
            return Future.failedFuture(new IllegalArgumentException("Local Torrent control is invalid"));
        }
        return repository.getByResourceId(resourceId).compose(record -> {
            if (record == null) {
                return Future.failedFuture(new IllegalArgumentException("Torrent was not found"));
            }
            return execute(record, controlType, uploadLimitBytesPerSecond);
        });
    }

    private Future<JsonObject> execute(
            TorrentRecord record,
            String controlType,
            long uploadLimitBytesPerSecond
    ) {
            Future<Void> operation = switch (controlType) {
                case "PAUSE_V1" -> client.pause(record.infoHashV1());
                case "RESUME_V1" -> resumeOrRestore(record);
                case "CANCEL_V1" -> client.delete(record.infoHashV1());
                case "RECHECK_V1" -> recheck(record.infoHashV1());
                case "SET_UPLOAD_LIMIT_V1" -> client.setUploadLimit(
                        record.infoHashV1(), uploadLimitBytesPerSecond
                );
                default -> Future.failedFuture(new BootstrapExecutionException(
                        "UNSUPPORTED_TASK_TYPE", false, "Torrent control is unsupported"
                ));
            };
            return operation.compose(_ -> persistState(record, controlType))
                    .map(_ -> new JsonObject()
                            .put("resourceId", record.resourceId())
                            .put("infoHashV1", record.infoHashV1())
                            .put("controlType", controlType)
                            .put("status", targetState(record, controlType)));
    }

    private Future<Void> resumeOrRestore(TorrentRecord record) {
        return client.get(record.infoHashV1())
                .compose(_ -> client.resume(record.infoHashV1()))
                .recover(failure -> failure instanceof QbittorrentClient.TorrentNotFoundException
                        ? restore(record)
                        : Future.failedFuture(failure));
    }

    private Future<Void> restore(TorrentRecord record) {
        if (identityService == null || metadataStore == null || torrentService == null) {
            return Future.failedFuture(new IllegalStateException("Torrent recovery is unavailable"));
        }
        if (record.trackerBaseUrl().isBlank() || record.savePath().isBlank()) {
            return Future.failedFuture(new IllegalStateException("Legacy Torrent record lacks recovery metadata"));
        }
        return identityService.access().compose(access -> {
            if (access.trackerCredential() == null) {
                return Future.failedFuture(new IllegalStateException("Tracker credential is unavailable"));
            }
            return metadataStore.readRelative(record.torrentRelativePath())
                    .compose(canonical -> {
                        V1TorrentService.TorrentMetadata metadata = torrentService.parseCanonical(
                                canonical, record.infoHashV1()
                        );
                        byte[] announced = torrentService.withTracker(
                                metadata, java.net.URI.create(record.trackerBaseUrl()), access.trackerCredential()
                        );
                        return client.addOrConfirm(new TorrentClient.AddRequest(
                                announced,
                                record.savePath(),
                                "telegram-files",
                                List.of("telegram-files", "resource-" + record.resourceId()),
                                false
                        ), record.infoHashV1());
                    })
                    .compose(_ -> client.resume(record.infoHashV1()));
        });
    }

    private Future<JsonObject> rotateTrackerCredential(TorrentControlTask task) {
        if (identityService == null) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "UNSUPPORTED_TASK_TYPE", false, "Credential rotation is unavailable"
            ));
        }
        return repository.getByInfoHash(task.infoHashV1()).compose(anchor -> {
            if (anchor == null || !anchor.resourceId().equals(task.resourceId())) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "FORBIDDEN", false, "Rotation anchor is not owned by this node"
                ));
            }
            return identityService.requestTrackerCredentialRotation(task.taskId()).compose(credential ->
                    repository.listActive(10_000).compose(records -> {
                        List<Future<Void>> updates = new ArrayList<>();
                        for (TorrentRecord record : records) {
                            updates.add(client.replaceTrackerByBase(
                                    record.infoHashV1(), task.trackerBaseUrl(), credential
                            ).compose(_ -> repository.save(withTrackerBaseUrl(
                                    record, task.trackerBaseUrl()
                            )).mapEmpty()));
                        }
                        Future<Void> changed = updates.isEmpty()
                                ? Future.succeededFuture() : Future.all(updates).mapEmpty();
                        return changed.compose(_ -> identityService.replaceTrackerCredential(credential));
                    })
            ).map(_ -> new JsonObject()
                    .put("resourceId", anchor.resourceId())
                    .put("infoHashV1", anchor.infoHashV1())
                    .put("controlType", task.controlType())
                    .put("status", anchor.status()));
        });
    }

    private static TorrentRecord withTrackerBaseUrl(TorrentRecord record, String trackerBaseUrl) {
        return new TorrentRecord(
                record.id(), record.resourceId(), record.contentSha256(), record.infoHashV1(),
                record.torrentRelativePath(), record.viewRelativePath(), record.fileName(),
                record.fileSize(), record.mimeType(), record.telegramFileUniqueId(),
                record.acquiredVia(), record.completedAt(), record.status(),
                record.progressPermille(), record.downloadedBytes(), record.uploadedBytes(),
                record.downloadSpeedBytesPerSecond(), record.uploadSpeedBytesPerSecond(),
                record.connectedPeers(),
                record.savePath(), trackerBaseUrl, record.seedingSeconds(),
                record.lastSynchronizedAt(), record.createdAt(), record.updatedAt(), record.version()
        );
    }

    private Future<TorrentRecord> persistState(TorrentRecord record, String control) {
        long now = clock.millis();
        return repository.save(new TorrentRecord(
                record.id(), record.resourceId(), record.contentSha256(), record.infoHashV1(),
                record.torrentRelativePath(), record.viewRelativePath(), record.fileName(),
                record.fileSize(), record.mimeType(), record.telegramFileUniqueId(),
                record.acquiredVia(), record.completedAt(), targetState(record, control),
                record.progressPermille(), record.downloadedBytes(), record.uploadedBytes(),
                0, 0, record.connectedPeers(), record.savePath(), record.trackerBaseUrl(),
                record.seedingSeconds(), now, record.createdAt(), now, record.version()
        ));
    }

    private Future<Void> recheck(String infoHashV1) {
        if (vertx == null || configuration == null) {
            return client.recheck(infoHashV1);
        }
        long startedAt = clock.millis();
        return client.recheck(infoHashV1)
                .compose(_ -> delay())
                .compose(_ -> waitForRecheck(infoHashV1, startedAt));
    }

    private Future<Void> waitForRecheck(String infoHashV1, long startedAt) {
        if (clock.millis() - startedAt > configuration.operationTimeout().toMillis()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "INTERNAL_RETRYABLE", true, "qBittorrent recheck timed out"
            ));
        }
        return client.get(infoHashV1).compose(status -> {
            if (status.failed()) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "HASH_MISMATCH", false, "qBittorrent recheck failed"
                ));
            }
            if (status.checking()) {
                return delay().compose(_ -> waitForRecheck(infoHashV1, startedAt));
            }
            if (status.progress() < 1) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "HASH_MISMATCH", false, "qBittorrent recheck found incomplete content"
                ));
            }
            return Future.succeededFuture();
        });
    }

    private Future<Void> delay() {
        return Future.future(promise -> vertx.setTimer(
                configuration.pollInterval().toMillis(), _ -> promise.complete()
        ));
    }

    private static String targetState(TorrentRecord record, String control) {
        return switch (control) {
            case "PAUSE_V1" -> "PAUSED";
            case "RESUME_V1" -> record.progressPermille() == 1_000 ? "SEEDING" : "DOWNLOADING";
            case "CANCEL_V1" -> "STOPPED";
            case "RECHECK_V1" -> record.progressPermille() == 1_000 ? "SEEDING" : "DOWNLOADING";
            default -> record.status();
        };
    }
}
