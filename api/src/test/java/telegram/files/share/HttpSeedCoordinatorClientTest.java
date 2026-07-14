package telegram.files.share;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpSeedCoordinatorClientTest {

    @Test
    void usesHttpOneForCompatibilityWithLocalNextDevelopmentServer() {
        ShareConfiguration configuration = new ShareConfiguration(
                true,
                URI.create("http://localhost:7654"),
                Path.of(System.getProperty("java.io.tmpdir"), "tf-shared").toAbsolutePath(),
                2,
                Duration.ofSeconds(30),
                5,
                true
        );

        HttpClient client = HttpSeedCoordinatorClient.createHttpClient(configuration);

        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
    }
}
