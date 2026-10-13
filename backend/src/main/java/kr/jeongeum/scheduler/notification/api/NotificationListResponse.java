package kr.jeongeum.scheduler.notification.api;

import java.time.Instant;
import java.util.List;

public record NotificationListResponse(Instant serverTime,List<NotificationResponse> notifications,long unreadCount,String nextBeforeId) {}
