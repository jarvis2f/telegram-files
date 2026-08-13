package telegram.files;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;
import io.vertx.core.json.Json;
import telegram.files.repository.SettingAutoRecords;
import telegram.files.repository.SettingKey;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class AutomationsHolder {
    private final Log log = LogFactory.get();

    private final SettingAutoRecords AUTO_RECORDS = new SettingAutoRecords();

    private final List<Consumer<List<SettingAutoRecords.Automation>>> onRemoveListeners = new ArrayList<>();

    private volatile boolean initialized = false;

    public static final AutomationsHolder INSTANCE = new AutomationsHolder();

    private AutomationsHolder() {
    }

    public SettingAutoRecords autoRecords() {
        return AUTO_RECORDS;
    }

    public void registerOnRemoveListener(Consumer<List<SettingAutoRecords.Automation>> onRemove) {
        onRemoveListeners.add(onRemove);
    }

    public synchronized Future<Void> init() {
        if (initialized) {
            return Future.succeededFuture();
        }
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .onSuccess(settingAutoRecords -> {
                    initialized = true;
                    if (settingAutoRecords == null) {
                        return;
                    }
                    settingAutoRecords.automations.forEach(item -> TelegramVerticles.get(item.telegramId)
                            .ifPresentOrElse(_ -> AUTO_RECORDS.add(item),
                                    () -> log.warn("Init auto records fail. Telegram verticle not found: %s".formatted(item.telegramId))));
                    TelegramVerticles.refreshIdlePolicies();
                })
                .onFailure(e -> log.error("Init auto records failed!", e))
                .mapEmpty();
    }

    public void onAutoRecordsUpdate(SettingAutoRecords records) {
        for (SettingAutoRecords.Automation automation : records.automations) {
            if (!AUTO_RECORDS.exists(automation.telegramId, automation.chatId)) {
                // new enabled
                TelegramVerticles.get(automation.telegramId)
                        .ifPresentOrElse(telegramVerticle -> {
                            if (telegramVerticle.authorized) {
                                AUTO_RECORDS.add(automation);
                                log.info("Add auto records success: %s".formatted(automation.uniqueKey()));
                            } else {
                                log.warn("Add auto records fail. Telegram verticle not authorized: %s".formatted(automation.telegramId));
                            }
                        }, () -> log.warn("Add auto records fail. Telegram verticle not found: %s".formatted(automation.telegramId)));
            } else {
                // update fields
                SettingAutoRecords.Automation theAutomation = AUTO_RECORDS.getItem(automation.telegramId, automation.chatId);
                theAutomation.preload.with(automation.preload);
                theAutomation.download.with(automation.download);
                theAutomation.transfer.with(automation.transfer);
                log.info("Update auto records success: %s".formatted(automation.uniqueKey()));
            }
        }
        // remove disabled
        List<SettingAutoRecords.Automation> removedItems = new ArrayList<>();
        AUTO_RECORDS.automations.removeIf(item -> {
            if (records.exists(item.telegramId, item.chatId)) {
                return false;
            }
            removedItems.add(item);
            log.info("Remove auto records success: %s".formatted(item.uniqueKey()));
            return true;
        });
        if (CollUtil.isNotEmpty(removedItems)) {
            onRemoveListeners.forEach(listener -> listener.accept(removedItems));
        }
        TelegramVerticles.refreshIdlePolicies();
    }

    public Future<Void> saveAutoRecords() {
        return DataVerticle.settingRepository.<SettingAutoRecords>getByKey(SettingKey.automation)
                .compose(settingAutoRecords -> {
                    if (settingAutoRecords == null) {
                        settingAutoRecords = new SettingAutoRecords();
                    }
                    AUTO_RECORDS.automations.forEach(settingAutoRecords::add);
                    return DataVerticle.settingRepository.createOrUpdate(SettingKey.automation.name(), Json.encode(settingAutoRecords));
                })
                .onFailure(e -> log.error("Save auto records failed!", e))
                .mapEmpty();
    }
}
