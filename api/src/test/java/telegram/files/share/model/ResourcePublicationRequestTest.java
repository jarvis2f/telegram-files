package telegram.files.share.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ResourcePublicationRequestTest {

    @Test
    void privatePublicationPayloadContainsNoTelegramLocatorOrLocalPath() {
        FileRecord file = file("completed", "/private/cache/secret.bin");
        ResourcePublicationRequest request = ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", file.uniqueId())
                        .put("title", "Private fixture")
                        .put("description", "safe description")
                        .put("tags", new JsonArray().add("fixture"))
                        .put("accessScope", "OWNER_ONLY")
                        .put("autoDownloadOnDemand", true),
                file
        );

        JsonObject payload = request.toPlatformCreate(
                file,
                "018f52d8-9d73-7f18-b7b6-56cfb8c20101",
                "D9l21cTj2YLFpXbdqHfQ2Vq5Au9bCiOe"
        );
        JsonObject source = payload.getJsonObject("source");
        for (String forbidden : Set.of(
                "chatId", "messageId", "telegramId", "phone", "apiHash", "localPath"
        )) {
            assertFalse(source.containsKey(forbidden), forbidden);
        }
        assertFalse(payload.encode().contains("778899"));
        assertFalse(payload.encode().contains("445566"));
        assertFalse(payload.encode().contains("/private/cache"));
        assertTrue(source.getBoolean("downloaded", false));
        assertTrue(request.immediateReseed());
        assertFalse(request.indexOnly());
    }

    @Test
    void completedFilesAlwaysRequestImmediateReseed() {
        FileRecord file = file("completed", "/private/cache/secret.bin");

        ResourcePublicationRequest request = ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", file.uniqueId())
                        .put("title", "Downloaded fixture")
                        .put("immediateReseed", false)
                        .put("indexOnly", true),
                file
        );

        assertTrue(request.immediateReseed());
        assertFalse(request.indexOnly());
    }

    @Test
    void derivesTagsFromDescriptionAndSensitiveContent() {
        FileRecord file = file("completed", "/private/cache/secret.bin", "video", true);

        ResourcePublicationRequest request = ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", file.uniqueId())
                        .put("title", "Tagged fixture")
                        .put("description", "Scene #alpha #beta")
                        .put("tags", new JsonArray().add("manual")),
                file
        );

        assertEquals(List.of("manual", "alpha", "beta", "R18"), request.tags().getList());
    }

    @Test
    void rejectsForbiddenTypesAndSmallFiles() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePublicationRequest.from(
                new JsonObject().put("fileUniqueId", "photo").put("title", "Photo"),
                file("completed", "/private/cache/photo.jpg", "photo", false)
        ));
        assertThrows(IllegalArgumentException.class, () -> ResourcePublicationRequest.from(
                new JsonObject().put("fileUniqueId", "small").put("title", "Small"),
                file("completed", "/private/cache/small.bin", "file", false, 1024)
        ));
    }

    @Test
    void descriptionPreservesLineBreaks() {
        FileRecord file = file("completed", "/private/cache/secret.bin");
        ResourcePublicationRequest request = ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", file.uniqueId())
                        .put("title", "Multiline fixture")
                        .put("description", "  line one\r\nline two\nline three  "),
                file
        );

        assertEquals("line one\nline two\nline three", request.description());
        assertEquals("line one\nline two\nline three", request.platformMetadata().getString("description"));
    }

    @Test
    void remoteOnlyAndPublicSourcesFollowAccessRules() {
        FileRecord remote = file("idle", null);
        ResourcePublicationRequest privateRequest = ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", remote.uniqueId())
                        .put("title", "Remote fixture")
                        .put("accessScope", "OWNER_ONLY"),
                remote
        );
        assertFalse(privateRequest.toPlatformCreate(remote, "node", "abcdefghijklmnopqrstuv")
                .getJsonObject("source").getBoolean("downloaded"));

        assertThrows(IllegalArgumentException.class, () -> ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", remote.uniqueId())
                        .put("title", "Public fixture")
                        .put("accessScope", "PUBLIC"),
                remote
        ));
        assertDoesNotThrow(() -> ResourcePublicationRequest.from(
                new JsonObject()
                        .put("fileUniqueId", remote.uniqueId())
                        .put("title", "Public fixture")
                        .put("accessScope", "PUBLIC")
                        .put("publicMessageUrl", "https://t.me/public_fixture/42"),
                remote
        ));
    }

    @Test
    void rejectsUnknownOrExplicitPrivateLocatorFields() {
        FileRecord file = file("idle", null);
        for (String forbidden : Set.of("chatId", "messageId", "telegramId", "localPath", "apiHash")) {
            JsonObject body = new JsonObject()
                    .put("fileUniqueId", file.uniqueId())
                    .put("title", "Fixture")
                    .put(forbidden, "secret");
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ResourcePublicationRequest.from(body, file),
                    forbidden
            );
        }
    }

    private static FileRecord file(String status, String localPath) {
        return file(status, localPath, "file", false);
    }

    private static FileRecord file(String status, String localPath, String type, boolean sensitive) {
        return file(status, localPath, type, sensitive, 60L * 1024 * 1024);
    }

    private static FileRecord file(String status, String localPath, String type, boolean sensitive, long size) {
        return new FileRecord(
                7,
                "file-unique-7",
                11,
                778899,
                445566,
                0,
                1,
                sensitive,
                size,
                0,
                type,
                "application/octet-stream",
                "fixture.bin",
                null,
                null,
                "caption",
                null,
                localPath,
                status,
                "idle",
                0,
                "completed".equals(status) ? 1L : null,
                null,
                0,
                0,
                0
        );
    }
}
