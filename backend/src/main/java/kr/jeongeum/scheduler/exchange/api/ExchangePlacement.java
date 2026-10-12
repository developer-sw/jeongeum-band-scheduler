package kr.jeongeum.scheduler.exchange.api;

import java.time.LocalDate;
import java.util.List;

public record ExchangePlacement(String side, List<String> sourceReservationIds, String subjectType, String purpose,
        String teamId, String teamName, String ownerUserId, String creatorUserId, LocalDate date, String blockCode,
        List<String> instrumentCodes, boolean allocationRequired, int quotaMinutes, List<String> teamFaultWarnings) {}
