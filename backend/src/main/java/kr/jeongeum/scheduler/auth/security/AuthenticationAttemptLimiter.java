package kr.jeongeum.scheduler.auth.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.invitation.service.InvitationTokens;
import org.springframework.stereotype.Component;

/** Single-process protection. Keys are digests; proxy IP addresses and forwarding headers are unused. */
@Component
public class AuthenticationAttemptLimiter {
    private static final Duration WINDOW = Duration.ofMinutes(10);
    private final Map<String, Bucket> buckets = new HashMap<>();
    private final Semaphore passwordComputations = new Semaphore(4);
    private final Clock clock;
    private Instant globalStart;
    private int globalAttempts;

    public AuthenticationAttemptLimiter(Clock clock) { this.clock = clock; }

    public synchronized void beforeLogin(String loginId) {
        globalLimit();
        Bucket bucket = bucket("login:" + InvitationTokens.digest(loginId));
        if (bucket.count >= 10) { throw limited(); }
    }

    public synchronized void failedLogin(String loginId) {
        bucket("login:" + InvitationTokens.digest(loginId)).count++;
    }

    public synchronized void successfulLogin(String loginId) {
        buckets.remove("login:" + InvitationTokens.digest(loginId));
    }

    public synchronized void beforeInvitation(String token) {
        globalLimit();
        Bucket bucket = bucket("invitation:" + InvitationTokens.digest(token));
        if (bucket.count >= 60) { throw limited(); }
        bucket.count++;
    }

    public <T> T passwordWork(Supplier<T> operation) {
        if (!passwordComputations.tryAcquire()) { throw limited(); }
        try { return operation.get(); } finally { passwordComputations.release(); }
    }

    private void globalLimit() {
        Instant now = clock.instant();
        if (globalStart == null || !now.isBefore(globalStart.plusSeconds(60))) {
            globalStart = now;
            globalAttempts = 0;
        }
        if (++globalAttempts > 600) { throw limited(); }
    }

    private Bucket bucket(String key) {
        Instant now = clock.instant();
        buckets.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().start.plus(WINDOW)));
        Bucket existing = buckets.get(key);
        if (existing != null) { return existing; }
        if (buckets.size() >= 10_000) { throw limited(); }
        Bucket created = new Bucket(now);
        buckets.put(key, created);
        return created;
    }

    private static ApiException limited() {
        return new ApiException(429, "TOO_MANY_REQUESTS", "요청이 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static final class Bucket {
        final Instant start;
        int count;
        Bucket(Instant start) { this.start = start; }
    }
}
