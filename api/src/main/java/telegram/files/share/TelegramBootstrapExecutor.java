package telegram.files.share;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.drinkless.tdlib.TdApi;
import telegram.files.TdApiHelp;
import telegram.files.TelegramVerticle;
import telegram.files.TelegramVerticles;
import telegram.files.repository.FileRecord;
import telegram.files.repository.FileRepository;
import telegram.files.repository.ShareSourceRecord;
import telegram.files.repository.ShareSourceRepository;
import telegram.files.share.model.OpaqueSourceToken;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Objects;

public final class TelegramBootstrapExecutor {

    @FunctionalInterface
    public interface ProgressReporter {
        Future<Void> report(String phase, int percent, long completedBytes, long totalBytes);
    }

    private final Vertx vertx;

    private final ShareSourceRepository sourceRepository;

    private final FileRepository fileRepository;

    private final ContentHashService hashService;

    private final Clock clock;

    public TelegramBootstrapExecutor(
            Vertx vertx,
            ShareSourceRepository sourceRepository,
            FileRepository fileRepository,
            ContentHashService hashService
    ) {
        this(
                vertx,
                sourceRepository,
                fileRepository,
                hashService,
                Clock.systemUTC()
        );
    }

    TelegramBootstrapExecutor(
            Vertx vertx,
            ShareSourceRepository sourceRepository,
            FileRepository fileRepository,
            ContentHashService hashService,
            Clock clock
    ) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.sourceRepository = Objects.requireNonNull(sourceRepository, "sourceRepository");
        this.fileRepository = Objects.requireNonNull(fileRepository, "fileRepository");
        this.hashService = Objects.requireNonNull(hashService, "hashService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Future<JsonObject> execute(TelegramBootstrapTask task, ProgressReporter progress) {
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(progress, "progress");
        return detached(sourceRepository.getByPlatformResourceId(task.resourceId()))
                .compose(source -> validateSource(task, source))
                .compose(source -> detached(fileRepository.getByUniqueId(task.fileUniqueId()))
                        .compose(file -> validateFile(task, source, file)))
                .compose(context -> progress.report(
                                "FETCHING_TELEGRAM", 1, 0, task.fileSize()
                        )
                        .compose(_ -> localOrDownload(context.source(), context.file()))
                        .map(path -> new Downloaded(context.source(), path)))
                .compose(downloaded -> progress.report(
                                "HASHING", 60, task.fileSize(), task.fileSize()
                        )
                        .compose(_ -> hashService.sha256(downloaded.path()))
                        .map(hash -> new Hashed(downloaded.source(), downloaded.path(), hash)))
                .compose(hashed -> progress.report(
                                "STORING", 85, task.fileSize(), task.fileSize()
                        )
                        .compose(_ -> detached(sourceRepository.markDownloaded(
                                hashed.source().id(), clock.millis()
                        )))
                        .compose(_ -> progress.report(
                                "STORING", 100, task.fileSize(), task.fileSize()
                        ))
                        .map(new JsonObject()
                                .put("sha256", hashed.sha256())
                                .put("fileSize", Long.toString(task.fileSize()))))
                .recover(failure -> Future.failedFuture(classify(failure)));
    }

    private <T> Future<T> detached(Future<T> operation) {
        Promise<T> promise = Promise.promise();
        operation.onComplete(result -> vertx.setTimer(1, _ -> promise.handle(result)));
        return promise.future();
    }

