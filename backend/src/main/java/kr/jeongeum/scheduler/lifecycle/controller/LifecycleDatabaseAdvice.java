package kr.jeongeum.scheduler.lifecycle.controller;

import jakarta.servlet.http.HttpServletRequest;
import kr.jeongeum.scheduler.common.api.ApiErrorResponse;
import kr.jeongeum.scheduler.common.web.RequestIdFilter;
import kr.jeongeum.scheduler.member.controller.MemberController;
import kr.jeongeum.scheduler.team.controller.TeamController;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes={MemberController.class,TeamController.class})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LifecycleDatabaseAdvice {
    @ExceptionHandler({QueryTimeoutException.class,TransactionTimedOutException.class,PessimisticLockingFailureException.class})
    ResponseEntity<ApiErrorResponse> temporaryFailure(HttpServletRequest request) {
        return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(ApiErrorResponse.of(
                "LIFECYCLE_TEMPORARILY_UNAVAILABLE","정리 처리가 지연되고 있습니다. 잠시 후 같은 요청을 다시 시도해 주세요.",
                (String)request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
