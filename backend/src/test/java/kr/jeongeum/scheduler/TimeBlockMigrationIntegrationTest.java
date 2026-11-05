package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V12 preservation and V13 constraints run only in this disposable PostgreSQL. */
@Testcontainers
@Timeout(90)
class TimeBlockMigrationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("time_block_migration_test").withUsername("time_block_migration_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void v13AddsTwelveBlocksWithoutChangingAnyExistingDataReferencesOrIdentitySequences() throws Exception {
        String schema = "full_day_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(12, migration(schema, "12").migrate().migrationsExecuted);
        Map<String, String> contents;
        String oldBlocks;
        String sequences;
        try (Connection connection = connection(schema)) {
            seedExistingReferences(connection);
            contents = contents(connection);
            oldBlocks = snapshot(connection, "SELECT * FROM time_blocks");
            sequences = sequences(connection);
            assertEquals(2, scalar(connection, "SELECT count(*) FROM reservations"));
            assertEquals(7, scalar(connection, "SELECT count(*) FROM reservation_assets"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recurring_occurrence_reservations"));
        }
        assertEquals(1, migration(schema, "13").migrate().migrationsExecuted);
        assertEquals(0, migration(schema, "13").migrate().migrationsExecuted);
        try (Connection connection = connection(schema)) {
            assertEquals(contents, contents(connection), "Every table other than the time-block catalog and Flyway must be identical");
            assertEquals(sequences, sequences(connection), "Adding codes must not advance existing identity sequences");
            assertEquals(oldBlocks, snapshot(connection, "SELECT * FROM time_blocks WHERE code IN "
                    + "('B01','B02','B03','B04','B05','B06','B07','B08','B09','B10')"));
            assertEquals(22, scalar(connection, "SELECT count(*) FROM time_blocks"));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT code,start_minute,end_minute,duration_minutes FROM time_blocks ORDER BY start_minute")) {
                for (TimeBlock block : TimeBlock.chronological()) {
                    assertTrue(rows.next());
                    assertEquals(block.name(), rows.getString(1));
                    assertEquals(block.startMinute(), rows.getInt(2));
                    assertEquals(block.endMinute(), rows.getInt(3));
                    assertEquals(block.durationMinutes(), rows.getInt(4));
                }
                assertFalse(rows.next());
            }
            assertEquals(2, scalar(connection, "SELECT count(*) FROM reservations r JOIN time_blocks b "
                    + "ON (r.time_block_code,r.block_start_minute,r.block_end_minute,r.duration_minutes) "
                    + "= (b.code,b.start_minute,b.end_minute,b.duration_minutes)"));
            assertCheckViolation(connection, "INSERT INTO time_blocks VALUES ('B23',0,60,60)");
            assertCheckViolation(connection, "UPDATE time_blocks SET start_minute=1,end_minute=61 WHERE code='B11'");
            // A fresh named schema also has Flyway's successful SCHEMA marker
            // with a NULL version; count only the thirteen versioned migrations.
            assertEquals(13, scalar(connection, "SELECT count(version) FROM flyway_schema_history WHERE success"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE NOT success"));
        }
    }

    private static void seedExistingReferences(Connection connection) throws Exception {
        long admin = id(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                + "VALUES ('migration-admin','synthetic-no-login-hash','합성 관리자','GLOBAL_ADMIN','ACTIVE','2026-11-04T01:00:00Z') RETURNING id");
        long member = id(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                + "VALUES ('migration-member','synthetic-no-login-hash','합성 회원','MEMBER','ACTIVE','2026-11-04T01:00:00Z') RETURNING id");
        long roster = id(connection, "INSERT INTO roster_entries(name,user_id) VALUES ('합성 명단'," + member + ") RETURNING id");
        long group = id(connection, "INSERT INTO teams(name,kind) VALUES ('합성 보존 팀','REGULAR') RETURNING id");
        execute(connection, "INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (" + group + "," + roster + ",'LEADER')");
        execute(connection, "INSERT INTO booking_days(reservation_date,status,opened_at,created_at,recurring_prepared) "
                + "VALUES ('2026-11-05','OPEN','2026-11-04T01:00:00Z','2026-11-04T01:00:00Z',true)");
        long fixedOperation = id(connection, "INSERT INTO reservation_operations(kind,actor_kind,occurred_at) "
                + "VALUES ('FIXED_GENERATE','SYSTEM','2026-11-04T01:00:00Z') RETURNING id");
        long personalOperation = id(connection, "INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at) "
                + "VALUES ('CREATE','USER'," + member + ",'2026-11-04T01:00:00Z') RETURNING id");
        long fixed = id(connection, """
                INSERT INTO reservations(owner_type,purpose,team_id,creator_user_id,reservation_date,time_block_code,
                block_start_minute,block_end_minute,duration_minutes,block_start_at,block_end_at,usable_from,
                origin_kind,quota_exempt,created_at,created_operation_id)
                VALUES ('TEAM','REGULAR',%d,%d,'2026-11-05','B01',720,780,60,
                '2026-11-05T12:00:00+09:00','2026-11-05T13:00:00+09:00','2026-11-05T12:00:00+09:00',
                'FIXED',true,'2026-11-04T01:00:00Z',%d) RETURNING id
                """.formatted(group, admin, fixedOperation));
        long personal = id(connection, """
                INSERT INTO reservations(owner_type,purpose,owner_user_id,creator_user_id,reservation_date,time_block_code,
                block_start_minute,block_end_minute,duration_minutes,block_start_at,block_end_at,usable_from,
                origin_kind,created_at,created_operation_id)
                VALUES ('PERSONAL','PERSONAL',%d,%d,'2026-11-05','B10',1350,1440,90,
                '2026-11-05T22:30:00+09:00','2026-11-06T00:00:00+09:00','2026-11-05T22:30:00+09:00',
                'MANUAL','2026-11-04T01:00:00Z',%d) RETURNING id
                """.formatted(member, member, personalOperation));
        execute(connection, "INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at) "
                + "SELECT " + fixed + ",'2026-11-05','B01',id,'TEAM','2026-11-04T01:00:00Z' FROM assets");
        execute(connection, "INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at) "
                + "SELECT " + personal + ",'2026-11-05','B10',id,'PERSONAL','2026-11-04T01:00:00Z' FROM assets WHERE code='DRUMS'");
        execute(connection, "INSERT INTO quota_entries(reservation_id,operation_id,quota_date,team_id,amount_minutes,entry_kind,created_at) "
                + "VALUES (" + fixed + "," + fixedOperation + ",'2026-11-05'," + group + ",0,'RESERVE','2026-11-04T01:00:00Z')");
        execute(connection, "INSERT INTO quota_entries(reservation_id,operation_id,quota_date,owner_user_id,amount_minutes,entry_kind,created_at) "
                + "VALUES (" + personal + "," + personalOperation + ",'2026-11-05'," + member + ",0,'RESERVE','2026-11-04T01:00:00Z')");
        long assignment = id(connection, "INSERT INTO recurring_assignments(team_id,created_by_user_id,created_at,updated_at) "
                + "VALUES (" + group + "," + admin + ",'2026-11-04T01:00:00Z','2026-11-04T01:00:00Z') RETURNING id");
        long revision = id(connection, "INSERT INTO recurring_assignment_revisions(assignment_id,revision_no,effective_date,weekday,time_block_code,created_by_user_id,created_at) "
                + "VALUES (" + assignment + ",1,'2026-11-05',4,'B01'," + admin + ",'2026-11-04T01:00:00Z') RETURNING id");
        long occurrence = id(connection, "INSERT INTO recurring_occurrences(assignment_id,week_start_date,pattern_revision_id,original_scheduled_date,original_block_code,state,created_at,updated_at) "
                + "VALUES (" + assignment + ",'2026-11-02'," + revision + ",'2026-11-05','B01','MATERIALIZED','2026-11-04T01:00:00Z','2026-11-04T01:00:00Z') RETURNING id");
        execute(connection, "INSERT INTO recurring_occurrence_reservations VALUES (" + occurrence + "," + fixed + ",'ORIGINAL',true)");
        execute(connection, "INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                + "VALUES ('synthetic-primary','synthetic-session',1,1,7776000,7776000001,'migration-member')");
        execute(connection, "INSERT INTO spring_session_attributes VALUES ('synthetic-primary','synthetic.proof',decode('7072657365727665','hex'))");
    }

    private static Flyway migration(String schema, String version) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(version).load();
    }
    private static Connection connection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword());
        connection.setSchema(schema);
        return connection;
    }
    private static Map<String,String> contents(Connection connection) throws Exception {
        Map<String,String> result = new LinkedHashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT tablename FROM pg_tables WHERE schemaname=current_schema() AND tablename NOT IN ('flyway_schema_history','time_blocks') ORDER BY tablename")) {
            while (rows.next()) {
                String table = rows.getString(1);
                result.put(table, snapshot(connection, "SELECT * FROM " + table));
            }
        }
        assertEquals(37, result.size());
        return result;
    }
    private static String sequences(Connection connection) throws Exception {
        return snapshot(connection, "SELECT sequencename,start_value,increment_by,last_value FROM pg_sequences WHERE schemaname=current_schema()");
    }
    private static String snapshot(Connection connection, String select) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM (" + select + ") r")) {
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private static long scalar(Connection connection, String query) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(query)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private static long id(Connection connection, String query) throws Exception { return scalar(connection, query); }
    private static void execute(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }
    private static void assertCheckViolation(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement()) {
            SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate(sql));
            assertEquals("23514", error.getSQLState());
        }
    }
}
