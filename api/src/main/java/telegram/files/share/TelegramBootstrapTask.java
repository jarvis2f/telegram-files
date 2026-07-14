package telegram.files.share;

import io.vertx.core.json.JsonObject;
import telegram.files.share.model.OpaqueSourceToken;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;

public record TelegramBootstrapTask(
        String taskId,
        String attemptId,
        String leaseToken,
        String deadlineAt,
        String resourceId,
        String sourceId,
        String fileUniqueId,
        String fileName,
        long fileSize,
        String accessScope,
        long reservedBytes,
        String opaqueSourceToken,
        JsonObject raw
) implements NodeTaskEnvelope {

    public static final String TYPE = "TELEGRAM_BOOTSTRAP_V1";

    public static final int SCHEMA_VERSION = 1;

    public TelegramBootstrapTask {
        required(taskId, "taskId", 128);
        required(attemptId, "attemptId", 128);
        required(leaseToken, "leaseToken", 2048);
        required(resourceId, "resourceId", 128);
        required(sourceId, "sourceId", 128);
        required(fileUniqueId, "fileUniqueId", 512);
        required(accessScope, "accessScope", 32);
        if (!List.of("PUBLIC", "MEMBER_ACCESS", "OWNER_ONLY").contains(accessScope)) {
            throw new IllegalArgumentException("accessScope is invalid");
        }
        if (fileSize < 0 || reservedBytes < fileSize || reservedBytes < 0) {
            throw new IllegalArgumentException("Bootstrap byte counts are invalid");
        }
        OpaqueSourceToken.digest(opaqueSourceToken);
        try {
            Instant.parse(deadlineAt);
        } catch (DateTimeParseException | NullPointerException exception) {
            throw new IllegalArgumentException("deadlineAt is invalid", exception);
        }
        raw = raw.copy();
    }

    public static TelegramBootstrapTask fromJson(JsonObject envelope) {
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
        return new TelegramBootstrapTask(
                envelope.getString("taskId"),
                envelope.getString("attemptId"),
                envelope.getString("leaseToken"),
                envelope.getString("deadlineAt"),
                payload.getString("resourceId"),
                payload.getString("sourceId"),
                payload.getString("fileUniqueId"),
                payload.getString("fileName"),
                decimalLong(payload, "fileSize"),
                payload.getString("accessScope"),
                decimalLong(payload, "reservedBytes"),
                payload.getString("opaqueSourceToken"),
                envelope
        );
    }

    public String payloadDigest() {
        String fingerprint = String.join("\u0000",
                TYPE,
                Integer.toString(SCHEMA_VERSION),
                resourceId,
                sourceId,
                fileUniqueId,
                Long.toString(fileSize),
                accessScope,
                Long.toString(reservedBytes),
                OpaqueSourceToken.digest(opaqueSourceToken)
        );
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(fingerprint.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public int schemaVersion() {
        return SCHEMA_VERSION;
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

    public static final class UnsupportedTaskException extends IllegalArgumentException {
        private final String taskType;

        private final String errorCode;

        UnsupportedTaskException(String taskType, String errorCode) {
            super(errorCode);
            this.taskType = taskType;
            this.errorCode = errorCode;
        }

        public String taskType() {
            return taskType;
        }

        public String errorCode() {
            return errorCode;
        }
    }
}
