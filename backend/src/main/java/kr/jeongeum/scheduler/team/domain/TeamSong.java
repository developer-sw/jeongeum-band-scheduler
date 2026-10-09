package kr.jeongeum.scheduler.team.domain;

public record TeamSong(long id, long teamId, String title, String artist, String memo, int sortOrder, long version) {}
