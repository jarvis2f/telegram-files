package telegram.files;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class BuildInfo {

    public static final String VERSION = loadVersion();

    private BuildInfo() {
    }

    private static String loadVersion() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/telegram-files-version.properties")) {
            if (input != null) {
                properties.load(input);
                String version = properties.getProperty("version");
                if (version != null && !version.isBlank() && !version.contains("$")) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // Fall through to the source-tree version for IDE execution.
        }

        for (Path candidate : new Path[]{Path.of("VERSION"), Path.of("../VERSION")}) {
            try {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate).trim();
                }
            } catch (IOException ignored) {
                // Try the next source-tree location.
            }
        }
        throw new IllegalStateException("Application version is unavailable");
    }
}
