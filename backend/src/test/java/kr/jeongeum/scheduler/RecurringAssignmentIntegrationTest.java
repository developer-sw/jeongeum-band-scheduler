package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
@Import(RecurringAssignmentIntegrationTest.TestTime.class)
@Timeout(90)
class RecurringAssignmentIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30); // Friday in Korea.
    private static final Set<String> INSTRUMENTS = Set.of(
            "VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("recurring_test").withUsername("recurring_test")
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
        // Every connection in this class targets only the disposable Testcontainers database.
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status = 'NORMAL', version = 0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
    }

    @Test
    void onlyCurrentGlobalAdministratorCanManageAssignmentsAndCsrfRemainsRequired() throws Exception {
        Person ordinary = person("MEMBER");
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, leader, true);
        var body = assignment(group, 5, "B01", TODAY);
        Client anonymous = new Client();
        assertError(anonymous.get("/api/v1/recurring-assignments"), 401, "AUTHENTICATION_REQUIRED");
        assertError(ordinary.client().get("/api/v1/recurring-assignments"), 403, "FORBIDDEN");
        assertError(ordinary.client().write("PATCH", "/api/v1/recurring-assignments/999999",
                Map.of("expectedVersion", 0, "weekday", 5, "blockCode", "B02", "effectiveDate", TODAY.toString()), key()),
                403, "FORBIDDEN");
        assertError(leader.client().write("POST", "/api/v1/recurring-assignments", body, key()), 403, "FORBIDDEN");
        assertError(leader.client().mutate("POST", "/api/v1/recurring-assignments/preview", body), 403, "FORBIDDEN");
        assertError(administrator.client().send("POST", "/api/v1/recurring-assignments", body, null, key()), 403, "CSRF_INVALID");
        JsonNode created = success(createAssignment(body), 201);
        assertError(ordinary.client().write("PATCH", assignmentPath(created),
                revision(created, 6, "B02", TODAY), key()), 403, "FORBIDDEN");
        assertError(leader.client().write("POST", assignmentPath(created) + "/end",
                Map.of("expectedVersion", created.get("version").asLong(), "endDate", TODAY.toString()), key()), 403, "FORBIDDEN");
        assertEquals(1, count("recurring_assignments"));
        assertEquals(3, fixedCount(group));
    }

    @Test
    void fixedRegularAndAdditionalTeamsExclusivelyClaimSixAssetsAndRetainAdministratorCreator() throws Exception {
        long regular = team("REGULAR");
        long additional = team("ADDITIONAL");
        JsonNode regularAssignment = success(createAssignment(assignment(regular, 5, "B01", TODAY)), 201);
        success(createAssignment(assignment(additional, 5, "B07", TODAY)), 201);
        JsonNode sixty = fixed(regular, TODAY, "B01");
        JsonNode ninety = fixed(additional, TODAY, "B07");
        assertEquals("FIXED", sixty.get("originKind").asString());
        assertEquals("TEAM", sixty.get("subjectType").asString());
        assertEquals("REGULAR", sixty.get("purpose").asString());
        assertEquals("ADDITIONAL", ninety.get("purpose").asString());
        assertFalse(ninety.get("quotaExempt").asBoolean(), "A 90-minute block is outside the hourly allowance rather than exempt from it");
        assertEquals(0, ninety.get("quotaMinutes").asInt());
        assertEquals(Long.toString(administrator.member().id()), sixty.get("creatorUserId").asString(),
                "A system worker must retain the administrator who assigned the series");
        assertEquals(INSTRUMENTS, strings(sixty.get("instrumentCodes")));
        assertTrue(sixty.get("quotaExempt").asBoolean());
        assertEquals(0, sixty.get("quotaMinutes").asInt());
        assertEquals(6, claims(id(sixty)));
        assertEquals(0, quota(regular, TODAY));
        assertEquals(3, regularAssignment.get("occurrences").size());
        assertEquals(6, jdbc.queryForObject(
                "SELECT count(*) FROM reservation_operations WHERE kind = 'FIXED_GENERATE' AND actor_kind = 'SYSTEM' AND actor_user_id IS NULL", Long.class));
        Person outsider = person("MEMBER");
        assertError(createReservation(outsider.client(), personal(TODAY, "B01", "DRUMS")), 409, "RESERVATION_CONFLICT");
        assertError(outsider.client().get(reservationPath(sixty)), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void fixedSixtyDoesNotConsumeTeamOrPersonalDailyAllowanceAndCannotBeForgedByOrdinaryCreation() throws Exception {
        Person member = person("MEMBER");
        long group = team("REGULAR");
        join(group, member, true);
        success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        success(createReservation(member.client(), teamBody(TODAY, "B02", group)), 201);
        success(createReservation(member.client(), teamBody(TODAY, "B03", group)), 201);
        assertError(createReservation(member.client(), teamBody(TODAY, "B06", group)), 409, "DAILY_LIMIT_EXCEEDED");
        success(createReservation(member.client(), personal(TODAY, "B04", "DRUMS")), 201);
        success(createReservation(member.client(), personal(TODAY, "B05", "DRUMS")), 201);
        assertEquals(120, quota(group, TODAY));
        assertEquals(120, jdbc.queryForObject(
                "SELECT SUM(amount_minutes)::integer FROM quota_entries WHERE owner_user_id = ? AND quota_date = ?",
                Integer.class, member.member().id(), TODAY));
        var forged = new HashMap<>(teamBody(TODAY, "B06", group));
        forged.put("quotaExempt", true);
        assertError(createReservation(member.client(), forged), 400, "INVALID_INPUT");
        JsonNode canceled = success(cancel(member.client(), fixed(group, TODAY, "B01")), 200);
        assertEquals("CANCELED", canceled.get("status").asString());
        assertEquals(120, quota(group, TODAY));
        assertEquals("CANCELED_EXCEPTION", occurrence(assignmentById(1), TODAY).get("state").asString());
    }

    @Test
    void faultyAssetWarnsFixedTeamWithoutPreventingItsExclusiveReservation() throws Exception {
        long group = team("ADDITIONAL");
        jdbc.update("UPDATE assets SET status = 'FAULTY', version = version + 1 WHERE code = 'DRUMS'");
        success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        JsonNode booking = fixed(group, TODAY, "B01");
        assertEquals(INSTRUMENTS, strings(booking.get("instrumentCodes")));
        assertEquals(Set.of("DRUMS"), strings(booking.get("teamFaultWarnings")));
        assertEquals("CONFIRMED", booking.get("status").asString());
        assertEquals(6, claims(id(booking)));
    }

    @Test
    void assignmentWritesAreIdempotentButAKeyCannotBeReusedForAnotherBodyOrRoute() throws Exception {
        long group = team("REGULAR");
        String requestKey = key();
        var body = assignment(group, 5, "B01", TODAY);
        JsonNode first = success(administrator.client().write("POST", "/api/v1/recurring-assignments", body, requestKey), 201);
        JsonNode replay = success(administrator.client().write("POST", "/api/v1/recurring-assignments", body, requestKey), 201);
        assertEquals(id(first), id(replay));
        assertError(administrator.client().write("POST", "/api/v1/recurring-assignments",
                assignment(group, 5, "B02", TODAY), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(administrator.client().write("POST", assignmentPath(first) + "/end",
                Map.of("expectedVersion", 0, "endDate", TODAY.toString()), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(1, count("recurring_assignments"));
        assertEquals(3, fixedCount(group));
        assertEquals(3, count("recurring_occurrences"));
        assertEquals(18, activeClaims());
    }

    @Test
    void retriesOfCommittedRevisionAndEndDoNotAddRevisionsReservationsOperationsOrQuota() throws Exception {
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        String updateKey = key();
        var update = revision(series, 5, "B02", TODAY);
        JsonNode revised = success(administrator.client().write("PATCH", assignmentPath(series), update, updateKey), 200);
        Map<String, String> revisedState = snapshots("recurring_assignments", "recurring_assignment_revisions",
                "recurring_occurrences", "recurring_occurrence_reservations", "reservations", "reservation_assets",
                "quota_entries", "reservation_operations", "recurring_idempotency_requests");
        JsonNode updateReplay = success(administrator.client().write("PATCH", assignmentPath(series), update, updateKey), 200);
        assertEquals(revised.get("version").asLong(), updateReplay.get("version").asLong());
        assertEquals(revisedState, snapshots(revisedState.keySet().toArray(String[]::new)));
        String endKey = key();
        var end = Map.of("expectedVersion", revised.get("version").asLong(), "endDate", TODAY.toString());
        JsonNode ended = success(administrator.client().write("POST", assignmentPath(series) + "/end", end, endKey), 200);
        Map<String, String> endedState = snapshots("recurring_assignments", "recurring_assignment_revisions",
                "recurring_occurrences", "recurring_occurrence_reservations", "reservations", "reservation_assets",
                "quota_entries", "reservation_operations", "recurring_idempotency_requests");
        JsonNode endReplay = success(administrator.client().write("POST", assignmentPath(series) + "/end", end, endKey), 200);
        assertEquals(ended.get("version").asLong(), endReplay.get("version").asLong());
        assertEquals(endedState, snapshots(endedState.keySet().toArray(String[]::new)));
        opening.openBookableWindow();
        assertEquals(1, activeAt(group, TODAY, "B02"));
        assertEquals(0, activeAt(group, TODAY.plusDays(7), "B02"));
        assertEquals(0, quota(group, TODAY));
    }

    @Test
    void duplicateWorkersGenerateEachOccurrenceOnlyOnceAndRecoverMissingDateAfterRestart() throws Exception {
        long group = team("REGULAR");
        success(createAssignment(assignment(group, 6, "B01", TODAY.plusDays(15))), 201);
        assertEquals(0, fixedCount(group));
        jdbc.update("INSERT INTO booking_days(reservation_date,status,created_at) VALUES (?,'PREPARING',?)",
                TODAY.plusDays(15), Timestamp.from(clock.instant()));
        clock.set(TODAY.plusDays(1) + "T00:00:00+09:00");
        CountDownLatch start = new CountDownLatch(1);
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-11-14' FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { start.await(); opening.openBookableWindow(); return null; });
            var second = executor.submit(() -> { start.await(); opening.openBookableWindow(); return null; });
            start.countDown();
            awaitWaiter("booking_days");
            awaitWaiter("directory_mutation_guard");
            connection.commit();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        }
        opening.openBookableWindow();
        assertEquals(1, fixedCount(group));
        assertEquals(1, count("recurring_occurrences"));
        assertEquals(6, activeClaims());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind = 'FIXED_GENERATE'", Long.class));
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM booking_days WHERE reservation_date = ?", String.class, TODAY.plusDays(15)));
        assertTrue(jdbc.queryForObject(
                "SELECT recurring_prepared FROM booking_days WHERE reservation_date = ?", Boolean.class, TODAY.plusDays(15)));
    }

    @Test
    void actualTenSecondClaimLockTimeoutRollsBackFixedOpeningContinuesNextDateAndRecoversOnce() throws Exception {
        long group = team("REGULAR");
        LocalDate blockedDate = TODAY.plusDays(15);
        LocalDate followingDate = blockedDate.plusDays(1);
        success(createAssignment(assignment(group, 6, "B01", blockedDate)), 201);
        jdbc.update("INSERT INTO booking_days(reservation_date,status,created_at) VALUES (?,'PREPARING',?)",
                blockedDate, Timestamp.from(clock.instant()));
        clock.set(TODAY.plusDays(2) + "T00:00:00+09:00");
        Map<String, String> before = snapshots("recurring_assignments", "recurring_assignment_revisions",
                "recurring_occurrences", "recurring_occurrence_reservations", "reservations",
                "quota_entries", "reservation_operations", "recurring_idempotency_requests");

        try (Connection connection = fixtureConnection(null);
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                // Only this disposable database is locked. This blocks the first real claim INSERT,
                // after occurrence, SYSTEM operation, and reservation writes have already run.
                statement.execute("LOCK TABLE reservation_assets IN ACCESS EXCLUSIVE MODE");
            }
            var preparing = executor.submit(() -> {
                long started = System.nanoTime();
                opening.prepareRange(blockedDate, followingDate);
                return System.nanoTime() - started;
            });
            awaitWaiter("reservation_assets");
            long elapsedNanos = preparing.get(25, TimeUnit.SECONDS);
            assertTrue(elapsedNanos >= Duration.ofSeconds(9).toNanos(),
                    "The unchanged production ten-second timeout must expire while the PostgreSQL lock is still held");
            assertEquals("PREPARING", jdbc.queryForObject(
                    "SELECT status FROM booking_days WHERE reservation_date=?", String.class, blockedDate));
            assertFalse(jdbc.queryForObject(
                    "SELECT recurring_prepared FROM booking_days WHERE reservation_date=?", Boolean.class, blockedDate));
            assertEquals(before, snapshots(before.keySet().toArray(String[]::new)),
                    "A timed-out claim must roll back the preceding fixed occurrence, reservation, operation, and quota");
            assertEquals("OPEN", jdbc.queryForObject(
                    "SELECT status FROM booking_days WHERE reservation_date=?", String.class, followingDate));
            assertTrue(jdbc.queryForObject(
                    "SELECT recurring_prepared FROM booking_days WHERE reservation_date=?", Boolean.class, followingDate),
                    "A failed date must not stop preparation of the next date");
            // Release the real PostgreSQL lock only after timeout/rollback and the next-date commit.
            connection.rollback();
        }

        assertEquals(0, activeClaims());
        assertTrue(opening.prepareDate(blockedDate));
        JsonNode booking = fixed(group, blockedDate, "B01");
        assertEquals("OPEN", jdbc.queryForObject(
                "SELECT status FROM booking_days WHERE reservation_date=?", String.class, blockedDate));
        assertTrue(jdbc.queryForObject(
                "SELECT recurring_prepared FROM booking_days WHERE reservation_date=?", Boolean.class, blockedDate));
        assertEquals(1, count("recurring_occurrences"));
        assertEquals(1, count("reservations"));
        assertEquals(6, activeClaims());
        assertEquals(1, count("quota_entries"));
        assertEquals(0, quota(group, blockedDate));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM reservation_operations WHERE kind='FIXED_GENERATE'", Integer.class));
        Map<String, String> recovered = snapshots("recurring_occurrences", "recurring_occurrence_reservations",
                "reservations", "reservation_assets", "quota_entries", "reservation_operations");
        assertTrue(opening.prepareDate(blockedDate));
        assertEquals(id(booking), id(fixed(group, blockedDate, "B01")));
        assertEquals(recovered, snapshots(recovered.keySet().toArray(String[]::new)));
    }

    @Test
    void koreanMidnightExpandsExactlyOneDateAndFixedClaimsPrecedeNewFreeReservation() throws Exception {
        long group = team("ADDITIONAL");
        Person individual = person("MEMBER");
        LocalDate newDate = TODAY.plusDays(15);
        success(createAssignment(assignment(group, 6, "B10", newDate)), 201);
        clock.set(TODAY + "T23:59:59+09:00");
        assertError(createReservation(individual.client(), personal(newDate, "B10", "DRUMS")), 409, "DATE_NOT_OPEN");
        assertEquals(0, fixedCount(group));
        clock.set(TODAY.plusDays(1) + "T00:00:00+09:00");
        assertError(createReservation(individual.client(), personal(newDate, "B10", "DRUMS")), 409, "RESERVATION_CONFLICT");
        JsonNode fixed = fixed(group, newDate, "B10");
        assertEquals(Instant.parse(newDate.plusDays(1) + "T00:00:00+09:00"),
                Instant.parse(fixed.get("endsAt").asString()));
        JsonNode schedule = success(individual.client().get("/api/v1/schedule"), 200);
        assertEquals(TODAY.plusDays(1).toString(), schedule.get("today").asString());
        assertEquals(newDate.toString(), schedule.get("bookableThrough").asString());
        JsonNode day = schedule.get("days").get(14);
        assertEquals("OPEN", day.get("openingStatus").asString());
        assertFalse(block(day, "B10").get("canReservePersonal").asBoolean());
        assertEquals(0, block(day, "B10").get("availableInstrumentCodes").size());
        success(createReservation(individual.client(), personal(newDate, "B09", "DRUMS")), 201);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booking_days WHERE reservation_date = ?", Integer.class, newDate.plusDays(1)));
    }

    @Test
    void dayLockDelaysOpeningAndFreeCreationCannotPassAWorkerPreparingFixedOccupancy() throws Exception {
        long group = team("REGULAR");
        Person individual = person("MEMBER");
        LocalDate newDate = TODAY.plusDays(15);
        success(createAssignment(assignment(group, 6, "B01", newDate)), 201);
        jdbc.update("INSERT INTO booking_days(reservation_date, status, created_at) VALUES (?, 'PREPARING', ?)",
                newDate, Timestamp.from(clock.instant()));
        clock.set(TODAY.plusDays(1) + "T00:00:00+09:00");
        JsonNode csrf = individual.client().csrf();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date = '2026-11-14' FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var worker = executor.submit(() -> { opening.openBookableWindow(); return null; });
            awaitWaiter("booking_days");
            var free = executor.submit(() -> individual.client().send("POST", "/api/v1/reservations",
                    personal(newDate, "B01", "DRUMS"), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            assertEquals("PREPARING", jdbc.queryForObject(
                    "SELECT status FROM booking_days WHERE reservation_date = ?", String.class, newDate));
            assertEquals(0, fixedCount(group));
            connection.commit();
            worker.get(30, TimeUnit.SECONDS);
            assertError(free.get(30, TimeUnit.SECONDS), 409, "RESERVATION_CONFLICT");
        }
        assertEquals(1, fixedCount(group));
        assertEquals(6, activeClaims());
        assertTrue(jdbc.queryForObject("SELECT recurring_prepared FROM booking_days WHERE reservation_date = ?", Boolean.class, newDate));
    }

    @Test
    void canceledOccurrenceNeverReappearsAfterWorkerRerunOrWeekdayAndBlockRevision() throws Exception {
        long group = team("REGULAR");
        Person leader = person("MEMBER");
        join(group, leader, true);
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        JsonNode original = fixed(group, TODAY, "B01");
        success(cancel(leader.client(), original), 200);
        opening.openBookableWindow();
        assertEquals("CANCELED", success(administrator.client().get(reservationPath(original)), 200).get("status").asString());
        assertEquals("CANCELED_EXCEPTION", occurrence(assignmentById(id(series)), TODAY).get("state").asString());
        JsonNode revised = success(administrator.client().write("PATCH", assignmentPath(series),
                revision(series, 6, "B02", TODAY), key()), 200);
        opening.openBookableWindow();
        JsonNode exception = occurrence(revised, TODAY);
        assertEquals("2026-10-26", exception.get("weekStartDate").asString());
        assertEquals("CANCELED_EXCEPTION", exception.get("state").asString());
        assertEquals(1, exception.get("reservationIds").size());
        assertEquals(original.get("id").asString(), exception.get("reservationIds").get(0).asString());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE team_id = ? AND reservation_date BETWEEN ? AND ? AND status = 'CONFIRMED'",
                Integer.class, group, TODAY.minusDays(4), TODAY.plusDays(2)));
        assertEquals("B02", fixed(group, TODAY.plusDays(8), "B02").get("blockCode").asString());
        assertEquals(2, revised.get("revisions").size());
    }

    @Test
    void revisionPreservesBoundaryWeekWhenOriginalDateIsBeforeEffectiveDate() throws Exception {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        clock.set(monday + "T11:00:00+09:00");
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 3, "B01", monday)), 201);
        JsonNode original = fixed(group, monday.plusDays(2), "B01");
        success(administrator.client().write("PATCH", assignmentPath(series),
                revision(series, 5, "B02", monday.plusDays(3)), key()), 200);
        opening.openBookableWindow();
        assertEquals(id(original), id(fixed(group, monday.plusDays(2), "B01")));
        assertEquals(0, activeAt(group, monday.plusDays(4), "B02"));
        assertEquals(1, activeAt(group, monday.plusDays(11), "B02"));
        assertEquals(2, assignmentById(id(series)).get("occurrences").size());
    }

    @Test
    void revisionPreservesBoundaryWeekWhenNewDateWouldBeBeforeEffectiveDate() throws Exception {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        clock.set(monday + "T11:00:00+09:00");
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", monday)), 201);
        JsonNode original = fixed(group, monday.plusDays(4), "B01");
        success(administrator.client().write("PATCH", assignmentPath(series),
                revision(series, 3, "B02", monday.plusDays(3)), key()), 200);
        opening.openBookableWindow();
        assertEquals(id(original), id(fixed(group, monday.plusDays(4), "B01")));
        assertEquals(0, activeAt(group, monday.plusDays(2), "B02"));
        assertEquals(1, activeAt(group, monday.plusDays(9), "B02"));
        assertEquals(2, assignmentById(id(series)).get("occurrences").size());
    }

    @Test
    void revisionMovesOnlyUnstartedEligibleOccurrenceRetainsWeeklyIdentityAndOriginalFixedExemption() throws Exception {
        LocalDate monday = LocalDate.of(2026, 11, 2);
        clock.set(monday + "T11:00:00+09:00");
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", monday)), 201);
        JsonNode original = fixed(group, monday.plusDays(4), "B01");
        String occurrenceId = occurrence(series, monday.plusDays(4)).get("id").asString();
        success(administrator.client().write("PATCH", assignmentPath(series),
                revision(series, 6, "B02", monday.plusDays(3)), key()), 200);
        JsonNode revised = assignmentById(id(series));
        JsonNode moved = fixed(group, monday.plusDays(5), "B02");
        assertNotEquals(id(original), id(moved));
        assertEquals("CANCELED", success(administrator.client().get(reservationPath(original)), 200).get("status").asString());
        assertEquals(occurrenceId, occurrence(revised, monday.plusDays(5)).get("id").asString());
        assertEquals(monday.toString(), occurrence(revised, monday.plusDays(5)).get("weekStartDate").asString());
        assertTrue(moved.get("quotaExempt").asBoolean());
        assertEquals(0, moved.get("quotaMinutes").asInt());
        assertEquals(0, quota(group, monday.plusDays(5)));
        opening.openBookableWindow();
        assertEquals(id(moved), id(fixed(group, monday.plusDays(5), "B02")));
    }

    @Test
    void revisionAtExactStartPreservesCurrentOccurrenceButChangesNextUnstartedWeek() throws Exception {
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        JsonNode original = fixed(group, TODAY, "B01");
        clock.set(TODAY + "T12:00:00+09:00");
        success(administrator.client().write("PATCH", assignmentPath(series), revision(series, 5, "B02", TODAY), key()), 200);
        assertEquals(id(original), id(fixed(group, TODAY, "B01")));
        assertEquals(0, activeAt(group, TODAY, "B02"));
        assertEquals("IN_PROGRESS", fixed(group, TODAY, "B01").get("phase").asString());
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B02"));
        clock.set(TODAY + "T13:00:00+09:00");
        opening.openBookableWindow();
        assertEquals("ENDED", success(administrator.client().get(reservationPath(original)), 200).get("phase").asString());
        assertEquals("CONFIRMED", success(administrator.client().get(reservationPath(original)), 200).get("status").asString());
    }

    @Test
    void revisionWaitingForDirectoryGuardRechecksTheStartTimeBeforeMovingCurrentOccurrence() throws Exception {
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        JsonNode original = fixed(group, TODAY, "B01");
        clock.set(TODAY + "T11:59:59+09:00");
        JsonNode csrf = administrator.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> administrator.client().send("PATCH", assignmentPath(series),
                    revision(series, 5, "B02", TODAY), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            clock.set(TODAY + "T12:00:00+09:00");
            connection.commit();
            success(waiting.get(25, TimeUnit.SECONDS), 200);
        }
        assertEquals(id(original), id(fixed(group, TODAY, "B01")));
        assertEquals(0, activeAt(group, TODAY, "B02"));
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B02"));
        assertEquals(0, quota(group, TODAY));
    }

    @Test
    void approvedFutureWeekdayRevisionDoesNotFallBackToOldPatternAfterDowntimePastItsNewTime() throws Exception {
        long group = team("REGULAR");
        Person individual = person("MEMBER");
        JsonNode series = success(createAssignment(assignment(group, 5, "B07", TODAY)), 201);
        LocalDate effectiveMonday = LocalDate.of(2026, 11, 23);
        success(administrator.client().write("PATCH", assignmentPath(series),
                revision(series, 3, "B07", effectiveMonday), key()), 200);
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM recurring_occurrences WHERE assignment_id=? AND week_start_date=?",
                Integer.class, id(series), effectiveMonday));
        // The process misses midnight and the newly approved Wednesday time before recovering on Thursday.
        clock.set(effectiveMonday.plusDays(3) + "T11:00:00+09:00");
        opening.openBookableWindow();
        assertEquals(0, activeAt(group, effectiveMonday.plusDays(4), "B07"),
                "An approved Wednesday pattern must never revert to the old Friday because Wednesday has passed");
        assertEquals(0, activeAt(group, effectiveMonday.plusDays(2), "B07"),
                "Recovery must not create a past reservation or reopen Wednesday");
        assertEquals(1, activeAt(group, effectiveMonday.plusDays(9), "B07"));
        assertEquals(0, activeAt(group, effectiveMonday.plusDays(11), "B07"));
        success(createReservation(individual.client(), personal(effectiveMonday.plusDays(4), "B07", "DRUMS")), 201);
        opening.openBookableWindow();
        assertEquals(0, activeAt(group, effectiveMonday.plusDays(4), "B07"));
        assertEquals(1, activeAt(group, effectiveMonday.plusDays(9), "B07"));
    }

    @Test
    void workerAndPatternRevisionSerializeThenOutOfWindowMoveStaysPlannedUntilItsDateOpens() throws Exception {
        long group = team("REGULAR");
        LocalDate lastBookable = TODAY.plusDays(15); // Opens after midnight on October 31.
        LocalDate deferred = lastBookable.plusDays(1);
        JsonNode series = success(createAssignment(assignment(group, 6, "B01", lastBookable)), 201);
        jdbc.update("INSERT INTO booking_days(reservation_date,status,created_at) VALUES (?,'PREPARING',?)",
                lastBookable, Timestamp.from(clock.instant()));
        clock.set(TODAY.plusDays(1) + "T00:00:00+09:00");
        JsonNode csrf = administrator.client().csrf();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-11-14' FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var worker = executor.submit(() -> { opening.openBookableWindow(); return null; });
            awaitWaiter("booking_days");
            var update = executor.submit(() -> administrator.client().send("PATCH", assignmentPath(series),
                    revision(series, 7, "B02", lastBookable), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            connection.commit();
            worker.get(30, TimeUnit.SECONDS);
            success(update.get(30, TimeUnit.SECONDS), 200);
        }
        JsonNode detail = assignmentById(id(series));
        JsonNode planned = occurrence(detail, lastBookable);
        String occurrenceId = planned.get("id").asString();
        assertEquals("PLANNED", planned.get("state").asString());
        assertEquals(deferred.toString(), planned.get("originalScheduledDate").asString());
        assertEquals("B02", planned.get("originalBlockCode").asString());
        assertEquals(0, activeAt(group, lastBookable, "B01"));
        assertEquals(0, activeAt(group, deferred, "B02"));
        assertEquals(0, activeClaims());
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booking_days WHERE reservation_date=? AND status='OPEN'", Integer.class, deferred));
        assertEquals("CANCELED", jdbc.queryForObject(
                "SELECT status FROM reservations WHERE team_id=? AND reservation_date=?", String.class, group, lastBookable));
        opening.openBookableWindow();
        assertEquals("PLANNED", occurrence(assignmentById(id(series)), lastBookable).get("state").asString());
        clock.set(TODAY.plusDays(2) + "T00:00:00+09:00");
        opening.openBookableWindow();
        JsonNode materialized = occurrence(assignmentById(id(series)), deferred);
        assertEquals(occurrenceId, materialized.get("id").asString());
        assertEquals("MATERIALIZED", materialized.get("state").asString());
        assertEquals(1, activeAt(group, deferred, "B02"));
        assertEquals(6, activeClaims());
        assertTrue(fixed(group, deferred, "B02").get("quotaExempt").asBoolean());
        assertTrue(jdbc.queryForObject("SELECT recurring_prepared FROM booking_days WHERE reservation_date=?", Boolean.class, deferred));
    }

    @Test
    void missedOpeningMaterializesRemainingFixedBlockButSkipsEndedBlockWithoutLosingWeeklyTombstone() throws Exception {
        long ongoing = team("REGULAR");
        long ended = team("ADDITIONAL");
        clock.set(TODAY + "T12:30:00+09:00");
        JsonNode series = success(createAssignment(assignment(ongoing, 5, "B01", TODAY)), 201);
        JsonNode booking = fixed(ongoing, TODAY, "B01");
        assertEquals(clock.instant(), Instant.parse(booking.get("usableFrom").asString()));
        assertTrue(booking.get("quotaExempt").asBoolean());
        assertEquals("IN_PROGRESS", booking.get("phase").asString());
        assertEquals("MATERIALIZED", occurrence(series, TODAY).get("state").asString());
        // Close its future pattern so the ended slot can belong to the second series in later weeks.
        success(administrator.client().write("POST", assignmentPath(series) + "/end",
                Map.of("expectedVersion", series.get("version").asLong(), "endDate", TODAY.toString()), key()), 200);
        clock.set(TODAY + "T13:00:00+09:00");
        assertError(cancel(administrator.client(), booking), 409, "RESERVATION_FINISHED");
        JsonNode expiredSeries = success(createAssignment(assignment(ended, 5, "B01", TODAY)), 201);
        assertEquals("ENDED", occurrence(expiredSeries, TODAY).get("state").asString());
        assertEquals(0, activeAt(ended, TODAY, "B01"));
        opening.openBookableWindow();
        assertEquals(2, fixedCount(ended));
        assertEquals("ENDED", occurrence(assignmentById(id(expiredSeries)), TODAY).get("state").asString());
    }

    @Test
    void previewDoesNotReserveAndActualApplyRechecksNewReservationAndRollsBackTheWholeAssignment() throws Exception {
        long group = team("REGULAR");
        Person individual = person("MEMBER");
        var body = assignment(group, 5, "B01", TODAY);
        JsonNode preview = success(administrator.client().mutate("POST", "/api/v1/recurring-assignments/preview", body), 200);
        assertTrue(preview.get("canApply").asBoolean());
        assertEquals(0, count("recurring_assignments"));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(0, count("reservations"));
        JsonNode original = success(createReservation(individual.client(), personal(TODAY.plusDays(7), "B01", "DRUMS")), 201);
        HttpResponse<String> conflict = createAssignment(body);
        assertError(conflict, 409, "ASSIGNMENT_CONFLICT");
        JsonNode conflicts = json.readTree(conflict.body()).at("/error/conflicts");
        assertTrue(conflicts.isArray() && !conflicts.isEmpty());
        assertTrue(conflicts.valueStream().anyMatch(c -> original.get("id").asString().equals(c.path("reservationId").asString())));
        assertEquals(0, count("recurring_assignments"));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(1, count("reservations"));
        assertEquals(1, activeClaims());
        assertEquals("CONFIRMED", success(individual.client().get(reservationPath(original)), 200).get("status").asString());
    }

    @Test
    void revisionConflictOnLaterWeekPreservesEveryEarlierOriginalClaimRevisionAndOccurrence() throws Exception {
        long group = team("REGULAR");
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        Person individual = person("MEMBER");
        JsonNode occupied = success(createReservation(individual.client(), personal(TODAY.plusDays(7), "B02", "DRUMS")), 201);
        Map<String, String> before = snapshots("recurring_assignments", "recurring_assignment_revisions", "recurring_occurrences",
                "recurring_occurrence_reservations", "reservations", "reservation_assets", "quota_entries", "reservation_operations");
        assertError(administrator.client().write("PATCH", assignmentPath(series), revision(series, 5, "B02", TODAY), key()),
                409, "ASSIGNMENT_CONFLICT");
        assertEquals(before, snapshots(before.keySet().toArray(String[]::new)));
        assertEquals(id(occupied), id(success(individual.client().get(reservationPath(occupied)), 200)));
        assertEquals(1, activeAt(group, TODAY, "B01"));
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01"));
        assertEquals(0, activeAt(group, TODAY, "B02"));
        assertEquals(series.get("version").asLong(), assignmentById(id(series)).get("version").asLong());
    }

    @Test
    void unopenedFuturePatternsConflictWithoutCreatingDateRowsAndNonoverlappingEndPeriodsAreAllowed() throws Exception {
        long regular = team("REGULAR");
        long additional = team("ADDITIONAL");
        LocalDate future = TODAY.plusDays(35);
        JsonNode first = success(createAssignment(assignment(regular, 5, "B07", future)), 201);
        assertEquals(0, fixedCount(regular));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booking_days WHERE reservation_date >= ?", Integer.class, future));
        HttpResponse<String> second = createAssignment(assignment(additional, 5, "B07", future.plusDays(14)));
        assertError(second, 409, "ASSIGNMENT_CONFLICT");
        assertTrue(json.readTree(second.body()).at("/error/conflicts").valueStream()
                .anyMatch(c -> "PATTERN".equals(c.get("type").asString())));
        success(administrator.client().write("POST", assignmentPath(first) + "/end",
                Map.of("expectedVersion", first.get("version").asLong(), "endDate", future.toString()), key()), 200);
        success(createAssignment(assignment(additional, 5, "B07", future.plusDays(7))), 201);
        assertEquals(2, count("recurring_assignments"));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(0, count("reservations"));
    }

    @Test
    void concurrentDifferentAdministratorsCannotCreateConflictingFuturePatternsWithoutBookingDays() throws Exception {
        long firstTeam = team("REGULAR");
        long secondTeam = team("ADDITIONAL");
        Person otherAdministrator = person("GLOBAL_ADMIN");
        JsonNode firstCsrf = administrator.client().csrf();
        JsonNode secondCsrf = otherAdministrator.client().csrf();
        LocalDate future = TODAY.plusDays(35);
        List<HttpResponse<String>> responses = race(
                () -> administrator.client().send("POST", "/api/v1/recurring-assignments",
                        assignment(firstTeam, 5, "B07", future), firstCsrf, key()),
                () -> otherAdministrator.client().send("POST", "/api/v1/recurring-assignments",
                        assignment(secondTeam, 5, "B07", future), secondCsrf, key()));
        assertEquals(List.of(201, 409), statuses(responses));
        assertError(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "ASSIGNMENT_CONFLICT");
        assertEquals(1, count("recurring_assignments"));
        assertEquals(1, count("recurring_assignment_revisions"));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM booking_days WHERE reservation_date >= ?", Integer.class, future));
    }

    @Test
    void administratorDemotedWhileWaitingForGuardCannotCreateARecurringAssignmentUsingOldSession() throws Exception {
        long group = team("REGULAR");
        JsonNode csrf = administrator.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> administrator.client().send("POST", "/api/v1/recurring-assignments",
                    assignment(group, 5, "B01", TODAY), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var update = connection.prepareStatement("UPDATE users SET global_role = 'MEMBER', version = version + 1 WHERE id = ?")) {
                update.setLong(1, administrator.member().id());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertError(waiting.get(25, TimeUnit.SECONDS), 403, "FORBIDDEN");
        }
        assertEquals(0, count("recurring_assignments"));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(0, count("reservations"));
    }

    @Test
    void endDateIsInclusiveAndEndCancelsOnlyUnstartedDirectOccurrencesBeyondThatDate() throws Exception {
        long group = team("REGULAR");
        Person leader = person("MEMBER");
        join(group, leader, true);
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", TODAY)), 201);
        JsonNode current = fixed(group, TODAY, "B01");
        JsonNode exception = fixed(group, TODAY.plusDays(7), "B01");
        success(cancel(leader.client(), exception), 200);
        clock.set(TODAY + "T12:00:00+09:00");
        JsonNode ended = success(administrator.client().write("POST", assignmentPath(series) + "/end",
                Map.of("expectedVersion", series.get("version").asLong(), "endDate", TODAY.toString()), key()), 200);
        assertEquals("ENDED", ended.get("status").asString());
        assertEquals(TODAY.toString(), ended.get("endDate").asString());
        assertEquals(id(current), id(fixed(group, TODAY, "B01")));
        assertEquals("CANCELED_EXCEPTION", occurrence(ended, TODAY.plusDays(7)).get("state").asString());
        assertEquals("ENDED", occurrence(ended, TODAY.plusDays(14)).get("state").asString());
        assertEquals(0, activeAt(group, TODAY.plusDays(14), "B01"));
        opening.openBookableWindow();
        assertEquals(3, count("recurring_occurrences"));
        assertEquals(1, activeAt(group, TODAY, "B01"));
        assertEquals(6, activeClaims());
        assertEquals(0, quota(group, TODAY));
    }

    @Test
    void futureEndDateStillGeneratesItsIncludedOccurrenceWhenThatDateBecomesBookable() throws Exception {
        long group = team("ADDITIONAL");
        LocalDate future = TODAY.plusDays(21);
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", future)), 201);
        success(administrator.client().write("POST", assignmentPath(series) + "/end",
                Map.of("expectedVersion", series.get("version").asLong(), "endDate", future.toString()), key()), 200);
        assertEquals(0, count("reservations"));
        clock.set(TODAY.plusDays(7) + "T00:00:00+09:00");
        opening.openBookableWindow();
        assertEquals(1, activeAt(group, future, "B01"));
        clock.set(TODAY.plusDays(14) + "T00:00:00+09:00");
        opening.openBookableWindow();
        assertEquals(0, activeAt(group, future.plusDays(7), "B01"));
        assertEquals(1, fixedCount(group));
    }

    @Test
    void endingAnInactiveTeamsFutureSeriesDoesNotMaterializeANewFixedBooking() throws Exception {
        long group = team("ADDITIONAL");
        LocalDate future = TODAY.plusDays(35);
        JsonNode series = success(createAssignment(assignment(group, 5, "B01", future)), 201);
        assertEquals(0, count("reservations"));
        jdbc.update("UPDATE teams SET status='DISBANDED',version=version+1 WHERE id=?", group);
        clock.set(future.minusDays(14) + "T00:00:00+09:00");
        JsonNode ended = success(administrator.client().write("POST", assignmentPath(series) + "/end",
                Map.of("expectedVersion", series.get("version").asLong(), "endDate", future.toString()), key()), 200);
        assertEquals("ENDED", ended.get("status").asString());
        assertEquals("ENDED", occurrence(ended, future).get("state").asString());
        assertEquals(0, count("reservations"));
        assertEquals(0, activeClaims());
        opening.openBookableWindow();
        assertEquals(0, count("reservations"));
        assertEquals(0, jdbc.queryForObject(
                "SELECT count(*) FROM reservation_operations WHERE kind='FIXED_GENERATE'", Integer.class));
    }

    @Test
    void staleVersionAndInvalidAssignmentInputsLeaveExistingPatternAndReservationsUnchanged() throws Exception {
        long group = team("REGULAR");
        var body = assignment(group, 5, "B01", TODAY);
        assertError(administrator.client().write("POST", "/api/v1/recurring-assignments", body, null), 400, "INVALID_INPUT");
        for (Object weekday : List.of(0, 8, 1.5)) {
            var invalid = new HashMap<>(body); invalid.put("weekday", weekday);
            assertError(createAssignment(invalid), 400, "INVALID_INPUT");
        }
        var unknown = new HashMap<>(body); unknown.put("quotaExempt", true);
        assertError(createAssignment(unknown), 400, "INVALID_INPUT");
        JsonNode series = success(createAssignment(body), 201);
        var stale = revision(series, 5, "B02", TODAY);
        stale.put("expectedVersion", series.get("version").asLong() + 1);
        assertError(administrator.client().write("PATCH", assignmentPath(series), stale, key()), 409, "VERSION_CONFLICT");
        assertEquals(1, count("recurring_assignment_revisions"));
        assertEquals(3, fixedCount(group));
        assertEquals(1, activeAt(group, TODAY, "B01"));
    }

    @Test
    void prepareLegacyOpenDateKeepsManualReservationAndItsClaimsAndQuotaUnchanged() throws Exception {
        Person member = person("MEMBER");
        JsonNode booking = success(createReservation(member.client(), personal(TODAY, "B01", "DRUMS")), 201);
        Map<String, String> before = snapshots("reservations", "reservation_assets", "quota_entries", "reservation_operations", "idempotency_requests");
        jdbc.update("UPDATE booking_days SET recurring_prepared=FALSE WHERE reservation_date=?", TODAY);
        opening.openBookableWindow();
        assertEquals(before, snapshots(before.keySet().toArray(String[]::new)));
        assertEquals(id(booking), id(success(member.client().get(reservationPath(booking)), 200)));
        assertTrue(jdbc.queryForObject("SELECT recurring_prepared FROM booking_days WHERE reservation_date=?", Boolean.class, TODAY));
        assertEquals("OPEN", jdbc.queryForObject("SELECT status FROM booking_days WHERE reservation_date=?", String.class, TODAY));
        assertEquals(0, count("recurring_occurrences"));
        assertEquals(1, activeClaims());
    }

    @Test
    void postgresRejectsDuplicateWeeklyOccurrenceAndCrossSeriesRevisionAndNonMondayWeekKey() throws Exception {
        long regular = team("REGULAR");
        long additional = team("ADDITIONAL");
        JsonNode first = success(createAssignment(assignment(regular, 5, "B01", TODAY)), 201);
        JsonNode second = success(createAssignment(assignment(additional, 5, "B07", TODAY)), 201);
        long firstOccurrence = id(occurrence(first, TODAY));
        long secondRevision = id(second.get("revisions").get(0));
        long ninetyMinuteReservation = id(fixed(additional, TODAY, "B07"));
        try (Connection connection = fixtureConnection(null)) {
            assertSqlState(connection, "23514",
                    "UPDATE reservations SET quota_exempt=TRUE WHERE id=?", ninetyMinuteReservation);
            assertSqlState(connection, "23505", """
                    INSERT INTO recurring_occurrences(assignment_id,week_start_date,pattern_revision_id,
                    original_scheduled_date,original_block_code,state,created_at,updated_at)
                    SELECT assignment_id,week_start_date,pattern_revision_id,original_scheduled_date,
                    original_block_code,state,created_at,updated_at FROM recurring_occurrences WHERE id=?
                    """, firstOccurrence);
            assertSqlState(connection, "23503",
                    "UPDATE recurring_occurrences SET pattern_revision_id=? WHERE id=?", secondRevision, firstOccurrence);
            assertSqlState(connection, "23514",
                    "UPDATE recurring_occurrences SET week_start_date=week_start_date+1 WHERE id=?", firstOccurrence);
            assertSqlState(connection, "23514",
                    "UPDATE recurring_occurrences SET original_scheduled_date=original_scheduled_date+7 WHERE id=?", firstOccurrence);
        }
        assertEquals(6, count("recurring_occurrences"));
        assertEquals(36, activeClaims());
    }

    @Test
    void v5UpgradePreservesPopulatedV4BookingsClaimsQuotaDirectoryInvitationAndNinetyDaySession() throws Exception {
        // This separate schema also lives only inside the disposable Testcontainers database.
        String schema = "recurring_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        fixtureFlyway(schema, "4").migrate();
        Map<String, TableSnapshot> before = new HashMap<>();
        try (Connection connection = fixtureConnection(schema)) {
            long admin = insertId(connection, """
                    INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at)
                    VALUES ('synthetic-upgrade-admin',?,'합성 관리자','GLOBAL_ADMIN','ACTIVE',?) RETURNING id
                    """, passwords.encode(password), Timestamp.from(clock.instant()));
            long member = insertId(connection, """
                    INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at)
                    VALUES ('synthetic-upgrade-member',?,'합성 회원','MEMBER','ACTIVE',?) RETURNING id
                    """, passwords.encode(password), Timestamp.from(clock.instant()));
            long roster = insertId(connection,
                    "INSERT INTO roster_entries(name,user_id) VALUES ('합성 연결 명단',?) RETURNING id", member);
            long pending = insertId(connection,
                    "INSERT INTO roster_entries(name) VALUES ('합성 초대 명단') RETURNING id");
            long group = insertId(connection,
                    "INSERT INTO teams(name,kind) VALUES ('합성 이전 팀','REGULAR') RETURNING id");
            execute(connection, "INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,'LEADER')", group, roster);
            execute(connection, """
                    INSERT INTO team_songs(team_id,title,artist,memo,sort_order,created_by,updated_by)
                    VALUES (?,'합성 곡','합성 가수','합성 메모',1,?,?)
                    """, group, admin, member);
            execute(connection, """
                    INSERT INTO invitation_codes(code_digest,recipient_name,issued_by,created_at,expires_at,roster_entry_id)
                    VALUES (?,'합성 초대',?,?,?,?)
                    """, kr.jeongeum.scheduler.invitation.service.InvitationTokens.digest(UUID.randomUUID().toString()),
                    admin, Timestamp.from(clock.instant()), Timestamp.from(clock.instant().plusSeconds(86400)), pending);
            String session = UUID.randomUUID().toString();
            execute(connection, """
                    INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
                    VALUES (?,?,?,?,7776000,?,'synthetic-upgrade-member')
                    """, session, UUID.randomUUID().toString(), clock.instant().toEpochMilli(), clock.instant().toEpochMilli(),
                    clock.instant().plus(Duration.ofDays(90)).toEpochMilli());
            execute(connection, "INSERT INTO spring_session_attributes VALUES (?,'synthetic.recurring-upgrade',?)",
                    session, "synthetic-session-preservation".getBytes(StandardCharsets.UTF_8));
            execute(connection, """
                    INSERT INTO booking_days(reservation_date,status,opened_at,created_at)
                    VALUES (?,'OPEN',?,?)
                    """, TODAY, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
            long operation = insertId(connection, """
                    INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at)
                    VALUES ('CREATE','USER',?,?) RETURNING id
                    """, member, Timestamp.from(clock.instant()));
            long booking = insertId(connection, """
                    INSERT INTO reservations(owner_type,purpose,owner_user_id,creator_user_id,reservation_date,time_block_code,
                    block_start_minute,block_end_minute,duration_minutes,block_start_at,block_end_at,usable_from,
                    status,origin_kind,created_at,created_operation_id)
                    VALUES ('PERSONAL','PERSONAL',?, ?,?,'B01',720,780,60,?,?,?,'CONFIRMED','MANUAL',?,?) RETURNING id
                    """, member, member, TODAY, Timestamp.from(Instant.parse(TODAY + "T12:00:00+09:00")),
                    Timestamp.from(Instant.parse(TODAY + "T13:00:00+09:00")),
                    Timestamp.from(Instant.parse(TODAY + "T12:00:00+09:00")), Timestamp.from(clock.instant()), operation);
            execute(connection, """
                    INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at)
                    VALUES (?,?,'B01',(SELECT id FROM assets WHERE code='DRUMS'),'PERSONAL',?)
                    """, booking, TODAY, Timestamp.from(clock.instant()));
            execute(connection, """
                    INSERT INTO quota_entries(reservation_id,operation_id,quota_date,owner_user_id,amount_minutes,entry_kind,created_at)
                    VALUES (?,?,?,?,60,'RESERVE',?)
                    """, booking, operation, TODAY, member, Timestamp.from(clock.instant()));
            execute(connection, """
                    INSERT INTO idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,
                    result_reservation_id,operation_id,created_at)
                    VALUES (?, ?,?,'POST','/api/v1/reservations',201,?,?,?)
                    """, member, "a".repeat(64), "b".repeat(64), booking, operation, Timestamp.from(clock.instant()));
            for (String table : List.of("users", "roster_entries", "teams", "team_memberships", "team_songs", "invitation_codes",
                    "spring_session", "spring_session_attributes", "directory_mutation_guard", "time_blocks", "assets",
                    "booking_days", "reservations", "reservation_assets", "quota_entries", "reservation_operations", "idempotency_requests")) {
                before.put(table, tableSnapshot(connection, table));
            }
        }
        assertEquals(1, fixtureFlyway(schema, "5").migrate().migrationsExecuted);
        assertEquals(0, fixtureFlyway(schema, "5").migrate().migrationsExecuted);
        try (Connection connection = fixtureConnection(schema)) {
            for (var entry : before.entrySet()) {
                assertEquals(entry.getValue().data(), projectedSnapshot(connection, entry.getKey(), entry.getValue().projection()),
                        "V5 must preserve existing synthetic values in " + entry.getKey());
            }
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recurring_schedule_guard WHERE id=1"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM booking_days WHERE recurring_prepared"));
            for (String table : List.of("recurring_assignments", "recurring_assignment_revisions", "recurring_occurrences",
                    "recurring_occurrence_reservations")) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM " + table));
            }
            assertEquals(5, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
        }
    }

    private Flyway fixtureFlyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
    private Connection fixtureConnection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (schema != null) { connection.setSchema(schema); }
        return connection;
    }
    private record TableSnapshot(String projection, String data) {}
    private TableSnapshot tableSnapshot(Connection connection, String table) throws Exception {
        List<String> columns = new java.util.ArrayList<>();
        try (var statement = connection.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=? AND table_name=? ORDER BY ordinal_position")) {
            statement.setString(1, connection.getSchema());
            statement.setString(2, table);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) { columns.add('"' + rows.getString(1) + '"'); }
            }
        }
        String projection = String.join(",", columns);
        return new TableSnapshot(projection, projectedSnapshot(connection, table, projection));
    }
    private String projectedSnapshot(Connection connection, String table, String projection) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM (SELECT " + projection + " FROM " + table + ") r")) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
    private long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }
    private long insertId(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            try (var rows = statement.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }
    private void execute(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            assertEquals(1, statement.executeUpdate());
        }
    }
    private void assertSqlState(Connection connection, String state, String sql, long... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setLong(i + 1, parameters[i]); }
            java.sql.SQLException exception = assertThrows(java.sql.SQLException.class, statement::executeUpdate);
            assertEquals(state, exception.getSQLState());
        }
    }

    private HttpResponse<String> createAssignment(Object body) throws Exception {
        return administrator.client().write("POST", "/api/v1/recurring-assignments", body, key());
    }
    private Map<String, Object> assignment(long team, int weekday, String block, LocalDate effective) {
        return Map.of("teamId", Long.toString(team), "weekday", weekday, "blockCode", block, "effectiveDate", effective.toString());
    }
    private Map<String, Object> revision(JsonNode series, int weekday, String block, LocalDate effective) {
        return new HashMap<>(Map.of("expectedVersion", series.get("version").asLong(),
                "weekday", weekday, "blockCode", block, "effectiveDate", effective.toString()));
    }
    private JsonNode assignmentById(long id) throws Exception {
        JsonNode list = success(administrator.client().get("/api/v1/recurring-assignments"), 200);
        return list.valueStream().filter(a -> Long.toString(id).equals(a.get("id").asString())).findFirst().orElseThrow();
    }
    private JsonNode occurrence(JsonNode series, LocalDate dateInWeek) {
        LocalDate monday = dateInWeek.minusDays(dateInWeek.getDayOfWeek().getValue() - 1);
        return series.get("occurrences").valueStream()
                .filter(o -> monday.toString().equals(o.get("weekStartDate").asString())).findFirst().orElseThrow();
    }
    private JsonNode fixed(long team, LocalDate date, String block) throws Exception {
        long reservationId = jdbc.queryForObject(
                "SELECT id FROM reservations WHERE team_id = ? AND reservation_date = ? AND time_block_code = ? AND origin_kind = 'FIXED' AND status = 'CONFIRMED'",
                Long.class, team, date, block);
        return success(administrator.client().get("/api/v1/reservations/" + reservationId), 200);
    }
    private long fixedCount(long team) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE team_id = ? AND origin_kind = 'FIXED'", Long.class, team);
    }
    private long activeAt(long team, LocalDate date, String block) {
        return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE team_id = ? AND reservation_date = ? AND time_block_code = ? AND status = 'CONFIRMED'",
                Long.class, team, date, block);
    }
    private int quota(long team, LocalDate date) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minutes),0)::integer FROM quota_entries WHERE team_id = ? AND quota_date = ?", Integer.class, team, date);
    }
    private long claims(long id) {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_id = ? AND released_at IS NULL", Long.class, id);
    }
    private long activeClaims() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE released_at IS NULL", Long.class);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private Map<String, String> snapshots(String... tables) {
        Map<String, String> result = new HashMap<>();
        for (String table : tables) {
            result.put(table, jdbc.queryForObject(
                    "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r", String.class));
        }
        return result;
    }
    private Connection lock(String sql) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); }
        return connection;
    }
    private void awaitWaiter(String table) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?)", Boolean.class, "%" + table + "%")));
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                            + "AND query ILIKE '%directory_mutation_guard%'", Integer.class) == 2);
            connection.commit();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
    private List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).sorted().toList();
    }
    private Person person(String role) throws Exception {
        Member member = members.insert("fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(password), "합성 반복 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name, user_id) VALUES ('합성 반복 명단', ?) RETURNING id", Long.class, member.id());
        Client client = new Client();
        success(client.mutate("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password)), 200);
        return new Person(member, roster, client);
    }
    private long team(String kind) {
        return jdbc.queryForObject("INSERT INTO teams(name, kind) VALUES ('합성 반복 조', ?) RETURNING id", Long.class, kind);
    }
    private void join(long team, Person person, boolean leader) {
        assertEquals(1, jdbc.update("INSERT INTO team_memberships(team_id, roster_entry_id, role) VALUES (?, ?, ?)",
                team, person.rosterId(), leader ? "LEADER" : "MEMBER"));
    }
    private Map<String, Object> personal(LocalDate date, String block, String instrument) {
        return Map.of("subjectType", "PERSONAL", "date", date.toString(), "blockCode", block, "instrumentCodes", List.of(instrument));
    }
    private Map<String, Object> teamBody(LocalDate date, String block, long team) {
        return Map.of("subjectType", "TEAM", "date", date.toString(), "blockCode", block, "teamId", Long.toString(team));
    }
    private HttpResponse<String> createReservation(Client client, Object body) throws Exception {
        return client.write("POST", "/api/v1/reservations", body, key());
    }
    private HttpResponse<String> cancel(Client client, JsonNode reservation) throws Exception {
        return client.write("POST", reservationPath(reservation) + "/cancel", Map.of("expectedVersion", reservation.get("version").asLong()), key());
    }
    private JsonNode block(JsonNode day, String code) {
        return day.get("blocks").valueStream().filter(b -> code.equals(b.get("blockCode").asString())).findFirst().orElseThrow();
    }
    private Set<String> strings(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).collect(java.util.stream.Collectors.toSet());
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
    private String assignmentPath(JsonNode series) { return "/api/v1/recurring-assignments/" + series.get("id").asString(); }
    private String reservationPath(JsonNode booking) { return "/api/v1/reservations/" + booking.get("id").asString(); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Person(Member member, long rosterId, Client client) {}

    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"), 200); }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception { return send(method, path, body, csrf(), null); }
        HttpResponse<String> write(String method, String path, Object body, String requestKey) throws Exception {
            return send(method, path, body, csrf(), requestKey);
        }
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
        @Bean @Primary TestClock recurringTestClock() { return new TestClock(); }
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
