package kr.jeongeum.scheduler.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.jeongeum.scheduler.push.domain.PushDelivery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PushDeliveryRepository {
    private final JdbcTemplate jdbc;
    public PushDeliveryRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Long> claim(Instant now,UUID token,Instant leaseUntil) {
        jdbc.update("UPDATE notification_jobs SET status='FAILED',last_error_code='ATTEMPTS_EXHAUSTED',updated_at=?,lease_token=NULL,lease_expires_at=NULL WHERE job_kind='PUSH_DELIVERY' AND attempts>=8 AND status='PROCESSING' AND lease_expires_at<=?",Timestamp.from(now),Timestamp.from(now));
        return jdbc.queryForList("""
                UPDATE notification_jobs SET status='PROCESSING',attempts=attempts+1,lease_token=?,lease_expires_at=?,updated_at=?
                WHERE id=(SELECT id FROM notification_jobs WHERE job_kind='PUSH_DELIVERY' AND attempts<8
                    AND ((status IN ('PENDING','RETRY_WAIT') AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_expires_at<=?))
                    ORDER BY next_attempt_at,id FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING id
                """,Long.class,token,Timestamp.from(leaseUntil),Timestamp.from(now),Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public Optional<PushDelivery> valid(long job,UUID token,Instant now) {
        return jdbc.query("""
                SELECT j.id,j.attempts,j.notification_id,j.subscription_id,j.subscription_binding_version,j.actual_start_at,
                    s.endpoint,s.p256dh,s.auth,n.event_key,n.event_type,n.title,n.body,n.target_path
                FROM notification_jobs j JOIN notifications n ON n.id=j.notification_id
                    JOIN users u ON u.id=n.user_id AND u.status='ACTIVE'
                    JOIN push_subscriptions s ON s.id=j.subscription_id AND s.user_id=n.user_id AND s.active
                        AND s.binding_version=j.subscription_binding_version
                    LEFT JOIN reservations r ON r.id=n.reservation_id
                    LEFT JOIN announcements a ON a.id=n.announcement_id
                    LEFT JOIN exchange_requests e ON e.id=n.exchange_request_id
                WHERE j.id=? AND j.status='PROCESSING' AND j.lease_token=? AND j.lease_expires_at>?
                    AND (n.announcement_id IS NULL OR a.deleted_at IS NULL)
                    AND (n.reservation_id IS NULL OR (r.owner_type='PERSONAL' AND r.owner_user_id=n.user_id)
                        OR (r.owner_type='TEAM' AND EXISTS(SELECT 1 FROM teams t JOIN team_memberships m ON m.team_id=t.id AND m.status='ACTIVE'
                            JOIN roster_entries re ON re.id=m.roster_entry_id WHERE t.id=r.team_id AND t.status='ACTIVE' AND re.user_id=n.user_id)))
                    AND (n.event_type NOT IN ('RESERVATION_CREATED','RESERVATION_CHANGED','START_REMINDER')
                        OR (r.status='CONFIRMED' AND r.version=j.expected_version AND r.block_start_at=j.actual_start_at))
                    AND (n.event_type<>'START_REMINDER' OR r.block_start_at>?)
                    AND (n.event_type<>'EXCHANGE_REQUESTED' OR (e.status='PENDING' AND e.expires_at>?))
                    AND (n.exchange_request_id IS NULL OR (e.requester_user_id=n.user_id AND u.global_role='GLOBAL_ADMIN') OR EXISTS(
                        SELECT 1 FROM exchange_request_items x JOIN reservations xr ON xr.id=x.reservation_id
                        WHERE x.exchange_request_id=e.id AND ((xr.owner_type='PERSONAL' AND xr.owner_user_id=n.user_id)
                        OR (xr.owner_type='TEAM' AND EXISTS(SELECT 1 FROM teams xt JOIN team_memberships xm ON xm.team_id=xt.id AND xm.status='ACTIVE'
                            JOIN roster_entries xe ON xe.id=xm.roster_entry_id WHERE xt.id=xr.team_id AND xt.status='ACTIVE' AND xe.user_id=n.user_id)))))
                """,(r,i) -> new PushDelivery(r.getLong("id"),token,r.getInt("attempts"),r.getLong("notification_id"),r.getLong("subscription_id"),
                        r.getLong("subscription_binding_version"),r.getString("endpoint"),r.getString("p256dh"),r.getString("auth"),r.getString("event_key"),
                        r.getString("title"),r.getString("body"),r.getString("target_path"),r.getTimestamp("actual_start_at")==null?null:r.getTimestamp("actual_start_at").toInstant(),r.getString("event_type")),
                job,token,Timestamp.from(now),Timestamp.from(now),Timestamp.from(now)).stream().findFirst();
    }
    public void cancel(long id,UUID lease,Instant now,String error) {
        jdbc.update("UPDATE notification_jobs SET status='CANCELED',canceled_at=?,last_error_code=?,updated_at=?,lease_token=NULL,lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND lease_token=?",
                Timestamp.from(now),error,Timestamp.from(now),id,lease);
    }
    public boolean finish(long id,UUID lease,String state,String error,Instant next,Instant now) {
        return jdbc.update("UPDATE notification_jobs SET status=?,last_error_code=?,next_attempt_at=?,updated_at=?,sent_at=CASE WHEN ?='SENT' THEN ? ELSE sent_at END,lease_token=NULL,lease_expires_at=NULL WHERE id=? AND status='PROCESSING' AND lease_token=?",
                state,error,Timestamp.from(next),Timestamp.from(now),state,Timestamp.from(now),id,lease)>0;
    }
}
