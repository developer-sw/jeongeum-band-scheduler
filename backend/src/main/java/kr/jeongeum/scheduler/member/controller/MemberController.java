package kr.jeongeum.scheduler.member.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.member.api.AdminMemberResponse;
import kr.jeongeum.scheduler.member.service.MemberService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
@RequestMapping("/api/v1/users")
public class MemberController {
    private final MemberService members;
    private final Clock clock;
    public MemberController(MemberService members, Clock clock) { this.members = members; this.clock = clock; }
    @GetMapping
    public ResponseEntity<ApiResponse<List<AdminMemberResponse>>> list(@AuthenticationPrincipal MemberPrincipal actor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(members.list(actor.memberId()), clock));
    }
    @PatchMapping("/{id}/role")
    public ResponseEntity<ApiResponse<AdminMemberResponse>> role(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestBody ChangeRole input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(members.changeRole(
                actor.memberId(), DirectoryInputs.id(id), input.globalRole(), DirectoryInputs.version(input.version())), clock));
    }
    public record ChangeRole(String globalRole, Long version) {}
}
