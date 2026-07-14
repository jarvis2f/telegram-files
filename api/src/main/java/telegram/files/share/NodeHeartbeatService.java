package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.BuildInfo;
import telegram.files.Config;
import telegram.files.TelegramVerticles;
import telegram.files.repository.SeedNodeIdentityRepository;
import telegram.files.repository.NodeTaskRepository;
import telegram.files.repository.TorrentRepository;
import telegram.files.share.HttpSeedCoordinatorClient.SeedProtocolException;

import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;

public final class NodeHeartbeatService {

    private static final Log log = LogFactory.get();

    private static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    private final Vertx vertx;

    private final ShareConfiguration configuration;

    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    private final SeedNodeIdentityRepository repository;

    private final NodeTaskRepository taskRepository;

    private final TorrentConfiguration torrentConfiguration;

    private final TorrentRepository torrentRepository;

    private final NodeTaskPoller taskPoller;

    private final NodeConfigurationService configurationService;

    private final TorrentStatisticsReporter statisticsReporter;

    private final Clock clock;

    private final DoubleSupplier jitterSource;

    private final AtomicBoolean sending = new AtomicBoolean();

    private long timerId = -1;

    private int consecutiveFailures;

    private boolean running;

    private volatile Duration currentInterval;

    private volatile Duration statisticsInterval = Duration.ofSeconds(60);

    private volatile Duration taskPullInterval = Duration.ZERO;

    private String taskVersion = "";

    private String configVersion = "";

    private long lastTaskPullAt;

    private long nextStatisticsAt;

    public NodeHeartbeatService(
            Vertx vertx,
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            NodeTaskRepository taskRepository,
            TorrentConfiguration torrentConfiguration,
            TorrentRepository torrentRepository
    ) {
        this(
                vertx, configuration, identityService, client, repository, taskRepository,
                torrentConfiguration, torrentRepository,
                Duration.ofSeconds(Config.SHARE_HEARTBEAT_INTERVAL_SECONDS),
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(),
                null, null, null
        );
    }

    public NodeHeartbeatService(
            Vertx vertx,
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            NodeTaskRepository taskRepository,
            TorrentConfiguration torrentConfiguration,
            TorrentRepository torrentRepository,
            NodeTaskPoller taskPoller,
            NodeConfigurationService configurationService,
            TorrentStatisticsReporter statisticsReporter
    ) {
        this(
                vertx, configuration, identityService, client, repository, taskRepository,
                torrentConfiguration, torrentRepository,
                Duration.ofSeconds(Config.SHARE_HEARTBEAT_INTERVAL_SECONDS),
                Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble(),
                taskPoller, configurationService, statisticsReporter
        );
    }

