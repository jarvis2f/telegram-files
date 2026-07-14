package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class HttpSeedCoordinatorClient implements SeedCoordinatorClient {

    private final URI baseUri;

    private final HttpClient httpClient;

    private final Duration requestTimeout;

    public HttpSeedCoordinatorClient(ShareConfiguration configuration) {
        this(
                configuration.platformUri(),
                createHttpClient(configuration),
                configuration.requestTimeout()
        );
    }

    static HttpClient createHttpClient(ShareConfiguration configuration) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(configuration.requestTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    HttpSeedCoordinatorClient(URI baseUri, HttpClient httpClient, Duration requestTimeout) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    }

    @Override
    public Future<JsonObject> get(String path, Map<String, String> headers) {
        return send("GET", path, null, headers);
    }

    @Override
    public Future<JsonArray> getArray(String path, Map<String, String> headers) {
        return sendValue("GET", path, null, headers).compose(value -> value instanceof JsonArray array
                ? Future.succeededFuture(array)
                : Future.failedFuture(new SeedProtocolException(
                "Platform returned a non-array response", 200, null, null
        )));
    }

    @Override
    public Future<JsonObject> post(String path, JsonObject body, Map<String, String> headers) {
        return send("POST", path, Objects.requireNonNull(body, "body"), headers);
    }

    @Override
    public Future<JsonObject> put(String path, JsonObject body, Map<String, String> headers) {
        return send("PUT", path, Objects.requireNonNull(body, "body"), headers);
    }

    @Override
    public Future<JsonObject> delete(String path, Map<String, String> headers) {
        return send("DELETE", path, null, headers);
    }

    private Future<JsonObject> send(
            String method,
            String path,
            JsonObject body,
            Map<String, String> headers
    ) {
        return sendValue(method, path, body, headers).compose(value -> value instanceof JsonObject object
                ? Future.succeededFuture(object)
                : Future.failedFuture(new SeedProtocolException(
                "Platform returned a non-object response", 200, null, null
        )));
    }

    private Future<Object> sendValue(
            String method,
            String path,
            JsonObject body,
            Map<String, String> headers
    ) {
        if (path == null || !path.startsWith("/api/v1/") || path.contains("://")) {
            return Future.failedFuture(new IllegalArgumentException("Only versioned API paths are allowed"));
        }

        URI target = baseUri.resolve(path).normalize();
        if (!Objects.equals(baseUri.getScheme(), target.getScheme())
            || !Objects.equals(baseUri.getAuthority(), target.getAuthority())
            || target.getPath() == null || !target.getPath().startsWith("/api/v1/")) {
            return Future.failedFuture(new IllegalArgumentException("API path escaped the configured platform"));
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        boolean hasRequestId = headers.keySet().stream()
                .anyMatch(name -> name.equalsIgnoreCase("x-request-id"));
        if (!hasRequestId) {
            builder.header("X-Request-Id", "req_" + UUID.randomUUID());
        }
        headers.forEach((name, value) -> {
            if (name.equalsIgnoreCase("host") || name.equalsIgnoreCase("content-length")) {
                throw new IllegalArgumentException("Restricted HTTP header");
            }
            builder.header(name, value);
        });
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.encode());
        HttpRequest request = builder.method(method, publisher).build();

        return Future.fromCompletionStage(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()))
                .compose(response -> {
                    String responseRequestId = response.headers()
                            .firstValue("x-request-id")
                            .orElse(null);
                    Object responseBody;
                    try {
                        String contentType = response.headers()
                                .firstValue("content-type")
                                .orElse("");
                        if (!response.body().isBlank()
                            && !contentType.toLowerCase().startsWith("application/json")) {
                            throw new IllegalArgumentException("Unexpected response content type");
                        }
                        String encoded = response.body().trim();
                        responseBody = encoded.isEmpty()
                                ? new JsonObject()
                                : encoded.startsWith("[")
                                ? new JsonArray(encoded)
                                : new JsonObject(encoded);
                    } catch (RuntimeException exception) {
                        return Future.failedFuture(new SeedProtocolException(
                                "Platform returned invalid JSON",
                                response.statusCode(),
                                null,
                                responseRequestId
                        ));
                    }
                    if (response.statusCode() >= 200 && response.statusCode() < 300) {
                        return Future.succeededFuture(responseBody);
                    }
                    String errorCode = responseBody instanceof JsonObject object
                            ? object.getJsonObject("error", new JsonObject()).getString("code")
                            : null;
                    return Future.failedFuture(new SeedProtocolException(
                            "Platform request failed",
                            response.statusCode(),
                            errorCode,
                            responseRequestId
                    ));
                });
    }

    public static final class SeedProtocolException extends RuntimeException {
        private final int statusCode;

        private final String errorCode;

        private final String requestId;

        SeedProtocolException(String message, int statusCode, String errorCode, String requestId) {
            super(message);
            this.statusCode = statusCode;
            this.errorCode = errorCode;
            this.requestId = requestId;
        }

        public int statusCode() {
            return statusCode;
        }

        public String errorCode() {
            return errorCode;
        }

        public String requestId() {
            return requestId;
        }
    }
}
