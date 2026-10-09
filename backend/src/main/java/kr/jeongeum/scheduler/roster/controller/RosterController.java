package kr.jeongeum.scheduler.roster.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.roster.api.RosterEntryResponse;
import kr.jeongeum.scheduler.roster.service.RosterService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
@RequestMapping("/api/v1/roster")
public class RosterController {
    private final RosterService roster;
    private final Clock clock;
    public RosterController(RosterService roster, Clock clock) { this.roster = roster; this.clock = clock; }
    @GetMapping
    public ResponseEntity<ApiResponse<List<RosterEntryResponse>>> list(@AuthenticationPrincipal MemberPrincipal actor) {
        return response(roster.list(actor.memberId()), 200);
    }
    @PostMapping
    public ResponseEntity<ApiResponse<RosterEntryResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestBody Create input) { return response(roster.create(actor.memberId(), input.name()), 201); }
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<RosterEntryResponse>> rename(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody Rename input) {
        return response(roster.rename(actor.memberId(), DirectoryInputs.id(id), input.name(),
                DirectoryInputs.version(input.version())), 200);
    }
    @PostMapping("/{id}/link-account")
    public ResponseEntity<ApiResponse<RosterEntryResponse>> link(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody Link input) {
        return response(roster.linkAccount(actor.memberId(), DirectoryInputs.id(id), DirectoryInputs.id(input.userId()),
                DirectoryInputs.version(input.version())), 200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data, clock));
    }
    public record Create(String name) {}
    public record Rename(String name, Long version) {}
    public record Link(String userId, Long version) {}
}
