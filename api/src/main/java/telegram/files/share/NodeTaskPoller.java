package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.NodeTaskExecutionRecord;
import telegram.files.repository.NodeTaskRepository;
import telegram.files.share.HttpSeedCoordinatorClient.SeedProtocolException;
import telegram.files.share.TelegramBootstrapTask.UnsupportedTaskException;
import telegram.files.share.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class NodeTaskPoller {

    private static final Log log = LogFactory.get();

    private final Vertx vertx;

    private final ShareConfiguration configuration;

    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    private final NodeTaskRepository repository;

    private final TelegramBootstrapExecutor executor;

    private final TorrentDownloadExecutor torrentDownloadExecutor;

    private final PrivateTorrentService privateTorrentService;

    private final TorrentControlExecutor torrentControlExecutor;

    private final SecretJsonCodec secretJsonCodec;

    private final Clock clock;

    private final AtomicBoolean polling = new AtomicBoolean();

    private final Set<String> activeTasks = ConcurrentHashMap.newKeySet();

    private long timerId = -1;

    private boolean running;

    public NodeTaskPoller(
            Vertx vertx,
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            NodeTaskRepository repository,
            TelegramBootstrapExecutor executor,
            TorrentDownloadExecutor torrentDownloadExecutor,
            PrivateTorrentService privateTorrentService,
            TorrentControlExecutor torrentControlExecutor,
            SecretStore secretStore
    ) {
        this(vertx, configuration, identityService, client, repository, executor,
                torrentDownloadExecutor, privateTorrentService, torrentControlExecutor,
                secretStore, Clock.systemUTC());
    }

    private NodeTaskPoller(
            Vertx vertx,
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            NodeTaskRepository repository,
            TelegramBootstrapExecutor executor,
            TorrentDownloadExecutor torrentDownloadExecutor,
            PrivateTorrentService privateTorrentService,
            TorrentControlExecutor torrentControlExecutor,
            SecretStore secretStore,
            Clock clock
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.identityService = Objects.requireNonNull(identityService, "identityService");
        this.client = Objects.requireNonNull(client, "client");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.torrentDownloadExecutor = torrentDownloadExecutor;
        this.privateTorrentService = privateTorrentService;
        this.torrentControlExecutor = torrentControlExecutor;
        this.secretJsonCodec = new SecretJsonCodec(secretStore);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        recover();
    }

    public void stop() {
        running = false;
        if (timerId >= 0) {
            vertx.cancelTimer(timerId);
            timerId = -1;
        }
    }

    public Future<Void> runOnce(boolean pullTasks) {
        if (!polling.compareAndSet(false, true)) {
            return Future.succeededFuture();
        }
        return flushReports()
                .compose(_ -> recover())
                .compose(_ -> pullTasks ? pull() : Future.succeededFuture())
                .recover(failure -> {
                    handleProtocolFailure(failure);
                    if (!(failure instanceof IllegalStateException
                          && "No platform node is bound".equals(failure.getMessage()))) {
                        log.warn("Node task polling failed: {}", safeMessage(failure));
                    }
                    return Future.failedFuture(failure);
                })
                .eventually(() -> {
                    polling.set(false);
                    return Future.succeededFuture();
                });
    }

    private Future<Void> recover() {
        return detached(repository.listRecoverable(configuration.concurrency() * 4))
                .compose(records -> {
                    List<Future<Void>> futures = new ArrayList<>();
                    for (NodeTaskExecutionRecord record : records) {
                        try {
                            futures.add(startExecution(
                                    NodeTaskEnvelope.fromJson(
                                            secretJsonCodec.decrypt(record.envelopeCiphertext())
                                    ),
                                    record
                            ));
                        } catch (RuntimeException failure) {
                            futures.add(repository.markFailurePending(
                                    record.taskId(), "VALIDATION_FAILED", clock.millis()
                            ).mapEmpty());
                        }
                    }
                    return futures.isEmpty() ? Future.succeededFuture() : Future.all(futures).mapEmpty();
                });
    }

    private Future<Void> pull() {
        if (activeTasks.size() >= configuration.concurrency()) {
            return Future.succeededFuture();
        }
        int limit = Math.max(1, configuration.concurrency() - activeTasks.size());
        return identityService.access().compose(access -> client.getArray(
                        "/api/v1/nodes/tasks/pull?limit=" + limit,
                        authorization(access.accessToken())
                )
                .compose(tasks -> handlePulled(tasks, access.accessToken())));
    }

    private Future<Void> handlePulled(JsonArray envelopes, String accessToken) {
        List<Future<Void>> futures = new ArrayList<>();
        for (Object item : envelopes) {
            if (!(item instanceof JsonObject envelope)) {
                continue;
            }
            try {
                NodeTaskEnvelope task = NodeTaskEnvelope.fromJson(envelope);
                long now = clock.millis();
                NodeTaskExecutionRecord execution = new NodeTaskExecutionRecord(
                        task.taskId(), task.attemptId(), task.taskType(),
                        task.schemaVersion(), task.payloadDigest(),
                        secretJsonCodec.encrypt(envelope), "PERSISTED", -1, -1,
                        null, null, null, now, now, 0
                );
                futures.add(detached(repository.persist(
                                execution,
                                task.reservedBytes(),
                                Instant.parse(task.deadlineAt()).toEpochMilli()
                        ))
                        .compose(stored -> startExecution(task, stored)));
            } catch (UnsupportedTaskException unsupported) {
                futures.add(reportUnsupported(envelope, unsupported.errorCode(), accessToken));
            } catch (RuntimeException invalid) {
                futures.add(reportUnsupported(envelope, "VALIDATION_FAILED", accessToken));
            }
        }
        return futures.isEmpty() ? Future.succeededFuture() : Future.all(futures).mapEmpty();
    }

    private Future<Void> startExecution(
            NodeTaskEnvelope task,
            NodeTaskExecutionRecord execution
    ) {
        if (execution.terminal() || execution.pendingTerminalReport()
            || !activeTasks.add(task.taskId())) {
            return Future.succeededFuture();
        }
        Future<Void> acknowledge = "PERSISTED".equals(execution.state())
                ? acknowledge(task)
                : Future.succeededFuture();
        return acknowledge
                .compose(_ -> detached(repository.markRunning(task.taskId(), clock.millis())))
                .compose(claimed -> claimed
                        ? execute(task)
                        : Future.succeededFuture())
                .eventually(() -> {
                    activeTasks.remove(task.taskId());
                    return Future.succeededFuture();
                });
    }

    private Future<Void> acknowledge(NodeTaskEnvelope task) {
        return identityService.access()
                .compose(access -> client.post(
                        taskPath(task.taskId(), "ack"),
                        attemptBody(task),
                        authorization(access.accessToken())
                ))
                .compose(_ -> repository.markAcknowledged(task.taskId(), clock.millis()))
                .mapEmpty();
    }

    private Future<Void> execute(NodeTaskEnvelope task) {
        AtomicInteger sequence = new AtomicInteger();
        TelegramBootstrapExecutor.ProgressReporter progress = (phase, percent, completed, total) -> {
            int current = sequence.getAndIncrement();
            JsonObject progressBody = new JsonObject()
                    .put("sequence", current)
                    .put("phase", phase)
                    .put("percent", percent)
                    .put("completedBytes", Long.toString(completed))
                    .put("totalBytes", Long.toString(total));
            return detached(repository.recordProgress(
                            task.taskId(), current, progressBody.encode(), clock.millis()
                    ))
                    .compose(recorded -> recorded
                            ? flushProgress(task.taskId())
                            : Future.succeededFuture());
        };
        Future<JsonObject> execution;
        if (task instanceof TelegramBootstrapTask bootstrap) {
            execution = executor.execute(bootstrap, progress);
        } else if (task instanceof TorrentDownloadTask torrent && torrentDownloadExecutor != null) {
            execution = torrentDownloadExecutor.execute(torrent, progress);
        } else if (task instanceof TorrentControlTask control && torrentControlExecutor != null) {
            execution = torrentControlExecutor.execute(control);
        } else {
            execution = Future.failedFuture(new BootstrapExecutionException(
                    "UNSUPPORTED_TASK_TYPE", false, "Task capability is not enabled"
            ));
        }
        return execution
                .compose(result -> repository.markCompletionPending(
                        task.taskId(), result.encode(), clock.millis()
                ))
                .compose(_ -> repository.releaseReservation(task.taskId(), clock.millis()))
                .compose(_ -> flushTerminal(task.taskId()))
                .recover(failure -> {
                    BootstrapExecutionException classified = classify(failure);
                    return repository.markFailurePending(
                                    task.taskId(), classified.errorCode(), clock.millis()
                            )
                            .compose(_ -> repository.releaseReservation(task.taskId(), clock.millis()))
                            .compose(_ -> flushTerminal(task.taskId()));
                });
    }

    private Future<Void> flushReports() {
        return repository.listPendingReports(configuration.concurrency() * 8)
                .compose(records -> {
                    List<Future<Void>> reports = new ArrayList<>();
                    for (NodeTaskExecutionRecord record : records) {
                        if (record.progressSequence() > record.reportedSequence()) {
                            reports.add(flushProgress(record.taskId()));
                        }
                        if (record.pendingTerminalReport()) {
                            reports.add(flushTerminal(record.taskId()));
                        }
                    }
                    return reports.isEmpty()
                            ? Future.succeededFuture()
                            : Future.all(reports).mapEmpty();
                });
    }

    private Future<Void> flushProgress(String taskId) {
        return repository.getByTaskId(taskId).compose(record -> {
            if (record == null || record.progressJson() == null
                || record.progressSequence() <= record.reportedSequence()) {
                return Future.succeededFuture();
            }
            NodeTaskEnvelope task = task(record);
            JsonObject body = new JsonObject(record.progressJson()).mergeIn(attemptBody(task));
            int sequence = record.progressSequence();
            return identityService.access()
                    .compose(access -> client.post(
                            taskPath(taskId, "progress"),
                            body,
                            idempotentHeaders(
                                    access.accessToken(), task, "progress:" + sequence
                            )
                    ))
                    .compose(_ -> repository.markProgressReported(
                            taskId, sequence, clock.millis()
                    ))
                    .map(_ -> (Void) null)
                    .recover(failure -> deferReport(taskId, failure));
        });
    }

    private Future<Void> flushTerminal(String taskId) {
        return repository.getByTaskId(taskId).compose(record -> {
            if (record == null || !record.pendingTerminalReport()) {
                return Future.succeededFuture();
            }
            NodeTaskEnvelope task = task(record);
            boolean completed = "COMPLETED_PENDING_REPORT".equals(record.state());
            JsonObject body = completed
                    ? new JsonObject(record.resultJson())
                    : new JsonObject()
                    .put("errorCode", record.errorCode())
                    .put("retryable", retryable(record.errorCode()));
            body.mergeIn(attemptBody(task));
            String action = completed ? "complete" : "fail";
            String terminal = completed ? "COMPLETED" : "FAILED";
            return identityService.access()
                    .compose(access -> client.post(
                                    taskPath(taskId, action),
                                    body,
                                    idempotentHeaders(access.accessToken(), task, action)
                            )
                            .compose(_ -> completed
                                          && task instanceof TelegramBootstrapTask bootstrap
                                          && privateTorrentService != null
                                    ? privateTorrentService.publish(bootstrap, body, access)
                                    : Future.succeededFuture()))
                    .compose(_ -> repository.markTerminal(taskId, terminal, clock.millis()))
                    .map(_ -> (Void) null)
                    .recover(failure -> deferReport(taskId, failure));
        });
    }

    private Future<Void> deferReport(String taskId, Throwable failure) {
        handleProtocolFailure(failure);
        if (failure instanceof SeedProtocolException protocol
            && ("LEASE_EXPIRED".equals(protocol.errorCode())
                || "LEASE_MISMATCH".equals(protocol.errorCode())
                || "TASK_TERMINAL".equals(protocol.errorCode()))) {
            return repository.markTerminal(taskId, "OBSOLETE", clock.millis())
                    .compose(_ -> repository.releaseReservation(taskId, clock.millis()));
        }
        return Future.succeededFuture();
    }

    private Future<Void> reportUnsupported(JsonObject envelope, String errorCode, String accessToken) {
        String taskId = envelope.getString("taskId");
        String attemptId = envelope.getString("attemptId");
        String leaseToken = envelope.getString("leaseToken");
        if (taskId == null || attemptId == null || leaseToken == null) {
            return Future.succeededFuture();
        }
        JsonObject body = new JsonObject()
                .put("attemptId", attemptId)
                .put("leaseToken", leaseToken)
                .put("errorCode", errorCode)
                .put("retryable", false);
        return client.post(
                        taskPath(taskId, "fail"),
                        body,
                        Map.of(
                                "Authorization", "Bearer " + accessToken,
                                "Idempotency-Key", idempotency(taskId, attemptId, "unsupported")
                        )
                )
                .map(_ -> (Void) null)
                .recover(_ -> Future.<Void>succeededFuture());
    }

    private NodeTaskEnvelope task(NodeTaskExecutionRecord record) {
        return NodeTaskEnvelope.fromJson(secretJsonCodec.decrypt(record.envelopeCiphertext()));
    }

    private void handleProtocolFailure(Throwable failure) {
        if (!(failure instanceof SeedProtocolException protocol)) {
            return;
        }
        if ("NODE_REVOKED".equals(protocol.errorCode())) {
            identityService.clearRevoked().onFailure(error ->
                    log.warn("Failed to clear explicitly revoked node identity: {}",
                            error.getClass().getSimpleName()));
        } else if (protocol.statusCode() == 401) {
            identityService.refreshAfterUnauthorized().onFailure(error ->
                    log.warn("Node credential refresh failed after unauthorized task request: {}",
                            error.getClass().getSimpleName()));
        }
    }

    private static JsonObject attemptBody(NodeTaskEnvelope task) {
        return new JsonObject()
                .put("attemptId", task.attemptId())
                .put("leaseToken", task.leaseToken());
    }

    private static Map<String, String> authorization(String accessToken) {
        return Map.of("Authorization", "Bearer " + accessToken);
    }

    private static Map<String, String> idempotentHeaders(
            String accessToken,
            NodeTaskEnvelope task,
            String action
    ) {
        return Map.of(
                "Authorization", "Bearer " + accessToken,
                "Idempotency-Key", idempotency(task.taskId(), task.attemptId(), action)
        );
    }

    private static String taskPath(String taskId, String action) {
        return "/api/v1/nodes/tasks/" + taskId + "/" + action;
    }

    private static String idempotency(String taskId, String attemptId, String action) {
        try {
            return "task_" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest((taskId + '\u0000' + attemptId + '\u0000' + action)
                                    .getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BootstrapExecutionException classify(Throwable failure) {
        if (failure instanceof BootstrapExecutionException bootstrap) {
            return bootstrap;
        }
        return new BootstrapExecutionException(
                "INTERNAL_RETRYABLE", true, "Task execution failed", failure
        );
    }

    static boolean retryable(String errorCode) {
        return List.of(
                "RATE_LIMITED", "NODE_OFFLINE", "NO_REACHABLE_PEER",
                "SOURCE_UNAVAILABLE", "INSUFFICIENT_DISK", "INTERNAL_RETRYABLE"
        ).contains(errorCode);
    }

    private static String safeMessage(Throwable failure) {
        if (failure instanceof SeedProtocolException protocol) {
            return "platform status=" + protocol.statusCode() + " code=" + protocol.errorCode();
        }
        return failure.getClass().getSimpleName();
    }

    private <T> Future<T> detached(Future<T> operation) {
        Promise<T> promise = Promise.promise();
        operation.onComplete(result -> vertx.setTimer(1, _ -> promise.handle(result)));
        return promise.future();
    }
}
