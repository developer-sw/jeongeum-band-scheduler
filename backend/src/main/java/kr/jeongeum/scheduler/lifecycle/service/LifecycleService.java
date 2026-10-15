package kr.jeongeum.scheduler.lifecycle.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.exchange.service.ExchangeNotificationTransitions;
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
import kr.jeongeum.scheduler.lifecycle.api.LifecycleRequests;
import kr.jeongeum.scheduler.lifecycle.api.LifecycleResponse;
import kr.jeongeum.scheduler.lifecycle.repository.LifecycleRepository;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.notification.service.NotificationPublisher;
import kr.jeongeum.scheduler.push.repository.PushSubscriptionRepository;
import kr.jeongeum.scheduler.recurring.repository.RecurringRepository;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One short PostgreSQL transaction owns soft closure and every dependent transition. */
@Service
public class LifecycleService {
    private final LifecycleRepository lifecycle;
    private final MemberRepository members;
    private final TeamRepository teams;
    private final ReservationRepository reservations;
    private final RecurringRepository recurring;
    private final PushSubscriptionRepository subscriptions;
    private final ExchangeService exchanges;
    private final ExchangeNotificationTransitions exchangeNotifications;
    private final NotificationPublisher notifications;
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;
    private final Clock clock;

    public LifecycleService(LifecycleRepository lifecycle,MemberRepository members,TeamRepository teams,
            ReservationRepository reservations,RecurringRepository recurring,PushSubscriptionRepository subscriptions,
            ExchangeService exchanges,ExchangeNotificationTransitions exchangeNotifications,NotificationPublisher notifications,
            DirectoryMutationGuard guard,MemberAccess access,Clock clock) {
        this.lifecycle=lifecycle; this.members=members; this.teams=teams; this.reservations=reservations;
        this.recurring=recurring; this.subscriptions=subscriptions; this.exchanges=exchanges;
        this.exchangeNotifications=exchangeNotifications; this.notifications=notifications;
        this.guard=guard; this.access=access; this.clock=clock;
    }
    @Transactional(timeout=10)
    public LifecycleResponse withdraw(long actor,long target,String key,LifecycleRequests.Close input) {
        return close(actor,target,key,input,true);
    }
    @Transactional(timeout=10)
    public LifecycleResponse disband(long actor,long target,String key,LifecycleRequests.Close input) {
        return close(actor,target,key,input,false);
    }
    private LifecycleResponse close(long actor,long target,String key,LifecycleRequests.Close input,boolean withdrawal) {
        if (input==null || target<=0) { throw ApiException.invalidInput(); }
        String digest=LifecycleInputs.keyDigest(key);
        long expected=DirectoryInputs.version(input.expectedVersion());
        String reason=DirectoryInputs.text(input.reason(),500,false);
        String route=withdrawal?"/api/v1/users/"+target+"/withdraw":"/api/v1/teams/"+target+"/disband";
        String fingerprint=LifecycleInputs.digest("POST|"+route+"|"+expected+"|"
                +(reason==null?"null":reason.length()+":"+reason));
        guard.lock();
        // A saved response never restores the rights of a withdrawn or demoted actor.
        access.administrator(actor);
        var replay=lifecycle.replay(actor,digest).orElse(null);
        if (replay!=null) {
            if (!replay.fingerprint().equals(fingerprint)) {
                throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","같은 재시도 키를 다른 요청에 사용할 수 없습니다.");
            }
            return replay.response();
        }
        Instant plannedNow=now();
        List<Reservation> planned=lifecycle.remainingReservations(withdrawal,target,plannedNow).stream()
                .map(id -> reservations.find(id).orElseThrow(DirectoryInputs::notFound)).toList();
        List<Long> memberTeams=withdrawal?lifecycle.membershipTeams(target):List.of(target);
        List<Long> capturedRecipients=withdrawal?List.of():lifecycle.activeTeamUsers(target);
        TreeSet<Long> userIds=new TreeSet<>(List.of(actor));
        if (withdrawal) { userIds.add(target); }
        userIds.addAll(capturedRecipients);
        for (Reservation reservation:planned) {
            userIds.add(reservation.creatorUserId());
            if (reservation.ownerUserId()!=null) { userIds.add(reservation.ownerUserId()); }
        }
        for (long user:userIds) { members.lockById(user).orElseThrow(DirectoryInputs::notFound); }
        for (long team:new TreeSet<>(memberTeams)) { lifecycle.lockTeam(team); }
        access.administrator(actor);
        long resultVersion;
        if (withdrawal) {
            var member=members.findById(target).orElseThrow(DirectoryInputs::notFound);
            DirectoryInputs.sameVersion(member.version(),expected);
            if ("WITHDRAWN".equals(member.status())) {
                throw new ApiException(409,"MEMBER_ALREADY_WITHDRAWN","이미 탈퇴 처리된 회원입니다.");
            }
            if (member.active() && member.globalAdmin() && members.activeAdministratorCount()<=1) {
                throw new ApiException(409,"LAST_GLOBAL_ADMIN","마지막 활성 전체 관리자는 탈퇴 처리할 수 없습니다.");
            }
            resultVersion=member.version()+1;
        } else {
            var team=teams.findById(target).orElseThrow(DirectoryInputs::notFound);
            DirectoryInputs.sameVersion(team.version(),expected);
            if (!team.active()) {
                throw new ApiException(409,"TEAM_ALREADY_DISBANDED","이미 해체 처리된 팀입니다.");
            }
            resultVersion=team.version()+1;
        }
        reservations.lockInstruments();
        if (!withdrawal) { recurring.lockPattern(true); }
        TreeSet<LocalDate> dates=new TreeSet<>();
        for (Reservation reservation:planned) { dates.add(reservation.date()); }
        for (LocalDate date:dates) { reservations.ensureAndLockDay(date,now()); }
        List<Reservation> lockedReservations=new ArrayList<>();
        for (Reservation snapshot:planned) {
            Reservation current=reservations.lock(snapshot.id()).orElseThrow(DirectoryInputs::notFound);
            if (current.version()!=snapshot.version() || !current.date().equals(snapshot.date())) {
                throw new ApiException(409,"VERSION_CONFLICT","예약이 변경되었습니다. 새로고침 후 다시 시도해 주세요.");
            }
            lockedReservations.add(current);
        }
        Instant appliedAt=now();
        List<Reservation> remaining=lockedReservations.stream().filter(reservation ->
                "CONFIRMED".equals(reservation.status()) && appliedAt.isBefore(reservation.endsAt())).toList();
        List<Long> affectedExchanges=lifecycle.affectedPendingExchanges(withdrawal,target);
        List<Long> canceledSourceExchanges=exchangeNotifications.pendingForReservations(
                remaining.stream().map(Reservation::id).toList());
        long operation=reservations.operation(withdrawal?"USER_WITHDRAW":"TEAM_DISBAND",actor,appliedAt,reason);
        int assignments=0;
        if (withdrawal) {
            lifecycle.withdraw(target,appliedAt);
            for (long team:memberTeams) { teams.touch(team,appliedAt); }
        } else {
            lifecycle.disband(target,appliedAt);
            assignments=lifecycle.endAssignments(target,LocalDate.ofInstant(appliedAt,TimeBlock.SEOUL).minusDays(1),appliedAt);
            if (assignments>0) { recurring.patternChanged(); }
        }
        for (Reservation reservation:remaining) {
            int consumed=reservations.reservedQuota(reservation.id());
            if (appliedAt.isBefore(reservation.startsAt()) && consumed>0) {
                reservations.quota(reservation,operation,-consumed,"RELEASE",appliedAt);
            }
            reservations.cancel(reservation.id(),actor,operation,appliedAt,
                    reason==null?(withdrawal?"회원 탈퇴에 따른 취소":"팀 해체에 따른 취소"):reason);
            reservations.cancelOccurrence(reservation.id(),appliedAt);
            notifications.reservationCancelledTo(reservations.find(reservation.id()).orElseThrow(),operation,appliedAt,capturedRecipients);
        }
        exchangeNotifications.invalidated(canceledSourceExchanges,appliedAt,capturedRecipients);
        exchanges.refreshPendingUnderGuard(affectedExchanges,capturedRecipients);
        lifecycle.cancelInvalidExchangeJobs(affectedExchanges,appliedAt);
        int subscriptionsStopped=0,sessionsRevoked=0;
        if (withdrawal) {
            subscriptionsStopped=subscriptions.deactivateUser(target,appliedAt);
            lifecycle.cancelMemberJobs(target,appliedAt);
            sessionsRevoked=lifecycle.revokeSessions(target);
        } else { lifecycle.cancelTeamJobs(target,appliedAt); }
        return lifecycle.record(actor,digest,fingerprint,withdrawal,target,expected,resultVersion,operation,
                remaining.size(),lifecycle.invalidatedCount(affectedExchanges),assignments,subscriptionsStopped,
                sessionsRevoked,reason,appliedAt);
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
