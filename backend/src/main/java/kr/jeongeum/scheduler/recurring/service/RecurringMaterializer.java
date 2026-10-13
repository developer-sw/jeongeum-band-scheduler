package kr.jeongeum.scheduler.recurring.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.exchange.service.ExchangeNotificationTransitions;
import kr.jeongeum.scheduler.notification.service.NotificationPublisher;
import kr.jeongeum.scheduler.recurring.api.RecurringResponse;
import kr.jeongeum.scheduler.recurring.domain.RecurringOccurrence;
import kr.jeongeum.scheduler.recurring.repository.RecurringRepository;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecurringMaterializer {
    public record Action(RecurringOccurrence occurrence, RecurringPlanning.Plan plan, boolean preserved) {}
    private final RecurringRepository recurring;
    private final ReservationRepository reservations;
    private final RecurringPlanning planning;
    private final RecurringLocks locks;
    private final DirectoryMutationGuard guard;
    private final TeamRepository teams;
    private final Clock clock;
    private final NotificationPublisher notifications;
    private final ExchangeNotificationTransitions exchangeNotifications;
    public RecurringMaterializer(RecurringRepository recurring, ReservationRepository reservations, RecurringPlanning planning,
            RecurringLocks locks, DirectoryMutationGuard guard, TeamRepository teams, Clock clock,
            NotificationPublisher notifications, ExchangeNotificationTransitions exchangeNotifications) {
        this.recurring = recurring; this.reservations = reservations; this.planning = planning; this.locks = locks;
        this.guard = guard; this.teams = teams; this.clock = clock;
        this.notifications = notifications; this.exchangeNotifications = exchangeNotifications;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public void openDate(LocalDate date) {
        if (reservations.prepared(date)) { return; }
        guard.lock();
        Instant now = now();
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        if (date.isBefore(today) || date.isAfter(today.plusDays(14))) { return; }
        List<RecurringPlanning.Series> all = planning.all();
        List<Instrument> instruments = locks.subjects(all, null, null);
        recurring.lockPattern(false);
        reservations.ensureAndLockDay(date, now);
        if (reservations.prepared(date)) { return; }
        now = now();
        today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        if (date.isBefore(today) || date.isAfter(today.plusDays(14))) { return; }
        List<RecurringPlanning.Series> selected = all.stream().filter(series -> planning.activeTeam(series.assignment().teamId())).toList();
        for (var series : selected) {
            RecurringPlanning.Plan plan = planning.plan(series, RecurringPlanning.week(date), now);
            if (plan == null || !plan.date().equals(date)) { continue; }
            RecurringOccurrence occurrence = planning.occurrence(series, plan.week());
            if (occurrence != null && (planning.preserved(occurrence, now)
                    || "MATERIALIZED".equals(occurrence.state()))) { continue; }
            if (!now.isBefore(plan.block().endsAt(date))) {
                saveOccurrence(series, occurrence, plan, "ENDED", now);
                continue;
            }
            List<RecurringResponse.Conflict> conflicts = actualConflicts(List.of(new Action(occurrence, plan, false)), now);
            if (!conflicts.isEmpty()) { throw new AssignmentConflictException(conflicts); }
            materialize(series, occurrence, plan, instruments, now);
        }
        reservations.openDay(date, now);
    }
    public List<Action> actions(RecurringPlanning.Series series, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        TreeSet<LocalDate> weeks = new TreeSet<>();
        for (LocalDate week = RecurringPlanning.week(today); !week.isAfter(RecurringPlanning.week(today.plusDays(14))); week = week.plusWeeks(1)) {
            weeks.add(week);
        }
        for (RecurringOccurrence occurrence : series.occurrences()) {
            if (!occurrence.weekStartDate().plusDays(6).isBefore(today)
                    || planning.current(occurrence).stream().anyMatch(reservation -> now.isBefore(reservation.endsAt()))) {
                weeks.add(occurrence.weekStartDate());
            }
        }
        List<Action> result = new ArrayList<>();
        for (LocalDate week : weeks) {
            RecurringOccurrence occurrence = planning.occurrence(series, week);
            RecurringPlanning.Plan plan = planning.plan(series, week, now);
            if (plan == null && occurrence == null) { continue; }
            if (plan != null && plan.date().isBefore(today) && occurrence == null) { continue; }
            if (plan != null && plan.date().isAfter(today.plusDays(14)) && occurrence == null) { continue; }
            result.add(new Action(occurrence, plan, planning.preserved(occurrence, now)));
        }
        return result;
    }
    public Set<LocalDate> dates(List<Action> actions) {
        Set<LocalDate> dates = new TreeSet<>();
        for (Action action : actions) {
            if (action.plan() != null) { dates.add(action.plan().date()); }
            if (action.occurrence() != null) {
                for (Reservation reservation : planning.current(action.occurrence())) { dates.add(reservation.date()); }
            }
        }
        return dates;
    }
    public Set<Long> reservationIds(List<Action> actions) {
        Set<Long> ids = new TreeSet<>();
        for (Action action : actions) {
            if (action.occurrence() != null) { ids.addAll(recurring.reservationIds(action.occurrence().id(), true)); }
        }
        return ids;
    }
    public List<RecurringResponse.Conflict> actualConflicts(List<Action> actions, Instant now) {
        Set<Long> released = new HashSet<>();
        for (Action action : actions) {
            if (!action.preserved()) {
                for (Reservation current : planning.current(action.occurrence())) {
                    if (!RecurringPlanning.sameLocation(action.plan(), current) && "CONFIRMED".equals(current.status())
                            && "FIXED".equals(current.originKind()) && now.isBefore(current.startsAt())) { released.add(current.id()); }
                }
            }
        }
        List<RecurringResponse.Conflict> result = new ArrayList<>();
        for (Action action : actions) {
            RecurringPlanning.Plan plan = action.plan();
            if (action.preserved() || plan == null || !now.isBefore(plan.block().endsAt(plan.date()))) { continue; }
            Set<Long> unchanged = new HashSet<>();
            for (Reservation current : planning.current(action.occurrence())) {
                if (RecurringPlanning.sameLocation(plan, current)) { unchanged.add(current.id()); }
            }
            for (Reservation existing : reservations.between(plan.date(), plan.date())) {
                if ("CONFIRMED".equals(existing.status()) && existing.blockCode().equals(plan.block().name())
                        && !released.contains(existing.id()) && !unchanged.contains(existing.id())) {
                    String teamName = existing.teamId() == null ? null : teams.findById(existing.teamId()).map(team -> team.name()).orElse(null);
                    result.add(new RecurringResponse.Conflict("RESERVATION", null, Long.toString(existing.id()), plan.date(),
                            plan.date().getDayOfWeek().getValue(), plan.block().name(),
                            existing.teamId() == null ? null : Long.toString(existing.teamId()), teamName));
                }
            }
        }
        return result;
    }
    /** Called only after all dates and reservation rows have been locked and the final conflicts checked. */
    public void apply(RecurringPlanning.Series series, List<Action> actions, List<Instrument> instruments,
            long actorId, long operationId, Instant now) {
        // Release every old location before allocating any new location, in the same transaction.
        List<Long> pendingExchanges = exchangeNotifications.pendingForReservations(new ArrayList<>(reservationIds(actions)));
        for (Action action : actions) {
            if (action.preserved()) { continue; }
            for (Reservation current : planning.current(action.occurrence())) {
                if (!RecurringPlanning.sameLocation(action.plan(), current) && "CONFIRMED".equals(current.status())
                        && "FIXED".equals(current.originKind()) && now.isBefore(current.startsAt())) {
                    reservations.cancel(current.id(), actorId, operationId, now, "관리자 고정 시간표 변경 또는 종료");
                    notifications.reservationCancelled(reservations.find(current.id()).orElseThrow(), operationId, now);
                    recurring.replaceLinks(action.occurrence().id());
                }
            }
        }
        exchangeNotifications.invalidated(pendingExchanges, now);
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        for (Action action : actions) {
            if (action.preserved()) { continue; }
            RecurringOccurrence occurrence = action.occurrence();
            RecurringPlanning.Plan plan = action.plan();
            if (plan == null) {
                if (occurrence != null && !"ENDED".equals(occurrence.state())) { recurring.state(occurrence.id(), "ENDED", now); }
                continue;
            }
            boolean unchanged = planning.current(occurrence).stream().anyMatch(reservation -> "CONFIRMED".equals(reservation.status())
                    && RecurringPlanning.sameLocation(plan, reservation));
            if (unchanged) { continue; }
            if (!now.isBefore(plan.block().endsAt(plan.date()))) { saveOccurrence(series, occurrence, plan, "ENDED", now); }
            else if (plan.date().isBefore(today) || plan.date().isAfter(today.plusDays(14))) {
                saveOccurrence(series, occurrence, plan, "PLANNED", now);
            } else { materialize(series, occurrence, plan, instruments, now); }
        }
    }
    private void materialize(RecurringPlanning.Series series, RecurringOccurrence occurrence, RecurringPlanning.Plan plan,
            List<Instrument> instruments, Instant now) {
        if (!planning.activeTeam(series.assignment().teamId())) {
            saveOccurrence(series, occurrence, plan, "ENDED", now);
            return;
        }
        if (instruments.size() != 6) { throw new IllegalStateException("The fixed instrument set is incomplete"); }
        RecurringOccurrence stored = saveOccurrence(series, occurrence, plan, "MATERIALIZED", now);
        long operationId = reservations.systemOperation("FIXED_GENERATE", now);
        var assignment = series.assignment();
        String purpose = teams.findById(assignment.teamId()).orElseThrow().kind();
        Reservation created = reservations.insertFixed(purpose, assignment.teamId(), assignment.createdByUserId(),
                plan.date(), plan.block(), now, operationId);
        for (Instrument instrument : instruments) { reservations.claim(created, instrument, now); }
        reservations.quota(created, operationId, 0, "RESERVE", now);
        recurring.link(stored.id(), created.id());
        notifications.reservationCreated(created, operationId, now);
    }
    private RecurringOccurrence saveOccurrence(RecurringPlanning.Series series, RecurringOccurrence occurrence,
            RecurringPlanning.Plan plan, String state, Instant now) {
        if (occurrence == null) {
            return recurring.insertOccurrence(series.assignment().id(), plan.week(), plan.revision().id(), plan.date(), plan.block().name(), state, now);
        }
        recurring.planOccurrence(occurrence.id(), plan.revision().id(), plan.date(), plan.block().name(), state, now);
        return recurring.occurrence(series.assignment().id(), plan.week()).orElseThrow();
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
