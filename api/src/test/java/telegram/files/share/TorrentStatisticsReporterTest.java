package telegram.files.share;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TorrentStatisticsReporterTest {
    @Test
    void rolloutAssignmentIsStableAndBounded() {
        assertFalse(TorrentStatisticsReporter.enabledFor("node-a", 0));
        assertTrue(TorrentStatisticsReporter.enabledFor("node-a", 100));
        boolean first = TorrentStatisticsReporter.enabledFor("node-stable", 25);
        assertEquals(first, TorrentStatisticsReporter.enabledFor("node-stable", 25));
        long enabled = IntStream.range(0, 10_000)
                .filter(index -> TorrentStatisticsReporter.enabledFor("node-" + index, 25))
                .count();
        assertTrue(enabled > 2_000 && enabled < 3_000);
    }

    @Test
    void acceptsCompletePlatformAcknowledgement() {
        TorrentStatisticsReporter.validateDeliveryResponse(new JsonObject()
                .put("accepted", 1)
                .put("duplicates", 1)
                .put("rejected", new JsonArray()), 2);
    }

    @Test
    void rejectsPartialPlatformAcknowledgement() {
        assertThrows(IllegalStateException.class, () ->
                TorrentStatisticsReporter.validateDeliveryResponse(new JsonObject()
                        .put("accepted", 1)
                        .put("duplicates", 0)
                        .put("rejected", new JsonArray().add(new JsonObject()
                                .put("eventId", "event-2")
                                .put("code", "TORRENT_INVALID"))), 2));
    }

    @Test
    void rejectsMalformedPlatformAcknowledgement() {
        assertThrows(IllegalStateException.class, () ->
                TorrentStatisticsReporter.validateDeliveryResponse(new JsonObject()
                        .put("accepted", 1)
                        .put("duplicates", 0), 1));
    }
}
