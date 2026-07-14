package telegram.files.share;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TelegramBootstrapTaskTest {

    @Test
    void validatesFrozenV1EnvelopeAndCreatesStableDigest() {
        TelegramBootstrapTask first = TelegramBootstrapTask.fromJson(envelope());
        TelegramBootstrapTask repeated = TelegramBootstrapTask.fromJson(envelope().copy());

        assertEquals("resource-1", first.resourceId());
        assertEquals(first.payloadDigest(), repeated.payloadDigest());
        assertEquals(64, first.payloadDigest().length());
    }

    @Test
    void rejectsUnsupportedSchemaBeforeExecution() {
        JsonObject envelope = envelope().put("schemaVersion", 2);
        TelegramBootstrapTask.UnsupportedTaskException failure = assertThrows(
                TelegramBootstrapTask.UnsupportedTaskException.class,
                () -> TelegramBootstrapTask.fromJson(envelope)
        );
        assertEquals("UNSUPPORTED_SCHEMA_VERSION", failure.errorCode());
    }

    private static JsonObject envelope() {
        return new JsonObject()
                .put("taskId", "task-1")
                .put("attemptId", "attempt-1")
                .put("type", "TELEGRAM_BOOTSTRAP_V1")
                .put("schemaVersion", 1)
                .put("leaseToken", "lease-token-fixture-0123456789")
                .put("deadlineAt", "2026-07-15T10:00:00Z")
                .put("payload", new JsonObject()
                        .put("resourceId", "resource-1")
                        .put("sourceId", "source-1")
                        .put("fileUniqueId", "file-1")
                        .put("fileName", "fixture.bin")
                        .put("fileSize", "1024")
                        .put("accessScope", "OWNER_ONLY")
                        .put("reservedBytes", "4096")
                        .put("opaqueSourceToken", "opaque_source_fixture_0123456789"))
                .put("createdAt", "2026-07-15T09:00:00Z");
    }
}
