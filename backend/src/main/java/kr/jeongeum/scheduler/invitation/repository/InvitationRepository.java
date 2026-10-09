package kr.jeongeum.scheduler.invitation.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.invitation.domain.Invitation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InvitationRepository {
    private static final RowMapper<Invitation> MAPPER = (row, index) -> new Invitation(row.getLong("id"),
            row.getString("code_digest"), row.getString("recipient_name"), row.getLong("issued_by"),
            instant(row, "created_at"), instant(row, "expires_at"), instant(row, "used_at"),
            row.getObject("used_by", Long.class), instant(row, "revoked_at"), row.getLong("version"),
            row.getObject("roster_entry_id", Long.class));
    private final JdbcTemplate jdbc;

    public InvitationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Invitation> findByDigest(String digest) {
        return jdbc.query("SELECT * FROM invitation_codes WHERE code_digest = ?", MAPPER, digest)
                .stream().findFirst();
    }

    public Optional<Invitation> lockByDigest(String digest) {
        return jdbc.query("SELECT * FROM invitation_codes WHERE code_digest = ? FOR UPDATE", MAPPER, digest)
                .stream().findFirst();
    }

    public Optional<Invitation> lockById(long id) {
        return jdbc.query("SELECT * FROM invitation_codes WHERE id = ? FOR UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Invitation insert(String digest, String recipientName, long issuedBy, Instant now, Instant expiresAt) {
        return insert(digest, recipientName, issuedBy, now, expiresAt, null);
    }

    public Invitation insert(String digest, String recipientName, long issuedBy, Instant now, Instant expiresAt, Long rosterId) {
        return jdbc.query("""
                INSERT INTO invitation_codes (code_digest, recipient_name, issued_by, created_at, expires_at, roster_entry_id)
                VALUES (?, ?, ?, ?, ?, ?) RETURNING *
                """, MAPPER, digest, recipientName, issuedBy, Timestamp.from(now), Timestamp.from(expiresAt), rosterId).getFirst();
    }

    public void use(long id, long memberId, Instant now) {
        jdbc.update("UPDATE invitation_codes SET used_at = ?, used_by = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(now), memberId, id);
    }

    public void revoke(long id, Instant now) {
        jdbc.update("UPDATE invitation_codes SET revoked_at = ?, version = version + 1 WHERE id = ?",
                Timestamp.from(now), id);
    }

    public boolean pendingForRoster(long rosterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM invitation_codes WHERE roster_entry_id = ? AND used_at IS NULL AND revoked_at IS NULL)
                """, Boolean.class, rosterId));
    }

    public void bindRoster(long id, long rosterId) {
        jdbc.update("UPDATE invitation_codes SET roster_entry_id = ?, version = version + 1 WHERE id = ?", rosterId, id);
    }

    public void revokePendingForRoster(long rosterId, Instant now) {
        jdbc.update("""
                UPDATE invitation_codes SET revoked_at = ?, version = version + 1
                WHERE roster_entry_id = ? AND used_at IS NULL AND revoked_at IS NULL
                """, Timestamp.from(now), rosterId);
    }

    public List<Invitation> page(Long beforeId, int limit) {
        if (beforeId == null) {
            return jdbc.query("SELECT * FROM invitation_codes ORDER BY id DESC LIMIT ?", MAPPER, limit);
        }
        return jdbc.query("SELECT * FROM invitation_codes WHERE id < ? ORDER BY id DESC LIMIT ?",
                MAPPER, beforeId, limit);
    }

    private static Instant instant(ResultSet row, String name) throws SQLException {
        Timestamp value = row.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }
}
