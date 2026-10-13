package kr.jeongeum.scheduler.notice.api;

import java.time.Instant;
import java.util.List;

public record NoticeListResponse(Instant serverTime, List<NoticeResponse> notices, String nextCursor) {}
