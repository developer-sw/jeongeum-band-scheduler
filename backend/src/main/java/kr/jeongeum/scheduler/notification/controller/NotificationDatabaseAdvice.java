package kr.jeongeum.scheduler.notification.controller;

import jakarta.servlet.http.HttpServletRequest;
import kr.jeongeum.scheduler.common.api.ApiErrorResponse;
import kr.jeongeum.scheduler.common.web.RequestIdFilter;
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

@RestControllerAdvice(assignableTypes=NotificationController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class NotificationDatabaseAdvice {
    @ExceptionHandler({QueryTimeoutException.class,TransactionTimedOutException.class,PessimisticLockingFailureException.class,DataAccessResourceFailureException.class})
    ResponseEntity<ApiErrorResponse> temporary(HttpServletRequest request) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiErrorResponse.of("NOTIFICATION_TEMPORARILY_UNAVAILABLE",
                "알림함 처리가 지연되고 있습니다. 잠시 후 다시 시도해 주세요.",(String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
