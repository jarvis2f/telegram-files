package telegram.files.share;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TorrentDownloadTaskTest {

    @Test
    void parsesThePinnedPrivateTorrentTaskAndDetectsPayloadChanges() {
        JsonObject fixture = new JsonObject()
                .put("taskId", "task-torrent")
                .put("attemptId", "attempt-torrent")
                .put("type", TorrentDownloadTask.TYPE)
                .put("schemaVersion", 1)
                .put("leaseToken", "lease_torrent_fixture_0123456789")
                .put("deadlineAt", "2026-07-15T01:00:00Z")
                .put("payload", new JsonObject()
                        .put("resourceId", "resource-fixture")
                        .put("torrentId", "torrent-fixture")
                        .put("torrentBase64", "ZDQ6aW5mb2Rl")
                        .put("infoHashV1", "1".repeat(40))
                        .put("contentSha256", "2".repeat(64))
                        .put("fileName", "fixture.bin")
                        .put("fileSize", "1024")
                        .put("reservedBytes", "67109888")
                        .put("trackerBaseUrl", "http://127.0.0.1:8081/announce/"));

        TorrentDownloadTask task = TorrentDownloadTask.fromJson(fixture);
        assertEquals("resource-fixture", task.resourceId());
        assertEquals(1024, task.fileSize());
        assertEquals(TorrentDownloadTask.TYPE, task.taskType());

        String digest = task.payloadDigest();
        fixture.getJsonObject("payload").put("contentSha256", "3".repeat(64));
        assertNotEquals(digest, TorrentDownloadTask.fromJson(fixture).payloadDigest());
    }

    @Test
    void rejectsArbitrarySavePathsAndPublicTaskTypes() {
        JsonObject envelope = new JsonObject()
                .put("type", "PUBLIC_TORRENT")
                .put("schemaVersion", 1);
        assertThrows(
                TelegramBootstrapTask.UnsupportedTaskException.class,
                () -> NodeTaskEnvelope.fromJson(envelope)
        );
    }
}
