package telegram.files.security;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class SlidingWindowRateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, ArrayDeque<Long>> attempts = new HashMap<>();

    public SlidingWindowRateLimiter(int limit, Duration window) {
        this(limit, window, Clock.systemUTC());
    }

    SlidingWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1 || window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("Rate limit and window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = Objects.requireNonNull(clock);
    }

    public synchronized boolean tryAcquire(String rawKey) {
        String key = rawKey == null || rawKey.isBlank() ? "unknown" : rawKey;
        long now = clock.millis();
        ArrayDeque<Long> timestamps = attempts.computeIfAbsent(key, _ -> new ArrayDeque<>());
        evictExpired(timestamps, now);
        if (timestamps.size() >= limit) {
            return false;
        }
        timestamps.addLast(now);
        if (attempts.size() > 10_000) {
            attempts.entrySet().removeIf(entry -> {
                evictExpired(entry.getValue(), now);
                return entry.getValue().isEmpty();
            });
        }
        return true;
    }

    public synchronized long retryAfterSeconds(String rawKey) {
        String key = rawKey == null || rawKey.isBlank() ? "unknown" : rawKey;
        ArrayDeque<Long> timestamps = attempts.get(key);
        if (timestamps == null || timestamps.isEmpty()) {
            return 0;
        }
        long now = clock.millis();
        evictExpired(timestamps, now);
        if (timestamps.size() < limit) {
            return 0;
        }
        long remainingMillis = timestamps.getFirst() + windowMillis - now;
        return Math.max(1, (remainingMillis + 999) / 1_000);
    }

    private void evictExpired(ArrayDeque<Long> timestamps, long now) {
        long threshold = now - windowMillis;
        while (!timestamps.isEmpty() && timestamps.getFirst() <= threshold) {
            timestamps.removeFirst();
        }
    }
}
