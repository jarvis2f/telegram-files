package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import telegram.files.repository.SeedNodeIdentityRecord;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NodeConfigurationServiceTest {

    @Test
    void fetchesAuthenticatedRuntimeConfiguration() {
        NodeIdentityService identityService = mock(NodeIdentityService.class);
        SeedNodeIdentityRecord identity = mock(SeedNodeIdentityRecord.class);
        when(identityService.access()).thenReturn(Future.succeededFuture(
                new NodeIdentityService.NodeAccess(identity, "token")
        ));
        SeedCoordinatorClient client = new SeedCoordinatorClient() {
            @Override
            public Future<JsonObject> get(String path, Map<String, String> headers) {
                assertEquals("/api/v1/nodes/config", path);
                assertEquals("Bearer token", headers.get("Authorization"));
                return Future.succeededFuture(new JsonObject()
                        .put("version", "cfg-3")
                        .put("heartbeatIntervalSeconds", 40)
                        .put("taskPullIntervalSeconds", 120)
                        .put("statisticsIntervalSeconds", 80)
                        .put("statisticsRolloutPercent", 30));
            }

            @Override
            public Future<JsonObject> post(String path, JsonObject body, Map<String, String> headers) {
                return Future.failedFuture("Unexpected POST");
            }

            @Override
            public Future<JsonObject> put(String path, JsonObject body, Map<String, String> headers) {
                return Future.failedFuture("Unexpected PUT");
            }

            @Override
            public Future<JsonObject> delete(String path, Map<String, String> headers) {
                return Future.failedFuture("Unexpected DELETE");
            }
        };

        NodeRuntimeConfiguration result =
                new NodeConfigurationService(identityService, client).refresh().result();

        assertEquals("cfg-3", result.version());
        assertEquals(40, result.heartbeatIntervalSeconds());
        assertEquals(120, result.taskPullIntervalSeconds());
    }
}
