package telegram.files.share;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
class TorrentViewStoreTest {

    @TempDir
    Path temporary;

    @Test
    void seedViewHardLinksTheTelegramSource(Vertx vertx, VertxTestContext context) throws Exception {
        Path base = temporary.toRealPath();
        Path source = base.resolve("telegram.mp3");
        byte[] content = "single-physical-copy".getBytes(StandardCharsets.UTF_8);
        Files.write(source, content);
        TorrentViewStore store = new TorrentViewStore(vertx, base.resolve("shared"));

        store.createSeedView("0123456789abcdef0123456789abcdef01234567",
                        "song.mp3", source, content.length)
                .onComplete(context.succeeding(view -> context.verify(() -> {
                    assertTrue(Files.isSameFile(source, view.content()));
                    assertEquals(content.length, Files.size(view.content()));
                    context.completeNow();
                })));
    }

    @Test
    void rejectsAnExistingViewBackedByDifferentStorage(
            Vertx vertx,
            VertxTestContext context
    ) throws Exception {
        String infoHash = "abcdef0123456789abcdef0123456789abcdef01";
        byte[] content = "same-bytes-different-inode".getBytes(StandardCharsets.UTF_8);
        Path base = temporary.toRealPath();
        Path source = base.resolve("telegram.mp3");
        Files.write(source, content);
        Path existing = base.resolve("shared/torrent-views")
                .resolve(infoHash).resolve("song.mp3");
        Files.createDirectories(existing.getParent());
        Files.write(existing, content);
        TorrentViewStore store = new TorrentViewStore(vertx, base.resolve("shared"));

        store.createSeedView(infoHash, "song.mp3", source, content.length)
                .onComplete(context.failing(failure -> context.verify(() -> {
                    assertTrue(failure.getMessage().contains("STORAGE_LAYOUT_UNSUPPORTED"));
                    context.completeNow();
                })));
    }
}
