package telegram.files.share;

import io.vertx.core.json.JsonObject;
import telegram.files.share.TelegramBootstrapTask.UnsupportedTaskException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Set;

public record TorrentControlTask(
        String taskId,
        String attemptId,
        String leaseToken,
        String deadlineAt,
        String resourceId,
        String infoHashV1,
        String controlType,
        long uploadLimitBytesPerSecond,
        String trackerBaseUrl,
        JsonObject raw
) implements NodeTaskEnvelope {

    public static final Set<String> TYPES = Set.of(
            "PAUSE_V1", "RESUME_V1", "CANCEL_V1", "RECHECK_V1", "SET_UPLOAD_LIMIT_V1",
            "ROTATE_TRACKER_CREDENTIAL_V1"
    );

    public TorrentControlTask {
        required(taskId, "taskId", 128);
        required(attemptId, "attemptId", 128);
        required(leaseToken, "leaseToken", 2048);
        required(resourceId, "resourceId", 128);
        if (!TYPES.contains(controlType)) throw new UnsupportedTaskException(controlType, "UNSUPPORTED_TASK_TYPE");
        if (infoHashV1 == null || !infoHashV1.matches("[a-f0-9]{40}")) {
            throw new IllegalArgumentException("infoHashV1 is invalid");
        }
        if (uploadLimitBytesPerSecond < 0
            || (!"SET_UPLOAD_LIMIT_V1".equals(controlType) && uploadLimitBytesPerSecond != 0)) {
            throw new IllegalArgumentException("uploadLimitBytesPerSecond is invalid");
        }
        if ("ROTATE_TRACKER_CREDENTIAL_V1".equals(controlType)) {
            if (trackerBaseUrl == null || !trackerBaseUrl.matches("https?://[^\\s]+/announce/")) {
                throw new IllegalArgumentException("trackerBaseUrl is invalid");
            }
        } else if (trackerBaseUrl != null) {
            throw new IllegalArgumentException("trackerBaseUrl is only valid for credential rotation");
        }
        try {
            Instant.parse(deadlineAt);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("deadlineAt is invalid", exception);
        }
        raw = raw.copy();
    }

    public static TorrentControlTask fromJson(JsonObject envelope) {
        if (envelope == null) throw new IllegalArgumentException("Task envelope is required");
        String type = envelope.getString("type");
        if (!TYPES.contains(type)) throw new UnsupportedTaskException(type, "UNSUPPORTED_TASK_TYPE");
        if (!Integer.valueOf(1).equals(envelope.getInteger("schemaVersion"))) {
            throw new UnsupportedTaskException(type, "UNSUPPORTED_SCHEMA_VERSION");
        }
        JsonObject payload = envelope.getJsonObject("payload");
        if (payload == null) throw new IllegalArgumentException("Task payload is required");
        return new TorrentControlTask(
                envelope.getString("taskId"), envelope.getString("attemptId"),
                envelope.getString("leaseToken"), envelope.getString("deadlineAt"),
                payload.getString("resourceId"), payload.getString("infoHashV1"), type,
                decimalLong(payload.getValue("uploadLimitBytesPerSecond")),
                payload.getString("trackerBaseUrl"), envelope
        );
    }

    @Override public long reservedBytes() { return 0; }
    @Override public String taskType() { return controlType; }
    @Override public int schemaVersion() { return 1; }

    @Override
    public String payloadDigest() {
        return sha256(String.join("\u0000", controlType, resourceId, infoHashV1,
                Long.toString(uploadLimitBytesPerSecond), trackerBaseUrl == null ? "" : trackerBaseUrl));
    }

    private static long decimalLong(Object raw) {
        if (raw == null) return 0;
        String value = raw.toString();
        if (!value.matches("0|[1-9][0-9]{0,18}")) {
            throw new IllegalArgumentException("uploadLimitBytesPerSecond is invalid");
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("uploadLimitBytesPerSecond exceeds node capacity", exception);
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
