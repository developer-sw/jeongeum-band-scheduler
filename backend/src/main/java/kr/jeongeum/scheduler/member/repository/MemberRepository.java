package kr.jeongeum.scheduler.member.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.List;
import kr.jeongeum.scheduler.member.api.AdminMemberResponse;
import kr.jeongeum.scheduler.member.api.TeamMembershipResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MemberRepository {
    private static final RowMapper<Member> MAPPER = (row, index) -> new Member(row.getLong("id"),
            row.getString("login_id"), row.getString("password_hash"), row.getString("display_name"),
            row.getString("global_role"), row.getString("status"),
            row.getTimestamp("created_at").toInstant(), row.getLong("version"));
    private final JdbcTemplate jdbc;
    private static final RowMapper<AdminMemberResponse> ADMIN_MAPPER = (row, index) -> new AdminMemberResponse(
            row.getString("id"), row.getString("login_id"), row.getString("display_name"),
            row.getString("global_role"), row.getString("status"), row.getString("roster_entry_id"), row.getLong("version"));
    private static final String ADMIN_SQL = """
            SELECT u.*, r.id AS roster_entry_id FROM users u LEFT JOIN roster_entries r ON r.user_id = u.id
            """;

    public MemberRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Member> findByLoginId(String loginId) {
        return jdbc.query("SELECT * FROM users WHERE login_id = ?", MAPPER, loginId).stream().findFirst();
    }

    public Optional<Member> findById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    public Optional<Member> lockById(long id) {
        return jdbc.query("SELECT * FROM users WHERE id = ? FOR NO KEY UPDATE", MAPPER, id)
                .stream().findFirst();
    }

    public Optional<Member> insert(String loginId, String passwordHash, String displayName,
            String globalRole, Instant now) {
        return jdbc.query("""
                INSERT INTO users (login_id, password_hash, display_name, global_role, status, created_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?) ON CONFLICT (login_id) DO NOTHING RETURNING *
                """, MAPPER, loginId, passwordHash, displayName, globalRole,
                java.sql.Timestamp.from(now)).stream().findFirst();
    }

    public boolean anyMemberExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM users)", Boolean.class));
    }

    public void lockBootstrapGuard() {
        jdbc.queryForObject("SELECT id FROM auth_bootstrap_guard WHERE id = 1 FOR UPDATE", Integer.class);
    }

    public List<AdminMemberResponse> listAdminMembers() {
        return jdbc.query(ADMIN_SQL + " ORDER BY u.id", ADMIN_MAPPER);
    }

    public AdminMemberResponse adminResponse(long id) {
        return jdbc.query(ADMIN_SQL + " WHERE u.id = ?", ADMIN_MAPPER, id).getFirst();
    }

    public List<TeamMembershipResponse> memberships(long userId) {
        return jdbc.query("""
                SELECT t.id, t.name, t.kind, m.role FROM team_memberships m
                JOIN teams t ON t.id = m.team_id AND t.status = 'ACTIVE'
                JOIN roster_entries r ON r.id = m.roster_entry_id
                WHERE r.user_id = ? AND m.status = 'ACTIVE' ORDER BY t.id
                """, (row, index) -> new TeamMembershipResponse(row.getString("id"), row.getString("name"),
                        row.getString("kind"), row.getString("role")), userId);
    }

    public long activeAdministratorCount() {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE status = 'ACTIVE' AND global_role = 'GLOBAL_ADMIN'",
                Long.class);
    }

    public void changeRole(long id, String role) {
        jdbc.update("UPDATE users SET global_role = ?, version = version + 1 WHERE id = ?", role, id);
    }

    public void auditRole(long targetId, long actorId, String oldRole, String newRole, Instant now) {
        jdbc.update("""
                INSERT INTO member_role_audit(target_user_id, actor_user_id, old_role, new_role, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, targetId, actorId, oldRole, newRole, java.sql.Timestamp.from(now));
    }
}
