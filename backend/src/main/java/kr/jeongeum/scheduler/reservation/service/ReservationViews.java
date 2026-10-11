package kr.jeongeum.scheduler.reservation.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.member.api.TeamMembershipResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.api.ReservationSummary;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.stereotype.Service;

@Service
public class ReservationViews {
    private final MemberAccess access;
    private final MemberRepository members;
    private final TeamRepository teams;
    private final ReservationRepository reservations;
    public ReservationViews(MemberAccess access, MemberRepository members, TeamRepository teams, ReservationRepository reservations) {
        this.access = access; this.members = members; this.teams = teams; this.reservations = reservations;
    }
    public record Context(Member actor, List<TeamMembershipResponse> memberships, Map<Long,String> roles, List<Instrument> instruments) {
        public Set<Long> teamIds() { return roles.keySet(); }
        public List<String> faults() { return instruments.stream().filter(Instrument::faulty).map(Instrument::code).toList(); }
    }
    public Context context(long actorId) {
        Member actor = access.active(actorId);
        List<TeamMembershipResponse> memberships = members.memberships(actorId);
        Map<Long,String> roles = memberships.stream().collect(Collectors.toMap(
                membership -> Long.parseLong(membership.teamId()), TeamMembershipResponse::role));
        return new Context(actor, memberships, roles, reservations.instruments());
    }
    public boolean mine(Context context, Reservation reservation) {
        return reservation.ownerUserId() != null ? reservation.ownerUserId() == context.actor().id()
                : context.roles().containsKey(reservation.teamId());
    }
    public void requireRead(Context context, Reservation reservation) {
        if (!context.actor().globalAdmin() && !mine(context, reservation)) { throw DirectoryInputs.notFound(); }
    }
    public boolean cancelAuthority(Context context, Reservation reservation) {
        if (context.actor().globalAdmin()) { return true; }
        if (reservation.ownerUserId() != null) { return reservation.ownerUserId() == context.actor().id(); }
        String role = context.roles().get(reservation.teamId());
        return role != null && (reservation.creatorUserId() == context.actor().id() || "LEADER".equals(role));
    }
    public void requireCancel(Context context, Reservation reservation) {
        if (!cancelAuthority(context, reservation)) { throw MemberAccess.forbidden(); }
    }
    public void requireChange(Context context, Reservation reservation) {
        requireCancel(context, reservation);
        if (!activeTeam(reservation)) { throw DirectoryInputs.notFound(); }
    }
    public void requireChangeSubject(Reservation reservation) {
        if (reservation.ownerUserId() != null) { access.active(reservation.ownerUserId()); }
    }
    private boolean activeTeam(Reservation reservation) {
        return reservation.teamId() == null || teams.findById(reservation.teamId()).map(team -> team.active()).orElse(false);
    }
    private boolean activeChangeSubject(Reservation reservation) {
        return reservation.ownerUserId() == null ? activeTeam(reservation)
                : members.findById(reservation.ownerUserId()).map(Member::active).orElse(false);
    }
    public void requireCreate(Context context, String subjectType, Long teamId) {
        if ("TEAM".equals(subjectType) && !context.roles().containsKey(teamId)) {
            throw new ApiException(403, "MEMBERSHIP_REQUIRED", "현재 소속된 팀의 예약만 만들 수 있습니다.");
        }
    }
    public ReservationSummary summary(Context context, Reservation reservation, List<String> codes, Instant now) {
        String name = reservation.teamId() == null ? null : teams.findById(reservation.teamId()).map(team -> team.name()).orElse(null);
        return new ReservationSummary(Long.toString(reservation.id()), reservation.version(), reservation.ownerType(),
                reservation.purpose(), string(reservation.teamId()), name, codes, reservation.status(),
                TimeBlock.require(reservation.blockCode()).phase(reservation.date(), now), mine(context, reservation),
                "CONFIRMED".equals(reservation.status()) && now.isBefore(reservation.endsAt()) && cancelAuthority(context, reservation),
                "CONFIRMED".equals(reservation.status()) && now.isBefore(reservation.startsAt())
                        && cancelAuthority(context, reservation) && activeChangeSubject(reservation),
                reservation.originKind(), reservation.quotaExempt());
    }
    public ReservationResponse detail(Context context, Reservation reservation, Instant now) {
        return detail(context, reservation, reservations.instrumentCodes(reservation.id()), now);
    }
    public ReservationResponse detail(Context context, Reservation reservation, List<String> codes, Instant now) {
        ReservationSummary summary = summary(context, reservation, codes, now);
        return new ReservationResponse(summary.id(), summary.version(), summary.subjectType(), summary.purpose(), summary.teamId(),
                summary.teamName(), codes, summary.status(), summary.phase(), summary.isMine(), summary.canCancel(), summary.canChange(),
                string(reservation.ownerUserId()), Long.toString(reservation.creatorUserId()), reservation.date(), reservation.blockCode(),
                reservation.startsAt(), reservation.endsAt(), reservation.usableFrom(), reservations.reservedQuota(reservation.id()),
                reservation.quotaExempt(), reservation.dualAssetException(), reservation.originKind(), reservation.teamId() == null ? List.of() : context.faults(),
                reservation.canceledAt(), reservation.cancelReason(), reservations.successorIds(reservation.id()).stream().map(Object::toString).toList());
    }
    private static String string(Long value) { return value == null ? null : Long.toString(value); }
}
