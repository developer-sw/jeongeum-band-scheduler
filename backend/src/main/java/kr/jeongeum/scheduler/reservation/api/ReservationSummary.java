package kr.jeongeum.scheduler.reservation.api;

import java.util.List;

public record ReservationSummary(String id, long version, String subjectType, String purpose, String teamId,
        String teamName, List<String> instrumentCodes, String status, String phase, boolean isMine, boolean canCancel, boolean canChange,
        String originKind, boolean quotaExempt) {}
