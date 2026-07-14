package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;
import telegram.files.repository.TorrentStatisticEventRecord;
import telegram.files.repository.TorrentStatisticEventRepository;
import telegram.files.repository.TorrentUploadSessionRecord;
import telegram.files.repository.TorrentUploadSessionRepository;
import telegram.files.repository.InstallationIdentityRecord;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable, replayable qBittorrent snapshots used for operational reconciliation only.
 */
public final class TorrentStatisticsReporter {
    private static final Log log = LogFactory.get();

    private static final int BATCH_SIZE = 100;

    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    private final TorrentRepository torrentRepository;

    private final TorrentStatisticEventRepository eventRepository;

    private final TorrentUploadSessionRepository uploadSessionRepository;

    private final TorrentClient torrentClient;

    private final InstallationIdentityService installationIdentityService;

    private volatile int rolloutPercent;

    private final Clock clock;

    private final AtomicBoolean running = new AtomicBoolean();

    public TorrentStatisticsReporter(
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            TorrentRepository torrentRepository,
            TorrentStatisticEventRepository eventRepository,
            TorrentUploadSessionRepository uploadSessionRepository,
            TorrentClient torrentClient,
            InstallationIdentityService installationIdentityService,
            int rolloutPercent
    ) {
        this(identityService, client, torrentRepository, eventRepository, uploadSessionRepository,
                torrentClient, installationIdentityService, rolloutPercent, Clock.systemUTC());
    }

