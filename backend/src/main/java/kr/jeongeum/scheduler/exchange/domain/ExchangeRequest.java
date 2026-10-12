package kr.jeongeum.scheduler.exchange.domain;

import java.time.Instant;

public record ExchangeRequest(long id, long requesterUserId, String status, Instant expiresAt, Instant createdAt,
        Instant processedAt, Long processedByUserId, String stateReason, long version) {
    public boolean pending() { return "PENDING".equals(status); }
}
