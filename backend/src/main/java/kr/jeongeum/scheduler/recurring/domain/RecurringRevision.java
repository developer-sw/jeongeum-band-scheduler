package kr.jeongeum.scheduler.recurring.domain;

import java.time.LocalDate;
import java.time.Instant;

public record RecurringRevision(long id, long assignmentId, long revisionNo, LocalDate effectiveDate,
        int weekday, String blockCode, Instant createdAt) {}
