package kr.jeongeum.scheduler.common.api;

import jakarta.servlet.http.HttpServletRequest;
import kr.jeongeum.scheduler.common.web.RequestIdFilter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiErrorResponse> apiException(ApiException exception, HttpServletRequest request) {
        return error(exception.status(), exception.code(), exception.getMessage(), request);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentNotValidException.class,
            MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiErrorResponse> invalidInput(Exception exception, HttpServletRequest request) {
        return error(400, "INVALID_INPUT", "입력한 항목을 확인해 주세요.", request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiErrorResponse> integrityFailure(HttpServletRequest request) {
        return error(409, "DATA_CONFLICT", "다른 요청과 충돌했습니다. 최신 상태를 확인해 주세요.", request);
    }

    private ResponseEntity<ApiErrorResponse> error(int status, String code, String message,
            HttpServletRequest request) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(ApiErrorResponse.of(code, message,
                        (String) request.getAttribute(RequestIdFilter.ATTRIBUTE)));
    }
}
