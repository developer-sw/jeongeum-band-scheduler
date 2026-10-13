package kr.jeongeum.scheduler.notice.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.notice.api.NoticeResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NoticeRepository {
    private static final RowMapper<NoticeResponse> NOTICE=(r,i) -> new NoticeResponse(r.getString("id"),r.getString("title"),
            r.getString("body"),r.getBoolean("pinned"),r.getString("author_user_id"),r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at").toInstant(),r.getLong("version"),r.getTimestamp("deleted_at")!=null);
    public record Replay(String fingerprint,long noticeId,int status) {}
    private final JdbcTemplate jdbc;
    public NoticeRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<NoticeResponse> find(long id) { return jdbc.query("SELECT * FROM announcements WHERE id=?",NOTICE,id).stream().findFirst(); }
    public Optional<NoticeResponse> lock(long id) { return jdbc.query("SELECT * FROM announcements WHERE id=? FOR UPDATE",NOTICE,id).stream().findFirst(); }
    public List<NoticeResponse> list(Boolean pinned,Instant at,Long id,int limit) {
        return jdbc.query("SELECT * FROM announcements WHERE deleted_at IS NULL AND (?::bigint IS NULL OR (pinned,created_at,id)<(?::boolean,?::timestamptz,?::bigint)) ORDER BY pinned DESC,created_at DESC,id DESC LIMIT ?",
                NOTICE,id,pinned,at==null?null:Timestamp.from(at),id,limit);
    }
    public NoticeResponse insert(long actor,String title,String body,boolean pinned,Instant now) {
        return jdbc.query("INSERT INTO announcements(author_user_id,title,body,pinned,created_at,updated_at) VALUES (?,?,?,?,?,?) RETURNING *",NOTICE,
                actor,title,body,pinned,Timestamp.from(now),Timestamp.from(now)).getFirst();
    }
    public NoticeResponse update(long id,String title,String body,boolean pinned,Instant now) {
        return jdbc.query("UPDATE announcements SET title=?,body=?,pinned=?,updated_at=?,version=version+1 WHERE id=? RETURNING *",NOTICE,
                title,body,pinned,Timestamp.from(now),id).getFirst();
    }
    public NoticeResponse delete(long id,Instant now) {
        return jdbc.query("UPDATE announcements SET deleted_at=?,updated_at=?,version=version+1 WHERE id=? RETURNING *",NOTICE,
                Timestamp.from(now),Timestamp.from(now),id).getFirst();
    }
    public Optional<Replay> replay(long actor,String key) {
        return jdbc.query("SELECT * FROM notice_idempotency_requests WHERE actor_user_id=? AND key_digest=?",
                (r,i) -> new Replay(r.getString("request_fingerprint"),r.getLong("result_notice_id"),r.getInt("response_status")),actor,key).stream().findFirst();
    }
    public void remember(long actor,String key,String fingerprint,String method,String route,int status,long notice,Instant now) {
        jdbc.update("INSERT INTO notice_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_notice_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
                actor,key,fingerprint,method,route,status,notice,Timestamp.from(now));
    }
}
