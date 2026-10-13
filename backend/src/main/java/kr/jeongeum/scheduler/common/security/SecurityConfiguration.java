package kr.jeongeum.scheduler.common.security;

import kr.jeongeum.scheduler.auth.security.CurrentMemberFilter;
import kr.jeongeum.scheduler.auth.security.PersistentLoginCookieFilter;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.web.http.CookieSerializer;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @Profile("!bootstrap-admin")
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityErrorHandler errors,
            CurrentMemberFilter currentMember, SecurityContextRepository contexts,
            HttpSessionCsrfTokenRepository csrfTokens, CookieSerializer cookies,
            @Value("${spring.session.timeout}") Duration sessionTimeout) throws Exception {
        http
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/api/v1/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/register",
                                "/api/v1/auth/invitations/inspect").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/auth/me").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").authenticated()
                        .requestMatchers("/api/v1/invitations", "/api/v1/invitations/**").hasRole("GLOBAL_ADMIN")
                        .requestMatchers("/api/v1/roster", "/api/v1/roster/**", "/api/v1/users", "/api/v1/users/**")
                                .hasRole("GLOBAL_ADMIN")
                        .requestMatchers("/api/v1/teams", "/api/v1/teams/**").authenticated()
                        .requestMatchers("/api/v1/recurring-assignments", "/api/v1/recurring-assignments/**").hasRole("GLOBAL_ADMIN")
                        .requestMatchers("/api/v1/instrument-faults", "/api/v1/instrument-faults/**").hasRole("GLOBAL_ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/schedule", "/api/v1/instruments").authenticated()
                        .requestMatchers("/api/v1/reservations", "/api/v1/reservations/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/exchange-candidates").authenticated()
                        .requestMatchers("/api/v1/exchange-requests", "/api/v1/exchange-requests/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/v1/notices", "/api/v1/notices/**").authenticated()
                        .requestMatchers("/api/v1/notices", "/api/v1/notices/**").hasRole("GLOBAL_ADMIN")
                        .requestMatchers("/api/v1/notifications", "/api/v1/notifications/**").authenticated()
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokens)
                        .csrfTokenRequestHandler(new XorCsrfTokenRequestAttributeHandler()))
                .securityContext(context -> context.securityContextRepository(contexts).requireExplicitSave(true))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .addFilterBefore(currentMember, AnonymousAuthenticationFilter.class)
                .addFilterAfter(new PersistentLoginCookieFilter(cookies, sessionTimeout), CurrentMemberFilter.class);
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    /** The JSON controller authenticates through LoginService; no generated default user is installed. */
    @Bean
    AuthenticationManager noDefaultAuthenticationManager() {
        return authentication -> { throw new BadCredentialsException("Unsupported authentication mechanism"); };
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(HttpSessionCsrfTokenRepository csrfTokens) {
        CsrfAuthenticationStrategy csrf = new CsrfAuthenticationStrategy(csrfTokens);
        csrf.setRequestHandler(new XorCsrfTokenRequestAttributeHandler());
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), csrf));
    }

    @Bean
    FilterRegistrationBean<CurrentMemberFilter> disableServletCurrentMemberFilter(CurrentMemberFilter filter) {
        FilterRegistrationBean<CurrentMemberFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
