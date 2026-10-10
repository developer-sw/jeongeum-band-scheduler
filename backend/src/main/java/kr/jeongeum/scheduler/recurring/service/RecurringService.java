package kr.jeongeum.scheduler.recurring.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.recurring.api.RecurringRequests;
import kr.jeongeum.scheduler.recurring.api.RecurringResponse;
import kr.jeongeum.scheduler.recurring.domain.RecurringAssignment;
import kr.jeongeum.scheduler.recurring.domain.RecurringRevision;
import kr.jeongeum.scheduler.recurring.repository.RecurringRepository;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.domain.Team;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RecurringService {
    public record Result(RecurringResponse assignment, int status) {}
    private record Pattern(long teamId, int weekday, TimeBlock block, LocalDate effective) {}
    private final RecurringRepository recurring;
    private final ReservationRepository reservations;
    private final TeamRepository teams;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final RecurringPlanning planning;
    private final RecurringLocks locks;
    private final RecurringMaterializer materializer;
    private final RecurringOpeningService opening;
    private final Clock clock;
    private final TransactionTemplate writes;
    public RecurringService(RecurringRepository recurring, ReservationRepository reservations, TeamRepository teams,
            MemberAccess access, DirectoryMutationGuard guard, RecurringPlanning planning, RecurringLocks locks,
            RecurringMaterializer materializer, RecurringOpeningService opening, Clock clock, PlatformTransactionManager transactionManager) {
        this.recurring = recurring; this.reservations = reservations; this.teams = teams; this.access = access;
        this.guard = guard; this.planning = planning; this.locks = locks; this.materializer = materializer;
        this.opening = opening; this.clock = clock; this.writes = new TransactionTemplate(transactionManager); this.writes.setTimeout(10);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<RecurringResponse> list(long actorId, String fromInput, String toInput) {
        access.administrator(actorId);
        LocalDate today = today(now());
        LocalDate from = fromInput == null ? today : RecurringInputs.date(fromInput);
        LocalDate to = toInput == null ? today.plusDays(14) : RecurringInputs.date(toInput);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 31) { throw ApiException.invalidInput(); }
        return recurring.assignments().stream().map(assignment -> detail(assignment, from, to)).toList();
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public RecurringResponse.Preview preview(long actorId, RecurringRequests.Preview input) {
        access.administrator(actorId);
        Long assignmentId = DirectoryInputs.optionalId(input.assignmentId());
        RecurringAssignment existing = assignmentId == null ? null : recurring.find(assignmentId).orElseThrow(DirectoryInputs::notFound);
        if (existing != null) {
            DirectoryInputs.sameVersion(existing.version(), DirectoryInputs.version(input.expectedVersion()));
            requireActive(existing);
        } else if (input.expectedVersion() != null) { throw ApiException.invalidInput(); }
        long teamId = DirectoryInputs.id(input.teamId());
        if (existing != null && existing.teamId() != teamId) { throw ApiException.invalidInput(); }
        Pattern pattern = pattern(teamId, input.weekday(), input.blockCode(), input.effectiveDate());
        requireEffective(pattern.effective(), now());
        requireTeam(teamId);
        List<RecurringPlanning.Series> all = planning.all();
        var candidate = proposed(existing, pattern, actorId, now());
        List<RecurringMaterializer.Action> actions = materializer.actions(candidate, now());
        List<RecurringResponse.Conflict> conflicts = new ArrayList<>(planning.patternConflicts(candidate, all, now()));
        conflicts.addAll(materializer.actualConflicts(actions, now()));
        List<RecurringResponse.PlannedOccurrence> plans = actions.stream().filter(action -> action.plan() != null).map(action -> {
            var plan = action.plan();
            String state = action.preserved() ? action.occurrence().state()
                    : now().isBefore(plan.block().endsAt(plan.date())) ? "PLANNED" : "ENDED";
            return new RecurringResponse.PlannedOccurrence(plan.week(), plan.date(), plan.block().name(), state, action.preserved());
        }).toList();
        return new RecurringResponse.Preview(conflicts.isEmpty(), conflicts, plans);
    }
    public Result create(long actorId, String key, RecurringRequests.Create input) {
        String digest = RecurringInputs.key(key);
        Pattern pattern = pattern(DirectoryInputs.id(input.teamId()), input.weekday(), input.blockCode(), input.effectiveDate());
        String route = "/api/v1/recurring-assignments";
        String fingerprint = fingerprint("POST", route, null, pattern, null);
        access.administrator(actorId);
        opening.openBookableWindow();
        return writes.execute(status -> writePattern(actorId, null, null, pattern, digest, fingerprint, "POST", route));
    }
    public Result update(long actorId, long assignmentId, String key, RecurringRequests.Update input) {
        access.administrator(actorId);
        String digest = RecurringInputs.key(key);
        long expectedVersion = DirectoryInputs.version(input.expectedVersion());
        RecurringAssignment initial = recurring.find(assignmentId).orElseThrow(DirectoryInputs::notFound);
        Pattern pattern = pattern(initial.teamId(), input.weekday(), input.blockCode(), input.effectiveDate());
        String route = "/api/v1/recurring-assignments/" + assignmentId;
        String fingerprint = fingerprint("PATCH", route, expectedVersion, pattern, null);
        opening.openBookableWindow();
        return writes.execute(status -> writePattern(actorId, assignmentId, expectedVersion, pattern, digest, fingerprint, "PATCH", route));
    }
    private Result writePattern(long actorId, Long assignmentId, Long expectedVersion, Pattern pattern, String digest,
            String fingerprint, String method, String route) {
        guard.lock();
        access.administrator(actorId);
        var replay = recurring.replay(actorId, digest).orElse(null);
        if (replay != null) { return replay(replay, fingerprint); }
        RecurringAssignment existing = assignmentId == null ? null : recurring.find(assignmentId).orElseThrow(DirectoryInputs::notFound);
        if (existing != null) { DirectoryInputs.sameVersion(existing.version(), expectedVersion); requireActive(existing); }
        List<RecurringPlanning.Series> all = planning.all();
        var instruments = locks.subjects(all, actorId, pattern.teamId());
        access.administrator(actorId);
        requireTeam(pattern.teamId());
        recurring.lockPattern(true);
        Instant initialNow = now();
        requireEffective(pattern.effective(), initialNow);
        var candidate = proposed(existing, pattern, actorId, initialNow);
        List<RecurringMaterializer.Action> actions = materializer.actions(candidate, initialNow);
        Set<LocalDate> dates = materializer.dates(actions);
        locks.dates(dates, initialNow);
        locks.reservations(materializer.reservationIds(actions));
        Instant now = now();
        requireEffective(pattern.effective(), now);
        candidate = proposed(existing, pattern, actorId, now);
        actions = materializer.actions(candidate, now);
        requireLockedDates(dates, materializer.dates(actions));
        List<RecurringResponse.Conflict> conflicts = new ArrayList<>(planning.patternConflicts(candidate, all, now));
        conflicts.addAll(materializer.actualConflicts(actions, now));
        if (!conflicts.isEmpty()) { throw new AssignmentConflictException(conflicts); }
        long operationId = reservations.operation("SERIES_UPDATE", actorId, now, "관리자 고정 배정 설정");
        RecurringAssignment stored = existing == null ? recurring.insert(pattern.teamId(), actorId, now) : existing;
        if (existing != null) { recurring.update(stored.id(), now); }
        long revisionNo = candidate.revisions().stream().mapToLong(RecurringRevision::revisionNo).max().orElseThrow();
        recurring.revise(stored.id(), revisionNo, pattern.effective(), pattern.weekday(), pattern.block().name(), actorId, now);
        candidate = new RecurringPlanning.Series(recurring.find(stored.id()).orElseThrow(), recurring.revisions(stored.id()),
                recurring.occurrences(stored.id()));
        actions = materializer.actions(candidate, now);
        materializer.apply(candidate, actions, instruments, actorId, operationId, now);
        recurring.patternChanged();
        int responseStatus = existing == null ? 201 : 200;
        recurring.record(actorId, digest, fingerprint, method, route, responseStatus, stored.id(), operationId, now);
        return new Result(detail(recurring.find(stored.id()).orElseThrow(), today(now), today(now).plusDays(14)), responseStatus);
    }
    public Result end(long actorId, long assignmentId, String key, RecurringRequests.End input) {
        String digest = RecurringInputs.key(key);
        long expectedVersion = DirectoryInputs.version(input.expectedVersion());
        LocalDate endDate = RecurringInputs.date(input.endDate());
        String route = "/api/v1/recurring-assignments/" + assignmentId + "/end";
        String fingerprint = fingerprint("POST", route, expectedVersion, null, endDate);
        access.administrator(actorId);
        opening.openBookableWindow();
        return writes.execute(status -> {
            guard.lock();
            access.administrator(actorId);
            var replay = recurring.replay(actorId, digest).orElse(null);
            if (replay != null) { return replay(replay, fingerprint); }
            RecurringAssignment existing = recurring.find(assignmentId).orElseThrow(DirectoryInputs::notFound);
            DirectoryInputs.sameVersion(existing.version(), expectedVersion);
            if (existing.endDate() != null && endDate.isAfter(existing.endDate())) { throw ended(); }
            List<RecurringPlanning.Series> all = planning.all();
            var instruments = locks.subjects(all, actorId, existing.teamId());
            access.administrator(actorId);
            recurring.lockPattern(true);
            Instant initialNow = now();
            requireEffective(endDate, initialNow);
            var ended = new RecurringAssignment(existing.id(), existing.teamId(), existing.createdByUserId(), "ENDED", endDate,
                    existing.version(), existing.createdAt(), initialNow);
            var candidate = new RecurringPlanning.Series(ended, recurring.revisions(assignmentId), recurring.occurrences(assignmentId));
            var actions = materializer.actions(candidate, initialNow);
            Set<LocalDate> dates = materializer.dates(actions);
            locks.dates(dates, initialNow);
            locks.reservations(materializer.reservationIds(actions));
            Instant now = now();
            requireEffective(endDate, now);
            actions = materializer.actions(candidate, now);
            requireLockedDates(dates, materializer.dates(actions));
            long operationId = reservations.operation("SERIES_UPDATE", actorId, now, "관리자 고정 배정 종료");
            recurring.end(assignmentId, endDate, now);
            materializer.apply(candidate, actions, instruments, actorId, operationId, now);
            recurring.patternChanged();
            recurring.record(actorId, digest, fingerprint, "POST", route, 200, assignmentId, operationId, now);
            return new Result(detail(recurring.find(assignmentId).orElseThrow(), today(now), today(now).plusDays(14)), 200);
        });
    }
    private RecurringPlanning.Series proposed(RecurringAssignment existing, Pattern pattern, long actorId, Instant now) {
        var assignment = existing == null ? new RecurringAssignment(-1, pattern.teamId(), actorId, "ACTIVE", null, 0, now, now) : existing;
        List<RecurringRevision> revisions = new ArrayList<>(existing == null ? List.of() : recurring.revisions(existing.id()));
        long next = revisions.stream().mapToLong(RecurringRevision::revisionNo).max().orElse(0) + 1;
        revisions.add(new RecurringRevision(-1, assignment.id(), next, pattern.effective(), pattern.weekday(), pattern.block().name(), now));
        return new RecurringPlanning.Series(assignment, revisions, existing == null ? List.of() : recurring.occurrences(existing.id()));
    }
    private Result replay(RecurringRepository.Idempotency replay, String fingerprint) {
        if (!replay.fingerprint().equals(fingerprint)) {
            throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "같은 재시도 키를 다른 요청에 사용할 수 없습니다.");
        }
        LocalDate today = today(now());
        return new Result(detail(recurring.find(replay.assignmentId()).orElseThrow(DirectoryInputs::notFound), today, today.plusDays(14)), replay.status());
    }
    private RecurringResponse detail(RecurringAssignment assignment, LocalDate from, LocalDate to) {
        Team team = teams.findById(assignment.teamId()).orElseThrow(DirectoryInputs::notFound);
        var revisions = recurring.revisions(assignment.id()).stream().map(revision -> new RecurringResponse.Revision(
                Long.toString(revision.id()), revision.revisionNo(), revision.effectiveDate(), revision.weekday(), revision.blockCode())).toList();
        var occurrences = recurring.occurrences(assignment.id()).stream()
                .filter(occurrence -> !occurrence.weekStartDate().isBefore(RecurringPlanning.week(from))
                        && !occurrence.weekStartDate().isAfter(RecurringPlanning.week(to)))
                .map(occurrence -> new RecurringResponse.Occurrence(Long.toString(occurrence.id()), occurrence.weekStartDate(),
                        Long.toString(occurrence.revisionId()), occurrence.originalScheduledDate(), occurrence.originalBlockCode(), occurrence.state(),
                        recurring.reservationIds(occurrence.id(), true).stream().map(Object::toString).toList())).toList();
        return new RecurringResponse(Long.toString(assignment.id()), Long.toString(team.id()), team.name(), team.kind(),
                assignment.status(), assignment.version(), Long.toString(assignment.createdByUserId()), assignment.endDate(), revisions, occurrences);
    }
    private Pattern pattern(long teamId, Integer weekday, String block, String effective) {
        return new Pattern(teamId, RecurringInputs.weekday(weekday), TimeBlock.require(block), RecurringInputs.date(effective));
    }
    private void requireTeam(long id) {
        Team team = teams.findById(id).orElseThrow(DirectoryInputs::notFound);
        if (!team.active()) { throw DirectoryInputs.notFound(); }
    }
    private static void requireEffective(LocalDate date, Instant now) {
        if (date.isBefore(today(now))) { throw ApiException.invalidInput(); }
    }
    private static void requireActive(RecurringAssignment assignment) { if (!"ACTIVE".equals(assignment.status())) { throw ended(); } }
    private static ApiException ended() { return new ApiException(409, "ASSIGNMENT_ENDED", "종료가 설정된 배정은 되돌릴 수 없습니다. 필요한 일정은 새 배정으로 등록해 주세요."); }
    private static void requireLockedDates(Set<LocalDate> locked, Set<LocalDate> required) {
        if (!locked.containsAll(required)) { throw new ApiException(503, "RESERVATION_TEMPORARILY_UNAVAILABLE", "날짜가 바뀌었습니다. 같은 요청으로 다시 시도해 주세요."); }
    }
    private static String fingerprint(String method, String route, Long version, Pattern pattern, LocalDate endDate) {
        return RecurringInputs.digest(method + "|" + route + "|" + version + "|" + (pattern == null ? endDate
                : pattern.teamId() + "|" + pattern.weekday() + "|" + pattern.block().name() + "|" + pattern.effective()));
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static LocalDate today(Instant now) { return LocalDate.ofInstant(now, TimeBlock.SEOUL); }
}
