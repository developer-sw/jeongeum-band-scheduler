package kr.jeongeum.scheduler.exchange.api;

import java.time.Instant;
import java.util.List;

public record ExchangeListResponse(Instant serverTime, List<ExchangeResponse> requests) {}
