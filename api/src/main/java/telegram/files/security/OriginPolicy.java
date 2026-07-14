package telegram.files.security;

import java.net.URI;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class OriginPolicy {

    private final Set<String> allowedOrigins;

    public OriginPolicy(Collection<String> origins) {
        this.allowedOrigins = origins == null
                ? Set.of()
                : origins.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(OriginPolicy::normalize)
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean isAllowed(String origin) {
        return origin == null || origin.isBlank() || allowedOrigins.contains(normalize(origin));
    }

    public Set<String> allowedOrigins() {
        return allowedOrigins;
    }

    public static boolean isLoopback(String host) {
        String normalized = normalizeHost(host);
        return "localhost".equals(normalized)
                || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)
                || isIpv4Loopback(normalized);
    }

    public static boolean isLocalNetwork(String host) {
        String normalized = normalizeHost(host);
        return isLoopback(normalized)
                || isPrivateIpv4(normalized)
                || isLocalIpv6(normalized);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        int zoneIndex = normalized.indexOf('%');
        return zoneIndex >= 0 ? normalized.substring(0, zoneIndex) : normalized;
    }

    private static boolean isIpv4Loopback(String host) {
        int[] octets = parseIpv4(host);
        return octets != null && octets[0] == 127;
    }

    private static boolean isPrivateIpv4(String host) {
        int[] octets = parseIpv4(host);
        if (octets == null) {
            return false;
        }
        return octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168
                || octets[0] == 169 && octets[1] == 254;
    }

    private static int[] parseIpv4(String host) {
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        int[] values = new int[4];
        for (int i = 0; i < octets.length; i++) {
            String octet = octets[i];
            if (octet.isEmpty() || octet.length() > 3) {
                return null;
            }
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return null;
                }
                values[i] = value;
            } catch (NumberFormatException exception) {
                return null;
            }
        }
        return values;
    }

    private static boolean isLocalIpv6(String host) {
        if (!host.contains(":")) {
            return false;
        }
        try {
            InetAddress address = InetAddress.getByName(host);
            if (!(address instanceof Inet6Address)) {
                return false;
            }
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (first & 0xfe) == 0xfc
                    || first == 0xfe && (second & 0xc0) == 0x80;
        } catch (UnknownHostException exception) {
            return false;
        }
    }

    private static String normalize(String rawOrigin) {
        URI uri;
        try {
            uri = URI.create(rawOrigin);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Invalid allowed origin: " + rawOrigin, exception);
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null || uri.getUserInfo() != null
                || uri.getPath() != null && !uri.getPath().isEmpty()
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("Allowed origin must contain only scheme, host and port");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Allowed origin must use http or https");
        }
        int port = uri.getPort();
        boolean defaultPort = port == -1
                || "http".equals(scheme) && port == 80
                || "https".equals(scheme) && port == 443;
        return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
    }
}
