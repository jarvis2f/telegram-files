package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;
import telegram.files.repository.ShareSourceRecord;
import telegram.files.repository.ShareSourceRepository;
import telegram.files.share.HttpSeedCoordinatorClient.SeedProtocolException;
import telegram.files.share.model.OpaqueSourceToken;
import telegram.files.share.model.ResourcePublicationRequest;
import telegram.files.share.security.SecretStore;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ResourcePublishingService {

    private static final Duration BASE_RETRY_DELAY = Duration.ofSeconds(5);
    private static final Pattern UNSAFE_FILE_NAME_CHARS = Pattern.compile("[/\\\\\\p{Cntrl}]+");

    private final ShareConfiguration configuration;

    private final NodeIdentityService identityService;

    private final SeedCoordinatorClient client;

    private final ShareSourceRepository repository;

    private final FileRepository fileRepository;

    private final SecretStore secretStore;

    private final Clock clock;

    private final SecureRandom random;

    private record FileAccess(NodeIdentityService.NodeAccess access, FileRecord file) {
    }

    private record FileAccessPolicy(FileAccess fileAccess, SharePublicationPolicy policy) {
    }

    public ResourcePublishingService(
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            ShareSourceRepository repository,
            FileRepository fileRepository,
            SecretStore secretStore
    ) {
        this(
                configuration,
                identityService,
                client,
                repository,
                fileRepository,
                secretStore,
                Clock.systemUTC(),
                new SecureRandom()
        );
    }

    ResourcePublishingService(
            ShareConfiguration configuration,
            NodeIdentityService identityService,
            SeedCoordinatorClient client,
            ShareSourceRepository repository,
            FileRepository fileRepository,
            SecretStore secretStore,
            Clock clock,
            SecureRandom random
    ) {
        this.configuration = Objects.requireNonNull(configuration);
        this.identityService = Objects.requireNonNull(identityService);
        this.client = Objects.requireNonNull(client);
        this.repository = Objects.requireNonNull(repository);
        this.fileRepository = Objects.requireNonNull(fileRepository);
        this.secretStore = Objects.requireNonNull(secretStore);
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public Future<JsonObject> publish(JsonObject body) {
        String fileUniqueId = required(body, "fileUniqueId");
        return identityService.access()
                .compose(access -> fileRepository.getByUniqueId(fileUniqueId).map(file -> new FileAccess(access, file)))
                .compose(entry -> policy(entry.access().accessToken()).map(policy -> new FileAccessPolicy(entry, policy)))
                .compose(entry -> {
                    NodeIdentityService.NodeAccess access = entry.fileAccess().access();
                    FileRecord fileRecord = entry.fileAccess().file();
                    SharePublicationPolicy policy = entry.policy();
                    if (fileRecord == null) {
                        return Future.failedFuture(new IllegalArgumentException("Telegram file was not found"));
                    }
                    ResourcePublicationRequest request = ResourcePublicationRequest.from(body, fileRecord, policy);
                    String sourceKey = sourceKey(fileRecord);
                    return repository.getBySourceKey(sourceKey).compose(existing -> {
                        if (existing != null && "PUBLISHED".equals(existing.status())) {
                            return Future.succeededFuture(existing.toPublicJson());
                        }
                        if (existing != null && isPending(existing.status())) {
                            return synchronize(existing).map(ShareSourceRecord::toPublicJson);
                        }
                        if (existing != null && "REVOKED".equals(existing.status())) {
                            ShareSourceRecord pending = newRecord(existing, fileRecord, request, sourceKey);
                            return repository.save(pending)
                                    .compose(this::synchronize)
                                    .map(ShareSourceRecord::toPublicJson);
                        }
                        if (existing != null && existing.platformResourceId() != null) {
                            return Future.succeededFuture(existing.toPublicJson());
                        }
                        if (existing != null && "PUBLISH_FAILED".equals(existing.status())) {
                            ShareSourceRecord retry = copyStatus(
                                    existing,
                                    "PUBLISH_PENDING",
                                    existing.createIdempotencyKey(),
                                    existing.updateIdempotencyKey(),
                                    existing.revokeIdempotencyKey(),
                                    clock.millis()
                            );
                            return repository.save(retry)
                                    .compose(this::synchronize)
                                    .map(ShareSourceRecord::toPublicJson);
                        }
                        ShareSourceRecord pending = newRecord(existing, fileRecord, request, sourceKey);
                        return repository.save(pending)
                                .compose(this::synchronize)
                                .map(ShareSourceRecord::toPublicJson);
                    });
                });
    }

    public Future<JsonObject> update(String sourceId, JsonObject changes) {
        return repository.getById(sourceId).compose(existing -> {
            if (existing == null || existing.platformResourceId() == null
                || "REVOKED".equals(existing.status())) {
                return Future.failedFuture(new IllegalArgumentException("Published source was not found"));
            }
            return identityService.access().compose(access -> fileRepository.getByUniqueId(existing.fileUniqueId()).compose(file -> {
                if (file == null) {
                    return Future.failedFuture(new IllegalArgumentException("Telegram file was not found"));
                }
                return policy(access.accessToken()).compose(policy -> {
                    JsonObject merged = editableBody(existing).mergeIn(changes, true);
                    merged.put("fileUniqueId", existing.fileUniqueId());
                    ResourcePublicationRequest request = ResourcePublicationRequest.from(merged, file, policy);
                    ShareSourceRecord pending = withRequest(
                            existing,
                            file,
                            request,
                            "UPDATE_PENDING",
                            existing.createIdempotencyKey(),
                            UUID.randomUUID().toString(),
                            existing.revokeIdempotencyKey()
                    );
                    return repository.save(pending)
                            .compose(this::synchronize)
                            .map(ShareSourceRecord::toPublicJson);
                });
            }));
        });
    }

    public Future<JsonObject> publicationPolicy() {
        return identityService.access()
                .compose(access -> policy(access.accessToken()))
                .map(SharePublicationPolicy::toJson);
    }

    public Future<JsonObject> revoke(String sourceId) {
        return repository.getById(sourceId).compose(existing -> {
            if (existing == null) {
                return Future.failedFuture(new IllegalArgumentException("Published source was not found"));
            }
            if ("REVOKED".equals(existing.status())) {
                return Future.succeededFuture(existing.toPublicJson());
            }
            if (existing.platformResourceId() == null) {
                return repository.markRevoked(existing.id(), clock.millis())
                        .compose(_ -> repository.getById(existing.id()))
                        .map(ShareSourceRecord::toPublicJson);
            }
            ShareSourceRecord pending = copyStatus(
                    existing,
                    "REVOKE_PENDING",
                    existing.createIdempotencyKey(),
                    existing.updateIdempotencyKey(),
                    UUID.randomUUID().toString(),
                    clock.millis()
            );
            return repository.save(pending)
                    .compose(this::synchronize)
                    .map(ShareSourceRecord::toPublicJson);
        });
    }

    public Future<JsonObject> list(JsonObject body) {
        int page = clamp(body == null ? null : body.getInteger("page"), 1, 100_000, 1);
        int pageSize = clamp(body == null ? null : body.getInteger("pageSize"), 1, 100, 10);
        int offset = (page - 1) * pageSize;
        Future<List<ShareSourceRecord>> pageFuture = repository.listPage(offset, pageSize);
        Future<Long> totalFuture = repository.count();
        return Future.all(pageFuture, totalFuture).map(_ -> {
            List<ShareSourceRecord> records = pageFuture.result();
            JsonArray items = new JsonArray();
            records.forEach(record -> items.add(record.toPublicJson()));
            return new JsonObject()
                    .put("items", items)
                    .put("page", page)
                    .put("pageSize", pageSize)
                    .put("total", totalFuture.result());
        });
    }

    public Future<Void> recoverPending() {
        return repository.listRetryable(clock.millis(), Math.max(1, configuration.concurrency() * 4))
                .compose(records -> {
                    List<Future<ShareSourceRecord>> work = new ArrayList<>();
                    records.forEach(record -> work.add(synchronize(record)));
                    return work.isEmpty() ? Future.succeededFuture() : Future.all(work).mapEmpty();
                });
    }

    private Future<ShareSourceRecord> synchronize(ShareSourceRecord record) {
        return identityService.access()
                .compose(access -> switch (record.status()) {
                    case "PUBLISH_PENDING" -> fileRepository.getByUniqueId(record.fileUniqueId())
                            .compose(file -> client.post(
                                            "/api/v1/resources",
                                            platformCreate(record, access.identity().nodeId(), file == null ? "file" : file.type()),
                                            headers(access.accessToken(), record.createIdempotencyKey())
                                    )
                            )
                            .compose(response -> {
                                String resourceId = response.getString("id");
                                if (resourceId == null || resourceId.isBlank()) {
                                    return Future.failedFuture(
                                            new IllegalArgumentException("Platform resource response is invalid")
                                    );
                                }
                                return repository.markPublished(record.id(), resourceId, clock.millis());
                            });
                    case "UPDATE_PENDING" -> client.put(
                                    "/api/v1/resources/" + record.platformResourceId(),
                                    platformMetadata(record),
                                    headers(access.accessToken(), record.updateIdempotencyKey())
                            )
                            .compose(_ -> repository.markPublished(
                                    record.id(), record.platformResourceId(), clock.millis()
                            ));
                    case "REVOKE_PENDING" -> client.delete(
                                    "/api/v1/resources/" + record.platformResourceId(),
                                    headers(access.accessToken(), record.revokeIdempotencyKey())
                            )
                            .compose(_ -> repository.markRevoked(record.id(), clock.millis()));
                    default -> Future.succeededFuture();
                })
                .compose(_ -> repository.getById(record.id()))
                .recover(failure -> defer(record, failure));
    }

    private Future<SharePublicationPolicy> policy(String accessToken) {
        return client.get("/api/v1/publication-policy", headers(accessToken, UUID.randomUUID().toString()))
                .map(SharePublicationPolicy::from);
    }

    private Future<ShareSourceRecord> defer(ShareSourceRecord record, Throwable failure) {
        int attempt = record.attemptCount() + 1;
        boolean retryable = isRetryable(failure) && attempt <= configuration.maxRetries();
        String status = retryable
                ? record.status()
                : record.status().replace("_PENDING", "_FAILED");
        String errorCode = safeErrorCode(failure);
        long nextAttemptAt = retryable
                ? clock.millis() + retryDelay(attempt).toMillis()
                : clock.millis();
        return repository.markPending(
                        record.id(), status, errorCode, attempt, nextAttemptAt, clock.millis()
                )
                .compose(_ -> repository.getById(record.id()));
    }

    private JsonObject platformCreate(ShareSourceRecord record, String nodeId, String fileType) {
        String token = OpaqueSourceToken.decrypt(record.opaqueTokenCiphertext(), secretStore);
        JsonObject source = new JsonObject()
                .put("ownerNodeId", nodeId)
                .put("opaqueSourceToken", token)
                .put("accessScope", record.accessScope())
                .put("publicMessageUrl", record.publicMessageUrl())
                .put("fileUniqueId", record.fileUniqueId())
                .put("fileName", publishedFileName(record))
                .put("fileSize", Long.toString(record.fileSize()))
                .put("mimeType", record.mimeType())
                .put("fileType", fileType == null || fileType.isBlank() ? "file" : fileType)
                .put("downloaded", record.downloaded());
        return platformMetadata(record).put("source", source);
    }

    private static JsonObject platformMetadata(ShareSourceRecord record) {
        JsonObject policy = new JsonObject()
                .put("immediateReseed", record.immediateReseed())
                .put("indexOnly", record.indexOnly())
                .put("autoDownloadOnDemand", record.autoDownloadOnDemand())
                .put("uploadLimitBytesPerSecond", record.uploadLimitBytesPerSecond())
                .put("minimumSeedSeconds", record.minimumSeedSeconds());
        return new JsonObject()
                .put("title", record.title())
                .put("description", record.description())
                .put("tags", new JsonArray(record.tagsJson()))
                .put("category", record.category())
                .put("publicationPolicy", policy);
    }

    private ShareSourceRecord newRecord(
            ShareSourceRecord existing,
            FileRecord file,
            ResourcePublicationRequest request,
            String sourceKey
    ) {
        long now = clock.millis();
        String token = OpaqueSourceToken.issue(random);
        String id = existing == null ? UUID.randomUUID().toString() : existing.id();
        long createdAt = existing == null ? now : existing.createdAt();
        int version = existing == null ? 0 : existing.version();
        return new ShareSourceRecord(
                id,
                sourceKey,
                null,
                file.id(),
                file.uniqueId(),
                file.telegramId(),
                file.chatId(),
                file.messageId(),
                publishedFileName(file.fileName(), request.title(), file.uniqueId(), file.mimeType()),
                file.size(),
                file.mimeType(),
                ResourcePublicationRequest.isDownloaded(file),
                request.accessScope(),
                request.publicMessageUrl(),
                OpaqueSourceToken.encrypt(token, secretStore),
                OpaqueSourceToken.digest(token),
                request.title(),
                request.description(),
                request.tags().encode(),
                request.category(),
                request.immediateReseed(),
                request.indexOnly(),
                request.autoDownloadOnDemand(),
                request.uploadLimitBytesPerSecond(),
                request.minimumSeedSeconds(),
                "PUBLISH_PENDING",
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                0,
                now,
                null,
                createdAt,
                now,
                version
        );
    }

    private ShareSourceRecord withRequest(
            ShareSourceRecord record,
            FileRecord file,
            ResourcePublicationRequest request,
            String status,
            String createKey,
            String updateKey,
            String revokeKey
    ) {
        return new ShareSourceRecord(
                record.id(), record.sourceKey(), record.platformResourceId(), file.id(), file.uniqueId(),
                file.telegramId(), file.chatId(), file.messageId(),
                publishedFileName(file.fileName(), request.title(), file.uniqueId(), file.mimeType()), file.size(),
                file.mimeType(), ResourcePublicationRequest.isDownloaded(file), request.accessScope(),
                request.publicMessageUrl(), record.opaqueTokenCiphertext(), record.opaqueTokenDigest(),
                request.title(), request.description(), request.tags().encode(), request.category(),
                request.immediateReseed(), request.indexOnly(), request.autoDownloadOnDemand(),
                request.uploadLimitBytesPerSecond(), request.minimumSeedSeconds(), status, createKey,
                updateKey, revokeKey, 0, clock.millis(), null, record.createdAt(), clock.millis(),
                record.version()
        );
    }

    private static ShareSourceRecord copyStatus(
            ShareSourceRecord record,
            String status,
            String createKey,
            String updateKey,
            String revokeKey,
            long now
    ) {
        return new ShareSourceRecord(
                record.id(), record.sourceKey(), record.platformResourceId(), record.fileRecordId(),
                record.fileUniqueId(), record.telegramId(), record.chatId(), record.messageId(),
                record.fileName(), record.fileSize(), record.mimeType(), record.downloaded(),
                record.accessScope(), record.publicMessageUrl(), record.opaqueTokenCiphertext(),
                record.opaqueTokenDigest(), record.title(), record.description(), record.tagsJson(),
                record.category(), record.immediateReseed(), record.indexOnly(),
                record.autoDownloadOnDemand(), record.uploadLimitBytesPerSecond(),
                record.minimumSeedSeconds(), status, createKey, updateKey, revokeKey, 0, now, null,
                record.createdAt(), now, record.version()
        );
    }

    private static JsonObject editableBody(ShareSourceRecord record) {
        return new JsonObject()
                .put("title", record.title())
                .put("description", record.description())
                .put("tags", new JsonArray(record.tagsJson()))
                .put("category", record.category())
                .put("accessScope", record.accessScope())
                .put("publicMessageUrl", record.publicMessageUrl())
                .put("immediateReseed", record.immediateReseed())
                .put("indexOnly", record.indexOnly())
                .put("autoDownloadOnDemand", record.autoDownloadOnDemand())
                .put("uploadLimitBytesPerSecond", record.uploadLimitBytesPerSecond())
                .put("minimumSeedSeconds", record.minimumSeedSeconds());
    }

    private static Map<String, String> headers(String token, String idempotencyKey) {
        return Map.of(
                "Authorization", "Bearer " + token,
                "Idempotency-Key", idempotencyKey
        );
    }

    private static boolean isPending(String status) {
        return "PUBLISH_PENDING".equals(status)
               || "UPDATE_PENDING".equals(status)
               || "REVOKE_PENDING".equals(status);
    }

    private static boolean isRetryable(Throwable failure) {
        if (failure instanceof SeedProtocolException protocol) {
            return protocol.statusCode() == 408 || protocol.statusCode() == 429
                   || protocol.statusCode() >= 500;
        }
        return !(failure instanceof IllegalArgumentException);
    }

    static String safeErrorCode(Throwable failure) {
        if (failure instanceof SeedProtocolException protocol) {
            if (protocol.errorCode() != null && !protocol.errorCode().isBlank()) {
                return protocol.errorCode();
            }
            if (protocol.statusCode() >= 300 && protocol.statusCode() < 400) {
                return "PLATFORM_ACCESS_BLOCKED";
            }
            return "PLATFORM_HTTP_" + protocol.statusCode();
        }
        return failure instanceof IllegalArgumentException
                ? "VALIDATION_FAILED"
                : "INTERNAL_RETRYABLE";
    }

    private static Duration retryDelay(int attempt) {
        long multiplier = 1L << Math.min(Math.max(0, attempt - 1), 10);
        return BASE_RETRY_DELAY.multipliedBy(multiplier);
    }

    private static String required(JsonObject body, String field) {
        if (body == null || body.getString(field) == null || body.getString(field).isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return body.getString(field);
    }

    private static int clamp(Integer value, int min, int max, int defaultValue) {
        int candidate = value == null ? defaultValue : value;
        return Math.min(max, Math.max(min, candidate));
    }

    private static String publishedFileName(ShareSourceRecord record) {
        return publishedFileName(record.fileName(), record.title(), record.fileUniqueId(), record.mimeType());
    }

    private static String publishedFileName(String rawFileName, String title, String fileUniqueId, String mimeType) {
        String extension = extension(mimeType);
        String value = sanitizeFileName(rawFileName);
        if (value == null) {
            value = sanitizeFileName(title);
        }
        if (value == null) {
            String uniqueId = sanitizeFileName(fileUniqueId);
            value = uniqueId == null ? "shared-file" : "telegram-" + uniqueId;
        }
        if (!extension.isEmpty() && !hasExtension(value)) {
            value = value + extension;
        }
        if (value.length() > 255) {
            value = trimFileName(value, extension);
        }
        return value;
    }

    private static String sanitizeFileName(String raw) {
        if (raw == null) {
            return null;
        }
        String value = UNSAFE_FILE_NAME_CHARS.matcher(raw.strip()).replaceAll("_")
                .replaceAll("\\s+", " ");
        if (value.isBlank() || ".".equals(value) || "..".equals(value)) {
            return null;
        }
        return value;
    }

    private static boolean hasExtension(String value) {
        int dot = value.lastIndexOf('.');
        return dot > 0 && dot < value.length() - 1;
    }

    private static String extension(String mimeType) {
        return switch (mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT)) {
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "video/x-matroska" -> ".mkv";
            case "audio/mpeg" -> ".mp3";
            case "audio/mp4" -> ".m4a";
            case "audio/ogg" -> ".ogg";
            case "application/pdf" -> ".pdf";
            case "application/zip" -> ".zip";
            default -> "";
        };
    }

    private static String trimFileName(String value, String extension) {
        if (!extension.isEmpty() && value.endsWith(extension) && extension.length() < 255) {
            return value.substring(0, 255 - extension.length()) + extension;
        }
        return value.substring(0, 255);
    }

    static String sourceKey(FileRecord file) {
        String value = "%d:%d:%d:%s".formatted(
                file.telegramId(), file.chatId(), file.messageId(), file.uniqueId()
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