    private Future<ShareSourceRecord> validateSource(
            TelegramBootstrapTask task,
            ShareSourceRecord source
    ) {
        if (source == null || !"PUBLISHED".equals(source.status())) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true, "Published Telegram source is unavailable"
            ));
        }
        if (!source.fileUniqueId().equals(task.fileUniqueId())
            || source.fileSize() != task.fileSize()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true, "Task source identity does not match the local source"
            ));
        }
        if (!OpaqueSourceToken.matches(task.opaqueSourceToken(), source.opaqueTokenDigest())) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_PERMISSION_DENIED", false, "Opaque source proof is invalid"
            ));
        }
        return Future.succeededFuture(source);
    }

    private Future<SourceFile> validateFile(
            TelegramBootstrapTask task,
            ShareSourceRecord source,
            FileRecord file
    ) {
        if (file == null
            || file.telegramId() != source.telegramId()
            || file.size() != task.fileSize()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true, "Telegram file record is unavailable"
            ));
        }
        return Future.succeededFuture(new SourceFile(source, file));
    }

    private Future<Path> localOrDownload(ShareSourceRecord source, FileRecord file) {
        Path local = file.localPath() == null || file.localPath().isBlank()
                ? null
                : Path.of(file.localPath()).toAbsolutePath().normalize();
        return regularFile(local).compose(available -> {
            if (available) {
                return Future.succeededFuture(local);
            }
            TelegramVerticle telegram;
            try {
                telegram = TelegramVerticles.getOrElseThrow(source.telegramId());
            } catch (RuntimeException exception) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "NODE_OFFLINE", true, "Telegram account is not available", exception
                ));
            }
            if (!telegram.authorized) {
                return Future.failedFuture(new BootstrapExecutionException(
                        "SOURCE_PERMISSION_DENIED", false, "Telegram account is not authorized"
                ));
            }
            return telegram.client.execute(new TdApi.GetMessage(source.chatId(), source.messageId()))
                    .compose(message -> validateMessageFile(source, message))
                    .compose(current -> currentPath(current).compose(currentPath -> {
                        if (currentPath != null) {
                            return recordDownloadedPath(current, source, currentPath);
                        }
                        Future<Void> reset = current.local != null
                                             && current.local.isDownloadingCompleted
                                ? telegram.client.execute(new TdApi.DeleteFile(current.id)).mapEmpty()
                                : Future.succeededFuture();
                        return reset.compose(_ -> telegram.client.execute(
                                        new TdApi.DownloadFile(current.id, 32, 0, 0, true)
                                ))
                                .compose(downloaded -> validateDownload(source, downloaded))
                                .compose(downloaded -> requireRegularFile(downloaded)
                                        .compose(downloadedPath -> recordDownloadedPath(
                                                downloaded, source, downloadedPath
                                        )));
                    }));
        });
    }

    private Future<Path> recordDownloadedPath(
            TdApi.File file,
            ShareSourceRecord source,
            Path path
    ) {
        return fileRepository.updateDownloadStatus(
                        file.id,
                        source.fileUniqueId(),
                        path.toString(),
                        FileRecord.DownloadStatus.completed,
                        clock.millis()
                )
                .map(path);
    }

    private Future<Path> currentPath(TdApi.File file) {
        Path path = normalizedPath(file);
        return regularFile(path).map(available -> available ? path : null);
    }

    private Future<Path> requireRegularFile(TdApi.File file) {
        Path path = normalizedPath(file);
        return regularFile(path).compose(available -> available
                ? Future.succeededFuture(path)
                : Future.failedFuture(new BootstrapExecutionException(
                        "SOURCE_UNAVAILABLE", true,
                        "TDLib completed the download without a readable local file"
                )));
    }

    private static Path normalizedPath(TdApi.File file) {
        if (file == null || file.local == null
            || file.local.path == null || file.local.path.isBlank()) {
            return null;
        }
        return Path.of(file.local.path).toAbsolutePath().normalize();
    }

    private Future<Boolean> regularFile(Path path) {
        if (path == null) {
            return Future.succeededFuture(false);
        }
        return vertx.executeBlocking(() -> Files.isRegularFile(path) && !Files.isSymbolicLink(path), false);
    }

    private static Future<TdApi.File> validateDownload(ShareSourceRecord source, TdApi.File file) {
        if (file == null || file.local == null || !file.local.isDownloadingCompleted
            || file.local.path == null || file.local.path.isBlank()
            || file.remote == null || !source.fileUniqueId().equals(file.remote.uniqueId)
            || effectiveSize(file) != source.fileSize()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true, "TDLib did not return the expected complete file"
            ));
        }
        return Future.succeededFuture(file);
    }

    static Future<TdApi.File> validateMessageFile(
            ShareSourceRecord source,
            TdApi.Message message
    ) {
        TdApi.File file = message == null || message.content == null
                ? null
                : TdApiHelp.getFileHandler(message)
                        .map(TdApiHelp.FileHandler::getFile)
                        .orElse(null);
        if (file == null || file.remote == null
            || !source.fileUniqueId().equals(file.remote.uniqueId)
            || effectiveSize(file) != source.fileSize()) {
            return Future.failedFuture(new BootstrapExecutionException(
                    "SOURCE_UNAVAILABLE", true,
                    "Telegram message no longer contains the published file"
            ));
        }
        return Future.succeededFuture(file);
    }

    private static long effectiveSize(TdApi.File file) {
        return file.size == 0 ? file.expectedSize : file.size;
    }

    private static BootstrapExecutionException classify(Throwable failure) {
        if (failure instanceof BootstrapExecutionException bootstrap) {
            return bootstrap;
        }
        if (failure.getMessage() != null
            && failure.getMessage().contains("STORAGE_LAYOUT_UNSUPPORTED")) {
            return new BootstrapExecutionException(
                    "STORAGE_LAYOUT_UNSUPPORTED", false,
                    "Telegram and Torrent directories must support same-filesystem hard links", failure
            );
        }
        if (failure instanceof java.nio.file.FileSystemException) {
            return new BootstrapExecutionException(
                    "INSUFFICIENT_DISK", true, "Stable content could not be written", failure
            );
        }
        if (failure instanceof java.io.IOException) {
            return new BootstrapExecutionException(
                    "HASH_MISMATCH", true, "Content verification failed", failure
            );
        }
        return new BootstrapExecutionException(
                "INTERNAL_RETRYABLE", true, "Telegram bootstrap failed", failure
        );
    }

    private record SourceFile(ShareSourceRecord source, FileRecord file) {
    }

    private record Downloaded(ShareSourceRecord source, Path path) {
    }

    private record Hashed(ShareSourceRecord source, Path path, String sha256) {
    }

}
