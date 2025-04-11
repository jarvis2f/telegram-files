package telegram.files.repository;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.jooq.lambda.tuple.Tuple3;

import java.util.List;
import java.util.Map;

public interface FileRepository {
    Future<FileRecord> create(FileRecord fileRecord);

    Future<Boolean> createIfNotExist(FileRecord fileRecord);

    Future<Tuple3<List<FileRecord>, Long, Long>> getFiles(long chatId, Map<String, String> filter);

    Future<Map<String, FileRecord>> getFilesByUniqueId(List<String> uniqueIds);

    Future<FileRecord> getByPrimaryKey(int fileId, String uniqueId);

    Future<FileRecord> getByUniqueId(String uniqueId);

    Future<String> getCaptionByMediaAlbumId(long mediaAlbumId);

    Future<JsonObject> getDownloadStatistics(long telegramId);

    Future<JsonObject> getDownloadStatistics();

    Future<JsonArray> getCompletedRangeStatistics(long id, long startTime, long endTime, int timeRange);

    Future<Integer> countByStatus(long telegramId, FileRecord.DownloadStatus downloadStatus);

    Future<JsonObject> updateDownloadStatus(int fileId,
                                            String uniqueId,
                                            String localPath,
                                            FileRecord.DownloadStatus downloadStatus,
                                            Long completionDate);

    Future<JsonObject> updateTransferStatus(String uniqueId,
                                            FileRecord.TransferStatus transferStatus,
                                            String localPath);

    Future<Void> updateFileId(int fileId, String uniqueId);

    Future<Integer> updateCaptionByMediaAlbumId(long mediaAlbumId, String caption);

    Future<Void> updateTags(String uniqueId, String tags);

    Future<Void> deleteByUniqueId(String uniqueId);
}
