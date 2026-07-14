package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import telegram.files.share.ShareConfiguration;
import telegram.files.share.ShareVerticle;

import java.time.Duration;

public class Start {
    static {
        LogFactory.setCurrentLogFactory(new Config.JDKLogFactory());
    }

    private static final Log log = LogFactory.get();

    public static final String VERSION = BuildInfo.VERSION;

    public static void main(String[] args) {
        ApplicationLifecycle lifecycle = new ApplicationLifecycle(
                Duration.ofSeconds(30),
                System::exit,
                command -> new Thread(command, "telegram-files-exit").start()
        );
        registerShutdownHook(lifecycle);

        try {
            Vertx vertx = Vertx.vertx();
            lifecycle.attach(vertx);
            deployVerticles(vertx)
                    .onSuccess(_ -> log.info("🚀 Start success! version: {}", VERSION))
                    .onFailure(failure -> {
                        log.error("😱 Start failed!", failure);
                        lifecycle.requestExit(1);
                    });
        } catch (Throwable failure) {
            log.error("😱 Start failed!", failure);
            lifecycle.requestExit(1);
        }
    }

    private static void registerShutdownHook(ApplicationLifecycle lifecycle) {
        Thread shutdownHook = new Thread(() -> {
            log.info("👋 Shutdown hook triggered");
            lifecycle.shutdown();
        }, "telegram-files-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private static Future<Void> deployVerticles(Vertx vertx) {
        ShareConfiguration shareConfiguration = Config.shareConfiguration();
        DataVerticle dataVerticle = new DataVerticle();
        HttpVerticle httpVerticle = new HttpVerticle();
        return vertx.deployVerticle(dataVerticle)
                .compose(_ -> deployShareVerticle(vertx, httpVerticle, shareConfiguration))
                .compose(_ -> vertx.deployVerticle(httpVerticle)
                        .onFailure(err -> log.error("Deploy http verticle failed", err))
                )
                .mapEmpty();
    }

    private static Future<Void> deployShareVerticle(
            Vertx vertx,
            HttpVerticle httpVerticle,
            ShareConfiguration configuration
    ) {
        if (!configuration.enabled()) {
            return Future.succeededFuture();
        }
        return ShareRuntime.initialize(vertx, httpVerticle, configuration)
                .compose(shareVerticle -> deployShareVerticle(vertx, shareVerticle));
    }

    private static Future<Void> deployShareVerticle(Vertx vertx, ShareVerticle shareVerticle) {
        return vertx.deployVerticle(shareVerticle)
                .mapEmpty();
    }
}
