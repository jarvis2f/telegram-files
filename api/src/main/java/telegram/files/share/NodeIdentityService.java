package telegram.files.share;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.BuildInfo;
import telegram.files.repository.SeedNodeIdentityRecord;
import telegram.files.repository.SeedNodeIdentityRepository;
import telegram.files.repository.InstallationIdentityRecord;
import telegram.files.repository.InstallationIdentityRepository;
import telegram.files.share.HttpSeedCoordinatorClient.SeedProtocolException;
import telegram.files.share.security.SecretStore;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

public final class NodeIdentityService {

    private static final Log log = LogFactory.get();

    private static final Duration REFRESH_AHEAD = Duration.ofMinutes(1);

    private final Vertx vertx;

    private final ShareConfiguration configuration;

    private final SeedCoordinatorClient client;

    private final SeedNodeIdentityRepository repository;

    private final InstallationIdentityService installationIdentityService;

    private final SecretStore secretStore;

    private final Clock clock;

    private final Object refreshMonitor = new Object();

    private Future<SeedNodeIdentityRecord> refreshInFlight;

    private DeviceAuthorization authorization;

    private long pollTimerId = -1;

    public NodeIdentityService(
            Vertx vertx,
            ShareConfiguration configuration,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            InstallationIdentityService installationIdentityService,
            SecretStore secretStore
    ) {
        this(vertx, configuration, client, repository, installationIdentityService,
                secretStore, Clock.systemUTC());
    }

    public NodeIdentityService(
            Vertx vertx,
            ShareConfiguration configuration,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            SecretStore secretStore
    ) {
        this(vertx, configuration, client, repository,
                new InstallationIdentityService(new MemoryInstallationIdentityRepository(), secretStore),
                secretStore, Clock.systemUTC());
    }

    NodeIdentityService(
            Vertx vertx,
            ShareConfiguration configuration,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            SecretStore secretStore,
            Clock clock
    ) {
        this(vertx, configuration, client, repository,
                new InstallationIdentityService(new MemoryInstallationIdentityRepository(), secretStore, clock),
                secretStore, clock);
    }

