package telegram.files;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import cn.hutool.log.dialect.jdk.JdkLog;
import com.openai.models.ChatModel;
import io.vertx.core.DeploymentOptions;
import io.vertx.core.ThreadingModel;
import telegram.files.share.ShareConfiguration;
import telegram.files.share.TorrentConfiguration;
import telegram.files.share.security.AesGcmSecretStore;
import telegram.files.share.security.SecretStore;
import telegram.files.security.RedactingFormatter;

import java.io.File;
import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.*;
import javax.crypto.spec.SecretKeySpec;

public class Config {
    public static final String LOG_LEVEL = StrUtil.blankToDefault(System.getenv("LOG_LEVEL"), "INFO");

    public static final String APP_ENV = StrUtil.blankToDefault(System.getenv("APP_ENV"), "prod");

    public static final String APP_ROOT = System.getenv("APP_ROOT");

    public static final String DB_TYPE = StrUtil.blankToDefault(System.getenv("DB_TYPE"), "sqlite");

    public static final String DB_HOST = System.getenv("DB_HOST");

    public static final int DB_PORT = Convert.toInt(System.getenv("DB_PORT"), 0);

    public static final String DB_USER = System.getenv("DB_USER");

    public static final String DB_PASSWORD = System.getenv("DB_PASSWORD");

    public static final String DB_NAME = System.getenv("DB_NAME");

    public static final boolean DB_NEED_CREATE = Convert.toBool(System.getenv("DB_NEED_CREATE"), false);

    public static final String LOG_PATH = APP_ROOT + File.separator + "logs";

    public static final String TELEGRAM_ROOT = APP_ROOT + File.separator + "account";

    public static final int TELEGRAM_API_ID = Convert.toInt(System.getenv("TELEGRAM_API_ID"), 0);

    public static final String TELEGRAM_API_HASH = System.getenv("TELEGRAM_API_HASH");

    public static final int TELEGRAM_LOG_LEVEL = Convert.toInt(System.getenv("TELEGRAM_LOG_LEVEL"), 0);

    public static final long HTTP_BODY_LIMIT_BYTES = Convert.toLong(
            System.getenv("HTTP_BODY_LIMIT_BYTES"),
            1024L * 1024L
    );

    public static final String HTTP_HOST = StrUtil.blankToDefault(
            System.getenv("HTTP_HOST"), "0.0.0.0"
    );

    public static final boolean HTTP_SECURE_COOKIES = Convert.toBool(
            System.getenv("HTTP_SECURE_COOKIES"),
            "prod".equals(APP_ENV)
    );

    public static final Set<String> HTTP_ALLOWED_ORIGINS = parseCsv(
            StrUtil.blankToDefault(
                    System.getenv("HTTP_ALLOWED_ORIGINS"),
                    "prod".equals(APP_ENV) ? "" : "http://localhost:3000,http://127.0.0.1:3000"
            )
    );

    public static final int AUTH_LOGIN_ATTEMPTS_PER_MINUTE = Convert.toInt(
            System.getenv("AUTH_LOGIN_ATTEMPTS_PER_MINUTE"), 10
    );

    public static final int FILE_READS_PER_MINUTE = Convert.toInt(
            System.getenv("FILE_READS_PER_MINUTE"), 120
    );

    public static final int SHARE_HEARTBEAT_INTERVAL_SECONDS = Convert.toInt(
            System.getenv("SHARE_HEARTBEAT_INTERVAL_SECONDS"), 30
    );

    public static final int PEER_LISTEN_PORT = Convert.toInt(
            System.getenv("PEER_LISTEN_PORT"), 51413
    );

    public static final int SHARE_STATISTICS_ROLLOUT_PERCENT = Convert.toInt(
            System.getenv("SHARE_STATISTICS_ROLLOUT_PERCENT"),
            "prod".equals(APP_ENV) ? 0 : 100
    );

    public static final String OPENAI_MODEL = StrUtil.blankToDefault(System.getenv("OPENAI_MODEL"), ChatModel.GPT_4O_MINI.asString());

    public static final DeploymentOptions VIRTUAL_THREAD_DEPLOYMENT_OPTIONS = new DeploymentOptions()
            .setThreadingModel(ThreadingModel.VIRTUAL_THREAD);

    private static Level logLevel;

