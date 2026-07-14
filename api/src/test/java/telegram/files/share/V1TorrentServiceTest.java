package telegram.files.share;

import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(VertxExtension.class)
class V1TorrentServiceTest {

    private static final String FIXTURE_BASE64 =
            "ZDQ6aW5mb2Q2Omxlbmd0aGkxMDI0ZTQ6bmFtZTExOmZpeHR1cmUuYmluMTI6cGllY2UgbGVuZ3RoaTI2MjE0NGU2OnBpZWNlczIwOmDKy/PXLh54NCA9pggDexv4O0DoNzpwcml2YXRlaTFlZWU=";

    private static final String FIXTURE_INFO_HASH = "12a4577456eb1aa06a37a5d6ccebaea54713c4ab";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTheCrossProjectCanonicalFixtureAndTrackerInjectionPreservesInfoHash(
            Vertx vertx,
            VertxTestContext context
    ) throws Exception {
        Path content = temporaryDirectory.resolve("fixture.bin");
        Files.write(content, new byte[1024]);
        V1TorrentService service = new V1TorrentService(vertx);

        service.create(content, "fixture.bin", 1024)
                .onComplete(context.succeeding(metadata -> context.verify(() -> {
                    assertEquals(FIXTURE_BASE64, metadata.canonicalBase64());
                    assertEquals(FIXTURE_INFO_HASH, metadata.infoHashV1());
                    assertEquals(V1TorrentService.MIN_PIECE_LENGTH, metadata.pieceLength());
                    assertEquals(1, metadata.pieceCount());

                    byte[] announced = service.withTracker(
                            metadata,
                            URI.create("http://127.0.0.1:8081/announce/"),
                            "tracker_fixture_credential_0123456789"
                    );
                    assertTrue(new String(announced, java.nio.charset.StandardCharsets.UTF_8)
                            .contains("announce"));
                    assertArrayEquals(
                            metadata.infoBytes(),
                            service.parseCanonical(
                                    FIXTURE_BASE64, FIXTURE_INFO_HASH, "fixture.bin", 1024
                            ).infoBytes()
                    );
                    assertThrows(IllegalArgumentException.class, () -> service.parseCanonical(
                            Base64.getEncoder().encodeToString(announced),
                            FIXTURE_INFO_HASH,
                            "fixture.bin",
                            1024
                    ));
                    context.completeNow();
                })));
    }

    @Test
    void selectsDeterministicBoundedPieceLengthsAndRejectsUnsafeNames() {
        assertEquals(V1TorrentService.MIN_PIECE_LENGTH, V1TorrentService.selectPieceLength(0));
        assertEquals(
                V1TorrentService.MIN_PIECE_LENGTH,
                V1TorrentService.selectPieceLength(512L * 1024 * 1024)
        );
        assertEquals(512 * 1024, V1TorrentService.selectPieceLength(513L * 1024 * 1024));
        assertEquals(
                V1TorrentService.MAX_PIECE_LENGTH,
                V1TorrentService.selectPieceLength(1_000_000_000_000L)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> V1TorrentService.safeFileName("../escape.bin", "resource")
        );
    }
}
