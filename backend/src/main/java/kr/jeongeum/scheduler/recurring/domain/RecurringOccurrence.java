package kr.jeongeum.scheduler.recurring.domain;

import java.time.LocalDate;

public record RecurringOccurrence(long id, long assignmentId, LocalDate weekStartDate, long revisionId,
        LocalDate originalScheduledDate, String originalBlockCode, String state, long version) {
    public boolean exception() { return state.endsWith("_EXCEPTION"); }
}
