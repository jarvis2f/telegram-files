package telegram.files.share;

import io.vertx.core.Future;

public interface ShareService {

    Future<Void> handleFileReady(FileReadyForShare event);

    default Future<Void> recoverPendingJobs() {
        return Future.succeededFuture();
    }

    static ShareService noop() {
        return event -> Future.succeededFuture();
    }
}
