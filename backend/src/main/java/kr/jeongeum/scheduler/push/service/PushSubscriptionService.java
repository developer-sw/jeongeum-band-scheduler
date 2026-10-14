package kr.jeongeum.scheduler.push.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.push.api.PushRequests;
import kr.jeongeum.scheduler.push.api.PushSubscriptionResponse;
import kr.jeongeum.scheduler.push.repository.PushSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PushSubscriptionService {
    private final PushSubscriptionRepository subscriptions;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final TransactionTemplate writes,reads;
    public PushSubscriptionService(PushSubscriptionRepository subscriptions,MemberAccess access,DirectoryMutationGuard guard,Clock clock,PlatformTransactionManager transactions) {
        this.subscriptions=subscriptions; this.access=access; this.guard=guard; this.clock=clock;
        writes=new TransactionTemplate(transactions); writes.setTimeout(10); reads=new TransactionTemplate(transactions); reads.setReadOnly(true); reads.setTimeout(10);
    }
    public List<PushSubscriptionResponse> list(long owner,String sessionId) {
        String digest=PushInputs.sessionDigest(sessionId);
        return reads.execute(status -> { access.active(owner); return subscriptions.list(owner,digest); });
    }
    public PushSubscriptionResponse register(long owner,String sessionId,PushRequests.Register request) {
        PushInputs.Validated input=PushInputs.validate(request); String digest=PushInputs.sessionDigest(sessionId);
        return writes.execute(status -> {
            guard.lock(); access.activeForMutation(owner); Instant now=now();
            if (!subscriptions.currentSession(owner,sessionId,now)) {
                throw new ApiException(401,"AUTHENTICATION_REQUIRED","로그인이 필요합니다.");
            }
            var old=subscriptions.lockEndpoint(input.endpoint());
            if (old.isEmpty()) {
                requireCapacity(owner); return subscriptions.insert(owner,digest,input,now).view(digest);
            }
            var previous=old.get();
            if (previous.owner()!=owner && (!equal(previous.auth(),input.auth()) || !equal(previous.p256dh(),input.p256dh()))) {
                throw new ApiException(409,"PUSH_SUBSCRIPTION_CONFLICT","이 기기의 알림 연결을 해제한 뒤 다시 시도해 주세요.");
            }
            if (!previous.active() || previous.owner()!=owner) { requireCapacity(owner); }
            boolean rebind=previous.owner()!=owner || !previous.active() || !previous.sessionDigest().equals(digest)
                    || !equal(previous.auth(),input.auth()) || !equal(previous.p256dh(),input.p256dh());
            if (rebind) { subscriptions.cancelJobs(previous.id(),now); }
            return subscriptions.update(previous,owner,digest,input,now,rebind).view(digest);
        });
    }
    public void delete(long owner,long id) {
        writes.executeWithoutResult(status -> { guard.lock(); access.activeForMutation(owner);
            if (!subscriptions.deactivateOwn(owner,id,now())) { throw DirectoryInputs.notFound(); }
        });
    }
    public void logout(long owner,String sessionId) {
        if (sessionId==null) { return; }
        String digest=PushInputs.sessionDigest(sessionId);
        writes.executeWithoutResult(status -> {
            guard.lock(); subscriptions.deactivateSession(owner,digest,now());
            // Revocation and Push shutdown commit together. A request authenticated before logout
            // must not reactivate a binding after it finally acquires the directory guard.
            subscriptions.deleteSession(owner,sessionId);
        });
    }
    private void requireCapacity(long owner) {
        if (subscriptions.activeCount(owner)>=20) { throw new ApiException(409,"PUSH_DEVICE_LIMIT","등록된 알림 기기 중 사용하지 않는 기기를 해제해 주세요."); }
    }
    private static boolean equal(String first,String second) { return MessageDigest.isEqual(first.getBytes(StandardCharsets.US_ASCII),second.getBytes(StandardCharsets.US_ASCII)); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
