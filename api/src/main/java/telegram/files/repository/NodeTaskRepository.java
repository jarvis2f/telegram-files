package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface NodeTaskRepository {

    Future<NodeTaskExecutionRecord> persist(
            NodeTaskExecutionRecord execution,
            long reservedBytes,
            long reservationExpiresAt
    );

    Future<NodeTaskExecutionRecord> getByTaskId(String taskId);

    Future<List<NodeTaskExecutionRecord>> listRecoverable(int limit);

    Future<List<NodeTaskExecutionRecord>> listPendingReports(int limit);

    Future<Boolean> markAcknowledged(String taskId, long now);

    Future<Boolean> markRunning(String taskId, long now);

    Future<Boolean> recordProgress(String taskId, int sequence, String progressJson, long now);

    Future<Boolean> markProgressReported(String taskId, int sequence, long now);

    Future<Boolean> markCompletionPending(String taskId, String resultJson, long now);

    Future<Boolean> markFailurePending(String taskId, String errorCode, long now);

    Future<Boolean> markTerminal(String taskId, String state, long now);

    Future<Void> releaseReservation(String taskId, long now);

    Future<Long> reservedBytes();

    Future<Integer> activeTaskCount();

    default Future<Integer> activeTaskCount(String taskType) {
        return activeTaskCount();
    }
}
