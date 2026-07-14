package telegram.files.share;

import io.vertx.core.json.JsonObject;

import java.util.Set;

public record FileReadyForShare(long fileRecordId, long recordVersion, long telegramId) {

    private static final Set<String> ALLOWED_FIELDS =
            Set.of("fileRecordId", "recordVersion", "telegramId");

    public FileReadyForShare {
        if (fileRecordId <= 0 || recordVersion < 0 || telegramId <= 0) {
            throw new IllegalArgumentException("File-ready identifiers are invalid");
        }
    }

    public static FileReadyForShare fromJson(JsonObject json) {
        if (json == null || !ALLOWED_FIELDS.containsAll(json.fieldNames())) {
            throw new IllegalArgumentException("File-ready payload contains unsupported fields");
        }
        Long fileRecordId = json.getLong("fileRecordId");
        Long recordVersion = json.getLong("recordVersion");
        Long telegramId = json.getLong("telegramId");
        if (fileRecordId == null || recordVersion == null || telegramId == null) {
            throw new IllegalArgumentException("File-ready payload is incomplete");
        }
        return new FileReadyForShare(fileRecordId, recordVersion, telegramId);
    }

    public JsonObject toJson() {
        return JsonObject.of(
                "fileRecordId", fileRecordId,
                "recordVersion", recordVersion,
                "telegramId", telegramId
        );
    }
}
