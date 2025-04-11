package telegram.files;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.lang.Version;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingKey;
import telegram.files.repository.TelegramRecord;

@ExtendWith(VertxExtension.class)
public class DataVerticleTest {

    private static final Log log = LogFactory.get();

    @BeforeAll
    static void setUp() {
        printDBInfo();
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext testContext) {
        clear(vertx).onComplete(testContext.succeedingThenComplete());
    }

    public static void printDBInfo() {
        if (Config.isSqlite()) {
            log.debug("DB_PATH: " + DataVerticle.getDataPath());
        } else if (Config.isPostgres() || Config.isMysql()) {
            log.debug("DB_HOST: " + Config.DB_HOST);
            log.debug("DB_PORT: " + Config.DB_PORT);
            log.debug("DB_NAME: " + Config.DB_NAME);
            log.debug("DB_USER: " + Config.DB_USER);
        } else {
            log.error("Unknown database type");
        }
    }

    public static Future<Void> clear(Vertx vertx) {
        if (Config.isSqlite()) {
            String appRoot = Config.APP_ROOT;
            if (FileUtil.file(appRoot).exists()) {
                FileUtil.clean(appRoot);
            }
            return Future.succeededFuture();
        } else if (Config.isPostgres()) {
            SqlClient sqlClient = DataVerticle.createSqlClient(vertx, DataVerticle.createDefaultOptions());

            return sqlClient
                    .query("ALTER DATABASE \"%s\" ALLOW_CONNECTIONS false;".formatted(Config.DB_NAME)).execute()
                    .compose(r -> sqlClient
                            .query("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '%s';".formatted(Config.DB_NAME)).execute()
                    )
                    .compose(r -> sqlClient
                            .query("DROP DATABASE \"%s\";".formatted(Config.DB_NAME)).execute()
                    )
                    .onFailure(t -> log.error("Failed to clear database", t))
                    .onComplete(r -> {
                        if (r.succeeded()) {
                            log.debug("Database cleared");
                        }
                        sqlClient.close();
                    })
                    .mapEmpty();
        } else if (Config.isMysql()) {
            SqlClient sqlClient = DataVerticle.createSqlClient(vertx, DataVerticle.createDefaultOptions());

            return sqlClient
                    .query("DROP SCHEMA IF EXISTS `%s`".formatted(Config.DB_NAME))
                    .execute()
                    .onFailure(t -> log.error("Failed to clear database", t))
                    .onComplete(r -> {
                        if (r.succeeded()) {
                            log.debug("Database cleared");
                        }
                        sqlClient.close();
                    })
                    .mapEmpty();
        } else {
            return Future.failedFuture("Unknown database type");
        }
    }

    @BeforeEach
    @DisplayName("Deploy data verticle")
    void deployVerticle(Vertx vertx, VertxTestContext testContext) {
        vertx.deployVerticle(new DataVerticle())
                .onComplete(testContext.succeedingThenComplete());
    }

