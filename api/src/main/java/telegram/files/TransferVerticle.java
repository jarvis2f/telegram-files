package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.json.JsonObject;
import org.jooq.lambda.tuple.Tuple3;
import telegram.files.repository.FileRecord;
import telegram.files.repository.SettingAutoRecords;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TransferVerticle extends AbstractVerticle {
    private static final Log log = LogFactory.get();

    private static final int HISTORY_SCAN_INTERVAL = 2 * 60 * 1000;

    private static final int TRANSFER_INTERVAL = 3 * 1000;

    private final SettingAutoRecords autoRecords;

    private final Map<String, Transfer> transfers = new HashMap<>();

    private final BlockingQueue<WaitingTransferFile> waitingTransferFiles = new LinkedBlockingQueue<>();

    private volatile boolean isStopped = false;

    private volatile Transfer beingTransferred;

    public TransferVerticle() {
        this.autoRecords = AutomationsHolder.INSTANCE.autoRecords();
        AutomationsHolder.INSTANCE.registerOnRemoveListener(removedItems -> removedItems.forEach(item -> {
            waitingTransferFiles.removeIf(waitingTransferFile -> waitingTransferFile.uniqueId().equals(item.uniqueKey()));
            transfers.remove(item.uniqueKey());
        }));
    }

    @Override
    public void start(Promise<Void> startPromise) {
        initEventConsumer().onSuccess(v -> {
            vertx.setPeriodic(0, HISTORY_SCAN_INTERVAL, id -> addHistoryFiles());
            vertx.setPeriodic(0, TRANSFER_INTERVAL, id -> startTransfer());

            log.info("""
                    Transfer verticle started!
                    |History scan interval: %s ms
                    |Transfer interval: %s ms
                    |Auto chats: %s
                    """.formatted(HISTORY_SCAN_INTERVAL, TRANSFER_INTERVAL, autoRecords.getTransferEnabledItems().size()));

            startPromise.complete();
        }).onFailure(startPromise::fail);
    }

    @Override
    public void stop(Promise<Void> stopPromise) {
        isStopped = true;
        if (beingTransferred != null) {
            log.info("Wait for transfer to complete, file: %s".formatted(beingTransferred.getTransferRecord().uniqueId()));
            while (beingTransferred != null) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    log.error("Stop transfer verticle error: %s".formatted(e.getMessage()));
                    stopPromise.fail(e);
                }
            }
        }
        log.info("Transfer verticle stopped");
        stopPromise.complete();
    }

    private Future<Void> initEventConsumer() {
        vertx.eventBus().consumer(EventEnum.TELEGRAM_EVENT.address(), message -> {
            JsonObject jsonObject = (JsonObject) message.body();
            EventPayload payload = jsonObject.getJsonObject("payload").mapTo(EventPayload.class);
            if (payload == null || payload.type() != EventPayload.TYPE_FILE_STATUS) {
                return;
            }

            if (payload.data() != null && payload.data() instanceof Map<?, ?> data && StrUtil.isNotBlank((String) data.get("downloadStatus"))) {
                FileRecord.DownloadStatus downloadStatus = FileRecord.DownloadStatus.valueOf((String) data.get("downloadStatus"));
                if (downloadStatus != FileRecord.DownloadStatus.completed) {
                    return;
                }
                FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId((String) data.get("uniqueId")));

                SettingAutoRecords.Automation automation = null;
                if (fileRecord.threadChatId() != 0 && fileRecord.messageThreadId() != 0 && fileRecord.threadChatId() == fileRecord.chatId()) {
                    // thread message file,try to get the main message
                    FileRecord mainFileRecord = Future.await(DataVerticle.fileRepository.getMainFileByThread(
                            fileRecord.telegramId(),
                            fileRecord.threadChatId(),
                            fileRecord.messageThreadId()));
                    if (mainFileRecord != null) {
                        automation = autoRecords.getItem(mainFileRecord.telegramId(), mainFileRecord.chatId());
                    }
                } else {
                    automation = autoRecords.getItem(fileRecord.telegramId(), fileRecord.chatId());
                }

                if (automation == null || !automation.transfer.enabled || getTransfer(automation) == null) {
                    return;
                }

                if (addWaitingTransferFile(automation.telegramId, automation.chatId, fileRecord.uniqueId())) {
                    log.debug("Add file to transfer queue: %s".formatted(fileRecord.uniqueId()));
                }
            }
        });

        return Future.succeededFuture();
    }

    private void addHistoryFiles() {
        if (CollUtil.isEmpty(autoRecords.automations)) {
            return;
        }
        log.trace("Start scan history files for transfer");
        for (SettingAutoRecords.Automation automation : autoRecords.automations) {
            if (!automation.transfer.enabled
                || !automation.transfer.rule.transferHistory
                || automation.isComplete(SettingAutoRecords.HISTORY_TRANSFER_STATE)) {
                continue;
            }
            Transfer transfer = getTransfer(automation);
            if (transfer == null) {
                continue;
            }
            Tuple3<List<FileRecord>, Long, Long> filesTuple = Future.await(DataVerticle.fileRepository.getFiles(automation.chatId,
                    Map.of("downloadStatus", FileRecord.DownloadStatus.completed.name(),
                            "transferStatus", FileRecord.TransferStatus.idle.name()
                    )
            ));
            List<FileRecord> files = filesTuple.v1;
            if (CollUtil.isEmpty(files)) {
                log.debug("No history files found for transfer: %s".formatted(automation.uniqueKey()));
                automation.complete(SettingAutoRecords.HISTORY_TRANSFER_STATE);
                continue;
            }

            int count = 0;
            for (FileRecord fileRecord : files) {
                if (addWaitingTransferFile(fileRecord)) {
                    count++;
                }
            }

            if (count > 0) {
                log.info("Add history files to transfer queue: %s".formatted(count));
                break;
            }
        }
    }

    private boolean addWaitingTransferFile(FileRecord fileRecord) {
        return addWaitingTransferFile(fileRecord.telegramId(), fileRecord.chatId(), fileRecord.uniqueId());
    }

    private boolean addWaitingTransferFile(long telegramId, long chatId, String uniqueId) {
        WaitingTransferFile waitingTransferFile = new WaitingTransferFile(telegramId, chatId, uniqueId);
        if (!waitingTransferFiles.contains(waitingTransferFile)) {
            waitingTransferFiles.add(waitingTransferFile);
            return true;
        }
        return false;
    }

    private Transfer getTransfer(SettingAutoRecords.Automation automation) {
        if (automation == null || !automation.transfer.enabled) {
            return null;
        }

        SettingAutoRecords.TransferRule transferRule = automation.transfer.rule;

        if (transfers.containsKey(automation.uniqueKey())) {
            Transfer transfer = transfers.get(automation.uniqueKey());
            if (!transfer.isRuleUpdated(transferRule)) {
                return transfer;
            } else {
                log.debug("Transfer rule updated: %s".formatted(automation.uniqueKey()));
                transfers.remove(automation.uniqueKey());
            }
        }

        return transfers.computeIfAbsent(automation.uniqueKey(), k -> {
            Transfer transfer = Transfer.create(transferRule);
            transfer.transferStatusUpdated = updated ->
                    updateTransferStatus(updated.fileRecord(), updated.transferStatus(), updated.localPath());
            return transfer;
        });
    }

    public void startTransfer() {
        if (beingTransferred != null) {
            return;
        }
        try {
            WaitingTransferFile waitingTransferFile = waitingTransferFiles.poll(1, TimeUnit.SECONDS);
            if (waitingTransferFile == null) {
                log.trace("No file to transfer");
                return;
            }
            Transfer transfer = transfers.get("%d:%d".formatted(waitingTransferFile.telegramId(), waitingTransferFile.chatId()));
            if (transfer == null) {
                return;
            }
            if (beingTransferred == transfer) {
                waitingTransferFiles.add(waitingTransferFile);
                log.debug("Transfer is busy: %s".formatted(waitingTransferFile.uniqueId));
                return;
            }
            FileRecord fileRecord = Future.await(DataVerticle.fileRepository.getByUniqueId(waitingTransferFile.uniqueId));
            if (fileRecord == null) {
                log.error("File not found: %s".formatted(waitingTransferFile.uniqueId));
                return;
            }

            startTransfer(fileRecord, transfer);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                log.debug("Transfer loop interrupted");
            } else {
                log.error(e, "Transfer error");
            }
        }
    }

    public void startTransfer(FileRecord fileRecord, Transfer transfer) {
        if (isStopped) {
            return;
        }
        if (!fileRecord.isDownloadStatus(FileRecord.DownloadStatus.completed)
            || StrUtil.isBlank(fileRecord.localPath())) {
            log.warn("File {} is not downloaded yet", fileRecord.id());
            return;
        }
        if (fileRecord.transferStatus() != null
            && !fileRecord.isTransferStatus(FileRecord.TransferStatus.idle)) {
            log.debug("File {} transfer status is not idle: {}", fileRecord.id(), fileRecord.transferStatus());
            return;
        }

        beingTransferred = transfer;
        transfer.transfer(fileRecord);
        beingTransferred = null;
    }

    private void updateTransferStatus(FileRecord fileRecord, FileRecord.TransferStatus transferStatus, String localPath) {
        Future.await(DataVerticle.fileRepository.updateTransferStatus(fileRecord.uniqueId(), transferStatus, localPath)
                .onSuccess(fileUpdated -> {
                    if (fileUpdated != null && !fileUpdated.isEmpty()) {
                        EventPayload payload = EventPayload.build(EventPayload.TYPE_FILE_STATUS, new JsonObject()
                                .put("fileId", fileRecord.id())
                                .put("uniqueId", fileRecord.uniqueId())
                                .put("transferStatus", fileUpdated.getString("transferStatus"))
                                .put("localPath", fileUpdated.getString("localPath"))
                        );
                        vertx.eventBus().publish(EventEnum.TELEGRAM_EVENT.address(),
                                JsonObject.of("telegramId", fileRecord.telegramId(), "payload", JsonObject.mapFrom(payload))
                        );
                    }
                }));
    }

    private record WaitingTransferFile(long telegramId, long chatId, String uniqueId) {
    }
}
