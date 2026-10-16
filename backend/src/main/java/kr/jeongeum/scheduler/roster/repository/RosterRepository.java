package kr.jeongeum.scheduler.roster.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import kr.jeongeum.scheduler.roster.api.RosterEntryResponse;
import kr.jeongeum.scheduler.roster.api.RosterEntryResponse.LeaderTeam;
import kr.jeongeum.scheduler.roster.domain.RosterEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class RosterRepository {
    private static final RowMapper<RosterEntry> MAPPER = (row, index) -> new RosterEntry(
            row.getLong("id"), row.getString("name"), row.getObject("user_id", Long.class), row.getLong("version"),
            row.getString("signup_global_role"));
    private static final RowMapper<RosterEntryResponse> RESPONSE = (row, index) -> new RosterEntryResponse(
            row.getString("id"), row.getString("name"), row.getString("user_id"), row.getString("login_id"),
            row.getString("display_name"), row.getString("status"), row.getLong("version"),
            row.getString("signup_global_role"), List.of());
    private static final String RESPONSE_SQL = """
            SELECT r.*, u.login_id, u.display_name, u.status FROM roster_entries r
            LEFT JOIN users u ON u.id = r.user_id
            """;
    private static final String LEADER_TEAMS_SQL = """
            SELECT r.id AS roster_id, t.id AS team_id, t.name, t.kind
            FROM team_memberships m
            JOIN roster_entries r ON r.id = m.roster_entry_id
            JOIN teams t ON t.id = m.team_id
            LEFT JOIN users u ON u.id = r.user_id
            WHERE m.status = 'ACTIVE' AND m.role = 'LEADER' AND t.status = 'ACTIVE'
              AND (r.user_id IS NULL OR u.status = 'ACTIVE')
            """;
    private final JdbcTemplate jdbc;
    public RosterRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public Optional<RosterEntry> lockById(long id) {
        return jdbc.query("SELECT * FROM roster_entries WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public Optional<RosterEntry> findByUserId(long userId) {
        return jdbc.query("SELECT * FROM roster_entries WHERE user_id = ?", MAPPER, userId).stream().findFirst();
    }
    public List<RosterEntryResponse> list() {
        List<RosterEntryResponse> entries = jdbc.query(RESPONSE_SQL + " ORDER BY r.id", RESPONSE);
        if (entries.isEmpty()) { return entries; }
        Map<String, List<LeaderTeam>> leaders = leaderTeams(null);
        return entries.stream().map(entry -> withLeaderTeams(entry, leaders)).toList();
    }
    public RosterEntryResponse response(long id) {
        RosterEntryResponse entry = jdbc.query(RESPONSE_SQL + " WHERE r.id = ?", RESPONSE, id).getFirst();
        return withLeaderTeams(entry, leaderTeams(id));
    }
    private Map<String, List<LeaderTeam>> leaderTeams(Long rosterId) {
        Map<String, List<LeaderTeam>> result = new HashMap<>();
        String sql = LEADER_TEAMS_SQL + (rosterId == null ? "" : " AND r.id = ?") + " ORDER BY r.id, t.id";
        Object[] parameters = rosterId == null ? new Object[0] : new Object[] {rosterId};
        jdbc.query(sql, row -> {
            result.computeIfAbsent(row.getString("roster_id"), ignored -> new ArrayList<>())
                    .add(new LeaderTeam(row.getString("team_id"), row.getString("name"), row.getString("kind")));
        }, parameters);
        return result;
    }
    private RosterEntryResponse withLeaderTeams(RosterEntryResponse entry, Map<String, List<LeaderTeam>> leaders) {
        List<LeaderTeam> teams = entry.userId() == null || "ACTIVE".equals(entry.accountStatus())
                ? leaders.getOrDefault(entry.id(), List.of()) : List.of();
        return new RosterEntryResponse(entry.id(), entry.name(), entry.userId(), entry.loginId(), entry.displayName(),
                entry.accountStatus(), entry.version(), entry.signupGlobalRole(), teams);
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
    public void changeSignupRole(long id, String role, Instant now) {
        jdbc.update("UPDATE roster_entries SET signup_global_role = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                role, Timestamp.from(now), id);
    }
    public void link(long id, long userId, Instant now) {
        jdbc.update("UPDATE roster_entries SET user_id = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                userId, Timestamp.from(now), id);
    }
}
