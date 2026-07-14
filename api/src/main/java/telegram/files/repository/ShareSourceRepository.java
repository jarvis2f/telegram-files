package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface ShareSourceRepository {

    Future<ShareSourceRecord> getById(String id);

    Future<ShareSourceRecord> getBySourceKey(String sourceKey);

    Future<ShareSourceRecord> getByPlatformResourceId(String platformResourceId);

    Future<List<ShareSourceRecord>> list();

    Future<List<ShareSourceRecord>> listPage(int offset, int limit);

    Future<Long> count();

    Future<List<ShareSourceRecord>> listByFileUniqueIds(List<String> fileUniqueIds);

    Future<List<ShareSourceRecord>> listRetryable(long now, int limit);

    Future<ShareSourceRecord> save(ShareSourceRecord record);

    Future<Void> markPublished(String id, String platformResourceId, long now);

    Future<Void> markPending(
            String id,
            String status,
            String errorCode,
            int attemptCount,
            long nextAttemptAt,
            long now
    );

    Future<Void> markRevoked(String id, long now);

    Future<Void> markDownloaded(String id, long now);
}
