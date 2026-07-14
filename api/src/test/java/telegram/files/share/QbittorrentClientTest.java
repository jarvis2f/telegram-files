package telegram.files.share;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QbittorrentClientTest {

    @TempDir
    Path temporaryDirectory;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void checksVersionsRelogsOnceAcceptsDuplicateAddAndNeverDeletesContent() throws Exception {
        AtomicInteger logins = new AtomicInteger();
        AtomicBoolean rejectFirstAuthenticatedRequest = new AtomicBoolean(true);
        AtomicReference<String> addBody = new AtomicReference<>();
        AtomicReference<String> deleteBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/auth/login")) {
                    logins.incrementAndGet();
                    exchange.getResponseHeaders().add("Set-Cookie", "SID=session-" + logins.get() + "; HttpOnly");
                    respond(exchange, 200, "Ok.");
                    return;
                }
                if (rejectFirstAuthenticatedRequest.compareAndSet(true, false)) {
                    respond(exchange, 403, "Forbidden");
                    return;
                }
                if (path.endsWith("/app/version")) {
                    respond(exchange, 200, "v5.1.2");
                } else if (path.endsWith("/app/webapiVersion")) {
                    respond(exchange, 200, "2.11.4");
                } else if (path.endsWith("/torrents/add")) {
                    addBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 409, "Conflict");
                } else if (path.endsWith("/torrents/delete")) {
                    deleteBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, "");
                } else {
                    respond(exchange, 404, "missing");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        TorrentConfiguration configuration = new TorrentConfiguration(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "user",
                "password-never-log",
                temporaryDirectory,
                temporaryDirectory.toString(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofMillis(250),
                "NONE",
                false,
                false
        );
        QbittorrentClient client = new QbittorrentClient(configuration);

        client.healthCheck()
                .compose(_ -> client.add(new TorrentClient.AddRequest(
                        "torrent-bytes".getBytes(StandardCharsets.UTF_8),
                        "/shared/torrent-views/hash",
                        "telegram-files",
                        List.of("telegram-files"),
                        true
                )))
                .compose(_ -> client.delete("1".repeat(40)))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals(2, logins.get());
        assertNotNull(addBody.get());
        assertTrue(addBody.get().contains("name=\"stopped\""));
        assertTrue(addBody.get().contains("true"));
        assertEquals("deleteFiles=false&hashes=" + "1".repeat(40), deleteBody.get());
    }

    @Test
    void comparesSemanticVersionFloors() {
        assertEquals(0, QbittorrentClient.compareVersion("5.1.2", "5.1.2"));
        assertTrue(QbittorrentClient.compareVersion("5.2.0", "5.1.2") > 0);
        assertTrue(QbittorrentClient.compareVersion("5.1.1", "5.1.2") < 0);
    }

    @Test
    void acceptsQbittorrent52NoContentLoginAndPortScopedCookie() throws Exception {
        AtomicReference<String> cookie = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/auth/login")) {
                    exchange.getResponseHeaders().add(
                            "Set-Cookie", "SID_8090=session-value; HttpOnly"
                    );
                    respond(exchange, 204, "");
                } else if (path.endsWith("/app/version")) {
                    cookie.set(exchange.getRequestHeaders().getFirst("Cookie"));
                    respond(exchange, 200, "v5.2.3");
                } else if (path.endsWith("/app/webapiVersion")) {
                    respond(exchange, 200, "2.11.4");
                } else {
                    respond(exchange, 404, "missing");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        TorrentConfiguration configuration = new TorrentConfiguration(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "user",
                "password-never-log",
                temporaryDirectory,
                temporaryDirectory.toString(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofMillis(250),
                "NONE",
                false,
                false
        );

        new QbittorrentClient(configuration).healthCheck()
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertEquals("SID_8090=session-value", cookie.get());
    }

    @Test
    void confirmsTorrentByHashWhenAddReturnsFailureAfterAcceptingIt() throws Exception {
        String hash = "1".repeat(40);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/auth/login")) {
                    exchange.getResponseHeaders().add("Set-Cookie", "SID=session-value; HttpOnly");
                    respond(exchange, 200, "Ok.");
                } else if (path.endsWith("/torrents/add")) {
                    respond(exchange, 200, "Fails.");
                } else if (path.endsWith("/torrents/info")) {
                    respond(exchange, 200, "[{\"hash\":\"" + hash + "\",\"state\":\"stalledDL\","
                            + "\"progress\":0,\"downloaded\":0,\"uploaded\":0,\"dlspeed\":0,"
                            + "\"upspeed\":0,\"num_leechs\":0,\"num_seeds\":0,"
                            + "\"save_path\":\"/shared/view\",\"private\":true}]");
                } else {
                    respond(exchange, 404, "missing");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        TorrentConfiguration configuration = new TorrentConfiguration(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "user",
                "password-never-log",
                temporaryDirectory,
                temporaryDirectory.toString(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofMillis(250),
                "NONE",
                false,
                false
        );

        new QbittorrentClient(configuration).addOrConfirm(new TorrentClient.AddRequest(
                        "torrent-bytes".getBytes(StandardCharsets.UTF_8),
                        "/shared/view",
                        "telegram-files",
                        List.of("telegram-files"),
                        false
                ), hash)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void keepsSuccessfulTrackerEditWhenImmediateReannounceIsRejected() throws Exception {
        String hash = "1".repeat(40);
        String oldUrl = "https://tracker.example/announce/old-credential";
        String newUrl = "https://tracker.example/announce/new-credential";
        AtomicReference<String> editBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v2/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/auth/login")) {
                    exchange.getResponseHeaders().add("Set-Cookie", "SID=session-value; HttpOnly");
                    respond(exchange, 200, "Ok.");
                } else if (path.endsWith("/torrents/editTracker")) {
                    editBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                    respond(exchange, 200, "");
                } else if (path.endsWith("/torrents/reannounce")) {
                    respond(exchange, 200, "Fails.");
                } else {
                    respond(exchange, 404, "missing");
                }
            } finally {
                exchange.close();
            }
        });
        server.start();

        TorrentConfiguration configuration = new TorrentConfiguration(
                true,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "user",
                "password-never-log",
                temporaryDirectory,
                temporaryDirectory.toString(),
                Duration.ofSeconds(5),
                Duration.ofMinutes(10),
                Duration.ofMillis(250),
                "NONE",
                false,
                false
        );

        new QbittorrentClient(configuration).replaceTracker(hash, oldUrl, newUrl)
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        assertNotNull(editBody.get());
        assertTrue(editBody.get().contains("origUrl="));
        assertTrue(editBody.get().contains("newUrl="));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
    }
}
