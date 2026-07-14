package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SeedNodeIdentityRecord;
import telegram.files.repository.ShareSourceRecord;
import telegram.files.repository.ShareSourceRepository;
import telegram.files.repository.FileRepository;
import telegram.files.share.security.AesGcmSecretStore;

import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResourcePublishingServiceTest {

    @Test
    void classifiesPlatformRedirectsAndUnstructuredHttpFailures() {
        assertEquals(
                "PLATFORM_ACCESS_BLOCKED",
                ResourcePublishingService.safeErrorCode(
                        new HttpSeedCoordinatorClient.SeedProtocolException(
                                "Platform returned invalid JSON",
                                302,
                                null,
                                "req-redirect"
                        )
                )
        );
        assertEquals(
                "PLATFORM_HTTP_404",
                ResourcePublishingService.safeErrorCode(
                        new HttpSeedCoordinatorClient.SeedProtocolException(
                                "Platform request failed",
                                404,
                                null,
                                "req-missing"
                        )
                )
        );
        assertEquals(
                "NODE_REVOKED",
                ResourcePublishingService.safeErrorCode(
                        new HttpSeedCoordinatorClient.SeedProtocolException(
                                "Platform request failed",
                                401,
                                "NODE_REVOKED",
                                "req-revoked"
                        )
                )
        );
    }

    @Test
    void duplicatePublishReusesMappingAndRepublishAfterRevokeRotatesToken() {
        FileRecord file = fileRecord();
        FileRepository fileRepository = mock(FileRepository.class);
        when(fileRepository.getByUniqueId("file-unique")).thenReturn(Future.succeededFuture(file));

        AtomicReference<ShareSourceRecord> stored = new AtomicReference<>();
        ShareSourceRepository repository = repository(stored);

        NodeIdentityService identityService = mock(NodeIdentityService.class);
        SeedNodeIdentityRecord identity = new SeedNodeIdentityRecord(
                "http://127.0.0.1:8080",
                "node-1",
                "test-node",
                "encrypted-credentials",
                Long.MAX_VALUE,
                null,
                "BOUND",
                1L,
                1L
        );
        when(identityService.access()).thenReturn(Future.succeededFuture(
                new NodeIdentityService.NodeAccess(identity, "access-token")
        ));

        List<String> publishedTokens = new ArrayList<>();
        AtomicInteger resourceSequence = new AtomicInteger();
        SeedCoordinatorClient client = mock(SeedCoordinatorClient.class);
        when(client.get(eq("/api/v1/publication-policy"), any())).thenReturn(Future.succeededFuture(
                new JsonObject()
                        .put("defaultDecision", "ALLOW")
                        .put("defaultCategoryId", "file")
                        .put("categories", new JsonArray()
                                .add(new JsonObject()
                                        .put("id", "file")
                                        .put("label", "File")
                                        .put("defaultForFileTypes", new JsonArray().add("file")))
                                .add(new JsonObject()
                                        .put("id", "video")
                                        .put("label", "Video")
                                        .put("defaultForFileTypes", new JsonArray().add("video"))))
                        .put("shareRules", new JsonArray()
                                .add(new JsonObject()
                                        .put("id", "deny-preview-types")
                                        .put("decision", "DENY")
                                        .put("reason", "File type is not shareable")
                                        .put("match", new JsonObject()
                                                .put("fileTypes", new JsonArray().add("thumbnail").add("photo"))))
                                .add(new JsonObject()
                                        .put("id", "deny-small-files")
                                        .put("decision", "DENY")
                                        .put("reason", "File is smaller than the minimum share size")
                                        .put("match", new JsonObject()
                                                .put("maxFileSizeBytes", 50L * 1024 * 1024 - 1))))
        ));
        when(client.post(anyString(), any(JsonObject.class), any()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(0);
                    JsonObject request = invocation.getArgument(1);
                    assertEquals("/api/v1/resources", path);
                    JsonObject source = request.getJsonObject("source");
                    publishedTokens.add(source.getString("opaqueSourceToken"));
                    assertEquals("file", source.getString("fileType"));
                    assertFalse(source.containsKey("telegramId"));
                    assertFalse(source.containsKey("chatId"));
                    assertFalse(source.containsKey("messageId"));
                    assertFalse(source.containsKey("localPath"));
                    return Future.succeededFuture(new JsonObject().put(
                            "id",
                            "resource-" + resourceSequence.incrementAndGet()
                    ));
                });
        when(client.delete(anyString(), any())).thenReturn(Future.succeededFuture(new JsonObject()));

        byte[] key = new byte[32];
        ResourcePublishingService service = new ResourcePublishingService(
                new ShareConfiguration(
                        true,
                        URI.create("http://127.0.0.1:8080"),
                        Path.of("build/test-share").toAbsolutePath(),
                        2,
                        Duration.ofSeconds(5),
                        3,
                        true
                ),
                identityService,
                client,
                repository,
                fileRepository,
                new AesGcmSecretStore(Map.of(1, new SecretKeySpec(key, "AES")), 1),
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC),
                new SecureRandom()
        );
        JsonObject request = new JsonObject()
                .put("fileUniqueId", "file-unique")
                .put("title", "M2 fixture")
                .put("accessScope", "OWNER_ONLY");

        JsonObject first = await(service.publish(request));
        JsonObject duplicate = await(service.publish(request));
        assertEquals("resource-1", first.getString("resourceId"));
        assertEquals("resource-1", duplicate.getString("resourceId"));
        assertEquals(1, publishedTokens.size());

        JsonObject revoked = await(service.revoke(first.getString("sourceId")));
        assertEquals("REVOKED", revoked.getString("status"));

        JsonObject republished = await(service.publish(request));
        assertEquals("resource-2", republished.getString("resourceId"));
        assertEquals(2, publishedTokens.size());
        assertNotEquals(publishedTokens.get(0), publishedTokens.get(1));
        verify(client).delete(eq("/api/v1/resources/resource-1"), any());
    }

    @Test
    void publishFallsBackToSafeFileNameWhenTelegramFileNameIsBlank() {
        FileRecord file = fileRecord();
        when(file.fileName()).thenReturn("");
        when(file.type()).thenReturn("video");
        when(file.mimeType()).thenReturn("video/mp4");
        FileRepository fileRepository = mock(FileRepository.class);
        when(fileRepository.getByUniqueId("file-unique")).thenReturn(Future.succeededFuture(file));

        AtomicReference<ShareSourceRecord> stored = new AtomicReference<>();
        ShareSourceRepository repository = repository(stored);

        NodeIdentityService identityService = mock(NodeIdentityService.class);
        SeedNodeIdentityRecord identity = new SeedNodeIdentityRecord(
                "http://127.0.0.1:8080",
                "node-1",
                "test-node",
                "encrypted-credentials",
                Long.MAX_VALUE,
                null,
                "BOUND",
                1L,
                1L
        );
        when(identityService.access()).thenReturn(Future.succeededFuture(
                new NodeIdentityService.NodeAccess(identity, "access-token")
        ));

        AtomicReference<JsonObject> platformRequest = new AtomicReference<>();
        SeedCoordinatorClient client = mock(SeedCoordinatorClient.class);
        when(client.get(eq("/api/v1/publication-policy"), any()))
                .thenReturn(Future.succeededFuture(SharePublicationPolicy.defaults().toJson()));
        when(client.post(anyString(), any(JsonObject.class), any())).thenAnswer(invocation -> {
            platformRequest.set(invocation.getArgument(1));
            return Future.succeededFuture(new JsonObject().put("id", "resource-1"));
        });

        byte[] key = new byte[32];
        ResourcePublishingService service = new ResourcePublishingService(
                new ShareConfiguration(
                        true,
                        URI.create("http://127.0.0.1:8080"),
                        Path.of("build/test-share").toAbsolutePath(),
                        2,
                        Duration.ofSeconds(5),
                        3,
                        true
                ),
                identityService,
                client,
                repository,
                fileRepository,
                new AesGcmSecretStore(Map.of(1, new SecretKeySpec(key, "AES")), 1),
                Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC),
                new SecureRandom()
        );

        JsonObject response = await(service.publish(new JsonObject()
                .put("fileUniqueId", "file-unique")
                .put("title", "好好活着，就是对逝者最大的安慰。 #夜班日记 20完")
                .put("accessScope", "OWNER_ONLY")));

        assertEquals("PUBLISHED", response.getString("status"));
        assertEquals(
                "好好活着，就是对逝者最大的安慰。 #夜班日记 20完.mp4",
                platformRequest.get().getJsonObject("source").getString("fileName")
        );
        assertEquals(
                "好好活着，就是对逝者最大的安慰。 #夜班日记 20完.mp4",
                stored.get().fileName()
        );
    }

    private static ShareSourceRepository repository(AtomicReference<ShareSourceRecord> stored) {
        ShareSourceRepository repository = mock(ShareSourceRepository.class);
        when(repository.getBySourceKey(anyString()))
                .thenAnswer(_ -> Future.succeededFuture(stored.get()));
        when(repository.getById(anyString()))
                .thenAnswer(_ -> Future.succeededFuture(stored.get()));
        when(repository.listPage(anyInt(), anyInt()))
                .thenAnswer(_ -> Future.succeededFuture(stored.get() == null
                        ? java.util.List.of()
                        : java.util.List.of(stored.get())));
        when(repository.count())
                .thenAnswer(_ -> Future.succeededFuture(stored.get() == null ? 0L : 1L));
        when(repository.save(any(ShareSourceRecord.class))).thenAnswer(invocation -> {
            ShareSourceRecord record = invocation.getArgument(0);
            stored.set(record);
            return Future.succeededFuture(record);
        });
        when(repository.markPublished(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    ShareSourceRecord current = stored.get();
                    stored.set(copy(
                            current,
                            invocation.getArgument(1),
                            "PUBLISHED",
                            invocation.getArgument(2)
                    ));
                    return Future.succeededFuture();
                });
        when(repository.markPending(anyString(), anyString(), anyString(), anyInt(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    ShareSourceRecord current = stored.get();
                    stored.set(new ShareSourceRecord(
                            current.id(), current.sourceKey(), current.platformResourceId(),
                            current.fileRecordId(), current.fileUniqueId(), current.telegramId(),
                            current.chatId(), current.messageId(), current.fileName(), current.fileSize(),
                            current.mimeType(), current.downloaded(), current.accessScope(),
                            current.publicMessageUrl(), current.opaqueTokenCiphertext(),
                            current.opaqueTokenDigest(), current.title(), current.description(),
                            current.tagsJson(), current.category(), current.immediateReseed(),
                            current.indexOnly(), current.autoDownloadOnDemand(),
                            current.uploadLimitBytesPerSecond(), current.minimumSeedSeconds(),
                            invocation.getArgument(1), current.createIdempotencyKey(),
                            current.updateIdempotencyKey(), current.revokeIdempotencyKey(),
                            invocation.getArgument(3), invocation.getArgument(4),
                            invocation.getArgument(2), current.createdAt(), invocation.getArgument(5),
                            current.version() + 1
                    ));
                    return Future.succeededFuture();
                });
        when(repository.markRevoked(anyString(), anyLong())).thenAnswer(invocation -> {
            ShareSourceRecord current = stored.get();
            stored.set(copy(current, current.platformResourceId(), "REVOKED", invocation.getArgument(1)));
            return Future.succeededFuture();
        });
        return repository;
    }

    private static FileRecord fileRecord() {
        FileRecord file = mock(FileRecord.class);
        when(file.id()).thenReturn(17);
        when(file.uniqueId()).thenReturn("file-unique");
        when(file.telegramId()).thenReturn(101L);
        when(file.chatId()).thenReturn(-100202L);
        when(file.messageId()).thenReturn(303L);
        when(file.fileName()).thenReturn("fixture.bin");
        when(file.size()).thenReturn(60L * 1024 * 1024);
        when(file.type()).thenReturn("file");
        when(file.mimeType()).thenReturn("application/octet-stream");
        when(file.localPath()).thenReturn("/private/local/fixture.bin");
        when(file.isDownloadStatus(FileRecord.DownloadStatus.completed)).thenReturn(true);
        return file;
    }

    private static ShareSourceRecord copy(
            ShareSourceRecord record,
            String platformResourceId,
            String status,
            long now
    ) {
        return new ShareSourceRecord(
                record.id(), record.sourceKey(), platformResourceId, record.fileRecordId(),
                record.fileUniqueId(), record.telegramId(), record.chatId(), record.messageId(),
                record.fileName(), record.fileSize(), record.mimeType(), record.downloaded(),
                record.accessScope(), record.publicMessageUrl(), record.opaqueTokenCiphertext(),
                record.opaqueTokenDigest(), record.title(), record.description(), record.tagsJson(),
                record.category(), record.immediateReseed(), record.indexOnly(),
                record.autoDownloadOnDemand(), record.uploadLimitBytesPerSecond(),
                record.minimumSeedSeconds(), status, record.createIdempotencyKey(),
                record.updateIdempotencyKey(), record.revokeIdempotencyKey(), record.attemptCount(),
                now, null, record.createdAt(), now, record.version() + 1
        );
    }

    private static <T> T await(Future<T> future) {
        return future.toCompletionStage().toCompletableFuture().join();
    }
}
