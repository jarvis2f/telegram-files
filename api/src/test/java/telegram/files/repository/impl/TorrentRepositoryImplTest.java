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
import telegram.files.repository.TorrentRecord;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class TorrentRepositoryImplTest {

    @Test
    void preservesIdentityAndUpdatesRuntimeStatus(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        TorrentRepositoryImpl repository = new TorrentRepositoryImpl(pool);
        String infoHash = "b".repeat(40);
        TorrentRecord record = new TorrentRecord(
                "torrent-1", "resource-1", "a".repeat(64), infoHash,
                "torrent-metadata/resource-1.torrent", "torrent-views/resource-1/file.bin",
                "CHECKING", 0, 0, 0, 0, 0, 0, "/shared/view", "https://tracker/", 0,
                10, 10, 10, 0
        );

        pool.query(TorrentRecord.SCHEME).execute()
                .compose(_ -> repository.save(record))
                .compose(_ -> repository.updateStatus(
                        infoHash, "SEEDING", 1000, 1024, 128,
                        64, 32, 3, "/shared/view", 20
                ))
                .compose(updated -> {
                    context.verify(() -> assertTrue(updated));
                    return repository.getByResourceId("resource-1");
                })
                .compose(saved -> {
                    context.verify(() -> {
                        assertEquals(infoHash, saved.infoHashV1());
                        assertEquals("SEEDING", saved.status());
                        assertEquals(1000, saved.progressPermille());
                        assertEquals(1, saved.version());
                    });
                    return repository.countByStatuses(List.of("SEEDING", "DOWNLOADING"));
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(count -> context.verify(() -> {
                    assertEquals(1, count);
                    context.completeNow();
                })));
    }

    @Test
    void paginatesAndFiltersSeedOnlyRecords(Vertx vertx, VertxTestContext context) {
        Pool pool = JDBCPool.pool(
                vertx,
                new JDBCConnectOptions().setJdbcUrl("jdbc:sqlite::memory:"),
                new PoolOptions().setMaxSize(1)
        );
        TorrentRepositoryImpl repository = new TorrentRepositoryImpl(pool);
        TorrentRecord older = seedOnly("torrent-1", "resource-1", "alpha-old.bin", 1024, 100);
        TorrentRecord newer = seedOnly("torrent-2", "resource-2", "alpha-new.bin", 2048, 200);
        TorrentRecord linked = new TorrentRecord(
                "torrent-3", "resource-3", "c".repeat(64), "d".repeat(40),
                "torrent-metadata/resource-3.torrent", "torrent-views/resource-3/file.bin",
                "linked.bin", 4096, "application/octet-stream", "telegram-id",
                "SEED", 300L, "SEEDING", 1000, 4096, 0, 0, 0, 0,
                "/shared/view", "https://tracker/", 0, 300, 300, 300, 0
        );

        pool.query(TorrentRecord.SCHEME).execute()
                .compose(_ -> repository.save(older))
                .compose(_ -> repository.save(newer))
                .compose(_ -> repository.save(linked))
                .compose(_ -> repository.listSeedOnly(Map.of(
                        "limit", "1", "search", "alpha", "type", "file"
                )))
                .compose(first -> {
                    context.verify(() -> {
                        assertEquals(2, first.v2);
                        assertEquals(List.of("resource-2"), first.v1.stream()
                                .map(TorrentRecord::resourceId).toList());
                    });
                    return repository.listSeedOnly(Map.of(
                            "limit", "1", "seedOffset", "1", "search", "alpha", "type", "file"
                    ));
                })
                .compose(second -> {
                    context.verify(() -> {
                        assertEquals(2, second.v2);
                        assertEquals(List.of("resource-1"), second.v1.stream()
                                .map(TorrentRecord::resourceId).toList());
                    });
                    return repository.countSeedOnlyWithType(Map.of("search", "alpha", "type", "file"));
                })
                .eventually(pool::close)
                .onComplete(context.succeeding(counts -> context.verify(() -> {
                    assertEquals(2, counts.getInteger("file"));
                    assertEquals(0, counts.getInteger("media"));
                    context.completeNow();
                })));
    }

    private static TorrentRecord seedOnly(
            String id, String resourceId, String fileName, long size, long completedAt
    ) {
        return new TorrentRecord(
                id, resourceId, "a".repeat(64), Integer.toHexString(resourceId.hashCode())
                .replace("-", "0").repeat(10).substring(0, 40),
                "torrent-metadata/" + resourceId + ".torrent",
                "torrent-views/" + resourceId + "/" + fileName,
                fileName, size, "application/octet-stream", null, "SEED", completedAt,
                "SEEDING", 1000, size, 0, 0, 0, 0, "/shared/view", "https://tracker/",
                0, completedAt, completedAt, completedAt, 0
        );
    }
}
