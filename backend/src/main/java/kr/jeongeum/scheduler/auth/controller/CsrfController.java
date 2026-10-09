package kr.jeongeum.scheduler.auth.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {
    private final Clock clock;

    public CsrfController(Clock clock) {
        this.clock = clock;
    }

    @GetMapping("/api/v1/auth/csrf")
    public ResponseEntity<ApiResponse<CsrfData>> csrf(CsrfToken csrfToken) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(new CsrfData(csrfToken.getHeaderName(), csrfToken.getToken()), clock));
    }

    public record CsrfData(String headerName, String token) {}
}
