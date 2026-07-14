package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SeedNodeIdentityRecord;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UnifiedFileDownloadServiceTest {

    private final TorrentRepository repository = mock(TorrentRepository.class);
    private final NodeIdentityService identityService = mock(NodeIdentityService.class);
    private final RecordingCoordinatorClient client = new RecordingCoordinatorClient();
    private final TorrentControlExecutor torrentControlExecutor = mock(TorrentControlExecutor.class);
    private final TorrentStatisticsReporter statisticsReporter = mock(TorrentStatisticsReporter.class);
    private UnifiedFileDownloadService service;

    @BeforeEach
    void setUp() {
        SeedNodeIdentityRecord identity = mock(SeedNodeIdentityRecord.class);
        when(identity.nodeId()).thenReturn("node-b");
        when(identityService.access()).thenReturn(Future.succeededFuture(
                new NodeIdentityService.NodeAccess(identity, "access-token")
        ));
        service = new UnifiedFileDownloadService(
                identityService, client, repository, torrentControlExecutor, statisticsReporter
        );
        when(statisticsReporter.runOnce()).thenReturn(Future.succeededFuture());
    }

    @Test
    void activeTorrentIsReusedWithoutCallingPlatform() {
        when(repository.listByTelegramFileUniqueIds(List.of("file-1")))
                .thenReturn(Future.succeededFuture(List.of(torrent("DOWNLOADING"))));

        assertTrue(service.downloadIfAvailable("file-1", 4096).result());
        assertTrue(client.calls.isEmpty());
    }

    @Test
    void pausedTorrentIsResumedDirectlyOnTheLocalClient() {
        when(repository.listByTelegramFileUniqueIds(List.of("file-1")))
                .thenReturn(Future.succeededFuture(List.of(torrent("PAUSED"))));
        when(torrentControlExecutor.executeLocal("resource-1", "RESUME_V1", 0))
                .thenReturn(Future.succeededFuture(new JsonObject().put("status", "SEEDING")));

        assertTrue(service.downloadIfAvailable("file-1", 4096).result());
        assertTrue(client.calls.isEmpty());
    }

    @Test
    void uploadLimitControlExecutesDirectlyOnTheLocalClient() {
        when(repository.getByResourceId("resource-1"))
                .thenReturn(Future.succeededFuture(torrent("SEEDING")));
        when(torrentControlExecutor.executeLocal(
                "resource-1", "SET_UPLOAD_LIMIT_V1", 1_048_576
        )).thenReturn(Future.succeededFuture(new JsonObject()
                .put("resourceId", "resource-1")
                .put("status", "SEEDING")));

        JsonObject result = service.controlSeedResource(
                "resource-1",
                "SET_UPLOAD_LIMIT_V1",
                1_048_576
        ).result();

        assertEquals("SEED", result.getString("route"));
        assertTrue(client.calls.isEmpty());
        verify(statisticsReporter).runOnce();
    }

    @Test
    void unavailableSeedFallsBackWithoutCreatingDownload() {
        when(repository.listByTelegramFileUniqueIds(List.of("file-1")))
                .thenReturn(Future.succeededFuture(List.of()));
        client.responses.add(new JsonObject().put("files", new JsonArray().add(
                new JsonObject().put("ptAvailable", false)
        )));

        assertFalse(service.downloadIfAvailable("file-1", 4096).result());
        assertEquals(1, client.calls.size());
        assertEquals("/api/v1/nodes/file-availability", client.calls.getFirst().path());
    }

    @Test
    void availableSeedUsesStableFingerprintAndCreatesOneDownloadRequest() {
        when(repository.listByTelegramFileUniqueIds(List.of("file-1")))
                .thenReturn(Future.succeededFuture(List.of()));
        client.responses.add(new JsonObject().put("files", new JsonArray().add(
                new JsonObject()
                        .put("resourceId", "resource-1")
                        .put("ptAvailable", true)
        )));
        client.responses.add(new JsonObject().put("id", "download-1"));

        assertTrue(service.downloadIfAvailable("file-1", 4096).result());
        assertEquals(2, client.calls.size());
        JsonObject candidate = client.calls.getFirst().body().getJsonArray("files").getJsonObject(0);
        assertEquals(UnifiedFileDownloadService.sourceFingerprint("file-1", 4096),
                candidate.getString("sourceFingerprint"));
        assertEquals("/api/v1/resources/resource-1/download", client.calls.getLast().path());
        assertEquals("node-b", client.calls.getLast().body().getString("targetNodeId"));
    }

    private static TorrentRecord torrent(String status) {
        return new TorrentRecord(
                "torrent-1", "resource-1", "a".repeat(64), "b".repeat(40),
                "meta/file.torrent", "view/file.bin", "file.bin", 4096,
                "application/octet-stream", "file-1", "SEED", null, status,
                500, 2048, 0, 0, 0, 0, "/tmp/file.bin", "https://tracker.test",
                0, 0, 0, 0, 0
        );
    }

    private static final class RecordingCoordinatorClient implements SeedCoordinatorClient {
        private final List<Call> calls = new ArrayList<>();
        private final List<JsonObject> responses = new ArrayList<>();

        @Override
        public Future<JsonObject> get(String path, Map<String, String> headers) {
            return call("GET", path, null, headers);
        }

        @Override
        public Future<JsonObject> post(String path, JsonObject body, Map<String, String> headers) {
            return call("POST", path, body, headers);
        }

        @Override
        public Future<JsonObject> put(String path, JsonObject body, Map<String, String> headers) {
            return call("PUT", path, body, headers);
        }

        @Override
        public Future<JsonObject> delete(String path, Map<String, String> headers) {
            return call("DELETE", path, null, headers);
        }

        private Future<JsonObject> call(
                String method,
                String path,
                JsonObject body,
                Map<String, String> headers
        ) {
            calls.add(new Call(method, path, body, headers));
            if (responses.isEmpty()) return Future.failedFuture("No stub response");
            return Future.succeededFuture(responses.removeFirst());
        }
    }

    private record Call(String method, String path, JsonObject body, Map<String, String> headers) { }
}
