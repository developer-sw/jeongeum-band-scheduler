package kr.jeongeum.scheduler.instrument.api;

import java.time.Instant;
import kr.jeongeum.scheduler.instrument.domain.InstrumentFault;
import kr.jeongeum.scheduler.reservation.domain.Instrument;

public record InstrumentFaultResponse(String id, String instrumentCode, String instrumentName, String status,
        String description, String origin, String reportedByUserId, Instant reportedAt,
        String resolvedByUserId, Instant resolvedAt, String resolutionNote, long version, long assetVersion) {
    public static InstrumentFaultResponse from(InstrumentFault fault, Instrument asset) {
        return new InstrumentFaultResponse(Long.toString(fault.id()), asset.code(), asset.name(), fault.status(),
                fault.description(), fault.origin(), id(fault.reportedByUserId()), fault.reportedAt(),
                id(fault.resolvedByUserId()), fault.resolvedAt(), fault.resolutionNote(), fault.version(), asset.version());
    }
    private static String id(Long value) { return value == null ? null : value.toString(); }
}
