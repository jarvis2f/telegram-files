package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import org.jooq.lambda.tuple.Tuple2;

import java.util.List;
import java.util.Map;

public interface TorrentRepository {

    Future<TorrentRecord> save(TorrentRecord record);

    Future<TorrentRecord> getByResourceId(String resourceId);

    Future<TorrentRecord> getByInfoHash(String infoHashV1);

    Future<List<TorrentRecord>> listActive(int limit);

    Future<List<TorrentRecord>> listByTelegramFileUniqueIds(List<String> fileUniqueIds);

    Future<Tuple2<List<TorrentRecord>, Long>> listSeedOnly(Map<String, String> filter);

    Future<JsonObject> countSeedOnlyWithType(Map<String, String> filter);

    Future<Boolean> updateStatus(
            String infoHashV1,
            String status,
            int progressPermille,
            long downloadedBytes,
            long uploadedBytes,
            long downloadSpeedBytesPerSecond,
            long uploadSpeedBytesPerSecond,
            int connectedPeers,
            String savePath,
            long now
    );

    Future<Integer> countByStatuses(List<String> statuses);
}
