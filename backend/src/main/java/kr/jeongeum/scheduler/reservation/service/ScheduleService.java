package kr.jeongeum.scheduler.reservation.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.reservation.api.InstrumentResponse;
import kr.jeongeum.scheduler.reservation.api.ScheduleResponse;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ScheduleService {
    private final ReservationRepository reservations;
    private final ReservationViews views;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    public ScheduleService(ReservationRepository reservations, ReservationViews views, DirectoryMutationGuard guard, Clock clock) {
        this.reservations = reservations; this.views = views; this.guard = guard; this.clock = clock;
    }
    @Transactional(timeout = 10)
    public ScheduleResponse schedule(long actorId, String fromInput, String toInput) {
        // New dates are prepared only in the same mutation protocol as reservation writes.
        // Recurring allocation will later populate PREPARING dates before marking them OPEN.
        guard.lock();
        ReservationViews.Context context = views.context(actorId);
        Instant initialNow = now();
        LocalDate initialToday = LocalDate.ofInstant(initialNow, TimeBlock.SEOUL);
        LocalDate from = fromInput == null ? initialToday : ReservationInputs.date(fromInput);
        LocalDate to = toInput == null ? initialToday.plusDays(14) : ReservationInputs.date(toInput);
        requireRange(from, to, initialToday);
        List<Instrument> instruments = reservations.lockInstruments();
        context = new ReservationViews.Context(context.actor(), context.memberships(), context.roles(), instruments);
        Map<LocalDate,String> statuses = new HashMap<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            statuses.put(date, reservations.ensureAndLockDay(date, initialNow));
        }
        Instant now = now();
        LocalDate today = LocalDate.ofInstant(now, TimeBlock.SEOUL);
        requireRange(from, to, today);
        Map<Long,List<String>> codes = reservations.instrumentsBetween(from, to);
        Map<ReservationRepository.QuotaKey,Integer> quotas = reservations.quotasBetween(from, to);
        Map<LocalDate,List<Reservation>> byDay = reservations.between(from, to).stream()
                .filter(reservation -> "CONFIRMED".equals(reservation.status()))
                .collect(Collectors.groupingBy(Reservation::date));
        List<ScheduleResponse.Day> days = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(day(context, date, statuses.get(date), byDay.getOrDefault(date, List.of()), codes, quotas, now));
        }
        return new ScheduleResponse(today, today.plusDays(14), now, TimeBlock.SEOUL.getId(),
                instruments.stream().map(InstrumentResponse::from).toList(), days);
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<InstrumentResponse> instruments(long actorId) {
        return views.context(actorId).instruments().stream().map(InstrumentResponse::from).toList();
    }
    private ScheduleResponse.Day day(ReservationViews.Context context, LocalDate date, String status,
            List<Reservation> confirmed, Map<Long,List<String>> codes,
            Map<ReservationRepository.QuotaKey,Integer> quotas, Instant now) {
        int personalUsed = quotas.getOrDefault(new ReservationRepository.QuotaKey(date, context.actor().id(), null), 0);
        List<ScheduleResponse.TeamQuota> teamQuotas = context.memberships().stream().map(membership -> {
            int used = quotas.getOrDefault(new ReservationRepository.QuotaKey(date, null, Long.parseLong(membership.teamId())), 0);
            return new ScheduleResponse.TeamQuota(membership.teamId(), membership.teamName(), used, Math.max(0, 120 - used));
        }).toList();
        List<ScheduleResponse.Block> blocks = new ArrayList<>();
        for (TimeBlock block : TimeBlock.values()) {
            List<Reservation> occupancies = confirmed.stream().filter(reservation -> block.name().equals(reservation.blockCode())).toList();
            Set<String> occupiedCodes = occupancies.stream().flatMap(reservation -> codes.getOrDefault(reservation.id(), List.of()).stream())
                    .collect(Collectors.toSet());
            List<String> available = context.instruments().stream().filter(instrument -> !instrument.faulty() && !occupiedCodes.contains(instrument.code()))
                    .map(Instrument::code).toList();
            boolean personalOverlap = occupancies.stream().anyMatch(reservation -> reservation.ownerUserId() != null
                    && reservation.ownerUserId() == context.actor().id());
            boolean reservable = "OPEN".equals(status) && now.isBefore(block.endsAt(date));
            int consumption = block.durationMinutes() == 60 ? 60 : 0;
            boolean personal = reservable && personalUsed + consumption <= 120 && !personalOverlap && !available.isEmpty();
            boolean team = reservable && occupancies.isEmpty()
                    && teamQuotas.stream().anyMatch(quota -> quota.usedMinutes() + consumption <= 120);
            blocks.add(new ScheduleResponse.Block(block.name(), block.startsAt(date), block.endsAt(date), block.durationMinutes(),
                    block.phase(date, now), block.remainingMinutes(date, now), personal, team, available, context.faults(), context.faults(),
                    occupancies.stream().map(reservation -> views.summary(context, reservation, codes.getOrDefault(reservation.id(), List.of()), now)).toList()));
        }
        return new ScheduleResponse.Day(date, status, new ScheduleResponse.Quota(personalUsed, Math.max(0, 120 - personalUsed)), teamQuotas, blocks);
    }
    private static void requireRange(LocalDate from, LocalDate to, LocalDate today) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= 15 || from.isBefore(today) || to.isAfter(today.plusDays(14))) {
            throw ApiException.invalidInput();
        }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
