package kr.jeongeum.scheduler.recurring.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.recurring.domain.RecurringAssignment;
import kr.jeongeum.scheduler.recurring.domain.RecurringOccurrence;
import kr.jeongeum.scheduler.recurring.domain.RecurringRevision;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RecurringRepository {
    private static final RowMapper<RecurringAssignment> ASSIGNMENT = (row, index) -> new RecurringAssignment(
            row.getLong("id"), row.getLong("team_id"), row.getLong("created_by_user_id"), row.getString("status"),
            row.getObject("end_date", LocalDate.class), row.getLong("version"), row.getTimestamp("created_at").toInstant(),
            row.getTimestamp("updated_at").toInstant());
    private static final RowMapper<RecurringRevision> REVISION = (row, index) -> new RecurringRevision(
            row.getLong("id"), row.getLong("assignment_id"), row.getLong("revision_no"),
            row.getObject("effective_date", LocalDate.class), row.getInt("weekday"), row.getString("time_block_code"),
            row.getTimestamp("created_at").toInstant());
    private static final RowMapper<RecurringOccurrence> OCCURRENCE = (row, index) -> new RecurringOccurrence(
            row.getLong("id"), row.getLong("assignment_id"), row.getObject("week_start_date", LocalDate.class),
            row.getLong("pattern_revision_id"), row.getObject("original_scheduled_date", LocalDate.class),
            row.getString("original_block_code"), row.getString("state"), row.getLong("version"));
    private final JdbcTemplate jdbc;
    public RecurringRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<RecurringAssignment> assignments() { return jdbc.query("SELECT * FROM recurring_assignments ORDER BY id", ASSIGNMENT); }
    public Optional<RecurringAssignment> find(long id) {
        return jdbc.query("SELECT * FROM recurring_assignments WHERE id = ?", ASSIGNMENT, id).stream().findFirst();
    }
    public List<RecurringRevision> revisions(long id) {
        return jdbc.query("SELECT * FROM recurring_assignment_revisions WHERE assignment_id = ? ORDER BY effective_date, revision_no", REVISION, id);
    }
    public List<RecurringOccurrence> occurrences(long id) {
        return jdbc.query("SELECT * FROM recurring_occurrences WHERE assignment_id = ? ORDER BY week_start_date", OCCURRENCE, id);
    }
    public Optional<RecurringOccurrence> occurrence(long id, LocalDate week) {
        return jdbc.query("SELECT * FROM recurring_occurrences WHERE assignment_id = ? AND week_start_date = ?", OCCURRENCE, id, week)
                .stream().findFirst();
    }
    public List<Long> reservationIds(long occurrenceId, boolean onlyCurrent) {
        return jdbc.queryForList("SELECT reservation_id FROM recurring_occurrence_reservations WHERE occurrence_id = ?"
                + (onlyCurrent ? " AND is_current" : "") + " ORDER BY reservation_id", Long.class, occurrenceId);
    }
    public void lockPattern(boolean write) {
        jdbc.queryForObject("SELECT id FROM recurring_schedule_guard WHERE id = 1 FOR " + (write ? "UPDATE" : "SHARE"), Integer.class);
    }
    public void patternChanged() { jdbc.update("UPDATE recurring_schedule_guard SET version = version + 1 WHERE id = 1"); }
    public void lockTeam(long id) {
        jdbc.queryForObject("SELECT id FROM teams WHERE id = ? FOR NO KEY UPDATE", Long.class, id);
    }
    public RecurringAssignment insert(long teamId, long actorId, Instant now) {
        return jdbc.query("INSERT INTO recurring_assignments(team_id, created_by_user_id, created_at, updated_at) VALUES (?, ?, ?, ?) RETURNING *",
                ASSIGNMENT, teamId, actorId, Timestamp.from(now), Timestamp.from(now)).getFirst();
    }
    public RecurringRevision revise(long assignmentId, long revisionNo, LocalDate effective, int weekday, String blockCode,
            long actorId, Instant now) {
        return jdbc.query("""
                INSERT INTO recurring_assignment_revisions(assignment_id, revision_no, effective_date, weekday,
                time_block_code, created_by_user_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, REVISION, assignmentId, revisionNo, effective, weekday, blockCode, actorId, Timestamp.from(now)).getFirst();
    }
    public void update(long id, Instant now) {
        jdbc.update("UPDATE recurring_assignments SET version = version + 1, updated_at = ? WHERE id = ?", Timestamp.from(now), id);
    }
    public void end(long id, LocalDate endDate, Instant now) {
        jdbc.update("UPDATE recurring_assignments SET status = 'ENDED', end_date = ?, version = version + 1, updated_at = ? WHERE id = ?",
                endDate, Timestamp.from(now), id);
    }
    public RecurringOccurrence insertOccurrence(long assignmentId, LocalDate week, long revisionId, LocalDate date,
            String code, String state, Instant now) {
        return jdbc.query("""
                INSERT INTO recurring_occurrences(assignment_id, week_start_date, pattern_revision_id,
                original_scheduled_date, original_block_code, state, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, OCCURRENCE, assignmentId, week, revisionId, date, code, state, Timestamp.from(now), Timestamp.from(now)).getFirst();
    }
    public void planOccurrence(long id, long revisionId, LocalDate date, String blockCode, String state, Instant now) {
        jdbc.update("""
                UPDATE recurring_occurrences SET pattern_revision_id = ?, original_scheduled_date = ?,
                original_block_code = ?, state = ?, version = version + 1, updated_at = ? WHERE id = ?
                """, revisionId, date, blockCode, state, Timestamp.from(now), id);
    }
    public void state(long id, String state, Instant now) {
        jdbc.update("UPDATE recurring_occurrences SET state = ?, version = version + 1, updated_at = ? WHERE id = ?", state, Timestamp.from(now), id);
    }
    public void replaceLinks(long id) {
        jdbc.update("UPDATE recurring_occurrence_reservations SET is_current = FALSE WHERE occurrence_id = ? AND is_current", id);
    }
    public void link(long occurrenceId, long reservationId) {
        jdbc.update("INSERT INTO recurring_occurrence_reservations(occurrence_id,reservation_id,link_role,is_current) VALUES (?,?,'ORIGINAL',TRUE)",
                occurrenceId, reservationId);
    }
    public record Idempotency(String fingerprint, long assignmentId, int status, long operationId) {}
    public Optional<Idempotency> replay(long actorId, String digest) {
        return jdbc.query("SELECT * FROM recurring_idempotency_requests WHERE actor_user_id = ? AND key_digest = ? FOR UPDATE",
                (row, index) -> new Idempotency(row.getString("request_fingerprint"), row.getLong("result_assignment_id"),
                        row.getInt("response_status"), row.getLong("operation_id")), actorId, digest).stream().findFirst();
    }
    public void record(long actorId, String digest, String fingerprint, String method, String route, int status,
            long assignmentId, long operationId, Instant now) {
        jdbc.update("""
                INSERT INTO recurring_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,
                response_status,result_assignment_id,operation_id,created_at) VALUES (?,?,?,?,?,?,?,?,?)
                """, actorId, digest, fingerprint, method, route, status, assignmentId, operationId, Timestamp.from(now));
    }
}
