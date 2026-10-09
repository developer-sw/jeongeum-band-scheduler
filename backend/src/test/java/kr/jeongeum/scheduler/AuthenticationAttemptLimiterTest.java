package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.common.api.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class AuthenticationAttemptLimiterTest {
    @Test
    void loginFailuresExpireAndSuccessfulLoginResetsOnlyItsIdentifier() {
        MutableClock clock = new MutableClock();
        var limiter = new AuthenticationAttemptLimiter(clock);
        for (int attempt = 0; attempt < 10; attempt++) { limiter.failedLogin("member-one"); }
        ApiException blocked = assertThrows(ApiException.class, () -> limiter.beforeLogin("member-one"));
        assertEquals(429, blocked.status());
        assertDoesNotThrow(() -> limiter.beforeLogin("member-two"));
        clock.advance(Duration.ofMinutes(10));
        assertDoesNotThrow(() -> limiter.beforeLogin("member-one"));
        for (int attempt = 0; attempt < 9; attempt++) { limiter.failedLogin("member-one"); }
        limiter.successfulLogin("member-one");
        limiter.failedLogin("member-one");
        assertDoesNotThrow(() -> limiter.beforeLogin("member-one"));
    }

    @Test
    void invitationAndGlobalBudgetsRecoverWithoutUsingProxyAddresses() {
        MutableClock clock = new MutableClock();
        var limiter = new AuthenticationAttemptLimiter(clock);
        for (int attempt = 0; attempt < 60; attempt++) { limiter.beforeInvitation("synthetic-token"); }
        assertThrows(ApiException.class, () -> limiter.beforeInvitation("synthetic-token"));
        assertDoesNotThrow(() -> limiter.beforeInvitation("different-synthetic-token"));
        clock.advance(Duration.ofMinutes(10));
        assertDoesNotThrow(() -> limiter.beforeInvitation("synthetic-token"));

        var globalLimiter = new AuthenticationAttemptLimiter(clock);
        for (int attempt = 0; attempt < 600; attempt++) { globalLimiter.beforeLogin("member-" + attempt); }
        assertThrows(ApiException.class, () -> globalLimiter.beforeLogin("next-member"));
        clock.advance(Duration.ofMinutes(1));
        assertDoesNotThrow(() -> globalLimiter.beforeLogin("next-member"));
    }

    @Test
    void passwordHashWorkIsBoundedAndCapacityIsReturnedAfterCompletion() throws Exception {
        var limiter = new AuthenticationAttemptLimiter(new MutableClock());
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int worker = 0; worker < 4; worker++) {
                futures.add(workers.submit(() -> limiter.passwordWork(() -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted test computation");
                    }
                    return 1;
                })));
            }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertThrows(ApiException.class, () -> limiter.passwordWork(() -> 2));
            } finally { release.countDown(); }
            for (var future : futures) { assertEquals(1, future.get(5, TimeUnit.SECONDS)); }
            assertEquals(2, limiter.passwordWork(() -> 2));
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-09T00:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
