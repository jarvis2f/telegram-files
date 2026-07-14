package telegram.files.share;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record TorrentConfiguration(
        boolean enabled,
        URI webApiUri,
        String username,
        String password,
        Path sharedRoot,
        String qbittorrentSharedRoot,
        Duration requestTimeout,
        Duration operationTimeout,
        Duration pollInterval,
        String mappingMethod,
        boolean ipv4Reachable,
        boolean ipv6Reachable
) {

    public static final String MINIMUM_QBITTORRENT_VERSION = "5.1.2";

    public static final String MINIMUM_WEB_API_VERSION = "2.11.4";

    private static final List<String> MAPPING_METHODS = List.of(
            "NONE", "MANUAL", "UPNP", "NAT_PMP", "PCP", "IPV6", "OVERLAY"
    );

    public TorrentConfiguration {
        sharedRoot = Objects.requireNonNull(sharedRoot, "sharedRoot").toAbsolutePath().normalize();
        if (enabled) {
            if (webApiUri == null || webApiUri.getHost() == null || webApiUri.getUserInfo() != null
                || webApiUri.getQuery() != null || webApiUri.getFragment() != null
                || !("http".equalsIgnoreCase(webApiUri.getScheme())
                     || "https".equalsIgnoreCase(webApiUri.getScheme()))) {
                throw new IllegalArgumentException("QBITTORRENT_URL must be an HTTP(S) origin");
            }
            String path = webApiUri.getPath();
            if (path != null && !path.isEmpty() && !"/".equals(path)) {
                throw new IllegalArgumentException("QBITTORRENT_URL must not contain a path");
            }
            if (username == null || username.isBlank() || password == null || password.isBlank()) {
                throw new IllegalArgumentException("qBittorrent credentials are required when Torrent is enabled");
            }
        }
        if (qbittorrentSharedRoot == null || !qbittorrentSharedRoot.startsWith("/")) {
            throw new IllegalArgumentException("QBITTORRENT_SHARED_ROOT must be an absolute POSIX path");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
            || requestTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("QBITTORRENT_REQUEST_TIMEOUT_SECONDS is invalid");
        }
        if (operationTimeout == null || operationTimeout.compareTo(Duration.ofMinutes(1)) < 0
            || operationTimeout.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("QBITTORRENT_OPERATION_TIMEOUT_SECONDS is invalid");
        }
        if (pollInterval == null || pollInterval.compareTo(Duration.ofMillis(250)) < 0
            || pollInterval.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("QBITTORRENT_POLL_INTERVAL_MILLIS is invalid");
        }
        mappingMethod = Objects.requireNonNull(mappingMethod, "mappingMethod").toUpperCase(Locale.ROOT);
        if (!MAPPING_METHODS.contains(mappingMethod)) {
            throw new IllegalArgumentException("PEER_MAPPING_METHOD is invalid");
        }
    }

    public static TorrentConfiguration from(Map<String, String> environment, Path sharedRoot) {
        String rawUrl = environment.get("QBITTORRENT_URL");
        boolean enabled = rawUrl != null && !rawUrl.isBlank();
        return new TorrentConfiguration(
                enabled,
                enabled ? URI.create(rawUrl) : null,
                environment.get("QBITTORRENT_USERNAME"),
                environment.get("QBITTORRENT_PASSWORD"),
                sharedRoot,
                environment.getOrDefault("QBITTORRENT_SHARED_ROOT", sharedRoot.toString())
                        .replace('\\', '/'),
                Duration.ofSeconds(integer(environment, "QBITTORRENT_REQUEST_TIMEOUT_SECONDS", 30)),
                Duration.ofSeconds(integer(environment, "QBITTORRENT_OPERATION_TIMEOUT_SECONDS", 86400)),
                Duration.ofMillis(integer(environment, "QBITTORRENT_POLL_INTERVAL_MILLIS", 1000)),
                environment.getOrDefault("PEER_MAPPING_METHOD", "NONE"),
                Boolean.parseBoolean(environment.getOrDefault("PEER_IPV4_REACHABLE", "false")),
                Boolean.parseBoolean(environment.getOrDefault("PEER_IPV6_REACHABLE", "false"))
        );
    }

    public String qbittorrentPath(Path localPath) {
        Path normalized = Objects.requireNonNull(localPath, "localPath").toAbsolutePath().normalize();
        if (!normalized.startsWith(sharedRoot)) {
            throw new IllegalArgumentException("qBittorrent path escaped SHARED_ROOT");
        }
        String relative = sharedRoot.relativize(normalized).toString().replace('\\', '/');
        return relative.isEmpty()
                ? qbittorrentSharedRoot
                : qbittorrentSharedRoot.replaceAll("/+$", "") + "/" + relative;
    }

    private static int integer(Map<String, String> environment, String name, int defaultValue) {
        String raw = environment.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer", exception);
        }
    }
}
