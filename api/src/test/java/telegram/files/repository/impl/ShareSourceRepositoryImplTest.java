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
import telegram.files.repository.ShareSourceRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(VertxExtension.class)
class ShareSourceRepositoryImplTest {

    @Test
    void savesUpdatesRetriesPublishesAndRevokes(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        ShareSourceRepositoryImpl repository = new ShareSourceRepositoryImpl(pool);
        ShareSourceRecord pending = record();

        pool.query(ShareSourceRecord.SCHEME).execute()
                .compose(_ -> repository.save(pending))
                .compose(_ -> repository.save(pending))
                .compose(_ -> repository.list())
                .compose(records -> {
                    context.verify(() -> assertEquals(1, records.size()));
                    return repository.markPending(
                            pending.id(), "PUBLISH_PENDING", "RATE_LIMITED", 1, 50, 20
                    );
                })
                .compose(_ -> repository.listRetryable(49, 10))
                .compose(records -> {
                    context.verify(() -> assertEquals(0, records.size()));
                    return repository.listRetryable(50, 10);
                })
                .compose(records -> {
                    context.verify(() -> assertEquals(1, records.size()));
                    return repository.markPublished(pending.id(), "resource-1", 60);
                })
                .compose(_ -> repository.getById(pending.id()))
                .compose(published -> {
                    context.verify(() -> {
                        assertEquals("PUBLISHED", published.status());
                        assertEquals("resource-1", published.platformResourceId());
                        assertNull(published.lastErrorCode());
                    });
                    return repository.markRevoked(pending.id(), 70);
                })
                .compose(_ -> repository.getById(pending.id()))
                .eventually(pool::close)
                .onComplete(context.succeeding(revoked -> context.verify(() -> {
                    assertEquals("REVOKED", revoked.status());
                    context.completeNow();
                })));
    }

    @Test
    void listsShareSourcesByPageWithTotal(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        ShareSourceRepositoryImpl repository = new ShareSourceRepositoryImpl(pool);

        pool.query(ShareSourceRecord.SCHEME).execute()
                .compose(_ -> repository.save(record("source-1", "key-1", 10)))
                .compose(_ -> repository.save(record("source-2", "key-2", 30)))
                .compose(_ -> repository.save(record("source-3", "key-3", 20)))
                .compose(_ -> repository.count())
                .compose(total -> {
                    context.verify(() -> assertEquals(3L, total));
                    return repository.listPage(1, 1);
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(records -> context.verify(() -> {
                    assertEquals(1, records.size());
                    assertEquals("source-3", records.getFirst().id());
                    context.completeNow();
                })));
    }

    private static ShareSourceRecord record() {
        return record("source-1", "abc123", 10);
    }

    private static ShareSourceRecord record(String id, String sourceKey, long createdAt) {
        return new ShareSourceRecord(
                id, sourceKey, null, 7, "file-" + id, 11, 22, 33,
                "fixture.bin", 1024, "application/octet-stream", false,
                "OWNER_ONLY", null, "ciphertext", "digest", "Fixture", null,
                "[]", null, false, true, true, null, 0,
                "PUBLISH_PENDING", "create-key-123456", "update-key-123456",
                "revoke-key-123456", 0, createdAt, null, createdAt, createdAt, 0
        );
    }
}
