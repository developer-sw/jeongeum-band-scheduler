package kr.jeongeum.scheduler.pushruntime;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import kr.jeongeum.scheduler.JeongeumSchedulerApplication;
import kr.jeongeum.scheduler.push.service.PushSender;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** Test-classpath-only application: database-controlled time and a sender that never uses a network. */
public final class PushWorkerTestApplication {
    private PushWorkerTestApplication() { }

    public static void main(String[] args) {
        String database = System.getenv("APP_DB_URL");
        if (database == null || !database.matches("jdbc:postgresql://[^/]+/push_worker_runtime_test(?:\\?.*)?")) {
            throw new IllegalStateException("The isolated worker launcher requires its disposable runtime test database");
        }
        try (var context = new SpringApplicationBuilder(JeongeumSchedulerApplication.class, RuntimeConfiguration.class)
                .profiles("local", "push-worker-runtime-test").run(args)) {
            context.getBean(JdbcTemplate.class).update("UPDATE runtime_worker_registration SET ready=true WHERE pid=?",
                    ProcessHandle.current().pid());
            // Keep this independently launched JVM alive until the parent sends a normal process stop.
            try { new java.util.concurrent.CountDownLatch(1).await(); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile("push-worker-runtime-test")
    public static class RuntimeConfiguration {
        @Bean
        @Primary
        Clock runtimeControlledClock(JdbcTemplate jdbc) {
            return new Clock() {
                @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() {
                    return jdbc.queryForObject("SELECT controlled_at FROM runtime_worker_clock WHERE id=1",
                            (row, index) -> row.getTimestamp(1).toInstant());
                }
            };
        }

        @Bean
        @Primary
        PushSender runtimeNoNetworkSender(JdbcTemplate jdbc) {
            return new PushSender() {
                @Override public boolean enabled() { return true; }
                @Override public Result send(kr.jeongeum.scheduler.push.domain.PushDelivery delivery) {
                    // No subscription endpoint, keys, cookie, body, or credential is recorded.
                    jdbc.update("INSERT INTO runtime_worker_calls(job_id,notification_id,pid) VALUES (?,?,?)",
                            delivery.jobId(), delivery.notificationId(), ProcessHandle.current().pid());
                    return Result.response(201, null);
                }
            };
        }

        @Bean
        ApplicationRunner recordActualSchedulerRegistration(JdbcTemplate jdbc,
                ScheduledAnnotationBeanPostProcessor scheduling) {
            return arguments -> {
                long reminders = scheduling.getScheduledTasks().stream()
                        .filter(task -> task.getTask().toString().equals(
                                "kr.jeongeum.scheduler.notification.service.ReminderWorker.poll")).count();
                long deliveries = scheduling.getScheduledTasks().stream()
                        .filter(task -> task.getTask().toString().equals(
                                "kr.jeongeum.scheduler.push.service.PushDeliveryWorker.deliver")).count();
                jdbc.update("INSERT INTO runtime_worker_registration(pid,reminder_tasks,delivery_tasks,ready) VALUES (?,?,?,false)",
                        ProcessHandle.current().pid(), reminders, deliveries);
            };
        }
    }
}
