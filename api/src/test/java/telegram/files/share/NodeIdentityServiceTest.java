package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import telegram.files.repository.SeedNodeIdentityRecord;
import telegram.files.repository.SeedNodeIdentityRepository;
import telegram.files.share.HttpSeedCoordinatorClient.SeedProtocolException;
import telegram.files.share.security.AesGcmSecretStore;
import telegram.files.share.security.SecretStore;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class NodeIdentityServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void deviceFlowPersistsOnlyEncryptedCredentials(Vertx vertx, VertxTestContext context) {
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.responses.add(new JsonObject()
                .put("deviceCode", "d".repeat(43))
                .put("userCode", "ABCD-EFGH")
                .put("verificationUri", "https://seed.example.test/device")
                .put("verificationUriComplete", "https://seed.example.test/device?user_code=ABCD-EFGH")
                .put("expiresIn", 600)
                .put("interval", 60)
                .put("authorizationId", "authorization-one")
                .put("challenge", "c".repeat(43))
                .put("challengeExpiresAt", "2026-07-14T00:10:00Z")
                .put("signatureAlgorithm", "Ed25519")
                .put("canonicalizationVersion", InstallationIdentityService.CANONICALIZATION_VERSION));
        client.responses.add(new JsonObject().put("accepted", true));
        client.responses.add(new JsonObject().put("status", "pending").put("interval", 60));
        client.responses.add(approved("node-one", "a", "r"));
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        service.authorize("  Home   Node ")
                .compose(status -> {
                    context.verify(() -> {
                        assertEquals("pending", status.getString("status"));
                        assertEquals("ABCD-EFGH", status.getString("userCode"));
                        assertFalse(status.containsKey("deviceCode"));
                    });
                    return service.pollNow();
                })
                .compose(_ -> service.pollNow())
                .compose(_ -> service.status())
                .onComplete(context.succeeding(status -> context.verify(() -> {
                    assertEquals("BOUND", status.getString("status"));
                    assertEquals("Home Node", status.getString("nodeName"));
                    assertNotNull(repository.identity);
                    assertFalse(repository.identity.credentialCiphertext().contains("a".repeat(32)));
                    assertFalse(repository.identity.credentialCiphertext().contains("r".repeat(32)));
                    NodeCredentialBundle decrypted = NodeCredentialBundle.decrypt(
                            repository.identity.credentialCiphertext(),
                            secretStore()
                    );
                    assertEquals("a".repeat(32), decrypted.accessToken());
                    assertEquals("t".repeat(32), decrypted.trackerCredential());
                    service.close();
                    context.completeNow();
                })));
    }

    @Test
    void localDeviceFlowAcceptsSameOriginHttpVerificationUri(
            Vertx vertx,
            VertxTestContext context
    ) {
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.responses.add(new JsonObject()
                .put("deviceCode", "d".repeat(43))
                .put("userCode", "ABCD-EFGH")
                .put("verificationUri", "http://localhost:7654/device")
                .put("verificationUriComplete", "http://localhost:7654/device?user_code=ABCD-EFGH")
                .put("expiresIn", 600)
                .put("interval", 60)
                .put("authorizationId", "authorization-local")
                .put("challenge", "c".repeat(43))
                .put("challengeExpiresAt", "2026-07-14T00:10:00Z")
                .put("signatureAlgorithm", "Ed25519")
                .put("canonicalizationVersion", InstallationIdentityService.CANONICALIZATION_VERSION));
        client.responses.add(new JsonObject().put("accepted", true));
        ShareConfiguration configuration = new ShareConfiguration(
                true,
                URI.create("http://localhost:7654"),
                temporaryDirectory.resolve("shared-local").toAbsolutePath(),
                2,
                Duration.ofSeconds(30),
                5,
                true
        );
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration,
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        service.authorize("Local Node")
                .onComplete(context.succeeding(status -> context.verify(() -> {
                    assertEquals("pending", status.getString("status"));
                    assertEquals("http://localhost:7654/device", status.getString("verificationUri"));
                    service.close();
                    context.completeNow();
                })));
    }

    @Test
    void refreshAtomicallyReplacesBothTokensAndUnbindRevokesPlatformNode(
            Vertx vertx,
            VertxTestContext context
    ) {
        long now = Instant.parse("2026-07-14T00:00:00Z").toEpochMilli();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        repository.identity = identity(
                "node-one",
                new NodeCredentialBundle("o".repeat(32), "p".repeat(32), now + 1_000),
                now
        );
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.responses.add(approved("node-one", "n", "q"));
        client.responses.add(new JsonObject().put("success", true));
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );

        service.access()
                .compose(access -> {
                    context.verify(() -> {
                        assertEquals("n".repeat(32), access.accessToken());
                        assertEquals("t".repeat(32), access.trackerCredential());
                        assertEquals("refresh_token", client.lastBody.getString("grantType"));
                        assertEquals("p".repeat(32), client.lastBody.getString("refreshToken"));
                        assertTrue(client.lastBody.getBoolean("requireTrackerCredential"));
                    });
                    return service.unbind();
                })
                .onComplete(context.succeeding(_ -> context.verify(() -> {
                    assertNull(repository.identity);
                    assertEquals("DELETE", client.lastMethod);
                    assertEquals("/api/v1/nodes/node-one", client.lastPath);
                    assertTrue(client.lastHeaders.get("Authorization").startsWith("Bearer "));
                    context.completeNow();
                })));
    }

    @Test
    void concurrentAccessSharesOneRefreshRequest(Vertx vertx, VertxTestContext context) {
        long now = Instant.parse("2026-07-14T00:00:00Z").toEpochMilli();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        repository.identity = identity(
                "node-one",
                new NodeCredentialBundle("o".repeat(32), "p".repeat(32), now + 1_000),
                now
        );
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.deferredRefresh = Promise.promise();
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );

        Future<NodeIdentityService.NodeAccess> first = service.access();
        Future<NodeIdentityService.NodeAccess> second = service.access();
        context.verify(() -> assertEquals(1, client.refreshRequestCount));
        client.deferredRefresh.complete(approved("node-one", "n", "q"));

        Future.all(first, second)
                .onComplete(context.succeeding(accesses -> context.verify(() -> {
                    assertEquals("n".repeat(32), accesses.<NodeIdentityService.NodeAccess>resultAt(0).accessToken());
                    assertEquals("n".repeat(32), accesses.<NodeIdentityService.NodeAccess>resultAt(1).accessToken());
                    assertEquals(1, client.refreshRequestCount);
                    assertNotNull(repository.identity);
                    service.close();
                    context.completeNow();
                })));
    }

    @Test
    void refreshPreservesExistingTrackerCredentialWhenReplacementIsNotRequired(
            Vertx vertx,
            VertxTestContext context
    ) {
        long now = Instant.parse("2026-07-14T00:00:00Z").toEpochMilli();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        repository.identity = identity(
                "node-one",
                new NodeCredentialBundle(
                        "o".repeat(32), "p".repeat(32), now + 1_000, "z".repeat(32)
                ),
                now
        );
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.responses.add(approvedWithoutTracker("node-one", "n", "q"));
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );

        service.access().onComplete(context.succeeding(access -> context.verify(() -> {
            assertEquals("z".repeat(32), access.trackerCredential());
            assertFalse(client.lastBody.getBoolean("requireTrackerCredential"));
            service.close();
            context.completeNow();
        })));
    }

    @Test
    void genericUnauthorizedRefreshKeepsLocalBinding(
            Vertx vertx,
            VertxTestContext context
    ) {
        long now = Instant.parse("2026-07-14T00:00:00Z").toEpochMilli();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        repository.identity = identity(
                "node-one",
                new NodeCredentialBundle("o".repeat(32), "p".repeat(32), now + 1_000),
                now
        );
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.failure = new SeedProtocolException(
                "Platform request failed", 401, "UNAUTHENTICATED", "request-one"
        );
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );

        service.access().onComplete(context.failing(_ ->
                service.status().onComplete(context.succeeding(status -> context.verify(() -> {
                    assertEquals("BOUND", status.getString("status"));
                    assertNotNull(repository.identity);
                    service.close();
                    context.completeNow();
                })))));
    }

    @Test
    void explicitNodeRevocationClearsLocalBinding(
            Vertx vertx,
            VertxTestContext context
    ) {
        long now = Instant.parse("2026-07-14T00:00:00Z").toEpochMilli();
        InMemoryIdentityRepository repository = new InMemoryIdentityRepository();
        repository.identity = identity(
                "node-one",
                new NodeCredentialBundle("o".repeat(32), "p".repeat(32), now + 1_000),
                now
        );
        StubCoordinatorClient client = new StubCoordinatorClient();
        client.failure = new SeedProtocolException(
                "Platform request failed", 401, "NODE_REVOKED", "request-one"
        );
        NodeIdentityService service = new NodeIdentityService(
                vertx,
                configuration(),
                client,
                repository,
                secretStore(),
                Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
        );

        service.access().onComplete(context.failing(_ ->
                service.status().onComplete(context.succeeding(status -> context.verify(() -> {
                    assertEquals("UNBOUND", status.getString("status"));
                    assertNull(repository.identity);
                    service.close();
                    context.completeNow();
                })))));
    }

    private ShareConfiguration configuration() {
        return new ShareConfiguration(
                true,
                URI.create("https://seed.example.test"),
                temporaryDirectory.resolve("shared").toAbsolutePath(),
                2,
                Duration.ofSeconds(30),
                5,
                false
        );
    }

    private SeedNodeIdentityRecord identity(String nodeId, NodeCredentialBundle bundle, long now) {
        return new SeedNodeIdentityRecord(
                "https://seed.example.test",
                nodeId,
                "Home Node",
                bundle.encrypt(secretStore()),
                bundle.accessTokenExpiresAt(),
                null,
                "BOUND",
                now,
                now
        );
    }

    private static JsonObject approved(String nodeId, String accessCharacter, String refreshCharacter) {
        return approvedWithoutTracker(nodeId, accessCharacter, refreshCharacter)
                .put("trackerCredential", "t".repeat(32));
    }

    private static JsonObject approvedWithoutTracker(
            String nodeId,
            String accessCharacter,
            String refreshCharacter
    ) {
        return new JsonObject()
                .put("status", "approved")
                .put("nodeId", nodeId)
                .put("accessToken", accessCharacter.repeat(32))
                .put("accessTokenExpiresIn", 900)
                .put("refreshToken", refreshCharacter.repeat(32));
    }

    private static SecretStore secretStore() {
        return new AesGcmSecretStore(
                Map.of(1, new SecretKeySpec(new byte[32], "AES")),
                1
        );
    }

    private static final class InMemoryIdentityRepository implements SeedNodeIdentityRepository {
        private SeedNodeIdentityRecord identity;

        @Override
        public Future<SeedNodeIdentityRecord> getCurrent() {
            return Future.succeededFuture(identity);
        }

        @Override
        public Future<Void> save(SeedNodeIdentityRecord value) {
            identity = value;
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> updateHeartbeat(long receivedAt) {
            if (identity != null) {
                identity = new SeedNodeIdentityRecord(
                        identity.platformUrl(), identity.nodeId(), identity.nodeName(),
                        identity.credentialCiphertext(), identity.tokenExpireAt(), receivedAt,
                        identity.bindingStatus(), identity.createdAt(), receivedAt
                );
            }
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> updateNodeName(String nodeName, long updatedAt) {
            if (identity != null) {
                identity = new SeedNodeIdentityRecord(
                        identity.platformUrl(), identity.nodeId(), nodeName,
                        identity.credentialCiphertext(), identity.tokenExpireAt(), identity.lastHeartbeatAt(),
                        identity.bindingStatus(), identity.createdAt(), updatedAt
                );
            }
            return Future.succeededFuture();
        }

        @Override
        public Future<Void> clear() {
            identity = null;
            return Future.succeededFuture();
        }
    }

    private static final class StubCoordinatorClient implements SeedCoordinatorClient {
        private final Queue<JsonObject> responses = new ArrayDeque<>();

        private JsonObject lastBody;

        private String lastMethod;

        private String lastPath;

        private Map<String, String> lastHeaders = Map.of();

        private Promise<JsonObject> deferredRefresh;

        private Throwable failure;

        private int refreshRequestCount;

        @Override
        public Future<JsonObject> get(String path, Map<String, String> headers) {
            return respond("GET", path, null, headers);
        }

        @Override
        public Future<JsonObject> post(String path, JsonObject body, Map<String, String> headers) {
            if (body != null && "refresh_token".equals(body.getString("grantType"))) {
                refreshRequestCount++;
                if (deferredRefresh != null) {
                    lastMethod = "POST";
                    lastPath = path;
                    lastBody = body;
                    lastHeaders = headers;
                    return deferredRefresh.future();
                }
            }
            return respond("POST", path, body, headers);
        }

        @Override
        public Future<JsonObject> put(String path, JsonObject body, Map<String, String> headers) {
            return respond("PUT", path, body, headers);
        }

        @Override
        public Future<JsonObject> delete(String path, Map<String, String> headers) {
            return respond("DELETE", path, null, headers);
        }

        private Future<JsonObject> respond(
                String method,
                String path,
                JsonObject body,
                Map<String, String> headers
        ) {
            lastMethod = method;
            lastPath = path;
            lastBody = body;
            lastHeaders = headers;
            if (failure != null) {
                Throwable currentFailure = failure;
                failure = null;
                return Future.failedFuture(currentFailure);
            }
            JsonObject response = responses.poll();
            return response == null
                    ? Future.failedFuture("No stub response")
                    : Future.succeededFuture(response);
        }
    }
}
