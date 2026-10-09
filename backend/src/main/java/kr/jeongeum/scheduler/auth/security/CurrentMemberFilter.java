package kr.jeongeum.scheduler.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import kr.jeongeum.scheduler.common.security.SecurityErrorHandler;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class CurrentMemberFilter extends OncePerRequestFilter {
    private final MemberRepository members;
    private final SecurityErrorHandler errors;

    public CurrentMemberFilter(MemberRepository members, SecurityErrorHandler errors) {
        this.members = members;
        this.errors = errors;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            Member member = authentication.getPrincipal() instanceof MemberPrincipal principal
                    ? members.findById(principal.memberId()).orElse(null) : null;
            if (member == null || !member.active()) {
                new SecurityContextLogoutHandler().logout(request, response, authentication);
                new CookieClearingLogoutHandler("SESSION").logout(request, response, authentication);
                errors.reject(request, response, member == null ? 401 : 403,
                        member == null ? "AUTHENTICATION_REQUIRED" : "INACTIVE_USER",
                        member == null ? "로그인이 필요합니다." : "현재 이용할 수 없는 계정입니다.");
                return;
            }
            var context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                    new MemberPrincipal(member.id()), null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + member.globalRole()))));
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }
}
