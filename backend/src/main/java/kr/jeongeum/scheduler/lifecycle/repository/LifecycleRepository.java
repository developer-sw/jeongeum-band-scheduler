package kr.jeongeum.scheduler.lifecycle.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.lifecycle.api.LifecycleResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LifecycleRepository {
    private final JdbcTemplate jdbc;
    public LifecycleRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Replay(String fingerprint, LifecycleResponse response) {}
    public Optional<Replay> replay(long actor, String digest) {
        return jdbc.query("SELECT * FROM lifecycle_operations WHERE actor_user_id=? AND key_digest=? FOR UPDATE",
                (row, index) -> new Replay(row.getString("request_fingerprint"), new LifecycleResponse(
                        row.getString("id"), row.getString("target_user_id") == null
                                ? row.getString("target_team_id") : row.getString("target_user_id"),
                        row.getString("result_status"), row.getLong("result_version"), row.getInt("canceled_reservations"),
                        row.getInt("invalidated_exchanges"), row.getInt("ended_assignments"),
                        row.getInt("deactivated_subscriptions"), row.getInt("revoked_sessions"))), actor, digest)
                .stream().findFirst();
    }
    public LifecycleResponse record(long actor, String digest, String fingerprint, boolean withdrawal, long target,
            long expectedVersion, long resultVersion, long reservationOperation, int canceled, int invalidated,
            int assignments, int subscriptions, int sessions, String reason, Instant now) {
        long id = jdbc.queryForObject("""
                INSERT INTO lifecycle_operations(actor_user_id,key_digest,request_fingerprint,kind,target_user_id,
                    target_team_id,expected_version,result_status,result_version,reservation_operation_id,
                    canceled_reservations,invalidated_exchanges,ended_assignments,deactivated_subscriptions,
                    revoked_sessions,reason,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) RETURNING id
                """, Long.class, actor, digest, fingerprint, withdrawal ? "USER_WITHDRAW" : "TEAM_DISBAND",
                withdrawal ? target : null, withdrawal ? null : target, expectedVersion,
                withdrawal ? "WITHDRAWN" : "DISBANDED", resultVersion, reservationOperation, canceled, invalidated,
                assignments, subscriptions, sessions, reason, Timestamp.from(now));
        return new LifecycleResponse(Long.toString(id), Long.toString(target), withdrawal ? "WITHDRAWN" : "DISBANDED",
                resultVersion, canceled, invalidated, assignments, subscriptions, sessions);
    }
    public List<Long> remainingReservations(boolean withdrawal, long id, Instant now) {
        String owner = withdrawal ? "owner_user_id" : "team_id";
        return jdbc.queryForList("SELECT id FROM reservations WHERE " + owner
                + "=? AND status='CONFIRMED' AND block_end_at>? ORDER BY id", Long.class, id, Timestamp.from(now));
    }
    public List<Long> membershipTeams(long user) {
        return jdbc.queryForList("""
                SELECT DISTINCT m.team_id FROM team_memberships m JOIN roster_entries r ON r.id=m.roster_entry_id
                WHERE r.user_id=? AND m.status='ACTIVE' ORDER BY m.team_id
                """, Long.class, user);
    }
    public List<Long> activeTeamUsers(long team) {
        return jdbc.queryForList("""
                SELECT DISTINCT u.id FROM team_memberships m JOIN roster_entries r ON r.id=m.roster_entry_id
                JOIN users u ON u.id=r.user_id AND u.status='ACTIVE'
                WHERE m.team_id=? AND m.status='ACTIVE' ORDER BY u.id
                """, Long.class, team);
    }
    public void lockTeam(long team) {
        if (jdbc.queryForList("SELECT id FROM teams WHERE id=? FOR NO KEY UPDATE", Long.class, team).isEmpty()) {
            throw kr.jeongeum.scheduler.common.api.DirectoryInputs.notFound();
        }
    }
    public List<Long> affectedPendingExchanges(boolean withdrawal, long target) {
        return withdrawal ? jdbc.queryForList("""
                SELECT e.id FROM exchange_requests e WHERE e.status='PENDING' AND (e.requester_user_id=? OR EXISTS(
                    SELECT 1 FROM exchange_request_items x JOIN reservations r ON r.id=x.reservation_id
                    WHERE x.exchange_request_id=e.id AND r.owner_user_id=?)) ORDER BY e.id
                """, Long.class, target, target) : jdbc.queryForList("""
                SELECT e.id FROM exchange_requests e WHERE e.status='PENDING' AND EXISTS(
                    SELECT 1 FROM exchange_request_items x JOIN reservations r ON r.id=x.reservation_id
                    WHERE x.exchange_request_id=e.id AND r.team_id=?) ORDER BY e.id
                """, Long.class, target);
    }
    public int invalidatedCount(List<Long> pendingIds) {
        int count = 0;
        for (long id : pendingIds) {
            if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT status='INVALIDATED' FROM exchange_requests WHERE id=?",
                    Boolean.class, id))) { count++; }
        }
        return count;
    }
    public void withdraw(long target, Instant now) {
        jdbc.update("UPDATE users SET status='WITHDRAWN',withdrawn_at=?,version=version+1 WHERE id=?",
                Timestamp.from(now), target);
        jdbc.update("""
                UPDATE team_memberships m SET status='REMOVED',role='MEMBER',updated_at=?,version=m.version+1
                FROM roster_entries r WHERE m.roster_entry_id=r.id AND r.user_id=? AND m.status='ACTIVE'
                """, Timestamp.from(now), target);
    }
    public void disband(long target, Instant now) {
        jdbc.update("UPDATE teams SET status='DISBANDED',disbanded_at=?,updated_at=?,version=version+1 WHERE id=?",
                Timestamp.from(now), Timestamp.from(now), target);
        jdbc.update("""
                UPDATE team_memberships SET status='REMOVED',role='MEMBER',updated_at=?,version=version+1
                WHERE team_id=? AND status='ACTIVE'
                """, Timestamp.from(now), target);
    }
    public int endAssignments(long team, LocalDate yesterday, Instant now) {
        int count = jdbc.update("""
                UPDATE recurring_assignments SET status='ENDED',end_date=?,updated_at=?,version=version+1
                WHERE team_id=? AND (status='ACTIVE' OR end_date>?)
                """, yesterday, Timestamp.from(now), team, yesterday);
        // Unmaterialized plans must not return when opening/recovering dates after the closure.
        jdbc.update("""
                UPDATE recurring_occurrences o SET state='ENDED',updated_at=?,version=o.version+1
                FROM recurring_assignments a WHERE o.assignment_id=a.id AND a.team_id=? AND o.state='PLANNED'
                """, Timestamp.from(now), team);
        return count;
    }
    public int revokeSessions(long user) {
        return jdbc.update("DELETE FROM spring_session WHERE principal_name=?", new MemberPrincipal(user).toString());
    }
    public void cancelMemberJobs(long user, Instant now) {
        jdbc.update("""
                UPDATE notification_jobs j SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='USER_WITHDRAWN',
                    lease_token=NULL,lease_expires_at=NULL
                WHERE j.status IN ('PENDING','RETRY_WAIT','PROCESSING') AND (j.owner_user_id=? OR EXISTS(
                    SELECT 1 FROM notifications n WHERE n.id=j.notification_id AND n.user_id=?))
                """, Timestamp.from(now), Timestamp.from(now), user, user);
    }
    public void cancelTeamJobs(long team, Instant now) {
        jdbc.update("""
                UPDATE notification_jobs j SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='TEAM_DISBANDED',
                    lease_token=NULL,lease_expires_at=NULL
                WHERE j.status IN ('PENDING','RETRY_WAIT','PROCESSING') AND (j.team_id=? OR EXISTS(
                    SELECT 1 FROM reservations r WHERE r.id=j.reservation_id AND r.team_id=?))
                """, Timestamp.from(now), Timestamp.from(now), team, team);
    }
    public void cancelInvalidExchangeJobs(List<Long> ids, Instant now) {
        for (long id : ids) {
            jdbc.update("""
                    UPDATE notification_jobs j SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='EXCHANGE_INVALIDATED',
                        lease_token=NULL,lease_expires_at=NULL FROM notifications n
                    WHERE n.id=j.notification_id AND n.exchange_request_id=? AND n.event_type='EXCHANGE_REQUESTED'
                        AND j.status IN ('PENDING','RETRY_WAIT','PROCESSING')
                        AND EXISTS(SELECT 1 FROM exchange_requests e WHERE e.id=? AND e.status='INVALIDATED')
                    """, Timestamp.from(now), Timestamp.from(now), id, id);
        }
    }
}
