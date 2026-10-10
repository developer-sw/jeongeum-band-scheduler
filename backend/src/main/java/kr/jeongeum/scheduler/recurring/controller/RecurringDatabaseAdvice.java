package kr.jeongeum.scheduler.recurring.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiErrorResponse;
import kr.jeongeum.scheduler.common.web.RequestIdFilter;
import kr.jeongeum.scheduler.recurring.service.AssignmentConflictException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RecurringController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RecurringDatabaseAdvice {
    @ExceptionHandler(AssignmentConflictException.class)
    ResponseEntity<ApiErrorResponse> conflict(AssignmentConflictException exception, HttpServletRequest request) {
        return ResponseEntity.status(409).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse(new ApiErrorResponse.Error(
                exception.code(), exception.getMessage(), (String) request.getAttribute(RequestIdFilter.ATTRIBUTE),
                List.of(), new ArrayList<>(exception.conflicts()))));
    }
    @ExceptionHandler({QueryTimeoutException.class, TransactionTimedOutException.class, PessimisticLockingFailureException.class,
            DataAccessResourceFailureException.class})
    ResponseEntity<ApiErrorResponse> temporary(HttpServletRequest request) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiErrorResponse.of("RESERVATION_TEMPORARILY_UNAVAILABLE",
                "예약 처리가 지연되고 있습니다. 잠시 후 같은 요청을 다시 시도해 주세요.",
                (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
