package kr.jeongeum.scheduler.roster.service;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.invitation.repository.InvitationRepository;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.roster.api.RosterEntryResponse;
import kr.jeongeum.scheduler.roster.domain.RosterEntry;
import kr.jeongeum.scheduler.roster.repository.RosterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RosterService {
    private final RosterRepository roster;
    private final MemberRepository members;
    private final InvitationRepository invitations;
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;
    private final Clock clock;
    public RosterService(RosterRepository roster, MemberRepository members, InvitationRepository invitations,
            DirectoryMutationGuard guard, MemberAccess access, Clock clock) {
        this.roster = roster; this.members = members; this.invitations = invitations;
        this.guard = guard; this.access = access; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public List<RosterEntryResponse> list(long actorId) { access.administrator(actorId); return roster.list(); }
    @Transactional
    public RosterEntryResponse create(long actorId, String name) {
        String normalized = DirectoryInputs.text(name, 40, true);
        guard.lock(); access.administratorForMutation(actorId);
        return roster.response(roster.insert(normalized, clock.instant()).id());
    }
    @Transactional
    public RosterEntryResponse rename(long actorId, long id, String name, long version) {
        String normalized = DirectoryInputs.text(name, 40, true);
        guard.lock(); access.administratorForMutation(actorId);
        RosterEntry entry = roster.lockById(id).orElseThrow(DirectoryInputs::notFound);
        DirectoryInputs.sameVersion(entry.version(), version);
        roster.rename(id, normalized, clock.instant());
        return roster.response(id);
    }
    @Transactional
    public RosterEntryResponse linkAccount(long actorId, long id, long userId, long version) {
        guard.lock(); access.administratorForMutation(actorId);
        RosterEntry entry = roster.lockById(id).orElseThrow(DirectoryInputs::notFound);
        DirectoryInputs.sameVersion(entry.version(), version);
        if (entry.userId() != null) { throw alreadyLinked(); }
        Member user = members.lockById(userId).orElseThrow(DirectoryInputs::notFound);
        if (!user.active()) { throw ApiException.invalidInput(); }
        if (roster.findByUserId(userId).isPresent()) {
            throw new ApiException(409, "ACCOUNT_ALREADY_LINKED", "이미 다른 명단에 연결된 계정입니다.");
        }
        roster.link(id, userId, clock.instant());
        invitations.revokePendingForRoster(id, clock.instant());
        return roster.response(id);
    }
    public static ApiException alreadyLinked() {
        return new ApiException(409, "ROSTER_ALREADY_LINKED", "이미 계정이 연결된 명단입니다.");
    }
}
