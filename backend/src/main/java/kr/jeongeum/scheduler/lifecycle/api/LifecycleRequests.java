package kr.jeongeum.scheduler.lifecycle.api;

public final class LifecycleRequests {
    private LifecycleRequests() {}
    public record Close(Long expectedVersion, String reason) {}
}
