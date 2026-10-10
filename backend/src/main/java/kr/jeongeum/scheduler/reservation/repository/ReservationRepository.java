package kr.jeongeum.scheduler.reservation.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Optional;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ReservationRepository {
    private static final RowMapper<Instrument> ASSET = (row, index) -> new Instrument(row.getLong("id"),
            row.getString("code"), row.getString("name"), row.getString("status"), row.getLong("version"));
    private static final RowMapper<Reservation> RESERVATION = (row, index) -> new Reservation(row.getLong("id"),
            row.getString("owner_type"), row.getString("purpose"), row.getObject("owner_user_id", Long.class),
            row.getObject("team_id", Long.class), row.getLong("creator_user_id"),
            row.getObject("reservation_date", LocalDate.class), row.getString("time_block_code"),
            row.getTimestamp("block_start_at").toInstant(), row.getTimestamp("block_end_at").toInstant(),
            row.getTimestamp("usable_from").toInstant(), row.getString("status"), row.getString("origin_kind"),
            row.getBoolean("quota_exempt"), row.getBoolean("dual_asset_exception"), row.getLong("version"),
            row.getTimestamp("created_at").toInstant(), instant(row.getTimestamp("canceled_at")),
            row.getString("cancel_reason"), row.getLong("created_operation_id"), row.getObject("canceled_operation_id", Long.class));
    private final JdbcTemplate jdbc;
    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Instrument> instruments() { return jdbc.query("SELECT * FROM assets ORDER BY id", ASSET); }
    public List<Instrument> lockInstruments() { return jdbc.query("SELECT * FROM assets ORDER BY id FOR SHARE", ASSET); }
    public String ensureAndLockDay(LocalDate date, Instant now) {
        // The directory guard precedes every caller, including concurrent first creation of a date.
        jdbc.update("""
                INSERT INTO booking_days(reservation_date, status, created_at) VALUES (?, 'PREPARING', ?)
                ON CONFLICT (reservation_date) DO NOTHING
                """, date, Timestamp.from(now));
        return jdbc.queryForObject("SELECT CASE WHEN recurring_prepared THEN status ELSE 'PREPARING' END FROM booking_days WHERE reservation_date = ? FOR UPDATE", String.class, date);
    }
    public boolean prepared(LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM booking_days WHERE reservation_date = ? AND status = 'OPEN' AND recurring_prepared)", Boolean.class, date));
    }
    public void openDay(LocalDate date, Instant now) {
        jdbc.update("UPDATE booking_days SET status = 'OPEN', recurring_prepared = TRUE, opened_at = COALESCE(opened_at, ?), version = version + 1 WHERE reservation_date = ?",
                Timestamp.from(now), date);
    }
    public Map<LocalDate, String> dayStatuses(LocalDate from, LocalDate to) {
        Map<LocalDate, String> result = new HashMap<>();
        jdbc.query("SELECT reservation_date, CASE WHEN recurring_prepared THEN status ELSE 'PREPARING' END AS status FROM booking_days WHERE reservation_date BETWEEN ? AND ?",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(row.getObject("reservation_date", LocalDate.class),
                        row.getString("status")), from, to);
        return result;
    }
    public Optional<Reservation> find(long id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ?", RESERVATION, id).stream().findFirst();
    }
    public Optional<Reservation> lock(long id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ? FOR UPDATE", RESERVATION, id).stream().findFirst();
    }
    public List<Reservation> between(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM reservations WHERE reservation_date BETWEEN ? AND ? ORDER BY reservation_date, time_block_code, id",
                RESERVATION, from, to);
    }
    public boolean personalOverlap(long userId, LocalDate date, String blockCode) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM reservations WHERE owner_user_id = ? AND reservation_date = ?
                AND time_block_code = ? AND status = 'CONFIRMED')
                """, Boolean.class, userId, date, blockCode));
    }
    public List<String> occupiedCodes(LocalDate date, String blockCode) {
        return jdbc.queryForList("""
                SELECT a.code FROM reservation_assets c JOIN assets a ON a.id = c.asset_id
                WHERE c.reservation_date = ? AND c.time_block_code = ? AND c.released_at IS NULL ORDER BY a.id
                """, String.class, date, blockCode);
    }
    public List<String> instrumentCodes(long reservationId) {
        return jdbc.queryForList("""
                SELECT a.code FROM reservation_assets c JOIN assets a ON a.id = c.asset_id
                WHERE c.reservation_id = ? ORDER BY a.id
                """, String.class, reservationId);
    }
    public Map<Long, List<String>> instrumentsBetween(LocalDate from, LocalDate to) {
        Map<Long, List<String>> result = new HashMap<>();
        jdbc.query("""
                SELECT c.reservation_id, a.code FROM reservation_assets c JOIN assets a ON a.id = c.asset_id
                WHERE c.reservation_date BETWEEN ? AND ? ORDER BY c.reservation_id, a.id
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> result.computeIfAbsent(row.getLong("reservation_id"),
                        ignored -> new ArrayList<>()).add(row.getString("code")), from, to);
        return result;
    }
    public int usedMinutes(LocalDate date, Long userId, Long teamId) {
        String subject = userId != null ? "owner_user_id" : "team_id";
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minutes), 0)::integer FROM quota_entries WHERE quota_date = ? AND "
                + subject + " = ?", Integer.class, date, userId != null ? userId : teamId);
    }
    public record QuotaKey(LocalDate date, Long userId, Long teamId) {}
    public Map<QuotaKey, Integer> quotasBetween(LocalDate from, LocalDate to) {
        Map<QuotaKey, Integer> result = new HashMap<>();
        jdbc.query("""
                SELECT quota_date, owner_user_id, team_id, SUM(amount_minutes)::integer AS used
                FROM quota_entries WHERE quota_date BETWEEN ? AND ? GROUP BY quota_date, owner_user_id, team_id
                """, (org.springframework.jdbc.core.RowCallbackHandler) row -> result.put(new QuotaKey(
                        row.getObject("quota_date", LocalDate.class), row.getObject("owner_user_id", Long.class),
                        row.getObject("team_id", Long.class)), row.getInt("used")), from, to);
        return result;
    }
    public long operation(String kind, long actorId, Instant now, String reason) {
        return jdbc.queryForObject("""
                INSERT INTO reservation_operations(kind, actor_kind, actor_user_id, occurred_at, reason)
                VALUES (?, 'USER', ?, ?, ?) RETURNING id
                """, Long.class, kind, actorId, Timestamp.from(now), reason);
    }
    public long systemOperation(String kind, Instant now) {
        return jdbc.queryForObject("INSERT INTO reservation_operations(kind, actor_kind, occurred_at) VALUES (?, 'SYSTEM', ?) RETURNING id",
                Long.class, kind, Timestamp.from(now));
    }
    public Reservation insertFixed(String purpose, long teamId, long creatorId, LocalDate date, TimeBlock block, Instant now, long operationId) {
        Instant start = block.startsAt(date);
        Instant usableFrom = now.isAfter(start) ? now : start;
        return jdbc.query("""
                INSERT INTO reservations(owner_type,purpose,team_id,creator_user_id,reservation_date,time_block_code,
                block_start_minute,block_end_minute,duration_minutes,block_start_at,block_end_at,usable_from,
                origin_kind,quota_exempt,created_at,created_operation_id)
                VALUES ('TEAM',?,?,?,?,?,?,?,?,?,?,?,'FIXED',?,?,?) RETURNING *
                """, RESERVATION, purpose, teamId, creatorId, date, block.name(), block.startMinute(), block.endMinute(),
                block.durationMinutes(), Timestamp.from(start), Timestamp.from(block.endsAt(date)), Timestamp.from(usableFrom),
                block.durationMinutes() == 60, Timestamp.from(now), operationId).getFirst();
    }
    public void cancelOccurrence(long reservationId, Instant now) {
        jdbc.update("""
                UPDATE recurring_occurrences SET state = 'CANCELED_EXCEPTION', version = version + 1, updated_at = ?
                WHERE id IN (SELECT occurrence_id FROM recurring_occurrence_reservations WHERE reservation_id = ? AND is_current)
                AND state <> 'CANCELED_EXCEPTION'
                """, Timestamp.from(now), reservationId);
    }
    public Reservation insert(String ownerType, String purpose, Long ownerUserId, Long teamId, long creatorId,
            LocalDate date, TimeBlock block, Instant now, long operationId) {
        Instant start = block.startsAt(date);
        Instant usableFrom = now.isAfter(start) ? now : start;
        return jdbc.query("""
                INSERT INTO reservations(owner_type, purpose, owner_user_id, team_id, creator_user_id,
                reservation_date, time_block_code, block_start_minute, block_end_minute, duration_minutes,
                block_start_at, block_end_at, usable_from, origin_kind, created_at, created_operation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'MANUAL', ?, ?) RETURNING *
                """, RESERVATION, ownerType, purpose, ownerUserId, teamId, creatorId, date, block.name(),
                block.startMinute(), block.endMinute(), block.durationMinutes(), Timestamp.from(start),
                Timestamp.from(block.endsAt(date)), Timestamp.from(usableFrom), Timestamp.from(now), operationId).getFirst();
    }
    public void claim(Reservation reservation, Instrument instrument, Instant now) {
        jdbc.update("""
                INSERT INTO reservation_assets(reservation_id, reservation_date, time_block_code, asset_id, claim_kind, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, reservation.id(), reservation.date(), reservation.blockCode(), instrument.id(),
                reservation.ownerType(), Timestamp.from(now));
    }
    public void quota(Reservation reservation, long operationId, int amount, String kind, Instant now) {
        jdbc.update("""
                INSERT INTO quota_entries(reservation_id, operation_id, quota_date, owner_user_id, team_id,
                amount_minutes, entry_kind, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, reservation.id(), operationId, reservation.date(), reservation.ownerUserId(), reservation.teamId(),
                amount, kind, Timestamp.from(now));
    }
    public int reservedQuota(long id) {
        return jdbc.queryForObject("SELECT amount_minutes FROM quota_entries WHERE reservation_id = ? AND entry_kind = 'RESERVE'",
                Integer.class, id);
    }
    public void cancel(long id, long actorId, long operationId, Instant now, String reason) {
        jdbc.update("""
                UPDATE reservations SET status = 'CANCELED', canceled_at = ?, canceled_by_user_id = ?, cancel_reason = ?,
                canceled_operation_id = ?, version = version + 1 WHERE id = ?
                """, Timestamp.from(now), actorId, reason, operationId, id);
        jdbc.update("UPDATE reservation_assets SET released_at = ? WHERE reservation_id = ? AND released_at IS NULL",
                Timestamp.from(now), id);
    }
    public record Idempotency(String fingerprint, long reservationId, int responseStatus, Long operationId) {}
    public Optional<Idempotency> idempotency(long actorId, String digest) {
        return jdbc.query("SELECT * FROM idempotency_requests WHERE actor_user_id = ? AND key_digest = ? FOR UPDATE",
                (row, index) -> new Idempotency(row.getString("request_fingerprint"), row.getLong("result_reservation_id"),
                        row.getInt("response_status"), row.getObject("operation_id", Long.class)), actorId, digest).stream().findFirst();
    }
    public void recordIdempotency(long actorId, String digest, String fingerprint, String route, int status,
            long reservationId, Long operationId, Instant now) {
        jdbc.update("""
                INSERT INTO idempotency_requests(actor_user_id, key_digest, request_fingerprint, http_method, route,
                response_status, result_reservation_id, operation_id, created_at) VALUES (?, ?, ?, 'POST', ?, ?, ?, ?, ?)
                """, actorId, digest, fingerprint, route, status, reservationId, operationId, Timestamp.from(now));
    }
    private static Instant instant(Timestamp timestamp) { return timestamp == null ? null : timestamp.toInstant(); }
}
