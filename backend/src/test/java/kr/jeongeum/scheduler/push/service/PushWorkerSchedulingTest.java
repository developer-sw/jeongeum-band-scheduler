package kr.jeongeum.scheduler.push.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class PushWorkerSchedulingTest {
    @Test void enabledDeliveryWorkerRegistersItsScheduleIndependentlyOfOtherWorkers() {
        new ApplicationContextRunner().withPropertyValues("jeongeum.push.worker.enabled=true","jeongeum.push.worker.delay-ms=100000")
                .withBean(PushDeliveryProcessor.class,()->mock(PushDeliveryProcessor.class)).withUserConfiguration(PushDeliveryWorker.class)
                .run(context -> {
                    assertNull(context.getStartupFailure()); assertEquals(1,context.getBeansOfType(PushDeliveryWorker.class).size());
                    var scheduler=context.getBean(ScheduledAnnotationBeanPostProcessor.class);
                    assertEquals(1,scheduler.getScheduledTasks().size());
                    verify(context.getBean(PushDeliveryProcessor.class),timeout(1500).atLeastOnce()).runOnce();
                });
    }
    @Test void deliveryWorkerIsAbsentByDefault() {
        new ApplicationContextRunner().withBean(PushDeliveryProcessor.class,()->mock(PushDeliveryProcessor.class)).withUserConfiguration(PushDeliveryWorker.class)
                .run(context -> { assertNull(context.getStartupFailure()); assertEquals(0,context.getBeansOfType(PushDeliveryWorker.class).size()); });
    }
    @Test void slowDeliveryDoesNotPreventOtherTasksOnTheSingleSpringScheduler() throws Exception {
        PushDeliveryProcessor processor=mock(PushDeliveryProcessor.class);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),otherTaskRan=new CountDownLatch(1);
        when(processor.runOnce()).thenAnswer(invocation -> {
            entered.countDown(); release.await(); return false;
        });
        new ApplicationContextRunner().withPropertyValues("jeongeum.push.worker.enabled=true","jeongeum.push.worker.delay-ms=10")
                .withBean(PushDeliveryProcessor.class,() -> processor)
                .withBean("taskScheduler",ThreadPoolTaskScheduler.class,() -> {
                    ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler(); scheduler.setPoolSize(1); return scheduler;
                })
                .withBean(OtherScheduledTask.class,() -> new OtherScheduledTask(entered,otherTaskRan))
                .withUserConfiguration(PushDeliveryWorker.class)
                .run(context -> {
                    try {
                        assertNull(context.getStartupFailure());
                        assertEquals(2,context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks().size());
                        assertTrue(entered.await(2,TimeUnit.SECONDS),"The slow delivery must actually start");
                        assertTrue(otherTaskRan.await(2,TimeUnit.SECONDS),"Other scheduled work must run before the delivery is released");
                        verify(processor,times(1)).runOnce();
                    } finally { release.countDown(); }
                });
    }
    @Test void schedulerReturnsWhileDeliveryIsBlockedAndRepeatedTicksDoNotQueueBatches() throws Exception {
        PushDeliveryProcessor processor=mock(PushDeliveryProcessor.class);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(processor.runOnce()).thenAnswer(invocation -> { entered.countDown(); release.await(); return false; });
        PushDeliveryWorker worker=new PushDeliveryWorker(processor);
        var scheduler=Executors.newSingleThreadExecutor();
        try {
            var firstTick=scheduler.submit(worker::deliver);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            firstTick.get(1,TimeUnit.SECONDS);
            for (int tick=0;tick<40;tick++) { scheduler.submit(worker::deliver).get(1,TimeUnit.SECONDS); }
            verify(processor,times(1)).runOnce();
            release.countDown();
            verify(processor,after(250).times(1)).runOnce();
        } finally {
            release.countDown(); worker.shutdown(); scheduler.shutdownNow();
        }
    }
    @Test void shutdownInterruptsInFlightDeliveryAndPreventsFurtherClaims() throws Exception {
        PushDeliveryProcessor processor=mock(PushDeliveryProcessor.class);
        CountDownLatch entered=new CountDownLatch(1),interrupted=new CountDownLatch(1),release=new CountDownLatch(1);
        when(processor.runOnce()).thenAnswer(invocation -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException shutdown) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return true;
        });
        PushDeliveryWorker worker=new PushDeliveryWorker(processor);
        try {
            worker.deliver(); assertTrue(entered.await(2,TimeUnit.SECONDS));
            worker.shutdown();
            assertTrue(interrupted.await(1,TimeUnit.SECONDS),"Shutdown must interrupt the provider call");
            worker.deliver(); worker.deliver();
            verify(processor,times(1)).runOnce();
        } finally { release.countDown(); worker.shutdown(); }
    }
    @Test void aFailedBatchDoesNotDisableSubsequentScheduledDelivery() throws Exception {
        PushDeliveryProcessor processor=mock(PushDeliveryProcessor.class);
        CountDownLatch recovered=new CountDownLatch(1);
        when(processor.runOnce()).thenThrow(new IllegalStateException("temporary failure")).thenAnswer(invocation -> {
            recovered.countDown(); return false;
        });
        PushDeliveryWorker worker=new PushDeliveryWorker(processor);
        var scheduler=Executors.newSingleThreadScheduledExecutor();
        try {
            scheduler.scheduleWithFixedDelay(worker::deliver,0,10,TimeUnit.MILLISECONDS);
            assertTrue(recovered.await(2,TimeUnit.SECONDS),"The next tick must recover after a failed batch");
            verify(processor,atLeast(2)).runOnce();
        } finally { scheduler.shutdownNow(); worker.shutdown(); }
    }
    static final class OtherScheduledTask {
        private final CountDownLatch deliveryEntered;
        private final CountDownLatch ran;
        OtherScheduledTask(CountDownLatch deliveryEntered,CountDownLatch ran) { this.deliveryEntered=deliveryEntered; this.ran=ran; }
        @Scheduled(fixedDelay=10)
        public void poll() { if (deliveryEntered.getCount()==0) { ran.countDown(); } }
    }
}
