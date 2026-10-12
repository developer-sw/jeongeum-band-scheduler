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
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
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

/** Every fixture, failure injection and direct JDBC connection targets only this disposable PostgreSQL. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jeongeum.recurring.worker.enabled=false", "jeongeum.exchange.worker.enabled=false"})
@Import(ExchangeIntegrationTest.TestTime.class)
@Timeout(90)
class ExchangeIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final Set<String> INSTRUMENTS = Set.of("VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");
    private static final String[] BOOKING_TABLES = {"reservations", "reservation_assets", "quota_entries", "reservation_operations",
            "reservation_lineage", "idempotency_requests", "recurring_assignments", "recurring_assignment_revisions",
            "recurring_occurrences", "recurring_occurrence_reservations", "recurring_idempotency_requests"};
    private static final String[] EXCHANGE_TABLES = {"exchange_requests", "exchange_request_items", "exchange_instrument_allocations", "exchange_idempotency_requests"};

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("exchange_test").withUsername("exchange_test")
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
    @Autowired ExchangeService exchanges;
    private String password;
    private Person administrator;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL',version=0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
        opening.prepareRange(TODAY, TODAY.plusDays(14));
    }

    @Test
    void oneForOnePersonalExchangesDifferentDurationsWithoutChangingOwnersCreatorsOrInstruments() throws Exception {
        for (int i = 0; i < 3; i++) {
            LocalDate date = TODAY.plusDays(i);
            Person a = person("MEMBER"), b = person("MEMBER");
            String first = i == 2 ? "B07" : "B01", second = i == 0 ? "B02" : "B08";
            JsonNode left = personal(a, date, first, "DRUMS"), right = personal(b, date, second, "VOCAL");
            JsonNode request = request(a.client(), List.of(left), List.of(right));
            JsonNode result = approve(b.client(), request);
            assertEquals("COMPLETED", result.get("status").asString());
            assertEquals(Set.of(left.get("id").asString(), right.get("id").asString()), strings(result.get("replacedReservationIds")));
            assertEquals(2, result.get("reservations").size());
            JsonNode aNext = successor(left), bNext = successor(right);
            assertSuccessor(left, aNext, date, second, Set.of("DRUMS"));
            assertSuccessor(right, bNext, date, first, Set.of("VOCAL"));
            assertEquals(TimeBlock.require(second).durationMinutes() == 60 ? 60 : 0, quota(a, null, date));
            assertEquals(TimeBlock.require(first).durationMinutes() == 60 ? 60 : 0, quota(b, null, date));
            assertEquals(0, claims(id(left))); assertEquals(0, claims(id(right)));
        }
        assertEquals(3, exchangeCount()); assertEquals(6, count("reservation_lineage"));
    }

    @Test
    void regularAndAdditionalTeamsExchangeRoomExclusivityAndPreserveHistoricalCreatorWhenAdministratorApproves() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        long regular = team("REGULAR"), additional = team("ADDITIONAL");
        join(regular, a, false); join(additional, b, false);
        JsonNode left = teamReservation(a, TODAY, "B01", regular), right = teamReservation(b, TODAY.plusDays(1), "B07", additional);
        JsonNode result = approve(administrator.client(), request(a.client(), List.of(left), List.of(right)));
        assertEquals("COMPLETED", result.get("status").asString());
        assertSuccessor(left, successor(left), TODAY.plusDays(1), "B07", INSTRUMENTS);
        assertSuccessor(right, successor(right), TODAY, "B01", INSTRUMENTS);
        assertEquals("REGULAR", successor(left).get("purpose").asString());
        assertEquals("ADDITIONAL", successor(right).get("purpose").asString());
        assertEquals(6, claims(id(successor(left)))); assertEquals(6, claims(id(successor(right))));
        assertEquals(administrator.member().id(), jdbc.queryForObject("SELECT actor_user_id FROM reservation_operations WHERE kind='EXCHANGE'", Long.class));
    }

    @Test
    void pendingRequestCreatesNoClaimsQuotaOperationsLineageOrSourceChangesAndLimitsUnrelatedReads() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), stranger = person("MEMBER");
        JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        Map<String, String> before = bookingSnapshots();
        JsonNode pending = request(a.client(), List.of(left), List.of(right));
        assertEquals("PENDING", pending.get("status").asString());
        assertEquals(before, bookingSnapshots());
        assertEquals(2, count("exchange_request_items"));
        JsonNode mine = success(a.client().get("/api/v1/exchange-requests"), 200);
        assertTrue(mine.has("serverTime")); assertEquals(Set.of(pending.get("id").asString()), strings(mine.get("requests"), "id"));
        assertEquals(0, success(stranger.client().get("/api/v1/exchange-requests"), 200).get("requests").size());
        assertError(stranger.client().get(exchangePath(pending)), 404, "RESOURCE_NOT_FOUND");
        assertEquals("PENDING", success(b.client().get(exchangePath(pending)), 200).get("status").asString());
    }

    @Test
    void differentInstrumentHourlyBundleMergesIntoOneDualNinetyWithOwnerOnlyLineage() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode first = personal(a, TODAY, "B01", "DRUMS"), second = personal(a, TODAY, "B02", "VOCAL");
        JsonNode ninety = personal(b, TODAY.plusDays(1), "B07", "BASS");
        JsonNode result = approve(b.client(), request(a.client(), List.of(second, first), List.of(ninety)));
        assertEquals(3, result.get("reservations").size());
        JsonNode merged = successor(first);
        assertEquals(id(merged), id(successor(second)));
        assertSuccessor(first, merged, TODAY.plusDays(1), "B07", Set.of("DRUMS", "VOCAL"));
        assertTrue(merged.get("dualAssetException").asBoolean()); assertEquals(2, claims(id(merged)));
        assertEquals(2, successors(ninety).size());
        for (JsonNode split : successors(ninety)) { assertEquals(Set.of("BASS"), strings(split.get("instrumentCodes"))); assertEquals(1, claims(id(split))); }
        assertEquals(0, quota(a, null, TODAY)); assertEquals(120, quota(b, null, TODAY));
        assertEquals(4, count("reservation_lineage")); assertEquals(1, exchangeCount());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reservation_lineage l JOIN reservations s ON s.id=l.source_reservation_id "
                + "JOIN reservations t ON t.id=l.successor_reservation_id WHERE s.owner_user_id IS DISTINCT FROM t.owner_user_id "
                + "OR s.team_id IS DISTINCT FROM t.team_id OR s.creator_user_id<>t.creator_user_id", Integer.class));
    }

    @Test
    void sameInstrumentBundleMergesOneClaimAndSingleInstrumentNinetySplitsSameInstrumentOnBothHours() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode first = personal(a, TODAY, "B01", "DRUMS"), second = personal(a, TODAY, "B02", "DRUMS"), ninety = personal(b, TODAY, "B07", "VOCAL");
        approve(b.client(), request(a.client(), List.of(first, second), List.of(ninety)));
        assertEquals(Set.of("DRUMS"), strings(successor(first).get("instrumentCodes"))); assertEquals(1, claims(id(successor(first))));
        assertFalse(successor(first).get("dualAssetException").asBoolean());
        assertEquals(Set.of("B01", "B02"), successors(ninety).stream().map(r -> r.get("blockCode").asString()).collect(java.util.stream.Collectors.toSet()));
        for (JsonNode split : successors(ninety)) { assertEquals(Set.of("VOCAL"), strings(split.get("instrumentCodes"))); assertFalse(split.get("dualAssetException").asBoolean()); }
    }

    @Test
    void dualNinetySplitsOnlyAfterPersonalOwnerStoresExplicitInstrumentOrderAndAdministratorCannotChooseIt() throws Exception {
        Dual dual = dual();
        Person other = person("MEMBER");
        JsonNode one = personal(other, TODAY.plusDays(2), "B03", "BASS"), two = personal(other, TODAY.plusDays(2), "B04", "BASS");
        JsonNode request = request(other.client(), List.of(two, one), List.of(dual.reservation()));
        Map<String, String> before = bookingSnapshots();
        assertError(action(administrator.client(), request, "approve", key()), 409, "EXCHANGE_ALLOCATION_REQUIRED");
        assertEquals(before, bookingSnapshots()); assertEquals("PENDING", state(request));
        assertError(allocate(administrator.client(), request, dual.reservation(), List.of("VOCAL", "DRUMS"), key()), 403, "FORBIDDEN");
        assertError(allocate(other.client(), request, dual.reservation(), List.of("VOCAL", "DRUMS"), key()), 403, "FORBIDDEN");
        for (List<String> invalid : List.of(List.of("DRUMS"), List.of("DRUMS", "DRUMS"), List.of("BASS", "DRUMS"), List.of("DRUMS", "VOCAL", "BASS"))) {
            assertEquals(400, allocate(dual.owner().client(), request, dual.reservation(), invalid, key()).statusCode());
        }
        JsonNode configured = success(allocate(dual.owner().client(), request, dual.reservation(), List.of("VOCAL", "DRUMS"), key()), 200);
        assertEquals(request.get("version").asLong() + 1, configured.get("version").asLong());
        assertError(action(administrator.client(), request, "approve", key()), 409, "VERSION_CONFLICT");
        approve(administrator.client(), configured);
        List<JsonNode> split = successors(dual.reservation());
        assertEquals(2, split.size());
        assertEquals(Set.of("VOCAL"), strings(split.get(0).get("instrumentCodes"))); assertEquals("B03", split.get(0).get("blockCode").asString());
        assertEquals(Set.of("DRUMS"), strings(split.get(1).get("instrumentCodes"))); assertEquals("B04", split.get(1).get("blockCode").asString());
        for (JsonNode reservation : split) { assertEquals(60, reservation.get("quotaMinutes").asInt()); assertFalse(reservation.get("dualAssetException").asBoolean()); }
        assertEquals(120, quota(dual.owner(), null, TODAY.plusDays(2)));
    }

    @Test
    void dualNinetyCannotBeSilentlyReducedInOneForOneSixtyExchangeButKeepsBothInNinetyExchange() throws Exception {
        Dual dual = dual(); Person other = person("MEMBER");
        JsonNode hour = personal(other, TODAY.plusDays(2), "B03", "BASS");
        assertError(createExchange(dual.owner().client(), List.of(dual.reservation()), List.of(hour), key()), 400, "INVALID_EXCHANGE_SHAPE");
        JsonNode ninety = personal(other, TODAY.plusDays(2), "B08", "BASS");
        approve(other.client(), request(dual.owner().client(), List.of(dual.reservation()), List.of(ninety)));
        JsonNode next = successor(dual.reservation());
        assertSuccessor(dual.reservation(), next, TODAY.plusDays(2), "B08", Set.of("VOCAL", "DRUMS"));
        assertTrue(next.get("dualAssetException").asBoolean()); assertEquals(2, claims(id(next)));
    }

    @Test
    void rejectsNonAdjacentDifferentDayDifferentOwnerAndTwoForTwoBundlesWithoutAnyExchangeWrite() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), c = person("MEMBER");
        JsonNode first = personal(a, TODAY, "B01", "DRUMS"), gap = personal(a, TODAY, "B03", "VOCAL");
        JsonNode otherDay = personal(a, TODAY.plusDays(1), "B02", "VOCAL"), foreign = personal(c, TODAY, "B02", "BASS");
        JsonNode ninety = personal(b, TODAY, "B07", "KEYBOARD"), hour1 = personal(b, TODAY.plusDays(1), "B03", "KEYBOARD"), hour2 = personal(b, TODAY.plusDays(1), "B04", "KEYBOARD");
        for (List<JsonNode> invalid : List.of(List.of(first, gap), List.of(first, otherDay), List.of(first, foreign))) {
            assertError(createExchange(administrator.client(), invalid, List.of(ninety), key()), 400, "INVALID_EXCHANGE_SHAPE");
        }
        assertError(createExchange(administrator.client(), List.of(first, gap), List.of(hour1, hour2), key()), 400, "INVALID_EXCHANGE_SHAPE");
        assertEquals(0, count("exchange_requests")); assertEquals(0, count("exchange_request_items")); assertEquals(0, exchangeCount());
    }

    @Test
    void bundleRequiresSameHistoricalCreatorEvenWhenCurrentLeaderOrAdministratorControlsBothTeamReservations() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), leader = person("MEMBER"), target = person("MEMBER");
        long team = team("REGULAR"); join(team, a, false); join(team, b, false); join(team, leader, true);
        JsonNode one = teamReservation(a, TODAY, "B01", team), two = teamReservation(b, TODAY, "B02", team), ninety = personal(target, TODAY, "B07", "DRUMS");
        for (Client client : List.of(leader.client(), administrator.client())) {
            assertError(createExchange(client, List.of(one, two), List.of(ninety), key()), 400, "INVALID_EXCHANGE_SHAPE");
        }
        assertEquals(0, count("exchange_requests")); assertEquals(0, count("reservation_lineage"));
    }

    @Test
    void rejectsDuplicateOverlappingEmptyAndExcessItemsAndStaleSourceVersions() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode one = personal(a, TODAY, "B01", "DRUMS"), two = personal(a, TODAY, "B02", "DRUMS"), three = personal(a, TODAY, "B07", "DRUMS"), target = personal(b, TODAY, "B08", "VOCAL");
        for (Map<String, Object> body : List.of(exchangeBody(List.of(), List.of(target)), exchangeBody(List.of(one), List.of()),
                exchangeBody(List.of(one, one), List.of(target)), exchangeBody(List.of(one), List.of(one)), exchangeBody(List.of(one, two, three), List.of(target)))) {
            assertEquals(400, a.client().write("POST", "/api/v1/exchange-requests", body, key()).statusCode());
        }
        Map<String, Object> stale = Map.of("offered", List.of(Map.of("reservationId", one.get("id").asString(), "expectedVersion", one.get("version").asLong() + 1)), "requested", List.of(item(target)));
        assertError(a.client().write("POST", "/api/v1/exchange-requests", stale, key()), 409, "VERSION_CONFLICT");
        assertEquals(0, count("exchange_requests"));
    }

    @Test
    void teamReadMembershipDoesNotGrantRequestOrApprovalAuthorityAndPersonalCounterpartIgnoresLeaderRole() throws Exception {
        Person creator = person("MEMBER"), ordinary = person("MEMBER"), leader = person("MEMBER"), personal = person("MEMBER");
        long team = team("REGULAR"); join(team, creator, false); join(team, ordinary, false); join(team, leader, true);
        JsonNode own = teamReservation(creator, TODAY, "B01", team), target = personal(personal, TODAY, "B07", "DRUMS");
        assertError(createExchange(ordinary.client(), List.of(own), List.of(target), key()), 403, "FORBIDDEN");
        JsonNode outgoing = request(leader.client(), List.of(own), List.of(target));
        assertError(action(leader.client(), outgoing, "approve", key()), 403, "FORBIDDEN");
        approve(personal.client(), outgoing);
        JsonNode hour = teamReservation(creator, TODAY.plusDays(1), "B02", team), ninety = personal(personal, TODAY.plusDays(1), "B08", "DRUMS");
        JsonNode incoming = request(personal.client(), List.of(ninety), List.of(hour));
        assertError(action(ordinary.client(), incoming, "approve", key()), 403, "FORBIDDEN");
        approve(leader.client(), incoming);
        assertEquals(2, exchangeCount());
    }

    @Test
    void losingOneCounterpartLeaderCandidateDoesNotInvalidateRequestAndHistoricalCreatorCanStillApprove() throws Exception {
        Person requester = person("MEMBER"), creator = person("MEMBER"), formerLeader = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, false); join(group, formerLeader, true);
        JsonNode own = personal(requester, TODAY, "B07", "DRUMS"), target = teamReservation(creator, TODAY, "B01", group);
        JsonNode pending = request(requester.client(), List.of(own), List.of(target));
        jdbc.update("UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, formerLeader.rosterId());
        exchanges.refreshPending();
        assertError(action(formerLeader.client(), pending, "approve", key()), 403, "FORBIDDEN");
        assertEquals("PENDING", state(pending));
        approve(creator.client(), pending);
        assertEquals(creator.member().id(), successor(target).get("creatorUserId").asLong());
    }

    @Test
    void requesterLosingOwnTeamAuthorityInvalidatesPendingButDoesNotChangeSurvivingTeamReservations() throws Exception {
        Person leader = person("MEMBER"), creator = person("MEMBER"), target = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, false); join(group, leader, true);
        JsonNode own = teamReservation(creator, TODAY, "B01", group), right = personal(target, TODAY, "B07", "DRUMS");
        JsonNode request = request(leader.client(), List.of(own), List.of(right)); Map<String, String> before = bookingSnapshots();
        jdbc.update("UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, leader.rosterId());
        exchanges.refreshPending();
        assertEquals("INVALIDATED", state(request));
        assertError(action(target.client(), request, "approve", key()), 409, "EXCHANGE_INVALIDATED");
        assertEquals(before, bookingSnapshots());
    }

    @Test
    void withdrawnHistoricalTeamCreatorDoesNotInvalidateAnotherCurrentLeadersRequestOrAdministratorApproval() throws Exception {
        Person creator = person("MEMBER"), leader = person("MEMBER"), target = person("MEMBER");
        long group = team("REGULAR"); join(group, creator, false); join(group, leader, true);
        JsonNode own = teamReservation(creator, TODAY, "B01", group), right = personal(target, TODAY, "B07", "DRUMS");
        JsonNode request = request(leader.client(), List.of(own), List.of(right));
        jdbc.update("UPDATE users SET status='WITHDRAWN',version=version+1 WHERE id=?", creator.member().id());
        exchanges.refreshPending(); assertEquals("PENDING", state(request));
        approve(administrator.client(), request);
        assertEquals(creator.member().id(), successor(own).get("creatorUserId").asLong());
    }

    @Test
    void earliestSourceStartExpiresWholeRequestAtBoundaryAndControlledWorkerPersistsExpiryWithoutMovingBookings() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode left = personal(a, TODAY, "B02", "DRUMS"), right = personal(b, TODAY.plusDays(1), "B07", "VOCAL");
        JsonNode request = request(a.client(), List.of(left), List.of(right)); Map<String, String> before = bookingSnapshots();
        assertEquals(Instant.parse(left.get("startsAt").asString()), Instant.parse(request.get("expiresAt").asString()));
        clock.set(TODAY + "T12:59:59+09:00"); exchanges.refreshPending(); assertEquals("PENDING", state(request));
        clock.set(TODAY + "T13:00:00+09:00"); exchanges.refreshPending(); assertEquals("EXPIRED", state(request));
        assertError(action(b.client(), request, "approve", key()), 409, "EXCHANGE_EXPIRED");
        assertEquals(before, bookingSnapshots()); assertEquals(0, exchangeCount());
    }

    @Test
    void activeBeforeEarliestBoundaryCanApproveButNewRequestAtStartedSourceIsRejected() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode left = personal(a, TODAY, "B02", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode pending = request(a.client(), List.of(left), List.of(right));
        clock.set(TODAY + "T12:59:59+09:00"); approve(b.client(), pending);
        Person c = person("MEMBER"), d = person("MEMBER");
        JsonNode started = personal(c, TODAY, "B02", "BASS"), later = personal(d, TODAY, "B08", "KEYBOARD");
        clock.set(TODAY + "T13:00:00+09:00");
        assertEquals(409, createExchange(c.client(), List.of(started), List.of(later), key()).statusCode());
        assertEquals(1, exchangeCount());
    }

    @Test
    void pendingSourceActualChangeAndCancelInvalidateRelatedRequestsWhileNoOpChangePreservesThem() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), c = person("MEMBER");
        JsonNode source = personal(a, TODAY, "B01", "DRUMS"), first = personal(b, TODAY, "B07", "VOCAL"), second = personal(c, TODAY, "B08", "BASS");
        JsonNode r1 = request(a.client(), List.of(source), List.of(first)), r2 = request(a.client(), List.of(source), List.of(second));
        success(change(a.client(), source, TODAY, "B01", List.of("DRUMS")), 200);
        assertEquals("PENDING", state(r1)); assertEquals("PENDING", state(r2));
        success(change(a.client(), source, TODAY.plusDays(1), "B02", List.of("DRUMS")), 200);
        assertEquals("INVALIDATED", state(r1)); assertEquals("INVALIDATED", state(r2));
        JsonNode next = successor(source); JsonNode r3 = request(a.client(), List.of(next), List.of(first));
        success(cancel(a.client(), next), 200); assertEquals("INVALIDATED", state(r3));
        assertEquals(0, exchangeCount());
    }

    @Test
    void rejectionAndRequesterCancellationKeepBothReservationsAndReplayWithoutVersionOrRowChanges() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), stranger = person("MEMBER");
        JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode rejected = request(a.client(), List.of(left), List.of(right)); Map<String, String> before = bookingSnapshots();
        assertError(action(a.client(), rejected, "reject", key()), 403, "FORBIDDEN");
        String rejectionKey = key(); JsonNode terminal = success(action(b.client(), rejected, "reject", rejectionKey), 200);
        assertEquals("REJECTED", terminal.get("status").asString());
        Map<String, String> done = allSnapshots(); assertEquals(terminal, success(action(b.client(), rejected, "reject", rejectionKey), 200)); assertEquals(done, allSnapshots());
        assertError(action(b.client(), terminal, "approve", key()), 409, "EXCHANGE_NOT_PENDING");
        JsonNode canceled = request(a.client(), List.of(left), List.of(right));
        assertError(action(stranger.client(), canceled, "cancel", key()), 403, "FORBIDDEN");
        String cancelKey = key(); JsonNode result = success(action(a.client(), canceled, "cancel", cancelKey), 200);
        assertEquals("CANCELED", result.get("status").asString());
        done = allSnapshots(); assertEquals(result, success(action(a.client(), canceled, "cancel", cancelKey), 200)); assertEquals(done, allSnapshots());
        assertEquals(before, bookingSnapshots());
    }

    @Test
    void creationAndApprovalIdempotencyDoNotCreateExtraRequestsSuccessorsOrOperationsAndRejectReusedBodyOrRoute() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), c = person("MEMBER");
        JsonNode own = personal(a, TODAY, "B01", "DRUMS"), target = personal(b, TODAY, "B07", "VOCAL"), other = personal(c, TODAY, "B08", "BASS");
        String createKey = key(); JsonNode request = success(createExchange(a.client(), List.of(own), List.of(target), createKey), 201);
        Map<String, String> before = allSnapshots(); assertEquals(request, success(createExchange(a.client(), List.of(own), List.of(target), createKey), 201)); assertEquals(before, allSnapshots());
        assertError(createExchange(a.client(), List.of(own), List.of(other), createKey), 409, "IDEMPOTENCY_KEY_REUSED");
        String approveKey = key(); JsonNode result = success(action(b.client(), request, "approve", approveKey), 200);
        before = allSnapshots(); assertEquals(result, success(action(b.client(), request, "approve", approveKey), 200)); assertEquals(before, allSnapshots());
        assertError(action(b.client(), request, "reject", approveKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(1, count("exchange_requests")); assertEquals(1, exchangeCount()); assertEquals(2, count("reservation_lineage"));
        assertEquals(5, count("reservations"));
    }

    @Test
    void rejectsUnknownFieldsForgedActorMissingVersionKeyAuthenticationAndCsrfWithoutLeakingSensitiveData() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode own = personal(a, TODAY, "B01", "DRUMS"), target = personal(b, TODAY, "B07", "VOCAL");
        Map<String, Object> body = exchangeBody(List.of(own), List.of(target));
        Map<String, Object> forged = new HashMap<>(body); forged.put("requesterUserId", administrator.member().id());
        assertError(a.client().write("POST", "/api/v1/exchange-requests", forged, key()), 400, "INVALID_INPUT");
        assertEquals(400, a.client().write("POST", "/api/v1/exchange-requests", body, null).statusCode());
        assertEquals(401, new Client().get("/api/v1/exchange-requests").statusCode());
        assertEquals(403, a.client().send("POST", "/api/v1/exchange-requests", body, null, key()).statusCode());
        JsonNode request = request(a.client(), List.of(own), List.of(target));
        assertEquals(400, b.client().write("POST", exchangePath(request) + "/approve", Map.of(), key()).statusCode());
        assertEquals(400, b.client().write("POST", exchangePath(request) + "/approve", Map.of("expectedVersion", -1), key()).statusCode());
        assertError(b.client().write("POST", exchangePath(request) + "/approve", Map.of("expectedVersion", request.get("version").asLong(), "actorUserId", a.member().id()), key()), 400, "INVALID_INPUT");
        assertEquals(0, exchangeCount());
    }

    @Test
    void thirdPartyPersonalClaimBlocksTeamArrivalAndClearingItAllowsSameApprovalKeyToRetry() throws Exception {
        Person creator = person("MEMBER"), personal = person("MEMBER"), third = person("MEMBER"); long group = team("REGULAR"); join(group, creator, false);
        JsonNode room = teamReservation(creator, TODAY, "B01", group), target = personal(personal, TODAY, "B07", "DRUMS");
        JsonNode pending = request(creator.client(), List.of(room), List.of(target));
        JsonNode extra = personal(third, TODAY, "B07", "VOCAL"); Map<String, String> before = allSnapshots(); String retryKey = key();
        assertError(action(personal.client(), pending, "approve", retryKey), 409, "RESERVATION_CONFLICT");
        assertEquals(before, allSnapshots()); assertEquals("PENDING", state(pending));
        success(cancel(third.client(), extra), 200); success(action(personal.client(), pending, "approve", retryKey), 200);
        assertEquals(6, claims(id(successor(room)))); assertEquals(1, exchangeCount());
    }

    @Test
    void currentFaultOnEitherNewPersonalPlacementRollsBackEntireExchangeWhileRoomReservationsReceiveWarnings() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode own = personal(a, TODAY, "B01", "DRUMS"), target = personal(b, TODAY, "B07", "VOCAL");
        JsonNode pending = request(a.client(), List.of(own), List.of(target));
        jdbc.update("UPDATE assets SET status='FAULTY',version=version+1 WHERE code='VOCAL'");
        Map<String, String> before = allSnapshots(); assertError(action(b.client(), pending, "approve", key()), 409, "ASSET_FAULTY"); assertEquals(before, allSnapshots());
        jdbc.update("UPDATE assets SET status='NORMAL',version=version+1 WHERE code='VOCAL'"); approve(b.client(), pending);
        Person c = person("MEMBER"), d = person("MEMBER"); long leftTeam = team("REGULAR"), rightTeam = team("ADDITIONAL"); join(leftTeam, c, false); join(rightTeam, d, false);
        JsonNode left = teamReservation(c, TODAY.plusDays(1), "B01", leftTeam), right = teamReservation(d, TODAY.plusDays(1), "B07", rightTeam);
        jdbc.update("UPDATE assets SET status='FAULTY',version=version+1 WHERE code='VOCAL'");
        approve(d.client(), request(c.client(), List.of(left), List.of(right)));
        assertTrue(successor(left).get("teamFaultWarnings").size() > 0); assertTrue(successor(right).get("teamFaultWarnings").size() > 0);
    }

    @Test
    void finalTwoHourQuotaIsRecheckedAfterRequestAndFailureKeepsAllSourcesAndCanRetryAfterRelease() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode one = personal(a, TODAY, "B01", "DRUMS"), two = personal(a, TODAY, "B02", "DRUMS"), ninety = personal(b, TODAY.plusDays(1), "B07", "VOCAL");
        JsonNode pending = request(a.client(), List.of(one, two), List.of(ninety));
        JsonNode existing = personal(b, TODAY, "B03", "VOCAL"); Map<String, String> before = allSnapshots(); String retryKey = key();
        assertError(action(b.client(), pending, "approve", retryKey), 409, "DAILY_LIMIT_EXCEEDED"); assertEquals(before, allSnapshots());
        assertEquals(120, quota(a, null, TODAY)); assertEquals(60, quota(b, null, TODAY));
        success(cancel(b.client(), existing), 200); success(action(b.client(), pending, "approve", retryKey), 200);
        assertEquals(0, quota(a, null, TODAY)); assertEquals(120, quota(b, null, TODAY));
    }

    @Test
    void fixedExchangeChargesMovedHourKeepsSourceOccurrenceExceptionAndNextWeekAndAfterCancelDoesNotRegenerate() throws Exception {
        Person owner = person("MEMBER"), other = person("MEMBER"); long group = team("REGULAR"); join(group, owner, true);
        JsonNode assignment = createAssignment(group, "B01"), fixed = fixed(group, TODAY, "B01"), ninety = personal(other, TODAY.plusDays(1), "B07", "DRUMS");
        assertEquals(0, quota(null, group, TODAY));
        approve(other.client(), request(owner.client(), List.of(fixed), List.of(ninety)));
        JsonNode moved = successor(fixed); assertFalse(moved.get("quotaExempt").asBoolean()); assertEquals("EXCHANGE", moved.get("originKind").asString());
        assertEquals(60, quota(other, null, TODAY)); assertEquals(0, quota(null, group, TODAY.plusDays(1)));
        assertEquals("EXCHANGED_EXCEPTION", occurrenceState(id(assignment), TODAY));
        reopen(TODAY); opening.prepareDate(TODAY); assertEquals(0, activeAt(group, TODAY, "B01")); assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01"));
        success(cancel(owner.client(), moved), 200); assertEquals("CANCELED_EXCEPTION", occurrenceState(id(assignment), TODAY));
        reopen(TODAY); opening.prepareDate(TODAY); assertEquals(0, activeAt(group, TODAY, "B01")); assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01"));
        assertEquals(2, count("reservation_lineage"));
    }

    @Test
    void exchangedFixedSixtyIsNotExemptAndItsOwnerFinalQuotaFailurePreservesOriginalExemption() throws Exception {
        Person owner = person("MEMBER"), other = person("MEMBER"); long group = team("REGULAR"); join(group, owner, true);
        createAssignment(group, "B01"); JsonNode fixed = fixed(group, TODAY, "B01");
        JsonNode right = personal(other, TODAY.plusDays(1), "B02", "DRUMS"), extra1 = teamReservation(owner, TODAY.plusDays(1), "B03", group), extra2 = teamReservation(owner, TODAY.plusDays(1), "B04", group);
        JsonNode request = request(owner.client(), List.of(fixed), List.of(right)); Map<String, String> before = allSnapshots();
        assertError(action(other.client(), request, "approve", key()), 409, "DAILY_LIMIT_EXCEEDED"); assertEquals(before, allSnapshots()); assertTrue(detail(fixed).get("quotaExempt").asBoolean());
        success(cancel(owner.client(), extra2), 200); approve(other.client(), request);
        assertFalse(successor(fixed).get("quotaExempt").asBoolean()); assertEquals(60, successor(fixed).get("quotaMinutes").asInt());
        assertEquals(120, quota(null, group, TODAY.plusDays(1))); assertEquals("CONFIRMED", detail(extra1).get("status").asString());
    }

    @Test
    void sameKeyConcurrentApprovalsCommitExactlyOneExchangeAndReturnSameResult() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode request = request(a.client(), List.of(left), List.of(right)); String approvalKey = key();
        List<HttpResponse<String>> responses = race(() -> action(b.client(), request, "approve", approvalKey), () -> action(b.client(), request, "approve", approvalKey));
        assertEquals(List.of(200, 200), statuses(responses));
        assertEquals(success(responses.get(0), 200), success(responses.get(1), 200));
        assertEquals(1, exchangeCount()); assertEquals(2, count("reservation_lineage")); assertEquals(4, count("reservations"));
        assertEquals("COMPLETED", state(request));
    }

    @Test
    void competingRequestsSharingSourceHaveOneApprovalAndInvalidateTheOtherWithoutPartialSuccessors() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), c = person("MEMBER");
        JsonNode own = personal(a, TODAY, "B01", "DRUMS"), second = personal(b, TODAY, "B07", "VOCAL"), third = personal(c, TODAY, "B08", "BASS");
        JsonNode r1 = request(a.client(), List.of(own), List.of(second)), r2 = request(a.client(), List.of(own), List.of(third));
        List<HttpResponse<String>> responses = race(() -> action(b.client(), r1, "approve", key()), () -> action(c.client(), r2, "approve", key()));
        assertEquals(List.of(200, 409), statuses(responses));
        assertEquals(Set.of("COMPLETED", "INVALIDATED"), Set.of(state(r1), state(r2)));
        assertEquals(1, exchangeCount()); assertEquals(2, count("reservation_lineage")); assertEquals(5, count("reservations"));
        JsonNode loser = "COMPLETED".equals(state(r1)) ? third : second;
        assertEquals("CONFIRMED", detail(loser).get("status").asString()); assertEquals(1, claims(id(loser)));
    }

    @Test
    void approvalRacingSourceCancellationHasOneWholeOutcomeAndNoExtraQuotaRelease() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode request = request(a.client(), List.of(left), List.of(right));
        List<HttpResponse<String>> responses = race(() -> action(b.client(), request, "approve", key()), () -> cancel(a.client(), left));
        assertEquals(List.of(200, 409), statuses(responses));
        if ("COMPLETED".equals(state(request))) {
            assertEquals(1, exchangeCount()); assertEquals(2, count("reservation_lineage")); assertEquals("REPLACED", detail(left).get("status").asString());
            assertEquals(60, quota(b, null, TODAY));
        } else {
            assertEquals("INVALIDATED", state(request)); assertEquals(0, exchangeCount()); assertEquals(0, count("reservation_lineage"));
            assertEquals("CANCELED", detail(left).get("status").asString()); assertEquals("CONFIRMED", detail(right).get("status").asString());
            assertEquals(0, quota(b, null, TODAY));
        }
        assertEquals(0, quota(a, null, TODAY));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE reservation_id=? AND entry_kind='RELEASE'", Integer.class, id(left)));
    }

    @Test
    void approvalRacingSourceChangeHasOneLogicalOperationAndSourceCannotBeMovedTwice() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode request = request(a.client(), List.of(left), List.of(right));
        List<HttpResponse<String>> responses = race(() -> action(b.client(), request, "approve", key()), () -> change(a.client(), left, TODAY.plusDays(1), "B02", List.of("DRUMS")));
        assertEquals(List.of(200, 409), statuses(responses));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind IN ('CHANGE','EXCHANGE')", Integer.class));
        assertEquals(1, successors(left).size()); assertEquals("REPLACED", detail(left).get("status").asString());
        if ("COMPLETED".equals(state(request))) { assertEquals(1, exchangeCount()); assertEquals(2, count("reservation_lineage")); }
        else { assertEquals("INVALIDATED", state(request)); assertEquals(0, exchangeCount()); assertEquals(1, count("reservation_lineage")); assertEquals("CONFIRMED", detail(right).get("status").asString()); }
    }

    @Test
    void approvalRechecksCounterpartLeaderRoleAfterActualDirectoryLockWaitAndLeavesAnotherApproverAvailable() throws Exception {
        Person a = person("MEMBER"), creator = person("MEMBER"), leader = person("MEMBER"); long group = team("REGULAR"); join(group, creator, false); join(group, leader, true);
        JsonNode left = personal(a, TODAY, "B07", "DRUMS"), right = teamReservation(creator, TODAY, "B01", group), request = request(a.client(), List.of(left), List.of(right));
        JsonNode csrf = leader.client().csrf(); Map<String, String> before = bookingSnapshots();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE"); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> leader.client().send("POST", exchangePath(request) + "/approve", Map.of("expectedVersion", request.get("version").asLong()), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var statement = connection.prepareStatement("UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?")) {
                statement.setLong(1, group); statement.setLong(2, leader.rosterId()); assertEquals(1, statement.executeUpdate());
            }
            connection.commit(); assertError(waiting.get(25, TimeUnit.SECONDS), 403, "FORBIDDEN");
        }
        assertEquals(before, bookingSnapshots()); assertEquals("PENDING", state(request)); approve(creator.client(), request);
    }

    @Test
    void approvalRechecksEarliestStartAfterActualDateLockWaitAndPersistsExpiredWithoutReleasingSources() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode request = request(a.client(), List.of(left), List.of(right)); JsonNode csrf = b.client().csrf(); Map<String, String> before = bookingSnapshots();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='" + TODAY + "' FOR UPDATE"); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> b.client().send("POST", exchangePath(request) + "/approve", Map.of("expectedVersion", request.get("version").asLong()), csrf, key()));
            awaitWaiter("booking_days"); clock.set(TODAY + "T12:00:00+09:00"); connection.commit();
            assertError(waiting.get(25, TimeUnit.SECONDS), 409, "EXCHANGE_EXPIRED");
        }
        assertEquals("EXPIRED", state(request)); assertEquals(before, bookingSnapshots());
    }

    @Test
    void realPostgresFailureOnSecondSplitClaimRollsBackPreviouslyWrittenSourcesSuccessorsQuotaAndRequestCompletion() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode first = personal(a, TODAY, "B01", "DRUMS"), second = personal(a, TODAY, "B02", "VOCAL"), ninety = personal(b, TODAY.plusDays(1), "B07", "BASS");
        JsonNode request = request(a.client(), List.of(first, second), List.of(ninety)); String retryKey = key(); Map<String, String> before = allSnapshots();
        // Fail inside PostgreSQL after the other successor claims and source releases have been written.
        jdbc.execute("CREATE FUNCTION exchange_qa_fail_last_claim() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                + "IF NEW.time_block_code='B02' AND EXISTS(SELECT 1 FROM reservations WHERE id=NEW.reservation_id AND origin_kind='EXCHANGE') "
                + "THEN RAISE EXCEPTION USING ERRCODE='23505',MESSAGE='synthetic exchange final claim collision'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER exchange_qa_fail_last_claim BEFORE INSERT ON reservation_assets FOR EACH ROW EXECUTE FUNCTION exchange_qa_fail_last_claim()");
        try { assertError(action(b.client(), request, "approve", retryKey), 409, "DATA_CONFLICT"); }
        finally { jdbc.execute("DROP TRIGGER exchange_qa_fail_last_claim ON reservation_assets"); jdbc.execute("DROP FUNCTION exchange_qa_fail_last_claim()"); }
        assertEquals(before, allSnapshots()); assertEquals("PENDING", state(request)); assertEquals(120, quota(a, null, TODAY)); assertEquals(0, exchangeCount());
        success(action(b.client(), request, "approve", retryKey), 200); assertEquals(1, exchangeCount()); assertEquals(4, count("reservation_lineage"));
    }

    @Test
    void realTenSecondLineageLockTimeoutRollsBackWholeBundleAndSameKeyCanRetryExactlyOnce() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); JsonNode first = personal(a, TODAY, "B01", "DRUMS"), second = personal(a, TODAY, "B02", "VOCAL"), ninety = personal(b, TODAY.plusDays(1), "B07", "BASS");
        JsonNode request = request(a.client(), List.of(first, second), List.of(ninety)); String retryKey = key(); JsonNode csrf = b.client().csrf(); Map<String, String> before = allSnapshots();
        try (Connection connection = fixtureConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false); try (var statement = connection.createStatement()) { statement.execute("LOCK TABLE reservation_lineage IN ACCESS EXCLUSIVE MODE"); }
            var waiting = executor.submit(() -> { long started = System.nanoTime(); HttpResponse<String> response = b.client().send("POST", exchangePath(request) + "/approve", Map.of("expectedVersion", request.get("version").asLong()), csrf, retryKey); return new TimedResponse(response, System.nanoTime() - started); });
            awaitWaiter("INSERT INTO reservation_lineage"); TimedResponse timed = waiting.get(25, TimeUnit.SECONDS);
            assertTrue(timed.elapsedNanos() >= Duration.ofSeconds(9).toNanos()); assertError(timed.response(), 503, "RESERVATION_TEMPORARILY_UNAVAILABLE"); connection.rollback();
        }
        assertEquals(before, allSnapshots()); assertEquals("PENDING", state(request)); assertEquals(1, claims(id(first))); assertEquals(1, claims(id(second))); assertEquals(1, claims(id(ninety)));
        JsonNode committed = success(action(b.client(), request, "approve", retryKey), 200); Map<String, String> after = allSnapshots();
        assertEquals(committed, success(action(b.client(), request, "approve", retryKey), 200)); assertEquals(after, allSnapshots());
        assertEquals(1, exchangeCount()); assertEquals(4, count("reservation_lineage")); assertEquals(6, count("reservations"));
    }

    @Test
    void inactivePersonalOwnerOrRequesterCannotBeKeptPendingButLosingOnlyGlobalApprovalRoleDoesNotInvalidate() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), candidate = person("GLOBAL_ADMIN");
        JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL"), pending = request(a.client(), List.of(left), List.of(right));
        jdbc.update("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", candidate.member().id()); exchanges.refreshPending();
        assertError(action(candidate.client(), pending, "approve", key()), 403, "FORBIDDEN"); assertEquals("PENDING", state(pending));
        Map<String, String> before = bookingSnapshots(); jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?", b.member().id()); exchanges.refreshPending();
        assertEquals("INVALIDATED", state(pending)); assertEquals(before, bookingSnapshots());
        assertError(action(administrator.client(), pending, "approve", key()), 409, "EXCHANGE_INVALIDATED");
    }

    @Test
    void v7UpgradePreservesAllPopulatedV1ThroughV6TablesAndLineageAndCanRestartAndValidate() throws Exception {
        // Both versions are migrated only in an isolated schema of this disposable container.
        String schema = "exchange_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        assertEquals(6, fixtureFlyway(schema, "6").migrate().migrationsExecuted);
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
            long change = insertId(connection, "INSERT INTO reservation_operations(kind,actor_kind,actor_user_id,occurred_at) VALUES ('CHANGE','USER',?,?) RETURNING id", admin, now);
            long changed = fixtureReservation(connection, member, null, member, "B04", change, "CHANGE", false);
            fixtureClaims(connection, changed, 1);
            execute(connection, "UPDATE reservations SET status='REPLACED',version=1 WHERE id=?", personal);
            execute(connection, "UPDATE reservation_assets SET released_at=? WHERE reservation_id=?", now, personal);
            fixtureQuota(connection, personal, change, member, null, -60, "RELEASE");
            fixtureQuota(connection, changed, change, member, null, 60, "RESERVE");
            execute(connection, "INSERT INTO reservation_lineage(operation_id,source_reservation_id,successor_reservation_id,created_at) VALUES (?,?,?,?)", change, personal, changed, now);
            before = schemaSnapshots(connection);
            assertEquals(26, before.size(), "The fixture must cover every V1-V6 feature table");
            for (var entry : before.entrySet()) {
                assertNotEquals("[]", entry.getValue(), "Migration preservation requires populated " + entry.getKey());
            }
        }
        assertEquals(1, fixtureFlyway(schema, "7").migrate().migrationsExecuted);
        assertEquals(0, fixtureFlyway(schema, "7").migrate().migrationsExecuted);
        assertTrue(fixtureFlyway(schema, "7").validateWithResult().validationSuccessful);
        try (Connection connection = fixtureConnection(schema)) {
            for (var entry : before.entrySet()) {
                assertEquals(entry.getValue(), tableSnapshot(connection, entry.getKey()), "V7 must preserve every existing value in " + entry.getKey());
            }
            assertEquals(1, scalar(connection, "SELECT count(*) FROM reservation_lineage"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM exchange_requests"));
            assertEquals(7, scalar(connection, "SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
            assertEquals(15, scalar(connection, "SELECT count(*) FROM reservation_assets"));
            assertEquals(7, scalar(connection, "SELECT count(*) FROM reservation_assets WHERE released_at IS NULL"));
            assertEquals(60, scalar(connection, "SELECT sum(amount_minutes) FROM quota_entries"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM reservations WHERE origin_kind='FIXED' AND quota_exempt"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recurring_occurrences WHERE state='CANCELED_EXCEPTION'"));
        }
    }

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
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) - 'exchange_request_id' ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r")) {
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

    private void execute(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setObject(i + 1, parameters[i]); }
            assertEquals(1, statement.executeUpdate());
        }
    }
    private record TimedResponse(HttpResponse<String> response, long elapsedNanos) {}
    @Test
    void fixedHourlyBundleSharesMergedSuccessorAcrossBothOccurrencesAndCancelKeepsBothWeeklyTombstones() throws Exception {
        Person leader = person("MEMBER"), other = person("MEMBER"); long group = team("REGULAR"); join(group, leader, true);
        JsonNode a1 = createAssignment(group, "B01"), a2 = createAssignment(group, "B02");
        JsonNode first = fixed(group, TODAY, "B01"), second = fixed(group, TODAY, "B02"), ninety = personal(other, TODAY.plusDays(1), "B07", "DRUMS");
        approve(other.client(), request(leader.client(), List.of(first, second), List.of(ninety)));
        JsonNode merged = successor(first); assertEquals(id(merged), id(successor(second)));
        assertEquals("EXCHANGED_EXCEPTION", occurrenceState(id(a1), TODAY)); assertEquals("EXCHANGED_EXCEPTION", occurrenceState(id(a2), TODAY));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM recurring_occurrence_reservations WHERE reservation_id=? AND link_role='SUCCESSOR' AND is_current", Integer.class, id(merged)));
        assertEquals(120, quota(other, null, TODAY)); assertEquals(0, quota(null, group, TODAY)); assertEquals(4, count("reservation_lineage"));
        success(cancel(leader.client(), merged), 200);
        assertEquals("CANCELED_EXCEPTION", occurrenceState(id(a1), TODAY)); assertEquals("CANCELED_EXCEPTION", occurrenceState(id(a2), TODAY));
        reopen(TODAY); opening.prepareDate(TODAY); assertEquals(0, activeAt(group, TODAY, "B01")); assertEquals(0, activeAt(group, TODAY, "B02"));
        assertEquals(1, activeAt(group, TODAY.plusDays(7), "B01")); assertEquals(1, activeAt(group, TODAY.plusDays(7), "B02"));
    }

    @Test
    void samePersonalOwnerAtDestinationBlocksExchangeEvenWhenAllSelectedInstrumentClaimsAreFree() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode left = personal(a, TODAY, "B01", "DRUMS"), right = personal(b, TODAY, "B07", "VOCAL");
        JsonNode pending = request(a.client(), List.of(left), List.of(right)); personal(a, TODAY, "B07", "BASS");
        Map<String, String> before = allSnapshots(); assertError(action(b.client(), pending, "approve", key()), 409, "PERSONAL_OVERLAP"); assertEquals(before, allSnapshots());
        assertEquals("PENDING", state(pending)); assertEquals(0, exchangeCount());
    }

    @Test
    void completedApprovalReplayStillRequiresCurrentTeamAuthorityAfterLeaderRemoval() throws Exception {
        Person requester = person("MEMBER"), creator = person("MEMBER"), leader = person("MEMBER"); long group = team("REGULAR"); join(group, creator, false); join(group, leader, true);
        JsonNode left = personal(requester, TODAY, "B07", "DRUMS"), right = teamReservation(creator, TODAY, "B01", group), pending = request(requester.client(), List.of(left), List.of(right));
        String requestKey = key(); success(action(leader.client(), pending, "approve", requestKey), 200); Map<String, String> before = allSnapshots();
        jdbc.update("UPDATE team_memberships SET role='MEMBER',version=version+1 WHERE team_id=? AND roster_entry_id=?", group, leader.rosterId());
        assertError(action(leader.client(), pending, "approve", requestKey), 403, "FORBIDDEN"); assertEquals(before, allSnapshots()); assertEquals(1, exchangeCount());
    }

    @Test
    void candidateReadUsesOpaqueBundleKeysWithoutExposingOtherOwnerCreatorLoginOrHistoricalDetails() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"), reader = person("MEMBER");
        JsonNode first = personal(a, TODAY, "B01", "DRUMS"), second = personal(a, TODAY, "B02", "VOCAL"), target = personal(b, TODAY, "B07", "BASS");
        JsonNode values = success(reader.client().get("/api/v1/exchange-candidates?from=" + TODAY + "&to=" + TODAY.plusDays(14)), 200);
        assertTrue(values.has("serverTime")); assertEquals(3, values.get("reservations").size());
        Map<String, JsonNode> byId = new HashMap<>(); for (JsonNode candidate : values.get("reservations")) { byId.put(candidate.get("id").asString(), candidate); }
        JsonNode one = byId.get(first.get("id").asString()), two = byId.get(second.get("id").asString()), other = byId.get(target.get("id").asString());
        assertEquals(one.get("bundleKey"), two.get("bundleKey")); assertNotEquals(one.get("bundleKey"), other.get("bundleKey"));
        for (JsonNode candidate : values.get("reservations")) {
            assertTrue(candidate.get("bundleKey").asString().matches("[0-9a-f]{64}")); assertFalse(candidate.get("canChange").asBoolean());
            for (String forbidden : List.of("ownerUserId", "creatorUserId", "loginId", "displayName", "canceledAt", "cancelReason", "successorIds")) { assertFalse(candidate.has(forbidden), "Public candidate must omit " + forbidden); }
        }
        success(cancel(a.client(), first), 200); clock.set(TODAY + "T13:00:00+09:00");
        assertEquals(Set.of(target.get("id").asString()), strings(success(reader.client().get("/api/v1/exchange-candidates"), 200).get("reservations"), "id"));
        assertError(reader.client().get("/api/v1/exchange-candidates?from=" + TODAY + "&to=" + TODAY.plusDays(15)), 400, "INVALID_INPUT");
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE"); ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); }); var b = executor.submit(() -> { start.await(); return second.run(); }); start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'", Integer.class) == 2);
            connection.commit(); return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }
    private List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private void awaitWaiter(String query) { org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)", Boolean.class, "%" + query + "%"))); }
    private Connection fixtureConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Connection lock(String sql) throws Exception { Connection connection = fixtureConnection(); connection.setAutoCommit(false); try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); } return connection; }

    private Dual dual() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER");
        JsonNode one = personal(a, TODAY, "B01", "DRUMS"), two = personal(a, TODAY, "B02", "VOCAL"), ninety = personal(b, TODAY.plusDays(1), "B07", "BASS");
        approve(b.client(), request(a.client(), List.of(one, two), List.of(ninety)));
        return new Dual(a, successor(one));
    }
    private record Dual(Person owner, JsonNode reservation) {}
    private JsonNode personal(Person person, LocalDate date, String block, String instrument) throws Exception {
        return success(person.client().write("POST", "/api/v1/reservations", Map.of("subjectType", "PERSONAL", "date", date.toString(), "blockCode", block, "instrumentCodes", List.of(instrument)), key()), 201);
    }
    private JsonNode teamReservation(Person person, LocalDate date, String block, long team) throws Exception {
        return success(person.client().write("POST", "/api/v1/reservations", Map.of("subjectType", "TEAM", "date", date.toString(), "blockCode", block, "teamId", Long.toString(team)), key()), 201);
    }
    private Map<String, Object> item(JsonNode reservation) { return Map.of("reservationId", reservation.get("id").asString(), "expectedVersion", reservation.get("version").asLong()); }
    private Map<String, Object> exchangeBody(List<JsonNode> offered, List<JsonNode> requested) { return Map.of("offered", offered.stream().map(this::item).toList(), "requested", requested.stream().map(this::item).toList()); }
    private HttpResponse<String> createExchange(Client client, List<JsonNode> offered, List<JsonNode> requested, String requestKey) throws Exception { return client.write("POST", "/api/v1/exchange-requests", exchangeBody(offered, requested), requestKey); }
    private JsonNode request(Client client, List<JsonNode> offered, List<JsonNode> requested) throws Exception { return success(createExchange(client, offered, requested, key()), 201); }
    private HttpResponse<String> action(Client client, JsonNode request, String action, String requestKey) throws Exception { return client.write("POST", exchangePath(request) + "/" + action, Map.of("expectedVersion", request.get("version").asLong()), requestKey); }
    private JsonNode approve(Client client, JsonNode request) throws Exception { return success(action(client, request, "approve", key()), 200); }
    private HttpResponse<String> allocate(Client client, JsonNode request, JsonNode source, List<String> codes, String requestKey) throws Exception { return client.write("PATCH", exchangePath(request) + "/instrument-allocation", Map.of("expectedVersion", request.get("version").asLong(), "reservationId", source.get("id").asString(), "instrumentCodesOrder", codes), requestKey); }
    private HttpResponse<String> change(Client client, JsonNode source, LocalDate date, String block, List<String> instruments) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("expectedVersion", source.get("version").asLong(), "date", date.toString(), "blockCode", block));
        if (instruments != null) { body.put("instrumentCodes", instruments); }
        return client.write("PATCH", reservationPath(source), body, key());
    }
    private HttpResponse<String> cancel(Client client, JsonNode reservation) throws Exception { return client.write("POST", reservationPath(reservation) + "/cancel", Map.of("expectedVersion", reservation.get("version").asLong()), key()); }
    private String state(JsonNode request) { return jdbc.queryForObject("SELECT status FROM exchange_requests WHERE id=?", String.class, id(request)); }
    private JsonNode detail(JsonNode reservation) throws Exception { return success(administrator.client().get(reservationPath(reservation)), 200); }
    private JsonNode successor(JsonNode source) throws Exception { List<JsonNode> rows = successors(source); assertEquals(1, rows.size()); return rows.getFirst(); }
    private List<JsonNode> successors(JsonNode source) throws Exception {
        List<Long> ids = jdbc.queryForList("SELECT DISTINCT t.id FROM reservation_lineage l JOIN reservations t ON t.id=l.successor_reservation_id WHERE l.source_reservation_id=? ORDER BY t.id", Long.class, id(source));
        List<JsonNode> result = new ArrayList<>(); for (long id : ids) { result.add(success(administrator.client().get("/api/v1/reservations/" + id), 200)); } return result;
    }
    private void assertSuccessor(JsonNode source, JsonNode successor, LocalDate date, String block, Set<String> instruments) throws Exception {
        assertNotEquals(id(source), id(successor));
        for (String field : List.of("subjectType", "purpose", "ownerUserId", "teamId", "creatorUserId")) { assertEquals(source.get(field), successor.get(field), "Exchange must preserve " + field); }
        assertEquals(date.toString(), successor.get("date").asString()); assertEquals(block, successor.get("blockCode").asString());
        assertEquals(instruments, strings(successor.get("instrumentCodes"))); assertEquals("EXCHANGE", successor.get("originKind").asString());
        assertEquals("CONFIRMED", successor.get("status").asString()); assertFalse(successor.get("quotaExempt").asBoolean());
        assertEquals("REPLACED", detail(source).get("status").asString()); assertEquals(source.get("version").asLong() + 1, detail(source).get("version").asLong());
    }
    private JsonNode createAssignment(long group, String block) throws Exception { return success(administrator.client().write("POST", "/api/v1/recurring-assignments", Map.of("teamId", Long.toString(group), "weekday", TODAY.getDayOfWeek().getValue(), "blockCode", block, "effectiveDate", TODAY.toString()), key()), 201); }
    private JsonNode fixed(long group, LocalDate date, String block) throws Exception { long id = jdbc.queryForObject("SELECT id FROM reservations WHERE team_id=? AND reservation_date=? AND time_block_code=? AND origin_kind='FIXED' AND status='CONFIRMED'", Long.class, group, date, block); return success(administrator.client().get("/api/v1/reservations/" + id), 200); }
    private String occurrenceState(long assignment, LocalDate date) { return jdbc.queryForObject("SELECT state FROM recurring_occurrences WHERE assignment_id=? AND original_scheduled_date=?", String.class, assignment, date); }
    private void reopen(LocalDate date) { assertEquals(1, jdbc.update("UPDATE booking_days SET status='PREPARING',opened_at=NULL,recurring_prepared=FALSE WHERE reservation_date=?", date)); }
    private long activeAt(long team, LocalDate date, String block) { return jdbc.queryForObject("SELECT count(*) FROM reservations WHERE team_id=? AND reservation_date=? AND time_block_code=? AND status='CONFIRMED'", Long.class, team, date, block); }
    private int quota(Person person, Long team, LocalDate date) { return jdbc.queryForObject("SELECT COALESCE(sum(amount_minutes),0)::integer FROM quota_entries WHERE quota_date=? AND " + (person == null ? "team_id" : "owner_user_id") + "=?", Integer.class, date, person == null ? team : person.member().id()); }
    private long claims(long reservation) { return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_id=? AND released_at IS NULL", Long.class, reservation); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }
    private long exchangeCount() { return jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind='EXCHANGE'", Long.class); }
    private Map<String, String> bookingSnapshots() { return snapshots(BOOKING_TABLES); }
    private Map<String, String> allSnapshots() { Map<String, String> result = bookingSnapshots(); result.putAll(snapshots(EXCHANGE_TABLES)); return result; }
    private Map<String, String> snapshots(String... tables) {
        Map<String, String> result = new HashMap<>(); for (String table : tables) { result.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM " + table + " r", String.class)); } return result;
    }
    private Person person(String role) throws Exception {
        Member member = members.insert("exchange-test-" + UUID.randomUUID().toString().substring(0, 16), passwords.encode(password), "합성 교환 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 교환 명단',?) RETURNING id", Long.class, member.id());
        Client client = new Client(); success(client.write("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password), null), 200);
        return new Person(member, roster, client);
    }
    private long team(String kind) { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 교환 조',?) RETURNING id", Long.class, kind); }
    private void join(long team, Person person, boolean leader) { assertEquals(1, jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,?)", team, person.rosterId(), leader ? "LEADER" : "MEMBER")); }
    private Set<String> strings(JsonNode array) { return strings(array, null); }
    private Set<String> strings(JsonNode array, String field) { return array.valueStream().map(n -> field == null ? n.asString() : n.get(field).asString()).collect(java.util.stream.Collectors.toSet()); }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response)); assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); assertSafe(response); return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response, int status, String code) { assertEquals(status, response.statusCode(), () -> safeFailure(response)); assertEquals(code, json.readTree(response.body()).at("/error/code").asString()); assertSafe(response); }
    private String safeFailure(HttpResponse<String> response) { JsonNode error = json.readTree(response.body()).get("error"); return error == null ? "Unexpected status without API error" : error.path("code").asString(); }
    private void assertSafe(HttpResponse<String> response) { assertFalse(response.body().contains(password)); for (String field : List.of("passwordHash", "password_hash", "codeDigest", "code_digest", "key_digest", "request_fingerprint")) { assertFalse(response.body().contains(field)); } }
    private long id(JsonNode value) { return Long.parseLong(value.get("id").asString()); }
    private String reservationPath(JsonNode value) { return "/api/v1/reservations/" + value.get("id").asString(); }
    private String exchangePath(JsonNode value) { return "/api/v1/exchange-requests/" + value.get("id").asString(); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Person(Member member, long rosterId, Client client) {}
    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"), 200); }
        HttpResponse<String> write(String method, String path, Object body, String requestKey) throws Exception { return send(method, path, body, csrf(), requestKey); }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf, String requestKey) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (requestKey != null) { builder.header("Idempotency-Key", requestKey); } if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return transport.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestTime { @Bean @Primary TestClock exchangeTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now; private final ZoneId zone;
        TestClock() { this(new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z")), ZoneId.of("UTC")); }
        private TestClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(String offsetDateTime) { now.set(Instant.parse(offsetDateTime)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new TestClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
