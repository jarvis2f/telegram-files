package telegram.files.share;

import io.vertx.core.Future;
import telegram.files.repository.ShareRepository;

import java.util.Objects;

public final class PersistentShareService implements ShareService {

    private final ShareRepository repository;

    public PersistentShareService(ShareRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    public Future<Void> handleFileReady(FileReadyForShare event) {
        return repository.enqueueFileReady(event);
    }

    @Override
    public Future<Void> recoverPendingJobs() {
        return repository.recoverPendingJobs();
    }
}
