package kr.jeongeum.scheduler.team.service;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.team.api.TeamSongResponse;
import kr.jeongeum.scheduler.team.domain.Team;
import kr.jeongeum.scheduler.team.domain.TeamSong;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import kr.jeongeum.scheduler.team.repository.TeamSongRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamSongService {
    private final TeamRepository teams;
    private final TeamSongRepository songs;
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;
    private final Clock clock;
    public TeamSongService(TeamRepository teams, TeamSongRepository songs, DirectoryMutationGuard guard,
            MemberAccess access, Clock clock) {
        this.teams = teams; this.songs = songs; this.guard = guard; this.access = access; this.clock = clock;
    }
    @Transactional(readOnly = true)
    public List<TeamSongResponse> list(long actorId, long teamId) {
        access.active(actorId);
        requireActive(teams.findById(teamId).orElseThrow(DirectoryInputs::notFound));
        return songs.list(teamId).stream().map(TeamSongResponse::from).toList();
    }
    @Transactional
    public TeamSongResponse create(long actorId, long teamId, String title, String artist, String memo, Integer sortOrder) {
        String normalizedTitle = DirectoryInputs.text(title, 120, true);
        String normalizedArtist = DirectoryInputs.text(artist, 120, false);
        String normalizedMemo = DirectoryInputs.memo(memo);
        if (sortOrder != null && sortOrder < 0) { throw ApiException.invalidInput(); }
        requireMutation(actorId, teamId);
        long order = sortOrder == null ? songs.nextSortOrder(teamId) : sortOrder;
        if (order > Integer.MAX_VALUE) { throw ApiException.invalidInput(); }
        return TeamSongResponse.from(songs.insert(teamId, normalizedTitle, normalizedArtist, normalizedMemo,
                (int) order, actorId, clock.instant()));
    }
    @Transactional
    public TeamSongResponse update(long actorId, long teamId, long songId, String title, String artist,
            String memo, Integer sortOrder, long version) {
        String normalizedTitle = DirectoryInputs.text(title, 120, true);
        String normalizedArtist = DirectoryInputs.text(artist, 120, false);
        String normalizedMemo = DirectoryInputs.memo(memo);
        if (sortOrder == null || sortOrder < 0) { throw ApiException.invalidInput(); }
        requireMutation(actorId, teamId);
        TeamSong song = songs.lockById(teamId, songId).orElseThrow(DirectoryInputs::notFound);
        DirectoryInputs.sameVersion(song.version(), version);
        return TeamSongResponse.from(songs.update(teamId, songId, normalizedTitle, normalizedArtist,
                normalizedMemo, sortOrder, actorId, clock.instant()));
    }
    @Transactional
    public void delete(long actorId, long teamId, long songId, long version) {
        requireMutation(actorId, teamId);
        TeamSong song = songs.lockById(teamId, songId).orElseThrow(DirectoryInputs::notFound);
        DirectoryInputs.sameVersion(song.version(), version);
        songs.delete(teamId, songId);
    }
    private void requireMutation(long actorId, long teamId) {
        guard.lock();
        Member actor = access.activeForMutation(actorId);
        requireActive(teams.lockById(teamId).orElseThrow(DirectoryInputs::notFound));
        if (!actor.globalAdmin() && !"LEADER".equals(teams.roleForUser(teamId, actorId).orElse(null))) {
            throw MemberAccess.forbidden();
        }
    }
    private static void requireActive(Team team) { if (!team.active()) { throw DirectoryInputs.notFound(); } }
}
