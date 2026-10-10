package kr.jeongeum.scheduler.recurring.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Profile("!bootstrap-admin")
@ConditionalOnProperty(name = "jeongeum.recurring.worker.enabled", havingValue = "true", matchIfMissing = true)
public class RecurringOpeningWorker {
    private final RecurringOpeningService opening;
    public RecurringOpeningWorker(RecurringOpeningService opening) { this.opening = opening; }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { opening.openBookableWindow(); }
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    public void midnight() { opening.openBookableWindow(); }
    @Scheduled(fixedDelay = 60000, initialDelay = 60000)
    public void catchUp() { opening.openBookableWindow(); }
}
