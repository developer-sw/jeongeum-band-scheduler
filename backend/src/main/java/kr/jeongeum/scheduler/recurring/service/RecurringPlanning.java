package kr.jeongeum.scheduler.recurring.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import kr.jeongeum.scheduler.recurring.api.RecurringResponse;
import kr.jeongeum.scheduler.recurring.domain.RecurringAssignment;
import kr.jeongeum.scheduler.recurring.domain.RecurringOccurrence;
import kr.jeongeum.scheduler.recurring.domain.RecurringRevision;
import kr.jeongeum.scheduler.recurring.repository.RecurringRepository;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.domain.Team;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.stereotype.Service;

@Service
public class RecurringPlanning {
    public record Series(RecurringAssignment assignment, List<RecurringRevision> revisions, List<RecurringOccurrence> occurrences) {}
    public record Plan(LocalDate week, RecurringRevision revision, LocalDate date, TimeBlock block) {}
    private final RecurringRepository recurring;
    private final ReservationRepository reservations;
    private final TeamRepository teams;
    public RecurringPlanning(RecurringRepository recurring, ReservationRepository reservations, TeamRepository teams) {
        this.recurring = recurring; this.reservations = reservations; this.teams = teams;
    }
    public List<Series> all() {
        return recurring.assignments().stream().map(assignment -> new Series(assignment, recurring.revisions(assignment.id()),
                recurring.occurrences(assignment.id()))).toList();
    }
    public static LocalDate week(LocalDate date) { return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); }
    public RecurringOccurrence occurrence(Series series, LocalDate week) {
        return series.occurrences().stream().filter(occurrence -> occurrence.weekStartDate().equals(week)).findFirst().orElse(null);
    }
    public List<Reservation> current(RecurringOccurrence occurrence) {
        if (occurrence == null) { return List.of(); }
        return recurring.reservationIds(occurrence.id(), true).stream().map(reservations::find)
                .flatMap(java.util.Optional::stream).toList();
    }
    public boolean preserved(RecurringOccurrence occurrence, Instant now) {
        return occurrence != null && (occurrence.exception() || "ENDED".equals(occurrence.state())
                || current(occurrence).stream().anyMatch(reservation -> !now.isBefore(reservation.startsAt())));
    }
    public Plan plan(Series series, LocalDate week, Instant now) {
        RecurringOccurrence occurrence = occurrence(series, week);
        if (preserved(occurrence, now)) {
            RecurringRevision revision = series.revisions().stream().filter(value -> value.id() == occurrence.revisionId()).findFirst().orElseThrow();
            return new Plan(week, revision, occurrence.originalScheduledDate(), TimeBlock.require(occurrence.originalBlockCode()));
        }
        return basePlan(series, week);
    }
    public static Plan basePlan(Series series, LocalDate week) {
        Plan selected = null;
        List<RecurringRevision> ordered = series.revisions().stream().sorted(Comparator.comparing(RecurringRevision::effectiveDate)
                .thenComparingLong(RecurringRevision::revisionNo)).toList();
        for (RecurringRevision revision : ordered) {
            LocalDate proposed = week.plusDays(revision.weekday() - 1L);
            if (proposed.isBefore(revision.effectiveDate())) { continue; }
            TimeBlock block = TimeBlock.require(revision.blockCode());
            // This decision was made when the revision was approved. A later restart must not revive an old pattern.
            if (selected != null && (selected.date().isBefore(revision.effectiveDate())
                    || !revision.createdAt().isBefore(selected.block().startsAt(selected.date()))
                    || !revision.createdAt().isBefore(block.startsAt(proposed)))) { continue; }
            selected = new Plan(week, revision, proposed, block);
        }
        return selected != null && series.assignment().endDate() != null && selected.date().isAfter(series.assignment().endDate()) ? null : selected;
    }
    /** A pattern changes only in its boundary week or the following week. Test one point in every constant interval. */
    public List<RecurringResponse.Conflict> patternConflicts(Series candidate, List<Series> others, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        List<RecurringResponse.Conflict> result = new ArrayList<>();
        for (Series other : others) {
            if (other.assignment().id() == candidate.assignment().id() || !activeTeam(other.assignment().teamId())) { continue; }
            TreeSet<LocalDate> critical = new TreeSet<>();
            critical.add(week(today)); critical.add(week(today).plusWeeks(1));
            for (Series value : List.of(candidate, other)) {
                for (RecurringRevision revision : value.revisions()) {
                    addBoundary(critical, revision.effectiveDate());
                }
                if (value.assignment().endDate() != null) { addBoundary(critical, value.assignment().endDate()); }
                for (RecurringOccurrence occurrence : value.occurrences()) { addBoundary(critical, occurrence.weekStartDate()); }
            }
            for (LocalDate point : critical) {
                if (point.plusDays(6).isBefore(today)) { continue; }
                Plan left = basePlan(candidate, point);
                Plan right = basePlan(other, point);
                if (left != null && right != null && left.date().equals(right.date()) && left.block() == right.block()
                        && !left.date().isBefore(today) && now.isBefore(left.block().endsAt(left.date()))) {
                    Team team = teams.findById(other.assignment().teamId()).orElseThrow();
                    result.add(new RecurringResponse.Conflict("PATTERN", Long.toString(other.assignment().id()), null,
                            left.date(), left.revision().weekday(), left.block().name(), Long.toString(team.id()), team.name()));
                    break;
                }
            }
        }
        return result;
    }
    private static void addBoundary(TreeSet<LocalDate> points, LocalDate date) {
        LocalDate week = week(date);
        points.add(week); points.add(week.plusWeeks(1));
        if (week.getYear() > 1) { points.add(week.minusWeeks(1)); }
    }
    public boolean activeTeam(long teamId) { return teams.findById(teamId).map(Team::active).orElse(false); }
    public static boolean sameLocation(Plan plan, Reservation reservation) {
        return plan != null && plan.date().equals(reservation.date()) && plan.block().name().equals(reservation.blockCode());
    }
    public static boolean sameLocation(Plan plan, RecurringOccurrence occurrence) {
        return plan != null && plan.date().equals(occurrence.originalScheduledDate())
                && Objects.equals(plan.block().name(), occurrence.originalBlockCode());
    }
}
