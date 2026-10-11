package kr.jeongeum.scheduler.reservation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ReservationResponse(String id, long version, String subjectType, String purpose, String teamId,
        String teamName, List<String> instrumentCodes, String status, String phase, boolean isMine, boolean canCancel, boolean canChange,
        String ownerUserId, String creatorUserId, LocalDate date, String blockCode, Instant startsAt, Instant endsAt,
        Instant usableFrom, int quotaMinutes, boolean quotaExempt, boolean dualAssetException, String originKind, List<String> teamFaultWarnings,
        Instant canceledAt, String cancelReason, List<String> successorIds) {}
