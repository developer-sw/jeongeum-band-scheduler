package kr.jeongeum.scheduler.notification.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.notification.api.NotificationResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {
    private static final RowMapper<NotificationResponse> ITEM=(r,i) -> new NotificationResponse(r.getString("id"),r.getString("event_type"),
            r.getString("title"),r.getString("body"),r.getString("target_path"),r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("read_at")==null?null:r.getTimestamp("read_at").toInstant());
    private final JdbcTemplate jdbc;
    public NotificationRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<NotificationResponse> list(long owner,Long before,boolean unreadOnly,int limit) {
        return jdbc.query("SELECT * FROM notifications WHERE user_id=? AND (?::bigint IS NULL OR id<?) AND (NOT ? OR read_at IS NULL) ORDER BY id DESC LIMIT ?",
                ITEM,owner,before,before,unreadOnly,limit);
    }
    public long unreadCount(long owner) { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL",Long.class,owner); }
    public Optional<NotificationResponse> markRead(long owner,long id,Instant now) {
        return jdbc.query("UPDATE notifications SET read_at=COALESCE(read_at,GREATEST(created_at,?::timestamptz)) WHERE user_id=? AND id=? RETURNING *",
                ITEM,Timestamp.from(now),owner,id).stream().findFirst();
    }
    public int markReadThrough(long owner,Instant through,Instant now) {
        return jdbc.update("UPDATE notifications SET read_at=GREATEST(created_at,?::timestamptz) WHERE user_id=? AND read_at IS NULL AND created_at<=?",
                Timestamp.from(now),owner,Timestamp.from(through));
    }
    public List<Long> activeUsers() { return jdbc.queryForList("SELECT id FROM users WHERE status='ACTIVE' ORDER BY id",Long.class); }
    public List<Long> activePersonal(long owner) { return jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE'",Long.class,owner); }
    public List<Long> activeTeam(long team) {
        return jdbc.queryForList("SELECT DISTINCT u.id FROM team_memberships m JOIN teams t ON t.id=m.team_id AND t.status='ACTIVE' JOIN roster_entries r ON r.id=m.roster_entry_id JOIN users u ON u.id=r.user_id AND u.status='ACTIVE' WHERE m.team_id=? AND m.status='ACTIVE' ORDER BY u.id",
                Long.class,team);
    }
    public String instrumentNames(long reservation) {
        return jdbc.queryForObject("SELECT COALESCE(string_agg(a.name, ', ' ORDER BY a.id),'') FROM reservation_assets c JOIN assets a ON a.id=c.asset_id WHERE c.reservation_id=?",String.class,reservation);
    }
    public String exchangeReason(long id) {
        return jdbc.queryForObject("SELECT state_reason FROM exchange_requests WHERE id=?",String.class,id);
    }
    public void insert(long user,String key,String type,Long operation,Long reservation,Long exchange,Long notice,
            String title,String body,String path,Instant now) {
        jdbc.update("INSERT INTO notifications(user_id,event_key,event_type,operation_id,reservation_id,exchange_request_id,announcement_id,title,body,target_path,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(event_key,user_id) DO NOTHING",
                user,key,type,operation,reservation,exchange,notice,title,body,path,Timestamp.from(now));
    }
}
