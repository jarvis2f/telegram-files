package telegram.files.repository.impl;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import telegram.files.repository.ShareRepository;
import telegram.files.share.FileReadyForShare;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class ShareRepositoryImpl extends AbstractSqlRepository implements ShareRepository {

    private static final Log log = LogFactory.get();

    private static final String JOB_TYPE = "FILE_READY";

    private final Clock clock;

    private final Duration runningTimeout;

    public ShareRepositoryImpl(SqlClient sqlClient) {
        this(sqlClient, Clock.systemUTC(), Duration.ofMinutes(15));
    }

    ShareRepositoryImpl(SqlClient sqlClient, Clock clock, Duration runningTimeout) {
        super(sqlClient);
        this.clock = Objects.requireNonNull(clock);
        if (runningTimeout == null || runningTimeout.isNegative() || runningTimeout.isZero()) {
            throw new IllegalArgumentException("runningTimeout must be positive");
        }
        this.runningTimeout = runningTimeout;
    }

    @Override
    public Future<Void> enqueueFileReady(FileReadyForShare event) {
        Objects.requireNonNull(event);
        String idempotencyKey = idempotencyKey(event);
        return preparedQuery("""
                        SELECT id FROM share_job WHERE idempotency_key = ?
                        """)
                .execute(Tuple.of(idempotencyKey))
                .compose(rows -> {
                    if (rows.iterator().hasNext()) {
                        return Future.succeededFuture();
                    }
                    long now = clock.millis();
                    return preparedQuery("""
                                    INSERT INTO share_job
                                        (id, job_type, idempotency_key, status,
                                         file_record_id, record_version, telegram_id,
                                         attempt_count, next_attempt_at, last_error_code,
                                         created_at, updated_at)
                                    VALUES (?, ?, ?, 'PENDING', ?, ?, ?, 0, ?, NULL, ?, ?)
                                    """)
                            .execute(Tuple.of(
                                    UUID.randomUUID().toString(),
                                    JOB_TYPE,
                                    idempotencyKey,
                                    event.fileRecordId(),
                                    event.recordVersion(),
                                    event.telegramId(),
                                    now,
                                    now,
                                    now
                            ))
                            .<Void>mapEmpty()
                            .recover(failure -> preparedQuery("""
                                            SELECT id FROM share_job WHERE idempotency_key = ?
                                            """)
                                    .execute(Tuple.of(idempotencyKey))
                                    .compose(duplicate -> duplicate.iterator().hasNext()
                                            ? Future.<Void>succeededFuture()
                                            : Future.<Void>failedFuture(failure)));
                });
    }

    @Override
    public Future<Void> recoverPendingJobs() {
        long now = clock.millis();
        long staleBefore = now - runningTimeout.toMillis();
        return preparedQuery("""
                        UPDATE share_job
                        SET status = 'PENDING',
                            attempt_count = attempt_count + 1,
                            next_attempt_at = ?,
                            last_error_code = 'PROCESS_RESTARTED',
                            updated_at = ?
                        WHERE status = 'RUNNING' AND updated_at < ?
                        """)
                .execute(Tuple.of(now, now, staleBefore))
                .compose(_ -> sqlClient.query("""
                                SELECT id, telegram_id, completion_date, type
                                FROM file_record
                                WHERE download_status = 'completed'
                                  AND local_path IS NOT NULL
                                  AND local_path <> ''
                                """)
                        .execute())
                .compose(this::enqueueMissingCompletedFiles)
                .onFailure(failure -> log.error(
                        "Failed to reconcile recoverable share jobs: {}",
                        failure.getMessage()
                ));
    }

    private Future<Void> enqueueMissingCompletedFiles(RowSet<Row> rows) {
        List<Future<Void>> futures = new ArrayList<>();
        for (Row row : rows) {
            if ("thumbnail".equals(row.getString("type"))) {
                continue;
            }
            Number fileId = (Number) row.getValue("id");
            Number telegramId = (Number) row.getValue("telegram_id");
            Number completionDate = (Number) row.getValue("completion_date");
            if (fileId == null || fileId.longValue() <= 0
                || telegramId == null || telegramId.longValue() <= 0) {
                continue;
            }
            futures.add(enqueueFileReady(new FileReadyForShare(
                    fileId.longValue(),
                    completionDate == null ? 0 : completionDate.longValue(),
                    telegramId.longValue()
            )));
        }
        if (futures.isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.all(futures).mapEmpty();
    }

    static String idempotencyKey(FileReadyForShare event) {
        return "%s:%d:%d".formatted(
                JOB_TYPE,
                event.fileRecordId(),
                event.recordVersion()
        );
    }
}
