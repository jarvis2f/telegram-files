package telegram.files;

public enum EventEnum {

    /**
     * suffix = SettingRecord.key <br>
     * body = SettingRecord.value
     *
     * @see telegram.files.repository.SettingRecord
     */
    SETTING_UPDATE,

    /**
     * suffix = null <br>
     * body = SettingAutoRecords
     *
     * @see telegram.files.repository.SettingAutoRecords
     */
    AUTO_DOWNLOAD_UPDATE,

    /**
     * suffix = null <br>
     * body = JSONObject with "telegramId", "chatId", "messageId"
     */
    MESSAGE_RECEIVED,

    /**
     * suffix = null <br>
     * body = JSONObject with "telegramId", "payload"
     *
     * @see telegram.files.EventPayload
     */
    TELEGRAM_EVENT,

    /**
     * suffix = null <br>
     * body = JSONObject containing only fileRecordId, recordVersion and telegramId.
     * The event is emitted after the file state and share job are durably committed.
     */
    FILE_READY_FOR_SHARE,

    SHARE_DEVICE_AUTHORIZE,

    SHARE_DEVICE_STATUS,

    SHARE_DEVICE_CANCEL,

    SHARE_NODE_UNBIND,

    SHARE_NODE_RENAME,

    SHARE_RESOURCE_PUBLISH,

    SHARE_RESOURCE_LIST,

    SHARE_PUBLICATION_POLICY,

    SHARE_RESOURCE_UPDATE,

    SHARE_RESOURCE_REVOKE,

    /**
     * suffix = null <br>
     * body = JSONObject with "success", "message"
     */
    MAINTAIN,
    ;

    public String address() {
        return name();
    }

    public String address(String suffix) {
        return name() + "." + suffix;
    }
}
