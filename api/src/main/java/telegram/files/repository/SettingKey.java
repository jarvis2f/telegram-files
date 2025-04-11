package telegram.files.repository;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.lang.Version;
import cn.hutool.core.util.StrUtil;
import io.vertx.core.json.JsonObject;

import java.util.function.Function;

public enum SettingKey {
    version(Version::new),
    uniqueOnly(Convert::toBool, false),
    imageLoadSize,
    alwaysHide(Convert::toBool, false),
    showSensitiveContent(Convert::toBool, false),
    autoDownload(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingAutoRecords.class)),
    /**
     * Auto download limit for each telegram account
     */
    autoDownloadLimit(Convert::toInt),
    autoDownloadTimeLimited(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingTimeLimitedDownload.class)),
    proxys(value -> StrUtil.isBlank(value) ? null : new JsonObject(value).mapTo(SettingProxyRecords.class)),
    /**
     * Interval for calculating average speed, in seconds
     */
    avgSpeedInterval(Convert::toInt, 5 * 60),
    tags(value -> StrUtil.isBlank(value) ? null : StrUtil.split(value, ","));

    public final Function<String, ?> converter;

    public final Object defaultValue;

    SettingKey() {
        this(Function.identity(), null);
    }

    SettingKey(Function<String, ?> converter) {
        this(converter, null);
    }

    SettingKey(Function<String, ?> converter, Object defaultValue) {
        this.converter = converter;
        this.defaultValue = defaultValue;
    }
}
