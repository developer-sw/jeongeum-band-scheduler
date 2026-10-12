package kr.jeongeum.scheduler.exchange.api;

import java.time.Instant;
import java.util.List;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;

public record ExchangeResponse(String id, long version, String status, String requesterUserId, Instant expiresAt,
        Instant createdAt, Instant processedAt, String processedByUserId, String stateReason, Instant serverTime,
        List<ReservationResponse> offered, List<ReservationResponse> requested, List<ExchangePlacement> preview,
        List<String> allocationReservationIds, List<String> canAllocateReservationIds, boolean canApprove,
        boolean canReject, boolean canCancel, List<String> replacedReservationIds, List<ReservationResponse> reservations) {}
