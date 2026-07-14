package telegram.files;

import io.vertx.core.Future;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Deterministic TDLib replacement shared by Telegram component tests. */
final class ScriptedTelegramGateway implements TelegramGateway {

    private final Function<TdApi.Function<?>, TdApi.Object> responder;
    private final List<TdApi.Function<?>> requests = new ArrayList<>();
    private Client.ResultHandler updateHandler;
    private Client.ExceptionHandler defaultExceptionHandler;

    ScriptedTelegramGateway(Function<TdApi.Function<?>, TdApi.Object> responder) {
        this.responder = Objects.requireNonNull(responder, "responder");
    }

    @Override
    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        this.updateHandler = Objects.requireNonNull(updateHandler, "updateHandler");
        this.defaultExceptionHandler = Objects.requireNonNull(defaultExceptionHandler, "defaultExceptionHandler");
    }

    @Override
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        return execute(method, false);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException) {
        requests.add(method);
        try {
            TdApi.Object response = responder.apply(method);
            if (response instanceof TdApi.Error error) {
                return ignoreException
                        ? Future.succeededFuture()
                        : Future.failedFuture(new TelegramRunException(error));
            }
            return Future.succeededFuture((R) response);
        } catch (Throwable failure) {
            return Future.failedFuture(failure);
        }
    }

    @Override
    public void send(TdApi.Function<?> method, Client.ResultHandler resultHandler) {
        requests.add(method);
        try {
            resultHandler.onResult(responder.apply(method));
        } catch (Throwable failure) {
            defaultExceptionHandler.onException(failure);
        }
    }

    void emit(TdApi.Object update) {
        if (updateHandler == null) throw new IllegalStateException("Gateway is not initialized");
        updateHandler.onResult(update);
    }

    List<TdApi.Function<?>> requests() {
        return List.copyOf(requests);
    }
}
