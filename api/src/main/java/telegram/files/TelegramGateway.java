package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.concurrent.TimeoutException;

/**
 * Boundary around TDLib used by the application.
 *
 * <p>Keeping TDLib behind this interface lets component and system tests drive
 * Telegram updates deterministically without using a real Telegram account.</p>
 */
public interface TelegramGateway {

    void initialize(Client.ResultHandler updateHandler,
                    Client.ExceptionHandler updateExceptionHandler,
                    Client.ExceptionHandler defaultExceptionHandler);

    <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method);

    <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException);

    default <R extends TdApi.Object> Future<R> execute(
            TdApi.Function<R> method,
            long timeoutMs,
            Vertx vertx
    ) {
        Promise<R> promise = Promise.promise();
        long timerId = vertx.setTimer(timeoutMs, _ -> {
            if (!promise.future().isComplete()) {
                promise.fail(new TimeoutException("Operation timed out after " + timeoutMs + " ms"));
            }
        });
        execute(method).onComplete(result -> {
            vertx.cancelTimer(timerId);
            if (promise.future().isComplete()) return;
            if (result.succeeded()) promise.complete(result.result());
            else promise.fail(result.cause());
        });
        return promise.future();
    }

    void send(TdApi.Function<?> method, Client.ResultHandler resultHandler);
}
