package kr.jeongeum.scheduler.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.jeongeum.scheduler.auth.api.AuthRequests;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.auth.service.LoginService;
import kr.jeongeum.scheduler.auth.service.RegistrationService;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.invitation.service.InvitationService;
import kr.jeongeum.scheduler.member.api.MemberResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.service.MemberService;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfLogoutHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!bootstrap-admin")
public class AuthenticationController {
    private final LoginService login;
    private final RegistrationService registration;
    private final InvitationService invitations;
    private final SecurityContextRepository contexts;
    private final SessionAuthenticationStrategy sessionAuthentication;
    private final HttpSessionCsrfTokenRepository csrfTokens;
    private final Clock clock;
    private final MemberService memberService;
    private final int sessionTimeoutSeconds;

    public AuthenticationController(LoginService login, RegistrationService registration,
            InvitationService invitations, SecurityContextRepository contexts,
            SessionAuthenticationStrategy sessionAuthentication, HttpSessionCsrfTokenRepository csrfTokens,
            Clock clock, MemberService memberService,
            @Value("${spring.session.timeout}") Duration sessionTimeout) {
        this.login = login;
        this.registration = registration;
        this.invitations = invitations;
        this.contexts = contexts;
        this.sessionAuthentication = sessionAuthentication;
        this.csrfTokens = csrfTokens;
        this.clock = clock;
        this.memberService = memberService;
        this.sessionTimeoutSeconds = Math.toIntExact(sessionTimeout.toSeconds());
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<ApiResponse<MemberResponse>> login(@RequestBody AuthRequests.Login input,
            HttpServletRequest request, HttpServletResponse response) {
        Member member = login.login(input.loginId(), input.password());
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                new MemberPrincipal(member.id()), null, List.of());
        // The controller performs the strategies and explicit save that form-login normally owns.
        sessionAuthentication.onAuthentication(authentication, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        // A pre-login CSRF session may still have the old inactivity limit.
        request.getSession().setMaxInactiveInterval(sessionTimeoutSeconds);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(memberService.current(member.id()), clock));
    }

    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<ApiResponse<MemberResponse>> register(@RequestBody AuthRequests.Register input) {
        MemberResponse member = registration.register(input.invitationToken(), input.loginId(),
                input.password(), input.displayName());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.of(member, clock));
    }

    @GetMapping("/api/v1/auth/me")
    public ResponseEntity<ApiResponse<MemberResponse>> me(@AuthenticationPrincipal MemberPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(memberService.current(principal.memberId()), clock));
    }

    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) {
        new CsrfLogoutHandler(csrfTokens).logout(request, response, authentication);
        SecurityContextLogoutHandler logout = new SecurityContextLogoutHandler();
        logout.setSecurityContextRepository(contexts);
        logout.logout(request, response, authentication);
        new CookieClearingLogoutHandler("SESSION").logout(request, response, authentication);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/api/v1/auth/invitations/inspect")
    public ResponseEntity<ApiResponse<Inspection>> inspect(@RequestBody AuthRequests.Inspect input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(new Inspection(invitations.inspect(input.token())), clock));
    }

    public record Inspection(Instant expiresAt) {}
}
