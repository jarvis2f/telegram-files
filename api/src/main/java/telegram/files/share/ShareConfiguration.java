package telegram.files.share;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public record ShareConfiguration(
        boolean enabled,
        URI platformUri,
        Path sharedRoot,
        int concurrency,
        Duration requestTimeout,
        int maxRetries,
        boolean allowLocalPlatform
) {

    public ShareConfiguration {
        if (enabled) {
            if (!isAllowedPlatformOrigin(platformUri, allowLocalPlatform)) {
                throw new IllegalArgumentException(
                        "SEED_PLATFORM_URL must be a public HTTPS origin; development may use a loopback HTTP origin"
                );
            }
        }
        if (sharedRoot == null || !sharedRoot.isAbsolute()) {
            throw new IllegalArgumentException("SHARED_ROOT must resolve to an absolute path");
        }
        if (concurrency < 1 || concurrency > 64) {
            throw new IllegalArgumentException("SHARE_CONCURRENCY must be between 1 and 64");
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
            || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("SHARE_REQUEST_TIMEOUT_SECONDS must be between 1 and 300");
        }
        if (maxRetries < 0 || maxRetries > 20) {
            throw new IllegalArgumentException("SHARE_MAX_RETRIES must be between 0 and 20");
        }
        sharedRoot = sharedRoot.toAbsolutePath().normalize();
    }

    public static ShareConfiguration from(
            Map<String, String> environment,
            Path appRoot,
            Path telegramRoot
    ) {
        if (appRoot == null || telegramRoot == null) {
            throw new IllegalArgumentException("Application and Telegram roots are required");
        }
        boolean enabled = Boolean.parseBoolean(environment.getOrDefault("SHARE_ENABLED", "false"));
        String appEnvironment = environment.getOrDefault("APP_ENV", "prod").toLowerCase(Locale.ROOT);
        boolean allowLocalPlatform = switch (appEnvironment) {
            case "dev", "development", "local", "test" -> true;
            default -> false;
        };
        String rawPlatformUrl = environment.get("SEED_PLATFORM_URL");
        URI platformUri = rawPlatformUrl == null || rawPlatformUrl.isBlank() ? null : URI.create(rawPlatformUrl);
        Path sharedRoot = Path.of(environment.getOrDefault(
                "SHARED_ROOT",
                appRoot.resolve("shared").toString()
        )).toAbsolutePath().normalize();
        Path normalizedTelegramRoot = telegramRoot.toAbsolutePath().normalize();
        if (sharedRoot.startsWith(normalizedTelegramRoot)
            || normalizedTelegramRoot.startsWith(sharedRoot)) {
            throw new IllegalArgumentException("SHARED_ROOT must not overlap the Telegram account root");
        }

        return new ShareConfiguration(
                enabled,
                platformUri,
                sharedRoot,
                parseInteger(environment, "SHARE_CONCURRENCY", 2),
                Duration.ofSeconds(parseInteger(environment, "SHARE_REQUEST_TIMEOUT_SECONDS", 30)),
                parseInteger(environment, "SHARE_MAX_RETRIES", 5),
                allowLocalPlatform
        );
    }

    private static boolean isAllowedPlatformOrigin(URI platformUri, boolean allowLocalPlatform) {
        if (platformUri == null || platformUri.getHost() == null || platformUri.getUserInfo() != null
            || (platformUri.getPath() != null && !platformUri.getPath().isEmpty()
                && !"/".equals(platformUri.getPath()))
            || platformUri.getQuery() != null || platformUri.getFragment() != null) {
            return false;
        }
        boolean publicHttps = "https".equalsIgnoreCase(platformUri.getScheme())
                              && (platformUri.getPort() == -1 || platformUri.getPort() == 443)
                              && !isPrivateHost(platformUri.getHost());
        boolean localDevelopment = allowLocalPlatform
                                   && ("http".equalsIgnoreCase(platformUri.getScheme())
                                       || "https".equalsIgnoreCase(platformUri.getScheme()))
                                   && (platformUri.getPort() == -1
                                       || (platformUri.getPort() >= 1 && platformUri.getPort() <= 65535))
                                   && isLoopbackHost(platformUri.getHost());
        return publicHttps || localDevelopment;
    }

    private static int parseInteger(Map<String, String> environment, String key, int defaultValue) {
        String value = environment.get(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer", exception);
        }
    }

    private static boolean isPrivateHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost")
            || host.endsWith(".local") || host.endsWith(".internal")) {
            return true;
        }
        if (host.contains(":")) {
            return host.equals("::") || host.equals("::1")
                   || host.startsWith("fc") || host.startsWith("fd")
                   || host.matches("^fe[89ab].*") || host.startsWith("::ffff:");
        }
        if (!host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] parts = host.split("\\.");
        for (String part : parts) {
            if (Integer.parseInt(part) > 255) {
                return true;
            }
        }
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        return first == 0 || first == 10 || first == 127 || first >= 224
               || (first == 100 && second >= 64 && second <= 127)
               || (first == 169 && second == 254)
               || (first == 172 && second >= 16 && second <= 31)
               || (first == 192 && (second == 0 || second == 168))
               || (first == 198 && (second == 18 || second == 19));
    }

    private static boolean isLoopbackHost(String rawHost) {
        String host = rawHost.toLowerCase(Locale.ROOT);
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.endsWith(".localhost") || host.equals("::1")) {
            return true;
        }
        if (!host.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) {
            return false;
        }
        String[] parts = host.split("\\.");
        for (String part : parts) {
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return Integer.parseInt(parts[0]) == 127;
    }
}
