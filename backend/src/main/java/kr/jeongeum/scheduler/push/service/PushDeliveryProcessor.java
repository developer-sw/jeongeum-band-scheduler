package kr.jeongeum.scheduler.push.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.push.domain.PushDelivery;
import kr.jeongeum.scheduler.push.repository.PushDeliveryRepository;
import kr.jeongeum.scheduler.push.repository.PushSubscriptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PushDeliveryProcessor {
    private final PushDeliveryRepository deliveries;
    private final PushSubscriptionRepository subscriptions;
    private final PushSender sender;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final TransactionTemplate transactions;
    public PushDeliveryProcessor(PushDeliveryRepository deliveries,PushSubscriptionRepository subscriptions,PushSender sender,
            DirectoryMutationGuard guard,Clock clock,PlatformTransactionManager manager) {
        this.deliveries=deliveries; this.subscriptions=subscriptions; this.sender=sender; this.guard=guard; this.clock=clock;
        transactions=new TransactionTemplate(manager); transactions.setTimeout(10);
    }
    /** Network occurs after the claim/permission transaction commits; booking mutexes are never held across it. */
    public boolean runOnce() {
        if (!sender.enabled()) { return false; }
        UUID lease=UUID.randomUUID();
        Claim claimed=transactions.execute(status -> {
            guard.lock(); Instant now=now(); var id=deliveries.claim(now,lease,now.plusSeconds(45));
            if (id.isEmpty()) { return new Claim(false,Optional.empty()); }
            var valid=deliveries.valid(id.get(),lease,now);
            if (valid.isEmpty()) { deliveries.cancel(id.get(),lease,now,"DELIVERY_NO_LONGER_VALID"); }
            return new Claim(true,valid);
        });
        if (claimed==null || !claimed.exists()) { return false; }
        if (claimed.delivery().isEmpty()) { return true; }
        PushDelivery delivery=claimed.delivery().get(); PushSender.Result result;
        try { result=sender.send(delivery); }
        catch (RuntimeException failure) { result=PushSender.Result.transientFailure("PUSH_SENDER_UNAVAILABLE"); }
        PushSender.Result outcome=result;
        transactions.executeWithoutResult(status -> {
            guard.lock(); Instant now=now();
            // Logout/rebinding/cancellation while the external request was in flight cannot resurrect the job.
            if (deliveries.valid(delivery.jobId(),lease,now).isEmpty()) {
                deliveries.cancel(delivery.jobId(),lease,now,"DELIVERY_NO_LONGER_VALID"); return;
            }
            int code=outcome.status();
            if (code>=200 && code<300) { deliveries.finish(delivery.jobId(),lease,"SENT",null,now,now); }
            else if (code==404 || code==410) {
                deliveries.finish(delivery.jobId(),lease,"FAILED","ENDPOINT_EXPIRED",now,now);
                subscriptions.expireBinding(delivery.subscriptionId(),delivery.bindingVersion(),now);
            } else if ((code==0 || code==408 || code==429 || code>=500) && delivery.attempts()<8) {
                long backoff=Math.min(3600,30L << Math.min(7,delivery.attempts()-1));
                long seconds=outcome.retryAfter()==null?backoff:Math.max(backoff,Math.min(3600,Math.max(1,outcome.retryAfter().toSeconds())));
                Instant next=now.plusSeconds(seconds);
                if ("START_REMINDER".equals(delivery.eventType()) && !next.isBefore(delivery.actualStartAt())) {
                    deliveries.cancel(delivery.jobId(),lease,now,"REMINDER_WINDOW_ELAPSED");
                } else { deliveries.finish(delivery.jobId(),lease,"RETRY_WAIT",outcome.errorCode(),next,now); }
            } else { deliveries.finish(delivery.jobId(),lease,"FAILED",delivery.attempts()>=8?"ATTEMPTS_EXHAUSTED":outcome.errorCode(),now,now); }
        });
        return true;
    }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
    private record Claim(boolean exists,Optional<PushDelivery> delivery) {}
}
