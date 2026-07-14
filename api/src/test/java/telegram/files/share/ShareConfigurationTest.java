package telegram.files.share;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ShareConfigurationTest {

    private final Path appRoot = Path.of(System.getProperty("java.io.tmpdir"), "tf-app").toAbsolutePath();

    private final Path telegramRoot = appRoot.resolve("account");

    @Test
    void disabledConfigurationDoesNotRequirePlatformUrl() {
        ShareConfiguration configuration = ShareConfiguration.from(Map.of(), appRoot, telegramRoot);

        assertFalse(configuration.enabled());
        assertNull(configuration.platformUri());
        assertEquals(appRoot.resolve("shared").normalize(), configuration.sharedRoot());
    }

    @Test
    void enabledConfigurationRequiresHttps() {
        Map<String, String> environment = Map.of(
                "SHARE_ENABLED", "true",
                "SEED_PLATFORM_URL", "http://seed.example.test"
        );

        assertThrows(IllegalArgumentException.class,
                () -> ShareConfiguration.from(environment, appRoot, telegramRoot));
    }

    @Test
    void developmentConfigurationAllowsLoopbackHttp() {
        Map<String, String> environment = Map.of(
                "APP_ENV", "dev",
                "SHARE_ENABLED", "true",
                "SEED_PLATFORM_URL", "http://localhost:7654"
        );

        ShareConfiguration configuration = ShareConfiguration.from(environment, appRoot, telegramRoot);

        assertEquals("http://localhost:7654", configuration.platformUri().toString());
        assertTrue(configuration.allowLocalPlatform());
    }

    @Test
    void developmentConfigurationStillRejectsPrivateNetworkTargets() {
        Map<String, String> environment = Map.of(
                "APP_ENV", "dev",
                "SHARE_ENABLED", "true",
                "SEED_PLATFORM_URL", "http://192.168.1.10:7654"
        );

        assertThrows(IllegalArgumentException.class,
                () -> ShareConfiguration.from(environment, appRoot, telegramRoot));
    }

    @Test
    void platformUrlMustBeAPublicOrigin() {
        for (String platformUrl : new String[]{
                "https://localhost",
                "https://127.0.0.1",
                "https://10.0.0.7",
                "https://[::1]",
                "https://seed.example.test/platform",
                "https://seed.example.test:8443"
        }) {
            Map<String, String> environment = Map.of(
                    "SHARE_ENABLED", "true",
                    "SEED_PLATFORM_URL", platformUrl
            );
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ShareConfiguration.from(environment, appRoot, telegramRoot),
                    platformUrl
            );
        }
    }

    @Test
    void sharedRootCannotOverlapTelegramRoot() {
        Map<String, String> environment = Map.of(
                "SHARED_ROOT", telegramRoot.resolve("shared").toString()
        );

        assertThrows(IllegalArgumentException.class,
                () -> ShareConfiguration.from(environment, appRoot, telegramRoot));
    }

    @Test
    void validatesOperationalBounds() {
        Map<String, String> environment = Map.of(
                "SHARE_CONCURRENCY", "0",
                "SHARE_REQUEST_TIMEOUT_SECONDS", "301"
        );

        assertThrows(IllegalArgumentException.class,
                () -> ShareConfiguration.from(environment, appRoot, telegramRoot));
    }
}
