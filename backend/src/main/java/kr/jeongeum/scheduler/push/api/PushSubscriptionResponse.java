package kr.jeongeum.scheduler.push.api;

import java.time.Instant;

/** No endpoint, browser secret, session digest or VAPID private key crosses the API boundary. */
public record PushSubscriptionResponse(String id,String deviceName,boolean active,boolean currentDevice,
        String bindingVersion,Instant createdAt,Instant updatedAt) {}