    NodeIdentityService(
            Vertx vertx,
            ShareConfiguration configuration,
            SeedCoordinatorClient client,
            SeedNodeIdentityRepository repository,
            InstallationIdentityService installationIdentityService,
            SecretStore secretStore,
            Clock clock
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.client = Objects.requireNonNull(client, "client");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.installationIdentityService = Objects.requireNonNull(
                installationIdentityService, "installationIdentityService"
        );
        this.secretStore = Objects.requireNonNull(secretStore, "secretStore");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<JsonObject> authorize(String rawNodeName) {
        String nodeName = normalizeNodeName(rawNodeName);
        return repository.getCurrent().compose(current -> {
            if (current != null) {
                return Future.failedFuture(new IllegalStateException("A platform node is already bound"));
            }
            if (authorization != null && !authorization.terminal()) {
                return Future.succeededFuture(publicAuthorizationStatus());
            }
            return installationIdentityService.loadOrCreate().compose(installation -> {
                JsonObject request = new JsonObject()
                        .put("nodeName", nodeName)
                        .put("agentVersion", BuildInfo.VERSION)
                        .put("contractVersions", new JsonArray().add("1.0"))
                        .put("capabilities", new JsonArray())
                        .put("identityVersion", installation.identityVersion())
                        .put("installationPublicKey", installation.publicKey())
                        .put("installationFingerprint", installation.fingerprint());
                return client.post("/api/v1/device/authorize", request, Map.of())
                        .compose(response -> {
                            authorization = DeviceAuthorization.from(
                                    response,
                                    nodeName,
                                    installation,
                                    installationIdentityService,
                                    clock.millis(),
                                    configuration.platformUri()
                            );
                            JsonObject proof = new JsonObject()
                                    .put("authorizationId", authorization.authorizationId())
                                    .put("challenge", authorization.challenge())
                                    .put("installationSignature", authorization.installationSignature());
                            return client.post("/api/v1/device/signature", proof, Map.of())
                                    .map(acknowledgement -> {
                                        if (!acknowledgement.getBoolean("accepted", false)) {
                                            throw new IllegalStateException(
                                                    "Platform rejected the installation signature"
                                            );
                                        }
                                        schedulePoll(authorization.intervalSeconds());
                                        return publicAuthorizationStatus();
                                    });
                        });
            });
        });
    }

    public Future<JsonObject> status() {
        return repository.getCurrent().map(identity -> {
            if (identity != null) {
                return new JsonObject()
                        .put("status", identity.bindingStatus())
                        .put("nodeId", identity.nodeId())
                        .put("nodeName", identity.nodeName())
                        .put("platformUrl", identity.platformUrl())
                        .put("tokenExpireAt", identity.tokenExpireAt())
                        .put("lastHeartbeatAt", identity.lastHeartbeatAt());
            }
            return publicAuthorizationStatus();
        });
    }

    public Future<Void> pollNow() {
        DeviceAuthorization current = authorization;
        if (current == null || current.terminal()) {
            return Future.succeededFuture();
        }
        cancelPollTimer();
        JsonObject request = new JsonObject()
                .put("grantType", "device_code")
                .put("deviceCode", current.deviceCode())
                .put("authorizationId", current.authorizationId())
                .put("challenge", current.challenge())
                .put("installationSignature", current.installationSignature());
        return client.post("/api/v1/device/token", request, Map.of())
                .compose(response -> handlePollResponse(current, response))
                .recover(failure -> {
                    if (failure instanceof SeedProtocolException protocol
                        && (protocol.statusCode() == 401 || "NODE_REVOKED".equals(protocol.errorCode()))) {
                        authorization = current.withStatus("expired", true);
                        return Future.succeededFuture();
                    }
                    int retryInterval = Math.min(current.intervalSeconds() * 2, 60);
                    authorization = current.withInterval(retryInterval);
                    schedulePoll(retryInterval);
                    log.warn("Device authorization poll failed: {}", failure.getMessage());
                    return Future.succeededFuture();
                });
    }

    public Future<Void> cancel() {
        cancelPollTimer();
        if (authorization != null && !authorization.terminal()) {
            authorization = authorization.withStatus("cancelled", true);
        }
        return Future.succeededFuture();
    }

    public Future<NodeAccess> access() {
        return refreshIfRequired().compose(identity -> {
            if (identity == null) {
                return Future.failedFuture(new IllegalStateException("No platform node is bound"));
            }
            NodeCredentialBundle credentials = NodeCredentialBundle.decrypt(
                    identity.credentialCiphertext(),
                    secretStore
            );
            return Future.succeededFuture(new NodeAccess(
                    identity, credentials.accessToken(), credentials.trackerCredential()
            ));
        });
    }

    public Future<Void> refreshAfterUnauthorized() {
        return refreshOnce(true).mapEmpty();
    }

    public Future<Void> replaceTrackerCredential(String trackerCredential) {
        if (trackerCredential == null || !trackerCredential.matches("[A-Za-z0-9_-]{32,1024}")) {
            return Future.failedFuture(new IllegalArgumentException("Tracker credential is invalid"));
        }
        return repository.getCurrent().compose(identity -> {
            if (identity == null) return Future.failedFuture(new IllegalStateException("No platform node is bound"));
            NodeCredentialBundle current = NodeCredentialBundle.decrypt(identity.credentialCiphertext(), secretStore);
            NodeCredentialBundle updated = new NodeCredentialBundle(
                    current.accessToken(), current.refreshToken(), current.accessTokenExpiresAt(), trackerCredential
            );
            SeedNodeIdentityRecord replacement = new SeedNodeIdentityRecord(
                    identity.platformUrl(), identity.nodeId(), identity.nodeName(), updated.encrypt(secretStore),
                    identity.tokenExpireAt(), identity.lastHeartbeatAt(), identity.bindingStatus(),
                    identity.createdAt(), clock.millis()
            );
            return repository.save(replacement).mapEmpty();
        });
    }

    public Future<String> requestTrackerCredentialRotation(String taskId) {
        if (taskId == null || taskId.isBlank() || taskId.length() > 128) {
            return Future.failedFuture(new IllegalArgumentException("Task ID is invalid"));
        }
        return access().compose(access -> client.post(
                "/api/v1/nodes/" + access.identity().nodeId() + "/tracker-credential",
                new JsonObject().put("taskId", taskId), bearer(access.accessToken())
        )).compose(response -> {
            String credential = response.getString("trackerCredential");
            if (credential == null || !credential.matches("[A-Za-z0-9_-]{32,1024}")) {
                return Future.failedFuture(new IllegalArgumentException("Platform returned an invalid Tracker credential"));
            }
            return Future.succeededFuture(credential);
        });
    }

    public Future<Void> rename(String rawNodeName) {
        String nodeName = normalizeNodeName(rawNodeName);
        return access().compose(access -> client.put(
                        "/api/v1/nodes/" + access.identity().nodeId(),
                        new JsonObject().put("name", nodeName),
                        bearer(access.accessToken())
                ))
                .compose(_ -> repository.updateNodeName(nodeName, clock.millis()));
    }

    public Future<Void> unbind() {
        cancelPollTimer();
        return access().compose(access -> client.delete(
                        "/api/v1/nodes/" + access.identity().nodeId(),
                        bearer(access.accessToken())
                ))
                .mapEmpty()
                .recover(failure -> failure instanceof SeedProtocolException protocol
                                    && (protocol.statusCode() == 401 || "NODE_REVOKED".equals(protocol.errorCode()))
                        ? Future.succeededFuture()
                        : Future.failedFuture(failure))
                .compose(_ -> repository.clear());
    }

    public Future<Void> clearRevoked() {
        cancelPollTimer();
        return repository.clear();
    }

    public void close() {
        cancelPollTimer();
        authorization = null;
    }

    private Future<SeedNodeIdentityRecord> refreshIfRequired() {
        return repository.getCurrent().compose(identity -> {
            if (identity == null || identity.tokenExpireAt() > clock.millis() + REFRESH_AHEAD.toMillis()) {
                return Future.succeededFuture(identity);
            }
            return refreshOnce(false);
        });
    }

    private Future<SeedNodeIdentityRecord> refreshOnce(boolean force) {
        Promise<SeedNodeIdentityRecord> promise;
        Future<SeedNodeIdentityRecord> flight;
        synchronized (refreshMonitor) {
            if (refreshInFlight != null) {
                return refreshInFlight;
            }
            promise = Promise.promise();
            flight = promise.future();
            refreshInFlight = flight;
        }

        refreshCurrentIdentity(force).onComplete(result -> {
            synchronized (refreshMonitor) {
                if (refreshInFlight == flight) {
                    refreshInFlight = null;
                }
            }
            promise.handle(result);
        });
        return flight;
    }

    private Future<SeedNodeIdentityRecord> refreshCurrentIdentity(boolean force) {
        return repository.getCurrent().compose(identity -> {
            if (identity == null || (!force
                                     && identity.tokenExpireAt() > clock.millis()
                                                                   + REFRESH_AHEAD.toMillis())) {
                return Future.succeededFuture(identity);
            }
            return refreshIdentity(identity);
        });
    }

    private Future<SeedNodeIdentityRecord> refreshIdentity(SeedNodeIdentityRecord identity) {
        NodeCredentialBundle current = NodeCredentialBundle.decrypt(
                identity.credentialCiphertext(),
                secretStore
        );
        JsonObject request = new JsonObject()
                .put("grantType", "refresh_token")
                .put("refreshToken", current.refreshToken())
                .put("requireTrackerCredential", current.trackerCredential() == null);
        return client.post("/api/v1/device/token", request, Map.of())
                .compose(response -> {
                    if (!"approved".equals(response.getString("status"))) {
                        return Future.failedFuture(new IllegalStateException("Platform did not rotate the refresh token"));
                    }
                    SeedNodeIdentityRecord refreshed = identityFromTokenResponse(
                            response,
                            identity.nodeName(),
                            identity.createdAt(),
                            identity.lastHeartbeatAt(),
                            current.trackerCredential()
                    );
                    return repository.save(refreshed).map(refreshed);
                })
                .recover(failure -> {
                    if (failure instanceof SeedProtocolException protocol
                        && "NODE_REVOKED".equals(protocol.errorCode())) {
                        return repository.clear().compose(_ -> Future.failedFuture(
                                new IllegalStateException("Platform node credential was revoked")
                        ));
                    }
                    return Future.failedFuture(failure);
                });
    }

    private Future<Void> handlePollResponse(DeviceAuthorization current, JsonObject response) {
        String status = response.getString("status");
        if ("pending".equals(status) || "slow_down".equals(status)) {
            int interval = response.getInteger("interval", current.intervalSeconds());
            authorization = current.withInterval(interval).withStatus(status, false);
            schedulePoll(interval);
            return Future.succeededFuture();
        }
        if ("denied".equals(status) || "expired".equals(status)) {
            authorization = current.withStatus(status, true);
            return Future.succeededFuture();
        }
        if (!"approved".equals(status)) {
            authorization = current.withStatus("error", true);
            return Future.failedFuture(new IllegalArgumentException("Unknown Device Flow status"));
        }
        SeedNodeIdentityRecord identity = identityFromTokenResponse(
                response,
                current.nodeName(),
                clock.millis(),
                null,
                null
        );
        return repository.save(identity).onSuccess(_ -> authorization = null);
    }

    private SeedNodeIdentityRecord identityFromTokenResponse(
            JsonObject response,
            String nodeName,
            long createdAt,
            Long lastHeartbeatAt,
            String existingTrackerCredential
    ) {
        String nodeId = response.getString("nodeId");
        String accessToken = response.getString("accessToken");
        String refreshToken = response.getString("refreshToken");
        String trackerCredential = response.getString(
                "trackerCredential", existingTrackerCredential
        );
        int expiresIn = response.getInteger("accessTokenExpiresIn", 0);
        if (nodeId == null || nodeId.isBlank() || expiresIn < 1 || trackerCredential == null) {
            throw new IllegalArgumentException("Platform returned an invalid credential response");
        }
        long now = clock.millis();
        NodeCredentialBundle credentials = new NodeCredentialBundle(
                accessToken,
                refreshToken,
                now + Duration.ofSeconds(expiresIn).toMillis(),
                trackerCredential
        );
        return new SeedNodeIdentityRecord(
                configuration.platformUri().toString(),
                nodeId,
                nodeName,
                credentials.encrypt(secretStore),
                credentials.accessTokenExpiresAt(),
                lastHeartbeatAt,
                "BOUND",
                createdAt,
                now
        );
    }

    private void schedulePoll(int intervalSeconds) {
        cancelPollTimer();
        pollTimerId = vertx.setTimer(Duration.ofSeconds(intervalSeconds).toMillis(), _ -> pollNow());
    }

    private void cancelPollTimer() {
        if (pollTimerId >= 0) {
            vertx.cancelTimer(pollTimerId);
            pollTimerId = -1;
        }
    }

    private JsonObject publicAuthorizationStatus() {
        if (authorization == null) {
            return new JsonObject().put("status", "UNBOUND");
        }
        JsonObject status = new JsonObject()
                .put("status", authorization.status())
                .put("userCode", authorization.userCode())
                .put("verificationUri", authorization.verificationUri().toString())
                .put("verificationUriComplete", authorization.verificationUriComplete().toString())
                .put("expiresAt", authorization.expiresAt())
                .put("interval", authorization.intervalSeconds());
        if (authorization.terminal()) {
            status.remove("userCode");
            status.remove("verificationUriComplete");
        }
        return status;
    }

    private static String normalizeNodeName(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Node name is required");
        }
        String normalized = raw.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty() || normalized.length() > 128 || normalized.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw new IllegalArgumentException("Node name must contain 1 to 128 safe characters");
        }
        return normalized;
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    public record NodeAccess(
            SeedNodeIdentityRecord identity,
            String accessToken,
            String trackerCredential
    ) {
        public NodeAccess(SeedNodeIdentityRecord identity, String accessToken) {
            this(identity, accessToken, null);
        }
    }

