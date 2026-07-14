package telegram.files;

import io.vertx.core.Vertx;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class FileRouteHandlerTest {

    @TempDir
    Path temporary;

    @Test
    void servesOpenEndedRangeForVideoPlayback(Vertx vertx, VertxTestContext context) throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(temporary.resolve("clip.mp4"), content);

        requestFile(vertx, file, "bytes=4-", context)
                .thenAccept(response -> context.verify(() -> {
                    assertEquals(206, response.statusCode());
                    assertEquals("bytes", response.headers().firstValue("accept-ranges").orElse(null));
                    assertEquals("bytes 4-9/10", response.headers().firstValue("content-range").orElse(null));
                    assertEquals("6", response.headers().firstValue("content-length").orElse(null));
                    assertArrayEquals("456789".getBytes(StandardCharsets.UTF_8), response.body());
                    context.completeNow();
                }))
                .exceptionally(failure -> {
                    context.failNow(failure);
                    return null;
                });
    }

    @Test
    void servesSuffixRange(Vertx vertx, VertxTestContext context) throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(temporary.resolve("clip.mp4"), content);

        requestFile(vertx, file, "bytes=-4", context)
                .thenAccept(response -> context.verify(() -> {
                    assertEquals(206, response.statusCode());
                    assertEquals("bytes 6-9/10", response.headers().firstValue("content-range").orElse(null));
                    assertArrayEquals("6789".getBytes(StandardCharsets.UTF_8), response.body());
                    context.completeNow();
                }))
                .exceptionally(failure -> {
                    context.failNow(failure);
                    return null;
                });
    }

    @Test
    void rejectsUnsatisfiableRange(Vertx vertx, VertxTestContext context) throws Exception {
        byte[] content = "0123456789".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(temporary.resolve("clip.mp4"), content);

        requestFile(vertx, file, "bytes=10-", context)
                .thenAccept(response -> context.verify(() -> {
                    assertEquals(416, response.statusCode());
                    assertEquals("bytes */10", response.headers().firstValue("content-range").orElse(null));
                    context.completeNow();
                }))
                .exceptionally(failure -> {
                    context.failNow(failure);
                    return null;
                });
    }

    private static java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> requestFile(
            Vertx vertx,
            Path file,
            String range,
            VertxTestContext context
    ) {
        Router router = Router.router(vertx);
        FileRouteHandler handler = new FileRouteHandler();
        router.get("/file").handler(ctx -> handler.handle(ctx, file.toString(), "video/mp4"));

        java.util.concurrent.CompletableFuture<HttpResponse<byte[]>> responseFuture =
                new java.util.concurrent.CompletableFuture<>();
        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .onComplete(context.succeeding(server -> {
                    URI uri = URI.create("http://127.0.0.1:" + server.actualPort() + "/file");
                    HttpRequest request = HttpRequest.newBuilder(uri)
                            .header("Range", range)
                            .GET()
                            .build();
                    HttpClient.newHttpClient()
                            .sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                            .whenComplete((response, failure) ->
                                    server.close().onComplete(_ -> {
                                        if (failure != null) {
                                            responseFuture.completeExceptionally(failure);
                                        } else {
                                            responseFuture.complete(response);
                                        }
                                    }));
                }));
        return responseFuture;
    }
}
