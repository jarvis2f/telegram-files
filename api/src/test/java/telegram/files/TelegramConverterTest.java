package telegram.files;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import telegram.files.repository.ShareSourceRecord;
import telegram.files.repository.TorrentRecord;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelegramConverterTest {

    @Test
    void convertsSeedOnlyTorrentInConverter() {
        TorrentRecord torrent = new TorrentRecord(
                "torrent-1", "resource-1", "a".repeat(64), "b".repeat(40),
                "torrent-metadata/resource-1.torrent", "torrent-views/resource-1/file.bin",
                "file.bin", 1024, "application/octet-stream", null, "SEED", 2000L,
                "SEEDING", 1000, 1024, 0, 0, 0, 0,
                "/shared/view", "https://tracker/", 0, 2000, 1000, 2000, 0
        );

        var files = TelegramConverter.convertSeedOnlyFiles(List.of(torrent));

        assertEquals(1, files.size());
        assertEquals("seed:resource-1", files.getFirst().getString("uniqueId"));
        assertEquals("SEED", files.getFirst().getString("source"));
        assertEquals("completed", files.getFirst().getString("downloadStatus"));
        assertEquals("idle", files.getFirst().getString("transferStatus"));
        assertEquals(1024L, files.getFirst().getLong("downloadedSize"));
        assertEquals(false, files.getFirst().getBoolean("hasSensitiveContent"));
        assertTrue(files.getFirst().containsKey("extra"));
        assertTrue(files.getFirst().getString("localPath").endsWith("torrent-views/resource-1/file.bin"));
    }

    @Test
    void seedOnlyTorrentDownloadedFromSeedIsCompleteEvenWhenPaused() {
        TorrentRecord torrent = new TorrentRecord(
                "torrent-1", "resource-1", "a".repeat(64), "b".repeat(40),
                "torrent-metadata/resource-1.torrent", "torrent-views/resource-1/file.bin",
                "file.bin", 1024, "application/octet-stream", null, "SEED", 2000L,
                "PAUSED", 500, 0, 0, 0, 0, 0,
                "/shared/view", "https://tracker/", 0, 2000, 1000, 2000, 0
        );

        var file = TelegramConverter.convertSeedOnlyFiles(List.of(torrent)).getFirst();

        assertEquals("completed", file.getString("downloadStatus"));
        assertEquals(1024L, file.getLong("downloadedSize"));
    }

    @Test
    void enrichesPublishedShareMetadataForFileLists() {
        ShareSourceRecord share = new ShareSourceRecord(
                "source-local-id", "source-key", "seed-resource-id", 1L, "unique-file",
                42L, 3L, 2L, "archive.zip", 1024L, "application/zip", true,
                "MEMBER_ACCESS", null, "ciphertext", "digest", "Indexed title",
                "line one\nline two", new JsonArray().add("docs").add("seed").encode(),
                "archive", false, false, false, null, 0L, "PUBLISHED",
                "create-key", "update-key", "revoke-key", 0, 0L, null,
                1000L, 2000L, 0
        );
        JsonObject file = new JsonObject().put("uniqueId", "unique-file");

        TelegramConverter.enrichTelegramFile(file, null, share);

        assertEquals("source-local-id", file.getString("sharedSourceId"));
        assertEquals("seed-resource-id", file.getString("sharedResourceId"));
        assertEquals("Indexed title", file.getString("shareTitle"));
        assertEquals("line one\nline two", file.getString("shareDescription"));
        assertEquals("MEMBER_ACCESS", file.getString("shareAccessScope"));
        assertEquals(List.of("docs", "seed"), file.getJsonArray("shareTags").getList());
    }
}
