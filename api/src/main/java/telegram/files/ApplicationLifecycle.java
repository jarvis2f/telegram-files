package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Vertx;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

final class ApplicationLifecycle {

    private static final Log log = LogFactory.get();

    private final Duration shutdownTimeout;

    private final IntConsumer exit;

    private final Executor exitExecutor;

    private final AtomicBoolean exitRequested = new AtomicBoolean();

    private final Object shutdownLock = new Object();

    private Vertx vertx;

    private boolean shutdownRequested;

    private CompletableFuture<Void> closeFuture;

    ApplicationLifecycle(Duration shutdownTimeout, IntConsumer exit, Executor exitExecutor) {
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout);
        this.exit = Objects.requireNonNull(exit);
        this.exitExecutor = Objects.requireNonNull(exitExecutor);
    }

    void attach(Vertx vertx) {
        synchronized (shutdownLock) {
            if (this.vertx != null) {
                throw new IllegalStateException("Vert.x runtime already attached");
            }
            this.vertx = Objects.requireNonNull(vertx);
            if (shutdownRequested) {
                startCloseLocked();
            }
        }
    }

    void requestExit(int status) {
        if (exitRequested.compareAndSet(false, true)) {
            exitExecutor.execute(() -> exit.accept(status));
        }
    }

    void shutdown() {
        CompletableFuture<Void> closing;
        synchronized (shutdownLock) {
            shutdownRequested = true;
            if (vertx == null) {
                return;
            }
            closing = startCloseLocked();
        }
        awaitClose(closing);
    }

    private CompletableFuture<Void> startCloseLocked() {
        if (closeFuture == null) {
            try {
                closeFuture = vertx.close().toCompletionStage().toCompletableFuture();
            } catch (Throwable failure) {
                closeFuture = CompletableFuture.failedFuture(failure);
            }
        }
        return closeFuture;
    }

    private void awaitClose(CompletableFuture<Void> closing) {
        try {
            closing.get(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS);
            log.info("Shutdown success");
        } catch (TimeoutException failure) {
            log.error("Shutdown timed out after {} ms", shutdownTimeout.toMillis());
        } catch (ExecutionException failure) {
            log.error("Shutdown failed", failure.getCause());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            log.error("Shutdown interrupted", failure);
        }
    }
}
