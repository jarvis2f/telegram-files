package telegram.files;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import telegram.files.repository.FileRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

@Execution(ExecutionMode.CONCURRENT)
public class FileDownloadStatusConcurrentTest {

    private static final int THREAD_COUNT = 10;

    private static ExecutorService executor;

    static Vertx vertx = Vertx.vertx();

    static FileRecord fileRecord = new FileRecord(
            1, "unique_id", 1, 1, 1, 1, 1, false, 1, 0, "type", "mime_type", "file_name", "thumbnail", "thumbnailUniqueId", "caption", null, null,
            FileRecord.DownloadStatus.idle.name(), FileRecord.TransferStatus.idle.name(), 0, null, null
    );

    @BeforeAll
    static void setUpAll() {
        executor = Executors.newFixedThreadPool(THREAD_COUNT);

        Future<String> future = vertx.deployVerticle(new DataVerticle());
        MessyUtils.await(future);

        Future<FileRecord> fileRecordFuture = DataVerticle.fileRepository.create(fileRecord);
        MessyUtils.await(fileRecordFuture);
    }

    @AfterAll
    static void tearDownAll() {
        executor.shutdown();
        DataVerticleTest.clear(vertx);
    }

    @RepeatedTest(THREAD_COUNT)
    @DisplayName("Concurrent update file download status")
    void concurrentUpdateFileDownloadStatusTest() {
        int newFileId = 2;

        String updateLocalPath = "local_path_" + Thread.currentThread().threadId();
        Long completionDate = System.currentTimeMillis();

        Future<JsonObject> future = DataVerticle.fileRepository.updateDownloadStatus(newFileId, fileRecord.uniqueId(), updateLocalPath, FileRecord.DownloadStatus.downloading, completionDate);
        MessyUtils.await(future);

        Future<FileRecord> finalRecordFuture = DataVerticle.fileRepository.getByPrimaryKey(newFileId, fileRecord.uniqueId());
        FileRecord finalRecord = MessyUtils.await(finalRecordFuture);

        Assertions.assertEquals(FileRecord.DownloadStatus.downloading.name(), finalRecord.downloadStatus());
        Assertions.assertNotNull(finalRecord.localPath());
        Assertions.assertNotNull(finalRecord.completionDate());
    }

    @Test
    @DisplayName("Multi-threaded concurrent update file download status using ExecutorService")
    void multiThreadedConcurrentUpdateFileDownloadStatusTest() throws Exception {
        int newFileId = 2;

        List<java.util.concurrent.Future<Void>> futures = new ArrayList<>();
        AtomicInteger completedCount = new AtomicInteger(0);

        for (int i = 0; i < THREAD_COUNT; i++) {
            int threadIndex = i;
            futures.add(executor.submit(() -> {
                String updateLocalPath = "local_path_" + threadIndex;
                Long completionDate = System.currentTimeMillis();
                System.out.println("Thread start update: " + Thread.currentThread().getName() + ", localPath: " + updateLocalPath + ", completionDate: " + completionDate);

                try {
                    DataVerticle.fileRepository.updateDownloadStatus(newFileId,
                                    fileRecord.uniqueId(),
                                    updateLocalPath,
                                    FileRecord.DownloadStatus.downloading,
                                    completionDate)
                            .onComplete(result -> completedCount.incrementAndGet());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }

        for (int i = 0; i < THREAD_COUNT; i++) {
            futures.add(executor.submit(() -> {
                try {
                    DataVerticle.fileRepository.getByUniqueId(fileRecord.uniqueId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                return null;
            }));
        }

        for (java.util.concurrent.Future<Void> future : futures) {
            future.get();
        }
        while (completedCount.get() < THREAD_COUNT) {
            Thread.sleep(100);
        }

        Future<FileRecord> finalRecordFuture = DataVerticle.fileRepository.getByPrimaryKey(newFileId, fileRecord.uniqueId());
        FileRecord finalRecord = MessyUtils.await(finalRecordFuture);

        Assertions.assertEquals(FileRecord.DownloadStatus.downloading.name(), finalRecord.downloadStatus());
        Assertions.assertNotNull(finalRecord.localPath());
        Assertions.assertNotNull(finalRecord.completionDate());
    }

    @Test
    @DisplayName("Multi-process concurrent update file download status")
    void multiProcessConcurrentUpdateFileDownloadStatusTest() throws Exception {
        int newFileId = 2;

        int processCount = 5;
        List<Process> processes = new ArrayList<>();
        String classpath = System.getProperty("java.class.path");
        String javaPath = System.getProperty("java.home") + "/bin/java";

        for (int i = 0; i < processCount; i++) {
            String localPath = "local_path_" + i;
            ProcessBuilder pb = new ProcessBuilder(javaPath,
                    "-cp",
                    classpath,
                    "-Djava.library.path=" + System.getenv("TDLIB_PATH"),
                    "telegram.files.UpdateFileDownloadProcess",
                    String.valueOf(newFileId),
                    "unique_id",
                    localPath,
                    "downloading",
                    "1");
            pb.environment().putAll(System.getenv());
            pb.inheritIO();
            processes.add(pb.start());
        }

        for (Process process : processes) {
            process.waitFor();
        }

        Future<FileRecord> future = DataVerticle.fileRepository.getByPrimaryKey(newFileId, fileRecord.uniqueId());
        FileRecord finalRecord = MessyUtils.await(future);

        Assertions.assertNotNull(finalRecord, "Final record should not be null");
        Assertions.assertTrue(
                finalRecord.downloadStatus().equals(FileRecord.DownloadStatus.downloading.name()) ||
                finalRecord.downloadStatus().equals(FileRecord.DownloadStatus.completed.name()),
                "Download status should be one of the concurrent updates"
        );
        Assertions.assertTrue(
                IntStream.range(0, processCount).anyMatch(i -> finalRecord.localPath().equals("local_path_" + i)),
                "Local path should match one of the concurrent updates"
        );
        Assertions.assertTrue(
                finalRecord.completionDate().equals(1L) || finalRecord.completionDate().equals(2L),
                "Completion date should match one of the concurrent updates"
        );
    }
}