    TorrentStatisticsReporter(
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            TorrentRepository torrentRepository,
            TorrentStatisticEventRepository eventRepository,
            TorrentUploadSessionRepository uploadSessionRepository,
            TorrentClient torrentClient,
            InstallationIdentityService installationIdentityService,
            int rolloutPercent,
            Clock clock
    ) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.client = Objects.requireNonNull(client, "client");
        this.torrentRepository = Objects.requireNonNull(torrentRepository, "torrentRepository");
        this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
        this.uploadSessionRepository = Objects.requireNonNull(
                uploadSessionRepository, "uploadSessionRepository"
        );
        this.torrentClient = Objects.requireNonNull(torrentClient, "torrentClient");
        this.installationIdentityService = Objects.requireNonNull(
                installationIdentityService, "installationIdentityService"
        );
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("Statistics rollout percent is invalid");
        }
        this.rolloutPercent = rolloutPercent;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<Void> runOnce() {
        if (!running.compareAndSet(false, true)) return Future.succeededFuture();
        return identityService.access()
                .compose(access -> {
                    if (!enabledFor(access.identity().nodeId(), rolloutPercent)) {
                        return Future.succeededFuture();
                    }
                    return installationIdentityService.loadOrCreate()
                            .compose(this::captureSnapshots)
                            .compose(_ -> deliver(access.accessToken()));
                })
                .recover(failure -> {
                    if (!(failure instanceof IllegalStateException
                          && "No platform node is bound".equals(failure.getMessage()))) {
                        log.warn("Torrent statistics reporting failed: {}: {}",
                                failure.getClass().getSimpleName(), failure.getMessage());
                    }
                    return Future.succeededFuture();
                })
                .eventually(() -> {
                    running.set(false);
                    return Future.succeededFuture();
                });
    }

    public void setRolloutPercent(int rolloutPercent) {
        if (rolloutPercent < 0 || rolloutPercent > 100) {
            throw new IllegalArgumentException("Statistics rollout percent is invalid");
        }
        this.rolloutPercent = rolloutPercent;
    }

    private Future<Void> captureSnapshots(InstallationIdentityRecord installation) {
        return torrentRepository.listActive(1_000)
                .compose(records -> {
                    Future<Void> chain = Future.succeededFuture();
                    for (TorrentRecord record : records) {
                        chain = chain.compose(_ -> capture(record, installation));
                    }
                    return chain;
                });
    }

    private Future<Void> capture(
            TorrentRecord record,
            InstallationIdentityRecord installation
    ) {
        long now = clock.millis();
        return torrentClient.get(record.infoHashV1())
                .compose(status -> torrentClient.getPeers(record.infoHashV1())
                        .compose(peers -> uploadSessionRepository.reconcile(
                                record.resourceId(), record.infoHashV1(),
                                peers.stream().map(peer -> new TorrentUploadSessionRepository.PeerCounter(
                                        installationIdentityService.anonymizePeer(
                                                installation, record.infoHashV1(), peer.peerIdentity()
                                        ),
                                        peer.uploadedBytes()
                                )).toList(),
                                now,
                                java.time.Duration.ofMinutes(15).toMillis()
                        )).compose(sessions -> captureEvent(record, status, sessions, now)));
    }

    private Future<Void> captureEvent(
            TorrentRecord record,
            TorrentClient.TorrentStatus status,
            TorrentUploadSessionRepository.Summary sessions,
            long now
    ) {
        return eventRepository.latest(record.resourceId()).compose(previous -> {
            boolean reset = previous != null
                            && (record.uploadedBytes() < previous.uploadedBytes()
                                || record.downloadedBytes() < previous.downloadedBytes()
                                || record.seedingSeconds() < previous.seedingSeconds());
            int epoch = previous == null ? 0 : previous.counterEpoch() + (reset ? 1 : 0);
            long intervalSeconds = previous == null
                    ? 0
                    : Math.min(Math.max(0, now - previous.observedAt()), 300_000) / 1_000;
            boolean uploadActive = status.uploadedBytes() > (previous == null ? 0 : previous.uploadedBytes())
                                   || status.uploadSpeedBytesPerSecond() > 0;
            boolean downloadActive = status.downloadedBytes() > (previous == null ? 0 : previous.downloadedBytes())
                                     || status.downloadSpeedBytesPerSecond() > 0;
            long cumulativeUploadSeconds = (previous == null ? 0 : previous.cumulativeUploadSeconds())
                                           + (uploadActive ? intervalSeconds : 0);
            long cumulativeDownloadSeconds = (previous == null ? 0 : previous.cumulativeDownloadSeconds())
                                             + (downloadActive ? intervalSeconds : 0);
            JsonArray sessionPayload = new JsonArray();
            sessions.sessions().stream()
                    .skip(Math.max(0, sessions.sessions().size() - 256L))
                    .forEach(session -> sessionPayload.add(toJson(session)));
            if (previous != null && !reset
                && record.uploadedBytes() == previous.uploadedBytes()
                && record.downloadedBytes() == previous.downloadedBytes()
                && record.seedingSeconds() == previous.seedingSeconds()
                && record.status().equals(previous.torrentStatus())
                && sessions.activeCount() == previous.activeUploadCount()
                && sessions.completedCount() == previous.completedUploadCount()
                && status.uploadSpeedBytesPerSecond() == previous.uploadSpeedBytesPerSecond()
                && status.downloadSpeedBytesPerSecond() == previous.downloadSpeedBytesPerSecond()) {
                return Future.succeededFuture();
            }
            return eventRepository.create(new TorrentStatisticEventRecord(
                    UUID.randomUUID().toString(), record.resourceId(), record.infoHashV1(), epoch,
                    record.uploadedBytes(), record.downloadedBytes(), record.seedingSeconds(),
                    sessions.activeCount(), sessions.completedCount(), cumulativeUploadSeconds,
                    cumulativeDownloadSeconds, status.uploadSpeedBytesPerSecond(),
                    status.downloadSpeedBytesPerSecond(), sessionPayload.encode(),
                    record.status(), now, "PENDING", 0, now, now
            ));
        });
    }

    private Future<Void> deliver(String accessToken) {
        return eventRepository.listPending(BATCH_SIZE).compose(events -> {
            if (events.isEmpty()) return Future.succeededFuture();
            JsonArray payload = new JsonArray();
            events.forEach(event -> payload.add(toJson(event)));
            String idempotencyKey = "stats-" + events.getFirst().eventId();
            return client.post(
                            "/api/v1/nodes/statistics/events",
                            new JsonObject().put("events", payload),
                            Map.of(
                                    "Authorization", "Bearer " + accessToken,
                                    "Idempotency-Key", idempotencyKey
                            )
                    )
                    .compose(response -> {
                        validateDeliveryResponse(response, events.size());
                        return eventRepository.markDelivered(
                                events.stream().map(TorrentStatisticEventRecord::eventId).toList(),
                                clock.millis()
                        );
                    });
        });
    }

    static void validateDeliveryResponse(JsonObject response, int expectedEvents) {
        if (response == null || expectedEvents < 1) {
            throw new IllegalStateException("Platform returned an invalid statistics acknowledgement");
        }
        Integer accepted = response.getInteger("accepted");
        Integer duplicates = response.getInteger("duplicates");
        JsonArray rejected = response.getJsonArray("rejected");
        if (accepted == null || accepted < 0 || duplicates == null || duplicates < 0
            || rejected == null || accepted + duplicates + rejected.size() != expectedEvents) {
            throw new IllegalStateException("Platform returned an invalid statistics acknowledgement");
        }
        if (!rejected.isEmpty()) {
            throw new IllegalStateException("Platform rejected " + rejected.size() + " statistics events");
        }
    }

    private static JsonObject toJson(TorrentStatisticEventRecord event) {
        return new JsonObject()
                .put("eventId", event.eventId())
                .put("resourceId", event.resourceId())
                .put("infoHashV1", event.infoHashV1())
                .put("counterEpoch", event.counterEpoch())
                .put("uploadedBytes", Long.toString(event.uploadedBytes()))
                .put("downloadedBytes", Long.toString(event.downloadedBytes()))
                .put("seedingSeconds", Long.toString(event.seedingSeconds()))
                .put("activeUploadCount", event.activeUploadCount())
                .put("completedUploadCount", event.completedUploadCount())
                .put("cumulativeUploadSeconds", Long.toString(event.cumulativeUploadSeconds()))
                .put("cumulativeDownloadSeconds", Long.toString(event.cumulativeDownloadSeconds()))
                .put("uploadSpeedBytesPerSecond", Long.toString(event.uploadSpeedBytesPerSecond()))
                .put("downloadSpeedBytesPerSecond", Long.toString(event.downloadSpeedBytesPerSecond()))
                .put("uploadSessions", new JsonArray(event.uploadSessionsJson()))
                .put("torrentStatus", event.torrentStatus())
                .put("observedAt", DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(event.observedAt())
                ));
    }

    private static JsonObject toJson(TorrentUploadSessionRecord session) {
        return new JsonObject()
                .put("sessionKey", session.sessionKey())
                .put("counterEpoch", session.counterEpoch())
                .put("startedAt", DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(session.startedAt())
                ))
                .put("lastObservedAt", DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(session.lastObservedAt())
                ))
                .put("endedAt", session.endedAt() == null ? null : DateTimeFormatter.ISO_INSTANT.format(
                        Instant.ofEpochMilli(session.endedAt())
                ))
                .put("uploadedBytes", Long.toString(session.uploadedBytes()))
                .put("status", session.status());
    }

    static boolean enabledFor(String nodeId, int percent) {
        if (percent <= 0) return false;
        if (percent >= 100) return true;
        UUID namespace = UUID.nameUUIDFromBytes(nodeId.getBytes(StandardCharsets.UTF_8));
        int bucket = Math.floorMod(namespace.hashCode(), 100);
        return bucket < percent;
    }
}
