package telegram.files.share;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileReadyForShareTest {

    @Test
    void roundTripsIdentifierOnlyPayload() {
        FileReadyForShare event = new FileReadyForShare(42, 3, 1001);

        assertEquals(event, FileReadyForShare.fromJson(event.toJson()));
        assertFalse(event.toJson().containsKey("path"));
    }

    @Test
    void rejectsPathsAndTelegramLocatorFields() {
        JsonObject payload = JsonObject.of(
                "fileRecordId", 42L,
                "recordVersion", 3L,
                "telegramId", 1001L,
                "localPath", "/private/file"
        );

        assertThrows(IllegalArgumentException.class, () -> FileReadyForShare.fromJson(payload));
    }
}
