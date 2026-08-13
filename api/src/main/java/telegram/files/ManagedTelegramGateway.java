package telegram.files;

import io.vertx.core.Future;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Application-facing TDLib gateway. It wakes a sleeping account before a request and keeps the
 * account busy until the asynchronous result has been delivered.
 */
final class ManagedTelegramGateway implements TelegramGateway {
    private final Supplier<Future<Void>> wake;
    private final Supplier<TelegramGateway> delegate;
    private final Runnable requestStarted;
    private final Runnable requestFinished;

    ManagedTelegramGateway(
            Supplier<Future<Void>> wake,
            Supplier<TelegramGateway> delegate,
            Runnable requestStarted,
            Runnable requestFinished
    ) {
        this.wake = Objects.requireNonNull(wake, "wake");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.requestStarted = Objects.requireNonNull(requestStarted, "requestStarted");
        this.requestFinished = Objects.requireNonNull(requestFinished, "requestFinished");
    }

    @Override
    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        throw new UnsupportedOperationException("Managed gateway initialization is owned by TelegramVerticle");
    }

    @Override
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        return execute(method, false);
    }

    @Override
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException) {
        requestStarted.run();
        return wake.get()
                .compose(_ -> delegate.get().execute(method, ignoreException))
                .eventually(() -> {
                    requestFinished.run();
                    return Future.succeededFuture();
                });
    }

    @Override
    public void send(TdApi.Function<?> method, Client.ResultHandler resultHandler) {
        requestStarted.run();
        wake.get()
                .onSuccess(_ -> {
                    try {
                        delegate.get().send(method, result -> {
                            try {
                                if (resultHandler != null) resultHandler.onResult(result);
                            } finally {
                                requestFinished.run();
                            }
                        });
                    } catch (Throwable failure) {
                        requestFinished.run();
                        if (resultHandler != null) {
                            resultHandler.onResult(new TdApi.Error(500, failure.getMessage()));
                        }
                    }
                })
                .onFailure(failure -> {
                    requestFinished.run();
                    if (resultHandler != null) {
                        resultHandler.onResult(new TdApi.Error(500, failure.getMessage()));
                    }
                });
    }
}