    NodeHeartbeatService(
            Vertx vertx,
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            NodeTaskRepository taskRepository,
            TorrentConfiguration torrentConfiguration,
            TorrentRepository torrentRepository,
            Duration interval,
            Clock clock,
            DoubleSupplier jitterSource,
            NodeTaskPoller taskPoller,
            NodeConfigurationService configurationService,
            TorrentStatisticsReporter statisticsReporter
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.client = Objects.requireNonNull(client, "client");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.taskRepository = taskRepository;
        this.torrentConfiguration = torrentConfiguration;
        this.torrentRepository = torrentRepository;
        this.currentInterval = Objects.requireNonNull(interval, "interval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jitterSource = Objects.requireNonNull(jitterSource, "jitterSource");
        this.taskPoller = taskPoller;
        this.configurationService = configurationService;
        this.statisticsReporter = statisticsReporter;
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduleNext(0);
    }

    public void stop() {
        running = false;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    public Future<Void> sendNow() {
        if (!sending.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        return identityService.access()
                .compose(access -> collect(access.identity().nodeId())
                        .compose(body -> client.post(
                                "/api/v1/nodes/heartbeat",
                                body,
                                Map.of("Authorization", "Bearer " + access.accessToken())
                        )))
                .compose(response -> repository.updateHeartbeat(clock.millis())
                        .compose(_ -> coordinate(response)))
                .onSuccess(_ -> consecutiveFailures = 0)
                .recover(failure -> {
                    if (failure instanceof IllegalStateException
                        && "No platform node is bound".equals(failure.getMessage())) {
                        consecutiveFailures = 0;
                        return Future.succeededFuture();
                    }
                    if (failure instanceof SeedProtocolException protocol
                        && "NODE_REVOKED".equals(protocol.errorCode())) {
                        consecutiveFailures = 0;
                        return identityService.clearRevoked();
                    }
                    if (failure instanceof SeedProtocolException protocol
                        && protocol.statusCode() == 401) {
                        consecutiveFailures = Math.min(consecutiveFailures + 1, 30);
                        return identityService.refreshAfterUnauthorized()
                                .recover(refreshFailure -> {
                                    log.warn("Node credential refresh failed after unauthorized heartbeat: {}",
                                            refreshFailure.getClass().getSimpleName());
                                    return Future.succeededFuture();
                                });
                    }
                    consecutiveFailures = Math.min(consecutiveFailures + 1, 30);
                    log.warn("Node heartbeat failed: {}", failure.getMessage());
                    return Future.succeededFuture();
                })
                .eventually(() -> {
                    sending.set(false);
                    return Future.succeededFuture();
                });
    }

    private void scheduleNext(long delayMillis) {
        if (!running) {
            return;
        }
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
        }
        timerId = vertx.setTimer(timerDelayMillis(delayMillis), _ -> {
            timerId = -1;
            sendNow().onComplete(_ -> scheduleNext(retryDelayMillis(
                    currentInterval,
                    consecutiveFailures,
                    jitterSource.getAsDouble()
            )));
        });
    }

    private Future<Void> coordinate(JsonObject response) {
        CoordinationActions actions = actions(
                taskVersion,
                configVersion,
                lastTaskPullAt,
                taskPullInterval.toMillis(),
                nextStatisticsAt,
                response,
                clock.millis()
        );
        Future<Void> chain = Future.succeededFuture();
        if (actions.refreshConfiguration() && configurationService != null) {
            chain = chain.compose(_ -> configurationService.refresh())
                    .compose(configuration -> {
                        currentInterval = Duration.ofSeconds(
                                configuration.heartbeatIntervalSeconds()
                        );
                        statisticsInterval = Duration.ofSeconds(
                                configuration.statisticsIntervalSeconds()
                        );
                        taskPullInterval = Duration.ofSeconds(
                                configuration.taskPullIntervalSeconds()
                        );
                        if (statisticsReporter != null) {
                            statisticsReporter.setRolloutPercent(
                                    configuration.statisticsRolloutPercent()
                            );
                        }
                        configVersion = configuration.version();
                        return Future.succeededFuture();
                    });
        } else {
            configVersion = actions.configVersion();
        }
        if (taskPoller != null) {
            chain = chain.compose(_ -> taskPoller.runOnce(actions.pullTasks()));
            if (actions.pullTasks()) {
                chain = chain.onSuccess(_ -> {
                    taskVersion = actions.taskVersion();
                    lastTaskPullAt = clock.millis();
                });
            }
        } else {
            taskVersion = actions.taskVersion();
        }
        if (actions.reportStatistics() && statisticsReporter != null) {
            chain = chain.compose(_ -> statisticsReporter.runOnce())
                    .onSuccess(_ -> nextStatisticsAt =
                            clock.millis() + statisticsInterval.toMillis());
        }
        return chain;
    }

    static long timerDelayMillis(long requestedDelayMillis) {
        return Math.max(1, requestedDelayMillis);
    }

    static long retryDelayMillis(Duration baseInterval, int failures, double jitterUnit) {
        if (failures <= 0) {
            return baseInterval.toMillis();
        }
        int exponent = Math.min(failures - 1, 20);
        long multiplier = 1L << exponent;
        long baseMillis = baseInterval.toMillis();
        long capped = Math.min(
                baseMillis > MAX_BACKOFF.toMillis() / multiplier
                        ? MAX_BACKOFF.toMillis()
                        : baseMillis * multiplier,
                MAX_BACKOFF.toMillis()
        );
        double normalizedJitter = Math.max(0, Math.min(1, jitterUnit));
        long jittered = Math.round(capped * (0.8 + normalizedJitter * 0.4));
        return Math.max(1, Math.min(jittered, MAX_BACKOFF.toMillis()));
    }

    static CoordinationActions actions(
            String currentTaskVersion,
            String currentConfigVersion,
            long lastTaskPullAt,
            long taskPullIntervalMillis,
            long nextStatisticsAt,
            JsonObject response,
            long now
    ) {
        if (response == null) {
            throw new IllegalArgumentException("Heartbeat response is missing");
        }
        String taskVersion = response.getString("taskVersion");
        String configVersion = response.getString("configVersion");
        if (taskVersion == null || taskVersion.isBlank()
            || configVersion == null || configVersion.isBlank()) {
            throw new IllegalArgumentException("Heartbeat coordination versions are invalid");
        }
        boolean taskVersionChanged = !taskVersion.equals(currentTaskVersion);
        boolean periodicTaskPullDue = taskPullIntervalMillis > 0
                                      && now - lastTaskPullAt >= taskPullIntervalMillis;
        return new CoordinationActions(
                taskVersionChanged || periodicTaskPullDue,
                !configVersion.equals(currentConfigVersion),
                now >= nextStatisticsAt,
                taskVersion,
                configVersion
        );
    }

    record CoordinationActions(
            boolean pullTasks,
            boolean refreshConfiguration,
            boolean reportStatistics,
            String taskVersion,
            String configVersion
    ) { }

    private Future<JsonObject> collect(String nodeId) {
        Future<Long> reserved = taskRepository == null
                ? Future.succeededFuture(0L)
                : taskRepository.reservedBytes();
        Future<Integer> activeTelegram = taskRepository == null
                ? Future.succeededFuture(0)
                : taskRepository.activeTaskCount(TelegramBootstrapTask.TYPE);
        Future<Integer> activeTorrent = taskRepository == null
                ? Future.succeededFuture(0)
                : taskRepository.activeTaskCount(TorrentDownloadTask.TYPE);
        Future<Integer> activeSeeds = torrentRepository == null
                ? Future.succeededFuture(0)
                : torrentRepository.countByStatuses(java.util.List.of("SEEDING"));
        return Future.all(reserved, activeTelegram, activeTorrent, activeSeeds)
                .compose(capacity -> vertx.executeBlocking(() -> {
                    Files.createDirectories(configuration.sharedRoot());
                    long freeDiskBytes = Files.getFileStore(configuration.sharedRoot()).getUsableSpace();
                    String checkedAt = DateTimeFormatter.ISO_INSTANT.format(clock.instant());
                    boolean torrentEnabled = torrentConfiguration != null && torrentConfiguration.enabled();
                    JsonArray taskCapabilities = new JsonArray().add(TelegramBootstrapTask.TYPE);
                    JsonArray torrentCapabilities = new JsonArray();
                    if (torrentEnabled) {
                        taskCapabilities.add(TorrentDownloadTask.TYPE);
                        TorrentControlTask.TYPES.stream().sorted().forEach(taskCapabilities::add);
                        torrentCapabilities.add(V1TorrentService.TORRENT_VERSION);
                    }
                    return new JsonObject()
                            .put("nodeId", nodeId)
                            .put("agentVersion", BuildInfo.VERSION)
                            .put("contractVersions", new JsonArray().add("1.0"))
                            .put("taskCapabilities", taskCapabilities)
                            .put("torrentCapabilities", torrentCapabilities)
                            .put("telegramAvailable", TelegramVerticles.hasAuthorized())
                            .put("torrentEnabled", torrentEnabled)
                            .put("freeDiskBytes", Long.toUnsignedString(Math.max(0, freeDiskBytes)))
                            .put("reservedDiskBytes", Long.toString(capacity.<Long>resultAt(0)))
                            .put("activeTelegramDownloads", capacity.<Integer>resultAt(1))
                            .put("activeTorrentDownloads", capacity.<Integer>resultAt(2))
                            .put("activeSeeds", capacity.<Integer>resultAt(3))
                            .put("peerNetwork", new JsonObject()
                                    .put("listenPort", Config.PEER_LISTEN_PORT)
                                    .put("ipv4Reachable", torrentEnabled
                                                          && torrentConfiguration.ipv4Reachable())
                                    .put("ipv6Reachable", torrentEnabled
                                                          && torrentConfiguration.ipv6Reachable())
                                    .put("mappingMethod", torrentEnabled
                                            ? torrentConfiguration.mappingMethod() : "NONE")
                                    .put("lastCheckedAt", checkedAt));
                }));
    }
}
