package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;
import telegram.files.share.TorrentClient.AddRequest;
import telegram.files.share.TorrentClient.TorrentStatus;
import telegram.files.share.V1TorrentService.TorrentMetadata;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class PrivateTorrentService {

    private static final Log log = LogFactory.get();

    private final Vertx vertx;

    private final TorrentConfiguration configuration;

    private final SeedCoordinatorClient coordinatorClient;

    private final V1TorrentService torrentService;

    private final TorrentViewStore viewStore;

    private final LocalTorrentMetadataStore metadataStore;

    private final TorrentClient torrentClient;

    private final FileRepository fileRepository;

    private final TorrentRepository torrentRepository;

    private final Clock clock;

    public PrivateTorrentService(
            Vertx vertx,
            TorrentConfiguration configuration,
            SeedCoordinatorClient coordinatorClient,
            V1TorrentService torrentService,
            TorrentViewStore viewStore,
            LocalTorrentMetadataStore metadataStore,
            TorrentClient torrentClient,
            FileRepository fileRepository,
            TorrentRepository torrentRepository
    ) {
        this(
                vertx, configuration, coordinatorClient, torrentService, viewStore, metadataStore,
                torrentClient, fileRepository, torrentRepository, Clock.systemUTC()
        );
    }

    PrivateTorrentService(
            Vertx vertx,
            TorrentConfiguration configuration,
            SeedCoordinatorClient coordinatorClient,
            V1TorrentService torrentService,
            TorrentViewStore viewStore,
            LocalTorrentMetadataStore metadataStore,
            TorrentClient torrentClient,
            FileRepository fileRepository,
            TorrentRepository torrentRepository,
            Clock clock
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.coordinatorClient = Objects.requireNonNull(coordinatorClient, "coordinatorClient");
        this.torrentService = Objects.requireNonNull(torrentService, "torrentService");
        this.viewStore = Objects.requireNonNull(viewStore, "viewStore");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
        this.torrentClient = Objects.requireNonNull(torrentClient, "torrentClient");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository");
        this.torrentRepository = Objects.requireNonNull(torrentRepository, "torrentRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<Void> publish(
            TelegramBootstrapTask task,
            JsonObject completion,
            NodeIdentityService.NodeAccess access
    ) {
        String sha256 = completion.getString("sha256");
        String fileSize = completion.getString("fileSize");
        if (sha256 == null || !sha256.matches("[a-f0-9]{64}")
            || !Long.toString(task.fileSize()).equals(fileSize)
            || access.trackerCredential() == null) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "TORRENT_INVALID", false, "Bootstrap completion cannot create a private Torrent"
            ));
        }
        String name = V1TorrentService.safeFileName(task.fileName(), task.resourceId());
        long startedAt = clock.millis();
        return sourceContent(task).compose(source -> torrentService.create(
                        source.path(), name, task.fileSize()
                )
                .compose(metadata -> metadataStore.save(
                                metadata.infoHashV1(), metadata.canonicalBytes()
                        )
                        .map(path -> new Publication(metadata, path, source))))
                .compose(publication -> reportMetadata(task, sha256, publication.metadata(), access)
                        .map(response -> new Confirmed(
                                publication,
                                URI.create(response.getString("trackerBaseUrl", ""))
                        )))
                .compose(confirmed -> viewStore.createSeedView(
                                confirmed.publication().metadata().infoHashV1(),
                                confirmed.publication().metadata().name(),
                                confirmed.publication().source().path(),
                                task.fileSize()
                        )
                        .map(view -> new Prepared(confirmed, view)))
                .compose(prepared -> {
                    TorrentMetadata metadata = prepared.confirmed().publication().metadata();
                    byte[] announced = torrentService.withTracker(
                            metadata,
                            prepared.confirmed().trackerBaseUri(),
                            access.trackerCredential()
                    );
                    return torrentClient.addOrConfirm(new AddRequest(
                                    announced,
                                    configuration.qbittorrentPath(prepared.view().directory()),
                                    "telegram-files",
                                    List.of("telegram-files", "resource-" + task.resourceId()),
                                    true
                            ), metadata.infoHashV1())
                            .compose(_ -> waitForTorrent(metadata.infoHashV1(), startedAt))
                            .compose(_ -> torrentClient.recheck(metadata.infoHashV1()))
                            .map(prepared);
                })
                .compose(prepared -> saveRecord(
                        task, sha256, prepared, "CHECKING", 0, 0, 0
                )
                        .map(prepared))
                .compose(prepared -> delay()
                        .compose(_ -> waitForVerified(
                                prepared.confirmed().publication().metadata().infoHashV1(), startedAt
                        ))
                        .map(status -> new Verified(prepared, status)))
                .compose(verified -> torrentClient.resume(
                                verified.prepared().confirmed().publication().metadata().infoHashV1()
                        )
                        .compose(_ -> saveRecord(
                                task, sha256, verified.prepared(), "SEEDING", 1000,
                                verified.status().downloadedBytes(), verified.status().uploadedBytes()
                        ))
                        .compose(_ -> reportSeedStatus(
                                task,
                                verified.prepared().confirmed().publication().metadata().infoHashV1(),
                                access
                        )))
                .map(_ -> (Void) null)
                .recover(failure -> {
                    log.warn("Private Torrent publication failed: {}: {}",
                            failure.getClass().getSimpleName(), failure.getMessage());
                    return Future.<Void>failedFuture(classify(failure));
                });
    }

    private Future<JsonObject> reportMetadata(
            TelegramBootstrapTask task,
            String sha256,
            TorrentMetadata metadata,
            NodeIdentityService.NodeAccess access
    ) {
        JsonObject body = new JsonObject()
                .put("torrentBase64", metadata.canonicalBase64())
                .put("contentSha256", sha256)
                .put("fileSize", Long.toString(task.fileSize()))
                .put("torrentVersion", V1TorrentService.TORRENT_VERSION)
                .put("nodeId", access.identity().nodeId())
                .put("attemptId", task.attemptId())
                .put("leaseToken", task.leaseToken());
        return coordinatorClient.post(
                "/api/v1/resources/" + task.resourceId() + "/torrent",
                body,
                headers(access.accessToken(), task, "torrent")
        );
    }

    private Future<JsonObject> reportSeedStatus(
            TelegramBootstrapTask task,
            String infoHashV1,
            NodeIdentityService.NodeAccess access
    ) {
        JsonObject body = new JsonObject()
                .put("attemptId", task.attemptId())
                .put("leaseToken", task.leaseToken())
                .put("infoHashV1", infoHashV1)
                .put("state", "SEEDING")
                .put("progressPermille", 1000);
        return coordinatorClient.post(
                "/api/v1/resources/" + task.resourceId() + "/seed-status",
                body,
                headers(access.accessToken(), task, "seed-status")
        );
    }

    private Future<TorrentRecord> saveRecord(
            TelegramBootstrapTask task,
            String sha256,
            Prepared prepared,
            String state,
            int progress,
            long downloaded,
            long uploaded
    ) {
        long now = clock.millis();
        Publication publication = prepared.confirmed().publication();
        return torrentRepository.save(new TorrentRecord(
                UUID.randomUUID().toString(), task.resourceId(), sha256,
                publication.metadata().infoHashV1(),
                metadataStore.relative(publication.metadataPath()),
                relative(prepared.view().content()),
                task.fileName(), task.fileSize(), publication.source().mimeType(),
                task.fileUniqueId(), "TELEGRAM",
                "SEEDING".equals(state) ? now : null,
                state, progress, downloaded, uploaded,
                0, 0, 0, configuration.qbittorrentPath(prepared.view().directory()),
                prepared.confirmed().trackerBaseUri().toString(),
                0, now, now, now, 0
        ));
    }

    private Future<SourceContent> sourceContent(TelegramBootstrapTask task) {
        return fileRepository.getByUniqueId(task.fileUniqueId()).compose(file -> {
            if (file == null || file.size() != task.fileSize()
                || file.localPath() == null || file.localPath().isBlank()) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "SOURCE_UNAVAILABLE", true,
                        "Telegram source file is unavailable for Torrent publication"
                ));
            }
            Path path = Path.of(file.localPath()).toAbsolutePath().normalize();
            return vertx.executeBlocking(() -> validateSourceContent(file, path, task.fileSize()), false);
        });
    }

    private static SourceContent validateSourceContent(
            FileRecord file,
            Path path,
            long expectedSize
    ) throws java.io.IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
            || Files.isSymbolicLink(path) || Files.size(path) != expectedSize) {
            throw new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true,
                    "Telegram source file disappeared before Torrent publication"
            );
        }
        return new SourceContent(path, file.mimeType());
    }

    private Future<TorrentStatus> waitForVerified(String infoHashV1, long startedAt) {
        if (clock.millis() - startedAt > configuration.operationTimeout().toMillis()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "INTERNAL_RETRYABLE", true, "qBittorrent recheck timed out"
            ));
        }
        return torrentClient.get(infoHashV1).compose(status -> {
            if (status.failed() || !status.privateTorrent()) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "TORRENT_INVALID", false, "qBittorrent private Torrent verification failed"
                ));
            }
            return status.progress() >= 1 && !status.checking()
                    ? Future.succeededFuture(status)
                    : delay().compose(_ -> waitForVerified(infoHashV1, startedAt));
        });
    }

    private Future<Void> waitForTorrent(String infoHashV1, long startedAt) {
        if (clock.millis() - startedAt > configuration.operationTimeout().toMillis()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "INTERNAL_RETRYABLE", true, "qBittorrent add timed out"
            ));
        }
        return torrentClient.get(infoHashV1)
                .map(_ -> (Void) null)
                .recover(_ -> delay().compose(ignored -> waitForTorrent(infoHashV1, startedAt)));
    }

    private Future<Void> delay() {
        Promise<Void> promise = Promise.promise();
        vertx.setTimer(configuration.pollInterval().toMillis(), _ -> promise.complete());
        return promise.future();
    }

    private String relative(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(configuration.sharedRoot())) {
            throw new IllegalArgumentException("Torrent path escaped SHARED_ROOT");
        }
        return configuration.sharedRoot().relativize(normalized).toString().replace('\\', '/');
    }

    private static Map<String, String> headers(
            String accessToken,
            TelegramBootstrapTask task,
            String action
    ) {
        return Map.of(
                "Authorization", "Bearer " + accessToken,
                "Idempotency-Key", "task_" + sha256(
                        task.taskId() + '\u0000' + task.attemptId() + '\u0000' + action
                )
        );
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BootstrapExecutionException classify(Throwable failure) {
        if (failure instanceof BootstrapExecutionException bootstrap) {
            return bootstrap;
        }
        if (failure.getMessage() != null
            && failure.getMessage().contains("STORAGE_LAYOUT_UNSUPPORTED")) {
            return new BootstrapExecutionException(
                    "STORAGE_LAYOUT_UNSUPPORTED", false,
                    "Telegram source and Torrent view must support same-filesystem hard links",
                    failure
            );
        }
        return new BootstrapExecutionException(
                "INTERNAL_RETRYABLE", true, "Private Torrent publication failed", failure
        );
    }

    private record SourceContent(Path path, String mimeType) {
    }

    private record Publication(
            TorrentMetadata metadata,
            Path metadataPath,
            SourceContent source
    ) {
    }

    private record Confirmed(Publication publication, URI trackerBaseUri) {
    }

    private record Prepared(Confirmed confirmed, TorrentViewStore.TorrentView view) {
    }

    private record Verified(Prepared prepared, TorrentStatus status) {
    }
}
