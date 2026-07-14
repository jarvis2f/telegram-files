package telegram.files.share;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TorrentConfigurationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void remainsDisabledWithoutDockerOrQbittorrentAndMapsOnlySharedPaths() {
        TorrentConfiguration disabled = TorrentConfiguration.from(Map.of(), temporaryDirectory);
        assertFalse(disabled.enabled());

        TorrentConfiguration enabled = TorrentConfiguration.from(Map.of(
                "QBITTORRENT_URL", "http://127.0.0.1:8080",
                "QBITTORRENT_USERNAME", "user",
                "QBITTORRENT_PASSWORD", "password",
                "QBITTORRENT_SHARED_ROOT", "/qb/shared"
        ), temporaryDirectory);
        assertTrue(enabled.enabled());
        assertEquals(
                "/qb/shared/torrent-views/hash",
                enabled.qbittorrentPath(temporaryDirectory.resolve("torrent-views/hash"))
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> enabled.qbittorrentPath(temporaryDirectory.resolve("../escape"))
        );
    }
}
