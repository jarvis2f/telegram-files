package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import telegram.files.repository.TorrentRecord;
import telegram.files.repository.TorrentRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TorrentMetadataPreservationTest {

    private static final String HASH = "a".repeat(40);
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(2_000), ZoneOffset.UTC);

    @Test
    void controlUpdatesPreserveSeedAcquisitionMetadata() {
        TorrentRepository repository = mock(TorrentRepository.class);
        TorrentClient client = mock(TorrentClient.class);
        TorrentRecord original = record();
        when(repository.getByInfoHash(HASH)).thenReturn(Future.succeededFuture(original));
        when(client.get(HASH)).thenReturn(Future.succeededFuture(new TorrentClient.TorrentStatus(
                HASH, "uploading", 1.0, 17, 23, 0, 0, 1, "/data", true
        )));
        when(client.resume(HASH)).thenReturn(Future.succeededFuture());
        when(repository.save(any())).thenAnswer(invocation ->
                Future.succeededFuture(invocation.getArgument(0)));
        TorrentControlTask task = new TorrentControlTask(
                "task", "attempt", "lease", "2026-07-22T08:00:00Z",
                "resource", HASH, "RESUME_V1", 0, null, new JsonObject()
        );

        new TorrentControlExecutor(repository, client, CLOCK).execute(task).toCompletionStage()
                .toCompletableFuture().join();

        assertMetadata(captureSaved(repository));
    }

    @Test
    void reconciliationUpdatesPreserveSeedAcquisitionMetadata() {
        TorrentRepository repository = mock(TorrentRepository.class);
        TorrentClient client = mock(TorrentClient.class);
        when(repository.listActive(1_000)).thenReturn(Future.succeededFuture(List.of(record())));
        when(client.get(HASH)).thenReturn(Future.succeededFuture(new TorrentClient.TorrentStatus(
                HASH, "uploading", 1, 17, 23, 0, 0, 1, "/data", true
        )));
        when(repository.save(any())).thenAnswer(invocation ->
                Future.succeededFuture(invocation.getArgument(0)));

        new TorrentReconciler(
                repository, client, mock(LocalTorrentMetadataStore.class),
                mock(V1TorrentService.class), mock(NodeIdentityService.class), CLOCK
        ).runOnce().toCompletionStage().toCompletableFuture().join();

        assertMetadata(captureSaved(repository));
    }

    private static TorrentRecord captureSaved(TorrentRepository repository) {
        ArgumentCaptor<TorrentRecord> captor = ArgumentCaptor.forClass(TorrentRecord.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    private static void assertMetadata(TorrentRecord saved) {
        assertEquals("fixture.bin", saved.fileName());
        assertEquals(1234, saved.fileSize());
        assertEquals("application/octet-stream", saved.mimeType());
        assertEquals("telegram-unique", saved.telegramFileUniqueId());
        assertEquals("SEED", saved.acquiredVia());
        assertEquals(1_500L, saved.completedAt());
    }

    private static TorrentRecord record() {
        return new TorrentRecord(
                "torrent", "resource", "b".repeat(64), HASH,
                "torrents/a.torrent", "views/a/fixture.bin", "fixture.bin", 1234,
                "application/octet-stream", "telegram-unique", "SEED", 1_500L,
                "SEEDING", 1000, 17, 23, 0, 0, 1, "/data",
                "https://tracker.example/announce/", 10, 1_000, 500, 1_000, 0
        );
    }
}
