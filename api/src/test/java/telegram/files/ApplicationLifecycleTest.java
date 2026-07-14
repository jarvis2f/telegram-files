package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationLifecycleTest {

    @Test
    void shutdownClosesAttachedVertxOnlyOnce() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.close()).thenReturn(Future.succeededFuture());
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofSeconds(1));
        lifecycle.attach(vertx);

        lifecycle.shutdown();
        lifecycle.shutdown();

        verify(vertx, times(1)).close();
    }

    @Test
    void concurrentShutdownCallsShareOneCloseOperation() throws Exception {
        Promise<Void> closePromise = Promise.promise();
        CountDownLatch closeStarted = new CountDownLatch(1);
        Vertx vertx = mock(Vertx.class);
        doAnswer(_ -> {
            closeStarted.countDown();
            return closePromise.future();
        }).when(vertx).close();
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofSeconds(1));
        lifecycle.attach(vertx);
        ExecutorService callers = Executors.newFixedThreadPool(2);

        try {
            java.util.concurrent.Future<?> first = callers.submit(lifecycle::shutdown);
            closeStarted.await(1, TimeUnit.SECONDS);
            java.util.concurrent.Future<?> second = callers.submit(lifecycle::shutdown);
            closePromise.complete();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            callers.shutdownNow();
        }

        verify(vertx, times(1)).close();
    }

    @Test
    void shutdownReturnsWhenCloseFails() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.close()).thenReturn(Future.failedFuture("close failed"));
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofSeconds(1));
        lifecycle.attach(vertx);

        assertTimeoutPreemptively(Duration.ofSeconds(1), lifecycle::shutdown);
    }

    @Test
    void shutdownReturnsWhenCloseTimesOut() {
        Promise<Void> never = Promise.promise();
        Vertx vertx = mock(Vertx.class);
        when(vertx.close()).thenReturn(never.future());
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofMillis(25));
        lifecycle.attach(vertx);

        assertTimeoutPreemptively(Duration.ofSeconds(1), lifecycle::shutdown);
    }

    @Test
    void requestExitUsesFirstStatusOnly() {
        AtomicInteger status = new AtomicInteger(-1);
        ApplicationLifecycle lifecycle = new ApplicationLifecycle(
                Duration.ofSeconds(1),
                status::set,
                Runnable::run
        );

        lifecycle.requestExit(1);
        lifecycle.requestExit(2);

        assertEquals(1, status.get());
    }

    @Test
    void shutdownBeforeAttachReturnsImmediately() {
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofSeconds(1));

        assertTimeoutPreemptively(Duration.ofMillis(100), lifecycle::shutdown);
    }

    @Test
    void attachAfterShutdownStartsClosesRuntimeImmediately() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.close()).thenReturn(Future.succeededFuture());
        ApplicationLifecycle lifecycle = lifecycle(Duration.ofSeconds(1));

        lifecycle.shutdown();
        lifecycle.attach(vertx);

        verify(vertx, times(1)).close();
    }

    private static ApplicationLifecycle lifecycle(Duration timeout) {
        return new ApplicationLifecycle(timeout, _ -> {
        }, Runnable::run);
    }
}
