package kr.jeongeum.scheduler.push.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.push.api.PushSubscriptionResponse;
import kr.jeongeum.scheduler.push.service.PushInputs;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class PushSubscriptionRepository {
    private static final RowMapper<Subscription> ITEM=(r,i) -> new Subscription(r.getLong("id"),r.getLong("user_id"),r.getString("endpoint"),
            r.getString("p256dh"),r.getString("auth"),r.getString("device_name"),r.getBoolean("active"),r.getString("session_digest"),
            r.getLong("binding_version"),r.getTimestamp("created_at").toInstant(),r.getTimestamp("updated_at").toInstant());
    private final JdbcTemplate jdbc;
    public PushSubscriptionRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public boolean currentSession(long owner,String sessionId,Instant now) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM spring_session WHERE session_id=? AND expiry_time>? AND principal_name=?)",
                Boolean.class,sessionId,now.toEpochMilli(),new MemberPrincipal(owner).toString()));
    }
    public void deleteSession(long owner,String sessionId) {
        jdbc.update("DELETE FROM spring_session WHERE session_id=? AND principal_name=?",sessionId,new MemberPrincipal(owner).toString());
    }
    public List<PushSubscriptionResponse> list(long owner,String sessionDigest) {
        return jdbc.query("SELECT * FROM push_subscriptions WHERE user_id=? ORDER BY active DESC,updated_at DESC,id DESC LIMIT 50",ITEM,owner)
                .stream().map(row -> row.view(sessionDigest)).toList();
    }
    public Optional<Subscription> lockEndpoint(String endpoint) {
        return jdbc.query("SELECT * FROM push_subscriptions WHERE endpoint=? FOR UPDATE",ITEM,endpoint).stream().findFirst();
    }
    public long activeCount(long owner) { return jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id=? AND active",Long.class,owner); }
    public Subscription insert(long owner,String sessionDigest,PushInputs.Validated input,Instant now) {
        return jdbc.query("INSERT INTO push_subscriptions(user_id,endpoint,p256dh,auth,device_name,session_digest,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?) RETURNING *",
                ITEM,owner,input.endpoint(),input.p256dh(),input.auth(),input.deviceName(),sessionDigest,Timestamp.from(now),Timestamp.from(now)).getFirst();
    }
    public Subscription update(Subscription old,long owner,String sessionDigest,PushInputs.Validated input,Instant now,boolean rebind) {
        return jdbc.query("UPDATE push_subscriptions SET user_id=?,p256dh=?,auth=?,device_name=?,session_digest=?,active=true,deactivated_at=NULL,binding_version=binding_version+?,updated_at=? WHERE id=? RETURNING *",
                ITEM,owner,input.p256dh(),input.auth(),input.deviceName(),sessionDigest,rebind?1:0,Timestamp.from(now),old.id()).getFirst();
    }
    public boolean deactivateOwn(long owner,long id,Instant now) {
        Optional<Subscription> own=jdbc.query("SELECT * FROM push_subscriptions WHERE id=? AND user_id=? FOR UPDATE",ITEM,id,owner).stream().findFirst();
        if (own.isEmpty()) { return false; }
        deactivate(id,now); cancelJobs(id,now); return true;
    }
    public void deactivateSession(long owner,String digest,Instant now) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM push_subscriptions WHERE user_id=? AND session_digest=? AND active FOR UPDATE",Long.class,owner,digest);
        for (long id:ids) { deactivate(id,now); cancelJobs(id,now); }
    }
    /** Withdrawal ends every device binding without deleting the subscription audit rows. */
    public int deactivateUser(long owner,Instant now) {
        List<Long> ids=jdbc.queryForList("SELECT id FROM push_subscriptions WHERE user_id=? AND active ORDER BY id FOR UPDATE",
                Long.class,owner);
        for (long id:ids) { deactivate(id,now); cancelJobs(id,now); }
        return ids.size();
    }
    public void expireBinding(long id,long version,Instant now) {
        int changed=jdbc.update("UPDATE push_subscriptions SET active=false,deactivated_at=?,binding_version=binding_version+1,updated_at=? WHERE id=? AND binding_version=? AND active",
                Timestamp.from(now),Timestamp.from(now),id,version);
        if (changed>0) { cancelJobs(id,now); }
    }
    public void cancelJobs(long id,Instant now) {
        jdbc.update("UPDATE notification_jobs SET status='CANCELED',canceled_at=?,updated_at=?,last_error_code='SUBSCRIPTION_DISABLED',lease_token=NULL,lease_expires_at=NULL WHERE subscription_id=? AND status IN ('PENDING','RETRY_WAIT','PROCESSING')",
                Timestamp.from(now),Timestamp.from(now),id);
    }
    private void deactivate(long id,Instant now) {
        jdbc.update("UPDATE push_subscriptions SET active=false,deactivated_at=?,binding_version=binding_version+1,updated_at=? WHERE id=? AND active",
                Timestamp.from(now),Timestamp.from(now),id);
    }
    public record Subscription(long id,long owner,String endpoint,String p256dh,String auth,String deviceName,boolean active,
            String sessionDigest,long bindingVersion,Instant createdAt,Instant updatedAt) {
        public PushSubscriptionResponse view(String session) {
            return new PushSubscriptionResponse(Long.toString(id),deviceName,active,sessionDigest.equals(session),Long.toString(bindingVersion),createdAt,updatedAt);
        }
        @Override public String toString() { return "Subscription [id="+id+", active="+active+"]"; }
    }
}
