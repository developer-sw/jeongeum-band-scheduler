package kr.jeongeum.scheduler.notification.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.notification.repository.NotificationRepository;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Only DB work occurs here. The directory guard serializes reminder materialization with business changes. */
@Service
public class ReminderProcessor {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private final JdbcTemplate jdbc;
    private final DirectoryMutationGuard guard;
    private final ReservationRepository reservations;
    private final NotificationRepository notifications;
    private final ReminderPlans plans;
    private final Clock clock;
    private final TransactionTemplate transaction;
    public ReminderProcessor(JdbcTemplate jdbc, DirectoryMutationGuard guard, ReservationRepository reservations,
            NotificationRepository notifications, ReminderPlans plans, Clock clock, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.guard=guard; this.reservations=reservations; this.notifications=notifications; this.clock=clock;
        this.plans=plans;
        this.transaction=new TransactionTemplate(manager); this.transaction.setTimeout(10);
    }

    /** After first rollout, existing future bookings gain plans only; no historical CREATED events or late plans. */
    public int recoverFuturePlans() {
        List<Long> ids=jdbc.queryForList("""
                SELECT r.id FROM reservations r WHERE r.status='CONFIRMED' AND r.block_start_at>?
                AND NOT EXISTS(SELECT 1 FROM notification_jobs j WHERE j.job_kind='REMINDER_PLAN' AND j.reservation_id=r.id)
                ORDER BY r.id LIMIT 200
                """,
                Long.class,Timestamp.from(clock.instant().plusSeconds(3600)));
        for(long id:ids) {
            transaction.executeWithoutResult(status -> {
                guard.lock();
                reservations.find(id).ifPresent(reservation -> plans.schedule(reservation,clock.instant()));
            });
        }
        return ids.size();
    }

    public int drain(int limit) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("Invalid reminder batch size"); }
        Instant now=clock.instant();
        List<Long> ids=jdbc.queryForList("""
                SELECT id FROM notification_jobs WHERE job_kind='REMINDER_PLAN' AND next_attempt_at<=?
                AND (status IN ('PENDING','RETRY_WAIT') OR (status='PROCESSING' AND lease_expires_at<=?))
                ORDER BY next_attempt_at,id LIMIT ?
                """,Long.class,Timestamp.from(now),Timestamp.from(now),limit);
        int processed=0;
        for(long id:ids) {
            try { if(Boolean.TRUE.equals(transaction.execute(status -> process(id)))) { processed++; } }
            catch(RuntimeException error) {
                // Redacted classification only; preserve the original transaction and retry after a bounded delay.
                transaction.executeWithoutResult(status -> {
                    guard.lock();
                    jdbc.update("""
                            UPDATE notification_jobs SET status=CASE WHEN attempts>=5 THEN 'FAILED' ELSE 'RETRY_WAIT' END,
                            attempts=attempts+1,next_attempt_at=?,updated_at=?,last_error_code='REMINDER_PROCESSING_FAILED',
                            lease_token=NULL,lease_expires_at=NULL WHERE id=? AND status IN ('PENDING','RETRY_WAIT','PROCESSING')
                            """,Timestamp.from(clock.instant().plusSeconds(30)),Timestamp.from(clock.instant()),id);
                });
            }
        }
        return processed;
    }

    private boolean process(long id) {
        guard.lock();
        Instant now=clock.instant();
        var rows=jdbc.queryForList("""
                SELECT reservation_id,expected_version,job_key,actual_start_at FROM notification_jobs WHERE id=?
                AND job_kind='REMINDER_PLAN' AND next_attempt_at<=?
                AND (status IN ('PENDING','RETRY_WAIT') OR (status='PROCESSING' AND lease_expires_at<=?)) FOR UPDATE
                """,id,Timestamp.from(now),Timestamp.from(now));
        if(rows.isEmpty()) { return false; }
        var job=rows.getFirst();
        long reservationId=((Number)job.get("reservation_id")).longValue();
        var reservation=reservations.find(reservationId).orElse(null);
        boolean valid=reservation!=null && "CONFIRMED".equals(reservation.status())
                && reservation.version()==((Number)job.get("expected_version")).longValue()
                && reservation.startsAt().equals(((Timestamp)job.get("actual_start_at")).toInstant())
                && now.isBefore(reservation.startsAt());
        if(!valid) { cancel(id,now,"STALE_RESERVATION"); return true; }
        boolean active=Boolean.TRUE.equals("PERSONAL".equals(reservation.ownerType())
                ? jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users WHERE id=? AND status='ACTIVE')",Boolean.class,reservation.ownerUserId())
                : jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM teams WHERE id=? AND status='ACTIVE')",Boolean.class,reservation.teamId()));
        if(!active) { cancel(id,now,"SUBJECT_INACTIVE"); return true; }
        List<Long> recipients="PERSONAL".equals(reservation.ownerType())
                ? notifications.activePersonal(reservation.ownerUserId()) : notifications.activeTeam(reservation.teamId());
        String key="reminder-job:"+id;
        for(long user:recipients) {
            notifications.insert(user,key,"START_REMINDER",null,reservation.id(),null,null,
                    "합주실 이용이 곧 시작돼요",WHEN.format(reservation.startsAt())+" · 예약 시간을 확인해 주세요.",
                    "/reservations?reservationId="+reservation.id(),now);
        }
        jdbc.update("""
                UPDATE notification_jobs SET status='COMPLETED',updated_at=?,lease_token=NULL,lease_expires_at=NULL,last_error_code=NULL WHERE id=?
                """,Timestamp.from(now),id);
        return true;
    }
    private void cancel(long id,Instant now,String reason) {
        jdbc.update("UPDATE notification_jobs SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code=?,lease_token=NULL,lease_expires_at=NULL WHERE id=?",
                Timestamp.from(now),Timestamp.from(now),reason,id);
    }
}
