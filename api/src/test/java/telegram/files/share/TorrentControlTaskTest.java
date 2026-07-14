package telegram.files.share;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TorrentControlTaskTest {

    @Test
    void acceptsOptionalFieldsButPinsTheKnownV1ControlPayload() {
        JsonObject envelope = envelope("SET_UPLOAD_LIMIT_V1")
                .put("futureEnvelopeField", true);
        envelope.getJsonObject("payload")
                .put("uploadLimitBytesPerSecond", "1048576")
                .put("futurePayloadField", "ignored");

        TorrentControlTask task = TorrentControlTask.fromJson(envelope);
        assertEquals(1_048_576, task.uploadLimitBytesPerSecond());
        assertEquals("resource-control", task.resourceId());
        assertEquals(64, task.payloadDigest().length());
    }

    @Test
    void rejectsUnknownTypesAndHigherSchemaVersionsBeforeExecution() {
        assertThrows(
                TelegramBootstrapTask.UnsupportedTaskException.class,
                () -> NodeTaskEnvelope.fromJson(envelope("DELETE_LOCAL_FILES_V1"))
        );
        TelegramBootstrapTask.UnsupportedTaskException failure = assertThrows(
                TelegramBootstrapTask.UnsupportedTaskException.class,
                () -> TorrentControlTask.fromJson(envelope("PAUSE_V1").put("schemaVersion", 2))
        );
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", failure.errorCode());
    }

    @Test
    void neverAllowsUploadLimitOnAnotherControl() {
        JsonObject envelope = envelope("CANCEL_V1");
        envelope.getJsonObject("payload").put("uploadLimitBytesPerSecond", "1");
        assertThrows(IllegalArgumentException.class, () -> TorrentControlTask.fromJson(envelope));
    }

    @Test
    void validatesPlannedTrackerCredentialRotationWithoutEmbeddingTheCredential() {
        JsonObject envelope = envelope("ROTATE_TRACKER_CREDENTIAL_V1");
        envelope.getJsonObject("payload")
                .put("trackerBaseUrl", "https://tracker.example.test/announce/");
        TorrentControlTask task = TorrentControlTask.fromJson(envelope);
        assertEquals("https://tracker.example.test/announce/", task.trackerBaseUrl());
    }

    private static JsonObject envelope(String type) {
        return new JsonObject()
                .put("taskId", "task-control")
                .put("attemptId", "attempt-control")
                .put("type", type)
                .put("schemaVersion", 1)
                .put("leaseToken", "lease-control-fixture-0123456789")
                .put("deadlineAt", "2026-07-16T10:00:00Z")
                .put("payload", new JsonObject()
                        .put("resourceId", "resource-control")
                        .put("infoHashV1", "a".repeat(40)));
    }
}
