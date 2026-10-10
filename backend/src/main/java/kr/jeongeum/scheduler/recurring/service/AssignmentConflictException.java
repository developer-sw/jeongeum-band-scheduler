package kr.jeongeum.scheduler.recurring.service;

import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.recurring.api.RecurringResponse;

public final class AssignmentConflictException extends ApiException {
    private final List<RecurringResponse.Conflict> conflicts;
    public AssignmentConflictException(List<RecurringResponse.Conflict> conflicts) {
        super(409, "ASSIGNMENT_CONFLICT", "기존 예약이나 다른 고정 배정과 충돌합니다. 기존 일정은 유지되었습니다.");
        this.conflicts = List.copyOf(conflicts);
    }
    public List<RecurringResponse.Conflict> conflicts() { return conflicts; }
}
