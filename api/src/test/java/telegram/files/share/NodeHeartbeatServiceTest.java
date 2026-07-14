package telegram.files.share;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeHeartbeatServiceTest {

    @Test
    void timerDelayMeetsVertxMinimum() {
        assertEquals(1, NodeHeartbeatService.timerDelayMillis(0));
        assertEquals(1, NodeHeartbeatService.timerDelayMillis(-1));
        assertEquals(25, NodeHeartbeatService.timerDelayMillis(25));
    }

    @Test
    void retryDelayBacksOffWithBoundedJitterAndResetsAfterSuccess() {
        Duration interval = Duration.ofSeconds(30);

        assertEquals(30_000, NodeHeartbeatService.retryDelayMillis(interval, 0, 0));
        assertEquals(24_000, NodeHeartbeatService.retryDelayMillis(interval, 1, 0));
        assertEquals(60_000, NodeHeartbeatService.retryDelayMillis(interval, 2, 0.5));
        assertEquals(300_000, NodeHeartbeatService.retryDelayMillis(interval, 30, 1));
    }

    @Test
    void coordinationActionsAreDrivenByVersionChangesAndStatisticsDeadline() {
        NodeHeartbeatService.CoordinationActions actions = NodeHeartbeatService.actions(
                "7",
                "cfg-1",
                0,
                0,
                1_000,
                new io.vertx.core.json.JsonObject()
                        .put("taskVersion", "8")
                        .put("configVersion", "cfg-2"),
                2_000
        );

        assertTrue(actions.pullTasks());
        assertTrue(actions.refreshConfiguration());
        assertTrue(actions.reportStatistics());

        NodeHeartbeatService.CoordinationActions unchanged = NodeHeartbeatService.actions(
                "8",
                "cfg-2",
                0,
                0,
                3_000,
                new io.vertx.core.json.JsonObject()
                        .put("taskVersion", "8")
                        .put("configVersion", "cfg-2"),
                2_000
        );

        assertFalse(unchanged.pullTasks());
        assertFalse(unchanged.refreshConfiguration());
        assertFalse(unchanged.reportStatistics());
    }

    @Test
    void coordinationActionsCanForcePeriodicTaskPullsWithoutTaskVersionChanges() {
        NodeHeartbeatService.CoordinationActions due = NodeHeartbeatService.actions(
                "8",
                "cfg-2",
                10_000,
                30_000,
                60_000,
                new io.vertx.core.json.JsonObject()
                        .put("taskVersion", "8")
                        .put("configVersion", "cfg-2"),
                45_000
        );

        assertTrue(due.pullTasks());

        NodeHeartbeatService.CoordinationActions disabled = NodeHeartbeatService.actions(
                "8",
                "cfg-2",
                0,
                0,
                60_000,
                new io.vertx.core.json.JsonObject()
                        .put("taskVersion", "8")
                        .put("configVersion", "cfg-2"),
                45_000
        );

        assertFalse(disabled.pullTasks());

        NodeHeartbeatService.CoordinationActions notDue = NodeHeartbeatService.actions(
                "8",
                "cfg-2",
                10_000,
                30_000,
                60_000,
                new io.vertx.core.json.JsonObject()
                        .put("taskVersion", "8")
                        .put("configVersion", "cfg-2"),
                35_000
        );

        assertFalse(notDue.pullTasks());
    }
}
