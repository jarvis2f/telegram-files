package telegram.files.share;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class StreamingContentHashServiceTest {

    @TempDir
    Path temporary;

    @Test
    void streamsKnownSha256AndReportsLongProgress(Vertx vertx, VertxTestContext context) throws Exception {
        Path source = temporary.resolve("fixture.bin");
        Files.writeString(source, "telegram-files-m3");
        AtomicLong progress = new AtomicLong();

        new StreamingContentHashService(vertx)
                .sha256(source, progress::set, () -> false)
                .onComplete(context.succeeding(hash -> context.verify(() -> {
                    assertEquals(
                            "8ed31a76979f085f33aa4ff1b280ceb61ece66f2a8a95561a2f911451b7cdac3",
                            hash
                    );
                    assertEquals(Files.size(source), progress.get());
                    context.completeNow();
                })));
    }
}
