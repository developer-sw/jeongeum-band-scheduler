package kr.jeongeum.scheduler.recurring.api;

import java.time.LocalDate;
import java.util.List;

public record RecurringResponse(String id, String teamId, String teamName, String teamKind,
        String status, long version, String createdByUserId, LocalDate endDate,
        List<Revision> revisions, List<Occurrence> occurrences) {
    public record Revision(String id, long revisionNo, LocalDate effectiveDate, int weekday, String blockCode) {}
    public record Occurrence(String id, LocalDate weekStartDate, String revisionId, LocalDate originalScheduledDate,
            String originalBlockCode, String state, List<String> reservationIds) {}
    public record Conflict(String type, String assignmentId, String reservationId, LocalDate date,
            int weekday, String blockCode, String teamId, String teamName) {}
    public record PlannedOccurrence(LocalDate weekStartDate, LocalDate date, String blockCode,
            String state, boolean preserved) {}
    public record Preview(boolean canApply, List<Conflict> conflicts, List<PlannedOccurrence> plannedOccurrences) {}
}
