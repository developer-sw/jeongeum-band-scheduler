package kr.jeongeum.scheduler.instrument.api;

import java.time.Instant;
import java.util.List;

public record InstrumentFaultListResponse(Instant serverTime, List<InstrumentFaultResponse> incidents, String nextBeforeId) {}
