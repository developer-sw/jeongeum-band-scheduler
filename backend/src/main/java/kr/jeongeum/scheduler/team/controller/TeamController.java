package kr.jeongeum.scheduler.team.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.team.api.TeamDetailResponse;
import kr.jeongeum.scheduler.team.api.TeamResponse;
import kr.jeongeum.scheduler.team.service.TeamService;
import kr.jeongeum.scheduler.lifecycle.api.LifecycleRequests;
import kr.jeongeum.scheduler.lifecycle.api.LifecycleResponse;
import kr.jeongeum.scheduler.lifecycle.service.LifecycleService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
@RequestMapping("/api/v1/teams")
public class TeamController {
    private final TeamService teams;
    private final Clock clock;
    private final LifecycleService lifecycle;
    public TeamController(TeamService teams, Clock clock, LifecycleService lifecycle) {
        this.teams = teams; this.clock = clock; this.lifecycle = lifecycle;
    }
    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamResponse>>> list(@AuthenticationPrincipal MemberPrincipal actor) {
        return response(teams.list(actor.memberId()), 200);
    }
    @PostMapping
    public ResponseEntity<ApiResponse<TeamResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestBody Create input) { return response(teams.create(actor.memberId(), input.name(), input.kind()), 201); }
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamDetailResponse>> detail(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id) { return response(teams.detail(actor.memberId(), DirectoryInputs.id(id)), 200); }
    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponse<TeamResponse>> rename(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody Rename input) {
        return response(teams.rename(actor.memberId(), DirectoryInputs.id(id), input.name(), DirectoryInputs.version(input.version())), 200);
    }
    @PostMapping("/{id}/members")
    public ResponseEntity<ApiResponse<TeamDetailResponse>> add(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody Membership input) {
        return response(teams.addMember(actor.memberId(), DirectoryInputs.id(id), DirectoryInputs.id(input.rosterEntryId())), 201);
    }
    @DeleteMapping("/{id}/members/{rosterId}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @PathVariable String rosterId) {
        teams.removeMember(actor.memberId(), DirectoryInputs.id(id), DirectoryInputs.id(rosterId));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    @PutMapping("/{id}/leader")
    public ResponseEntity<ApiResponse<TeamDetailResponse>> leader(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody Membership input) {
        return response(teams.assignLeader(actor.memberId(), DirectoryInputs.id(id), DirectoryInputs.optionalId(input.rosterEntryId())), 200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data, clock));
    }
    public record Create(String name, String kind) {}
    public record Rename(String name, Long version) {}
    public record Membership(String rosterEntryId) {}
    @PostMapping("/{id}/disband")
    public ResponseEntity<ApiResponse<LifecycleResponse>> disband(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id,@RequestHeader(name="Idempotency-Key",required=false) String key,
            @RequestBody LifecycleRequests.Close input) {
        return response(lifecycle.disband(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
}
