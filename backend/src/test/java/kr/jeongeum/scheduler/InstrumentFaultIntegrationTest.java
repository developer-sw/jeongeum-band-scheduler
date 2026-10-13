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

/** All accounts, time control, direct SQL, locks and migration upgrades target only this disposable PostgreSQL. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jeongeum.recurring.worker.enabled=false", "jeongeum.exchange.worker.enabled=false"})
@Import(InstrumentFaultIntegrationTest.TestTime.class)
@Timeout(90)
class InstrumentFaultIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final String FAULTS = "/api/v1/instrument-faults";
    private static final String[] BOOKING_TABLES = {"reservations", "reservation_assets", "quota_entries", "reservation_operations",
            "reservation_lineage", "idempotency_requests", "recurring_assignments", "recurring_assignment_revisions",
            "recurring_occurrences", "recurring_occurrence_reservations", "recurring_idempotency_requests", "booking_days",
            "exchange_requests", "exchange_request_items", "exchange_instrument_allocations", "exchange_idempotency_requests"};

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("instrument_fault_test").withUsername("instrument_fault_test")
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
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session, "
                + "asset_incidents, instrument_fault_idempotency_requests RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL',version=0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
        opening.prepareRange(TODAY, TODAY.plusDays(14));
    }

    @Test
    void administratorReportsAndResolvesWithActorTimesTrimmedTextAndIndependentAssetAndIncidentVersions() throws Exception {
        Person resolver = person("GLOBAL_ADMIN");
        Instant reportedAt = clock.instant();
        JsonNode incident = success(report(administrator.client(), "DRUMS", 0, "  페달 점검 필요  ", key()), 201);
        assertEquals("DRUMS", incident.get("instrumentCode").asString());
        assertEquals("드럼", incident.get("instrumentName").asString());
        assertEquals("OPEN", incident.get("status").asString());
        assertEquals("ADMIN", incident.get("origin").asString());
        assertEquals("페달 점검 필요", incident.get("description").asString());
        assertEquals(administrator.member().id(), incident.get("reportedByUserId").asLong());
        assertEquals(reportedAt, Instant.parse(incident.get("reportedAt").asString()));
        assertTrue(incident.get("resolvedByUserId").isNull()); assertTrue(incident.get("resolvedAt").isNull());
        assertEquals(0, incident.get("version").asLong()); assertEquals(1, incident.get("assetVersion").asLong());
        assertAsset("DRUMS", "FAULTY", 1);
        clock.set(TODAY + "T11:15:00+09:00");
        JsonNode resolved = success(resolve(resolver.client(), incident, "  페달 교체 완료  ", key()), 200);
        assertEquals(incident.get("id"), resolved.get("id"));
        assertEquals("RESOLVED", resolved.get("status").asString());
        assertEquals(1, resolved.get("version").asLong()); assertEquals(2, resolved.get("assetVersion").asLong());
        assertEquals(resolver.member().id(), resolved.get("resolvedByUserId").asLong());
        assertEquals(clock.instant(), Instant.parse(resolved.get("resolvedAt").asString()));
        assertEquals("페달 교체 완료", resolved.get("resolutionNote").asString());
        for (String field : List.of("description", "reportedByUserId", "reportedAt", "origin")) {
            assertEquals(incident.get(field), resolved.get(field), "Resolve must preserve " + field);
        }
        assertAsset("DRUMS", "NORMAL", 2); assertEquals(1, count("asset_incidents"));
        assertEquals(2, count("instrument_fault_idempotency_requests"));
    }

    @Test
    void resolvedHistorySurvivesNewFaultAndAccountRoleOrDisplayChanges() throws Exception {
        JsonNode first = fault("DRUMS", "첫 번째 고장");
        JsonNode resolved = success(resolve(administrator.client(), first, null, key()), 200);
        clock.set(TODAY + "T11:20:00+09:00");
        JsonNode second = fault("DRUMS", "두 번째 고장");
        assertNotEquals(first.get("id"), second.get("id")); assertEquals(3, second.get("assetVersion").asLong());
        Person reviewer = person("GLOBAL_ADMIN");
        jdbc.update("UPDATE users SET global_role='MEMBER',display_name='합성 변경 닉네임',version=version+1 WHERE id=?", administrator.member().id());
        JsonNode history = history(reviewer.client(), "?instrumentCode=DRUMS");
        assertEquals(List.of(second.get("id").asString(), resolved.get("id").asString()), ids(history.get("incidents")));
        assertEquals("RESOLVED", history.get("incidents").get(1).get("status").asString());
        assertEquals(administrator.member().id(), history.get("incidents").get(1).get("reportedByUserId").asLong());
        assertEquals("첫 번째 고장", history.get("incidents").get(1).get("description").asString());
        assertEquals(2, count("asset_incidents")); assertAsset("DRUMS", "FAULTY", 3);
    }

    @Test
    void historyUsesStrictDescendingExclusiveCursorFiltersAndDefaultPageAndBoundedLimit() throws Exception {
        List<String> created = new ArrayList<>();
        for (int i=0; i<21; i++) {
            JsonNode incident = fault(i % 2 == 0 ? "DRUMS" : "VOCAL", "합성 이력 " + i);
            success(resolve(administrator.client(), incident, "확인", key()), 200);
            created.add(incident.get("id").asString());
        }
        JsonNode first = history(administrator.client(), "");
        assertTrue(first.has("serverTime")); assertEquals(20, first.get("incidents").size());
        assertEquals(created.reversed().subList(0,20), ids(first.get("incidents")));
        assertEquals(first.get("incidents").get(19).get("id").asString(), first.get("nextBeforeId").asString());
        JsonNode last = history(administrator.client(), "?beforeId=" + first.get("nextBeforeId").asString());
        assertEquals(List.of(created.getFirst()), ids(last.get("incidents"))); assertTrue(last.get("nextBeforeId").isNull());
        assertEquals(21, history(administrator.client(), "?limit=50").get("incidents").size());
        JsonNode filtered = history(administrator.client(), "?instrumentCode=DRUMS&limit=3");
        assertEquals(3, filtered.get("incidents").size());
        for (JsonNode incident : filtered.get("incidents")) { assertEquals("DRUMS", incident.get("instrumentCode").asString()); }
        JsonNode more = history(administrator.client(), "?instrumentCode=DRUMS&limit=3&beforeId=" + filtered.get("nextBeforeId").asString());
        assertEquals(3, more.get("incidents").size());
        assertTrue(Long.parseLong(more.get("incidents").get(0).get("id").asString()) < Long.parseLong(filtered.get("nextBeforeId").asString()));
        assertEquals(0, history(administrator.client(), "?instrumentCode=BASS").get("incidents").size());
    }

    @Test
    void rejectsInvalidCursorLimitAndInstrumentFiltersWithoutExposingHistory() throws Exception {
        fault("DRUMS", "합성 점검");
        for (String query : List.of("?limit=0", "?limit=51", "?limit=-1", "?limit=no", "?beforeId=0", "?beforeId=-1",
                "?beforeId=nan", "?beforeId=9999999999999999999999", "?instrumentCode=TRUMPET")) {
            assertError(administrator.client().get(FAULTS + query), 400, "INVALID_INPUT");
        }
    }

    @Test
    void authenticatedMembersReadSixCurrentAssetsButOnlyGlobalAdministratorsReadOrWriteFaultHistory() throws Exception {
        JsonNode incident = fault("DRUMS", "합성 점검");
        Person member = person("MEMBER"), leader = person("MEMBER");
        long group = team("REGULAR"); join(group, leader, true);
        for (Person actor : List.of(member, leader)) {
            JsonNode instruments = success(actor.client().get("/api/v1/instruments"), 200);
            assertEquals(6, instruments.size());
            JsonNode drums = instruments.valueStream().filter(n -> "DRUMS".equals(n.get("code").asString())).findFirst().orElseThrow();
            assertEquals("FAULTY", drums.get("status").asString()); assertEquals(1, drums.get("version").asLong());
            assertError(actor.client().get(FAULTS), 403, "FORBIDDEN");
            assertError(report(actor.client(), "VOCAL", 0, "합성 등록", key()), 403, "FORBIDDEN");
            assertError(resolve(actor.client(), incident, null, key()), 403, "FORBIDDEN");
        }
        assertEquals(1, count("asset_incidents")); assertAsset("DRUMS", "FAULTY", 1); assertAsset("VOCAL", "NORMAL", 0);
    }

    @Test
    void missingAuthenticationCsrfKeyUnknownActorAndMalformedVersionsCannotMutateAssets() throws Exception {
        Map<String,Object> body = reportBody("DRUMS", 0, "합성 등록");
        assertError(new Client().get(FAULTS), 401, "AUTHENTICATION_REQUIRED");
        assertError(new Client().write("POST", FAULTS, body, key()), 401, "AUTHENTICATION_REQUIRED");
        assertEquals(403, administrator.client().send("POST", FAULTS, body, null, key()).statusCode());
        assertError(administrator.client().write("POST", FAULTS, body, null), 400, "INVALID_INPUT");
        assertError(administrator.client().write("POST", FAULTS, body, " "), 400, "INVALID_INPUT");
        assertError(administrator.client().write("POST", FAULTS, body, "k".repeat(201)), 400, "INVALID_INPUT");
        Map<String,Object> forged = new HashMap<>(body); forged.put("reportedByUserId", "999");
        assertError(administrator.client().write("POST", FAULTS, forged, key()), 400, "INVALID_INPUT");
        for (Object invalidVersion : List.of(-1, "bad", 0.25)) {
            Map<String,Object> invalid = new HashMap<>(body); invalid.put("expectedAssetVersion", invalidVersion);
            assertError(administrator.client().write("POST", FAULTS, invalid, key()), 400, "INVALID_INPUT");
        }
        Map<String,Object> missing = new HashMap<>(body); missing.remove("expectedAssetVersion");
        assertError(administrator.client().write("POST", FAULTS, missing, key()), 400, "INVALID_INPUT");
        assertEquals(0, count("asset_incidents")); assertEquals(0, count("instrument_fault_idempotency_requests")); assertAsset("DRUMS", "NORMAL", 0);
    }

    @Test
    void descriptionAndOptionalResolutionNoteRespectTrimmedOneToThousandCharacterPlainTextBounds() throws Exception {
        for (String text : List.of("", "  \n\t ", "가".repeat(1001))) {
            assertError(report(administrator.client(), "DRUMS", 0, text, key()), 400, "INVALID_INPUT");
        }
        assertError(report(administrator.client(), "UNKNOWN", 0, "합성 점검", key()), 400, "INVALID_INPUT");
        Map<String,Object> missing = new HashMap<>(reportBody("DRUMS", 0, "합성 점검")); missing.remove("description");
        assertError(administrator.client().write("POST", FAULTS, missing, key()), 400, "INVALID_INPUT");
        JsonNode incident = success(report(administrator.client(), "DRUMS", 0, "  " + "가".repeat(1000) + "  ", key()), 201);
        assertEquals(1000, incident.get("description").asString().length());
        assertError(resolve(administrator.client(), incident, "나".repeat(1001), key()), 400, "INVALID_INPUT");
        String literal = "<script>alert('synthetic')</script> 점검 완료";
        JsonNode resolved = success(resolve(administrator.client(), incident, literal, key()), 200);
        assertEquals(literal, resolved.get("resolutionNote").asString());
        JsonNode optional = fault("VOCAL", "합성 점검");
        assertEquals("RESOLVED", success(resolve(administrator.client(), optional, null, key()), 200).get("status").asString());
    }

    @Test
    void staleAssetOrIncidentVersionsAndAlreadyOpenOrResolvedConflictsDoNotOverwriteHistory() throws Exception {
        assertError(report(administrator.client(), "DRUMS", 9, "오래된 화면", key()), 409, "VERSION_CONFLICT");
        JsonNode incident = fault("DRUMS", "합성 점검");
        Map<String,String> before = faultSnapshots();
        assertError(report(administrator.client(), "DRUMS", 1, "중복 고장", key()), 409, "INSTRUMENT_ALREADY_FAULTY");
        assertError(administrator.client().write("POST", resolvePath(incident), Map.of("expectedVersion", 1, "expectedAssetVersion", 1), key()), 409, "VERSION_CONFLICT");
        assertError(administrator.client().write("POST", resolvePath(incident), Map.of("expectedVersion", 0, "expectedAssetVersion", 0), key()), 409, "VERSION_CONFLICT");
        assertEquals(before, faultSnapshots());
        JsonNode resolved = success(resolve(administrator.client(), incident, null, key()), 200);
        Map<String,String> after = faultSnapshots();
        assertError(resolve(administrator.client(), resolved, "다시 복구", key()), 409, "INSTRUMENT_FAULT_RESOLVED");
        assertError(administrator.client().write("POST", FAULTS + "/999999/resolve", Map.of("expectedVersion", 0, "expectedAssetVersion", 0), key()), 404, "RESOURCE_NOT_FOUND");
        assertEquals(after, faultSnapshots());
    }

    @Test
    void exactNormalizedReplayAfterResolveOrNewIncidentDoesNotReopenOldFaultOrIncrementVersions() throws Exception {
        String reportKey = key(), resolveKey = key();
        JsonNode incident = success(report(administrator.client(), "DRUMS", 0, "  합성 점검  ", reportKey), 201);
        assertEquals(incident, success(report(administrator.client(), "DRUMS", 0, "합성 점검", reportKey), 201));
        JsonNode resolved = success(resolve(administrator.client(), incident, "  복구 완료  ", resolveKey), 200);
        assertEquals(resolved, success(resolve(administrator.client(), incident, "복구 완료", resolveKey), 200));
        JsonNode later = fault("DRUMS", "새 고장"); Map<String,String> after = faultSnapshots();
        JsonNode reportReplay = success(report(administrator.client(), "DRUMS", 0, "합성 점검", reportKey), 201);
        JsonNode resolveReplay = success(resolve(administrator.client(), incident, "복구 완료", resolveKey), 200);
        assertEquals(incident.get("id"), reportReplay.get("id")); assertEquals("RESOLVED", reportReplay.get("status").asString());
        assertEquals(resolved.get("id"), resolveReplay.get("id")); assertEquals("RESOLVED", resolveReplay.get("status").asString());
        assertEquals(after, faultSnapshots()); assertEquals(2, count("asset_incidents")); assertEquals(3, count("instrument_fault_idempotency_requests"));
        assertAsset("DRUMS", "FAULTY", later.get("assetVersion").asLong());
    }

    @Test
    void actorScopedKeysRejectDifferentBodyInstrumentVersionOrResolveRouteButAllowAnotherAdministrator() throws Exception {
        String requestKey = key(); JsonNode incident = success(report(administrator.client(), "DRUMS", 0, "합성 점검", requestKey), 201);
        Map<String,String> before = faultSnapshots();
        assertError(report(administrator.client(), "DRUMS", 0, "다른 내용", requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(report(administrator.client(), "VOCAL", 0, "합성 점검", requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(report(administrator.client(), "DRUMS", 1, "합성 점검", requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertError(resolve(administrator.client(), incident, null, requestKey), 409, "IDEMPOTENCY_KEY_REUSED");
        assertEquals(before, faultSnapshots());
        Person other = person("GLOBAL_ADMIN");
        assertEquals("VOCAL", success(report(other.client(), "VOCAL", 0, "다른 관리자", requestKey), 201).get("instrumentCode").asString());
        assertEquals(2, count("instrument_fault_idempotency_requests"));
    }

    @Test
    void replayAndReadsRequireCurrentActiveGlobalAdministratorRatherThanLoginTimeRole() throws Exception {
        String requestKey = key(); JsonNode incident = success(report(administrator.client(), "DRUMS", 0, "합성 점검", requestKey), 201);
        Map<String,String> before = faultSnapshots();
        jdbc.update("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", administrator.member().id());
        assertError(report(administrator.client(), "DRUMS", 0, "합성 점검", requestKey), 403, "FORBIDDEN");
        assertError(administrator.client().get(FAULTS), 403, "FORBIDDEN");
        assertError(resolve(administrator.client(), incident, null, key()), 403, "FORBIDDEN");
        jdbc.update("UPDATE users SET global_role='GLOBAL_ADMIN',status='INACTIVE',version=version+1 WHERE id=?", administrator.member().id());
        assertError(administrator.client().get(FAULTS), 403, "INACTIVE_USER");
        assertEquals(before, faultSnapshots());
    }

    @Test
    void reportAndResolveKeepAllExistingReservationsClaimsQuotasFixedOccurrencesAndPendingExchangeByteForByte() throws Exception {
        Person owner = person("MEMBER"), counterpart = person("MEMBER"), groupMember = person("MEMBER");
        long group = team("REGULAR"); join(group, groupMember, false);
        JsonNode personal = personal(owner, TODAY, "B01", "DRUMS");
        JsonNode target = personal(counterpart, TODAY, "B07", "VOCAL");
        teamReservation(groupMember, TODAY.plusDays(1), "B02", group);
        createAssignment(group, "B03");
        JsonNode pending = exchange(owner.client(), personal, target);
        Map<String,String> before = bookingSnapshots();
        JsonNode incident = fault("DRUMS", "기존 예약 유지 점검");
        assertEquals(before, bookingSnapshots());
        assertEquals("PENDING", exchangeState(pending)); assertEquals(1, claims(id(personal))); assertEquals(60, quota(owner, TODAY));
        success(resolve(administrator.client(), incident, "복구", key()), 200);
        assertEquals(before, bookingSnapshots());
    }

    @Test
    void currentFaultBlocksOnlyNewPersonalPlacementAndClearingItPermitsSameCreationKey() throws Exception {
        Person owner = person("MEMBER"); JsonNode incident = fault("DRUMS", "합성 고장"); String requestKey = key();
        Map<String,Object> body = personalBody(TODAY, "B01", "DRUMS"); Map<String,String> before = bookingSnapshots();
        assertError(owner.client().write("POST", "/api/v1/reservations", body, requestKey), 409, "ASSET_FAULTY");
        assertEquals(before, bookingSnapshots()); assertEquals(0, quota(owner, TODAY));
        JsonNode unaffected = personal(owner, TODAY, "B02", "BASS"); assertEquals(1, claims(id(unaffected)));
        success(resolve(administrator.client(), incident, null, key()), 200);
        JsonNode created = success(owner.client().write("POST", "/api/v1/reservations", body, requestKey), 201);
        assertEquals("CONFIRMED", created.get("status").asString()); assertEquals(120, quota(owner, TODAY));
    }

    @Test
    void existingFaultyPersonalNoOpChangeRetainsFixedStatusAndPendingRequestButRealMoveFailsAtomically() throws Exception {
        Person owner = person("MEMBER"), other = person("MEMBER");
        JsonNode source = personal(owner, TODAY, "B01", "DRUMS"), target = personal(other, TODAY, "B07", "VOCAL");
        JsonNode pending = exchange(owner.client(), source, target); fault("DRUMS", "합성 고장");
        Map<String,String> before = bookingSnapshots();
        JsonNode noOp = success(change(owner.client(), source, TODAY, "B01", List.of("DRUMS"), key()), 200);
        assertEquals(source.get("id"), noOp.get("reservations").get(0).get("id"));
        assertEquals(source.get("version"), noOp.get("reservations").get(0).get("version"));
        // A completed no-op may add its idempotency record but does not change booking state or pending exchange.
        before.remove("idempotency_requests"); Map<String,String> after = bookingSnapshots(); after.remove("idempotency_requests");
        assertEquals(before, after); assertEquals("PENDING", exchangeState(pending));
        Map<String,String> beforeMove = bookingSnapshots();
        assertError(change(owner.client(), source, TODAY.plusDays(1), "B02", List.of("DRUMS"), key()), 409, "ASSET_FAULTY");
        assertEquals(beforeMove, bookingSnapshots()); assertEquals(1, claims(id(source))); assertEquals(60, quota(owner, TODAY));
    }

    @Test
    void changingAwayFromFaultyInstrumentIsAllowedAndChangingIntoFaultyInstrumentPreservesOriginal() throws Exception {
        Person owner = person("MEMBER"); JsonNode source = personal(owner, TODAY, "B01", "DRUMS");
        fault("DRUMS", "합성 고장");
        JsonNode moved = success(change(owner.client(), source, TODAY.plusDays(1), "B02", List.of("BASS"), key()), 200).get("reservations").get(0);
        assertEquals("REPLACED", jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id(source)));
        assertEquals("BASS", moved.get("instrumentCodes").get(0).asString());
        Map<String,String> before = bookingSnapshots();
        assertError(change(owner.client(), moved, TODAY.plusDays(2), "B03", List.of("DRUMS"), key()), 409, "ASSET_FAULTY");
        assertEquals(before, bookingSnapshots());
    }

    @Test
    void failedFaultyExchangeApprovalKeepsBothOriginalsAndPendingAndSameKeySucceedsAfterRepair() throws Exception {
        Person owner = person("MEMBER"), other = person("MEMBER");
        JsonNode left = personal(owner, TODAY, "B01", "DRUMS"), right = personal(other, TODAY.plusDays(1), "B07", "VOCAL");
        JsonNode pending = exchange(owner.client(), left, right); JsonNode incident = fault("VOCAL", "합성 고장");
        Map<String,String> before = bookingSnapshots(); String approvalKey = key();
        assertError(approve(other.client(), pending, approvalKey), 409, "ASSET_FAULTY");
        assertEquals(before, bookingSnapshots()); assertEquals("PENDING", exchangeState(pending));
        assertEquals(1, claims(id(left))); assertEquals(1, claims(id(right))); assertEquals(60, quota(owner, TODAY));
        success(resolve(administrator.client(), incident, null, key()), 200);
        JsonNode completed = success(approve(other.client(), pending, approvalKey), 200);
        assertEquals("COMPLETED", completed.get("status").asString());
        Map<String,String> after = bookingSnapshots();
        assertEquals(completed, success(approve(other.client(), pending, approvalKey), 200)); assertEquals(after, bookingSnapshots());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reservation_operations WHERE kind='EXCHANGE'", Integer.class));
    }

    @Test
    void faultyInstrumentDoesNotBlockRegularAdditionalTeamCreationChangeExchangeOrRecurringGeneration() throws Exception {
        Person a = person("MEMBER"), b = person("MEMBER"); long regular = team("REGULAR"), additional = team("ADDITIONAL");
        join(regular, a, false); join(additional, b, false); fault("DRUMS", "합주 가능 고장 안내");
        JsonNode one = teamReservation(a, TODAY, "B01", regular), two = teamReservation(b, TODAY.plusDays(1), "B07", additional);
        assertEquals(6, claims(id(one))); assertEquals(6, claims(id(two))); assertTrue(one.get("teamFaultWarnings").size() > 0);
        JsonNode moved = success(change(a.client(), one, TODAY.plusDays(2), "B02", null, key()), 200).get("reservations").get(0);
        assertEquals("COMPLETED", success(approve(b.client(), exchange(a.client(), moved, two), key()), 200).get("status").asString());
        JsonNode assignment = createAssignment(regular, "B03");
        long fixed = jdbc.queryForObject("SELECT id FROM reservations WHERE team_id=? AND origin_kind='FIXED' AND reservation_date=? AND time_block_code='B03'", Long.class, regular, TODAY);
        assertEquals(6, claims(fixed)); assertTrue(count("recurring_occurrences") >= 2);
        assertEquals("CONFIRMED", success(administrator.client().get("/api/v1/reservations/"+fixed), 200).get("status").asString());
        assertTrue(assignment.has("id")); assertAsset("DRUMS", "FAULTY", 1);
    }

    @Test
    void concurrentIdenticalReportsAndRepairsReturnOneCommittedIncidentWithoutVersionOrIdempotencyDuplication() throws Exception {
        String reportKey = key(); JsonNode csrf = administrator.client().csrf(); Map<String,Object> body = reportBody("DRUMS", 0, "동시 등록");
        List<HttpResponse<String>> registered = race(
                () -> administrator.client().send("POST", FAULTS, body, csrf, reportKey),
                () -> administrator.client().send("POST", FAULTS, body, csrf, reportKey));
        assertEquals(List.of(201,201), statuses(registered)); JsonNode incident = success(registered.get(0), 201);
        assertEquals(incident, success(registered.get(1), 201)); assertEquals(1, count("asset_incidents")); assertEquals(1, count("instrument_fault_idempotency_requests"));
        assertAsset("DRUMS", "FAULTY", 1);
        String resolveKey = key(); Map<String,Object> repair = resolveBody(incident, "동시 복구");
        List<HttpResponse<String>> resolved = race(
                () -> administrator.client().send("POST", resolvePath(incident), repair, csrf, resolveKey),
                () -> administrator.client().send("POST", resolvePath(incident), repair, csrf, resolveKey));
        assertEquals(List.of(200,200), statuses(resolved)); assertEquals(success(resolved.get(0), 200), success(resolved.get(1), 200));
        assertEquals(1, count("asset_incidents")); assertEquals(2, count("instrument_fault_idempotency_requests")); assertAsset("DRUMS", "NORMAL", 2);
    }

    @Test
    void competingFreshReportsAndRepairsHaveExactlyOneWinnerAndPreserveSuccessfulActorAndReason() throws Exception {
        Person other = person("GLOBAL_ADMIN"); JsonNode firstCsrf = administrator.client().csrf(), secondCsrf = other.client().csrf();
        List<HttpResponse<String>> registered = race(
                () -> administrator.client().send("POST", FAULTS, reportBody("DRUMS", 0, "관리자 A"), firstCsrf, key()),
                () -> other.client().send("POST", FAULTS, reportBody("DRUMS", 0, "관리자 B"), secondCsrf, key()));
        assertEquals(List.of(201,409), statuses(registered));
        JsonNode incident = success(registered.stream().filter(r -> r.statusCode()==201).findFirst().orElseThrow(), 201);
        assertEquals(1, count("asset_incidents")); assertAsset("DRUMS", "FAULTY", 1);
        List<HttpResponse<String>> resolved = race(
                () -> administrator.client().send("POST", resolvePath(incident), resolveBody(incident, "복구 A"), firstCsrf, key()),
                () -> other.client().send("POST", resolvePath(incident), resolveBody(incident, "복구 B"), secondCsrf, key()));
        assertEquals(List.of(200,409), statuses(resolved));
        JsonNode winningRepair = success(resolved.stream().filter(r -> r.statusCode()==200).findFirst().orElseThrow(), 200);
        JsonNode persisted = history(administrator.client(), "").get("incidents").get(0);
        assertEquals(winningRepair, persisted); assertEquals(incident.get("reportedByUserId"), persisted.get("reportedByUserId"));
        assertAsset("DRUMS", "NORMAL", 2); assertEquals(2, count("instrument_fault_idempotency_requests"));
    }

    @Test
    void differentAssetsCanBeReportedConcurrentlyWithoutChangingOneAnothersVersionsOrHistory() throws Exception {
        Person other = person("GLOBAL_ADMIN"); JsonNode aCsrf = administrator.client().csrf(), bCsrf = other.client().csrf();
        List<HttpResponse<String>> responses = race(
                () -> administrator.client().send("POST", FAULTS, reportBody("DRUMS", 0, "드럼 점검"), aCsrf, key()),
                () -> other.client().send("POST", FAULTS, reportBody("VOCAL", 0, "보컬 점검"), bCsrf, key()));
        assertEquals(List.of(201,201), statuses(responses)); assertEquals(2, count("asset_incidents"));
        assertAsset("DRUMS", "FAULTY", 1); assertAsset("VOCAL", "FAULTY", 1); assertAsset("BASS", "NORMAL", 0);
    }

    @Test
    void administratorRoleLossDuringDirectoryGuardWaitRejectsNewReportAndRepairWithNoPartialMutation() throws Exception {
        JsonNode incident = fault("DRUMS", "합성 고장"); JsonNode csrf = administrator.client().csrf();
        for (boolean repair : List.of(false,true)) {
            jdbc.update("UPDATE users SET global_role='GLOBAL_ADMIN' WHERE id=?", administrator.member().id());
            Map<String,String> before = faultSnapshots();
            try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
                    ExecutorService executor = Executors.newSingleThreadExecutor()) {
                var waiting = executor.submit(() -> administrator.client().send("POST", repair ? resolvePath(incident) : FAULTS,
                        repair ? resolveBody(incident, null) : reportBody("VOCAL", 0, "합성 고장"), csrf, key()));
                awaitWaiter("directory_mutation_guard");
                execute(connection, "UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?", administrator.member().id());
                connection.commit(); assertError(waiting.get(20, TimeUnit.SECONDS), 403, "FORBIDDEN");
            }
            assertEquals(before, faultSnapshots());
        }
    }

    @Test
    void reservationCommitBeforeQueuedFaultRemainsConfirmedAndChargedAfterFaultRegistration() throws Exception {
        Person owner = person("MEMBER"); JsonNode bookingCsrf = owner.client().csrf(), faultCsrf = administrator.client().csrf();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date='2026-10-30' FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var booking = executor.submit(() -> owner.client().send("POST", "/api/v1/reservations", personalBody(TODAY, "B01", "DRUMS"), bookingCsrf, key()));
            awaitWaiter("booking_days");
            var report = executor.submit(() -> administrator.client().send("POST", FAULTS, reportBody("DRUMS", 0, "경합 고장"), faultCsrf, key()));
            awaitWaiter("directory_mutation_guard"); connection.commit();
            JsonNode booked = success(booking.get(20, TimeUnit.SECONDS), 201); success(report.get(20, TimeUnit.SECONDS), 201);
            assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM reservations WHERE id=?", String.class, id(booked)));
            assertEquals(1, claims(id(booked))); assertEquals(60, quota(owner, TODAY));
            assertEquals(1, count("reservations")); assertAsset("DRUMS", "FAULTY", 1);
        }
    }

    @Test
    void faultCommitBeforeQueuedReservationRejectsBookingWithoutClaimQuotaOrOperation() throws Exception {
        Person owner = person("MEMBER"); JsonNode bookingCsrf = owner.client().csrf(), faultCsrf = administrator.client().csrf();
        Map<String,String> before = bookingSnapshots();
        try (Connection connection = lock("SELECT id FROM assets WHERE code='DRUMS' FOR SHARE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var report = executor.submit(() -> administrator.client().send("POST", FAULTS, reportBody("DRUMS", 0, "경합 고장"), faultCsrf, key()));
            awaitWaiter("assets");
            var booking = executor.submit(() -> owner.client().send("POST", "/api/v1/reservations", personalBody(TODAY, "B01", "DRUMS"), bookingCsrf, key()));
            awaitWaiter("directory_mutation_guard"); connection.commit();
            success(report.get(20, TimeUnit.SECONDS), 201); assertError(booking.get(20, TimeUnit.SECONDS), 409, "ASSET_FAULTY");
        }
        assertEquals(before, bookingSnapshots()); assertAsset("DRUMS", "FAULTY", 1); assertEquals(0, quota(owner, TODAY));
    }

    @Test
    void reportLateIdempotencyFailureRollsBackAssetIncidentAndKeyAndSameKeyRetriesOnce() throws Exception {
        Map<String,String> before = faultSnapshots(); String requestKey = key();
        failLastWrite();
        try { assertError(report(administrator.client(), "DRUMS", 0, "합성 rollback", requestKey), 409, "DATA_CONFLICT"); }
        finally { removeFailure(); }
        assertEquals(before, faultSnapshots());
        JsonNode result = success(report(administrator.client(), "DRUMS", 0, "합성 rollback", requestKey), 201);
        assertEquals(result, success(report(administrator.client(), "DRUMS", 0, "합성 rollback", requestKey), 201));
        assertEquals(1, count("asset_incidents")); assertEquals(1, count("instrument_fault_idempotency_requests")); assertAsset("DRUMS", "FAULTY", 1);
    }

    @Test
    void repairLateIdempotencyFailureRollsBackResolverTimesNotesVersionsAndSameKeyRetriesOnce() throws Exception {
        JsonNode incident = fault("DRUMS", "합성 고장"); Map<String,String> before = faultSnapshots(); String requestKey = key();
        failLastWrite();
        try { assertError(resolve(administrator.client(), incident, "합성 rollback", requestKey), 409, "DATA_CONFLICT"); }
        finally { removeFailure(); }
        assertEquals(before, faultSnapshots());
        JsonNode result = success(resolve(administrator.client(), incident, "합성 rollback", requestKey), 200);
        assertEquals(result, success(resolve(administrator.client(), incident, "합성 rollback", requestKey), 200));
        assertEquals(1, count("asset_incidents")); assertEquals(2, count("instrument_fault_idempotency_requests")); assertAsset("DRUMS", "NORMAL", 2);
    }

    @Test
    void actualTenSecondLateTableLockTimesOutEntireReportAndExplicitSameKeyRetryIsIdempotent() throws Exception {
        String requestKey = key(); JsonNode csrf = administrator.client().csrf(); Map<String,String> before = faultSnapshots();
        Map<String,Object> body = reportBody("DRUMS", 0, "타임아웃 재확인");
        try (Connection connection = fixtureConnection(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            // SHARE allows the replay SELECT, then blocks only the final idempotency INSERT after asset and incident writes.
            try (var statement = connection.createStatement()) { statement.execute("LOCK TABLE instrument_fault_idempotency_requests IN SHARE MODE"); }
            var waiting = executor.submit(() -> {
                long started = System.nanoTime(); HttpResponse<String> response = administrator.client().send("POST", FAULTS, body, csrf, requestKey);
                return new TimedResponse(response, System.nanoTime()-started);
            });
            awaitWaiter("INSERT INTO instrument_fault_idempotency_requests"); TimedResponse result = waiting.get(25, TimeUnit.SECONDS);
            assertTrue(result.elapsedNanos() >= Duration.ofSeconds(9).toNanos());
            assertError(result.response(), 503, "INSTRUMENT_FAULT_TEMPORARILY_UNAVAILABLE"); connection.rollback();
        }
        assertEquals(before, faultSnapshots());
        JsonNode committed = success(administrator.client().write("POST", FAULTS, body, requestKey), 201); Map<String,String> after = faultSnapshots();
        assertEquals(committed, success(administrator.client().write("POST", FAULTS, body, requestKey), 201)); assertEquals(after, faultSnapshots());
        assertEquals(1, count("asset_incidents")); assertAsset("DRUMS", "FAULTY", 1);
    }

    @Test
    void populatedV7UpgradePreservesEveryOldColumnAndLegacyFaultWhileRestartAndValidationStayIdempotent() throws Exception {
        String schema = "fault_upgrade_"+UUID.randomUUID().toString().replace("-", "");
        assertEquals(7,fixtureFlyway(schema,"7").migrate().migrationsExecuted);
        Map<String,String> before;
        try (Connection connection = fixtureConnection(schema)) {
            Timestamp now = Timestamp.from(clock.instant());
            long admin = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v7-admin',?,'합성 V7 관리자','GLOBAL_ADMIN','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long member = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v7-member',?,'합성 V7 회원','MEMBER','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long roster = insertId(connection, "INSERT INTO roster_entries(name,user_id,version) VALUES ('합성 연결 명단',?,2) RETURNING id", member);
            long pending = insertId(connection, "INSERT INTO roster_entries(name) VALUES ('합성 미가입 명단') RETURNING id");
            long group = insertId(connection, "INSERT INTO teams(name,kind,version) VALUES ('합성 V7 조','REGULAR',3) RETURNING id");
            execute(connection, "INSERT INTO team_memberships(team_id,roster_entry_id,role,version) VALUES (?,?,'LEADER',1)", group, roster);
            execute(connection, "INSERT INTO team_songs(team_id,title,artist,memo,sort_order,created_by,updated_by) "
                    + "VALUES (?,'합성 V7 곡','합성 아티스트','합성 메모',1,?,?)", group, admin, member);
            execute(connection, "INSERT INTO member_role_audit(target_user_id,actor_user_id,old_role,new_role,created_at) "
                    + "VALUES (?,?,'GLOBAL_ADMIN','MEMBER',?)", member, admin, now);
            execute(connection, "INSERT INTO invitation_codes(code_digest,recipient_name,issued_by,created_at,expires_at,roster_entry_id) "
                    + "VALUES (?,'합성 V7 초대',?,?,?,?)",
                    kr.jeongeum.scheduler.invitation.service.InvitationTokens.digest(UUID.randomUUID().toString()),
                    admin, now, Timestamp.from(clock.instant().plus(Duration.ofDays(7))), pending);
            String session = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                    + "VALUES (?,?,?,?,7776000,?,'synthetic-v7-member')", session, UUID.randomUUID().toString(),
                    clock.instant().toEpochMilli(), clock.instant().toEpochMilli(), clock.instant().plus(Duration.ofDays(90)).toEpochMilli());
            execute(connection, "INSERT INTO spring_session_attributes VALUES (?,'synthetic.v7-preservation',?)",
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
                    + "VALUES ('CANCEL','USER',?,?,'합성 V7 취소') RETURNING id", admin, now);
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
            execute(connection, "UPDATE reservations SET status='CANCELED',canceled_at=?,canceled_by_user_id=?,canceled_operation_id=?,cancel_reason='합성 V7 취소',version=1 WHERE id=?",
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
            // Populate every V7 table too, including pending exchange and owner-selected dual-asset allocation.
            long request = insertId(connection,"INSERT INTO exchange_requests(requester_user_id,expires_at,created_at) VALUES (?,?,?) RETURNING id",
                    member,Timestamp.from(TimeBlock.require("B04").startsAt(TODAY)),now);
            long secondHour = fixtureReservation(connection,member,null,member,"B05",create,"MANUAL",false);
            fixtureClaims(connection,secondHour,1);
            fixtureQuota(connection,secondHour,create,member,null,60,"RESERVE");
            long dual = fixtureReservation(connection,admin,null,admin,"B07",create,"EXCHANGE",false);
            execute(connection,"UPDATE reservations SET dual_asset_exception=TRUE WHERE id=?",dual);
            fixtureClaims(connection,dual,1);
            execute(connection,"INSERT INTO reservation_assets(reservation_id,reservation_date,time_block_code,asset_id,claim_kind,created_at) "
                    +"VALUES (?,?,'B07',(SELECT id FROM assets WHERE code='BASS'),'PERSONAL',?)",dual,TODAY,now);
            execute(connection,"INSERT INTO exchange_request_items(exchange_request_id,reservation_id,expected_reservation_version,side,sequence_no) VALUES (?,?,0,'REQUESTER',0)",request,changed);
            execute(connection,"INSERT INTO exchange_request_items(exchange_request_id,reservation_id,expected_reservation_version,side,sequence_no) VALUES (?,?,0,'REQUESTER',1)",request,secondHour);
            execute(connection,"INSERT INTO exchange_request_items(exchange_request_id,reservation_id,expected_reservation_version,side,sequence_no) VALUES (?,?,0,'COUNTERPART',0)",request,dual);
            execute(connection,"INSERT INTO exchange_instrument_allocations(exchange_request_id,reservation_id,target_sequence_no,asset_id) VALUES (?,?,0,(SELECT id FROM assets WHERE code='DRUMS'))",request,dual);
            execute(connection,"INSERT INTO exchange_instrument_allocations(exchange_request_id,reservation_id,target_sequence_no,asset_id) VALUES (?,?,1,(SELECT id FROM assets WHERE code='BASS'))",request,dual);
            execute(connection,"INSERT INTO exchange_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_exchange_request_id,created_at) "
                    +"VALUES (?, ?,?,'POST','/api/v1/exchange-requests',201,?,?)",member,"e".repeat(64),"f".repeat(64),request,now);
            before=schemaSnapshots(connection);
            assertEquals(30,before.size(),"Preservation fixture must cover all V1-V7 feature and session tables");
            for (var entry:before.entrySet()) { assertNotEquals("[]",entry.getValue(),"Migration requires populated "+entry.getKey()); }
        }
        assertEquals(1,fixtureFlyway(schema,"8").migrate().migrationsExecuted);
        assertEquals(0,fixtureFlyway(schema,"8").migrate().migrationsExecuted);
        assertTrue(fixtureFlyway(schema,"8").validateWithResult().validationSuccessful);
        try (Connection connection=fixtureConnection(schema)) {
            for (var entry:before.entrySet()) {
                assertEquals(entry.getValue(),tableSnapshot(connection,entry.getKey()),"V8 must preserve every old column in "+entry.getKey());
            }
            assertEquals(8,scalar(connection,"SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM asset_incidents WHERE origin='LEGACY' AND status='OPEN' AND reported_by_user_id IS NULL"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM asset_incidents i JOIN assets a ON a.id=i.asset_id WHERE a.code='VOCAL' AND a.status='FAULTY' AND a.version=4 AND i.reported_at IS NOT NULL"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM instrument_fault_idempotency_requests"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM exchange_requests WHERE status='PENDING'"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM reservation_lineage"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM recurring_occurrences WHERE state='CANCELED_EXCEPTION'"));
        }
    }

    private Flyway fixtureFlyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }
    private Connection fixtureConnection(String schema) throws Exception {
        Connection connection = fixtureConnection(); connection.setSchema(schema); return connection;
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
            assertTrue(rows.next()); return rows.getString(1);
        }
    }
    private long scalar(Connection connection, String sql) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); return rows.getLong(1); }
    }
    private long insertId(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i=0; i<parameters.length; i++) { statement.setObject(i+1, parameters[i]); }
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
                + "SELECT r.id,r.reservation_date,r.time_block_code,a.id,r.owner_type,? FROM reservations r CROSS JOIN assets a "
                + "WHERE r.id=? AND (r.owner_type='TEAM' OR a.code='DRUMS')")) {
            statement.setTimestamp(1, Timestamp.from(clock.instant())); statement.setLong(2, reservation);
            assertEquals(expected, statement.executeUpdate());
        }
    }
    private void fixtureQuota(Connection connection, long reservation, long operation, Long owner, Long group, int amount, String kind) throws Exception {
        execute(connection, "INSERT INTO quota_entries(reservation_id,operation_id,quota_date,owner_user_id,team_id,amount_minutes,entry_kind,created_at) "
                + "SELECT ?,?,reservation_date,?,?,?,?,? FROM reservations WHERE id=?", reservation, operation, owner, group, amount, kind, Timestamp.from(clock.instant()), reservation);
    }
    private void execute(Connection connection, String sql, Object... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i=0; i<parameters.length; i++) { statement.setObject(i+1, parameters[i]); }
            assertEquals(1, statement.executeUpdate());
        }
    }

    private void failLastWrite() {
        jdbc.execute("CREATE FUNCTION instrument_qa_fail_last_write() RETURNS trigger LANGUAGE plpgsql AS $$ "
                + "BEGIN RAISE EXCEPTION 'synthetic late fault write failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER instrument_qa_fail_last_write BEFORE INSERT ON instrument_fault_idempotency_requests "
                + "FOR EACH ROW EXECUTE FUNCTION instrument_qa_fail_last_write()");
    }
    private void removeFailure() {
        jdbc.execute("DROP TRIGGER instrument_qa_fail_last_write ON instrument_fault_idempotency_requests");
        jdbc.execute("DROP FUNCTION instrument_qa_fail_last_write()");
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
                ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); }); start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'", Integer.class)==2);
            connection.commit(); return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        }
    }
    private List<Integer> statuses(List<HttpResponse<String>> responses) { return responses.stream().map(HttpResponse::statusCode).sorted().toList(); }
    private void awaitWaiter(String query) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)", Boolean.class, "%"+query+"%")));
    }
    private Connection fixtureConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()); }
    private Connection lock(String sql) throws Exception {
        Connection connection = fixtureConnection(); connection.setAutoCommit(false);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); }
        return connection;
    }
    private record TimedResponse(HttpResponse<String> response, long elapsedNanos) {}

    private JsonNode fault(String code, String description) throws Exception {
        return success(report(administrator.client(), code, assetVersion(code), description, key()), 201);
    }
    private HttpResponse<String> report(Client client, String code, long version, String description, String requestKey) throws Exception {
        return client.write("POST", FAULTS, reportBody(code,version,description), requestKey);
    }
    private Map<String,Object> reportBody(String code, long version, String description) {
        return Map.of("instrumentCode",code,"expectedAssetVersion",version,"description",description);
    }
    private HttpResponse<String> resolve(Client client, JsonNode incident, String note, String requestKey) throws Exception {
        return client.write("POST", resolvePath(incident), resolveBody(incident,note), requestKey);
    }
    private Map<String,Object> resolveBody(JsonNode incident, String note) {
        Map<String,Object> body = new HashMap<>(Map.of("expectedVersion",incident.get("version").asLong(),"expectedAssetVersion",incident.get("assetVersion").asLong()));
        if (note != null) { body.put("resolutionNote",note); } return body;
    }
    private String resolvePath(JsonNode incident) { return FAULTS+"/"+incident.get("id").asString()+"/resolve"; }
    private JsonNode history(Client client, String query) throws Exception { return success(client.get(FAULTS+query), 200); }
    private long assetVersion(String code) { return jdbc.queryForObject("SELECT version FROM assets WHERE code=?", Long.class, code); }
    private void assertAsset(String code, String status, long version) {
        assertEquals(status,jdbc.queryForObject("SELECT status FROM assets WHERE code=?",String.class,code)); assertEquals(version,assetVersion(code));
    }
    private JsonNode personal(Person person, LocalDate date, String block, String instrument) throws Exception {
        return success(person.client().write("POST", "/api/v1/reservations", personalBody(date,block,instrument),key()),201);
    }
    private Map<String,Object> personalBody(LocalDate date, String block, String instrument) {
        return Map.of("subjectType","PERSONAL","date",date.toString(),"blockCode",block,"instrumentCodes",List.of(instrument));
    }
    private JsonNode teamReservation(Person person, LocalDate date, String block, long group) throws Exception {
        return success(person.client().write("POST", "/api/v1/reservations", Map.of("subjectType","TEAM","date",date.toString(),"blockCode",block,"teamId",Long.toString(group)),key()),201);
    }
    private HttpResponse<String> change(Client client, JsonNode source, LocalDate date, String block, List<String> instruments, String requestKey) throws Exception {
        Map<String,Object> body = new HashMap<>(Map.of("expectedVersion",source.get("version").asLong(),"date",date.toString(),"blockCode",block));
        if (instruments != null) { body.put("instrumentCodes",instruments); }
        return client.write("PATCH", "/api/v1/reservations/"+source.get("id").asString(),body,requestKey);
    }
    private JsonNode createAssignment(long group, String block) throws Exception {
        return success(administrator.client().write("POST", "/api/v1/recurring-assignments",Map.of("teamId",Long.toString(group),
                "weekday",TODAY.getDayOfWeek().getValue(),"blockCode",block,"effectiveDate",TODAY.toString()),key()),201);
    }
    private Map<String,Object> item(JsonNode reservation) { return Map.of("reservationId",reservation.get("id").asString(),"expectedVersion",reservation.get("version").asLong()); }
    private JsonNode exchange(Client client, JsonNode left, JsonNode right) throws Exception {
        return success(client.write("POST", "/api/v1/exchange-requests",Map.of("offered",List.of(item(left)),"requested",List.of(item(right))),key()),201);
    }
    private HttpResponse<String> approve(Client client, JsonNode exchange, String requestKey) throws Exception {
        return client.write("POST", "/api/v1/exchange-requests/"+exchange.get("id").asString()+"/approve",Map.of("expectedVersion",exchange.get("version").asLong()),requestKey);
    }
    private String exchangeState(JsonNode exchange) { return jdbc.queryForObject("SELECT status FROM exchange_requests WHERE id=?",String.class,id(exchange)); }
    private long claims(long reservation) { return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE reservation_id=? AND released_at IS NULL",Long.class,reservation); }
    private int quota(Person person, LocalDate date) { return jdbc.queryForObject("SELECT COALESCE(sum(amount_minutes),0)::integer FROM quota_entries WHERE owner_user_id=? AND quota_date=?",Integer.class,person.member().id(),date); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private Map<String,String> bookingSnapshots() { return snapshots(BOOKING_TABLES); }
    private Map<String,String> faultSnapshots() { return snapshots("assets","asset_incidents","instrument_fault_idempotency_requests"); }
    private Map<String,String> snapshots(String... tables) {
        Map<String,String> result = new HashMap<>();
        for (String table : tables) { result.put(table,jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "+table+" r",String.class)); }
        return result;
    }
    private Person person(String role) throws Exception {
        Member member = members.insert("fault-test-"+UUID.randomUUID().toString().substring(0,16),passwords.encode(password),"합성 고장 회원",role,clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 고장 명단',?) RETURNING id",Long.class,member.id());
        Client client = new Client(); success(client.write("POST","/api/v1/auth/login",Map.of("loginId",member.loginId(),"password",password),null),200);
        return new Person(member,roster,client);
    }
    private long team(String kind) { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 고장 조',?) RETURNING id",Long.class,kind); }
    private void join(long team, Person person, boolean leader) { assertEquals(1,jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,?)",team,person.rosterId(),leader ? "LEADER" : "MEMBER")); }
    private List<String> ids(JsonNode array) { return array.valueStream().map(n -> n.get("id").asString()).toList(); }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status,response.statusCode(),() -> safeFailure(response)); assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertSafe(response); return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status,response.statusCode(),() -> safeFailure(response)); assertEquals(code,json.readTree(response.body()).at("/error/code").asString()); assertSafe(response);
    }
    private String safeFailure(HttpResponse<String> response) {
        JsonNode error=json.readTree(response.body()).get("error"); return error == null ? "Unexpected status without API error" : error.path("code").asString();
    }
    private void assertSafe(HttpResponse<String> response) {
        assertFalse(response.body().contains(password));
        for (String field : List.of("passwordHash","password_hash","codeDigest","code_digest","key_digest","request_fingerprint")) {
            assertFalse(response.body().contains(field));
        }
        if (response.uri().getPath().startsWith(FAULTS)) { assertFalse(response.body().contains("loginId")); }
    }
    private long id(JsonNode value) { return Long.parseLong(value.get("id").asString()); }
    private String key() { return UUID.randomUUID().toString(); }
    private record Person(Member member,long rosterId,Client client) {}
    private final class Client {
        private final CookieManager cookies = new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null,null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"),200); }
        HttpResponse<String> write(String method,String path,Object body,String requestKey) throws Exception { return send(method,path,body,csrf(),requestKey); }
        HttpResponse<String> send(String method,String path,Object body,JsonNode csrf,String requestKey) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(),csrf.get("token").asString()); }
            if (requestKey != null) { builder.header("Idempotency-Key",requestKey); } if (body != null) { builder.header("Content-Type","application/json"); }
            builder.method(method,body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return transport.send(builder.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class TestTime { @Bean @Primary TestClock instrumentFaultTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now; private final ZoneId zone;
        TestClock() { this(new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z")),ZoneId.of("UTC")); }
        private TestClock(AtomicReference<Instant> now,ZoneId zone) { this.now=now; this.zone=zone; }
        void set(String offsetDateTime) { now.set(Instant.parse(offsetDateTime)); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new TestClock(now,zone); }
        @Override public Instant instant() { return now.get(); }
    }
}
