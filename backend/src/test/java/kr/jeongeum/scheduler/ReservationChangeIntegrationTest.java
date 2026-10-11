package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.recurring.service.RecurringOpeningService;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "jeongeum.recurring.worker.enabled=false")
@Import(ReservationChangeIntegrationTest.TestTime.class)
@Timeout(90)
class ReservationChangeIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final Set<String> INSTRUMENTS = Set.of(
            "VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");
    private static final String[] BOOKING_TABLES = {"reservations", "reservation_assets", "quota_entries",
            "reservation_operations", "reservation_lineage", "idempotency_requests",
            "recurring_assignments", "recurring_assignment_revisions", "recurring_occurrences",
            "recurring_occurrence_reservations", "recurring_idempotency_requests"};

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("reservation_change_test").withUsername("reservation_change_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("APP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @Autowired RecurringOpeningService opening;
    private String password;
    private Person administrator;

    @BeforeEach
    void setup() throws Exception {
        // This reset and every direct fixture/lock connection target only this disposable container.
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL', version=0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
        // Opening is independent of the booking transaction; prepare targets before rollback snapshots.
        opening.prepareRange(TODAY, TODAY.plusDays(14));
    }

    @Test
    void personalChangePreservesOwnerCreatorAndHistoryAndLinksOneSuccessorToItsActualActor() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        JsonNode successor = changed(administrator.client(), source, TODAY.plusDays(1), "B02", List.of("VOCAL"));
        assertNotEquals(id(source), id(successor));
        assertEquals(source.get("ownerUserId"), successor.get("ownerUserId"));
        assertEquals(source.get("creatorUserId"), successor.get("creatorUserId"));
        assertEquals("PERSONAL", successor.get("subjectType").asString());
        assertEquals("PERSONAL", successor.get("purpose").asString());
        assertEquals("CONFIRMED", successor.get("status").asString());
        assertEquals("CHANGE", successor.get("originKind").asString());
        assertFalse(successor.get("quotaExempt").asBoolean());
        assertFalse(successor.get("dualAssetException").asBoolean());
        assertEquals(Set.of("VOCAL"), strings(successor.get("instrumentCodes")));
        assertEquals(60, successor.get("quotaMinutes").asInt());
        assertEquals(successor.get("startsAt"), successor.get("usableFrom"));
        assertEquals(0, quota(owner, null, TODAY));
        assertEquals(60, quota(owner, null, TODAY.plusDays(1)));
        assertEquals(0, claims(id(source)));
        assertEquals(1, claims(id(successor)));
        assertEquals(2, count("reservations"));
        assertEquals(3, count("quota_entries"));
        assertEquals(1, changeCount());
        assertEquals(administrator.member().id(), jdbc.queryForObject(
                "SELECT actor_user_id FROM reservation_operations WHERE kind='CHANGE'", Long.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_lineage WHERE source_reservation_id=? "
                + "AND successor_reservation_id=? AND operation_id=(SELECT created_operation_id FROM reservations WHERE id=?)",
                Integer.class, id(source), id(successor), id(successor)));
        JsonNode history = detail(owner.client(), source);
        assertEquals("REPLACED", history.get("status").asString());
        assertEquals(source.get("version").asLong() + 1, history.get("version").asLong());
        for (String field : List.of("date", "blockCode", "startsAt", "endsAt", "usableFrom", "instrumentCodes", "creatorUserId", "ownerUserId")) {
            assertEquals(source.get(field), history.get(field), "Source history must retain " + field);
        }
        assertEquals(Set.of(successor.get("id").asString()), strings(history.get("successorIds")));
        assertFalse(history.get("canCancel").asBoolean());
        assertFalse(history.get("canChange").asBoolean());
        assertTrue(detail(owner.client(), successor).get("canChange").asBoolean());
        assertEquals(Set.of(source.get("id").asString(), successor.get("id").asString()),
                strings(success(owner.client().get("/api/v1/reservations?scope=mine"), 200), "id"));
    }

    @Test
    void regularAndAdditionalTeamsKeepCreatorAndSixClaimsAcrossCreatorLeaderAndAdministratorChanges() throws Exception {
        Person creator = person("MEMBER");
        Person leader = person("MEMBER");
        for (String kind : List.of("REGULAR", "ADDITIONAL")) {
            long group = team(kind);
            join(group, creator, false);
            join(group, leader, true);
            LocalDate date = "REGULAR".equals(kind) ? TODAY : TODAY.plusDays(1);
            JsonNode source = success(create(creator.client(), teamBody(date, "B01", group)), 201);
            JsonNode first = changed(creator.client(), source, date, "B02", null);
            JsonNode second = changed(leader.client(), first, date, "B07", null);
            JsonNode last = changed(administrator.client(), second, date, "B03", null);
            assertEquals(Long.toString(group), last.get("teamId").asString());
            assertEquals(kind, last.get("purpose").asString());
            assertEquals(Long.toString(creator.member().id()), last.get("creatorUserId").asString());
            assertTrue(last.get("ownerUserId").isNull());
            assertEquals(INSTRUMENTS, strings(last.get("instrumentCodes")));
            assertFalse(last.get("dualAssetException").asBoolean());
            assertEquals(6, claims(id(last)));
            assertEquals(0, claims(id(source)));
            assertEquals(0, claims(id(first)));
            assertEquals(0, claims(id(second)));
            assertEquals(60, quota(null, group, date));
        }
        assertEquals(6, changeCount());
        assertEquals(6, count("reservation_lineage"));
        assertEquals(12, activeClaims());
    }

    @Test
    void sameDateChangeCreditsItsOwnHourAndSixtyNinetyConversionsUseFinalLedgerTotal() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        success(create(owner.client(), personal(TODAY, "B02", "VOCAL")), 201);
        JsonNode moved = changed(owner.client(), source, TODAY, "B03", List.of("DRUMS"));
        assertEquals(120, quota(owner, null, TODAY));
        JsonNode ninety = changed(owner.client(), moved, TODAY, "B07", List.of("DRUMS"));
        assertEquals(0, ninety.get("quotaMinutes").asInt());
        assertEquals(60, quota(owner, null, TODAY));
        JsonNode sixty = changed(owner.client(), ninety, TODAY, "B04", List.of("DRUMS"));
        assertEquals(60, sixty.get("quotaMinutes").asInt());
        assertEquals(120, quota(owner, null, TODAY));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE entry_kind='RELEASE'", Integer.class));
        Map<String, String> before = snapshots();
        assertError(create(owner.client(), personal(TODAY, "B05", "DRUMS")), 409, "DAILY_LIMIT_EXCEEDED");
        assertEquals(before, snapshots());
    }

    @Test
    void crossDateChangeReleasesOldDayReservesNewDayAndDoesNotMixPersonalOrTeamQuotas() throws Exception {
        Person owner = person("MEMBER");
        long group = team("REGULAR");
        join(group, owner, false);
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        success(create(owner.client(), personal(TODAY, "B02", "VOCAL")), 201);
        success(create(owner.client(), personal(TODAY.plusDays(1), "B01", "DRUMS")), 201);
        success(create(owner.client(), teamBody(TODAY.plusDays(1), "B04", group)), 201);
        success(create(owner.client(), teamBody(TODAY.plusDays(1), "B05", group)), 201);
        changed(owner.client(), source, TODAY.plusDays(1), "B02", List.of("DRUMS"));
        assertEquals(60, quota(owner, null, TODAY));
        assertEquals(120, quota(owner, null, TODAY.plusDays(1)));
        assertEquals(120, quota(null, group, TODAY.plusDays(1)));
        success(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 201);
        JsonNode ninety = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        assertUnchangedFailure(owner.client(), ninety, changeBody(ninety, TODAY.plusDays(1), "B03", List.of("DRUMS")),
                409, "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void personalOverlapAssetConflictAndTeamExclusivityFailuresPreserveEveryBookingTable() throws Exception {
        Person owner = person("MEMBER");
        Person other = person("MEMBER");
        long group = team("REGULAR");
        join(group, owner, false);
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        success(create(owner.client(), personal(TODAY, "B08", "VOCAL")), 201);
        success(create(other.client(), personal(TODAY, "B09", "DRUMS")), 201);
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B08", List.of("BASS")), 409, "PERSONAL_OVERLAP");
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B09", List.of("DRUMS")), 409, "RESERVATION_CONFLICT");
        JsonNode teamBooking = success(create(owner.client(), teamBody(TODAY, "B10", group)), 201);
        assertUnchangedFailure(owner.client(), teamBooking, changeBody(teamBooking, TODAY, "B09", null), 409, "RESERVATION_CONFLICT");
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B10", List.of("KEYBOARD")), 409, "RESERVATION_CONFLICT");
        JsonNode changedInstrument = changed(owner.client(), source, TODAY, "B07", List.of("KEYBOARD"));
        assertEquals(Set.of("KEYBOARD"), strings(changedInstrument.get("instrumentCodes")));
        assertEquals(0, claims(id(source)));
        assertEquals(1, claims(id(changedInstrument)));
    }

    @Test
    void noOpPreservesFaultedPersonalPositionAndFixedExemptionWithoutChangeOperationsOrLineage() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        jdbc.update("UPDATE assets SET status='FAULTY',version=version+1 WHERE code='DRUMS'");
        Map<String, String> before = snapshotsWithoutIdempotency();
        long oldIdempotency = count("idempotency_requests");
        String requestKey = key();
        Map<String, Object> body = changeBody(source, TODAY, "B01", List.of("DRUMS"));
        JsonNode result = success(change(owner.client(), source, body, requestKey), 200);
        assertEquals(0, result.get("replacedReservationIds").size());
        assertEquals(id(source), id(result.get("reservations").get(0)));
        assertEquals(before, snapshotsWithoutIdempotency());
        assertEquals(oldIdempotency + 1, count("idempotency_requests"));
        success(change(owner.client(), source, body, requestKey), 200);
        assertEquals(oldIdempotency + 1, count("idempotency_requests"));
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B02", List.of("DRUMS")), 409, "ASSET_FAULTY");

        long group = team("REGULAR");
        join(group, owner, true);
        createAssignment(group, "B03");
        JsonNode fixed = fixed(group, TODAY, "B03");
        Map<String, String> fixedBefore = snapshotsWithoutIdempotency();
        JsonNode fixedNoOp = success(change(owner.client(), fixed, changeBody(fixed, TODAY, "B03", null), key()), 200);
        assertEquals(0, fixedNoOp.get("replacedReservationIds").size());
        assertEquals(id(fixed), id(fixedNoOp.get("reservations").get(0)));
        assertTrue(fixedNoOp.get("reservations").get(0).get("quotaExempt").asBoolean());
        assertFalse(fixedNoOp.get("reservations").get(0).get("dualAssetException").asBoolean());
        assertEquals(fixedBefore, snapshotsWithoutIdempotency());
        assertEquals(0, quota(null, group, TODAY));
        assertEquals(0, changeCount());
    }

    @Test
    void newlyPlacedPersonalInstrumentIsCheckedEvenOnInstrumentOnlyChangesWhileTeamsReceiveWarnings() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        jdbc.update("UPDATE assets SET status='FAULTY',version=version+1 WHERE code='VOCAL'");
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B07", List.of("VOCAL")), 409, "ASSET_FAULTY");
        long group = team("ADDITIONAL");
        join(group, owner, false);
        JsonNode teamSource = success(create(owner.client(), teamBody(TODAY, "B08", group)), 201);
        JsonNode successor = changed(owner.client(), teamSource, TODAY, "B09", null);
        assertEquals(Set.of("VOCAL"), strings(successor.get("teamFaultWarnings")));
        assertEquals(INSTRUMENTS, strings(successor.get("instrumentCodes")));
        assertEquals(6, claims(id(successor)));
        assertEquals("CONFIRMED", detail(owner.client(), source).get("status").asString());
    }

    @Test
    void rejectsForgedSubjectsUnknownFieldsInvalidVersionShapeAndNewDualInstrumentPrivileges() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        List<Map<String, Object>> invalid = new ArrayList<>();
        for (Object version : List.of(-1, 0.5)) {
            Map<String, Object> body = changeBody(source, TODAY, "B08", List.of("DRUMS"));
            body.put("expectedVersion", version);
            invalid.add(body);
        }
        Map<String, Object> noVersion = changeBody(source, TODAY, "B08", List.of("DRUMS"));
        noVersion.remove("expectedVersion");
        invalid.add(noVersion);
        for (Object instruments : List.of(List.of(), List.of("DRUMS", "VOCAL"), List.of("DRUMS", "DRUMS"), List.of("INVALID"), "DRUMS")) {
            Map<String, Object> body = changeBody(source, TODAY, "B08", List.of("DRUMS"));
            body.put("instrumentCodes", instruments);
            invalid.add(body);
        }
        Map<String, Object> nullInstrument = changeBody(source, TODAY, "B08", List.of("DRUMS"));
        nullInstrument.put("instrumentCodes", java.util.Arrays.asList((String) null));
        invalid.add(nullInstrument);
        Map<String, Object> absentInstrument = changeBody(source, TODAY, "B08", null);
        invalid.add(absentInstrument);
        for (String field : List.of("subjectType", "teamId", "ownerUserId", "creatorUserId", "quotaExempt", "dualAssetException", "originKind", "startsAt")) {
            Map<String, Object> body = changeBody(source, TODAY, "B08", List.of("DRUMS"));
            body.put(field, "forged");
            invalid.add(body);
        }
        Map<String, Object> badDate = changeBody(source, TODAY, "B08", List.of("DRUMS"));
        badDate.put("date", "2026-02-30");
        invalid.add(badDate);
        for (Map<String, Object> body : invalid) { assertUnchangedFailure(owner.client(), source, body, 400, "INVALID_INPUT"); }
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B11", List.of("DRUMS")), 400, "INVALID_BLOCK");
        Map<String, String> before = snapshots();
        assertError(owner.client().mutate("PATCH", reservationPath(source), changeBody(source, TODAY, "B08", List.of("DRUMS"))), 400, "INVALID_INPUT");
        assertError(change(owner.client(), source, changeBody(source, TODAY, "B08", List.of("DRUMS")), "short"), 400, "INVALID_INPUT");
        assertEquals(before, snapshots());
        long group = team("REGULAR");
        join(group, owner, false);
        JsonNode teamSource = success(create(owner.client(), teamBody(TODAY, "B09", group)), 201);
        assertUnchangedFailure(owner.client(), teamSource, changeBody(teamSource, TODAY, "B10", List.of("DRUMS")), 400, "INVALID_INPUT");
    }

    @Test
    void existingExchangeDualExceptionCanMoveWithTwoInstrumentsAndMustSelectOneWhenChangingToSixty() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        // Exchange is the next feature. Seed only its already-defined historical result shape.
        jdbc.update("UPDATE reservations SET origin_kind='EXCHANGE',dual_asset_exception=TRUE WHERE id=?", id(source));
        jdbc.update("INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at) "
                + "SELECT ?,?,'B07',id,'PERSONAL',? FROM assets WHERE code='VOCAL'", id(source), TODAY, Timestamp.from(clock.instant()));
        source = detail(owner.client(), source);
        String noopKey = key();
        JsonNode noop = success(change(owner.client(), source, changeBody(source, TODAY, "B07", List.of("VOCAL", "DRUMS")), noopKey), 200);
        assertEquals(0, noop.get("replacedReservationIds").size());
        assertEquals(id(source), id(noop.get("reservations").get(0)));
        JsonNode reordered = success(change(owner.client(), source, changeBody(source, TODAY, "B07", List.of("DRUMS", "VOCAL")), noopKey), 200);
        assertEquals(id(source), id(reordered.get("reservations").get(0)));
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B08", List.of("DRUMS", "VOCAL", "BASS")), 400, "INVALID_INPUT");
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY, "B01", List.of("DRUMS", "VOCAL")), 400, "INVALID_INPUT");
        JsonNode moved = changed(owner.client(), source, TODAY, "B08", List.of("KEYBOARD", "DRUMS"));
        assertEquals(Set.of("KEYBOARD", "DRUMS"), strings(moved.get("instrumentCodes")));
        assertEquals(2, claims(id(moved)));
        assertTrue(moved.get("dualAssetException").asBoolean());
        assertTrue(jdbc.queryForObject("SELECT dual_asset_exception FROM reservations WHERE id=?", Boolean.class, id(moved)));
        JsonNode oneInstrumentNinety = changed(owner.client(), moved, TODAY, "B09", List.of("KEYBOARD"));
        assertTrue(oneInstrumentNinety.get("dualAssetException").asBoolean());
        assertEquals(1, claims(id(oneInstrumentNinety)));
        JsonNode twoAgain = changed(owner.client(), oneInstrumentNinety, TODAY, "B10", List.of("KEYBOARD", "DRUMS"));
        assertTrue(twoAgain.get("dualAssetException").asBoolean());
        assertEquals(2, claims(id(twoAgain)));
        JsonNode sixty = changed(owner.client(), twoAgain, TODAY, "B01", List.of("KEYBOARD"));
        assertEquals(Set.of("KEYBOARD"), strings(sixty.get("instrumentCodes")));
        assertEquals(1, claims(id(sixty)));
        assertFalse(jdbc.queryForObject("SELECT dual_asset_exception FROM reservations WHERE id=?", Boolean.class, id(sixty)));
        assertFalse(sixty.get("dualAssetException").asBoolean());
        assertEquals(60, quota(owner, null, TODAY));
        assertUnchangedFailure(owner.client(), sixty, changeBody(sixty, TODAY, "B09", List.of("KEYBOARD", "DRUMS")), 400, "INVALID_INPUT");
    }

    @Test
    void currentMutationAuthorityIsNarrowerThanTeamReadAccessAndAuthenticationAndCsrfAreRequired() throws Exception {
        Person creator = person("MEMBER");
        Person teammate = person("MEMBER");
        Person leader = person("MEMBER");
        Person outsider = person("MEMBER");
        long group = team("REGULAR");
        join(group, creator, false);
        join(group, teammate, false);
        join(group, leader, true);
        JsonNode teamSource = success(create(creator.client(), teamBody(TODAY, "B07", group)), 201);
        JsonNode personalSource = success(create(creator.client(), personal(TODAY, "B08", "DRUMS")), 201);
        assertFalse(detail(teammate.client(), teamSource).get("canChange").asBoolean());
        assertTrue(detail(leader.client(), teamSource).get("canChange").asBoolean());
        assertUnchangedFailure(teammate.client(), teamSource, changeBody(teamSource, TODAY, "B09", null), 403, "FORBIDDEN");
        assertUnchangedFailure(outsider.client(), teamSource, changeBody(teamSource, TODAY, "B09", null), 403, "FORBIDDEN");
        assertUnchangedFailure(leader.client(), personalSource, changeBody(personalSource, TODAY, "B09", List.of("DRUMS")), 403, "FORBIDDEN");
        Map<String, String> before = snapshots();
        assertError(new Client().write("PATCH", reservationPath(teamSource), changeBody(teamSource, TODAY, "B09", null), key()), 401, "AUTHENTICATION_REQUIRED");
        assertError(creator.client().send("PATCH", reservationPath(teamSource), changeBody(teamSource, TODAY, "B09", null), null, key()), 403, "CSRF_INVALID");
        assertEquals(before, snapshots());
        jdbc.update("UPDATE team_memberships SET status='REMOVED',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, creator.rosterId());
        assertUnchangedFailure(creator.client(), teamSource, changeBody(teamSource, TODAY, "B09", null), 403, "FORBIDDEN");
        jdbc.update("UPDATE teams SET status='DISBANDED',version=version+1 WHERE id=?", group);
        assertFalse(detail(administrator.client(), teamSource).get("canChange").asBoolean());
        assertUnchangedFailure(administrator.client(), teamSource, changeBody(teamSource, TODAY, "B09", null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void staleCanceledAndReplacedSourcesCannotBeChangedAndRemainExactlyAsRecorded() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        Map<String, Object> stale = changeBody(source, TODAY, "B02", List.of("DRUMS"));
        stale.put("expectedVersion", source.get("version").asLong() + 1);
        assertUnchangedFailure(owner.client(), source, stale, 409, "VERSION_CONFLICT");
        JsonNode canceled = success(cancel(owner.client(), source), 200);
        assertUnchangedFailure(owner.client(), canceled, changeBody(canceled, TODAY, "B02", List.of("DRUMS")), 409, "VERSION_CONFLICT");
        JsonNode other = success(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 201);
        changed(owner.client(), other, TODAY, "B04", List.of("DRUMS"));
        JsonNode replaced = detail(owner.client(), other);
        assertUnchangedFailure(owner.client(), replaced, changeBody(replaced, TODAY, "B05", List.of("DRUMS")), 409, "VERSION_CONFLICT");
    }

    @Test
    void sourceMustBeFutureButTargetMayUseRemainderAndEndedTargetsAreRejected() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        clock.set(TODAY + "T12:10:00+09:00");
        JsonNode remainder = changed(owner.client(), source, TODAY, "B01", List.of("DRUMS"));
        assertEquals("IN_PROGRESS", remainder.get("phase").asString());
        assertEquals(clock.instant(), Instant.parse(remainder.get("usableFrom").asString()));
        assertEquals(60, remainder.get("quotaMinutes").asInt());
        assertEquals(60, quota(owner, null, TODAY));
        assertFalse(remainder.get("canChange").asBoolean());
        assertTrue(remainder.get("canCancel").asBoolean());
        assertUnchangedFailure(owner.client(), remainder, changeBody(remainder, TODAY, "B02", List.of("DRUMS")), 409, "RESERVATION_STARTED");
        success(cancel(owner.client(), remainder), 200);
        assertEquals(60, quota(owner, null, TODAY), "Moving into a remainder must retain the normal start-after consumption rule");
        JsonNode future = success(create(owner.client(), personal(TODAY, "B08", "DRUMS")), 201);
        clock.set(TODAY + "T13:00:00+09:00");
        assertUnchangedFailure(owner.client(), future, changeBody(future, TODAY, "B01", List.of("DRUMS")), 409, "RESERVATION_FINISHED");
    }

    @Test
    void inclusiveWindowAllowsLastDateAndRejectsPastAndFifteenthDateWithoutPartialReplacement() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY.minusDays(1), "B01", List.of("DRUMS")), 409, "DATE_NOT_OPEN");
        assertUnchangedFailure(owner.client(), source, changeBody(source, TODAY.plusDays(15), "B01", List.of("DRUMS")), 409, "DATE_NOT_OPEN");
        JsonNode last = changed(owner.client(), source, TODAY.plusDays(14), "B10", List.of("DRUMS"));
        assertEquals(TODAY.plusDays(14).toString(), last.get("date").asString());
        assertEquals(TODAY.plusDays(15).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant(), Instant.parse(last.get("endsAt").asString()));
    }

    @Test
    void fixedChangeCreatesChargedSuccessorPreservesNextWeekAndNeverRecreatesExceptionalOccurrence() throws Exception {
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, leader, true);
        createAssignment(group, "B01");
        JsonNode original = fixed(group, TODAY, "B01");
        JsonNode nextWeek = fixed(group, TODAY.plusDays(7), "B01");
        JsonNode moved = changed(leader.client(), original, TODAY.plusDays(1), "B02", null);
        assertFalse(moved.get("quotaExempt").asBoolean());
        assertEquals("CHANGE", moved.get("originKind").asString());
        assertEquals(original.get("creatorUserId"), moved.get("creatorUserId"));
        assertEquals(60, quota(null, group, TODAY.plusDays(1)));
        assertEquals(0, quota(null, group, TODAY));
        long occurrence = jdbc.queryForObject("SELECT occurrence_id FROM recurring_occurrence_reservations WHERE reservation_id=?", Long.class, id(original));
        assertEquals("CHANGED_EXCEPTION", jdbc.queryForObject("SELECT state FROM recurring_occurrences WHERE id=?", String.class, occurrence));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM recurring_occurrence_reservations WHERE occurrence_id=? "
                + "AND reservation_id=? AND link_role='SUCCESSOR' AND is_current", Integer.class, occurrence, id(moved)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM recurring_occurrence_reservations WHERE reservation_id=? AND is_current", Integer.class, id(original)));
        assertEquals(nextWeek, detail(administrator.client(), nextWeek));
        forceReopen(TODAY);
        assertTrue(opening.prepareDate(TODAY));
        assertEquals(0, activeAt(group, TODAY, "B01"));
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01"));
        assertEquals(1, activeAt(group, TODAY.plusDays(1), "B02"));
        success(cancel(leader.client(), moved), 200);
        assertEquals(0, quota(null, group, TODAY.plusDays(1)));
        forceReopen(TODAY);
        assertTrue(opening.prepareDate(TODAY));
        assertEquals(0, activeAt(group, TODAY, "B01"));
        assertEquals(0, activeAt(group, TODAY.plusDays(1), "B02"));
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01"));
        assertEquals("REPLACED", detail(administrator.client(), original).get("status").asString());
        assertEquals("CANCELED", detail(administrator.client(), moved).get("status").asString());
    }

    @Test
    void fixedMoveLosesExemptionOnlyOnSuccessAndUsesDestinationTeamsFinalHourlyLimit() throws Exception {
        Person creator = person("MEMBER");
        long group = team("ADDITIONAL");
        join(group, creator, true);
        createAssignment(group, "B01");
        JsonNode fixed = fixed(group, TODAY, "B01");
        success(create(creator.client(), teamBody(TODAY, "B02", group)), 201);
        success(create(creator.client(), teamBody(TODAY, "B03", group)), 201);
        assertUnchangedFailure(creator.client(), fixed, changeBody(fixed, TODAY, "B04", null), 409, "DAILY_LIMIT_EXCEEDED");
        assertTrue(detail(creator.client(), fixed).get("quotaExempt").asBoolean());
        assertEquals(120, quota(null, group, TODAY));
        JsonNode moved = changed(creator.client(), fixed, TODAY, "B07", null);
        assertFalse(moved.get("quotaExempt").asBoolean());
        assertEquals(0, moved.get("quotaMinutes").asInt());
        assertEquals(120, quota(null, group, TODAY));
        assertUnchangedFailure(creator.client(), moved, changeBody(moved, TODAY, "B04", null), 409, "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void fixedCrossWeekChangeKeepsSourceWeekExceptionThroughAdministratorPatternRevisionAndReopening() throws Exception {
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, leader, true);
        JsonNode series = createAssignment(group, "B01");
        JsonNode source = fixed(group, TODAY, "B01");
        JsonNode nextWeekOriginal = fixed(group, TODAY.plusDays(7), "B01");
        JsonNode moved = changed(leader.client(), source, TODAY.plusDays(8), "B02", null);
        assertEquals(nextWeekOriginal, detail(administrator.client(), nextWeekOriginal));
        long occurrence = jdbc.queryForObject("SELECT occurrence_id FROM recurring_occurrence_reservations WHERE reservation_id=?", Long.class, id(source));
        assertEquals(TODAY.minusDays(4), jdbc.queryForObject("SELECT week_start_date FROM recurring_occurrences WHERE id=?", LocalDate.class, occurrence));
        assertEquals(occurrence, jdbc.queryForObject("SELECT occurrence_id FROM recurring_occurrence_reservations WHERE reservation_id=? AND is_current", Long.class, id(moved)));
        success(administrator.client().write("PATCH", "/api/v1/recurring-assignments/" + series.get("id").asString(),
                Map.of("expectedVersion", series.get("version").asLong(), "weekday", 6, "blockCode", "B03", "effectiveDate", TODAY.toString()), key()), 200);
        assertEquals("CHANGED_EXCEPTION", jdbc.queryForObject("SELECT state FROM recurring_occurrences WHERE id=?", String.class, occurrence));
        assertEquals("CANCELED", detail(administrator.client(), nextWeekOriginal).get("status").asString());
        assertEquals(moved, detail(leader.client(), moved), "Pattern changes must preserve a successor even when it moved into another week");
        assertEquals(0, activeAt(group, TODAY, "B01"));
        assertEquals(0, activeAt(group, TODAY.plusDays(1), "B03"));
        JsonNode nextWeekRevised = fixed(group, TODAY.plusDays(8), "B03");
        assertTrue(nextWeekRevised.get("quotaExempt").asBoolean());
        assertEquals(60, quota(null, group, TODAY.plusDays(8)));
        for (LocalDate date : List.of(TODAY, TODAY.plusDays(1), TODAY.plusDays(8))) {
            forceReopen(date);
            assertTrue(opening.prepareDate(date));
        }
        assertEquals(0, activeAt(group, TODAY, "B01"));
        assertEquals(0, activeAt(group, TODAY.plusDays(1), "B03"));
        assertEquals(1, activeAt(group, TODAY.plusDays(8), "B02"));
        assertEquals(1, activeAt(group, TODAY.plusDays(8), "B03"));
        assertEquals(id(nextWeekRevised), id(fixed(group, TODAY.plusDays(8), "B03")));
        success(cancel(leader.client(), moved), 200);
        forceReopen(TODAY.plusDays(1));
        assertTrue(opening.prepareDate(TODAY.plusDays(1)));
        assertEquals(0, activeAt(group, TODAY.plusDays(1), "B03"));
        assertEquals(0, activeAt(group, TODAY.plusDays(8), "B02"));
        assertEquals(1, activeAt(group, TODAY.plusDays(8), "B03"));
        assertEquals(0, quota(null, group, TODAY.plusDays(8)));
    }

    @Test
    void idempotentRetryReturnsSameSuccessorCurrentStateAndRejectsChangedBodyOrRouteWithoutNewWrites() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        String requestKey = key();
        Map<String, Object> body = changeBody(source, TODAY, "B02", List.of("DRUMS"));
        JsonNode result = success(change(owner.client(), source, body, requestKey), 200);
        JsonNode successor = result.get("reservations").get(0);
        Map<String, String> committed = snapshots();
        assertEquals(result, success(change(owner.client(), source, body, requestKey), 200));
        assertEquals(committed, snapshots());
        assertEquals("PATCH", jdbc.queryForObject("SELECT http_method FROM idempotency_requests WHERE result_reservation_id=?", String.class, id(successor)));
        assertError(change(owner.client(), source, changeBody(source, TODAY, "B03", List.of("DRUMS")), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(owner.client().write("POST", reservationPath(successor) + "/cancel",
                Map.of("expectedVersion", successor.get("version").asLong()), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(committed, snapshots());
        success(cancel(owner.client(), successor), 200);
        Map<String, String> canceled = snapshots();
        JsonNode replay = success(change(owner.client(), source, body, requestKey), 200).get("reservations").get(0);
        assertEquals(id(successor), id(replay));
        assertEquals("CANCELED", replay.get("status").asString());
        assertEquals(canceled, snapshots());
        assertEquals(0, activeClaims());
        assertEquals(0, quota(owner, null, TODAY));
        assertEquals(1, changeCount());
        assertEquals(1, count("reservation_lineage"));
    }

    @Test
    void retryRechecksCurrentLeaderMembershipAdministratorRoleAndActiveActor() throws Exception {
        Person creator = person("MEMBER");
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, creator, false);
        join(group, leader, true);
        JsonNode source = success(create(creator.client(), teamBody(TODAY, "B01", group)), 201);
        String leaderKey = key();
        Map<String, Object> body = changeBody(source, TODAY, "B02", null);
        success(change(leader.client(), source, body, leaderKey), 200);
        jdbc.update("UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, leader.rosterId());
        Map<String, String> committed = snapshots();
        assertError(change(leader.client(), source, body, leaderKey), 403, "FORBIDDEN");
        assertEquals(committed, snapshots());

        JsonNode personal = success(create(creator.client(), personal(TODAY, "B07", "DRUMS")), 201);
        Person secondAdministrator = person("GLOBAL_ADMIN");
        String adminKey = key();
        Map<String, Object> adminBody = changeBody(personal, TODAY, "B08", List.of("DRUMS"));
        success(change(secondAdministrator.client(), personal, adminBody, adminKey), 200);
        jdbc.update("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", secondAdministrator.member().id());
        committed = snapshots();
        assertError(change(secondAdministrator.client(), personal, adminBody, adminKey), 403, "FORBIDDEN");
        assertEquals(committed, snapshots());

        JsonNode own = success(create(creator.client(), personal(TODAY, "B09", "VOCAL")), 201);
        String ownKey = key();
        Map<String, Object> ownBody = changeBody(own, TODAY, "B10", List.of("VOCAL"));
        success(change(creator.client(), own, ownBody, ownKey), 200);
        JsonNode csrf = creator.client().csrf();
        jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?", creator.member().id());
        committed = snapshots();
        assertError(creator.client().send("PATCH", reservationPath(own), ownBody, csrf, ownKey), 403, "INACTIVE_USER");
        assertEquals(committed, snapshots());
    }

    @Test
    void failedKeyCanRetryAfterConflictClearsAndConcurrentSameKeyCommitsOnlyOneChange() throws Exception {
        Person owner = person("MEMBER");
        Person other = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        JsonNode conflict = success(create(other.client(), personal(TODAY, "B02", "DRUMS")), 201);
        String requestKey = key();
        Map<String, Object> body = changeBody(source, TODAY, "B02", List.of("DRUMS"));
        Map<String, String> before = snapshots();
        assertError(change(owner.client(), source, body, requestKey), 409, "RESERVATION_CONFLICT");
        assertEquals(before, snapshots());
        success(cancel(other.client(), conflict), 200);
        List<HttpResponse<String>> responses = race(
                () -> change(owner.client(), source, body, requestKey),
                () -> change(owner.client(), source, body, requestKey));
        assertEquals(List.of(200, 200), statuses(responses));
        assertEquals(success(responses.get(0), 200), success(responses.get(1), 200));
        assertEquals(1, changeCount());
        assertEquals(1, count("reservation_lineage"));
        assertEquals(1, activeClaims());
        assertEquals(60, quota(owner, null, TODAY));
        assertEquals(0, quota(other, null, TODAY));
        assertEquals(4, count("idempotency_requests"));
    }

    @Test
    void concurrentChangesOfSameSourceHaveOneSuccessorAndOneOptimisticFailure() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        List<HttpResponse<String>> responses = race(
                () -> change(owner.client(), source, changeBody(source, TODAY, "B02", List.of("DRUMS")), key()),
                () -> change(owner.client(), source, changeBody(source, TODAY.plusDays(1), "B03", List.of("VOCAL")), key()));
        assertEquals(List.of(200, 409), statuses(responses));
        assertSingleConflict(responses, "VERSION_CONFLICT");
        assertEquals("REPLACED", detail(owner.client(), source).get("status").asString());
        assertEquals(1, changeCount());
        assertEquals(1, count("reservation_lineage"));
        assertEquals(2, count("reservations"));
        assertEquals(1, activeClaims());
        assertEquals(60, quota(owner, null, TODAY) + quota(owner, null, TODAY.plusDays(1)));
    }

    @Test
    void concurrentChangeAndCancelOfSameSourceCommitOneWholeOutcome() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        List<HttpResponse<String>> responses = race(
                () -> change(owner.client(), source, changeBody(source, TODAY, "B02", List.of("DRUMS")), key()),
                () -> cancel(owner.client(), source));
        assertEquals(List.of(200, 409), statuses(responses));
        assertSingleConflict(responses, "VERSION_CONFLICT");
        String state = detail(owner.client(), source).get("status").asString();
        boolean changed = "REPLACED".equals(state);
        assertTrue(changed || "CANCELED".equals(state));
        assertEquals(changed ? 1 : 0, count("reservation_lineage"));
        assertEquals(changed ? 2 : 1, count("reservations"));
        assertEquals(changed ? 1 : 0, activeClaims());
        assertEquals(changed ? 60 : 0, quota(owner, null, TODAY));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE entry_kind='RELEASE'", Integer.class));
        assertEquals(2, count("reservation_operations"));
        assertEquals(2, count("idempotency_requests"));
    }

    @Test
    void concurrentChangeAndCreateForSameInstrumentHaveOneWinnerAndPreserveLosingSource() throws Exception {
        Person owner = person("MEMBER");
        Person other = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        List<HttpResponse<String>> responses = race(
                () -> change(owner.client(), source, changeBody(source, TODAY, "B02", List.of("DRUMS")), key()),
                () -> create(other.client(), personal(TODAY, "B02", "DRUMS")));
        assertEquals(1, responses.stream().filter(r -> r.statusCode() < 300).count());
        assertSingleConflict(responses, "RESERVATION_CONFLICT");
        boolean changed = responses.get(0).statusCode() == 200;
        assertEquals(changed ? "REPLACED" : "CONFIRMED", detail(owner.client(), source).get("status").asString());
        assertEquals(changed ? 0 : 1, claims(id(source)));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_date=? AND time_block_code='B02' AND released_at IS NULL", Integer.class, TODAY));
        assertEquals(changed ? 1 : 2, activeClaims());
        assertEquals(60, quota(owner, null, TODAY));
        assertEquals(changed ? 0 : 60, quota(other, null, TODAY));
        assertEquals(2, count("reservations"));
    }

    @Test
    void concurrentChangesToLastDestinationHourlySlotCannotExceedPersonalOrSharedTeamQuota() throws Exception {
        Person owner = person("MEMBER");
        Person teammate = person("MEMBER");
        for (boolean personal : List.of(true, false)) {
            long group = team("REGULAR");
            join(group, owner, false);
            join(group, teammate, false);
            LocalDate sourceDate = personal ? TODAY : TODAY.plusDays(2);
            LocalDate targetDate = sourceDate.plusDays(1);
            JsonNode first = success(create(owner.client(), personal ? personal(sourceDate, "B07", "DRUMS") : teamBody(sourceDate, "B07", group)), 201);
            Client secondClient = personal ? owner.client() : teammate.client();
            JsonNode second = success(create(secondClient, personal ? personal(sourceDate, "B08", "DRUMS") : teamBody(sourceDate, "B08", group)), 201);
            success(create(owner.client(), personal ? personal(targetDate, "B01", "DRUMS") : teamBody(targetDate, "B01", group)), 201);
            long operations = changeCount();
            List<HttpResponse<String>> responses = race(
                    () -> change(owner.client(), first, changeBody(first, targetDate, "B02", personal ? List.of("DRUMS") : null), key()),
                    () -> change(secondClient, second, changeBody(second, targetDate, "B03", personal ? List.of("DRUMS") : null), key()));
            assertEquals(List.of(200, 409), statuses(responses));
            assertSingleConflict(responses, "DAILY_LIMIT_EXCEEDED");
            assertEquals(operations + 1, changeCount());
            assertEquals(120, quota(personal ? owner : null, personal ? null : group, targetDate));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE id IN (?,?) AND status='CONFIRMED'", Integer.class, id(first), id(second)));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE id IN (?,?) AND status='REPLACED'", Integer.class, id(first), id(second)));
        }
    }

    @Test
    void sourceStartingDuringActualDayLockWaitIsRecheckedAndRollsBackAllBookingWrites() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        clock.set(TODAY + "T11:59:59+09:00");
        JsonNode csrf = owner.client().csrf();
        Map<String, String> before = snapshots();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-10-30' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("PATCH", reservationPath(source),
                    changeBody(source, TODAY, "B02", List.of("DRUMS")), csrf, key()));
            awaitWaiter("booking_days");
            clock.set(TODAY + "T12:00:00+09:00");
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "RESERVATION_STARTED");
        }
        assertEquals(before, snapshots());
        assertEquals(1, claims(id(source)));
        assertEquals(60, quota(owner, null, TODAY));
    }

    @Test
    void destinationEndingDuringActualDayLockWaitIsRejectedAndClosedDateCannotAcceptChange() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        clock.set(TODAY + "T12:59:59+09:00");
        JsonNode csrf = owner.client().csrf();
        Map<String, String> before = snapshots();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-10-30' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("PATCH", reservationPath(source),
                    changeBody(source, TODAY, "B01", List.of("DRUMS")), csrf, key()));
            awaitWaiter("booking_days");
            clock.set(TODAY + "T13:00:00+09:00");
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "RESERVATION_FINISHED");
        }
        assertEquals(before, snapshots());
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-10-31' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("PATCH", reservationPath(source),
                    changeBody(source, TODAY.plusDays(1), "B01", List.of("DRUMS")), csrf, key()));
            awaitWaiter("booking_days");
            execute(connection, "UPDATE booking_days SET status='PREPARING',opened_at=NULL,recurring_prepared=FALSE WHERE reservation_date=?", TODAY.plusDays(1));
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "DATE_NOT_OPEN");
        }
        assertEquals(before, snapshots());
    }

    @Test
    void membershipLeaderRoleAdministratorRoleAndActorStateAreRecheckedAfterDirectoryGuardWait() throws Exception {
        for (String revoked : List.of("MEMBERSHIP", "LEADER", "ADMIN", "ACTOR")) {
            Person creator = person("MEMBER");
            Person actor = "ADMIN".equals(revoked) ? person("GLOBAL_ADMIN") : person("MEMBER");
            long group = team("REGULAR");
            join(group, creator, false);
            join(group, actor, "LEADER".equals(revoked));
            boolean own = "MEMBERSHIP".equals(revoked) || "ACTOR".equals(revoked);
            LocalDate date = TODAY.plusDays(List.of("MEMBERSHIP", "LEADER", "ADMIN", "ACTOR").indexOf(revoked));
            JsonNode source = success(create(own ? actor.client() : creator.client(), teamBody(date, "B01", group)), 201);
            JsonNode csrf = actor.client().csrf();
            Map<String, String> before = snapshots();
            try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
                    ExecutorService executor = Executors.newSingleThreadExecutor()) {
                var waiting = executor.submit(() -> actor.client().send("PATCH", reservationPath(source), changeBody(source, date, "B02", null), csrf, key()));
                awaitWaiter("directory_mutation_guard");
                switch (revoked) {
                    case "MEMBERSHIP" -> execute(connection, "UPDATE team_memberships SET status='REMOVED',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, actor.rosterId());
                    case "LEADER" -> execute(connection, "UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, actor.rosterId());
                    case "ADMIN" -> execute(connection, "UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", actor.member().id());
                    case "ACTOR" -> execute(connection, "UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?", actor.member().id());
                    default -> throw new AssertionError(revoked);
                }
                connection.commit();
                assertError(waiting.get(20, TimeUnit.SECONDS), 403, "ACTOR".equals(revoked) ? "INACTIVE_USER" : "FORBIDDEN");
            }
            assertEquals(before, snapshots());
            assertEquals(6, claims(id(source)));
            assertEquals(60, quota(null, group, date));
        }
    }

    @Test
    void faultCommittedWhileChangeWaitsForAssetLockIsObservedWithoutReleasingOriginalClaims() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        JsonNode csrf = owner.client().csrf();
        Map<String, String> before = snapshots();
        try (Connection connection = lock("SELECT id FROM assets WHERE code='DRUMS' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("PATCH", reservationPath(source),
                    changeBody(source, TODAY, "B02", List.of("DRUMS")), csrf, key()));
            awaitWaiter("assets");
            execute(connection, "UPDATE assets SET status='FAULTY',version=version+1 WHERE code='DRUMS'");
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "ASSET_FAULTY");
        }
        assertEquals(before, snapshots());
        assertEquals(1, claims(id(source)));
        assertEquals(60, quota(owner, null, TODAY));
    }

    @Test
    void actualTenSecondLineageLockTimeoutRollsBackReplacementClaimsQuotaAndCanRetrySameKeyOnce() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        String requestKey = key();
        Map<String, Object> body = changeBody(source, TODAY.plusDays(1), "B02", List.of("VOCAL"));
        JsonNode csrf = owner.client().csrf();
        Map<String, String> before = snapshots();
        try (Connection connection = fixtureConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // The lineage INSERT follows source replacement, new claims, and quota writes.
                // Hold the real PostgreSQL lock until the unchanged production timeout expires.
                statement.execute("LOCK TABLE reservation_lineage IN ACCESS EXCLUSIVE MODE");
            }
            var waiting = executor.submit(() -> {
                long started = System.nanoTime();
                HttpResponse<String> response = owner.client().send("PATCH", reservationPath(source), body, csrf, requestKey);
                return new TimedResponse(response, System.nanoTime() - started);
            });
            awaitWaiter("INSERT INTO reservation_lineage");
            TimedResponse timed = waiting.get(25, TimeUnit.SECONDS);
            assertTrue(timed.elapsedNanos() >= Duration.ofSeconds(9).toNanos(), "PostgreSQL must time out while the lock is still held");
            assertError(timed.response(), 503, "RESERVATION_TEMPORARILY_UNAVAILABLE");
            connection.rollback();
        }
        assertEquals(before, snapshots(), "Timeout must roll back already-written source, successor, claims, quota, and operation");
        assertEquals(1, claims(id(source)));
        assertEquals(60, quota(owner, null, TODAY));
        assertEquals(0, quota(owner, null, TODAY.plusDays(1)));
        JsonNode committed = success(change(owner.client(), source, body, requestKey), 200);
        Map<String, String> after = snapshots();
        assertEquals(committed, success(change(owner.client(), source, body, requestKey), 200));
        assertEquals(after, snapshots());
        assertEquals(1, changeCount());
        assertEquals(1, count("reservation_lineage"));
        assertEquals(2, count("reservations"));
        assertEquals(0, quota(owner, null, TODAY));
        assertEquals(60, quota(owner, null, TODAY.plusDays(1)));
    }

    @Test
    void administratorCannotPlaceOrNoOpInactiveOrWithdrawnPersonalOwnerReservations() throws Exception {
        for (String state : List.of("INACTIVE", "WITHDRAWN")) {
            Person owner = person("MEMBER");
            LocalDate date = "INACTIVE".equals(state) ? TODAY : TODAY.plusDays(1);
            JsonNode source = success(create(owner.client(), personal(date, "B01", "DRUMS")), 201);
            jdbc.update("UPDATE users SET status=?,version=version+1 WHERE id=?", state, owner.member().id());
            assertFalse(detail(administrator.client(), source).get("canChange").asBoolean());
            assertUnchangedFailure(administrator.client(), source, changeBody(source, date, "B02", List.of("DRUMS")), 403, "INACTIVE_USER");
            assertUnchangedFailure(administrator.client(), source, changeBody(source, date, "B01", List.of("DRUMS")), 403, "INACTIVE_USER");
            assertEquals("CONFIRMED", detail(administrator.client(), source).get("status").asString());
            assertEquals(1, claims(id(source)));
            assertEquals(60, quota(owner, null, date));
        }
        assertEquals(0, changeCount());
    }

    @Test
    void survivingTeamReservationCanBeChangedByCurrentLeaderAndAdministratorDespiteInactiveHistoricalCreator() throws Exception {
        for (String state : List.of("INACTIVE", "WITHDRAWN")) {
            Person creator = person("MEMBER");
            Person leader = person("MEMBER");
            long group = team("REGULAR");
            join(group, creator, false);
            join(group, leader, true);
            LocalDate date = "INACTIVE".equals(state) ? TODAY : TODAY.plusDays(1);
            JsonNode source = success(create(creator.client(), teamBody(date, "B01", group)), 201);
            jdbc.update("UPDATE users SET status=?,version=version+1 WHERE id=?", state, creator.member().id());
            assertTrue(detail(leader.client(), source).get("canChange").asBoolean());
            JsonNode first = changed(leader.client(), source, date, "B02", null);
            JsonNode last = changed(administrator.client(), first, date, "B03", null);
            assertEquals(Long.toString(creator.member().id()), last.get("creatorUserId").asString());
            assertEquals(Long.toString(group), last.get("teamId").asString());
            assertEquals("CONFIRMED", last.get("status").asString());
            assertEquals(INSTRUMENTS, strings(last.get("instrumentCodes")));
            assertEquals(6, claims(id(last)));
            assertEquals(60, quota(null, group, date));
        }
    }

    @Test
    void completedReplayNeverPreparesDateAndAdministratorCanReplayResultAfterOwnerBecomesInactive() throws Exception {
        Person owner = person("MEMBER");
        JsonNode source = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        String requestKey = key();
        Map<String, Object> body = changeBody(source, TODAY.plusDays(1), "B02", List.of("DRUMS"));
        JsonNode successor = success(change(administrator.client(), source, body, requestKey), 200).get("reservations").get(0);
        forceReopen(TODAY.plusDays(1));
        jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?", owner.member().id());
        Map<String, String> before = snapshots();
        Map<String, String> beforeDays = snapshots("booking_days");
        JsonNode replay = success(change(administrator.client(), source, body, requestKey), 200).get("reservations").get(0);
        assertEquals(id(successor), id(replay));
        assertEquals("CONFIRMED", replay.get("status").asString());
        assertFalse(replay.get("canChange").asBoolean());
        assertEquals(beforeDays, snapshots("booking_days"), "A completed retry must not reopen its target date");
        assertEquals(before, snapshots());
        assertError(change(administrator.client(), source, changeBody(source, TODAY.plusDays(1), "B03", List.of("DRUMS")), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(beforeDays, snapshots("booking_days"));
        assertEquals(before, snapshots());
        clock.set(TODAY.plusDays(2) + "T11:00:00+09:00");
        JsonNode endedReplay = success(change(administrator.client(), source, body, requestKey), 200).get("reservations").get(0);
        assertEquals(id(successor), id(endedReplay));
        assertEquals("ENDED", endedReplay.get("phase").asString());
        assertEquals(beforeDays, snapshots("booking_days"));
        assertEquals(before, snapshots());
    }

    @Test
    void v6UpgradePreservesEveryPopulatedV1ThroughV5TableIncludingFixedExceptionsAndNinetyDaySession() throws Exception {
        // Both versions are migrated only in an isolated schema of this disposable container.
        String schema = "change_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(5, fixtureFlyway(schema, "5").migrate().migrationsExecuted);
        Map<String, String> before;
        try (Connection connection = fixtureConnection(schema)) {
            Timestamp now = Timestamp.from(clock.instant());
            long admin = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v6-admin',?,'합성 이전 관리자','GLOBAL_ADMIN','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long member = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v6-member',?,'합성 이전 회원','MEMBER','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long roster = insertId(connection, "INSERT INTO roster_entries(name,user_id,version) VALUES ('합성 연결 명단',?,2) RETURNING id", member);
            long pending = insertId(connection, "INSERT INTO roster_entries(name) VALUES ('합성 미가입 명단') RETURNING id");
            long group = insertId(connection, "INSERT INTO teams(name,kind,version) VALUES ('합성 이전 조','REGULAR',3) RETURNING id");
            execute(connection, "INSERT INTO team_memberships(team_id,roster_entry_id,role,version) VALUES (?,?,'LEADER',1)", group, roster);
            execute(connection, "INSERT INTO team_songs(team_id,title,artist,memo,sort_order,created_by,updated_by) "
                    + "VALUES (?,'합성 이전 곡','합성 아티스트','합성 메모',1,?,?)", group, admin, member);
            execute(connection, "INSERT INTO member_role_audit(target_user_id,actor_user_id,old_role,new_role,created_at) "
                    + "VALUES (?,?,'GLOBAL_ADMIN','MEMBER',?)", member, admin, now);
            execute(connection, "INSERT INTO invitation_codes(code_digest,recipient_name,issued_by,created_at,expires_at,roster_entry_id) "
                    + "VALUES (?,'합성 이전 초대',?,?,?,?)",
                    kr.jeongeum.scheduler.invitation.service.InvitationTokens.digest(UUID.randomUUID().toString()),
                    admin, now, Timestamp.from(clock.instant().plus(Duration.ofDays(7))), pending);
            String session = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                    + "VALUES (?,?,?,?,7776000,?,'synthetic-v6-member')", session, UUID.randomUUID().toString(),
                    clock.instant().toEpochMilli(), clock.instant().toEpochMilli(), clock.instant().plus(Duration.ofDays(90)).toEpochMilli());
            execute(connection, "INSERT INTO spring_session_attributes VALUES (?,'synthetic.v6-preservation',?)",
                    session, "synthetic-session-attribute".getBytes(StandardCharsets.UTF_8));
            execute(connection, "UPDATE assets SET status='FAULTY',version=4 WHERE code='VOCAL'");
            execute(connection, "INSERT INTO booking_days(reservation_date,status,opened_at,created_at,recurring_prepared,version) "
                    + "VALUES (?,'OPEN',?,?,TRUE,2)", TODAY, now, now);
            execute(connection, "INSERT INTO booking_days(reservation_date,status,created_at) VALUES (?,'PREPARING',?)", TODAY.plusDays(1), now);
            execute(connection, "INSERT INTO booking_days(reservation_date,status,opened_at,created_at,recurring_prepared) "
                    + "VALUES (?,'OPEN',?,?,TRUE)", TODAY.plusDays(7), now, now);
            long create = insertId(connection, "INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at) "
                    + "VALUES ('CREATE','USER',?,?) RETURNING id", member, now);
            long cancel = insertId(connection, "INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at,reason) "
                    + "VALUES ('CANCEL','USER',?,?,'합성 이전 취소') RETURNING id", admin, now);
            long generate = insertId(connection, "INSERT INTO reservation_operations(kind,actor_kind,occurred_at) VALUES ('FIXED_GENERATE','SYSTEM',?) RETURNING id", now);
            long series = insertId(connection, "INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at) "
                    + "VALUES ('SERIES_UPDATE','USER',?,?) RETURNING id", admin, now);
            long personal = fixtureReservation(connection, member, null, member, "B01", create, "MANUAL", false);
            long canceled = fixtureReservation(connection, member, null, member, "B02", create, "MANUAL", false);
            long fixed = fixtureReservation(connection, null, group, admin, "B03", generate, "FIXED", true);
            long canceledFixed = fixtureReservation(connection, null, group, admin, TODAY.plusDays(7), "B03", generate, "FIXED", true);
            fixtureClaims(connection, personal, 1);
            fixtureClaims(connection, canceled, 1);
            fixtureClaims(connection, fixed, 6);
            fixtureClaims(connection, canceledFixed, 6);
            execute(connection, "UPDATE reservations SET status='CANCELED',canceled_at=?,canceled_by_user_id=?,canceled_operation_id=?,cancel_reason='합성 이전 취소',version=1 WHERE id=?",
                    now, admin, cancel, canceled);
            execute(connection, "UPDATE reservation_assets SET released_at=? WHERE reservation_id=?", now, canceled);
            execute(connection, "UPDATE reservations SET status='CANCELED',canceled_at=?,canceled_by_user_id=?,canceled_operation_id=?,cancel_reason='합성 고정 회차 취소',version=1 WHERE id=?",
                    now, admin, cancel, canceledFixed);
            try (var statement = connection.prepareStatement("UPDATE reservation_assets SET released_at=? WHERE reservation_id=?")) {
                statement.setTimestamp(1, now);
                statement.setLong(2, canceledFixed);
                assertEquals(6, statement.executeUpdate());
            }
            fixtureQuota(connection, personal, create, member, null, 60, "RESERVE");
            fixtureQuota(connection, canceled, create, member, null, 60, "RESERVE");
            fixtureQuota(connection, canceled, cancel, member, null, -60, "RELEASE");
            fixtureQuota(connection, fixed, generate, null, group, 0, "RESERVE");
            fixtureQuota(connection, canceledFixed, generate, null, group, 0, "RESERVE");
            execute(connection, "INSERT INTO idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_reservation_id,operation_id,created_at) "
                    + "VALUES (?, ?,?,'POST','/api/v1/reservations',201,?,?,?)", member, "a".repeat(64), "b".repeat(64), personal, create, now);
            long assignment = insertId(connection, "INSERT INTO recurring_assignments(team_id,created_by_user_id,version,created_at,updated_at) "
                    + "VALUES (?,?,2,?,?) RETURNING id", group, admin, now, now);
            long revision = insertId(connection, "INSERT INTO recurring_assignment_revisions(assignment_id,revision_no,effective_date,weekday,time_block_code,created_by_user_id,created_at) "
                    + "VALUES (?,1,?,5,'B03',?,?) RETURNING id", assignment, TODAY, admin, now);
            long occurrence = insertId(connection, "INSERT INTO recurring_occurrences(assignment_id,week_start_date,pattern_revision_id,original_scheduled_date,original_block_code,state,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,'B03','MATERIALIZED',1,?,?) RETURNING id", assignment, TODAY.minusDays(4), revision, TODAY, now, now);
            execute(connection, "INSERT INTO recurring_occurrence_reservations(occurrence_id,reservation_id,link_role,is_current) VALUES (?,?,'ORIGINAL',TRUE)", occurrence, fixed);
            execute(connection, "INSERT INTO recurring_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_assignment_id,operation_id,created_at) "
                    + "VALUES (?, ?,?,'POST','/api/v1/recurring-assignments',201,?,?,?)", admin, "c".repeat(64), "d".repeat(64), assignment, series, now);
            // A separate week carries a cancellation tombstone that migration must never drop.
            long exception = insertId(connection, "INSERT INTO recurring_occurrences(assignment_id,week_start_date,pattern_revision_id,original_scheduled_date,original_block_code,state,version,created_at,updated_at) "
                    + "VALUES (?,?,?,?,'B03','CANCELED_EXCEPTION',3,?,?) RETURNING id", assignment, TODAY.plusDays(3), revision, TODAY.plusDays(7), now, now);
            execute(connection, "INSERT INTO recurring_occurrence_reservations(occurrence_id,reservation_id,link_role,is_current) VALUES (?,?,'ORIGINAL',TRUE)", exception, canceledFixed);
            before = schemaSnapshots(connection);
            assertEquals(25, before.size(), "The fixture must cover every V1-V5 feature table");
            for (var entry : before.entrySet()) {
                assertNotEquals("[]", entry.getValue(), "Migration preservation requires populated " + entry.getKey());
            }
        }
        assertEquals(1, fixtureFlyway(schema, "6").migrate().migrationsExecuted);
        assertEquals(0, fixtureFlyway(schema, "6").migrate().migrationsExecuted);
        assertTrue(fixtureFlyway(schema, "6").validateWithResult().validationSuccessful);
        try (Connection connection = fixtureConnection(schema)) {
            for (var entry : before.entrySet()) {
                assertEquals(entry.getValue(), tableSnapshot(connection, entry.getKey()), "V6 must preserve every existing value in " + entry.getKey());
            }
            assertEquals(0, scalar(connection, "SELECT count(*) FROM reservation_lineage"));
            assertEquals(6, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
            assertEquals(14, scalar(connection, "SELECT count(*) FROM reservation_assets"));
            assertEquals(7, scalar(connection, "SELECT count(*) FROM reservation_assets WHERE released_at IS NULL"));
            assertEquals(60, scalar(connection, "SELECT sum(amount_minutes) FROM quota_entries"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM reservations WHERE origin_kind='FIXED' AND quota_exempt"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recurring_occurrences WHERE state='CANCELED_EXCEPTION'"));
        }
    }

    private record TimedResponse(HttpResponse<String> response, long elapsedNanos) {}

    private Flyway fixtureFlyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
    private Connection fixtureConnection(String schema) throws Exception {
        Connection connection = fixtureConnection();
        connection.setSchema(schema);
        return connection;
    }
    private Map<String, String> schemaSnapshots(Connection connection) throws Exception {
        List<String> tables = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name")) {
            statement.setString(1, connection.getSchema());
            try (var rows = statement.executeQuery()) { while (rows.next()) { tables.add(rows.getString(1)); } }
        }
        Map<String, String> result = new HashMap<>();
        for (String table : tables) { result.put(table, tableSnapshot(connection, table)); }
        return result;
    }
    private String tableSnapshot(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
    private long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
    private long insertId(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private long fixtureReservation(Connection connection, Long owner, Long group, long creator, String blockCode,
            long operation, String origin, boolean exempt) throws Exception {
        return fixtureReservation(connection, owner, group, creator, TODAY, blockCode, operation, origin, exempt);
    }
    private long fixtureReservation(Connection connection, Long owner, Long group, long creator, LocalDate date, String blockCode,
            long operation, String origin, boolean exempt) throws Exception {
        TimeBlock block = TimeBlock.require(blockCode);
        return insertId(connection, "INSERT INTO reservations(owner_type,purpose,owner_user_id,team_id,creator_user_id,reservation_date,time_block_code,"
                + "block_start_minute,block_end_minute,duration_minutes,block_start_at,block_end_at,usable_from,status,origin_kind,quota_exempt,created_at,created_operation_id) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'CONFIRMED',?,?,?,?) RETURNING id",
                owner == null ? "TEAM" : "PERSONAL", owner == null ? "REGULAR" : "PERSONAL", owner, group, creator, date, blockCode,
                block.startMinute(), block.endMinute(), block.durationMinutes(), Timestamp.from(block.startsAt(date)),
                Timestamp.from(block.endsAt(date)), Timestamp.from(block.startsAt(date)), origin, exempt, Timestamp.from(clock.instant()), operation);
    }
    private void fixtureClaims(Connection connection, long reservation, int expected) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at) "
                + "SELECT r.id,r.reservation_date,r.time_block_code,a.id,r.owner_type,r.created_at FROM reservations r CROSS JOIN assets a "
                + "WHERE r.id=? AND (r.owner_type='TEAM' OR a.code='DRUMS')")) {
            statement.setLong(1, reservation);
            assertEquals(expected, statement.executeUpdate());
        }
    }
    private void fixtureQuota(Connection connection, long reservation, long operation, Long owner, Long group, int amount, String kind) throws Exception {
        execute(connection, "INSERT INTO quota_entries(reservation_id,operation_id,quota_date,owner_user_id,team_id,amount_minutes,entry_kind,created_at) "
                + "SELECT ?,?,reservation_date,?,?,?,?,? FROM reservations WHERE id=?", reservation, operation, owner, group, amount, kind, Timestamp.from(clock.instant()), reservation);
    }

    private JsonNode changed(Client client, JsonNode source, LocalDate date, String block, List<String> instruments) throws Exception {
        JsonNode result = success(change(client, source, changeBody(source, date, block, instruments), key()), 200);
        assertEquals(Set.of(source.get("id").asString()), strings(result.get("replacedReservationIds")));
        assertEquals(1, result.get("reservations").size());
        return result.get("reservations").get(0);
    }
    private Map<String, Object> changeBody(JsonNode source, LocalDate date, String block, List<String> instruments) {
        Map<String, Object> body = new HashMap<>(Map.of("expectedVersion", source.get("version").asLong(), "date", date.toString(), "blockCode", block));
        if (instruments != null) { body.put("instrumentCodes", instruments); }
        return body;
    }
    private HttpResponse<String> change(Client client, JsonNode source, Object body, String requestKey) throws Exception {
        return client.write("PATCH", reservationPath(source), body, requestKey);
    }
    private void assertUnchangedFailure(Client client, JsonNode source, Object body, int status, String code) throws Exception {
        Map<String, String> before = snapshots();
        assertError(change(client, source, body, key()), status, code);
        assertEquals(before, snapshots(), "A rejected change must preserve original reservations, claims, ledger, lineage, and operation counts");
    }
    private JsonNode detail(Client client, JsonNode booking) throws Exception { return success(client.get(reservationPath(booking)), 200); }
    private JsonNode createAssignment(long team, String block) throws Exception {
        return success(administrator.client().write("POST", "/api/v1/recurring-assignments", Map.of("teamId", Long.toString(team),
                "weekday", TODAY.getDayOfWeek().getValue(), "blockCode", block, "effectiveDate", TODAY.toString()), key()), 201);
    }
    private JsonNode fixed(long team, LocalDate date, String block) throws Exception {
        long reservation = jdbc.queryForObject("SELECT id FROM reservations WHERE team_id=? AND reservation_date=? "
                + "AND time_block_code=? AND origin_kind='FIXED' AND status='CONFIRMED'", Long.class, team, date, block);
        return success(administrator.client().get("/api/v1/reservations/" + reservation), 200);
    }
    private void forceReopen(LocalDate date) {
        assertEquals(1, jdbc.update("UPDATE booking_days SET status='PREPARING',opened_at=NULL,recurring_prepared=FALSE WHERE reservation_date=?", date));
    }
    private long activeAt(long team, LocalDate date, String block) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE team_id=? AND reservation_date=? "
                + "AND time_block_code=? AND status='CONFIRMED'", Long.class, team, date, block);
    }
    private int quota(Person person, Long team, LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minutes),0)::integer FROM quota_entries WHERE quota_date=? AND "
                + (person == null ? "team_id" : "owner_user_id") + "=?", Integer.class, date, person == null ? team : person.member().id());
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private long changeCount() { return jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind='CHANGE'", Long.class); }
    private long claims(long id) { return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_id=? AND released_at IS NULL", Long.class, id); }
    private long activeClaims() { return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE released_at IS NULL", Long.class); }
    private Map<String, String> snapshots() { return snapshots(BOOKING_TABLES); }
    private Map<String, String> snapshotsWithoutIdempotency() {
        return snapshots(java.util.Arrays.stream(BOOKING_TABLES).filter(t -> !"idempotency_requests".equals(t)).toArray(String[]::new));
    }
    private Map<String, String> snapshots(String... tables) {
        Map<String, String> result = new HashMap<>();
        for (String table : tables) {
            result.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r", String.class));
        }
        return result;
    }
    private Connection fixtureConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private Connection lock(String sql) throws Exception {
        Connection connection = fixtureConnection();
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); }
        return connection;
    }
    private void execute(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            assertEquals(1, statement.executeUpdate());
        }
    }
    private void awaitWaiter(String query) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)", Boolean.class, "%" + query + "%")));
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'", Integer.class) == 2);
            connection.commit();
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }
    private List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private void assertSingleConflict(List<HttpResponse<String>> responses, String code) {
        assertEquals(1, responses.stream().filter(r -> r.statusCode() == 409).count());
        assertError(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, code);
    }
    private Person person(String role) throws Exception {
        Member member = members.insert("change-fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(password), "합성 변경 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 변경 명단',?) RETURNING id", Long.class, member.id());
        Client client = new Client();
        success(client.mutate("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password)), 200);
        return new Person(member, roster, client);
    }
    private long team(String kind) { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 변경 조',?) RETURNING id", Long.class, kind); }
    private void join(long team, Person person, boolean leader) {
        assertEquals(1, jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,?)", team, person.rosterId(), leader ? "LEADER" : "MEMBER"));
    }
    private Map<String, Object> personal(LocalDate date, String block, String instrument) {
        return Map.of("subjectType", "PERSONAL", "date", date.toString(), "blockCode", block, "instrumentCodes", List.of(instrument));
    }
    private Map<String, Object> teamBody(LocalDate date, String block, long team) {
        return Map.of("subjectType", "TEAM", "date", date.toString(), "blockCode", block, "teamId", Long.toString(team));
    }
    private HttpResponse<String> create(Client client, Object body) throws Exception { return client.write("POST", "/api/v1/reservations", body, key()); }
    private HttpResponse<String> cancel(Client client, JsonNode booking) throws Exception {
        return client.write("POST", reservationPath(booking) + "/cancel", Map.of("expectedVersion", booking.get("version").asLong()), key());
    }
    private Set<String> strings(JsonNode array) { return strings(array, null); }
    private Set<String> strings(JsonNode array, String field) {
        return array.valueStream().map(n -> field == null ? n.asString() : n.get(field).asString()).collect(java.util.stream.Collectors.toSet());
    }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertSafe(response);
        return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertSafe(response);
    }
    private String safeFailure(HttpResponse<String> response) {
        JsonNode error = json.readTree(response.body()).get("error");
        return error == null ? "Unexpected status without API error" : error.path("code").asString();
    }
    private void assertSafe(HttpResponse<String> response) {
        assertFalse(response.body().contains(password));
        for (String field : List.of("passwordHash", "password_hash", "codeDigest", "code_digest", "key_digest", "request_fingerprint")) {
            assertFalse(response.body().contains(field));
        }
    }
    private long id(JsonNode value) { return Long.parseLong(value.get("id").asString()); }
    private String reservationPath(JsonNode booking) { return "/api/v1/reservations/" + booking.get("id").asString(); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Person(Member member, long rosterId, Client client) {}

    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"), 200); }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception { return send(method, path, body, csrf(), null); }
        HttpResponse<String> write(String method, String path, Object body, String requestKey) throws Exception { return send(method, path, body, csrf(), requestKey); }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf, String requestKey) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (requestKey != null) { builder.header("Idempotency-Key", requestKey); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return transport.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTime {
        @Bean @Primary TestClock reservationChangeTestClock() { return new TestClock(); }
    }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        TestClock() { this(new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z")), ZoneId.of("UTC")); }
        private TestClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(String offsetDateTime) { now.set(Instant.parse(offsetDateTime)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new TestClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
