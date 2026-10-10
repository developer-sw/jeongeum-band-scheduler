package kr.jeongeum.scheduler.recurring.domain;

import java.time.Instant;
import java.time.LocalDate;

public record RecurringAssignment(long id, long teamId, long createdByUserId, String status,
        LocalDate endDate, long version, Instant createdAt, Instant updatedAt) {}
