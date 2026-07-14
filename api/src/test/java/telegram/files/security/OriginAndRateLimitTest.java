package telegram.files.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OriginAndRateLimitTest {

    @Test
    void originAllowlistIsExactAndLoopbackDetectionIsStrict() {
        OriginPolicy policy = new OriginPolicy(List.of(
                "https://files.example.test",
                "http://localhost:3000"
        ));

        assertTrue(policy.isAllowed(null));
        assertTrue(policy.isAllowed("https://files.example.test"));
        assertFalse(policy.isAllowed("https://files.example.test.attacker.invalid"));
        assertFalse(policy.isAllowed("http://files.example.test"));
        assertTrue(OriginPolicy.isLoopback("127.0.0.1"));
        assertTrue(OriginPolicy.isLoopback("::1"));
        assertFalse(OriginPolicy.isLoopback("127.0.0.1.attacker.invalid"));
        assertTrue(OriginPolicy.isLocalNetwork("127.0.0.1"));
        assertTrue(OriginPolicy.isLocalNetwork("10.0.0.42"));
        assertTrue(OriginPolicy.isLocalNetwork("172.16.4.20"));
        assertTrue(OriginPolicy.isLocalNetwork("172.31.255.254"));
        assertTrue(OriginPolicy.isLocalNetwork("192.168.1.20"));
        assertTrue(OriginPolicy.isLocalNetwork("169.254.10.20"));
        assertTrue(OriginPolicy.isLocalNetwork("fd00::1234"));
        assertTrue(OriginPolicy.isLocalNetwork("fe80::1%en0"));
        assertFalse(OriginPolicy.isLocalNetwork("172.32.0.1"));
        assertFalse(OriginPolicy.isLocalNetwork("8.8.8.8"));
        assertFalse(OriginPolicy.isLocalNetwork("private.example.test"));
    }

    @Test
    void slidingWindowRejectsAndThenRecovers() {
        MutableClock clock = new MutableClock();
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(
                2, Duration.ofMinutes(1), clock
        );

        assertTrue(limiter.tryAcquire("owner"));
        assertTrue(limiter.tryAcquire("owner"));
        assertFalse(limiter.tryAcquire("owner"));
        assertEquals(60, limiter.retryAfterSeconds("owner"));

        clock.advance(Duration.ofMinutes(1));
        assertTrue(limiter.tryAcquire("owner"));
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.EPOCH;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
