package telegram.files;

import cn.hutool.log.Log;
import cn.hutool.log.LogFactory;
import io.vertx.core.Future;

import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;

public class MessyUtils {

    private static final Log log = LogFactory.get();

    public static LocalDateTime withGrouping5Minutes(LocalDateTime time) {
        int minute = time.getMinute();
        int minuteGroup = minute / 5;
        int newMinute = minuteGroup * 5;
        return time.withMinute(newMinute).withSecond(0).withNano(0);
    }

    public static String calculateFileMD5(File file) {
        try (FileInputStream input = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[64 * 1024];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }

            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            log.warn(e, "Failed to calculate MD5 for file {}", file);
            return null;
        }
    }

    public static boolean compareFilesMD5(File file1, File file2) {
        CompletableFuture<String> md5Task1 = CompletableFuture.supplyAsync(() -> {
            try {
                return calculateFileMD5(file1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        CompletableFuture<String> md5Task2 = CompletableFuture.supplyAsync(() -> {
            try {
                return calculateFileMD5(file2);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        String md5File1 = null;
        String md5File2 = null;
        try {
            md5File1 = md5Task1.join();
            md5File2 = md5Task2.join();
        } catch (Exception ignore) {
            return false;
        }

        if (md5File1 == null || md5File2 == null) {
            return false;
        }

        return md5File1.equals(md5File2);
    }

    public static <T> T await(Future<T> future) {
        CompletableFuture<T> completableFuture = new CompletableFuture<>();
        future.onComplete(result -> {
            if (result.succeeded()) {
                completableFuture.complete(result.result());
            } else {
                completableFuture.completeExceptionally(result.cause());
            }
        });
        return completableFuture.join();
    }

    public static long convertToByte(long value, String unit) {
        return switch (unit) {
            case "B" -> value;
            case "KB" -> value * 1024;
            case "MB" -> value * 1024 * 1024;
            case "GB" -> value * 1024 * 1024 * 1024;
            default -> throw new IllegalArgumentException("Unknown unit: " + unit);
        };
    }

    public static class BitState {
        private int state;

        public BitState(int state) {
            this.state = state;
        }

        /**
         * 开启某个状态
         */
        public void enableState(int n) {
            state |= (1 << n);
        }

        /**
         * 关闭某个状态
         */
        public void disableState(int n) {
            state &= ~(1 << n);
        }

        /**
         * 切换某个状态（开启->关闭，关闭->开启）
         */
        public void toggleState(int n) {
            state ^= (1 << n);
        }

        /**
         * 检查某个状态是否开启
         */
        public boolean isStateEnabled(int n) {
            return (state & (1 << n)) != 0;
        }

        public int getState() {
            return state;
        }

        /**
         * 获取当前状态的二进制表示
         */
        public String getBinaryState() {
            return String.format("%8s", Integer.toBinaryString(state)).replace(' ', '0');
        }
    }
}
