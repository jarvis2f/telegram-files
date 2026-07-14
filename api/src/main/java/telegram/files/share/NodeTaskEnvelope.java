package telegram.files.share;

import io.vertx.core.json.JsonObject;

public sealed interface NodeTaskEnvelope permits TelegramBootstrapTask, TorrentDownloadTask, TorrentControlTask {

    String taskId();

    String attemptId();

    String leaseToken();

    String deadlineAt();

    String resourceId();

    long reservedBytes();

    JsonObject raw();

    String taskType();

    int schemaVersion();

    String payloadDigest();

    static NodeTaskEnvelope fromJson(JsonObject envelope) {
        String type = envelope == null ? null : envelope.getString("type");
        if (TelegramBootstrapTask.TYPE.equals(type)) {
            return TelegramBootstrapTask.fromJson(envelope);
        }
        if (TorrentDownloadTask.TYPE.equals(type)) {
            return TorrentDownloadTask.fromJson(envelope);
        }
        if (TorrentControlTask.TYPES.contains(type)) {
            return TorrentControlTask.fromJson(envelope);
        }
        throw new TelegramBootstrapTask.UnsupportedTaskException(type, "UNSUPPORTED_TASK_TYPE");
    }
}
