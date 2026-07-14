package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;
import telegram.files.share.QbittorrentClient.TorrentNotFoundException;
import telegram.files.share.TorrentClient.AddRequest;
import telegram.files.share.TorrentClient.TorrentStatus;

import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores and synchronizes platform-owned qBittorrent jobs without touching their content. */
public final class TorrentReconciler {

    private static final Log log = LogFactory.get();
    private static final int BATCH_SIZE = 1_000;

    private final TorrentRepository repository;
    private final TorrentClient client;
    private final LocalTorrentMetadataStore metadataStore;
    private final V1TorrentService torrentService;
    private final NodeIdentityService identityService;
    private final Clock clock;
    private final AtomicBoolean running = new AtomicBoolean();

    public TorrentReconciler(
            TorrentRepository repository,
            TorrentClient client,
            LocalTorrentMetadataStore metadataStore,
            V1TorrentService torrentService,
            NodeIdentityService identityService
    ) {
        this(repository, client, metadataStore, torrentService, identityService, Clock.systemUTC());
    }

    TorrentReconciler(
            TorrentRepository repository,
            TorrentClient client,
            LocalTorrentMetadataStore metadataStore,
            V1TorrentService torrentService,
            NodeIdentityService identityService,
            Clock clock
    ) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.client = Objects.requireNonNull(client, "client");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore");
        this.torrentService = Objects.requireNonNull(torrentService, "torrentService");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<Void> runOnce() {
        if (!running.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        return repository.listActive(BATCH_SIZE)
                .compose(records -> {
                    List<Future<Void>> updates = new ArrayList<>();
                    records.forEach(record -> updates.add(reconcile(record).recover(failure -> {
                        log.warn("Torrent reconciliation failed for resource {}: {}",
                                record.resourceId(), failure.getClass().getSimpleName());
                        return Future.<Void>succeededFuture();
                    })));
                    return updates.isEmpty() ? Future.<Void>succeededFuture() : Future.all(updates).mapEmpty();
                })
                .eventually(() -> {
                    running.set(false);
                    return Future.<Void>succeededFuture();
                });
    }

    private Future<Void> reconcile(TorrentRecord record) {
        return client.get(record.infoHashV1())
                .compose(status -> synchronize(record, status))
                .recover(failure -> failure instanceof TorrentNotFoundException
                        ? restore(record)
                        : Future.failedFuture(failure));
    }

    private Future<Void> restore(TorrentRecord record) {
        if (record.trackerBaseUrl().isBlank() || record.savePath().isBlank()) {
            return Future.failedFuture("Legacy Torrent record lacks recovery metadata");
        }
        return identityService.access().compose(access -> {
            if (access.trackerCredential() == null) {
                return Future.failedFuture("Tracker credential is unavailable");
            }
            return metadataStore.readRelative(record.torrentRelativePath())
                    .compose(canonical -> {
                        V1TorrentService.TorrentMetadata metadata = torrentService.parseCanonical(
                                canonical, record.infoHashV1()
                        );
                        byte[] announced = torrentService.withTracker(
                                metadata, URI.create(record.trackerBaseUrl()), access.trackerCredential()
                        );
                        return client.addOrConfirm(new AddRequest(
                                announced,
                                record.savePath(),
                                "telegram-files",
                                List.of("telegram-files", "resource-" + record.resourceId()),
                                true
                        ), record.infoHashV1());
                    })
                    .compose(_ -> client.recheck(record.infoHashV1()))
                    .compose(_ -> save(record, "RECOVERING", 0, 0, 0, 0, 0, 0, record.savePath()));
        });
    }

    private Future<Void> synchronize(TorrentRecord record, TorrentStatus status) {
        String expected = stripTrailingSlash(record.savePath());
        String actual = stripTrailingSlash(status.savePath());
        if ((!expected.isEmpty() && !expected.equals(actual)) || !status.privateTorrent()) {
            return save(record, "ERROR", status.progress(), status.downloadedBytes(),
                    status.uploadedBytes(), status.downloadSpeedBytesPerSecond(),
                    status.uploadSpeedBytesPerSecond(), status.connectedPeers(),
                    expected.isEmpty() ? status.savePath() : record.savePath());
        }
        if ("RECOVERING".equals(record.status())) {
            if (status.checking()) {
                return save(record, "RECOVERING", status.progress(), status.downloadedBytes(),
                        status.uploadedBytes(), status.downloadSpeedBytesPerSecond(),
                        status.uploadSpeedBytesPerSecond(), status.connectedPeers(), status.savePath());
            }
            String resumedState = status.progress() >= 1 ? "SEEDING" : "DOWNLOADING";
            return client.resume(record.infoHashV1()).compose(_ -> save(
                    record, resumedState, status.progress(), status.downloadedBytes(),
                    status.uploadedBytes(), status.downloadSpeedBytesPerSecond(),
                    status.uploadSpeedBytesPerSecond(), status.connectedPeers(), status.savePath()
            ));
        }
        return save(record, mappedState(status), status.progress(), status.downloadedBytes(),
                status.uploadedBytes(), status.downloadSpeedBytesPerSecond(),
                status.uploadSpeedBytesPerSecond(), status.connectedPeers(), status.savePath());
    }

    private Future<Void> save(
            TorrentRecord record,
            String state,
            double progress,
            long downloaded,
            long uploaded,
            long downloadSpeed,
            long uploadSpeed,
            int connectedPeers,
            String savePath
    ) {
        long now = clock.millis();
        long seeding = record.seedingSeconds();
        if ("SEEDING".equals(record.status()) && "SEEDING".equals(state)
            && record.lastSynchronizedAt() > 0 && now >= record.lastSynchronizedAt()) {
            seeding += (now - record.lastSynchronizedAt()) / 1_000;
        }
        TorrentRecord updated = new TorrentRecord(
                record.id(), record.resourceId(), record.contentSha256(), record.infoHashV1(),
                record.torrentRelativePath(), record.viewRelativePath(), record.fileName(),
                record.fileSize(), record.mimeType(), record.telegramFileUniqueId(),
                record.acquiredVia(), record.completedAt(), state,
                Math.max(0, Math.min(1_000, (int) Math.round(progress * 1_000))),
                downloaded, uploaded, downloadSpeed, uploadSpeed,
                connectedPeers, savePath, record.trackerBaseUrl(), seeding,
                now, record.createdAt(), now, record.version()
        );
        return repository.save(updated).mapEmpty();
    }

    private static String mappedState(TorrentStatus status) {
        String state = status.state().toLowerCase(Locale.ROOT);
        if (status.failed()) return "ERROR";
        if (status.checking()) return "CHECKING";
        if (state.contains("pause") || state.contains("stop")) return "PAUSED";
        if (status.progress() >= 1) return "SEEDING";
        return "DOWNLOADING";
    }

    private static String stripTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }
}
