package kr.jeongeum.scheduler.push.service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.stereotype.Component;

@Component
@Profile("!bootstrap-admin")
@ConditionalOnProperty(name="jeongeum.push.worker.enabled",havingValue="true")
@EnableScheduling
public class PushDeliveryWorker {
    private static final Logger LOG=LoggerFactory.getLogger(PushDeliveryWorker.class);
    private final PushDeliveryProcessor processor;
    private final AtomicBoolean batchRunning=new AtomicBoolean();
    private final AtomicBoolean stopping=new AtomicBoolean();
    private final ExecutorService deliveryExecutor=Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task,"jeongeum-push-delivery");
        thread.setDaemon(true);
        return thread;
    });
    public PushDeliveryWorker(PushDeliveryProcessor processor) { this.processor=processor; }
    @Scheduled(fixedDelayString="${jeongeum.push.worker.delay-ms:1000}")
    public void deliver() {
        // External provider latency must not occupy the scheduler used by date opening and reminders.
        if (stopping.get() || !batchRunning.compareAndSet(false,true)) { return; }
        try { deliveryExecutor.execute(this::runBatch); }
        catch (RejectedExecutionException rejected) {
            batchRunning.set(false);
            if (!stopping.get()) { LOG.warn("Push worker submission deferred; delivery executor unavailable"); }
        }
    }
    private void runBatch() {
        try {
            for (int count=0;count<20 && !stopping.get() && !Thread.currentThread().isInterrupted();count++) {
                if (!processor.runOnce()) { break; }
            }
        }
        catch (RuntimeException transientFailure) { LOG.warn("Push worker iteration deferred; database or sender temporarily unavailable"); }
        finally { batchRunning.set(false); }
    }
    @PreDestroy
    public void shutdown() {
        stopping.set(true);
        deliveryExecutor.shutdownNow();
        // The sender restores its interrupt flag; a claim left by an interrupted DB write is recovered by its lease.
        try {
            if (!deliveryExecutor.awaitTermination(5,TimeUnit.SECONDS)) {
                LOG.warn("Push worker shutdown timed out; outstanding claim will recover after lease expiry");
            }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
