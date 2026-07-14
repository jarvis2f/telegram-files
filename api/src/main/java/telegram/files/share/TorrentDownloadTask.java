package telegram.files.share;

import io.vertx.core.json.JsonObject;
import telegram.files.share.TelegramBootstrapTask.UnsupportedTaskException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;

public record TorrentDownloadTask(
        String taskId,
        String attemptId,
        String leaseToken,
        String deadlineAt,
        String resourceId,
        String torrentId,
        String torrentBase64,
        String infoHashV1,
        String contentSha256,
        String fileName,
        long fileSize,
        String mimeType,
        String telegramFileUniqueId,
        long reservedBytes,
        URI trackerBaseUri,
        JsonObject raw
) implements NodeTaskEnvelope {

    public static final String TYPE = "TORRENT_DOWNLOAD_V1";

    public static final int SCHEMA_VERSION = 1;

    public TorrentDownloadTask {
        required(taskId, "taskId", 128);
        required(attemptId, "attemptId", 128);
        required(leaseToken, "leaseToken", 2048);
        required(resourceId, "resourceId", 128);
        required(torrentId, "torrentId", 128);
        required(torrentBase64, "torrentBase64", 3_000_000);
        required(fileName, "fileName", 255);
        V1TorrentService.safeFileName(fileName, resourceId);
        if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("infoHashV1 is invalid");
        }
        if (contentSha256 == null || !contentSha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("contentSha256 is invalid");
        }
        if (fileSize < 0 || reservedBytes < fileSize) {
            throw new IllegalArgumentException("Torrent task byte counts are invalid");
        }
        if (trackerBaseUri == null || trackerBaseUri.getHost() == null
            || trackerBaseUri.getPath() == null || !trackerBaseUri.getPath().endsWith("/announce/")
            || trackerBaseUri.getQuery() != null || trackerBaseUri.getFragment() != null
            || trackerBaseUri.getUserInfo() != null
            || !("http".equalsIgnoreCase(trackerBaseUri.getScheme())
                 || "https".equalsIgnoreCase(trackerBaseUri.getScheme()))) {
            throw new IllegalArgumentException("trackerBaseUrl is invalid");
        }
        try {
            Instant.parse(deadlineAt);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("deadlineAt is invalid", exception);
        }
        raw = raw.copy();
    }

    public static TorrentDownloadTask fromJson(JsonObject envelope) {
        if (envelope == null) {
            throw new IllegalArgumentException("Task envelope is required");
        }
        String type = envelope.getString("type");
        if (!TYPE.equals(type)) {
            throw new UnsupportedTaskException(type, "UNSUPPORTED_TASK_TYPE");
        }
        Integer schemaVersion = envelope.getInteger("schemaVersion");
        if (schemaVersion == null || schemaVersion != SCHEMA_VERSION) {
            throw new UnsupportedTaskException(type, "UNSUPPORTED_SCHEMA_VERSION");
        }
        JsonObject payload = envelope.getJsonObject("payload");
        if (payload == null) {
            throw new IllegalArgumentException("Task payload is required");
        }
        return new TorrentDownloadTask(
                envelope.getString("taskId"), envelope.getString("attemptId"),
                envelope.getString("leaseToken"), envelope.getString("deadlineAt"),
                payload.getString("resourceId"), payload.getString("torrentId"),
                payload.getString("torrentBase64"), payload.getString("infoHashV1"),
                payload.getString("contentSha256"), payload.getString("fileName"),
                decimalLong(payload, "fileSize"), payload.getString("mimeType"),
                payload.getString("telegramFileUniqueId"), decimalLong(payload, "reservedBytes"),
                URI.create(payload.getString("trackerBaseUrl", "")), envelope
        );
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
    }

    @Override
    public String payloadDigest() {
        String fingerprint = String.join("\u0000",
                TYPE, Integer.toString(SCHEMA_VERSION), resourceId, torrentId, infoHashV1,
                contentSha256, fileName, Long.toString(fileSize), Long.toString(reservedBytes),
                mimeType == null ? "" : mimeType,
                telegramFileUniqueId == null ? "" : telegramFileUniqueId,
                trackerBaseUri.toString(), sha256(torrentBase64)
        );
        return sha256(fingerprint);
    }

    private static long decimalLong(JsonObject payload, String field) {
        String value = payload.getString(field);
        if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " exceeds node capacity", exception);
        }
    }

    private static void required(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