    static {
        if (APP_ENV == null) {
            throw new RuntimeException("APP_ENV is not set");
        }
        if (APP_ROOT == null) {
            throw new RuntimeException("APP_ROOT is not set");
        }
        if (TELEGRAM_API_ID == 0) {
            throw new RuntimeException("TELEGRAM_API_ID is not set");
        }
        if (TELEGRAM_API_HASH == null) {
            throw new RuntimeException("TELEGRAM_API_HASH is not set");
        }
        if (HTTP_BODY_LIMIT_BYTES <= 0
            || AUTH_LOGIN_ATTEMPTS_PER_MINUTE <= 0
            || FILE_READS_PER_MINUTE <= 0) {
            throw new RuntimeException("HTTP limits must be positive");
        }
        if (SHARE_HEARTBEAT_INTERVAL_SECONDS < 5 || SHARE_HEARTBEAT_INTERVAL_SECONDS > 600
            || PEER_LISTEN_PORT < 1 || PEER_LISTEN_PORT > 65535) {
            throw new RuntimeException("Share heartbeat interval or Peer listen port is invalid");
        }
        if (SHARE_STATISTICS_ROLLOUT_PERCENT < 0 || SHARE_STATISTICS_ROLLOUT_PERCENT > 100) {
            throw new RuntimeException("SHARE_STATISTICS_ROLLOUT_PERCENT must be between 0 and 100");
        }

        if (!FileUtil.exist(APP_ROOT)) {
            FileUtil.mkdir(APP_ROOT);
        }
        if (!FileUtil.exist(TELEGRAM_ROOT)) {
            FileUtil.mkdir(TELEGRAM_ROOT);
        }
        if (!FileUtil.exist(LOG_PATH)) {
            FileUtil.mkdir(LOG_PATH);
        }

        initLogger();
    }

    public static boolean isProd() {
        return "prod".equals(APP_ENV);
    }

    public static ShareConfiguration shareConfiguration() {
        return ShareConfiguration.from(
                System.getenv(),
                Path.of(APP_ROOT),
                Path.of(TELEGRAM_ROOT)
        );
    }

    public static TorrentConfiguration torrentConfiguration(ShareConfiguration shareConfiguration) {
        return TorrentConfiguration.from(System.getenv(), shareConfiguration.sharedRoot());
    }

    public static SecretStore shareSecretStore() {
        String raw = System.getenv("SECRET_STORE_MASTER_KEY");
        if (StrUtil.isBlank(raw)) {
            throw new IllegalStateException("SECRET_STORE_MASTER_KEY is required when sharing is enabled");
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(raw);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("SECRET_STORE_MASTER_KEY must be valid Base64", exception);
        }
        if (key.length != 32) {
            throw new IllegalStateException("SECRET_STORE_MASTER_KEY must decode to 32 bytes");
        }
        return new AesGcmSecretStore(Map.of(1, new SecretKeySpec(key, "AES")), 1);
    }

    private static Set<String> parseCsv(String value) {
        if (StrUtil.isBlank(value)) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .collect(Collectors.toUnmodifiableSet());
    }

    public static void initLogger() {
        Logger rootLogger = Logger.getLogger("");
        rootLogger.setLevel(Level.INFO);

        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$s] %5$s %6$s%n");

        if (ArrayUtil.isNotEmpty(rootLogger.getHandlers())) {
            for (Handler handler : rootLogger.getHandlers()) {
                rootLogger.removeHandler(handler);
            }
        }
        IgnoreExceptionLogFilter brokenPipeFilter = new IgnoreExceptionLogFilter();
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.FINEST);
        consoleHandler.setFormatter(new RedactingFormatter());
        consoleHandler.setFilter(brokenPipeFilter);
        rootLogger.addHandler(consoleHandler);

        try {
            String logFilePattern = LOG_PATH + File.separator + "api.log";

            FileHandler fileHandler = new FileHandler(logFilePattern, 5000000, 3, true);
            fileHandler.setLevel(Level.FINEST);
            fileHandler.setFormatter(new RedactingFormatter());
            fileHandler.setFilter(brokenPipeFilter);
            rootLogger.addHandler(fileHandler);
        } catch (IOException e) {
            System.out.println("Failed to create log FileHandler: " + e.getMessage());
        }

        try {
            logLevel = Level.parse(Config.LOG_LEVEL);
            System.out.println("Setting telegram.files log level to " + logLevel);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid log level [" + Config.LOG_LEVEL + "], using default INFO.");
        }

        Logger nettyLogger = Logger.getLogger("io.netty");
        nettyLogger.setLevel(Level.WARNING);

        Logger.getLogger("telegram.files").setLevel(logLevel);
    }

    public static boolean isSqlite() {
        return Objects.equals(DB_TYPE, "sqlite");
    }

    public static boolean isPostgres() {
        return Objects.equals(DB_TYPE, "postgres");
    }

    public static boolean isMysql() {
        return Objects.equals(DB_TYPE, "mysql");
    }

    public static class JDKLogFactory extends LogFactory {

        public JDKLogFactory() {
            super("JDK Logging");
        }

        public Log createLog(String name) {
            return new JdkLog(name);
        }

        public Log createLog(Class<?> clazz) {
            return new JdkLog(clazz);
        }
    }

    public static class IgnoreExceptionLogFilter implements Filter {

        @Override
        public boolean isLoggable(LogRecord record) {
            Throwable t = record.getThrown();
            if (t instanceof IOException &&
                t.getMessage() != null &&
                (t.getMessage().contains("Broken pipe") ||
                 t.getMessage().contains("Connection reset by peer"))) {
                return false;
            }

            if (t instanceof ClosedChannelException) {
                return false;
            }

            return true;
        }
    }
}
