package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.DataVerticle;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class UnifiedFileDownloadService {

    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    private final TorrentRepository torrentRepository;

    private final TorrentControlExecutor torrentControlExecutor;

    private final TorrentStatisticsReporter statisticsReporter;

    public UnifiedFileDownloadService(NodeIdentityService identityService, SeedCoordinatorClient client) {
        this(identityService, client, DataVerticle.torrentRepository, null, null);
    }

    UnifiedFileDownloadService(
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            TorrentRepository torrentRepository
    ) {
        this(identityService, client, torrentRepository, null, null);
    }

    public UnifiedFileDownloadService(
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            TorrentRepository torrentRepository,
            TorrentControlExecutor torrentControlExecutor
    ) {
        this(identityService, client, torrentRepository, torrentControlExecutor, null);
    }

    public UnifiedFileDownloadService(
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            TorrentRepository torrentRepository,
            TorrentControlExecutor torrentControlExecutor,
            TorrentStatisticsReporter statisticsReporter
    ) {
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.client = Objects.requireNonNull(client, "client");
        this.torrentRepository = Objects.requireNonNull(torrentRepository, "torrentRepository");
        this.torrentControlExecutor = torrentControlExecutor;
        this.statisticsReporter = statisticsReporter;
    }

    public Future<Boolean> downloadIfAvailable(String fileUniqueId, long fileSize) {
        return findTorrent(fileUniqueId).compose(active -> {
            if (active != null) {
                if ("PAUSED".equals(active.status())) {
                    return seedControl(active, "RESUME_V1").map(true);
                }
                return Future.succeededFuture(true);
            }
            return requestSeedDownload(fileUniqueId, fileSize);
        });
    }

    public Future<Boolean> controlIfPresent(String fileUniqueId, String controlType) {
        return findTorrent(fileUniqueId).compose(torrent -> torrent == null
                ? Future.succeededFuture(false)
                : seedControl(torrent, controlType).map(true));
    }

    public Future<JsonObject> controlSeedResource(String resourceId, String controlType) {
        return controlSeedResource(resourceId, controlType, 0);
    }

    public Future<JsonObject> controlSeedResource(
            String resourceId,
            String controlType,
            long uploadLimitBytesPerSecond
    ) {
        return torrentRepository.getByResourceId(resourceId).compose(torrent -> {
            if (torrent == null) {
                return Future.failedFuture(new IllegalArgumentException("Torrent was not found"));
            }
            if (uploadLimitBytesPerSecond < 0) {
                return Future.failedFuture(new IllegalArgumentException("Upload limit is invalid"));
            }
            if ("CANCEL_V1".equals(controlType) && "STOPPED".equals(torrent.status())) {
                return Future.succeededFuture(new JsonObject()
                        .put("route", "SEED")
                        .put("resourceId", resourceId)
                        .put("status", "STOPPED"));
            }
            return seedControl(torrent, controlType, uploadLimitBytesPerSecond);
        });
    }

    public Future<JsonObject> startSeedResource(String resourceId) {
        return torrentRepository.getByResourceId(resourceId).compose(torrent -> {
            if (torrent == null) {
                return Future.failedFuture(new IllegalArgumentException("Torrent was not found"));
            }
            if ("PAUSED".equals(torrent.status()) || "STOPPED".equals(torrent.status())) {
                return seedControl(torrent, "RESUME_V1");
            }
            return Future.succeededFuture(new JsonObject()
                    .put("route", "SEED")
                    .put("resourceId", resourceId)
                    .put("status", torrent.status())
                    .put("alreadyActive", true));
        });
    }

    private Future<TorrentRecord> findTorrent(String fileUniqueId) {
        if (fileUniqueId == null || fileUniqueId.isBlank()) return Future.succeededFuture(null);
        return torrentRepository.listByTelegramFileUniqueIds(List.of(fileUniqueId))
                .map(torrents -> torrents.stream().filter(torrent -> !"STOPPED".equals(torrent.status()))
                        .findFirst().orElse(null));
    }

    private Future<JsonObject> seedControl(TorrentRecord torrent, String controlType) {
        return seedControl(torrent, controlType, 0);
    }

    private Future<JsonObject> seedControl(
            TorrentRecord torrent,
            String controlType,
            long uploadLimitBytesPerSecond
    ) {
        if (torrentControlExecutor == null) {
            return Future.failedFuture(new IllegalStateException("Local Torrent control is unavailable"));
        }
        return torrentControlExecutor.executeLocal(
                torrent.resourceId(), controlType, uploadLimitBytesPerSecond
        ).map(result -> result.put("route", "SEED"))
                .onSuccess(_ -> {
                    if (statisticsReporter != null) {
                        statisticsReporter.runOnce();
                    }
                });
    }

    private Future<Boolean> requestSeedDownload(String fileUniqueId, long fileSize) {
        return identityService.access().compose(access -> {
            Map<String, String> authorization = Map.of(
                    "Authorization", "Bearer " + access.accessToken()
            );
            String fingerprint = sourceFingerprint(fileUniqueId, fileSize);
            JsonObject body = new JsonObject().put("files", new JsonArray().add(
                    new JsonObject()
                            .put("sourceFingerprint", fingerprint)
                            .put("fileSize", Long.toString(fileSize))
            ));
            return client.post("/api/v1/nodes/file-availability", body, authorization)
                    .compose(response -> {
                        JsonObject availability = response.getJsonArray("files", new JsonArray())
                                .stream().filter(JsonObject.class::isInstance)
                                .map(JsonObject.class::cast).findFirst().orElse(null);
                        if (availability == null
                            || !availability.getBoolean("ptAvailable", false)
                            || availability.getString("resourceId") == null) {
                            return Future.succeededFuture(false);
                        }
                        return client.post(
                                "/api/v1/resources/" + availability.getString("resourceId") + "/download",
                                new JsonObject().put("targetNodeId", access.identity().nodeId()),
                                Map.of(
                                        "Authorization", "Bearer " + access.accessToken(),
                                        "Idempotency-Key", UUID.randomUUID().toString()
                                )
                        ).map(true);
                    });
        });
    }

    public static String sourceFingerprint(String fileUniqueId, long fileSize) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(fileUniqueId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(fileSize).getBytes(StandardCharsets.US_ASCII));
            return "v1:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
