package kr.jeongeum.scheduler.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Duration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

/** Renews the browser cookie alongside Spring Session's sliding inactivity expiry. */
public final class PersistentLoginCookieFilter extends OncePerRequestFilter {
    private final CookieSerializer cookies;
    private final int timeoutSeconds;

    public PersistentLoginCookieFilter(CookieSerializer cookies, Duration timeout) {
        this.cookies = cookies;
        this.timeoutSeconds = Math.toIntExact(timeout.toSeconds());
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("Persistent login requires a positive timeout");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/logout");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof MemberPrincipal) {
            HttpSession session = request.getSession(false);
            if (session != null && request.isRequestedSessionIdValid()) {
                session.setMaxInactiveInterval(timeoutSeconds);
                // Write before MVC can commit the response. Member validity was already checked.
                cookies.writeCookieValue(new CookieSerializer.CookieValue(request, response, session.getId()));
            }
        }
        chain.doFilter(request, response);
    }
}
