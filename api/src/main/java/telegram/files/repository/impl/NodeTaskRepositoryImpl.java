package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.NodeTaskExecutionRecord;
import telegram.files.repository.NodeTaskRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class NodeTaskRepositoryImpl extends AbstractSqlRepository implements NodeTaskRepository {

    private final Pool pool;

    public NodeTaskRepositoryImpl(Pool pool) {
        super(pool);
        this.pool = Objects.requireNonNull(pool, "pool");
    }

    @Override
    public Future<NodeTaskExecutionRecord> persist(
            NodeTaskExecutionRecord execution,
            long reservedBytes,
            long reservationExpiresAt
    ) {
        if (reservedBytes < 0 || reservationExpiresAt < 0) {
            return Future.failedFuture(new IllegalArgumentException("Disk reservation is invalid"));
        }
            return pool.withTransaction(transaction -> get(transaction, execution.taskId()).compose(existing -> {
                if (existing != null) {
                    if (!existing.attemptId().equals(execution.attemptId())
                        || !existing.payloadDigest().equals(execution.payloadDigest())
                        || !existing.taskType().equals(execution.taskType())
                        || existing.schemaVersion() != execution.schemaVersion()) {
                        return Future.failedFuture(new IllegalStateException("TASK_PAYLOAD_CONFLICT"));
                    }
                    return Future.succeededFuture(existing);
                }
                return preparedQuery(transaction, """
                                INSERT INTO node_task_execution
                                (task_id, attempt_id, task_type, schema_version, payload_digest,
                                 envelope_ciphertext, state, progress_sequence, reported_sequence,
                                 progress_json, result_json, error_code, created_at, updated_at, version)
                                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """)
                        .execute(executionParameters(execution))
                        .compose(_ -> preparedQuery(transaction, """
                                        INSERT INTO disk_reservation
                                        (id, task_id, reserved_bytes, status, expires_at, released_at,
                                         created_at, updated_at, version)
                                        VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?, ?, 0)
                                        """)
                                .execute(Tuple.of(
                                        UUID.randomUUID().toString(),
                                        execution.taskId(),
                                        reservedBytes,
                                        reservationExpiresAt,
                                        execution.createdAt(),
                                        execution.updatedAt()
                                )))
                    .map(execution);
        })).recover(failure -> getByTaskId(execution.taskId()).compose(existing -> {
            if (existing != null
                && existing.attemptId().equals(execution.attemptId())
                && existing.payloadDigest().equals(execution.payloadDigest())) {
                return Future.succeededFuture(existing);
            }
            return Future.failedFuture(failure);
        }));
    }

    @Override
    public Future<NodeTaskExecutionRecord> getByTaskId(String taskId) {
        return get(pool, taskId);
    }

    @Override
    public Future<List<NodeTaskExecutionRecord>> listRecoverable(int limit) {
        return listByStates(
                "('PERSISTED', 'ACKNOWLEDGED', 'RUNNING')",
                limit
        );
    }

    @Override
    public Future<List<NodeTaskExecutionRecord>> listPendingReports(int limit) {
        return listByStates(
                "('RUNNING', 'COMPLETED_PENDING_REPORT', 'FAILED_PENDING_REPORT')",
                limit
        );
    }

    @Override
    public Future<Boolean> markAcknowledged(String taskId, long now) {
        return transition(taskId, "PERSISTED", "ACKNOWLEDGED", now);
    }

    @Override
    public Future<Boolean> markRunning(String taskId, long now) {
        return executeWrite("""
                        UPDATE node_task_execution SET state = 'RUNNING', updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state IN ('PERSISTED', 'ACKNOWLEDGED', 'RUNNING')
                        """, Tuple.of(now, taskId))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Boolean> recordProgress(String taskId, int sequence, String progressJson, long now) {
        if (sequence < 0 || progressJson == null) {
            return Future.failedFuture(new IllegalArgumentException("Task progress is invalid"));
        }
        return executeWrite("""
                        UPDATE node_task_execution SET progress_sequence = ?, progress_json = ?,
                          updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state = 'RUNNING' AND progress_sequence < ?
                        """, Tuple.of(sequence, progressJson, now, taskId, sequence))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Boolean> markProgressReported(String taskId, int sequence, long now) {
        return executeWrite("""
                        UPDATE node_task_execution SET reported_sequence = ?, updated_at = ?, version = version + 1
                        WHERE task_id = ? AND reported_sequence < ? AND progress_sequence >= ?
                        """, Tuple.of(sequence, now, taskId, sequence, sequence))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Boolean> markCompletionPending(String taskId, String resultJson, long now) {
        return executeWrite("""
                        UPDATE node_task_execution SET state = 'COMPLETED_PENDING_REPORT', result_json = ?,
                          updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state = 'RUNNING'
                        """, Tuple.of(resultJson, now, taskId))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Boolean> markFailurePending(String taskId, String errorCode, long now) {
        return executeWrite("""
                        UPDATE node_task_execution SET state = 'FAILED_PENDING_REPORT', error_code = ?,
                          updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state IN ('PERSISTED', 'ACKNOWLEDGED', 'RUNNING')
                        """, Tuple.of(errorCode, now, taskId))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Boolean> markTerminal(String taskId, String state, long now) {
        if (!List.of("COMPLETED", "FAILED", "OBSOLETE").contains(state)) {
            return Future.failedFuture(new IllegalArgumentException("Task terminal state is invalid"));
        }
        String expectedStates = "OBSOLETE".equals(state)
                ? "('PERSISTED', 'ACKNOWLEDGED', 'RUNNING', 'COMPLETED_PENDING_REPORT', 'FAILED_PENDING_REPORT')"
                : "('COMPLETED_PENDING_REPORT', 'FAILED_PENDING_REPORT')";
        return executeWrite("""
                        UPDATE node_task_execution SET state = ?, updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state IN %s
                        """.formatted(expectedStates), Tuple.of(state, now, taskId))
                .map(rows -> rows.rowCount() == 1);
    }

    @Override
    public Future<Void> releaseReservation(String taskId, long now) {
        return executeWrite("""
                        UPDATE disk_reservation SET status = 'RELEASED', released_at = ?,
                          updated_at = ?, version = version + 1
                        WHERE task_id = ? AND status = 'ACTIVE'
                        """, Tuple.of(now, now, taskId))
                .mapEmpty();
    }

    @Override
    public Future<Long> reservedBytes() {
        return pool.query("""
                        SELECT COALESCE(SUM(reserved_bytes), 0) AS total
                        FROM disk_reservation WHERE status = 'ACTIVE'
                        """)
                .execute()
                .map(rows -> {
                    Row row = rows.iterator().next();
                    Number value = (Number) row.getValue("total");
                    return value == null ? 0L : Math.max(0, value.longValue());
                });
    }

    @Override
    public Future<Integer> activeTaskCount() {
        return pool.query("""
                        SELECT COUNT(*) AS total FROM node_task_execution
                        WHERE state IN ('PERSISTED', 'ACKNOWLEDGED', 'RUNNING')
                        """)
                .execute()
                .map(rows -> {
                    Number value = (Number) rows.iterator().next().getValue("total");
                    return value == null ? 0 : Math.max(0, value.intValue());
                });
    }

    @Override
    public Future<Integer> activeTaskCount(String taskType) {
        if (taskType == null || !taskType.matches("[A-Z0-9_]{2,64}")) {
            return Future.failedFuture(new IllegalArgumentException("Task type is invalid"));
        }
        return preparedQuery(pool, """
                        SELECT COUNT(*) AS total FROM node_task_execution
                        WHERE task_type = ? AND state IN ('PERSISTED', 'ACKNOWLEDGED', 'RUNNING')
                        """)
                .execute(Tuple.of(taskType))
                .map(rows -> {
                    Number value = (Number) rows.iterator().next().getValue("total");
                    return value == null ? 0 : Math.max(0, value.intValue());
                });
    }

    private Future<List<NodeTaskExecutionRecord>> listByStates(String states, int limit) {
        if (limit < 1 || limit > 1000) {
            return Future.failedFuture(new IllegalArgumentException("Task list limit is invalid"));
        }
        return preparedQuery(pool, """
                        SELECT * FROM node_task_execution
                        WHERE state IN %s
                        ORDER BY updated_at, task_id LIMIT ?
                        """.formatted(states))
                .execute(Tuple.of(limit))
                .map(NodeTaskRepositoryImpl::mapRows);
    }

    private Future<Boolean> transition(String taskId, String expected, String target, long now) {
        return executeWrite("""
                        UPDATE node_task_execution SET state = ?, updated_at = ?, version = version + 1
                        WHERE task_id = ? AND state = ?
                        """, Tuple.of(target, now, taskId, expected))
                .map(rows -> rows.rowCount() == 1);
    }

    private Future<RowSet<Row>> executeWrite(String sql, Tuple parameters) {
        return pool.withTransaction(transaction ->
                preparedQuery(transaction, sql).execute(parameters));
    }

    private Future<NodeTaskExecutionRecord> get(SqlClient client, String taskId) {
        return preparedQuery(client, "SELECT * FROM node_task_execution WHERE task_id = ?")
                .execute(Tuple.of(taskId))
                .map(rows -> rows.iterator().hasNext() ? map(rows.iterator().next()) : null);
    }

    private static Tuple executionParameters(NodeTaskExecutionRecord record) {
        return Tuple.of(
                record.taskId(), record.attemptId(), record.taskType(), record.schemaVersion(),
                record.payloadDigest(), record.envelopeCiphertext(), record.state(),
                record.progressSequence(), record.reportedSequence(), record.progressJson(),
                record.resultJson(), record.errorCode(), record.createdAt(), record.updatedAt(),
                record.version()
        );
    }

    private static List<NodeTaskExecutionRecord> mapRows(RowSet<Row> rows) {
        List<NodeTaskExecutionRecord> records = new ArrayList<>();
        for (Row row : rows) {
            records.add(map(row));
        }
        return records;
    }

    private static NodeTaskExecutionRecord map(Row row) {
        return new NodeTaskExecutionRecord(
                row.getString("task_id"),
                row.getString("attempt_id"),
                row.getString("task_type"),
                number(row, "schema_version").intValue(),
                row.getString("payload_digest"),
                row.getString("envelope_ciphertext"),
                row.getString("state"),
                number(row, "progress_sequence").intValue(),
                number(row, "reported_sequence").intValue(),
                row.getString("progress_json"),
                row.getString("result_json"),
                row.getString("error_code"),
                number(row, "created_at").longValue(),
                number(row, "updated_at").longValue(),
                number(row, "version").intValue()
        );
    }

    private static Number number(Row row, String column) {
        return (Number) row.getValue(column);
    }
}
