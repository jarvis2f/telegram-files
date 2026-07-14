package telegram.files.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ContractArtifactTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Path SNAPSHOT = Path.of("contracts", "telegram-seed-v1");

    private static final Path SEED_ARTIFACT =
            Path.of("..", "..", "telegram-seed", "packages", "contracts", "artifacts", "v1");

    @Test
    void pinnedSnapshotMatchesItsSha256Manifest() throws Exception {
        JsonNode manifest = readJson(SNAPSHOT.resolve("manifest.json"));

        assertEquals("1.0.0", manifest.path("contractVersion").asText());
        assertEquals("1.0", manifest.path("schemaVersion").asText());
        Iterator<Map.Entry<String, JsonNode>> files = manifest.path("files").fields();
        int count = 0;
        while (files.hasNext()) {
            Map.Entry<String, JsonNode> entry = files.next();
            Path file = SNAPSHOT.resolve(entry.getKey()).normalize();
            assertTrue(file.startsWith(SNAPSHOT));
            assertTrue(Files.isRegularFile(file), entry.getKey());
            assertEquals(entry.getValue().path("sha256").asText(), sha256(file), entry.getKey());
            count++;
        }
        assertTrue(count >= 10);
    }

    @Test
    void localSeedAndFilesUseTheSameGeneratedArtifactWhenAvailable() throws IOException {
        if (!Files.isDirectory(SEED_ARTIFACT)) {
            return;
        }
        JsonNode manifest = readJson(SNAPSHOT.resolve("manifest.json"));
        Iterator<String> names = manifest.path("files").fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            assertArrayEquals(
                    Files.readAllBytes(SEED_ARTIFACT.resolve(name)),
                    Files.readAllBytes(SNAPSHOT.resolve(name)),
                    name
            );
        }
    }

    @Test
    void contractContainsStableErrorsAndPrivateFixturesContainNoLocator() throws IOException {
        JsonNode errors = readJson(SNAPSHOT.resolve("error-codes.json")).path("errors");
        assertEquals(21, errors.size());
        assertTrue(errors.findValuesAsText("code").contains("NO_REACHABLE_PEER"));
        assertTrue(errors.findValuesAsText("code").contains("STORAGE_LAYOUT_UNSUPPORTED"));

        JsonNode source = readJson(SNAPSHOT.resolve("fixtures/resource-private.json")).path("source");
        for (String forbidden : new String[]{
                "chatId", "messageId", "telegramId", "phone", "apiHash", "localPath"
        }) {
            assertFalse(source.has(forbidden), forbidden);
        }
        String openApi = Files.readString(SNAPSHOT.resolve("openapi.yaml"));
        assertTrue(openApi.contains("openapi: 3.1.0"));
        assertTrue(openApi.contains("version: 1.0.0"));
    }

    private static JsonNode readJson(Path path) throws IOException {
        return MAPPER.readTree(path.toFile());
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
    }
}
