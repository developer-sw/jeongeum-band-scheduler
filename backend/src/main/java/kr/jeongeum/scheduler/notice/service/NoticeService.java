package kr.jeongeum.scheduler.notice.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.notice.api.NoticeListResponse;
import kr.jeongeum.scheduler.notice.api.NoticeRequests;
import kr.jeongeum.scheduler.notice.api.NoticeResponse;
import kr.jeongeum.scheduler.notice.repository.NoticeRepository;
import kr.jeongeum.scheduler.notification.service.NotificationPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NoticeService {
    public record Result(NoticeResponse response,int status) {}
    private final NoticeRepository notices;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final NotificationPublisher publisher;
    private final Clock clock;
    private final TransactionTemplate reads,writes;
    public NoticeService(NoticeRepository notices,MemberAccess access,DirectoryMutationGuard guard,NotificationPublisher publisher,
            Clock clock,PlatformTransactionManager transactions) {
        this.notices=notices; this.access=access; this.guard=guard; this.publisher=publisher; this.clock=clock;
        reads=new TransactionTemplate(transactions); reads.setReadOnly(true); reads.setTimeout(10);
        writes=new TransactionTemplate(transactions); writes.setTimeout(10);
    }
    public NoticeListResponse list(long actor,String cursorInput,Integer limitInput) {
        NoticeInputs.Cursor cursor=NoticeInputs.cursor(cursorInput);
        int limit=limitInput==null?20:limitInput;
        if (limit<1 || limit>50) { throw ApiException.invalidInput(); }
        return reads.execute(status -> {
            access.active(actor);
            List<NoticeResponse> rows=notices.list(cursor==null?null:cursor.pinned(),cursor==null?null:cursor.createdAt(),cursor==null?null:cursor.id(),limit+1);
            List<NoticeResponse> page=List.copyOf(rows.subList(0,Math.min(rows.size(),limit)));
            return new NoticeListResponse(now(),page,rows.size()>limit?NoticeInputs.cursor(page.getLast()):null);
        });
    }
    public NoticeResponse get(long actor,long id) {
        return reads.execute(status -> {
            access.active(actor);
            NoticeResponse notice=notices.find(id).orElseThrow(DirectoryInputs::notFound);
            if (notice.deleted()) { throw DirectoryInputs.notFound(); }
            return notice;
        });
    }
    public Result create(long actor,String key,NoticeRequests.Create input) {
        if (input==null) { throw ApiException.invalidInput(); }
        String digest=NoticeInputs.key(key),title=DirectoryInputs.text(input.title(),120,true),body=NoticeInputs.body(input.body());
        boolean pinned=NoticeInputs.pinned(input.pinned());
        String route="/api/v1/notices",fingerprint=NoticeInputs.fingerprint("POST",route,0,title,body,pinned);
        return writes.execute(status -> {
            guard.lock(); access.administratorForMutation(actor);
            NoticeRepository.Replay replay=notices.replay(actor,digest).orElse(null);
            if (replay!=null) { return replay(replay,fingerprint); }
            Instant now=now(); NoticeResponse notice=notices.insert(actor,title,body,pinned,now);
            if (pinned) { publisher.importantNotice(notice,now); }
            notices.remember(actor,digest,fingerprint,"POST",route,201,Long.parseLong(notice.id()),now);
            return new Result(notice,201);
        });
    }
    public NoticeResponse update(long actor,long id,String key,NoticeRequests.Update input) {
        if (id<=0 || input==null) { throw ApiException.invalidInput(); }
        String digest=NoticeInputs.key(key),title=DirectoryInputs.text(input.title(),120,true),body=NoticeInputs.body(input.body());
        long version=DirectoryInputs.version(input.expectedVersion()); boolean pinned=NoticeInputs.pinned(input.pinned());
        String route="/api/v1/notices/"+id,fingerprint=NoticeInputs.fingerprint("PATCH",route,version,title,body,pinned);
        return writes.execute(status -> {
            guard.lock(); access.administratorForMutation(actor);
            NoticeRepository.Replay replay=notices.replay(actor,digest).orElse(null);
            if (replay!=null) { return replay(replay,fingerprint).response(); }
            NoticeResponse current=notices.lock(id).orElseThrow(DirectoryInputs::notFound);
            requireVisible(current); DirectoryInputs.sameVersion(current.version(),version);
            Instant now=now(); NoticeResponse updated=notices.update(id,title,body,pinned,now);
            notices.remember(actor,digest,fingerprint,"PATCH",route,200,id,now);
            return updated;
        });
    }
    public NoticeResponse delete(long actor,long id,String key,NoticeRequests.Delete input) {
        if (id<=0 || input==null) { throw ApiException.invalidInput(); }
        String digest=NoticeInputs.key(key); long version=DirectoryInputs.version(input.expectedVersion());
        String route="/api/v1/notices/"+id,fingerprint=NoticeInputs.fingerprint("DELETE",route,version,"","",false);
        return writes.execute(status -> {
            guard.lock(); access.administratorForMutation(actor);
            NoticeRepository.Replay replay=notices.replay(actor,digest).orElse(null);
            if (replay!=null) { return replay(replay,fingerprint).response(); }
            NoticeResponse current=notices.lock(id).orElseThrow(DirectoryInputs::notFound);
            requireVisible(current); DirectoryInputs.sameVersion(current.version(),version);
            Instant now=now(); NoticeResponse deleted=notices.delete(id,now);
            notices.remember(actor,digest,fingerprint,"DELETE",route,200,id,now);
            return deleted;
        });
    }
    private Result replay(NoticeRepository.Replay replay,String fingerprint) {
        if (!replay.fingerprint().equals(fingerprint)) {
            throw new ApiException(409,"IDEMPOTENCY_KEY_REUSED","같은 요청 키를 다른 작업에 사용할 수 없습니다.");
        }
        return new Result(notices.find(replay.noticeId()).orElseThrow(DirectoryInputs::notFound),replay.status());
    }
    private static void requireVisible(NoticeResponse current) {
        if (current.deleted()) { throw new ApiException(409,"NOTICE_DELETED","삭제된 공지입니다. 최신 목록을 확인해 주세요."); }
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
