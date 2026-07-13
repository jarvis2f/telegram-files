package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Vertx;

import java.util.concurrent.CountDownLatch;

public class Start {
    static {
        LogFactory.setCurrentLogFactory(new Config.JDKLogFactory());
    }

    private static final Log log = LogFactory.get();

    public static final String VERSION = "0.3.1";

    private static final CountDownLatch shutdownLatch = new CountDownLatch(1);

    private static volatile boolean isShuttingDown = false;

    private static final Vertx vertx = Vertx.vertx();

    private static final DataVerticle dataVerticle = new DataVerticle();

    private static final HttpVerticle httpVerticle = new HttpVerticle();

    public static void main(String[] args) {
        registerShutdownHooks();
        deployVerticles();
    }

    private static void registerShutdownHooks() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("👋 Shutdown hook triggered");
            close();
        }));

        try {
            sun.misc.Signal.handle(new sun.misc.Signal("TERM"), _ -> {
                log.info("📥 Received SIGTERM signal");
                close();
                System.exit(0);
            });

            sun.misc.Signal.handle(new sun.misc.Signal("INT"), _ -> {
                log.info("📥 Received SIGINT signal");
                close();
                System.exit(0);
            });
        } catch (IllegalArgumentException e) {
            log.warn("⚠️ Signal handling not supported on this platform", e);
        }
    }

    private static void deployVerticles() {
        vertx.deployVerticle(dataVerticle)
                .compose(_ -> vertx.deployVerticle(httpVerticle))
                .onSuccess(_ -> log.info("🚀 Start success"))
                .onFailure(err -> {
                    log.error("😱 Start failed", err);
                    System.exit(1);
                });
    }

    private static void close() {
        if (isShuttingDown) {
            return;
        }
        vertx.undeploy(httpVerticle.deploymentID())
                .compose(_ -> vertx.undeploy(dataVerticle.deploymentID()))
                .onComplete(res -> {
                    if (res.succeeded()) {
                        log.info("👋 Shutdown success");
                    } else {
                        log.error("😱 Shutdown failed", res.cause());
                    }
                    isShuttingDown = true;
                    shutdownLatch.countDown();
                });

        try {
            shutdownLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
