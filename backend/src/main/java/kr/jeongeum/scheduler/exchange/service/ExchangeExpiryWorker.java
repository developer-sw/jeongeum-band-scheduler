package kr.jeongeum.scheduler.exchange.service;

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
@ConditionalOnProperty(name="jeongeum.exchange.worker.enabled",havingValue="true",matchIfMissing=true)
public class ExchangeExpiryWorker {
    private static final Logger log=LoggerFactory.getLogger(ExchangeExpiryWorker.class);
    private final ExchangeService exchanges;
    public ExchangeExpiryWorker(ExchangeService exchanges) { this.exchanges=exchanges; }
    @EventListener(ApplicationReadyEvent.class)
    public void startup() { refresh(); }
    @Scheduled(fixedDelay=60000,initialDelay=60000)
    public void catchUp() { refresh(); }
    private void refresh() {
        try { exchanges.refreshPending(); }
        catch (RuntimeException failure) { log.warn("Exchange state refresh failed; the next interval will retry ({})",failure.getClass().getSimpleName()); }
    }
}
