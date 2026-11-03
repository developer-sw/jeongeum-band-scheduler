package kr.jeongeum.scheduler.reservation.domain;

import java.time.LocalDate;

/** Rolling window in Seoul calendar dates, including both today and the last date. */
public final class BookingWindow {
    public static final int ADVANCE_DAYS = 30;
    public static final int DATE_COUNT = ADVANCE_DAYS + 1;

    private BookingWindow() {}

    public static LocalDate through(LocalDate today) {
        return today.plusDays(ADVANCE_DAYS);
    }
}
