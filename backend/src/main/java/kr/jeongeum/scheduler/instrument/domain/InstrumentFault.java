package kr.jeongeum.scheduler.instrument.domain;

import java.time.Instant;

public record InstrumentFault(long id, long assetId, String status, String description, String origin,
        Long reportedByUserId, Instant reportedAt, Long resolvedByUserId, Instant resolvedAt,
        String resolutionNote, long version) {
    public boolean open() { return "OPEN".equals(status); }
}
