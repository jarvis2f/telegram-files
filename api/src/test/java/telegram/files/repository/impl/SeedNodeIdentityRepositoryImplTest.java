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
import telegram.files.repository.SeedNodeIdentityRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@ExtendWith(VertxExtension.class)
class SeedNodeIdentityRepositoryImplTest {

    @Test
    void saveReplacesCredentialEnvelopeAsOneIdentity(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        SeedNodeIdentityRepositoryImpl repository = new SeedNodeIdentityRepositoryImpl(pool);
        SeedNodeIdentityRecord first = record("cipher-one", 100);
        SeedNodeIdentityRecord second = record("cipher-two", 200);

        pool.query(SeedNodeIdentityRecord.SCHEME).execute()
                .compose(_ -> repository.save(first))
                .compose(_ -> repository.save(second))
                .compose(_ -> repository.getCurrent())
                .compose(saved -> {
                    context.verify(() -> {
                        assertEquals("cipher-two", saved.credentialCiphertext());
                        assertEquals(200, saved.tokenExpireAt());
                    });
                    return repository.clear();
                })
                .compose(_ -> repository.getCurrent())
                .eventually(pool::close)
                .onComplete(context.succeeding(saved -> context.verify(() -> {
                    assertNull(saved);
                    context.completeNow();
                })));
    }

    private static SeedNodeIdentityRecord record(String ciphertext, long expiresAt) {
        return new SeedNodeIdentityRecord(
                "https://seed.example.test", "node-one", "Node", ciphertext,
                expiresAt, null, "BOUND", 1, expiresAt
        );
    }
}
