package kr.jeongeum.scheduler.team.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.team.domain.TeamSong;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class TeamSongRepository {
    private static final RowMapper<TeamSong> MAPPER = (row, index) -> new TeamSong(row.getLong("id"),
            row.getLong("team_id"), row.getString("title"), row.getString("artist"), row.getString("memo"),
            row.getInt("sort_order"), row.getLong("version"));
    private final JdbcTemplate jdbc;
    public TeamSongRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public List<TeamSong> list(long teamId) {
        return jdbc.query("SELECT * FROM team_songs WHERE team_id = ? ORDER BY sort_order, id", MAPPER, teamId);
    }
    public Optional<TeamSong> lockById(long teamId, long songId) {
        return jdbc.query("SELECT * FROM team_songs WHERE team_id = ? AND id = ? FOR UPDATE", MAPPER, teamId, songId)
                .stream().findFirst();
    }
    public long nextSortOrder(long teamId) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(sort_order)::bigint + 1, 0) FROM team_songs WHERE team_id = ?",
                Long.class, teamId);
    }
    public TeamSong insert(long teamId, String title, String artist, String memo, int sortOrder, long actorId, Instant now) {
        return jdbc.query("""
                INSERT INTO team_songs(team_id, title, artist, memo, sort_order, created_by, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING *
                """, MAPPER, teamId, title, artist, memo, sortOrder, actorId, actorId,
                Timestamp.from(now), Timestamp.from(now)).getFirst();
    }
    public TeamSong update(long teamId, long id, String title, String artist, String memo, int sortOrder,
            long actorId, Instant now) {
        return jdbc.query("""
                UPDATE team_songs SET title = ?, artist = ?, memo = ?, sort_order = ?, updated_by = ?, updated_at = ?,
                version = version + 1 WHERE team_id = ? AND id = ? RETURNING *
                """, MAPPER, title, artist, memo, sortOrder, actorId, Timestamp.from(now), teamId, id).getFirst();
    }
    public void delete(long teamId, long songId) { jdbc.update("DELETE FROM team_songs WHERE team_id = ? AND id = ?", teamId, songId); }
}
