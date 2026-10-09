package kr.jeongeum.scheduler.team.service;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.roster.repository.RosterRepository;
import kr.jeongeum.scheduler.team.api.TeamDetailResponse;
import kr.jeongeum.scheduler.team.api.TeamResponse;
import kr.jeongeum.scheduler.team.api.TeamSongResponse;
import kr.jeongeum.scheduler.team.domain.Team;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import kr.jeongeum.scheduler.team.repository.TeamSongRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {
    private final TeamRepository teams;
    private final TeamSongRepository songs;
    private final RosterRepository roster;
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;
    private final Clock clock;
    public TeamService(TeamRepository teams, TeamSongRepository songs, RosterRepository roster,
            DirectoryMutationGuard guard, MemberAccess access, Clock clock) {
        this.teams = teams; this.songs = songs; this.roster = roster;
        this.guard = guard; this.access = access; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public List<TeamResponse> list(long actorId) {
        access.active(actorId);
        return teams.list().stream().map(TeamResponse::from).toList();
    }
    @Transactional(readOnly = true)
    public TeamDetailResponse detail(long actorId, long id) {
        Member actor = access.active(actorId);
        return response(actor, activeTeam(teams.findById(id).orElseThrow(DirectoryInputs::notFound)));
    }
    @Transactional
    public TeamResponse create(long actorId, String name, String kind) {
        String normalized = DirectoryInputs.text(name, 80, true);
        if (!"REGULAR".equals(kind) && !"ADDITIONAL".equals(kind)) { throw ApiException.invalidInput(); }
        guard.lock(); access.administratorForMutation(actorId);
        return TeamResponse.from(teams.insert(normalized, kind, clock.instant()));
    }
    @Transactional
    public TeamResponse rename(long actorId, long id, String name, long version) {
        String normalized = DirectoryInputs.text(name, 80, true);
        guard.lock(); access.administratorForMutation(actorId);
        Team team = lockActiveTeam(id);
        DirectoryInputs.sameVersion(team.version(), version);
        teams.rename(id, normalized, clock.instant());
        return TeamResponse.from(teams.findById(id).orElseThrow(DirectoryInputs::notFound));
    }
    @Transactional
    public TeamDetailResponse addMember(long actorId, long id, long rosterId) {
        guard.lock(); Member actor = access.administratorForMutation(actorId);
        lockActiveTeam(id);
        roster.lockById(rosterId).orElseThrow(DirectoryInputs::notFound);
        if (teams.membershipExists(id, rosterId)) {
            throw new ApiException(409, "MEMBERSHIP_ALREADY_EXISTS", "이미 이 팀에 등록된 사람입니다.");
        }
        teams.addMember(id, rosterId, clock.instant());
        teams.touch(id, clock.instant());
        return response(actor, teams.findById(id).orElseThrow(DirectoryInputs::notFound));
    }
    @Transactional
    public void removeMember(long actorId, long id, long rosterId) {
        guard.lock(); access.administratorForMutation(actorId);
        lockActiveTeam(id);
        if (teams.removeMember(id, rosterId, clock.instant()) == 0) { throw DirectoryInputs.notFound(); }
        teams.touch(id, clock.instant());
    }
    @Transactional
    public TeamDetailResponse assignLeader(long actorId, long id, Long rosterId) {
        guard.lock(); Member actor = access.administratorForMutation(actorId);
        lockActiveTeam(id);
        if (rosterId != null && !teams.membershipExists(id, rosterId)) { throw ApiException.invalidInput(); }
        teams.assignLeader(id, rosterId, clock.instant());
        teams.touch(id, clock.instant());
        return response(actor, teams.findById(id).orElseThrow(DirectoryInputs::notFound));
    }
    private Team lockActiveTeam(long id) { return activeTeam(teams.lockById(id).orElseThrow(DirectoryInputs::notFound)); }
    private static Team activeTeam(Team team) {
        if (!team.active()) { throw DirectoryInputs.notFound(); }
        return team;
    }
    private TeamDetailResponse response(Member actor, Team team) {
        String role = teams.roleForUser(team.id(), actor.id()).orElse(null);
        boolean canViewMembers = actor.globalAdmin() || role != null;
        return new TeamDetailResponse(TeamResponse.from(team), canViewMembers ? teams.members(team.id()) : List.of(),
                songs.list(team.id()).stream().map(TeamSongResponse::from).toList(), canViewMembers,
                actor.globalAdmin(), actor.globalAdmin() || "LEADER".equals(role));
    }
}