    @Test
    @DisplayName("Check table initialization")
    void checkTableInitialization(Vertx vertx, VertxTestContext testContext) {
        DataVerticle.settingRepository.<Version>getByKey(SettingKey.version)
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertNotNull(r);
                    Assertions.assertEquals(new Version(Start.VERSION), r);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Test Create telegram record")
    void createTelegramRecordTest(Vertx vertx, VertxTestContext testContext) {
        TelegramRecord telegramRecord = new TelegramRecord(1, "test", "test", null);
        DataVerticle.telegramRepository.create(telegramRecord)
                .compose(r -> DataVerticle.telegramRepository.getById(r.id()))
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertEquals(telegramRecord, r);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Test Get all telegram record")
    void getAllTelegramRecordTest(Vertx vertx, VertxTestContext testContext) {
        TelegramRecord telegramRecord = new TelegramRecord(1, "test", "test", null);
        TelegramRecord telegramRecord2 = new TelegramRecord(2, "test2", "test2", null);
        DataVerticle.telegramRepository.create(telegramRecord)
                .compose(r -> DataVerticle.telegramRepository.create(telegramRecord2))
                .compose(r -> DataVerticle.telegramRepository.getAll())
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertEquals(2, r.size());
                    Assertions.assertEquals(telegramRecord, r.get(0));
                    Assertions.assertEquals(telegramRecord2, r.get(1));
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Test Get file record by primary key")
    void getFileRecordByPrimaryKeyTest(Vertx vertx, VertxTestContext testContext) {
        FileRecord fileRecord = new FileRecord(
                1, "unique_id", 1, 1, 1, 1, 1, false, 1, 0, "type", "mime_type", "file_name", "thumbnail", "thumbnailUniqueId", "caption", "extra", "local_path", "download_status", "transfer_status", 0, null, null
        );
        DataVerticle.fileRepository.create(fileRecord)
                .compose(r -> DataVerticle.fileRepository.getByPrimaryKey(r.id(), r.uniqueId()))
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertEquals(fileRecord, r);
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Test update file download status")
    void updateFileDownloadStatusTest(Vertx vertx, VertxTestContext testContext) {
        FileRecord fileRecord = new FileRecord(
                1, "unique_id", 1, 1, 1, 1, 1, false, 1, 0, "type", "mime_type", "file_name", "thumbnail", "thumbnailUniqueId", "caption", "extra", null, FileRecord.DownloadStatus.idle.name(), FileRecord.TransferStatus.idle.name(), 0, null, null
        );
        String updateLocalPath = "local_path";
        Long completionDate = 1L;
        int newFileId = 2;
        DataVerticle.fileRepository.create(fileRecord)
                .compose(r -> DataVerticle.fileRepository.updateDownloadStatus(newFileId, r.uniqueId(), updateLocalPath, FileRecord.DownloadStatus.downloading, completionDate))
                .compose(r -> {
                    testContext.verify(() -> {
                        Assertions.assertEquals(updateLocalPath, r.getString("localPath"));
                        Assertions.assertEquals(FileRecord.DownloadStatus.downloading.name(), r.getString("downloadStatus"));
                        Assertions.assertEquals(completionDate, r.getLong("completionDate"));
                    });
                    return DataVerticle.fileRepository.getByPrimaryKey(newFileId, fileRecord.uniqueId());
                })
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertEquals(newFileId, r.id());
                    Assertions.assertEquals(FileRecord.DownloadStatus.downloading.name(), r.downloadStatus());
                    Assertions.assertEquals(updateLocalPath, r.localPath());
                    testContext.completeNow();
                })));
    }

    @Test
    @DisplayName("Test update file transfer status")
    void updateFileTransferStatusTest(Vertx vertx, VertxTestContext testContext) {
        FileRecord fileRecord = new FileRecord(
                1, "unique_id", 1, 1, 1, 1, 1, false, 1, 0, "type", "mime_type", "file_name", "thumbnail", "thumbnailUniqueId", "caption", "extra", null, FileRecord.DownloadStatus.idle.name(), FileRecord.TransferStatus.idle.name(), 0, null, null
        );
        String updateLocalPath = "local_path";
        DataVerticle.fileRepository.create(fileRecord)
                .compose(r -> DataVerticle.fileRepository.updateTransferStatus(r.uniqueId(), FileRecord.TransferStatus.completed, updateLocalPath))
                .compose(r -> {
                    testContext.verify(() -> {
                        Assertions.assertEquals(updateLocalPath, r.getString("localPath"));
                        Assertions.assertEquals(FileRecord.TransferStatus.completed.name(), r.getString("transferStatus"));
                    });
                    return DataVerticle.fileRepository.getByUniqueId(fileRecord.uniqueId());
                })
                .onComplete(testContext.succeeding(r -> testContext.verify(() -> {
                    Assertions.assertEquals(FileRecord.TransferStatus.completed.name(), r.transferStatus());
                    Assertions.assertEquals(updateLocalPath, r.localPath());
                    testContext.completeNow();
                })));
    }

}
