package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import org.junit.jupiter.api.Test;

class TimeBlockTest {
    @Test
    void coversTheSeoulDayWithoutGapsAndPreservesExistingBlockCodes() {
        List<TimeBlock> blocks = TimeBlock.chronological();
        assertEquals(22, blocks.size());
        assertEquals(0, blocks.getFirst().startMinute());
        assertEquals(1440, blocks.getLast().endMinute());
        for (int index = 0; index < blocks.size(); index++) {
            TimeBlock block = blocks.get(index);
            assertEquals(index < 18 ? 60 : 90, block.durationMinutes());
            if (index > 0) { assertEquals(blocks.get(index - 1).endMinute(), block.startMinute()); }
        }
        assertEquals(TimeBlock.B11, blocks.getFirst());
        assertEquals(TimeBlock.B22, blocks.get(11));
        assertEquals(TimeBlock.B01, blocks.get(12));
        assertEquals(720, TimeBlock.B01.startMinute());
        assertEquals(1080, TimeBlock.B07.startMinute());
        assertEquals(1350, TimeBlock.B10.startMinute());
        assertThrows(UnsupportedOperationException.class, () -> blocks.clear());
        assertThrows(ApiException.class, () -> TimeBlock.require("B23"));
    }

    @Test
    void midnightIsTheStartOfItsSeoulDateAndPreviousNightEndsAtTheSameInstant() {
        LocalDate date = LocalDate.of(2026, 11, 5);
        Instant midnight = Instant.parse("2026-11-04T15:00:00Z");
        assertEquals(midnight, TimeBlock.B11.startsAt(date));
        assertEquals(midnight, TimeBlock.B10.endsAt(date.minusDays(1)));
        assertEquals("FUTURE", TimeBlock.B11.phase(date, midnight.minusNanos(1)));
        assertEquals("IN_PROGRESS", TimeBlock.B11.phase(date, midnight));
        assertEquals("ENDED", TimeBlock.B10.phase(date.minusDays(1), midnight));
        assertEquals(60, TimeBlock.B11.remainingMinutes(date, midnight));
        assertEquals(1, TimeBlock.B11.remainingMinutes(date, midnight.plusSeconds(3599)));
        assertEquals(0, TimeBlock.B11.remainingMinutes(date, midnight.plusSeconds(3600)));
    }
}
