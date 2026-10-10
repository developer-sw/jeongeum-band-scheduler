package kr.jeongeum.scheduler.reservation.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record ScheduleResponse(LocalDate today, LocalDate bookableThrough, Instant serverTime, String timeZone,
        List<InstrumentResponse> instruments, List<Day> days) {
    public record Quota(int usedMinutes, int remainingMinutes) {}
    public record TeamQuota(String teamId, String teamName, int usedMinutes, int remainingMinutes) {}
    public record Day(LocalDate date, String openingStatus, Quota personalQuota, List<TeamQuota> teamQuotas, List<Block> blocks) {}
    public record Block(String blockCode, Instant startsAt, Instant endsAt, int durationMinutes, String phase,
            int remainingMinutes, boolean canReservePersonal, boolean canReserveTeam,
            List<String> availableInstrumentCodes, List<String> faultyInstrumentCodes, List<String> teamWarnings,
            List<ReservationSummary> reservations) {}
}
