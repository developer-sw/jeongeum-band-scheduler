package kr.jeongeum.scheduler.roster.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.roster.api.RosterEntryResponse;
import kr.jeongeum.scheduler.roster.domain.RosterEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RosterRepository {
    private static final RowMapper<RosterEntry> MAPPER = (row, index) -> new RosterEntry(
            row.getLong("id"), row.getString("name"), row.getObject("user_id", Long.class), row.getLong("version"));
    private static final RowMapper<RosterEntryResponse> RESPONSE = (row, index) -> new RosterEntryResponse(
            row.getString("id"), row.getString("name"), row.getString("user_id"), row.getString("login_id"),
            row.getString("display_name"), row.getString("status"), row.getLong("version"));
    private static final String RESPONSE_SQL = """
            SELECT r.*, u.login_id, u.display_name, u.status FROM roster_entries r
            LEFT JOIN users u ON u.id = r.user_id
            """;
    private final JdbcTemplate jdbc;
    public RosterRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<RosterEntry> lockById(long id) {
        return jdbc.query("SELECT * FROM roster_entries WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public Optional<RosterEntry> findByUserId(long userId) {
        return jdbc.query("SELECT * FROM roster_entries WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }
    public List<RosterEntryResponse> list() { return jdbc.query(RESPONSE_SQL + " ORDER BY r.id", RESPONSE); }
    public RosterEntryResponse response(long id) {
        return jdbc.query(RESPONSE_SQL + " WHERE r.id = ?", RESPONSE, id).getFirst();
    }
    public RosterEntry insert(String name, Instant now) {
        return jdbc.query("""
                INSERT INTO roster_entries(name, created_at, updated_at) VALUES (?, ?, ?) RETURNING *
                """, MAPPER, name, Timestamp.from(now), Timestamp.from(now)).getFirst();
    }
    public void rename(long id, String name, Instant now) {
        jdbc.update("UPDATE roster_entries SET name = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                name, Timestamp.from(now), id);
    }
    public void link(long id, long userId, Instant now) {
        jdbc.update("UPDATE roster_entries SET user_id = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                userId, Timestamp.from(now), id);
    }
}
