package kr.jeongeum.scheduler.notification.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.notification.api.NotificationListResponse;
import kr.jeongeum.scheduler.notification.api.NotificationReadAllResponse;
import kr.jeongeum.scheduler.notification.api.NotificationRequests;
import kr.jeongeum.scheduler.notification.api.NotificationResponse;
import kr.jeongeum.scheduler.notification.repository.NotificationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NotificationService {
    private final NotificationRepository notifications;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final TransactionTemplate reads,writes;
    public NotificationService(NotificationRepository notifications,MemberAccess access,DirectoryMutationGuard guard,Clock clock,
            PlatformTransactionManager transactions) {
        this.notifications=notifications; this.access=access; this.guard=guard; this.clock=clock;
        reads=new TransactionTemplate(transactions); reads.setReadOnly(true); reads.setTimeout(10);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        writes=new TransactionTemplate(transactions); writes.setTimeout(10);
    }
    public NotificationListResponse list(long actor,String beforeInput,boolean unreadOnly,Integer limitInput) {
        Long before=DirectoryInputs.optionalId(beforeInput); int limit=limitInput==null?20:limitInput;
        if (limit<1 || limit>50) { throw ApiException.invalidInput(); }
        return reads.execute(status -> {
            access.active(actor);
            List<NotificationResponse> rows=notifications.list(actor,before,unreadOnly,limit+1);
            List<NotificationResponse> page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
            return new NotificationListResponse(now(),page,notifications.unreadCount(actor),rows.size()>limit?page.getLast().id():null);
        });
    }
    public NotificationResponse read(long actor,long id) {
        return writes.execute(status -> {
            guard.lock(); access.activeForMutation(actor);
            return notifications.markRead(actor,id,now()).orElseThrow(DirectoryInputs::notFound);
        });
    }
    public NotificationReadAllResponse readAll(long actor,NotificationRequests.ReadAll input) {
        if (input==null || input.through()==null) { throw ApiException.invalidInput(); }
        return writes.execute(status -> {
            guard.lock(); access.activeForMutation(actor); Instant now=now(),through=input.through();
            if (through.isAfter(now) || through.isBefore(Instant.parse("0001-01-01T00:00:00Z"))) { throw ApiException.invalidInput(); }
            // PostgreSQL stores microseconds: floor a finer client cutoff rather than round it into a newer event.
            through=through.truncatedTo(ChronoUnit.MICROS);
            int count=notifications.markReadThrough(actor,through,now);
            return new NotificationReadAllResponse(through,count,notifications.unreadCount(actor));
        });
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
