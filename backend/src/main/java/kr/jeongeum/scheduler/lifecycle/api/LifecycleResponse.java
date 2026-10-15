package kr.jeongeum.scheduler.lifecycle.api;

public record LifecycleResponse(String operationId, String targetId, String status, long version,
        int canceledReservations, int invalidatedExchanges, int endedAssignments,
        int deactivatedSubscriptions, int revokedSessions) {}
