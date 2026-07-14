package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.EventEnum;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

@ExtendWith(VertxExtension.class)
class ShareVerticleTest {

    private static ShareConfiguration configuration(boolean enabled) {
        return new ShareConfiguration(
                enabled,
                enabled ? URI.create("https://seed.example.test") : null,
                Path.of(System.getProperty("java.io.tmpdir"), "tf-shared").toAbsolutePath(),
                2,
                Duration.ofSeconds(30),
                5,
                false
        );
    }

    @Test
    void disabledModuleRegistersNoConsumer(Vertx vertx, VertxTestContext context) {
        vertx.deployVerticle(new ShareVerticle(configuration(false), ShareService.noop()))
                .compose(_ -> vertx.eventBus().request(
                        EventEnum.FILE_READY_FOR_SHARE.address(),
                        new FileReadyForShare(1, 0, 1).toJson()
                ))
                .onComplete(context.failing(_ -> context.completeNow()));
    }

    @Test
    void enabledModuleAcceptsIdentifierOnlyEvent(Vertx vertx, VertxTestContext context) {
        ShareService service = event -> event.fileRecordId() == 7
                ? Future.succeededFuture()
                : Future.failedFuture("unexpected event");
        vertx.deployVerticle(new ShareVerticle(configuration(true), service))
                .compose(_ -> vertx.eventBus().<JsonObject>request(
                        EventEnum.FILE_READY_FOR_SHARE.address(),
                        new FileReadyForShare(7, 1, 99).toJson()
                ))
                .onComplete(context.succeeding(message -> context.verify(() -> {
                    org.junit.jupiter.api.Assertions.assertTrue(message.body().getBoolean("accepted"));
                    context.completeNow();
                })));
    }
}
