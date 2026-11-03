package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import kr.jeongeum.scheduler.reservation.domain.BookingWindow;
import org.junit.jupiter.api.Test;

class BookingWindowTest {
    @Test
    void includesThirtyOneCalendarDatesAcrossMonthYearAndLeapDayBoundaries() {
        for (String[] example : new String[][] {
                {"2026-11-03", "2026-12-03"},
                {"2026-12-15", "2027-01-14"},
                {"2028-02-01", "2028-03-02"},
                {"2027-02-01", "2027-03-03"}
        }) {
            LocalDate today = LocalDate.parse(example[0]);
            LocalDate through = BookingWindow.through(today);
            assertEquals(LocalDate.parse(example[1]), through);
            assertEquals(31, ChronoUnit.DAYS.between(today, through) + 1);
        }
    }
}
