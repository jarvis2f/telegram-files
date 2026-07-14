package telegram.files.repository;

import io.vertx.core.Future;

import java.util.List;

public interface TorrentStatisticEventRepository {
    Future<TorrentStatisticEventRecord> latest(String resourceId);

    Future<Void> create(TorrentStatisticEventRecord record);

    Future<List<TorrentStatisticEventRecord>> listPending(int limit);

    Future<Void> markDelivered(List<String> eventIds, long now);
}
