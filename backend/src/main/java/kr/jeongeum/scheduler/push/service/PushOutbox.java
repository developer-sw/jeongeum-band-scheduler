package kr.jeongeum.scheduler.push.service;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Each device delivery is inserted in the same transaction as its inbox event. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class PushOutbox {
    private final JdbcTemplate jdbc;
    public PushOutbox(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public void enqueue(long notificationId,Instant now) {
        jdbc.update("""
                INSERT INTO notification_jobs(job_kind,job_key,notification_id,subscription_id,subscription_binding_version,
                    reservation_id,expected_version,actual_start_at,scheduled_at,next_attempt_at,created_at,updated_at)
                SELECT 'PUSH_DELIVERY','push:'||n.id||':'||s.id||':'||s.binding_version,n.id,s.id,s.binding_version,
                    r.id,r.version,r.block_start_at,?,?,?,?
                FROM notifications n JOIN push_subscriptions s ON s.user_id=n.user_id AND s.active
                    JOIN users u ON u.id=n.user_id AND u.status='ACTIVE' LEFT JOIN reservations r ON r.id=n.reservation_id
                WHERE n.id=? ON CONFLICT(job_key) DO NOTHING
                """,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now),notificationId);
    }
}
