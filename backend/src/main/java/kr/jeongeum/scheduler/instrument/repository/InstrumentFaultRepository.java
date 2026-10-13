package kr.jeongeum.scheduler.instrument.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultResponse;
import kr.jeongeum.scheduler.instrument.domain.InstrumentFault;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class InstrumentFaultRepository {
    private static final RowMapper<InstrumentFault> FAULT = (row, index) -> new InstrumentFault(row.getLong("id"),
            row.getLong("asset_id"), row.getString("status"), row.getString("description"), row.getString("origin"),
            row.getObject("reported_by_user_id", Long.class), row.getTimestamp("reported_at").toInstant(),
            row.getObject("resolved_by_user_id", Long.class), instant(row.getTimestamp("resolved_at")),
            row.getString("resolution_note"), row.getLong("version"));
    private static final RowMapper<Instrument> ASSET = (row, index) -> new Instrument(row.getLong("id"),
            row.getString("code"), row.getString("name"), row.getString("status"), row.getLong("version"));
    private static final RowMapper<InstrumentFaultResponse> RESPONSE = (row, index) -> new InstrumentFaultResponse(
            row.getString("id"), row.getString("instrument_code"), row.getString("instrument_name"), row.getString("status"),
            row.getString("description"), row.getString("origin"), row.getString("reported_by_user_id"),
            row.getTimestamp("reported_at").toInstant(), row.getString("resolved_by_user_id"),
            instant(row.getTimestamp("resolved_at")), row.getString("resolution_note"), row.getLong("version"), row.getLong("asset_version"));
    public record Replay(String fingerprint, long incidentId, int status) {}
    private final JdbcTemplate jdbc;
    public InstrumentFaultRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Optional<Instrument> lockAsset(String code) {
        return jdbc.query("SELECT * FROM assets WHERE code = ? FOR UPDATE", ASSET, code).stream().findFirst();
    }
    public Optional<Instrument> lockAsset(long id) {
        return jdbc.query("SELECT * FROM assets WHERE id = ? FOR UPDATE", ASSET, id).stream().findFirst();
    }
    public Optional<InstrumentFault> find(long id) {
        return jdbc.query("SELECT * FROM asset_incidents WHERE id = ?", FAULT, id).stream().findFirst();
    }
    public Optional<InstrumentFault> lock(long id) {
        return jdbc.query("SELECT * FROM asset_incidents WHERE id = ? FOR UPDATE", FAULT, id).stream().findFirst();
    }
    public void assetStatus(long id, String status) {
        jdbc.update("UPDATE assets SET status = ?, version = version + 1 WHERE id = ?", status, id);
    }
    public InstrumentFault insert(long assetId, long actorId, String description, Instant now) {
        return jdbc.query("INSERT INTO asset_incidents(asset_id,description,origin,reported_by_user_id,reported_at) VALUES (?,?,'ADMIN',?,?) RETURNING *",
                FAULT, assetId, description, actorId, Timestamp.from(now)).getFirst();
    }
    public InstrumentFault resolve(long id, long actorId, String note, Instant now) {
        return jdbc.query("UPDATE asset_incidents SET status='RESOLVED',resolved_by_user_id=?,resolved_at=?,resolution_note=?,version=version+1 WHERE id=? RETURNING *",
                FAULT, actorId, Timestamp.from(now), note, id).getFirst();
    }
    public InstrumentFaultResponse response(long id) {
        return jdbc.query("""
                SELECT i.*, a.code AS instrument_code, a.name AS instrument_name, a.version AS asset_version
                FROM asset_incidents i JOIN assets a ON a.id=i.asset_id WHERE i.id=?
                """, RESPONSE, id).getFirst();
    }
    public List<InstrumentFaultResponse> history(String code, Long beforeId, int limit) {
        return jdbc.query("""
                SELECT i.*, a.code AS instrument_code, a.name AS instrument_name, a.version AS asset_version
                FROM asset_incidents i JOIN assets a ON a.id=i.asset_id
                WHERE (?::varchar IS NULL OR a.code=?) AND (?::bigint IS NULL OR i.id<?)
                ORDER BY i.id DESC LIMIT ?
                """, RESPONSE, code, code, beforeId, beforeId, limit);
    }
    public Optional<Replay> replay(long actorId, String digest) {
        return jdbc.query("SELECT * FROM instrument_fault_idempotency_requests WHERE actor_user_id=? AND key_digest=?",
                (row, index) -> new Replay(row.getString("request_fingerprint"), row.getLong("result_incident_id"), row.getInt("response_status")),
                actorId, digest).stream().findFirst();
    }
    public void remember(long actorId, String digest, String fingerprint, String route, int status, long incidentId, Instant now) {
        jdbc.update("""
                INSERT INTO instrument_fault_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_incident_id,created_at)
                VALUES (?,?,?,'POST',?,?,?,?)
                """, actorId, digest, fingerprint, route, status, incidentId, Timestamp.from(now));
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
