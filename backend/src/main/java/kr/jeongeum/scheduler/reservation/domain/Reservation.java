package kr.jeongeum.scheduler.reservation.domain;

import java.time.Instant;
import java.time.LocalDate;

public record Reservation(long id, String ownerType, String purpose, Long ownerUserId, Long teamId,
        long creatorUserId, LocalDate date, String blockCode, Instant startsAt, Instant endsAt, Instant usableFrom,
        String status, String originKind, boolean quotaExempt, boolean dualAssetException, long version,
        Instant createdAt, Instant canceledAt, String cancelReason, long createdOperationId, Long canceledOperationId) {}
