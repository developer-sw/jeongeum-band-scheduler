package kr.jeongeum.scheduler.reservation.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;

public enum TimeBlock {
    B01(720,780), B02(780,840), B03(840,900), B04(900,960), B05(960,1020), B06(1020,1080),
    B07(1080,1170), B08(1170,1260), B09(1260,1350), B10(1350,1440),
    B11(0,60), B12(60,120), B13(120,180), B14(180,240), B15(240,300), B16(300,360),
    B17(360,420), B18(420,480), B19(480,540), B20(540,600), B21(600,660), B22(660,720);
    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final List<TimeBlock> CHRONOLOGICAL = Arrays.stream(values())
            .sorted(Comparator.comparingInt(TimeBlock::startMinute)).toList();
    private final int startMinute;
    private final int endMinute;
    TimeBlock(int startMinute, int endMinute) { this.startMinute = startMinute; this.endMinute = endMinute; }
    public int startMinute() { return startMinute; }
    public int endMinute() { return endMinute; }
    public int durationMinutes() { return endMinute - startMinute; }
    public static List<TimeBlock> chronological() { return CHRONOLOGICAL; }
    public Instant startsAt(LocalDate date) { return date.atStartOfDay(SEOUL).plusMinutes(startMinute).toInstant(); }
    public Instant endsAt(LocalDate date) { return date.atStartOfDay(SEOUL).plusMinutes(endMinute).toInstant(); }
    public String phase(LocalDate date, Instant now) {
        if (!now.isBefore(endsAt(date))) { return "ENDED"; }
        return now.isBefore(startsAt(date)) ? "FUTURE" : "IN_PROGRESS";
    }
    public int remainingMinutes(LocalDate date, Instant now) {
        Instant from = now.isAfter(startsAt(date)) ? now : startsAt(date);
        if (!from.isBefore(endsAt(date))) { return 0; }
        java.time.Duration remaining = java.time.Duration.between(from, endsAt(date));
        long roundedSeconds = remaining.getSeconds() + (remaining.getNano() > 0 ? 1 : 0);
        return (int) ((roundedSeconds + 59) / 60);
    }
    public static TimeBlock require(String code) {
        try { return TimeBlock.valueOf(code); }
        catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(400, "INVALID_BLOCK", "정해진 시간 블록을 선택해 주세요.");
        }
    }
}
