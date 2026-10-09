package kr.jeongeum.scheduler.common.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import kr.jeongeum.scheduler.common.api.ApiErrorResponse;
import kr.jeongeum.scheduler.common.web.RequestIdFilter;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper objectMapper;

    public SecurityErrorHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        write(request, response, 401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
            org.springframework.security.access.AccessDeniedException exception) throws IOException {
        if (exception instanceof CsrfException) {
            write(request, response, 403, "CSRF_INVALID", "보안 토큰을 다시 받아 요청해 주세요.");
        } else {
            write(request, response, 403, "FORBIDDEN", "요청을 수행할 권한이 없습니다.");
        }
    }

    private void write(HttpServletRequest request, HttpServletResponse response, int status,
            String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiErrorResponse.of(code, message, (String) request.getAttribute(RequestIdFilter.ATTRIBUTE))));
    }
}
