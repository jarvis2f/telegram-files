package telegram.files.repository.impl;

import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.DiskReservationRecord;
import telegram.files.repository.NodeTaskExecutionRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class NodeTaskRepositoryImplTest {

    @Test
    void persistsBeforeAckAndReleasesReservationAtLocalTerminal(
            Vertx vertx,
            VertxTestContext context
    ) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        NodeTaskRepositoryImpl repository = new NodeTaskRepositoryImpl(pool);
        NodeTaskExecutionRecord record = new NodeTaskExecutionRecord(
                "task-1", "attempt-1", "TELEGRAM_BOOTSTRAP_V1", 1,
                "a".repeat(64), "ciphertext", "PERSISTED", -1, -1,
                null, null, null, 10, 10, 0
        );

        pool.query(NodeTaskExecutionRecord.SCHEME).execute()
                .compose(_ -> pool.query(DiskReservationRecord.SCHEME).execute())
                .compose(_ -> repository.persist(record, 4096, 1000))
                .compose(_ -> repository.persist(record, 4096, 1000))
                .compose(_ -> repository.reservedBytes())
                .compose(reserved -> {
                    context.verify(() -> assertEquals(4096, reserved));
                    return repository.markAcknowledged("task-1", 20);
                })
                .compose(acknowledged -> {
                    context.verify(() -> assertTrue(acknowledged));
                    return repository.markRunning("task-1", 30);
                })
                .compose(_ -> repository.recordProgress(
                        "task-1", 1, "{\"phase\":\"HASHING\"}", 40
                ))
                .compose(_ -> repository.markCompletionPending(
                        "task-1", "{\"sha256\":\"hash\"}", 50
                ))
                .compose(_ -> repository.releaseReservation("task-1", 50))
                .compose(_ -> repository.markTerminal("task-1", "COMPLETED", 60))
                .compose(_ -> repository.reservedBytes())
                .eventually(pool::close)
                .onComplete(context.succeeding(reserved -> context.verify(() -> {
                    assertEquals(0, reserved);
                    context.completeNow();
                })));
    }
}
