package telegram.files.share;

import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NodeRuntimeConfigurationTest {

    @Test
    void parsesPlatformConfiguration() {
        NodeRuntimeConfiguration configuration = NodeRuntimeConfiguration.fromJson(new JsonObject()
                .put("version", "cfg-2")
                .put("heartbeatIntervalSeconds", 45)
                .put("taskPullIntervalSeconds", 120)
                .put("statisticsIntervalSeconds", 90)
                .put("statisticsRolloutPercent", 20));

        assertEquals("cfg-2", configuration.version());
        assertEquals(45, configuration.heartbeatIntervalSeconds());
        assertEquals(120, configuration.taskPullIntervalSeconds());
        assertEquals(90, configuration.statisticsIntervalSeconds());
        assertEquals(20, configuration.statisticsRolloutPercent());
    }

    @Test
    void rejectsUnsafePlatformConfiguration() {
        JsonObject body = new JsonObject()
                .put("version", "cfg-2")
                .put("heartbeatIntervalSeconds", 4)
                .put("taskPullIntervalSeconds", 0)
                .put("statisticsIntervalSeconds", 90)
                .put("statisticsRolloutPercent", 20);

        assertThrows(IllegalArgumentException.class, () ->
                NodeRuntimeConfiguration.fromJson(body));

        JsonObject invalidPullInterval = body.copy()
                .put("heartbeatIntervalSeconds", 45)
                .put("taskPullIntervalSeconds", -1);
        assertThrows(IllegalArgumentException.class, () ->
                NodeRuntimeConfiguration.fromJson(invalidPullInterval));
    }
}
