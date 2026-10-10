package kr.jeongeum.scheduler.reservation.domain;

public record Instrument(long id, String code, String name, String status, long version) {
    public boolean faulty() { return "FAULTY".equals(status); }
}
