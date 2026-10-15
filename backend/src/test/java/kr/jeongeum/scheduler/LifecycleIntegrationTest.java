package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.notification.service.ReminderProcessor;
import kr.jeongeum.scheduler.recurring.service.RecurringOpeningService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
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
        properties = {"jeongeum.recurring.worker.enabled=false", "jeongeum.exchange.worker.enabled=false", "jeongeum.reminder.worker.enabled=false",
                "jeongeum.push.worker.enabled=false", "jeongeum.push.enabled=false"})
@Import(LifecycleIntegrationTest.TestTime.class)
@Timeout(90)
class LifecycleIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final String[] BUSINESS_TABLES = {"users", "teams", "roster_entries", "team_memberships",
            "team_songs", "invitation_codes", "member_role_audit", "reservations", "reservation_assets", "quota_entries",
            "reservation_operations", "reservation_lineage", "idempotency_requests", "recurring_assignments",
            "recurring_assignment_revisions", "recurring_occurrences", "recurring_occurrence_reservations",
            "recurring_idempotency_requests", "exchange_requests", "exchange_request_items", "exchange_instrument_allocations",
            "exchange_idempotency_requests", "notifications", "notification_jobs", "push_subscriptions", "lifecycle_operations"};
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("lifecycle_test").withUsername("lifecycle_test")
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
    @Autowired ReminderProcessor reminders;
    private String password;
    private Person administrator;

    @BeforeEach
    void setup() throws Exception {
        // All fixture writes, triggers, locks and schema migrations belong only to this disposable database.
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL', version=0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
        opening.prepareRange(TODAY, TODAY.plusDays(14));
    }

    @AfterEach
    void removeFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS lifecycle_test_failure ON lifecycle_operations");
        jdbc.execute("DROP FUNCTION IF EXISTS lifecycle_test_failure()");
    }

    @Test
    void withdrawalAndDisbandRequireAuthenticationCsrfAndCurrentAdministratorAuthority() throws Exception {
        Person ordinary = person("MEMBER");
        long group = team("REGULAR");
        join(group, ordinary, true);
        assertError(new Client().get("/api/v1/users"), 401, "AUTHENTICATION_REQUIRED");
        assertError(administrator.client().send("POST", withdrawalPath(ordinary), body(0), null, key()), 403, "CSRF_INVALID");
        assertError(administrator.client().send("POST", disbandPath(group), body(0), null, key()), 403, "CSRF_INVALID");
        Map<String, String> before = snapshots();
        assertError(withdraw(ordinary.client(), ordinary, body(0), key()), 403, "FORBIDDEN");
        assertError(disband(ordinary.client(), group, body(0), key()), 403, "FORBIDDEN");
        assertEquals(before, snapshots());
        assertEquals("ACTIVE", memberStatus(ordinary));
        assertEquals("ACTIVE", teamStatus(group));
    }

    @Test
    void missingKeyMalformedVersionsOversizedReasonAndUnknownTargetNeverMutate() throws Exception {
        Person target = person("MEMBER");
        long group = team("ADDITIONAL");
        Map<String, String> before = snapshots();
        assertError(withdraw(administrator.client(), target, body(0), null), 400, "INVALID_INPUT");
        assertError(disband(administrator.client(), group, body(0), "bad"), 400, "INVALID_INPUT");
        for (Object version : List.of(-1, 0.5)) {
            assertError(withdraw(administrator.client(), target, Map.of("expectedVersion", version), key()), 400, "INVALID_INPUT");
        }
        assertError(withdraw(administrator.client(), target, Map.of("reason", "synthetic"), key()), 400, "INVALID_INPUT");
        assertError(withdraw(administrator.client(), target, Map.of("expectedVersion", 0, "reason", "x".repeat(501)), key()), 400, "INVALID_INPUT");
        assertError(administrator.client().write("POST", "/api/v1/users/999999/withdraw", body(0), key()), 404, "RESOURCE_NOT_FOUND");
        assertError(administrator.client().write("POST", "/api/v1/teams/999999/disband", body(0), key()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(before, snapshots());
    }

    @Test
    void staleVersionsAndLastActiveAdministratorAreProtectedWithoutPartialCleanup() throws Exception {
        Person target = person("MEMBER");
        long group = team("REGULAR");
        personal(target, TODAY, "B07", "DRUMS");
        Map<String, String> before = snapshots();
        assertError(withdraw(administrator.client(), target, body(1), key()), 409, "VERSION_CONFLICT");
        assertError(disband(administrator.client(), group, body(1), key()), 409, "VERSION_CONFLICT");
        assertError(withdraw(administrator.client(), administrator, body(0), key()), 409, "LAST_GLOBAL_ADMIN");
        assertEquals(before, snapshots());
        Person inactiveAdmin = person("GLOBAL_ADMIN");
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", inactiveAdmin.id());
        assertError(withdraw(administrator.client(), administrator, body(0), key()), 409, "LAST_GLOBAL_ADMIN");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role='GLOBAL_ADMIN' AND status='ACTIVE'", Integer.class));
    }

    @Test
    void sameWithdrawalKeyReplaysOriginalSummaryAndDifferentBodyOrRouteCannotReuseIt() throws Exception {
        Person target = person("MEMBER");
        long group = team("REGULAR");
        personal(target, TODAY, "B07", "DRUMS");
        String requestKey = key();
        Map<String, Object> input = Map.of("expectedVersion", 0, "reason", "합성 탈퇴 사유");
        JsonNode result = success(withdraw(administrator.client(), target, input, requestKey), 200);
        assertEquals("WITHDRAWN", result.get("status").asString());
        assertEquals(Long.toString(target.id()), result.get("targetId").asString());
        assertEquals(1, result.get("version").asLong());
        assertEquals(1, result.get("canceledReservations").asInt());
        assertTrue(result.get("operationId").isString());
        Map<String, String> after = snapshots();
        assertEquals(result, success(withdraw(administrator.client(), target, input, requestKey), 200));
        assertEquals(after, snapshots());
        assertError(withdraw(administrator.client(), target, body(0), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(disband(administrator.client(), group, body(0), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(withdraw(administrator.client(), target, body(1), key()), 409, "MEMBER_ALREADY_WITHDRAWN");
        assertEquals(after, snapshots());
        assertEquals("합성 탈퇴 사유", jdbc.queryForObject("SELECT reason FROM lifecycle_operations", String.class));
        jdbc.update("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", administrator.id());
        assertError(withdraw(administrator.client(), target, input, requestKey), 403, "FORBIDDEN");
    }

    @Test
    void withdrawalCancelsFutureAndOngoingPersonalReservationsPreservesCompletedHistoryAndStartedQuota() throws Exception {
        Person target = person("MEMBER");
        JsonNode completed = personal(target, TODAY, "B01", "DRUMS");
        JsonNode ongoing = personal(target, TODAY, "B03", "DRUMS");
        JsonNode future = personal(target, TODAY, "B07", "VOCAL");
        JsonNode tomorrow = personal(target, TODAY.plusDays(1), "B01", "BASS");
        Person unrelated = person("MEMBER");
        JsonNode unaffected = personal(unrelated, TODAY.plusDays(1), "B01", "DRUMS");
        String completedBefore = row("reservations", id(completed));
        String unrelatedBefore = row("reservations", id(unaffected));
        long completedClaims = claims(completed);
        clock.set(TODAY + "T14:30:00+09:00");
        JsonNode result = success(withdraw(administrator.client(), target, body(0), key()), 200);
        assertEquals(3, result.get("canceledReservations").asInt());
        assertEquals("WITHDRAWN", memberStatus(target));
        assertNotNull(jdbc.queryForObject("SELECT withdrawn_at FROM users WHERE id=?", Timestamp.class, target.id()));
        for (JsonNode reservation : List.of(ongoing, future, tomorrow)) {
            assertEquals("CANCELED", reservationStatus(reservation));
            assertEquals(0, claims(reservation));
            assertEquals(target.id(), jdbc.queryForObject("SELECT creator_user_id FROM reservations WHERE id=?", Long.class, id(reservation)));
            assertEquals(reservation.get("version").asLong() + 1, jdbc.queryForObject("SELECT version FROM reservations WHERE id=?", Long.class, id(reservation)));
        }
        assertEquals(completedBefore, row("reservations", id(completed)));
        assertEquals(completedClaims, claims(completed));
        assertEquals(120, quota(target, null, TODAY));
        assertEquals(0, quota(target, null, TODAY.plusDays(1)));
        assertEquals(unrelatedBefore, row("reservations", id(unaffected)));
        assertEquals(60, quota(unrelated, null, TODAY.plusDays(1)));
        assertEquals(5, count("reservations"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE reservation_id=? AND amount_minutes=-60", Integer.class, id(tomorrow)));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE reservation_id=? AND amount_minutes<0", Integer.class, id(ongoing)));
    }

    @Test
    void departingCreatorAndLeaderLeaveSurvivingTeamBookingsSongsAndHistoryIntactWithoutAutomaticSuccessor() throws Exception {
        Person departing = person("MEMBER"), remaining = person("MEMBER");
        long group = team("REGULAR"), another = team("ADDITIONAL");
        join(group, departing, true); join(group, remaining, false);
        join(another, departing, true); join(another, remaining, false);
        JsonNode reservation = teamReservation(departing, TODAY, "B07", group);
        jdbc.update("INSERT INTO team_songs(team_id,title,sort_order,created_by,updated_by) VALUES (?,'합성 보존 곡',0,?,?)", group, departing.id(), departing.id());
        String before = row("reservations", id(reservation));
        String songsBefore = table("team_songs");
        JsonNode result = success(withdraw(administrator.client(), departing, body(0), key()), 200);
        assertEquals(0, result.get("canceledReservations").asInt());
        assertEquals(before, row("reservations", id(reservation)));
        assertEquals(songsBefore, table("team_songs"));
        assertEquals(6, claims(reservation));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE roster_entry_id=? AND status='ACTIVE'", Integer.class, departing.rosterId()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE team_id IN (?,?) AND status='ACTIVE' AND role='LEADER'", Integer.class, group, another));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE roster_entry_id=? AND status='ACTIVE'", Integer.class, remaining.rosterId()));
        assertEquals("ACTIVE", teamStatus(group));
        JsonNode history = success(administrator.client().get(reservationPath(reservation)), 200);
        assertEquals(Long.toString(departing.id()), history.get("creatorUserId").asString());
        // A remaining ordinary member keeps creation authority, without gaining departed creator edit authority.
        assertError(change(remaining.client(), reservation, TODAY, "B08", null), 403, "FORBIDDEN");
        success(teamReservationResponse(remaining, TODAY.plusDays(1), "B07", group), 201);
    }

    @Test
    void withdrawalRevokesEverySessionAndDeviceCancelsWaitingJobsButPreservesExistingInboxAndOtherUsers() throws Exception {
        Person target = person("MEMBER"), unrelated = person("MEMBER");
        Client second = authenticated(target.member());
        JsonNode firstCsrf = target.client().csrf(), secondCsrf = second.csrf();
        subscription(target, "first"); subscription(target, "second"); subscription(unrelated, "other");
        JsonNode reservation = personal(target, TODAY, "B07", "DRUMS");
        JsonNode other = personal(unrelated, TODAY, "B08", "VOCAL");
        String inboxBefore = filteredTable("notifications", "user_id=" + target.id());
        String unrelatedSubscriptions = filteredTable("push_subscriptions", "user_id=" + unrelated.id());
        Set<String> targetSessions = sessions(target);
        assertTrue(targetSessions.size() >= 2);
        JsonNode result = success(withdraw(administrator.client(), target, body(0), key()), 200);
        assertEquals(2, result.get("deactivatedSubscriptions").asInt());
        assertEquals(targetSessions.size(), result.get("revokedSessions").asInt());
        assertEquals(Set.of(), sessions(target));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id=? AND active", Integer.class, target.id()));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id=? AND NOT active AND deactivated_at IS NOT NULL", Integer.class, target.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_jobs j LEFT JOIN push_subscriptions s ON s.id=j.subscription_id WHERE (j.owner_user_id=? OR s.user_id=?) AND j.status IN ('PENDING','PROCESSING','RETRY_WAIT')", Integer.class, target.id(), target.id()));
        assertEquals(inboxBefore, filteredTable("notifications", "user_id=" + target.id()));
        assertEquals(unrelatedSubscriptions, filteredTable("push_subscriptions", "user_id=" + unrelated.id()));
        assertEquals("CONFIRMED", reservationStatus(other));
        assertEquals("CANCELED", reservationStatus(reservation));
        assertError(target.client().get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        assertError(second.get("/api/v1/notifications"), 401, "AUTHENTICATION_REQUIRED");
        assertError(target.client().send("POST", "/api/v1/reservations", personalBody(TODAY, "B09", "DRUMS"), firstCsrf, key()), 403, "CSRF_INVALID");
        assertError(second.send("POST", "/api/v1/push-subscriptions", Map.of(), secondCsrf, null), 403, "CSRF_INVALID");
        assertError(new Client().write("POST", "/api/v1/auth/login", Map.of("loginId", target.member().loginId(), "password", password), null), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void teamReminderRecomputesRecipientsAfterWithdrawalAndNeverNotifiesDepartedCreator() throws Exception {
        Person departed = person("MEMBER"), remaining = person("MEMBER");
        long group = team("REGULAR"); join(group, departed, true); join(group, remaining, false);
        teamReservation(departed, TODAY, "B07", group);
        String departedInbox = filteredTable("notifications", "user_id=" + departed.id());
        success(withdraw(administrator.client(), departed, body(0), key()), 200);
        clock.set(TODAY + "T17:00:00+09:00");
        assertEquals(1, reminders.drain(50));
        assertEquals(Set.of(remaining.id()), Set.copyOf(jdbc.queryForList("SELECT user_id FROM notifications WHERE event_type='START_REMINDER'", Long.class)));
        assertEquals(departedInbox, filteredTable("notifications", "user_id=" + departed.id()));
    }

    @Test
    void withdrawalInvalidatesRequestersAndCanceledPersonalSourcesButPreservesOtherPendingExchanges() throws Exception {
        Person departed = person("MEMBER"), other = person("MEMBER"), unrelated = person("MEMBER"), fourth = person("MEMBER");
        JsonNode left = personal(departed, TODAY, "B07", "DRUMS"), right = personal(other, TODAY, "B08", "VOCAL");
        JsonNode affected = exchange(departed.client(), left, right);
        JsonNode unrelatedLeft = personal(unrelated, TODAY, "B09", "BASS"), unrelatedRight = personal(fourth, TODAY, "B10", "KEYBOARD");
        JsonNode unaffected = exchange(unrelated.client(), unrelatedLeft, unrelatedRight);
        String unrelatedBefore = row("exchange_requests", id(unaffected));
        JsonNode result = success(withdraw(administrator.client(), departed, body(0), key()), 200);
        assertEquals(1, result.get("invalidatedExchanges").asInt());
        assertEquals("INVALIDATED", exchangeStatus(affected));
        assertEquals(unrelatedBefore, row("exchange_requests", id(unaffected)));
        assertEquals("CONFIRMED", reservationStatus(right));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type='EXCHANGE_INVALIDATED' AND user_id=?", Integer.class, other.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type='EXCHANGE_INVALIDATED' AND user_id=?", Integer.class, departed.id()));
    }

    @Test
    void withdrawingCounterpartCreatorPreservesPendingTeamExchangeWhenCurrentLeaderCanApprove() throws Exception {
        Person departed = person("MEMBER"), leader = person("MEMBER"), requester = person("MEMBER");
        long group = team("REGULAR"); join(group, departed, false); join(group, leader, true);
        JsonNode source = teamReservation(departed, TODAY, "B07", group), offered = personal(requester, TODAY, "B08", "DRUMS");
        JsonNode pending = exchange(requester.client(), offered, source);
        String before = row("exchange_requests", id(pending));
        JsonNode result = success(withdraw(administrator.client(), departed, body(0), key()), 200);
        assertEquals(0, result.get("invalidatedExchanges").asInt());
        assertEquals(before, row("exchange_requests", id(pending)));
        JsonNode approved = success(exchangeAction(leader.client(), pending, "approve"), 200);
        assertEquals("COMPLETED", approved.get("status").asString());
        long successor = successorId(source);
        assertEquals(departed.id(), jdbc.queryForObject("SELECT creator_user_id FROM reservations WHERE id=?", Long.class, successor));
        assertEquals(group, jdbc.queryForObject("SELECT team_id FROM reservations WHERE id=?", Long.class, successor));
    }

    @Test
    void departingOptionalApproverDoesNotInvalidateButDepartingTeamRequesterDoes() throws Exception {
        Person leader = person("MEMBER"), creator = person("MEMBER"), requester = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, false); join(group, leader, true);
        JsonNode teamBooking = teamReservation(creator, TODAY, "B07", group), personalBooking = personal(requester, TODAY, "B08", "DRUMS");
        JsonNode incoming = exchange(requester.client(), personalBooking, teamBooking);
        success(withdraw(administrator.client(), leader, body(0), key()), 200);
        assertEquals("PENDING", exchangeStatus(incoming));
        success(exchangeAction(creator.client(), incoming, "approve"), 200);
        JsonNode teamCurrent = success(administrator.client().get("/api/v1/reservations/" + successorId(teamBooking)), 200);
        JsonNode other = personal(person("MEMBER"), TODAY, "B09", "VOCAL");
        JsonNode outgoing = exchange(creator.client(), teamCurrent, other);
        JsonNode result = success(withdraw(administrator.client(), creator, body(0), key()), 200);
        assertEquals(1, result.get("invalidatedExchanges").asInt());
        assertEquals("INVALIDATED", exchangeStatus(outgoing));
        assertEquals("CONFIRMED", reservationStatus(teamCurrent));
    }

    @Test
    void disbandRegularAndAdditionalTeamsPreservesCompletedHistorySongsMembersAndTheirOtherTeams() throws Exception {
        for (String kind : List.of("REGULAR", "ADDITIONAL")) {
            Person leader = person("MEMBER");
            long group = team(kind), surviving = team("REGULAR");
            join(group, leader, true); join(surviving, leader, false);
            LocalDate date = "REGULAR".equals(kind) ? TODAY : TODAY.plusDays(1);
            clock.set(date + "T11:00:00+09:00");
            JsonNode completed = teamReservation(leader, date, "B01", group);
            JsonNode ongoing = teamReservation(leader, date, "B03", group);
            JsonNode future = teamReservation(leader, date, "B07", group);
            JsonNode survivingBooking = teamReservation(leader, date, "B08", surviving);
            jdbc.update("INSERT INTO team_songs(team_id,title,sort_order,created_by,updated_by) VALUES (?,'합성 이력 곡',0,?,?)", group, leader.id(), leader.id());
            String completedBefore = row("reservations", id(completed)), songsBefore = filteredTable("team_songs", "team_id=" + group);
            clock.set(date + "T14:30:00+09:00");
            JsonNode result = success(disband(administrator.client(), group, body(0), key()), 200);
            assertEquals("DISBANDED", result.get("status").asString());
            assertEquals(Long.toString(group), result.get("targetId").asString());
            assertEquals(2, result.get("canceledReservations").asInt());
            assertEquals("DISBANDED", teamStatus(group));
            assertNotNull(jdbc.queryForObject("SELECT disbanded_at FROM teams WHERE id=?", Timestamp.class, group));
            assertEquals("CANCELED", reservationStatus(ongoing)); assertEquals("CANCELED", reservationStatus(future));
            assertEquals(0, claims(ongoing)); assertEquals(0, claims(future));
            assertEquals(completedBefore, row("reservations", id(completed)));
            assertEquals(songsBefore, filteredTable("team_songs", "team_id=" + group));
            assertEquals(120, quota(null, group, date));
            assertEquals("CONFIRMED", reservationStatus(survivingBooking));
            assertEquals("ACTIVE", memberStatus(leader));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE team_id=? AND status='ACTIVE'", Integer.class, group));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE team_id=? AND status='ACTIVE'", Integer.class, surviving));
            assertFalse(sessions(leader).isEmpty());
        }
    }

    @Test
    void disbandReplayHasStableCountsAndProtectsReasonVersionAndTerminalStatus() throws Exception {
        long group = team("REGULAR");
        String requestKey = key();
        Map<String, Object> input = Map.of("expectedVersion", 0, "reason", "합성 해체 사유");
        JsonNode result = success(disband(administrator.client(), group, input, requestKey), 200);
        Map<String, String> before = snapshots();
        assertEquals(result, success(disband(administrator.client(), group, input, requestKey), 200));
        assertError(disband(administrator.client(), group, body(0), requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(disband(administrator.client(), group, body(1), key()), 409, "TEAM_ALREADY_DISBANDED");
        assertEquals(before, snapshots());
    }

    @Test
    void disbandCancelsChangedAndExchangedSuccessorsWithoutTouchingTheirHistoricalSourcesOrCounterpartOwner() throws Exception {
        Person creator = person("MEMBER"), counterpart = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, true);
        JsonNode original = teamReservation(creator, TODAY, "B07", group);
        JsonNode changed = changed(creator.client(), original, TODAY, "B08", null);
        JsonNode personal = personal(counterpart, TODAY, "B09", "DRUMS");
        success(exchangeAction(counterpart.client(), exchange(creator.client(), changed, personal), "approve"), 200);
        JsonNode teamSuccessor = success(administrator.client().get("/api/v1/reservations/" + successorId(changed)), 200);
        JsonNode personalSuccessor = success(administrator.client().get("/api/v1/reservations/" + successorId(personal)), 200);
        String originalHistory = row("reservations", id(original)), changedHistory = row("reservations", id(changed));
        String otherBefore = row("reservations", id(personalSuccessor));
        JsonNode result = success(disband(administrator.client(), group, body(0), key()), 200);
        assertEquals(1, result.get("canceledReservations").asInt());
        assertEquals("CANCELED", reservationStatus(teamSuccessor)); assertEquals(0, claims(teamSuccessor));
        assertEquals(originalHistory, row("reservations", id(original))); assertEquals(changedHistory, row("reservations", id(changed)));
        assertEquals(otherBefore, row("reservations", id(personalSuccessor))); assertEquals(1, claims(personalSuccessor));
        assertEquals(3, count("reservation_lineage"));
    }

    @Test
    void disbandEndsAllRecurringAssignmentsIncludingMovedOccurrencesAndOpeningCannotResurrectThem() throws Exception {
        Person creator = person("MEMBER"); long group = team("REGULAR"); join(group, creator, true);
        JsonNode series = assignment(group, "B07"), another = assignment(group, "B08");
        JsonNode fixed = fixed(group, TODAY, "B07");
        JsonNode moved = changed(administrator.client(), fixed, TODAY.plusDays(1), "B07", null);
        JsonNode result = success(disband(administrator.client(), group, body(0), key()), 200);
        assertEquals(2, result.get("endedAssignments").asInt());
        assertEquals(6, result.get("canceledReservations").asInt());
        assertEquals("CANCELED", reservationStatus(moved));
        for (JsonNode current : List.of(series, another)) {
            assertEquals("ENDED", jdbc.queryForObject("SELECT status FROM recurring_assignments WHERE id=?", String.class, id(current)));
            assertEquals(TODAY.minusDays(1), jdbc.queryForObject("SELECT end_date FROM recurring_assignments WHERE id=?", LocalDate.class, id(current)));
        }
        long countBefore = count("reservations");
        jdbc.update("UPDATE booking_days SET status='PREPARING',opened_at=NULL,recurring_prepared=false WHERE reservation_date>=?", TODAY);
        opening.openBookableWindow(); reminders.recoverFuturePlans();
        assertEquals(countBefore, count("reservations"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE team_id=? AND status='CONFIRMED'", Integer.class, group));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE team_id=? AND status IN ('PENDING','PROCESSING','RETRY_WAIT')", Integer.class, group));
        clock.set(TODAY + "T17:00:00+09:00"); assertEquals(0, reminders.drain(50));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type='START_REMINDER'", Integer.class));
    }

    @Test
    void disbandInvalidatesOnlyItsPendingExchangesAndCancelsAlreadyLeasedPushJobsWithoutDeletingInbox() throws Exception {
        Person creator = person("MEMBER"), counterpart = person("MEMBER"), unrelated = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, true); subscription(creator, "team-device");
        JsonNode source = teamReservation(creator, TODAY, "B07", group), other = personal(counterpart, TODAY, "B08", "DRUMS");
        JsonNode pending = exchange(creator.client(), source, other);
        JsonNode left = personal(unrelated, TODAY, "B09", "BASS"), right = personal(counterpart, TODAY, "B10", "VOCAL");
        JsonNode unrelatedPending = exchange(unrelated.client(), left, right);
        List<Long> oldInbox = jdbc.queryForList("SELECT id FROM notifications ORDER BY id", Long.class);
        jdbc.update("UPDATE notification_jobs SET status='PROCESSING',lease_token=?::uuid,lease_expires_at=? WHERE subscription_id IS NOT NULL", key(), Timestamp.from(clock.instant().plusSeconds(45)));
        String unrelatedBefore = row("exchange_requests", id(unrelatedPending));
        JsonNode result = success(disband(administrator.client(), group, body(0), key()), 200);
        assertEquals(1, result.get("invalidatedExchanges").asInt()); assertEquals("INVALIDATED", exchangeStatus(pending));
        assertEquals(unrelatedBefore, row("exchange_requests", id(unrelatedPending)));
        assertTrue(jdbc.queryForList("SELECT id FROM notifications ORDER BY id", Long.class).containsAll(oldInbox));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_jobs j JOIN notifications n ON n.id=j.notification_id WHERE (n.reservation_id=? OR (n.exchange_request_id=? AND n.event_type='EXCHANGE_REQUESTED')) AND j.status IN ('PENDING','PROCESSING','RETRY_WAIT')", Integer.class, id(source), id(pending)));
        assertEquals("CONFIRMED", reservationStatus(other));
        assertTrue(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE user_id=?", Boolean.class, creator.id()));
        assertFalse(sessions(creator).isEmpty());
    }

    @Test
    void failureAtFinalLifecycleInsertRollsBackWithdrawalReservationsMembershipsSessionsDevicesInboxAndJobs() throws Exception {
        Person target = person("MEMBER"); long group = team("REGULAR"); join(group, target, true);
        subscription(target, "rollback"); personal(target, TODAY, "B07", "DRUMS");
        Set<String> sessionsBefore = sessions(target);
        Map<String, String> before = snapshots();
        failFinalInsert(); String requestKey = key();
        assertError(withdraw(administrator.client(), target, body(0), requestKey), 409, "DATA_CONFLICT");
        assertEquals(before, snapshots()); assertEquals(sessionsBefore, sessions(target));
        removeFailure();
        success(withdraw(administrator.client(), target, body(0), requestKey), 200);
        assertEquals(1, count("lifecycle_operations")); assertEquals("WITHDRAWN", memberStatus(target));
    }

    @Test
    void failureAtFinalLifecycleInsertRollsBackDisbandAssignmentsOccurrencesAndAllBookingCleanup() throws Exception {
        Person creator = person("MEMBER"); long group = team("REGULAR"); join(group, creator, true);
        assignment(group, "B07"); subscription(creator, "rollback-team");
        Map<String, String> before = snapshots();
        failFinalInsert(); String requestKey = key();
        assertError(disband(administrator.client(), group, body(0), requestKey), 409, "DATA_CONFLICT");
        assertEquals(before, snapshots());
        removeFailure(); success(disband(administrator.client(), group, body(0), requestKey), 200);
        assertEquals(1, count("lifecycle_operations")); assertEquals("DISBANDED", teamStatus(group));
    }

    @Test
    void actualDirectoryGuardTimeoutLeavesNoPartialWithdrawalAndTheSameKeyCanRetry() throws Exception {
        Person target = person("MEMBER"); personal(target, TODAY, "B07", "DRUMS");
        JsonNode csrf = administrator.client().csrf(); String requestKey = key();
        Map<String, String> before = snapshots(); Set<String> targetSessions = sessions(target);
        HttpResponse<String> response; long elapsed;
        try (Connection lock = lockGuard()) {
            long started = System.nanoTime();
            response = administrator.client().send("POST", withdrawalPath(target), body(0), csrf, requestKey);
            elapsed = System.nanoTime() - started;
            lock.rollback();
        }
        assertError(response, 503, "LIFECYCLE_TEMPORARILY_UNAVAILABLE");
        assertTrue(Duration.ofNanos(elapsed).compareTo(Duration.ofSeconds(8)) >= 0);
        assertTrue(Duration.ofNanos(elapsed).compareTo(Duration.ofSeconds(20)) < 0);
        assertFalse(response.body().contains("directory_mutation_guard")); assertFalse(response.body().contains("SELECT"));
        assertEquals(before, snapshots()); assertEquals(targetSessions, sessions(target));
        success(withdraw(administrator.client(), target, body(0), requestKey), 200);
        assertEquals(1, count("lifecycle_operations"));
    }

    @Test
    void concurrentSameKeyWithdrawalAndDisbandEachCommitOneOperationAndOneCleanup() throws Exception {
        Person target = person("MEMBER"); personal(target, TODAY, "B07", "DRUMS");
        Client otherAdmin = authenticated(administrator.member());
        JsonNode firstCsrf = administrator.client().csrf(), secondCsrf = otherAdmin.csrf(); String requestKey = key();
        List<HttpResponse<String>> responses = race(
                () -> administrator.client().send("POST", withdrawalPath(target), body(0), firstCsrf, requestKey),
                () -> otherAdmin.send("POST", withdrawalPath(target), body(0), secondCsrf, requestKey));
        assertEquals(List.of(200, 200), statuses(responses));
        assertEquals(success(responses.getFirst(), 200), success(responses.getLast(), 200));
        assertEquals(1, count("lifecycle_operations"));
        long group = team("REGULAR"); String disbandKey = key();
        responses = race(
                () -> administrator.client().send("POST", disbandPath(group), body(0), firstCsrf, disbandKey),
                () -> otherAdmin.send("POST", disbandPath(group), body(0), secondCsrf, disbandKey));
        assertEquals(List.of(200, 200), statuses(responses)); assertEquals(2, count("lifecycle_operations"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind='USER_WITHDRAW'", Integer.class));
    }

    @Test
    void withdrawalCommittingAheadOfPreviouslyAuthenticatedChangeRejectsLateMutationWithoutSuccessor() throws Exception {
        Person target = person("MEMBER"); JsonNode source = personal(target, TODAY, "B07", "DRUMS");
        JsonNode adminCsrf = administrator.client().csrf(), ownerCsrf = target.client().csrf();
        try (Connection lock = lockGuard(); var executor = Executors.newFixedThreadPool(2)) {
            var withdraw = executor.submit(() -> administrator.client().send("POST", withdrawalPath(target), body(0), adminCsrf, key()));
            awaitWaiters(1);
            var change = executor.submit(() -> target.client().send("PATCH", reservationPath(source), Map.of("expectedVersion", 0, "date", TODAY.toString(), "blockCode", "B08", "instrumentCodes", List.of("DRUMS")), ownerCsrf, key()));
            awaitWaiters(2); lock.commit();
            success(withdraw.get(25, TimeUnit.SECONDS), 200);
            assertError(change.get(25, TimeUnit.SECONDS), 403, "INACTIVE_USER");
        }
        assertEquals(1, count("reservations")); assertEquals(0, count("reservation_lineage"));
        assertEquals("CANCELED", reservationStatus(source)); assertEquals(0, quota(target, null, TODAY));
    }

    @Test
    void queuedReminderAfterWithdrawalCannotCreateNewInboxOrRestoreCanceledPlan() throws Exception {
        Person target = person("MEMBER"); personal(target, TODAY, "B07", "DRUMS");
        String inbox = filteredTable("notifications", "user_id=" + target.id());
        clock.set(TODAY + "T17:00:00+09:00"); JsonNode csrf = administrator.client().csrf();
        try (Connection lock = lockGuard(); var executor = Executors.newFixedThreadPool(2)) {
            var withdraw = executor.submit(() -> administrator.client().send("POST", withdrawalPath(target), body(0), csrf, key()));
            awaitWaiters(1);
            var worker = executor.submit(() -> reminders.drain(50));
            awaitWaiters(2); lock.commit();
            success(withdraw.get(25, TimeUnit.SECONDS), 200); assertEquals(0, worker.get(25, TimeUnit.SECONDS));
        }
        assertEquals(inbox, filteredTable("notifications", "user_id=" + target.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE status IN ('PENDING','PROCESSING','RETRY_WAIT')", Integer.class));
        reminders.recoverFuturePlans(); assertEquals(0, reminders.drain(50));
    }

    @Test
    void passwordVerifiedBeforeWithdrawalCannotPublishNewAuthenticatedSessionAfterClosure() throws Exception {
        Person target = person("MEMBER");
        Client lateLogin = new Client();
        JsonNode loginCsrf = lateLogin.csrf(), adminCsrf = administrator.client().csrf();
        try (Connection lock = lockGuard(); var executor = Executors.newFixedThreadPool(2)) {
            var withdraw = executor.submit(() -> administrator.client().send("POST", withdrawalPath(target), body(0), adminCsrf, key()));
            awaitWaiters(1);
            var login = executor.submit(() -> lateLogin.send("POST", "/api/v1/auth/login", Map.of("loginId", target.member().loginId(), "password", password), loginCsrf, null));
            awaitWaiters(2); lock.commit();
            success(withdraw.get(25, TimeUnit.SECONDS), 200);
            assertError(login.get(25, TimeUnit.SECONDS), 403, "INACTIVE_USER");
        }
        assertEquals(Set.of(), sessions(target));
        assertError(lateLogin.get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void subscriptionRegistrationAuthenticatedBeforeWithdrawalCannotReactivateAnyDeviceAfterClosure() throws Exception {
        Person target = person("MEMBER"); subscription(target, "stale-binding");
        JsonNode ownerCsrf = target.client().csrf(), adminCsrf = administrator.client().csrf();
        // These are a fixed public P-256 generator and zero-valued synthetic auth bytes, never a real subscription.
        String publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(
                "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"));
        Map<String, Object> input = Map.of("endpoint", "https://fcm.googleapis.com/wpush/v2/synthetic-late-registration",
                "keys", Map.of("p256dh", publicKey, "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16])), "deviceName", "합성 늦은 등록");
        try (Connection lock = lockGuard(); var executor = Executors.newFixedThreadPool(2)) {
            var withdraw = executor.submit(() -> administrator.client().send("POST", withdrawalPath(target), body(0), adminCsrf, key()));
            awaitWaiters(1);
            var register = executor.submit(() -> target.client().send("POST", "/api/v1/push-subscriptions", input, ownerCsrf, null));
            awaitWaiters(2); lock.commit();
            success(withdraw.get(25, TimeUnit.SECONDS), 200);
            assertError(register.get(25, TimeUnit.SECONDS), 403, "INACTIVE_USER");
        }
        assertEquals(1, count("push_subscriptions"));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE active", Integer.class));
        assertEquals(Set.of(), sessions(target));
    }

    @Test
    void concurrentAdministratorSelfWithdrawalsPreserveOneActiveAdministratorAndRevokeOnlySuccessfulActor() throws Exception {
        Person second = person("GLOBAL_ADMIN");
        JsonNode firstCsrf = administrator.client().csrf(), secondCsrf = second.client().csrf();
        List<HttpResponse<String>> responses = race(
                () -> administrator.client().send("POST", withdrawalPath(administrator), body(0), firstCsrf, key()),
                () -> second.client().send("POST", withdrawalPath(second), body(0), secondCsrf, key()));
        assertEquals(List.of(200, 409), statuses(responses));
        HttpResponse<String> rejected = responses.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow();
        assertError(rejected, 409, "LAST_GLOBAL_ADMIN");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role='GLOBAL_ADMIN' AND status='ACTIVE'", Integer.class));
        assertEquals(1, count("lifecycle_operations"));
        Person withdrawn = "WITHDRAWN".equals(memberStatus(administrator)) ? administrator : second;
        Person surviving = withdrawn == administrator ? second : administrator;
        assertEquals(Set.of(), sessions(withdrawn));
        assertFalse(sessions(surviving).isEmpty());
        assertError(withdrawn.client().get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        success(surviving.client().get("/api/v1/users"), 200);
    }

    @Test
    void disbandedTeamRemainsReadOnlyForAdministratorAndCannotBeRenamedRejoinedOrUsedForNewBookings() throws Exception {
        Person member = person("MEMBER"); long group = team("REGULAR"); join(group, member, true);
        jdbc.update("INSERT INTO team_songs(team_id,title,sort_order,created_by,updated_by) VALUES (?,'합성 보존 곡',0,?,?)", group, member.id(), member.id());
        success(disband(administrator.client(), group, body(0), key()), 200);
        JsonNode detail = success(administrator.client().get("/api/v1/teams/" + group), 200);
        assertEquals("DISBANDED", detail.get("team").get("status").asString());
        assertEquals(0, detail.get("members").size()); assertEquals(1, detail.get("songs").size());
        assertFalse(detail.get("canManageTeam").asBoolean()); assertFalse(detail.get("canManageSongs").asBoolean());
        Map<String, String> before = snapshots();
        assertError(administrator.client().write("PATCH", "/api/v1/teams/" + group, Map.of("name", "합성 변경 시도", "version", 1), null), 404, "RESOURCE_NOT_FOUND");
        assertError(administrator.client().write("POST", "/api/v1/teams/" + group + "/members", Map.of("rosterEntryId", Long.toString(member.rosterId())), null), 404, "RESOURCE_NOT_FOUND");
        assertError(member.client().get("/api/v1/teams/" + group), 404, "RESOURCE_NOT_FOUND");
        assertEquals(before, snapshots());
        assertNotEquals(201, teamReservationResponse(member, TODAY, "B07", group).statusCode());
        assertEquals(0, count("reservations"));
    }

    @Test
    void populatedV10UpgradePreservesAllExistingRowsAndAddsOnlyEmptyLifecycleHistoryAndNullableTeamTimestamp() throws Exception {
        String schema = "lifecycle_upgrade_" + key().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("10").load().migrate();
        Map<String, String> before;
        try (Connection connection = fixtureConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            statement.execute("INSERT INTO users(id,login_id,password_hash,display_name,global_role,status,created_at) VALUES (1,'synthetic-upgrade','synthetic-preserved-hash','합성 보존 회원','GLOBAL_ADMIN','ACTIVE','2026-10-30T02:00:00Z')");
            statement.execute("INSERT INTO teams(id,name,kind) VALUES (1,'합성 보존 팀','REGULAR')");
            statement.execute("INSERT INTO roster_entries(id,name,user_id) VALUES (1,'합성 보존 명단',1)");
            statement.execute("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (1,1,'LEADER')");
            statement.execute("INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES ('synthetic-primary','synthetic-session',1,1,7776000,7776000001,'1')");
            statement.execute("INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES ('synthetic-primary','synthetic.preserved',decode('01020304','hex'))");
            statement.execute("INSERT INTO push_subscriptions(user_id,endpoint,p256dh,auth,device_name,session_digest,created_at,updated_at) VALUES (1,'https://fcm.googleapis.com/wpush/v2/synthetic-upgrade','synthetic-key','synthetic-auth','합성 보존 기기','" + "a".repeat(64) + "','2026-10-30T02:00:00Z','2026-10-30T02:00:00Z')");
            statement.execute("INSERT INTO notifications(user_id,event_key,event_type,title,body,target_path,created_at) VALUES (1,'synthetic-existing','IMPORTANT_NOTICE','합성 보존 알림','합성 보존 본문','/notifications','2026-10-30T02:00:00Z')");
            statement.execute("INSERT INTO notification_jobs(job_kind,job_key,notification_id,subscription_id,subscription_binding_version,scheduled_at,next_attempt_at,created_at,updated_at) VALUES ('PUSH_DELIVERY','synthetic-job',1,1,0,'2026-10-30T02:00:00Z','2026-10-30T02:00:00Z','2026-10-30T02:00:00Z','2026-10-30T02:00:00Z')");
            before = schemaSnapshots(connection, schema);
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("11").load().migrate();
        try (Connection connection = fixtureConnection(); var statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
            Map<String, String> after = schemaSnapshots(connection, schema);
            assertEquals("[]", after.remove("lifecycle_operations"));
            assertEquals(before, after);
            try (var rows = statement.executeQuery("SELECT disbanded_at IS NULL FROM teams WHERE id=1")) {
                assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
            }
        }
    }

    private void failFinalInsert() {
        jdbc.execute("CREATE FUNCTION lifecycle_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic final lifecycle failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER lifecycle_test_failure BEFORE INSERT ON lifecycle_operations FOR EACH ROW EXECUTE FUNCTION lifecycle_test_failure()");
    }
    private Map<String, String> schemaSnapshots(Connection connection, String schema) throws Exception {
        List<String> tables = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name")) {
            statement.setString(1, schema); try (var rows = statement.executeQuery()) { while (rows.next()) { tables.add(rows.getString(1)); } }
        }
        Map<String, String> result = new HashMap<>();
        for (String table : tables) {
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(r) - 'disbanded_at' ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r")) {
                assertTrue(rows.next()); result.put(table, rows.getString(1));
            }
        }
        return result;
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (Connection lock = lockGuard(); var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown(); awaitWaiters(2); lock.commit();
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }
    private void awaitWaiters(int expected) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'", Integer.class) >= expected);
    }
    private Connection fixtureConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Connection lockGuard() throws Exception {
        Connection connection = fixtureConnection(); connection.setAutoCommit(false);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE")) { assertTrue(rows.next()); }
        return connection;
    }
    private List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private Map<String, Object> body(long version) { return Map.of("expectedVersion", version); }
    private HttpResponse<String> withdraw(Client client, Person person, Object body, String requestKey) throws Exception { return client.write("POST", withdrawalPath(person), body, requestKey); }
    private HttpResponse<String> disband(Client client, long group, Object body, String requestKey) throws Exception { return client.write("POST", disbandPath(group), body, requestKey); }
    private String withdrawalPath(Person person) { return "/api/v1/users/" + person.id() + "/withdraw"; }
    private String disbandPath(long group) { return "/api/v1/teams/" + group + "/disband"; }
    private String memberStatus(Person person) { return jdbc.queryForObject("SELECT status FROM users WHERE id=?", String.class, person.id()); }
    private String teamStatus(long group) { return jdbc.queryForObject("SELECT status FROM teams WHERE id=?", String.class, group); }
    private Set<String> sessions(Person person) { return Set.copyOf(jdbc.queryForList("SELECT session_id FROM spring_session WHERE principal_name=?", String.class, new MemberPrincipal(person.id()).toString())); }
    private long subscription(Person owner, String suffix) {
        return jdbc.queryForObject("INSERT INTO push_subscriptions(user_id,endpoint,p256dh,auth,device_name,session_digest,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?) RETURNING id", Long.class,
                owner.id(), "https://fcm.googleapis.com/wpush/v2/synthetic-" + suffix, "synthetic-key", "synthetic-auth", "합성 기기", "a".repeat(64), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
    }
    private JsonNode personal(Person person, LocalDate date, String block, String instrument) throws Exception {
        return success(person.client().write("POST", "/api/v1/reservations", personalBody(date, block, instrument), key()), 201);
    }
    private Map<String, Object> personalBody(LocalDate date, String block, String instrument) { return Map.of("subjectType", "PERSONAL", "date", date.toString(), "blockCode", block, "instrumentCodes", List.of(instrument)); }
    private HttpResponse<String> teamReservationResponse(Person person, LocalDate date, String block, long group) throws Exception {
        return person.client().write("POST", "/api/v1/reservations", Map.of("subjectType", "TEAM", "date", date.toString(), "blockCode", block, "teamId", Long.toString(group)), key());
    }
    private JsonNode teamReservation(Person person, LocalDate date, String block, long group) throws Exception { return success(teamReservationResponse(person, date, block, group), 201); }
    private HttpResponse<String> change(Client client, JsonNode source, LocalDate date, String block, List<String> instruments) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("expectedVersion", source.get("version").asLong(), "date", date.toString(), "blockCode", block));
        if (instruments != null) { body.put("instrumentCodes", instruments); }
        return client.write("PATCH", reservationPath(source), body, key());
    }
    private JsonNode changed(Client client, JsonNode source, LocalDate date, String block, List<String> instruments) throws Exception { return success(change(client, source, date, block, instruments), 200).get("reservations").get(0); }
    private JsonNode exchange(Client client, JsonNode offered, JsonNode requested) throws Exception {
        return success(client.write("POST", "/api/v1/exchange-requests", Map.of("offered", List.of(exchangeItem(offered)), "requested", List.of(exchangeItem(requested))), key()), 201);
    }
    private Map<String, Object> exchangeItem(JsonNode reservation) { return Map.of("reservationId", reservation.get("id").asString(), "expectedVersion", reservation.get("version").asLong()); }
    private HttpResponse<String> exchangeAction(Client client, JsonNode request, String action) throws Exception { return client.write("POST", "/api/v1/exchange-requests/" + id(request) + "/" + action, body(request.get("version").asLong()), key()); }
    private String exchangeStatus(JsonNode request) { return jdbc.queryForObject("SELECT status FROM exchange_requests WHERE id=?", String.class, id(request)); }
    private long successorId(JsonNode reservation) { return jdbc.queryForObject("SELECT successor_reservation_id FROM reservation_lineage WHERE source_reservation_id=?", Long.class, id(reservation)); }
    private JsonNode assignment(long group, String block) throws Exception {
        return success(administrator.client().write("POST", "/api/v1/recurring-assignments", Map.of("teamId", Long.toString(group), "weekday", TODAY.getDayOfWeek().getValue(), "blockCode", block, "effectiveDate", TODAY.toString()), key()), 201);
    }
    private JsonNode fixed(long group, LocalDate date, String block) throws Exception {
        long reservation = jdbc.queryForObject("SELECT id FROM reservations WHERE team_id=? AND reservation_date=? AND time_block_code=? AND status='CONFIRMED'", Long.class, group, date, block);
        return success(administrator.client().get("/api/v1/reservations/" + reservation), 200);
    }
    private String reservationStatus(JsonNode reservation) { return jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id(reservation)); }
    private String reservationPath(JsonNode reservation) { return "/api/v1/reservations/" + id(reservation); }
    private long claims(JsonNode reservation) { return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_id=? AND released_at IS NULL", Long.class, id(reservation)); }
    private int quota(Person person, Long group, LocalDate date) { return jdbc.queryForObject("SELECT COALESCE(sum(amount_minutes),0)::integer FROM quota_entries WHERE quota_date=? AND " + (person == null ? "team_id" : "owner_user_id") + "=?", Integer.class, date, person == null ? group : person.id()); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private String table(String table) { return filteredTable(table, "true"); }
    private String row(String table, long id) { return filteredTable(table, "id=" + id); }
    private String filteredTable(String table, String predicate) { return jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r WHERE " + predicate, String.class); }
    private Map<String, String> snapshots() { Map<String, String> result = new HashMap<>(); for (String table : BUSINESS_TABLES) { result.put(table, table(table)); } return result; }
    private Person person(String role) throws Exception {
        Member member = members.insert("lifecycle-" + key().substring(0, 16), passwords.encode(password), "합성 정리 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 정리 명단',?) RETURNING id", Long.class, member.id());
        return new Person(member, roster, authenticated(member));
    }
    private Client authenticated(Member member) throws Exception {
        Client client = new Client(); success(client.write("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password), null), 200); return client;
    }
    private long team(String kind) { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 정리 조',?) RETURNING id", Long.class, kind); }
    private void join(long group, Person person, boolean leader) { jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,?)", group, person.rosterId(), leader ? "LEADER" : "MEMBER"); }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); safe(response);
        return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response, int status, String code) { assertEquals(status, response.statusCode(), () -> safeFailure(response)); assertEquals(code, json.readTree(response.body()).at("/error/code").asString()); safe(response); }
    private String safeFailure(HttpResponse<String> response) { JsonNode error = json.readTree(response.body()).get("error"); return error == null ? "Unexpected status without API error" : error.path("code").asString(); }
    private void safe(HttpResponse<String> response) { assertFalse(response.body().contains(password)); for (String field : List.of("passwordHash", "password_hash", "key_digest", "request_fingerprint", "session_digest", "p256dh", "synthetic-auth")) { assertFalse(response.body().contains(field)); } }
    private long id(JsonNode node) { return Long.parseLong(node.get("id").asString()); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Person(Member member, long rosterId, Client client) { long id() { return member.id(); } }
    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"), 200); }
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
    static class TestTime { @Bean @Primary TestClock lifecycleTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z"));
        void set(String value) { now.set(java.time.OffsetDateTime.parse(value).toInstant()); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
