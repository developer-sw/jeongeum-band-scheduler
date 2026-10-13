package kr.jeongeum.scheduler.exchange.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.exchange.domain.ExchangeRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ExchangeRepository {
    private static final RowMapper<ExchangeRequest> REQUEST = (row, index) -> new ExchangeRequest(row.getLong("id"),
            row.getLong("requester_user_id"), row.getString("status"), row.getTimestamp("expires_at").toInstant(),
            row.getTimestamp("created_at").toInstant(), instant(row.getTimestamp("processed_at")),
            row.getObject("processed_by_user_id", Long.class), row.getString("state_reason"), row.getLong("version"));
    public record Item(long reservationId, long expectedVersion, String side, int sequence) {}
    public record Replay(String fingerprint, long requestId, int status) {}
    private final JdbcTemplate jdbc;
    public ExchangeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public ExchangeRequest insert(long actorId, Instant expires, Instant now) {
        return jdbc.query("INSERT INTO exchange_requests(requester_user_id,expires_at,created_at) VALUES (?,?,?) RETURNING *",
                REQUEST, actorId, Timestamp.from(expires), Timestamp.from(now)).getFirst();
    }
    public void item(long requestId, long reservationId, long version, String side, int sequence) {
        jdbc.update("INSERT INTO exchange_request_items(exchange_request_id,reservation_id,expected_reservation_version,side,sequence_no) VALUES (?,?,?,?,?)",
                requestId, reservationId, version, side, sequence);
    }
    public Optional<ExchangeRequest> find(long id) {
        return jdbc.query("SELECT * FROM exchange_requests WHERE id = ?", REQUEST, id).stream().findFirst();
    }
    public Optional<ExchangeRequest> lock(long id) {
        return jdbc.query("SELECT * FROM exchange_requests WHERE id = ? FOR UPDATE", REQUEST, id).stream().findFirst();
    }
    public List<Long> allIds() { return jdbc.queryForList("SELECT id FROM exchange_requests ORDER BY id DESC", Long.class); }
    public List<Long> pendingIds() { return jdbc.queryForList("SELECT id FROM exchange_requests WHERE status = 'PENDING' ORDER BY id", Long.class); }
    /** The caller holds the directory guard; capture before reservation writes invalidate these requests. */
    public List<Long> pendingForReservations(List<Long> reservationIds) {
        if (reservationIds.isEmpty()) { return List.of(); }
        String placeholders = String.join(",", java.util.Collections.nCopies(reservationIds.size(), "?"));
        return jdbc.queryForList("SELECT DISTINCT r.id FROM exchange_requests r JOIN exchange_request_items i ON i.exchange_request_id=r.id "
                + "WHERE r.status='PENDING' AND i.reservation_id IN (" + placeholders + ") ORDER BY r.id",
                Long.class, reservationIds.toArray());
    }
    public List<Item> items(long id) {
        return jdbc.query("SELECT * FROM exchange_request_items WHERE exchange_request_id = ? ORDER BY side DESC,sequence_no",
                (row, index) -> new Item(row.getLong("reservation_id"), row.getLong("expected_reservation_version"),
                        row.getString("side"), row.getInt("sequence_no")), id);
    }
    public void terminal(long id, String status, Long actorId, Instant now, String reason) {
        jdbc.update("UPDATE exchange_requests SET status=?,processed_by_user_id=?,processed_at=?,state_reason=?,version=version+1 WHERE id=? AND status='PENDING'",
                status, actorId, Timestamp.from(now), reason, id);
    }
    public void invalidateReservations(List<Long> ids, Instant now) {
        for (long id : ids) {
            jdbc.update("""
                    UPDATE exchange_requests SET status='INVALIDATED',processed_at=?,state_reason='SOURCE_CHANGED',version=version+1
                    WHERE status='PENDING' AND id IN (SELECT exchange_request_id FROM exchange_request_items WHERE reservation_id=?)
                    """, Timestamp.from(now), id);
        }
    }
    public List<String> allocation(long requestId, long reservationId) {
        return jdbc.queryForList("""
                SELECT a.code FROM exchange_instrument_allocations m JOIN assets a ON a.id=m.asset_id
                WHERE m.exchange_request_id=? AND m.reservation_id=? ORDER BY m.target_sequence_no
                """, String.class, requestId, reservationId);
    }
    public void allocate(long requestId, long reservationId, List<Long> assetIds) {
        jdbc.update("DELETE FROM exchange_instrument_allocations WHERE exchange_request_id=? AND reservation_id=?", requestId, reservationId);
        for (int index = 0; index < assetIds.size(); index++) {
            jdbc.update("INSERT INTO exchange_instrument_allocations(exchange_request_id,reservation_id,target_sequence_no,asset_id) VALUES (?,?,?,?)",
                    requestId, reservationId, index, assetIds.get(index));
        }
        jdbc.update("UPDATE exchange_requests SET version=version+1 WHERE id=?", requestId);
    }
    public long operation(long requestId, long actorId, Instant now) {
        return jdbc.queryForObject("""
                INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at,exchange_request_id)
                VALUES ('EXCHANGE','USER',?,?,?) RETURNING id
                """, Long.class, actorId, Timestamp.from(now), requestId);
    }
    public List<Long> successors(long requestId) {
        return jdbc.queryForList("""
                SELECT DISTINCT l.successor_reservation_id FROM reservation_lineage l JOIN reservation_operations o ON o.id=l.operation_id
                WHERE o.exchange_request_id=? ORDER BY l.successor_reservation_id
                """, Long.class, requestId);
    }
    public Optional<Replay> replay(long actorId, String digest) {
        return jdbc.query("SELECT * FROM exchange_idempotency_requests WHERE actor_user_id=? AND key_digest=? FOR UPDATE",
                (row,index) -> new Replay(row.getString("request_fingerprint"),row.getLong("result_exchange_request_id"),row.getInt("response_status")),
                actorId,digest).stream().findFirst();
    }
    public void remember(long actorId,String digest,String fingerprint,String method,String route,int status,long requestId,Instant now) {
        jdbc.update("""
                INSERT INTO exchange_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_exchange_request_id,created_at)
                VALUES (?,?,?,?,?,?,?,?)
                """, actorId,digest,fingerprint,method,route,status,requestId,Timestamp.from(now));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
