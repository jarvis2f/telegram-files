package telegram.files.share.model;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import telegram.files.repository.FileRecord;
import telegram.files.share.SharePublicationPolicy;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ResourcePublicationRequest(
        String title,
        String description,
        JsonArray tags,
        String category,
        String accessScope,
        String publicMessageUrl,
        boolean immediateReseed,
        boolean indexOnly,
        boolean autoDownloadOnDemand,
        String uploadLimitBytesPerSecond,
        long minimumSeedSeconds
) {

    private static final Pattern HASHTAG = Pattern.compile("(?<!\\S)#([^#\\s]+)");

    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "fileUniqueId", "title", "description", "tags", "category", "accessScope",
            "publicMessageUrl", "immediateReseed", "indexOnly", "autoDownloadOnDemand",
            "uploadLimitBytesPerSecond", "minimumSeedSeconds"
    );

    public static ResourcePublicationRequest from(JsonObject body, FileRecord file) {
        return from(body, file, SharePublicationPolicy.defaults());
    }

    public static ResourcePublicationRequest from(JsonObject body, FileRecord file, SharePublicationPolicy policy) {
        if (body == null) {
            throw new IllegalArgumentException("Publication request is required");
        }
        policy = policy == null ? SharePublicationPolicy.defaults() : policy;
        policy.requireShareable(file);
        rejectUnknownFields(body, ALLOWED_FIELDS);
        String scope = normalized(body.getString("accessScope", "OWNER_ONLY"), "accessScope", 1, 32);
        if (!Set.of("PUBLIC", "MEMBER_ACCESS", "OWNER_ONLY").contains(scope)) {
            throw new IllegalArgumentException("accessScope is invalid");
        }
        String publicUrl = normalizePublicUrl(body.getString("publicMessageUrl"));
        if ("PUBLIC".equals(scope) && publicUrl == null) {
            throw new IllegalArgumentException("PUBLIC sources require publicMessageUrl");
        }
        if (!"PUBLIC".equals(scope) && publicUrl != null) {
            throw new IllegalArgumentException("Private sources cannot include publicMessageUrl");
        }
        String title = normalized(
                body.getString("title", file.fileName()),
                "title",
                1,
                255
        );
        String description = nullableMultiline(body.getString("description", file.caption()), "description", 4096);
        JsonArray tags = tags(body.getJsonArray("tags", new JsonArray()), description, file.hasSensitiveContent());
        String category = policy.categoryFor(file, body.getString("category"));
        String uploadLimit = decimal(body.getString("uploadLimitBytesPerSecond"), true);
        long minimumSeedSeconds = body.getLong("minimumSeedSeconds", 0L);
        if (minimumSeedSeconds < 0) {
            throw new IllegalArgumentException("minimumSeedSeconds cannot be negative");
        }
        boolean downloaded = isDownloaded(file);
        return new ResourcePublicationRequest(
                title,
                description,
                tags,
                category,
                scope,
                publicUrl,
                downloaded || body.getBoolean("immediateReseed", false),
                downloaded ? false : body.getBoolean("indexOnly", true),
                body.getBoolean("autoDownloadOnDemand", false),
                uploadLimit,
                minimumSeedSeconds
        );
    }

    public JsonObject toPlatformCreate(FileRecord file, String nodeId, String opaqueToken) {
        if (file.size() < 0 || file.uniqueId() == null || file.uniqueId().isBlank()) {
            throw new IllegalArgumentException("Telegram file metadata is incomplete");
        }
        String fileName = normalized(file.fileName(), "fileName", 1, 255);
        if (fileName.contains("/") || fileName.contains("\\") || ".".equals(fileName) || "..".equals(fileName)) {
            throw new IllegalArgumentException("fileName is unsafe");
        }
        String mimeType = normalized(file.mimeType(), "mimeType", 1, 255);
        JsonObject source = new JsonObject()
                .put("ownerNodeId", nodeId)
                .put("opaqueSourceToken", opaqueToken)
                .put("accessScope", accessScope)
                .put("publicMessageUrl", publicMessageUrl)
                .put("fileUniqueId", file.uniqueId())
                .put("sourceFingerprint", telegram.files.share.UnifiedFileDownloadService
                        .sourceFingerprint(file.uniqueId(), file.size()))
                .put("fileName", fileName)
                .put("fileSize", Long.toString(file.size()))
                .put("mimeType", mimeType)
                .put("fileType", file.type())
                .put("downloaded", isDownloaded(file));
        return platformMetadata().put("source", source);
    }

    public JsonObject platformMetadata() {
        JsonObject policy = new JsonObject()
                .put("immediateReseed", immediateReseed)
                .put("indexOnly", indexOnly)
                .put("autoDownloadOnDemand", autoDownloadOnDemand)
                .put("uploadLimitBytesPerSecond", uploadLimitBytesPerSecond)
                .put("minimumSeedSeconds", minimumSeedSeconds);
        return new JsonObject()
                .put("title", title)
                .put("description", description)
                .put("tags", tags.copy())
                .put("category", category)
                .put("publicationPolicy", policy);
    }

    public static boolean isDownloaded(FileRecord file) {
        return file.isDownloadStatus(FileRecord.DownloadStatus.completed)
               && file.localPath() != null
               && !file.localPath().isBlank();
    }

    private static JsonArray tags(JsonArray input, String description, boolean hasSensitiveContent) {
        if (input.size() > 32) {
            throw new IllegalArgumentException("tags must contain at most 32 entries");
        }
        JsonArray normalized = new JsonArray();
        Set<String> unique = new HashSet<>();
        for (int index = 0; index < input.size(); index++) {
            Object value = input.getValue(index);
            if (!(value instanceof String raw)) {
                throw new IllegalArgumentException("tags must contain strings");
            }
            String tag = normalized(raw, "tag", 1, 64);
            if (!unique.add(tag.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("tags must be unique");
            }
            normalized.add(tag);
        }
        if (description != null) {
            Matcher matcher = HASHTAG.matcher(description);
            while (matcher.find()) {
                addTag(normalized, unique, matcher.group(1));
            }
        }
        if (hasSensitiveContent) {
            addTag(normalized, unique, "R18");
        }
        return normalized;
    }

    private static void addTag(JsonArray tags, Set<String> unique, String raw) {
        if (tags.size() >= 32) {
            throw new IllegalArgumentException("tags must contain at most 32 entries");
        }
        String tag = normalized(raw, "tag", 1, 64);
        if (unique.add(tag.toLowerCase(Locale.ROOT))) {
            tags.add(tag);
        }
    }

    private static String normalizePublicUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(raw.strip());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                || !Set.of("t.me", "telegram.me", "www.telegram.me").contains(host)
                || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getPath() == null || "/".equals(uri.getPath())
                || uri.getFragment() != null) {
                throw new IllegalArgumentException("publicMessageUrl is invalid");
            }
            return uri.toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("publicMessageUrl must be a public HTTPS Telegram URL");
        }
    }

    private static String decimal(String raw, boolean nullable) {
        if (raw == null && nullable) {
            return null;
        }
        if (raw == null || !raw.matches("0|[1-9][0-9]{0,29}")) {
            throw new IllegalArgumentException("Byte limits must be decimal strings");
        }
        return raw;
    }

    private static String nullableMultiline(String raw, String field, int max) {
        return raw == null || raw.isBlank() ? null : normalizedMultiline(raw, field, 1, max);
    }

    private static String normalized(String raw, String field, int min, int max) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String value = raw.strip().replaceAll("\\s+", " ");
        if (value.length() < min || value.length() > max
            || value.chars().anyMatch(character -> character < 32 || character == 127)) {
            throw new IllegalArgumentException(field + " contains invalid characters or length");
        }
        return value;
    }

    private static String normalizedMultiline(String raw, String field, int min, int max) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String value = raw.strip().replace("\r\n", "\n").replace('\r', '\n');
        if (value.length() < min || value.length() > max
            || value.chars().anyMatch(character -> (character < 32 && character != '\n') || character == 127)) {
            throw new IllegalArgumentException(field + " contains invalid characters or length");
        }
        return value;
    }

    private static void rejectUnknownFields(JsonObject body, Set<String> allowed) {
        for (String field : body.fieldNames()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException("Unknown publication field: " + field);
            }
        }
    }
}
