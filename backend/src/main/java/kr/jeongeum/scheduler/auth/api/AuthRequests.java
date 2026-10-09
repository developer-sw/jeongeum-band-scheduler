package kr.jeongeum.scheduler.auth.api;

public final class AuthRequests {
    private AuthRequests() {}

    public record Login(String loginId, String password) {
        @Override public String toString() { return "Login[redacted]"; }
    }

    public record Register(String invitationToken, String loginId, String password, String displayName) {
        @Override public String toString() { return "Register[redacted]"; }
    }

    public record Inspect(String token) {
        @Override public String toString() { return "Inspect[redacted]"; }
    }
}
