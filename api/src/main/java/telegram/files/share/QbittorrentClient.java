package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class QbittorrentClient implements TorrentClient {

    private final TorrentConfiguration configuration;

    private final HttpClient httpClient;

    private volatile String sidCookie;

    public QbittorrentClient(TorrentConfiguration configuration) {
        this(configuration, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(configuration.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    QbittorrentClient(TorrentConfiguration configuration, HttpClient httpClient) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        if (!configuration.enabled()) {
            throw new IllegalArgumentException("qBittorrent is not configured");
        }
    }

    @Override
    public Future<Void> healthCheck() {
        return text("GET", "/api/v2/app/version", null, null)
                .compose(version -> {
                    requireVersion(version, TorrentConfiguration.MINIMUM_QBITTORRENT_VERSION,
                            "qBittorrent");
                    return text("GET", "/api/v2/app/webapiVersion", null, null);
                })
                .map(version -> {
                    requireVersion(version, TorrentConfiguration.MINIMUM_WEB_API_VERSION,
                            "qBittorrent WebAPI");
                    return null;
                });
    }

    @Override
    public Future<Void> add(AddRequest request) {
        Objects.requireNonNull(request, "request");
        String boundary = "telegram-files-" + UUID.randomUUID();
        byte[] body = multipart(boundary, request);
        return text(
                "POST", "/api/v2/torrents/add",
                "multipart/form-data; boundary=" + boundary, body
        ).compose(QbittorrentClient::requireOk)
                .recover(failure -> failure instanceof TorrentClientException clientFailure
                                    && clientFailure.statusCode() == 409
                        ? Future.succeededFuture()
                        : Future.failedFuture(failure));
    }

    @Override
    public Future<TorrentStatus> get(String infoHashV1) {
        String hash = hash(infoHashV1);
        return text("GET", "/api/v2/torrents/info?hashes=" + encode(hash), null, null)
                .compose(encoded -> {
                    JsonArray values;
                    try {
                        values = new JsonArray(encoded);
                    } catch (RuntimeException exception) {
                        return Future.failedFuture(new TorrentClientException(
                                "qBittorrent returned invalid Torrent JSON", false, exception
                        ));
                    }
                    if (values.isEmpty()) {
                        return Future.failedFuture(new TorrentNotFoundException(
                                "qBittorrent does not contain the assigned Torrent", true
                        ));
                    }
                    JsonObject value = values.getJsonObject(0);
                    return Future.succeededFuture(new TorrentStatus(
                            value.getString("hash", hash).toLowerCase(Locale.ROOT),
                            value.getString("state", "unknown"),
                            number(value, "progress").doubleValue(),
                            nonNegative(number(value, "downloaded").longValue()),
                            nonNegative(number(value, "uploaded").longValue()),
                            nonNegative(number(value, "dlspeed").longValue()),
                            nonNegative(number(value, "upspeed").longValue()),
                            Math.max(0, number(value, "num_leechs").intValue())
                            + Math.max(0, number(value, "num_seeds").intValue()),
                            value.getString("save_path", "/"),
                            value.getBoolean("private", true)
                    ));
                });
    }

    @Override
    public Future<List<PeerStatus>> getPeers(String infoHashV1) {
        String hash = hash(infoHashV1);
        return text("GET", "/api/v2/sync/torrentPeers?hash=" + encode(hash), null, null)
                .compose(encoded -> {
                    try {
                        JsonObject response = new JsonObject(encoded);
                        JsonObject peers = response.getJsonObject("peers", new JsonObject());
                        List<PeerStatus> result = new ArrayList<>();
                        for (String endpoint : peers.fieldNames()) {
                            JsonObject peer = peers.getJsonObject(endpoint);
                            if (peer == null) continue;
                            long uploaded = nonNegative(number(peer, "uploaded").longValue());
                            String client = peer.getString("client", "unknown");
                            result.add(new PeerStatus(endpoint + "\u0000" + client, uploaded));
                        }
                        return Future.succeededFuture(List.copyOf(result));
                    } catch (RuntimeException exception) {
                        return Future.failedFuture(new TorrentClientException(
                                "qBittorrent returned invalid Peer JSON", true, exception
                        ));
                    }
                });
    }

    @Override
    public Future<Void> pause(String infoHashV1) {
        return form("/api/v2/torrents/stop", Map.of("hashes", hash(infoHashV1)));
    }

    @Override
    public Future<Void> resume(String infoHashV1) {
        String h = hash(infoHashV1);
        return form("/api/v2/torrents/start", Map.of("hashes", h))
                .recover(_ -> form("/api/v2/torrents/resume", Map.of("hashes", h)))
                .compose(_ -> form("/api/v2/torrents/setForceStart", Map.of("hashes", h, "value", "true")).recover(_ -> Future.succeededFuture()));
    }

    @Override
    public Future<Void> recheck(String infoHashV1) {
        return form("/api/v2/torrents/recheck", Map.of("hashes", hash(infoHashV1)));
    }

    @Override
    public Future<Void> delete(String infoHashV1) {
        return form("/api/v2/torrents/delete", Map.of(
                "hashes", hash(infoHashV1),
                "deleteFiles", "false"
        ));
    }

    @Override
    public Future<Void> setUploadLimit(String infoHashV1, long bytesPerSecond) {
        if (bytesPerSecond < 0) {
            return Future.failedFuture(new IllegalArgumentException("Upload limit cannot be negative"));
        }
        return form("/api/v2/torrents/setUploadLimit", Map.of(
                "hashes", hash(infoHashV1),
                "limit", Long.toString(bytesPerSecond)
        ));
    }

    @Override
    public Future<Void> replaceTracker(String infoHashV1, String oldUrl, String newUrl) {
        if (oldUrl == null || newUrl == null) {
            return Future.failedFuture(new IllegalArgumentException("Tracker URLs are required"));
        }
        return form("/api/v2/torrents/editTracker", Map.of(
                "hash", hash(infoHashV1),
                "origUrl", oldUrl,
                "newUrl", newUrl
        )).compose(_ -> form("/api/v2/torrents/reannounce", Map.of(
                "hashes", hash(infoHashV1)
        )).recover(_ -> Future.succeededFuture()));
    }

    @Override
    public Future<Void> replaceTrackerByBase(String infoHashV1, String trackerBaseUrl, String credential) {
        if (trackerBaseUrl == null || !trackerBaseUrl.endsWith("/announce/")
            || credential == null || !credential.matches("[A-Za-z0-9_-]{32,1024}")) {
            return Future.failedFuture(new IllegalArgumentException("Tracker rotation input is invalid"));
        }
        String replacement = trackerBaseUrl + credential;
        return text("GET", "/api/v2/torrents/trackers?hash=" + encode(hash(infoHashV1)), null, null)
                .compose(encoded -> {
                    JsonArray trackers;
                    try {
                        trackers = new JsonArray(encoded);
                    } catch (RuntimeException exception) {
                        return Future.failedFuture(new TorrentClientException(
                                "qBittorrent returned invalid Tracker JSON", false, exception
                        ));
                    }
                    for (Object item : trackers) {
                        if (item instanceof JsonObject tracker) {
                            String url = tracker.getString("url");
                            if (url != null && url.startsWith(trackerBaseUrl)) {
                                return url.equals(replacement)
                                        ? form("/api/v2/torrents/reannounce", Map.of("hashes", hash(infoHashV1)))
                                        : replaceTracker(infoHashV1, url, replacement);
                            }
                        }
                    }
                    return Future.failedFuture(new TorrentNotFoundException(
                            "qBittorrent Torrent has no managed Tracker", false
                    ));
                });
    }

    private Future<Void> form(String path, Map<String, String> values) {
        byte[] body = formBytes(values);
        return text("POST", path, "application/x-www-form-urlencoded", body)
                .compose(QbittorrentClient::requireOk);
    }

    private Future<String> text(String method, String path, String contentType, byte[] body) {
        return authenticated(method, path, contentType, body, 1, 1)
                .map(response -> new String(response.body(), StandardCharsets.UTF_8).trim());
    }

    private Future<HttpResponse<byte[]>> authenticated(
            String method,
            String path,
            String contentType,
            byte[] body,
            int authRetries,
            int transientRetries
    ) {
        Future<Void> ready = sidCookie == null ? login() : Future.succeededFuture();
        return ready.compose(_ -> send(method, path, contentType, body, sidCookie))
                .compose(response -> {
                    if ((response.statusCode() == 401 || response.statusCode() == 403)
                        && authRetries > 0) {
                        sidCookie = null;
                        return login().compose(_ -> authenticated(
                                method, path, contentType, body, authRetries - 1, transientRetries
                        ));
                    }
                    if ((response.statusCode() == 429 || response.statusCode() >= 500)
                        && transientRetries > 0) {
                        return authenticated(
                                method, path, contentType, body, authRetries, transientRetries - 1
                        );
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Future.failedFuture(new TorrentClientException(
                                "qBittorrent WebAPI request failed with status " + response.statusCode(),
                                response.statusCode() == 429 || response.statusCode() >= 500,
                                response.statusCode()
                        ));
                    }
                    return Future.succeededFuture(response);
                });
    }

    private Future<Void> login() {
        byte[] body = formBytes(Map.of(
                "username", configuration.username(),
                "password", configuration.password()
        ));
        return send("POST", "/api/v2/auth/login", "application/x-www-form-urlencoded", body, null)
                .compose(response -> {
                    String result = new String(response.body(), StandardCharsets.UTF_8).trim();
                    String cookie = response.headers().allValues("set-cookie").stream()
                            .map(QbittorrentClient::sidFromSetCookie)
                            .filter(Objects::nonNull)
                            .findFirst()
                            .orElse(null);
                    boolean acceptedResponse = response.statusCode() >= 200
                                               && response.statusCode() < 300
                                               && (result.isEmpty() || "Ok.".equals(result));
                    if (!acceptedResponse || cookie == null) {
                        return Future.failedFuture(new TorrentClientException(
                                "qBittorrent authentication failed", false
                        ));
                    }
                    sidCookie = cookie;
                    return Future.succeededFuture();
                });
    }

    private Future<HttpResponse<byte[]>> send(
            String method,
            String path,
            String contentType,
            byte[] body,
            String cookie
    ) {
        URI target = configuration.webApiUri().resolve(path).normalize();
        if (!Objects.equals(target.getScheme(), configuration.webApiUri().getScheme())
            || !Objects.equals(target.getAuthority(), configuration.webApiUri().getAuthority())
            || target.getPath() == null || !target.getPath().startsWith("/api/v2/")) {
            return Future.failedFuture(new IllegalArgumentException("qBittorrent API path escaped its origin"));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(configuration.requestTimeout())
                .header("Accept", "application/json, text/plain;q=0.9");
        if (contentType != null) {
            builder.header("Content-Type", contentType);
        }
        if (cookie != null) {
            builder.header("Cookie", cookie);
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        HttpRequest request = builder.method(method, publisher).build();
        return Future.fromCompletionStage(httpClient.sendAsync(
                request, HttpResponse.BodyHandlers.ofByteArray()
        ));
    }

    private static Future<Void> requireOk(String response) {
        return response.isEmpty() || "Ok.".equals(response)
                ? Future.succeededFuture()
                : Future.failedFuture(new TorrentClientException(
                "qBittorrent rejected the Torrent operation", false
        ));
    }

    private static String sidFromSetCookie(String raw) {
        for (String part : raw.split(";")) {
            String value = part.trim();
            int separator = value.indexOf('=');
            if (separator > 0 && separator < value.length() - 1) {
                String name = value.substring(0, separator);
                if (name.matches("[A-Za-z0-9_!#$%&'*+.^`|~-]{1,128}")) {
                    return value.substring(0, separator + 1)
                           + value.substring(separator + 1);
                }
            }
        }
        return null;
    }

    private static byte[] multipart(String boundary, AddRequest request) {
        List<byte[]> parts = new ArrayList<>();
        parts.add(filePart(boundary, request.torrentBytes()));
        parts.add(textPart(boundary, "savepath", request.savePath()));
        parts.add(textPart(boundary, "category", request.category()));
        parts.add(textPart(boundary, "tags", String.join(",", request.tags())));
        parts.add(textPart(boundary, "stopped", Boolean.toString(request.stopped())));
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        parts.forEach(output::writeBytes);
        output.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static byte[] filePart(String boundary, byte[] bytes) {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        output.writeBytes(("--" + boundary + "\r\n"
                           + "Content-Disposition: form-data; name=\"torrents\"; filename=\"resource.torrent\"\r\n"
                           + "Content-Type: application/x-bittorrent\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        output.writeBytes(bytes);
        output.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        return output.toByteArray();
    }

    private static byte[] textPart(String boundary, String name, String value) {
        return ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] formBytes(Map<String, String> values) {
        return values.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(java.util.stream.Collectors.joining("&"))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String hash(String raw) {
        if (raw == null || !raw.matches("[A-Fa-f0-9]{40}")) {
            throw new IllegalArgumentException("Torrent infoHash is invalid");
        }
        return raw.toLowerCase(Locale.ROOT);
    }

    private static Number number(JsonObject value, String name) {
        Object raw = value.getValue(name);
        return raw instanceof Number number ? number : 0;
    }

    private static long nonNegative(long value) {
        return Math.max(0, value);
    }

    private static void requireVersion(String raw, String minimum, String product) {
        String version = raw == null ? "" : raw.strip().replaceFirst("^[vV]", "");
        if (compareVersion(version, minimum) < 0) {
            throw new TorrentClientException(
                    product + " " + minimum + " or newer is required", false
            );
        }
    }

    static int compareVersion(String left, String right) {
        String[] a = left.split("[.-]", 4);
        String[] b = right.split("[.-]", 4);
        for (int index = 0; index < 3; index++) {
            int leftPart = numericPart(a, index);
            int rightPart = numericPart(b, index);
            int comparison = Integer.compare(leftPart, rightPart);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static int numericPart(String[] parts, int index) {
        if (index >= parts.length || !parts[index].matches("[0-9]+")) {
            return -1;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    public static class TorrentClientException extends RuntimeException {
        private final boolean retryable;

        private final int statusCode;

        TorrentClientException(String message, boolean retryable) {
            this(message, retryable, -1);
        }

        TorrentClientException(String message, boolean retryable, int statusCode) {
            super(message);
            this.retryable = retryable;
            this.statusCode = statusCode;
        }

        TorrentClientException(String message, boolean retryable, Throwable cause) {
            super(message, cause);
            this.retryable = retryable;
            this.statusCode = -1;
        }

        public boolean retryable() {
            return retryable;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    public static final class TorrentNotFoundException extends TorrentClientException {
        TorrentNotFoundException(String message, boolean retryable) {
            super(message, retryable);
        }
    }
}
