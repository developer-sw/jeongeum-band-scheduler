package kr.jeongeum.scheduler.team.api;

import kr.jeongeum.scheduler.team.domain.TeamSong;

public record TeamSongResponse(String id, String teamId, String title, String artist, String memo, int sortOrder, long version) {
    public static TeamSongResponse from(TeamSong song) {
        return new TeamSongResponse(Long.toString(song.id()), Long.toString(song.teamId()), song.title(),
                song.artist(), song.memo(), song.sortOrder(), song.version());
    }
}
