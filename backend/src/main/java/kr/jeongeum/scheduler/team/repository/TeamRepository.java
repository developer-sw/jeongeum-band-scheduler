package kr.jeongeum.scheduler.team.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.team.api.TeamMemberResponse;
import kr.jeongeum.scheduler.team.domain.Team;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TeamRepository {
    private static final RowMapper<Team> MAPPER = (row, index) -> new Team(row.getLong("id"),
            row.getString("name"), row.getString("kind"), row.getString("status"), row.getLong("version"));
    private final JdbcTemplate jdbc;
    public TeamRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<Team> list() { return jdbc.query("SELECT * FROM teams WHERE status = 'ACTIVE' ORDER BY id", MAPPER); }
    public Optional<Team> findById(long id) {
        return jdbc.query("SELECT * FROM teams WHERE id = ?", MAPPER, id).stream().findFirst();
    }
    public Optional<Team> lockById(long id) {
        return jdbc.query("SELECT * FROM teams WHERE id = ? FOR UPDATE", MAPPER, id).stream().findFirst();
    }
    public Team insert(String name, String kind, Instant now) {
        return jdbc.query("""
                INSERT INTO teams(name, kind, created_at, updated_at) VALUES (?, ?, ?, ?) RETURNING *
                """, MAPPER, name, kind, Timestamp.from(now), Timestamp.from(now)).getFirst();
    }
    public void rename(long id, String name, Instant now) {
        jdbc.update("UPDATE teams SET name = ?, updated_at = ?, version = version + 1 WHERE id = ?",
                name, Timestamp.from(now), id);
    }
    public void touch(long id, Instant now) {
        jdbc.update("UPDATE teams SET updated_at = ?, version = version + 1 WHERE id = ?", Timestamp.from(now), id);
    }
    public List<TeamMemberResponse> members(long teamId) {
        return jdbc.query("""
                SELECT r.id, r.name, r.user_id, m.role FROM team_memberships m
                JOIN roster_entries r ON r.id = m.roster_entry_id
                WHERE m.team_id = ? AND m.status = 'ACTIVE' ORDER BY m.id
                """, (row, index) -> new TeamMemberResponse(row.getString("id"), row.getString("name"),
                        row.getString("user_id"), row.getString("role")), teamId);
    }
    public Optional<String> roleForUser(long teamId, long userId) {
        return jdbc.query("""
                SELECT m.role FROM team_memberships m JOIN roster_entries r ON r.id = m.roster_entry_id
                WHERE m.team_id = ? AND r.user_id = ? AND m.status = 'ACTIVE'
                """, (row, index) -> row.getString("role"), teamId, userId).stream().findFirst();
    }
    public boolean membershipExists(long teamId, long rosterId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM team_memberships WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE')
                """, Boolean.class, teamId, rosterId));
    }
    public void addMember(long teamId, long rosterId, Instant now) {
        jdbc.update("""
                INSERT INTO team_memberships(team_id, roster_entry_id, created_at, updated_at) VALUES (?, ?, ?, ?)
                """, teamId, rosterId, Timestamp.from(now), Timestamp.from(now));
    }
    public int removeMember(long teamId, long rosterId, Instant now) {
        return jdbc.update("""
                UPDATE team_memberships SET status = 'REMOVED', role = 'MEMBER', updated_at = ?, version = version + 1
                WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'
                """, Timestamp.from(now), teamId, rosterId);
    }
    public void assignLeader(long teamId, Long rosterId, Instant now) {
        // Clear first so the partial unique constraint holds throughout the statement sequence.
        jdbc.update("""
                UPDATE team_memberships SET role = 'MEMBER', updated_at = ?, version = version + 1
                WHERE team_id = ? AND status = 'ACTIVE' AND role = 'LEADER'
                """, Timestamp.from(now), teamId);
        if (rosterId != null) {
            jdbc.update("""
                    UPDATE team_memberships SET role = 'LEADER', updated_at = ?, version = version + 1
                    WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'
                    """, Timestamp.from(now), teamId, rosterId);
        }
    }
}
