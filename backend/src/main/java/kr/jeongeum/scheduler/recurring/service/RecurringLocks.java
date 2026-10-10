package kr.jeongeum.scheduler.recurring.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.recurring.repository.RecurringRepository;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;

@Service
public class RecurringLocks {
    private final MemberRepository members;
    private final RecurringRepository recurring;
    private final ReservationRepository reservations;
    public RecurringLocks(MemberRepository members, RecurringRepository recurring, ReservationRepository reservations) {
        this.members = members; this.recurring = recurring; this.reservations = reservations;
    }
    public List<Instrument> subjects(List<RecurringPlanning.Series> all, Long actorId, Long extraTeam) {
        Set<Long> users = new TreeSet<>();
        Set<Long> teams = new TreeSet<>();
        if (actorId != null) { users.add(actorId); }
        if (extraTeam != null) { teams.add(extraTeam); }
        for (var series : all) { users.add(series.assignment().createdByUserId()); teams.add(series.assignment().teamId()); }
        for (long id : users) { members.lockById(id).orElseThrow(DirectoryInputs::notFound); }
        for (long id : teams) { recurring.lockTeam(id); }
        return reservations.lockInstruments();
    }
    public void dates(Set<LocalDate> dates, Instant now) {
        for (LocalDate date : new TreeSet<>(dates)) { reservations.ensureAndLockDay(date, now); }
    }
    public void reservations(Set<Long> ids) {
        for (long id : new TreeSet<>(ids)) { reservations.lock(id).orElseThrow(DirectoryInputs::notFound); }
    }
}
