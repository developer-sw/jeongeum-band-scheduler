package kr.jeongeum.scheduler.notice.api;

import java.time.Instant;

public record NoticeResponse(String id, String title, String body, boolean pinned, String authorUserId,
        Instant createdAt, Instant updatedAt, long version, boolean deleted) {}
