package kr.jeongeum.scheduler.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Durable plans belong to the reservation transaction. A new row ID is not a new logical reminder. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ReminderPlans {
    private final JdbcTemplate jdbc;
    public ReminderPlans(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void schedule(Reservation reservation, Instant now) {
        Instant due = reservation.startsAt().minusSeconds(3600);
        if (!"CONFIRMED".equals(reservation.status()) || !due.isAfter(now)) { return; }
        List<Long> roots = roots(reservation.id());
        String array = array(roots);
        String identity = reservation.ownerType() + ":" + (reservation.ownerUserId() == null ? reservation.teamId() : reservation.ownerUserId());
        String key = "reminder:" + identity + ":" + reservation.startsAt().getEpochSecond() + ":" + digest(array);
        var matching = jdbc.queryForList("""
                SELECT id,status,reservation_id,expected_version FROM notification_jobs WHERE job_kind='REMINDER_PLAN'
                AND owner_type=? AND owner_user_id IS NOT DISTINCT FROM ?::bigint AND team_id IS NOT DISTINCT FROM ?::bigint
                AND actual_start_at=? AND logical_root_ids && ?::bigint[] ORDER BY id FOR UPDATE
                """, reservation.ownerType(), reservation.ownerUserId(), reservation.teamId(), Timestamp.from(reservation.startsAt()), array);
        if (matching.stream().anyMatch(row -> "COMPLETED".equals(row.get("status")))) { return; }
        if (matching.stream().anyMatch(row -> ((Number)row.get("reservation_id")).longValue()==reservation.id()
                && ((Number)row.get("expected_version")).longValue()==reservation.version()
                && !"CANCELED".equals(row.get("status")))) { return; }
        if (!matching.isEmpty()) {
            long id = ((Number) matching.getFirst().get("id")).longValue();
            jdbc.update("""
                    UPDATE notification_jobs SET reservation_id=?,expected_version=?,logical_root_ids=?::bigint[],
                    scheduled_at=?,next_attempt_at=?,status='PENDING',attempts=0,lease_token=NULL,lease_expires_at=NULL,
                    last_error_code=NULL,canceled_at=NULL,updated_at=? WHERE id=?
                    """, reservation.id(), reservation.version(), array, Timestamp.from(due), Timestamp.from(due), Timestamp.from(now), id);
            for (int index = 1; index < matching.size(); index++) {
                cancelJob(((Number) matching.get(index).get("id")).longValue(), now, "SUPERSEDED_PLAN");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO notification_jobs(job_kind,job_key,status,reservation_id,expected_version,logical_root_ids,
                owner_type,owner_user_id,team_id,actual_start_at,scheduled_at,next_attempt_at,created_at,updated_at)
                VALUES ('REMINDER_PLAN',?,'PENDING',?,?,?::bigint[],?,?,?,?,?,?,?,?) ON CONFLICT(job_key) DO NOTHING
                """, key, reservation.id(), reservation.version(), array, reservation.ownerType(), reservation.ownerUserId(), reservation.teamId(),
                Timestamp.from(reservation.startsAt()), Timestamp.from(due), Timestamp.from(due), Timestamp.from(now), Timestamp.from(now));
    }

    public void cancel(long reservationId, Instant now) {
        jdbc.update("""
                UPDATE notification_jobs SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='RESERVATION_CHANGED',
                lease_token=NULL,lease_expires_at=NULL WHERE reservation_id=? AND job_kind='REMINDER_PLAN'
                AND status IN ('PENDING','PROCESSING','RETRY_WAIT')
                """, Timestamp.from(now), Timestamp.from(now), reservationId);
        jdbc.update("""
                UPDATE notification_jobs j SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='RESERVATION_CHANGED',
                lease_token=NULL,lease_expires_at=NULL FROM notifications n WHERE j.notification_id=n.id AND n.reservation_id=?
                AND n.event_type='START_REMINDER' AND j.job_kind='PUSH_DELIVERY' AND j.status IN ('PENDING','PROCESSING','RETRY_WAIT')
                """, Timestamp.from(now), Timestamp.from(now), reservationId);
    }

    public List<Long> roots(long reservationId) {
        return jdbc.queryForList("""
                WITH RECURSIVE ancestors(id) AS (
                    SELECT ?::bigint UNION SELECT l.source_reservation_id FROM reservation_lineage l
                    JOIN ancestors a ON l.successor_reservation_id=a.id
                ) SELECT a.id FROM ancestors a WHERE NOT EXISTS(
                    SELECT 1 FROM reservation_lineage l WHERE l.successor_reservation_id=a.id) ORDER BY a.id
                """, Long.class, reservationId);
    }
    private void cancelJob(long id, Instant now, String reason) {
        jdbc.update("UPDATE notification_jobs SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code=?,lease_token=NULL,lease_expires_at=NULL WHERE id=?",
                Timestamp.from(now), Timestamp.from(now), reason, id);
    }
    private static String array(List<Long> roots) { return roots.stream().map(String::valueOf).collect(Collectors.joining(",", "{", "}")); }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 is unavailable"); }
    }
}
