package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;
import telegram.files.share.TorrentClient.AddRequest;
import telegram.files.share.TorrentClient.TorrentStatus;
import telegram.files.share.V1TorrentService.TorrentMetadata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class TorrentDownloadExecutor {

    private final Vertx vertx;

    private final TorrentConfiguration configuration;

    private final NodeIdentityService identityService;

    private final V1TorrentService torrentService;

    private final TorrentViewStore viewStore;

    private final LocalTorrentMetadataStore metadataStore;

    private final TorrentClient torrentClient;

    private final ContentHashService hashService;

    private final TorrentRepository torrentRepository;

    private final Clock clock;

    public TorrentDownloadExecutor(
            Vertx vertx,
            TorrentConfiguration configuration,
            NodeIdentityService identityService,
            V1TorrentService torrentService,
            TorrentViewStore viewStore,
            LocalTorrentMetadataStore metadataStore,
            TorrentClient torrentClient,
            ContentHashService hashService,
            TorrentRepository torrentRepository
    ) {
        this(
                vertx, configuration, identityService, torrentService, viewStore, metadataStore,
                torrentClient, hashService, torrentRepository,
                Clock.systemUTC()
        );
    }

    TorrentDownloadExecutor(
            Vertx vertx,
            TorrentConfiguration configuration,
            NodeIdentityService identityService,
            V1TorrentService torrentService,
            TorrentViewStore viewStore,
            LocalTorrentMetadataStore metadataStore,
            TorrentClient torrentClient,
            ContentHashService hashService,
            TorrentRepository torrentRepository,
            Clock clock
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.torrentService = Objects.requireNonNull(torrentService, "torrentService");
        this.viewStore = Objects.requireNonNull(viewStore, "viewStore");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
        this.torrentClient = Objects.requireNonNull(torrentClient, "torrentClient");
        this.hashService = Objects.requireNonNull(hashService, "hashService");
        this.torrentRepository = Objects.requireNonNull(torrentRepository, "torrentRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<JsonObject> execute(
            TorrentDownloadTask task,
            TelegramBootstrapExecutor.ProgressReporter progress
    ) {
        TorrentMetadata metadata;
        try {
            metadata = torrentService.parseCanonical(
                    task.torrentBase64(), task.infoHashV1(), task.fileName(), task.fileSize()
            );
        } catch (RuntimeException exception) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "TORRENT_INVALID", false, "Assigned Torrent metadata is invalid", exception
            ));
        }
        long startedAt = clock.millis();
        return identityService.access()
                .compose(access -> {
                    if (access.trackerCredential() == null) {
                        return Future.failedFuture(new BootstrapExecutionException(
                                "TORRENT_INVALID", false, "Node Tracker credential is unavailable"
                        ));
                    }
                    byte[] announced = torrentService.withTracker(
                            metadata, task.trackerBaseUri(), access.trackerCredential()
                    );
                    return viewStore.prepareDownloadView(metadata.infoHashV1(), metadata.name())
                            .map(view -> new DownloadContext(metadata, announced, view));
                })
                .compose(context -> metadataStore.save(
                                metadata.infoHashV1(), metadata.canonicalBytes()
                        )
                        .map(path -> new DownloadContext(
                                context.metadata(), context.announced(), context.view(), path
                        )))
                .compose(context -> progress.report("DOWNLOADING_TORRENT", 1, 0, task.fileSize())
                        .compose(_ -> torrentClient.addOrConfirm(new AddRequest(
                                context.announced(),
                                configuration.qbittorrentPath(context.view().directory()),
                                "telegram-files",
                                List.of("telegram-files", "resource-" + task.resourceId()),
                                false
                        ), task.infoHashV1()))
                        .map(context))
                .compose(context -> waitForReady(
                        task.infoHashV1(), task.fileSize(), startedAt, progress
                )
                        .compose(_ -> torrentClient.pause(task.infoHashV1()))
                        .compose(_ -> torrentClient.recheck(task.infoHashV1()))
                        .compose(_ -> delay())
                        .compose(_ -> torrentClient.resume(task.infoHashV1()))
                        .compose(_ -> waitForVerifiedReady(
                                task.infoHashV1(), task.fileSize(), startedAt, progress,
                                context.view().content()
                        ))
                        .map(status -> new Downloaded(context, status)))
                .compose(downloaded -> progress.report(
                                "HASHING", 96, task.fileSize(), task.fileSize()
                        )
                        .compose(_ -> hashService.sha256(downloaded.context().view().content()))
                        .compose(actual -> verifyHash(task.contentSha256(), actual)
                                ? Future.succeededFuture(new Hashed(downloaded, actual))
                                : Future.failedFuture(new BootstrapExecutionException(
                                "HASH_MISMATCH", false, "Downloaded Torrent content hash does not match"
                        ))))
                .compose(hashed -> {
                    Path completedFile = hashed.downloaded().context().view().content();
                    String relative = relativeView(completedFile);
                    long now = clock.millis();
                    return torrentRepository.save(new TorrentRecord(
                                    UUID.randomUUID().toString(), task.resourceId(), task.contentSha256(),
                                    task.infoHashV1(),
                                    metadataStore.relative(hashed.downloaded().context().metadataPath()),
                                    relative,
                                    task.fileName(), task.fileSize(), task.mimeType(),
                                    task.telegramFileUniqueId(), "SEED", now,
                                    "SEEDING", 1000,
                                    hashed.downloaded().status().downloadedBytes(),
                                    hashed.downloaded().status().uploadedBytes(),
                                    hashed.downloaded().status().downloadSpeedBytesPerSecond(),
                                    hashed.downloaded().status().uploadSpeedBytesPerSecond(),
                                    hashed.downloaded().status().connectedPeers(),
                                    configuration.qbittorrentPath(
                                            hashed.downloaded().context().view().directory()
                                    ),
                                    task.trackerBaseUri().toString(),
                                    0, now, now, now, 0
                            ))
                            .compose(_ -> torrentClient.resume(task.infoHashV1()))
                            .compose(_ -> progress.report(
                                    "SEEDING", 100, task.fileSize(), task.fileSize()
                            ))
                            .map(new JsonObject()
                                    .put("sha256", task.contentSha256())
                                    .put("fileSize", Long.toString(task.fileSize()))
                                    .put("infoHashV1", task.infoHashV1()));
                })
                .recover(failure -> Future.failedFuture(classify(failure)));
    }

    private Future<TorrentStatus> waitForReady(
            String infoHashV1,
            long totalBytes,
            long startedAt,
            TelegramBootstrapExecutor.ProgressReporter progress
    ) {
        if (clock.millis() - startedAt > configuration.operationTimeout().toMillis()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "INTERNAL_RETRYABLE", true, "qBittorrent download timed out"
            ));
        }
        return torrentClient.get(infoHashV1).compose(status -> {
            if (status.failed() || !status.privateTorrent()) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "TORRENT_INVALID", false, "qBittorrent rejected private Torrent state"
                ));
            }
            long completed = Math.min(totalBytes, Math.round(totalBytes * status.progress()));
            int percent = Math.max(1, Math.min(95, (int) Math.floor(status.progress() * 95)));
            return progress.report("DOWNLOADING_TORRENT", percent, completed, totalBytes)
                    .compose(_ -> status.progress() >= 1 && !status.checking()
                            ? Future.succeededFuture(status)
                            : delay().compose(_ignored -> waitForReady(
                            infoHashV1, totalBytes, startedAt, progress
                    )));
        });
    }

    private Future<TorrentStatus> waitForVerifiedReady(
            String infoHashV1,
            long totalBytes,
            long startedAt,
            TelegramBootstrapExecutor.ProgressReporter progress,
            Path content
    ) {
        if (clock.millis() - startedAt > configuration.operationTimeout().toMillis()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "INTERNAL_RETRYABLE", true, "qBittorrent verified download timed out"
            ));
        }
        return torrentClient.get(infoHashV1).compose(status -> {
            if (status.failed() || !status.privateTorrent()) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "TORRENT_INVALID", false, "qBittorrent rejected private Torrent state"
                ));
            }
            long completed = Math.min(totalBytes, Math.round(totalBytes * status.progress()));
            int percent = Math.max(1, Math.min(95, (int) Math.floor(status.progress() * 95)));
            return progress.report("DOWNLOADING_TORRENT", percent, completed, totalBytes)
                    .compose(_ -> contentReady(content, totalBytes))
                    .compose(ready -> status.progress() >= 1 && !status.checking() && ready
                            ? Future.succeededFuture(status)
                            : delay().compose(_ignored -> waitForVerifiedReady(
                                    infoHashV1, totalBytes, startedAt, progress, content
                            )));
        });
    }

    private Future<Boolean> contentReady(Path content, long expectedSize) {
        return vertx.executeBlocking(() -> java.nio.file.Files.isRegularFile(content)
                && java.nio.file.Files.size(content) == expectedSize, false);
    }

    private Future<Void> delay() {
        return Future.future(promise -> vertx.setTimer(
                configuration.pollInterval().toMillis(), _ -> promise.complete()
        ));
    }

    private String relativeView(Path view) {
        Path normalized = view.toAbsolutePath().normalize();
        if (!normalized.startsWith(configuration.sharedRoot())) {
            throw new IllegalArgumentException("Torrent view escaped SHARED_ROOT");
        }
        return configuration.sharedRoot().relativize(normalized).toString().replace('\\', '/');
    }

    private static boolean verifyHash(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private static BootstrapExecutionException classify(Throwable failure) {
        if (failure instanceof BootstrapExecutionException bootstrap) {
            return bootstrap;
        }
        if (failure.getMessage() != null
            && failure.getMessage().contains("STORAGE_LAYOUT_UNSUPPORTED")) {
            return new BootstrapExecutionException(
                    "STORAGE_LAYOUT_UNSUPPORTED", false,
                    "Torrent storage requires hard-link support on one filesystem", failure
            );
        }
        return new BootstrapExecutionException(
                "INTERNAL_RETRYABLE", true, "Torrent download failed", failure
        );
    }

    private record DownloadContext(
            TorrentMetadata metadata,
            byte[] announced,
            TorrentViewStore.TorrentView view,
            Path metadataPath
    ) {
        private DownloadContext(
                TorrentMetadata metadata,
                byte[] announced,
                TorrentViewStore.TorrentView view
        ) {
            this(metadata, announced, view, null);
        }
    }

    private record Downloaded(DownloadContext context, TorrentStatus status) {
    }

    private record Hashed(Downloaded downloaded, String sha256) {
    }

}
