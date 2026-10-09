package kr.jeongeum.scheduler.health.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public HealthController(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @GetMapping("/api/v1/health")
    public ResponseEntity<ApiResponse<HealthData>> health() {
        boolean up;
        try {
            up = Integer.valueOf(1).equals(jdbcTemplate.queryForObject("SELECT 1", Integer.class));
        } catch (DataAccessException exception) {
            up = false;
        }
        return ResponseEntity.status(up ? 200 : 503).cacheControl(CacheControl.noStore())
                .body(ApiResponse.of(new HealthData(up ? "UP" : "DOWN"), clock));
    }

    public record HealthData(String status) {}
}
