package kr.jeongeum.scheduler.reservation.api;

import java.util.List;

public record ReservationChangeResponse(List<String> replacedReservationIds, List<ReservationResponse> reservations) {}
