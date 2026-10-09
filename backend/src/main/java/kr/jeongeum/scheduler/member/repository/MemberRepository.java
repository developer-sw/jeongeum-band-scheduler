package kr.jeongeum.scheduler.member.repository;

import java.time.Instant;
import java.util.Optional;
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
}
