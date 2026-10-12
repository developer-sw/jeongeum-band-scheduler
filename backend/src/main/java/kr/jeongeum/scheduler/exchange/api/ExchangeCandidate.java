package kr.jeongeum.scheduler.exchange.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ExchangeCandidate(String id, long version, String subjectType, String purpose, String teamId,
        String teamName, List<String> instrumentCodes, String status, String phase, boolean isMine, boolean canCancel,
        boolean canChange, String originKind, boolean quotaExempt, String bundleKey,
        LocalDate date, String blockCode, Instant startsAt, Instant endsAt, int quotaMinutes, boolean dualAssetException) {}
