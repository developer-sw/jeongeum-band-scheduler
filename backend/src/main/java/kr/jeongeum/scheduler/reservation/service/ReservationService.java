package kr.jeongeum.scheduler.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.reservation.api.ReservationRequests;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.domain.Team;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class ReservationService {
    private final ReservationRepository reservations;
    private final ReservationViews views;
    private final MemberRepository members;
    private final TeamRepository teams;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    public ReservationService(ReservationRepository reservations, ReservationViews views, MemberRepository members,
            TeamRepository teams, DirectoryMutationGuard guard, Clock clock, JdbcTemplate jdbc) {
        this.reservations = reservations; this.views = views; this.members = members;
        this.teams = teams; this.guard = guard; this.clock = clock; this.jdbc = jdbc;
    }
    public record Result(ReservationResponse reservation, int status) {}
    @Transactional(timeout = 10)
    public Result create(long actorId, String key, ReservationRequests.Create input) {
        String digest = ReservationInputs.keyDigest(key);
        LocalDate date = ReservationInputs.date(input.date());
        TimeBlock block = TimeBlock.require(input.blockCode());
        Long teamId = DirectoryInputs.optionalId(input.teamId());
        boolean personal = "PERSONAL".equals(input.subjectType());
        if (personal) {
            if (teamId != null || input.instrumentCodes() == null || input.instrumentCodes().size() != 1
                    || input.instrumentCodes().getFirst() == null) { throw ApiException.invalidInput(); }
        } else if (!"TEAM".equals(input.subjectType()) || teamId == null || input.instrumentCodes() != null) {
            throw ApiException.invalidInput();
        }
        String fingerprint = ReservationInputs.digest("POST|/api/v1/reservations|" + input.subjectType() + "|"
                + teamId + "|" + date + "|" + block.name() + "|" + (personal ? input.instrumentCodes().getFirst() : ""));
        guard.lock();
        ReservationViews.Context context = views.context(actorId);
        var replay = reservations.idempotency(actorId, digest).orElse(null);
        if (replay != null) {
            requireFingerprint(replay, fingerprint);
            views.requireCreate(context, input.subjectType(), teamId);
            Reservation result = reservations.find(replay.reservationId()).orElseThrow(DirectoryInputs::notFound);
            views.requireRead(context, result);
            return new Result(views.detail(context, result, now()), replay.responseStatus());
        }
        ReservationInputs.openDate(date, LocalDate.ofInstant(now(), TimeBlock.SEOUL));
        lockUsers(actorId);
        if (teamId != null) { lockTeam(teamId); }
        context = views.context(actorId);
        views.requireCreate(context, input.subjectType(), teamId);
        Team team = teamId == null ? null : teams.findById(teamId).orElseThrow(DirectoryInputs::notFound);
        if (team != null && !team.active()) { throw DirectoryInputs.notFound(); }
        List<Instrument> instruments = reservations.lockInstruments();
        context = new ReservationViews.Context(context.actor(), context.memberships(), context.roles(), instruments);
        List<Instrument> selected = personal ? instruments.stream().filter(asset -> asset.code().equals(input.instrumentCodes().getFirst())).toList()
                : instruments;
        if (selected.size() != (personal ? 1 : 6)) { throw ApiException.invalidInput(); }
        if (personal && selected.getFirst().faulty()) { throw faulty(); }
        if (!"OPEN".equals(reservations.ensureAndLockDay(date, now()))) { throw notOpen(); }
        Instant now = now();
        ReservationInputs.openDate(date, LocalDate.ofInstant(now, TimeBlock.SEOUL));
        if (!now.isBefore(block.endsAt(date))) { throw finished(); }
        if (personal && reservations.personalOverlap(actorId, date, block.name())) {
            throw new ApiException(409, "PERSONAL_OVERLAP", "같은 시간에 개인 예약을 두 건 만들 수 없습니다.");
        }
        List<String> occupied = reservations.occupiedCodes(date, block.name());
        if (selected.stream().anyMatch(asset -> occupied.contains(asset.code()))) {
            throw new ApiException(409, "RESERVATION_CONFLICT", "선택한 시간이나 악기에 예약이 있습니다.");
        }
        int consumption = block.durationMinutes() == 60 ? 60 : 0;
        if (reservations.usedMinutes(date, personal ? actorId : null, teamId) + consumption > 120) {
            throw new ApiException(409, "DAILY_LIMIT_EXCEEDED", "60분 예약은 주체별 하루 두 블록까지 가능합니다.");
        }
        long operationId = reservations.operation("CREATE", actorId, now, null);
        Reservation reservation = reservations.insert(input.subjectType(), personal ? "PERSONAL" : team.kind(),
                personal ? actorId : null, teamId, actorId, date, block, now, operationId);
        for (Instrument instrument : selected) { reservations.claim(reservation, instrument, now); }
        reservations.quota(reservation, operationId, consumption, "RESERVE", now);
        reservations.recordIdempotency(actorId, digest, fingerprint, "/api/v1/reservations", 201,
                reservation.id(), operationId, now);
        return new Result(views.detail(context, reservation, now), 201);
    }
    @Transactional(timeout = 10)
    public Result cancel(long actorId, long id, String key, ReservationRequests.Cancel input) {
        String digest = ReservationInputs.keyDigest(key);
        long version = DirectoryInputs.version(input.expectedVersion());
        String reason = DirectoryInputs.text(input.reason(), 500, false);
        String route = "/api/v1/reservations/" + id + "/cancel";
        // Length-prefixed reason prevents delimiter ambiguity without storing the request body.
        String fingerprint = ReservationInputs.digest("POST|" + route + "|" + version + "|"
                + (reason == null ? "null" : reason.length() + ":" + reason));
        guard.lock();
        ReservationViews.Context context = views.context(actorId);
        var replay = reservations.idempotency(actorId, digest).orElse(null);
        if (replay != null) {
            requireFingerprint(replay, fingerprint);
            Reservation result = reservations.find(replay.reservationId()).orElseThrow(DirectoryInputs::notFound);
            views.requireCancel(context, result);
            return new Result(views.detail(context, result, now()), replay.responseStatus());
        }
        Reservation planned = reservations.find(id).orElseThrow(DirectoryInputs::notFound);
        lockUsers(actorId, planned.creatorUserId());
        if (planned.teamId() != null) { lockTeam(planned.teamId()); }
        reservations.lockInstruments();
        if (!"OPEN".equals(reservations.ensureAndLockDay(planned.date(), now()))) { throw notOpen(); }
        Reservation reservation = reservations.lock(id).orElseThrow(DirectoryInputs::notFound);
        // Later change/exchange implementations must restart instead of extending this lock set.
        if (!reservation.date().equals(planned.date()) || !reservation.blockCode().equals(planned.blockCode())
                || !java.util.Objects.equals(reservation.teamId(), planned.teamId())) { throw versionConflict(); }
        context = views.context(actorId);
        views.requireCancel(context, reservation);
        DirectoryInputs.sameVersion(reservation.version(), version);
        Instant now = now();
        Long operationId = null;
        if ("CONFIRMED".equals(reservation.status())) {
            if (!now.isBefore(reservation.endsAt())) { throw finished(); }
            operationId = reservations.operation("CANCEL", actorId, now, reason);
            int consumed = reservations.reservedQuota(id);
            if (now.isBefore(reservation.startsAt()) && consumed > 0) {
                reservations.quota(reservation, operationId, -consumed, "RELEASE", now);
            }
            reservations.cancel(id, actorId, operationId, now, reason);
        } else if (!"CANCELED".equals(reservation.status())) { throw versionConflict(); }
        reservations.recordIdempotency(actorId, digest, fingerprint, route, 200, id, operationId, now);
        return new Result(views.detail(context, reservations.find(id).orElseThrow(DirectoryInputs::notFound), now), 200);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReservationResponse detail(long actorId, long id) {
        ReservationViews.Context context = views.context(actorId);
        Reservation reservation = reservations.find(id).orElseThrow(DirectoryInputs::notFound);
        views.requireRead(context, reservation);
        return views.detail(context, reservation, now());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReservationResponse> mine(long actorId, String scope, String fromInput, String toInput) {
        if (!"mine".equals(scope)) { throw ApiException.invalidInput(); }
        ReservationViews.Context context = views.context(actorId);
        Instant now = now();
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        LocalDate from = fromInput == null ? today : ReservationInputs.date(fromInput);
        LocalDate to = toInput == null ? today.plusDays(14) : ReservationInputs.date(toInput);
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 31) { throw ApiException.invalidInput(); }
        var instruments = reservations.instrumentsBetween(from, to);
        return reservations.between(from, to).stream().filter(reservation -> views.mine(context, reservation))
                .map(reservation -> views.detail(context, reservation, instruments.getOrDefault(reservation.id(), List.of()), now)).toList();
    }
    private void lockUsers(long... ids) {
        TreeSet<Long> sorted = new TreeSet<>();
        for (long id : ids) { sorted.add(id); }
        for (long id : sorted) { members.lockById(id).orElseThrow(DirectoryInputs::notFound); }
    }
    private void lockTeam(long id) {
        List<Long> rows = jdbc.queryForList("SELECT id FROM teams WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
        if (rows.isEmpty()) { throw DirectoryInputs.notFound(); }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private static void requireFingerprint(ReservationRepository.Idempotency record, String fingerprint) {
        if (!record.fingerprint().equals(fingerprint)) {
            throw new ApiException(409, "IDEMPOTENCY_KEY_REUSED", "같은 재시도 키를 다른 요청에 사용할 수 없습니다.");
        }
    }
    private static ApiException notOpen() { return new ApiException(409, "DATE_NOT_OPEN", "아직 개방되지 않은 날짜입니다."); }
    private static ApiException finished() { return new ApiException(409, "RESERVATION_FINISHED", "이미 종료된 시간은 예약하거나 취소할 수 없습니다."); }
    private static ApiException faulty() { return new ApiException(409, "ASSET_FAULTY", "고장 안내가 있는 악기는 새 개인 예약을 할 수 없습니다."); }
    private static ApiException versionConflict() { return new ApiException(409, "VERSION_CONFLICT", "예약이 변경되었습니다. 새로고침 후 다시 시도해 주세요."); }
}
