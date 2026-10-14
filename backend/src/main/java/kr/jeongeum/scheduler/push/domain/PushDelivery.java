package kr.jeongeum.scheduler.push.domain;

import java.time.Instant;
import java.util.UUID;

/** Internal sender input; never logged or returned from HTTP APIs. */
public record PushDelivery(long jobId,UUID leaseToken,int attempts,long notificationId,long subscriptionId,long bindingVersion,
        String endpoint,String p256dh,String auth,String eventKey,String title,String body,String targetPath,
        Instant actualStartAt,String eventType) {
    @Override public String toString() { return "PushDelivery [jobId="+jobId+", subscriptionId="+subscriptionId+"]"; }
}
