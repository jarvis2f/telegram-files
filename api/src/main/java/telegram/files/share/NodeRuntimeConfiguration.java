package telegram.files.share;

import io.vertx.core.json.JsonObject;

public record NodeRuntimeConfiguration(
        String version,
        int heartbeatIntervalSeconds,
        int taskPullIntervalSeconds,
        int statisticsIntervalSeconds,
        int statisticsRolloutPercent
) {
    public NodeRuntimeConfiguration {
        if (version == null || version.isBlank() || version.length() > 128) {
            throw new IllegalArgumentException("Node configuration version is invalid");
        }
        if (heartbeatIntervalSeconds < 5 || heartbeatIntervalSeconds > 600) {
            throw new IllegalArgumentException("Heartbeat interval is invalid");
        }
        if (taskPullIntervalSeconds < 0 || taskPullIntervalSeconds > 3_600) {
            throw new IllegalArgumentException("Task pull interval is invalid");
        }
        if (statisticsIntervalSeconds < 5 || statisticsIntervalSeconds > 3_600) {
            throw new IllegalArgumentException("Statistics interval is invalid");
        }
        if (statisticsRolloutPercent < 0 || statisticsRolloutPercent > 100) {
            throw new IllegalArgumentException("Statistics rollout percent is invalid");
        }
    }

    public static NodeRuntimeConfiguration fromJson(JsonObject body) {
        if (body == null) {
            throw new IllegalArgumentException("Node configuration is missing");
        }
        return new NodeRuntimeConfiguration(
                body.getString("version"),
                requiredInteger(body, "heartbeatIntervalSeconds"),
                requiredInteger(body, "taskPullIntervalSeconds"),
                requiredInteger(body, "statisticsIntervalSeconds"),
                requiredInteger(body, "statisticsRolloutPercent")
        );
    }

    private static int requiredInteger(JsonObject body, String name) {
        Integer value = body.getInteger(name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is missing");
        }
        return value;
    }
}
