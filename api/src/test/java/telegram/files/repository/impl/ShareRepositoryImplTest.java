package telegram.files.repository.impl;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.jdbcclient.JDBCConnectOptions;
import io.vertx.jdbcclient.JDBCPool;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.FileRecord;
import telegram.files.repository.ShareJobRecord;
import telegram.files.share.FileReadyForShare;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class ShareRepositoryImplTest {

    @Test
    void enqueueIsIdempotentAndRecoveryReconcilesCompletedFiles(
            Vertx vertx,
            VertxTestContext context
    ) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        ShareRepositoryImpl repository = new ShareRepositoryImpl(pool);
        FileReadyForShare event = new FileReadyForShare(7, 100, 99);

        pool.query(FileRecord.SCHEME).execute()
                .compose(_ -> pool.query(
                        new ShareJobRecord.ShareJobRecordDefinition().getScheme()
                ).execute())
                .compose(_ -> pool.query("""
                        INSERT INTO file_record
                            (id, unique_id, telegram_id, type, local_path,
                             download_status, completion_date)
                        VALUES (7, 'file-7', 99, 'file', '/tmp/file-7',
                                'completed', 100)
                        """).execute())
                .compose(_ -> repository.enqueueFileReady(event))
                .compose(_ -> repository.enqueueFileReady(event))
                .compose(_ -> pool.query(
                        "SELECT COUNT(*) AS count FROM share_job"
                ).execute())
                .compose(rows -> {
                    context.verify(() -> assertEquals(
                            1L,
                            ((Number) rows.iterator().next().getValue("count")).longValue()
                    ));
                    return pool.query(
                            "DELETE FROM share_job"
                    ).execute();
                })
                .compose(_ -> repository.recoverPendingJobs())
                .compose(_ -> pool.query("""
                        SELECT COUNT(*) AS count
                        FROM share_job
                        WHERE status = 'PENDING'
                        """).execute())
                .eventually(pool::close)
                .onComplete(context.succeeding(rows -> context.verify(() -> {
                    assertEquals(
                            1L,
                            ((Number) rows.iterator().next().getValue("count")).longValue()
                    );
                    context.completeNow();
                })));
    }
}
