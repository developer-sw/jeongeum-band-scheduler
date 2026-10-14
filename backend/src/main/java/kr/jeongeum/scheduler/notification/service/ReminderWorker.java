package kr.jeongeum.scheduler.notification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods=false)
@EnableScheduling
@Profile("!bootstrap-admin")
@ConditionalOnProperty(name="jeongeum.reminder.worker.enabled",havingValue="true")
public class ReminderWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReminderWorker.class);
    private final ReminderProcessor processor;
    public ReminderWorker(ReminderProcessor processor) { this.processor=processor; }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { poll(); }
    @Scheduled(fixedDelayString="${jeongeum.reminder.worker.delay-ms:15000}",initialDelay=15000)
    public void poll() {
        try { processor.recoverFuturePlans(); processor.drain(50); }
        catch(RuntimeException temporaryFailure) { LOG.warn("Reminder worker iteration deferred; database temporarily unavailable"); }
    }
}
