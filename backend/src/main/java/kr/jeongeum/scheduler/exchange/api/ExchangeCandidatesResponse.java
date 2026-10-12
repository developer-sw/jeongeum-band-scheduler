package kr.jeongeum.scheduler.exchange.api;

import java.time.Instant;
import java.util.List;

public record ExchangeCandidatesResponse(Instant serverTime, List<ExchangeCandidate> reservations) {}
