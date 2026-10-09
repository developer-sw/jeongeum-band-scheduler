package kr.jeongeum.scheduler.team.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.team.api.TeamSongResponse;
import kr.jeongeum.scheduler.team.service.TeamSongService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
@RequestMapping("/api/v1/teams/{teamId}/songs")
public class TeamSongController {
    private final TeamSongService songs;
    private final Clock clock;
    public TeamSongController(TeamSongService songs, Clock clock) { this.songs = songs; this.clock = clock; }
    @GetMapping
    public ResponseEntity<ApiResponse<List<TeamSongResponse>>> list(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String teamId) { return response(songs.list(actor.memberId(), DirectoryInputs.id(teamId)), 200); }
    @PostMapping
    public ResponseEntity<ApiResponse<TeamSongResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String teamId, @RequestBody Create input) {
        return response(songs.create(actor.memberId(), DirectoryInputs.id(teamId), input.title(), input.artist(),
                input.memo(), input.sortOrder()), 201);
    }
    @PatchMapping("/{songId}")
    public ResponseEntity<ApiResponse<TeamSongResponse>> update(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String teamId, @PathVariable String songId, @RequestBody Update input) {
        return response(songs.update(actor.memberId(), DirectoryInputs.id(teamId), DirectoryInputs.id(songId),
                input.title(), input.artist(), input.memo(), input.sortOrder(), DirectoryInputs.version(input.version())), 200);
    }
    @DeleteMapping("/{songId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String teamId, @PathVariable String songId, @RequestParam Long version) {
        songs.delete(actor.memberId(), DirectoryInputs.id(teamId), DirectoryInputs.id(songId), DirectoryInputs.version(version));
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data, clock));
    }
    public record Create(String title, String artist, String memo, Integer sortOrder) {}
    public record Update(String title, String artist, String memo, Integer sortOrder, Long version) {}
}