    private record DeviceAuthorization(
            String deviceCode,
            String userCode,
            URI verificationUri,
            URI verificationUriComplete,
            long expiresAt,
            int intervalSeconds,
            String nodeName,
            String status,
            boolean terminal,
            String authorizationId,
            String challenge,
            String installationSignature
    ) {
        private static DeviceAuthorization from(
                JsonObject response,
                String nodeName,
                InstallationIdentityRecord installation,
                InstallationIdentityService identityService,
                long now,
                URI platformUri
        ) {
            String deviceCode = response.getString("deviceCode");
            String userCode = response.getString("userCode");
            int expiresIn = response.getInteger("expiresIn", 0);
            int interval = response.getInteger("interval", 0);
            String authorizationId = response.getString("authorizationId");
            String challenge = response.getString("challenge");
            String challengeExpiresAt = response.getString("challengeExpiresAt");
            String algorithm = response.getString("signatureAlgorithm");
            String canonicalization = response.getString("canonicalizationVersion");
            URI verificationUri = URI.create(response.getString("verificationUri", ""));
            URI complete = URI.create(response.getString("verificationUriComplete", ""));
            if (deviceCode == null || deviceCode.length() < 32 || userCode == null || userCode.isBlank()
                || expiresIn < 60 || interval < 1 || authorizationId == null
                || challenge == null || challengeExpiresAt == null
                || !"Ed25519".equals(algorithm)
                || !InstallationIdentityService.CANONICALIZATION_VERSION.equals(canonicalization)
                || !isDeviceVerificationUri(verificationUri, platformUri, false)
                || !isDeviceVerificationUri(complete, platformUri, true)) {
                throw new IllegalArgumentException("Platform returned an invalid Device Flow response");
            }
            return new DeviceAuthorization(
                    deviceCode,
                    userCode,
                    verificationUri,
                    complete,
                    now + Duration.ofSeconds(expiresIn).toMillis(),
                    interval,
                    nodeName,
                    "pending",
                    false,
                    authorizationId,
                    challenge,
                    identityService.sign(installation, authorizationId, challenge, challengeExpiresAt)
            );
        }

        private static boolean isDeviceVerificationUri(URI candidate, URI platformUri, boolean allowQuery) {
            return candidate.getScheme() != null
                   && candidate.getScheme().equalsIgnoreCase(platformUri.getScheme())
                   && candidate.getHost() != null
                   && candidate.getHost().equalsIgnoreCase(platformUri.getHost())
                   && candidate.getPort() == platformUri.getPort()
                   && candidate.getUserInfo() == null
                   && "/device".equals(candidate.getPath())
                   && (allowQuery || candidate.getQuery() == null)
                   && candidate.getFragment() == null;
        }

        private DeviceAuthorization withInterval(int interval) {
            if (interval < 1 || interval > 60) {
                throw new IllegalArgumentException("Device polling interval is invalid");
            }
            return new DeviceAuthorization(
                    deviceCode, userCode, verificationUri, verificationUriComplete,
                    expiresAt, interval, nodeName, status, terminal,
                    authorizationId, challenge, installationSignature
            );
        }

        private DeviceAuthorization withStatus(String value, boolean isTerminal) {
            return new DeviceAuthorization(
                    deviceCode, userCode, verificationUri, verificationUriComplete,
                    expiresAt, intervalSeconds, nodeName, value, isTerminal,
                    authorizationId, challenge, installationSignature
            );
        }
    }

    private static final class MemoryInstallationIdentityRepository
            implements InstallationIdentityRepository {
        private InstallationIdentityRecord current;

        @Override
        public Future<InstallationIdentityRecord> getCurrent() {
            return Future.succeededFuture(current);
        }

        @Override
        public Future<InstallationIdentityRecord> saveIfAbsent(InstallationIdentityRecord identity) {
            if (current == null) current = identity;
            return Future.succeededFuture(current);
        }
    }
}
