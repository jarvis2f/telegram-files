package telegram.files;

import cn.hutool.core.util.TypeUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.IOError;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class TelegramClient implements TelegramGateway {
    private static final Log log = LogFactory.get();

    private Client client;

    private volatile boolean initialized = false;

    static {
        Client.setLogMessageHandler(0, new LogMessageHandler());

        try {
            Client.execute(new TdApi.SetLogVerbosityLevel(Config.TELEGRAM_LOG_LEVEL));
            Client.execute(new TdApi.SetLogStream(new TdApi.LogStreamFile(Path.of(Config.LOG_PATH, "tdlib.log").toString(),
                    1 << 27, false)));
        } catch (Client.ExecutionException error) {
            throw new IOError(new IOException("Write access to the current directory is required"));
        }
    }

    @Override
    public void initialize(Client.ResultHandler updateHandler,
                           Client.ExceptionHandler updateExceptionHandler,
                           Client.ExceptionHandler defaultExceptionHandler) {
        List<TdApi.Object> pendingUpdates = new ArrayList<>();
        Client.ResultHandler bufferedUpdateHandler = updateHandler == null
                ? null
                : object -> {
                    synchronized (pendingUpdates) {
                        if (!initialized || !pendingUpdates.isEmpty()) {
                            pendingUpdates.add(object);
                            return;
                        }
                    }
                    updateHandler.onResult(object);
                };

        synchronized (this) {
            if (!initialized) {
                client = Client.create(bufferedUpdateHandler, updateExceptionHandler, defaultExceptionHandler);
                initialized = true;
            }
        }

        while (true) {
            TdApi.Object object;
            synchronized (pendingUpdates) {
                if (pendingUpdates.isEmpty()) {
                    return;
                }
                object = pendingUpdates.remove(0);
            }
            try {
                updateHandler.onResult(object);
            } catch (Throwable cause) {
                handleException(cause, updateExceptionHandler, defaultExceptionHandler);
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method) {
        return execute(method, false);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <R extends TdApi.Object> Future<R> execute(TdApi.Function<R> method, boolean ignoreException) {
        log.trace("Execute method: %s".formatted(TypeUtil.getTypeArgument(method.getClass())));
        if (!initialized) {
            throw new IllegalStateException("Client is not initialized");
        }
        return Future.future(promise -> client.send(method, object -> {
            if (object.getConstructor() == TdApi.Error.CONSTRUCTOR) {
                if (ignoreException) {
                    promise.complete(null);
                    return;
                }
                promise.fail(new TelegramRunException((TdApi.Error) object));
            } else {
                promise.complete((R) object);
            }
        }));
    }

    @Override
    public void send(TdApi.Function<?> method, Client.ResultHandler resultHandler) {
        if (!initialized) {
            throw new IllegalStateException("Client is not initialized");
        }
        client.send(method, resultHandler);
    }

    private static void handleException(Throwable cause,
                                        Client.ExceptionHandler exceptionHandler,
                                        Client.ExceptionHandler defaultExceptionHandler) {
        Client.ExceptionHandler handler = exceptionHandler != null ? exceptionHandler : defaultExceptionHandler;
        if (handler == null) return;
        try {
            handler.onException(cause);
        } catch (Throwable ignored) {
        }
    }

    private static class LogMessageHandler implements Client.LogMessageHandler {
        @Override
        public void onLogMessage(int verbosityLevel, String message) {
            log.debug("TDLib: %s".formatted(message));
        }
    }
}
