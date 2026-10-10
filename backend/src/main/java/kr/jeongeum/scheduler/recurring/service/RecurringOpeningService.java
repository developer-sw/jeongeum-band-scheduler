package kr.jeongeum.scheduler.recurring.service;

import java.time.Clock;
import java.time.LocalDate;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;

/** No caller holds a reservation transaction while acquiring the independent date-opening transaction. */
@Service
public class RecurringOpeningService {
    private static final Logger LOG = LoggerFactory.getLogger(RecurringOpeningService.class);
    private final RecurringMaterializer materializer;
    private final Clock clock;
    public RecurringOpeningService(RecurringMaterializer materializer, Clock clock) {
        this.materializer = materializer; this.clock = clock;
    }
    public void openBookableWindow() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), TimeBlock.SEOUL);
        prepareRange(today, today.plusDays(14));
    }
    public void openDate(LocalDate date) { prepareDate(date); }
    public void prepareRange(LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), TimeBlock.SEOUL);
        LocalDate first = from.isBefore(today) ? today : from;
        LocalDate last = to.isAfter(today.plusDays(14)) ? today.plusDays(14) : to;
        for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) { prepareDate(date); }
    }
    public boolean prepareDate(LocalDate date) {
        try { materializer.openDate(date); return true; }
        catch (ApiException | DataAccessException | TransactionException exception) {
            // Do not log SQL, member identities, cookies, credentials, or conflict payloads.
            LOG.warn("예약 날짜 준비를 완료하지 못했습니다. 다음 요청 또는 정기 실행에서 다시 확인합니다.");
            return false;
        }
    }
}
