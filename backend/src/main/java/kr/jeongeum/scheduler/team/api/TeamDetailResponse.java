package kr.jeongeum.scheduler.team.api;

import java.util.List;

public record TeamDetailResponse(TeamResponse team, List<TeamMemberResponse> members, List<TeamSongResponse> songs,
        boolean canViewMembers, boolean canManageTeam, boolean canManageSongs) {}
