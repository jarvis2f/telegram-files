package telegram.files.repository;

import io.vertx.core.Future;
import telegram.files.share.FileReadyForShare;

public interface ShareRepository {

    Future<Void> enqueueFileReady(FileReadyForShare event);

    Future<Void> recoverPendingJobs();
}
