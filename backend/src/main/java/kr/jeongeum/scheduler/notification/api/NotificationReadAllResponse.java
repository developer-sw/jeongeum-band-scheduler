package kr.jeongeum.scheduler.notification.api;

import java.time.Instant;

public record NotificationReadAllResponse(Instant through,long readCount,long unreadCount) {}
