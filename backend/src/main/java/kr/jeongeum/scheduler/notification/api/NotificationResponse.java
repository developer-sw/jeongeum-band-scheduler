package kr.jeongeum.scheduler.notification.api;

import java.time.Instant;

public record NotificationResponse(String id, String eventType, String title, String body, String targetPath,
        Instant createdAt, Instant readAt) {}
