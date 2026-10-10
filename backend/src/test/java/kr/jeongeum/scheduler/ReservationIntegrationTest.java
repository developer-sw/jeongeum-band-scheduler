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
import java.sql.SQLException;
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
import kr.jeongeum.scheduler.invitation.service.InvitationTokens;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ReservationIntegrationTest.TestTime.class)
@Timeout(90)
class ReservationIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> INSTRUMENTS = Set.of(
            "VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("reservation_test").withUsername("reservation_test")
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

    private String password;
    private Person administrator;

    @BeforeEach
    void setup() throws Exception {
        // Every connection in this class uses only this disposable Testcontainers database.
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status = 'NORMAL', version = 0");
        clock.set(TODAY + "T11:00:00+09:00");
        password = UUID.randomUUID().toString();
        administrator = person("GLOBAL_ADMIN");
    }

    @Test
    void scheduleUsesCanonicalBlocksOnWeekdaysAndWeekendsAndHidesOtherPersonalIdentity() throws Exception {
        Person owner = person("MEMBER");
        Person observer = person("MEMBER");
        JsonNode booking = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        JsonNode schedule = success(observer.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY.plusDays(1)), 200);
        assertEquals(TODAY.toString(), schedule.get("today").asString());
        assertEquals(TODAY.plusDays(14).toString(), schedule.get("bookableThrough").asString());
        assertEquals("Asia/Seoul", schedule.get("timeZone").asString());
        assertEquals(clock.instant(), Instant.parse(schedule.get("serverTime").asString()));
        assertEquals(INSTRUMENTS, strings(schedule.get("instruments"), "code"));
        int[] starts = {720, 780, 840, 900, 960, 1020, 1080, 1170, 1260, 1350};
        for (int dayIndex = 0; dayIndex < 2; dayIndex++) {
            JsonNode day = schedule.get("days").get(dayIndex);
            assertEquals(TODAY.plusDays(dayIndex).toString(), day.get("date").asString());
            assertEquals("OPEN", day.get("openingStatus").asString());
            assertEquals(10, day.get("blocks").size());
            for (int i = 0; i < 10; i++) {
                JsonNode block = day.get("blocks").get(i);
                int duration = i < 6 ? 60 : 90;
                assertEquals("B%02d".formatted(i + 1), block.get("blockCode").asString());
                assertEquals(duration, block.get("durationMinutes").asInt());
                Instant start = TODAY.plusDays(dayIndex).atStartOfDay(SEOUL).plusMinutes(starts[i]).toInstant();
                assertEquals(start, Instant.parse(block.get("startsAt").asString()));
                assertEquals(start.plusSeconds(duration * 60L), Instant.parse(block.get("endsAt").asString()));
                assertEquals("FUTURE", block.get("phase").asString());
            }
        }
        JsonNode summary = block(schedule.get("days").get(0), "B07").get("reservations").get(0);
        assertEquals(booking.get("id").asString(), summary.get("id").asString());
        assertFalse(summary.get("isMine").asBoolean());
        assertFalse(summary.get("canCancel").asBoolean());
        for (String field : List.of("ownerUserId", "creatorUserId", "displayName", "loginId", "ownerName", "creatorName")) {
            assertFalse(summary.has(field), "Public occupancy must not expose " + field);
        }
        assertEquals(404, observer.client().get(reservationPath(booking)).statusCode());
        assertEquals(0, success(observer.client().get("/api/v1/reservations?scope=mine"), 200).size());
        assertEquals(0, success(administrator.client().get("/api/v1/reservations?scope=mine"), 200).size(),
                "Administrator's mine list must not become a directory of all personal bookings");
        assertEquals(booking.get("id").asString(), success(administrator.client().get(reservationPath(booking)), 200).get("id").asString());
    }

    @Test
    void opensExactlyFifteenKoreanDatesAndAdvancesAtKoreanMidnight() throws Exception {
        Person owner = person("MEMBER");
        success(create(owner.client(), personal(TODAY, "B10", "VOCAL")), 201);
        success(create(owner.client(), personal(TODAY.plusDays(14), "B10", "VOCAL")), 201);
        assertError(create(owner.client(), personal(TODAY.minusDays(1), "B07", "VOCAL")), 409, "DATE_NOT_OPEN");
        assertError(create(owner.client(), personal(TODAY.plusDays(15), "B07", "VOCAL")), 409, "DATE_NOT_OPEN");
        assertEquals(400, owner.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY.plusDays(15)).statusCode());
        clock.set(TODAY + "T23:59:59+09:00");
        assertError(create(owner.client(), personal(TODAY.plusDays(15), "B07", "VOCAL")), 409, "DATE_NOT_OPEN");
        clock.set(TODAY.plusDays(1) + "T00:00:00+09:00");
        JsonNode opened = success(create(owner.client(), personal(TODAY.plusDays(15), "B07", "VOCAL")), 201);
        assertEquals(TODAY.plusDays(15).toString(), opened.get("date").asString());
        assertError(create(owner.client(), personal(TODAY, "B09", "KEYBOARD")), 409, "DATE_NOT_OPEN");
        assertEquals(TODAY.plusDays(1).toString(), success(owner.client().get("/api/v1/schedule"), 200).get("today").asString());
    }

    @Test
    void ongoingReservationKeepsFullBlockAndQuotaWhileUsingOnlyRemainingTime() throws Exception {
        Person owner = person("MEMBER");
        clock.set(TODAY + "T14:10:00+09:00");
        JsonNode booking = success(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 201);
        assertEquals(Instant.parse(TODAY + "T14:00:00+09:00"), Instant.parse(booking.get("startsAt").asString()));
        assertEquals(Instant.parse(TODAY + "T15:00:00+09:00"), Instant.parse(booking.get("endsAt").asString()));
        assertEquals(clock.instant(), Instant.parse(booking.get("usableFrom").asString()));
        assertEquals(60, booking.get("quotaMinutes").asInt());
        assertEquals("IN_PROGRESS", booking.get("phase").asString());
        JsonNode day = success(owner.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200).get("days").get(0);
        assertEquals(50, block(day, "B03").get("remainingMinutes").asInt());
        assertEquals(60, day.at("/personalQuota/usedMinutes").asInt());
        Person lastMoment = person("MEMBER");
        clock.set(TODAY + "T14:59:59.999+09:00");
        JsonNode lastDay = success(lastMoment.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200).get("days").get(0);
        assertEquals(1, block(lastDay, "B03").get("remainingMinutes").asInt());
        assertTrue(block(lastDay, "B03").get("canReservePersonal").asBoolean());
        assertEquals(clock.instant(), Instant.parse(success(create(lastMoment.client(), personal(TODAY, "B03", "KEYBOARD")), 201)
                .get("usableFrom").asString()));
        clock.set(TODAY + "T15:00:00+09:00");
        assertError(create(person("MEMBER").client(), personal(TODAY, "B03", "KEYBOARD")), 409, "RESERVATION_FINISHED");
        assertEquals(0, block(success(owner.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200)
                .get("days").get(0), "B03").get("remainingMinutes").asInt());
        assertEquals("ENDED", success(owner.client().get(reservationPath(booking)), 200).get("phase").asString());
    }

    @Test
    void validatesCreateShapeAndIdempotencyHeaderWithoutAllowingForgedPrivileges() throws Exception {
        Person owner = person("MEMBER");
        var ordinary = personal(TODAY, "B01", "DRUMS");
        assertError(owner.client().reservation("POST", "/api/v1/reservations", ordinary, null), 400, "INVALID_INPUT");
        for (String key : List.of("short", "x".repeat(129), "contains space 12345")) {
            assertError(owner.client().reservation("POST", "/api/v1/reservations", ordinary, key), 400, "INVALID_INPUT");
        }
        for (String field : List.of("ownerUserId", "creatorUserId", "quotaExempt", "dualAssetException", "startsAt", "originKind")) {
            Map<String, Object> forged = new HashMap<>(ordinary);
            forged.put(field, field.endsWith("Id") ? Long.toString(administrator.member().id()) : true);
            assertError(create(owner.client(), forged), 400, "INVALID_INPUT");
        }
        for (Object instruments : List.of(List.of(), List.of("DRUMS", "KEYBOARD"), List.of("UNKNOWN"))) {
            Map<String, Object> invalid = new HashMap<>(ordinary);
            invalid.put("instrumentCodes", instruments);
            assertEquals(400, create(owner.client(), invalid).statusCode());
        }
        Map<String, Object> invalidBlock = new HashMap<>(ordinary);
        invalidBlock.put("blockCode", "B11");
        assertError(create(owner.client(), invalidBlock), 400, "INVALID_BLOCK");
        assertEquals(0, count("reservations"));
        assertEquals(0, count("reservation_assets"));
        assertEquals(0, count("quota_entries"));
        assertEquals(0, count("idempotency_requests"));
    }

    @Test
    void protectsReservationMutationWithCsrfAndAuthentication() throws Exception {
        Client anonymous = new Client();
        JsonNode csrf = anonymous.csrf();
        assertError(anonymous.send("POST", "/api/v1/reservations", personal(TODAY, "B01", "DRUMS"), csrf, key()),
                401, "AUTHENTICATION_REQUIRED");
        Person owner = person("MEMBER");
        assertError(owner.client().send("POST", "/api/v1/reservations", personal(TODAY, "B01", "DRUMS"), null, key()),
                403, "CSRF_INVALID");
        assertEquals(0, count("reservations"));
    }

    @Test
    void onlyCurrentTeamMembersCanCreateAndTeamCreationAlwaysClaimsAllSixInstruments() throws Exception {
        Person creator = person("MEMBER");
        Person outsider = person("MEMBER");
        long regular = team("REGULAR");
        long additional = team("ADDITIONAL");
        join(regular, creator, false);
        join(additional, creator, false);
        assertEquals(403, create(outsider.client(), teamBody(TODAY, "B01", regular)).statusCode());
        assertEquals(403, create(administrator.client(), teamBody(TODAY, "B01", regular)).statusCode(),
                "Global administration alone is not team creation membership");
        JsonNode first = success(create(creator.client(), teamBody(TODAY, "B01", regular)), 201);
        JsonNode second = success(create(creator.client(), teamBody(TODAY, "B02", additional)), 201);
        assertEquals("REGULAR", first.get("purpose").asString());
        assertEquals("ADDITIONAL", second.get("purpose").asString());
        assertEquals(INSTRUMENTS, strings(first.get("instrumentCodes"), null));
        assertEquals(12, activeClaims());
        assertEquals("TEAM", first.get("subjectType").asString());
        assertEquals(Long.toString(creator.member().id()), first.get("creatorUserId").asString());
        assertTrue(first.get("ownerUserId").isNull());
        assertFalse(first.get("quotaExempt").asBoolean());
        assertEquals("MANUAL", first.get("originKind").asString());
        Map<String, Object> reduced = new HashMap<>(teamBody(TODAY, "B03", regular));
        reduced.put("instrumentCodes", List.of("DRUMS"));
        assertEquals(400, create(creator.client(), reduced).statusCode());
    }

    @Test
    void personalSharingAndTeamExclusivityAreReleasedAtomicallyOnCancellation() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        Person third = person("MEMBER");
        long group = team("REGULAR");
        join(group, third, false);
        JsonNode drums = success(create(first.client(), personal(TODAY, "B07", "DRUMS")), 201);
        assertError(create(second.client(), personal(TODAY, "B07", "DRUMS")), 409, "RESERVATION_CONFLICT");
        assertError(create(first.client(), personal(TODAY, "B07", "KEYBOARD")), 409, "PERSONAL_OVERLAP");
        JsonNode keys = success(create(second.client(), personal(TODAY, "B07", "KEYBOARD")), 201);
        assertError(create(third.client(), teamBody(TODAY, "B07", group)), 409, "RESERVATION_CONFLICT");
        success(cancel(first.client(), drums), 200);
        assertEquals(1, activeClaims());
        success(cancel(second.client(), keys), 200);
        JsonNode exclusive = success(create(third.client(), teamBody(TODAY, "B07", group)), 201);
        assertEquals(6, activeClaims());
        assertError(create(first.client(), personal(TODAY, "B07", "BASS")), 409, "RESERVATION_CONFLICT");
        success(cancel(third.client(), exclusive), 200);
        assertEquals(0, activeClaims());
        assertEquals(3, count("reservations"), "Canceled records must be retained");
        assertEquals(8, count("reservation_assets"), "Released claims must be retained for history");
    }

    @Test
    void cancellationUsesOwnerCreatorCurrentLeaderAndGlobalAdministratorRights() throws Exception {
        Person owner = person("MEMBER");
        Person teammate = person("MEMBER");
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, owner, false);
        join(group, teammate, false);
        join(group, leader, true);
        JsonNode personal = success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        assertEquals(403, cancel(leader.client(), personal).statusCode());
        success(cancel(administrator.client(), personal), 200);
        JsonNode teamBooking = success(create(owner.client(), teamBody(TODAY, "B08", group)), 201);
        assertEquals(403, cancel(teammate.client(), teamBooking).statusCode());
        assertFalse(success(teammate.client().get(reservationPath(teamBooking)), 200).get("canCancel").asBoolean());
        assertTrue(success(leader.client().get(reservationPath(teamBooking)), 200).get("canCancel").asBoolean());
        JsonNode canceled = success(cancel(leader.client(), teamBooking), 200);
        assertEquals(Long.toString(owner.member().id()), canceled.get("creatorUserId").asString());
        assertEquals(Long.toString(group), canceled.get("teamId").asString());
        assertEquals(leader.member().id(), jdbc.queryForObject("SELECT canceled_by_user_id FROM reservations WHERE id = ?", Long.class, id(teamBooking)));
        assertEquals(0, activeClaims());
    }

    @Test
    void endedBookingsAndOptimisticVersionFailuresPreserveHistoryAndQuota() throws Exception {
        Person owner = person("MEMBER");
        JsonNode booking = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        assertError(owner.client().reservation("POST", reservationPath(booking) + "/cancel",
                Map.of("expectedVersion", booking.get("version").asLong() + 1), key()), 409, "VERSION_CONFLICT");
        assertEquals(1, activeClaims());
        assertEquals(60, quota(owner, null));
        clock.set(TODAY + "T13:00:00+09:00");
        assertError(cancel(owner.client(), booking), 409, "RESERVATION_FINISHED");
        JsonNode ended = success(owner.client().get(reservationPath(booking)), 200);
        assertEquals("CONFIRMED", ended.get("status").asString());
        assertEquals("ENDED", ended.get("phase").asString());
        assertFalse(ended.get("canCancel").asBoolean());
        assertEquals(1, count("reservations"));
        assertEquals(1, count("reservation_operations"));
        assertEquals(60, quota(owner, null));
    }

    @Test
    void twoHourlyBlocksAreTheDailyLimitAndPreStartCancellationRestoresItOnlyOnce() throws Exception {
        Person owner = person("MEMBER");
        JsonNode first = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        success(create(owner.client(), personal(TODAY, "B02", "KEYBOARD")), 201);
        assertError(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 409, "DAILY_LIMIT_EXCEEDED");
        for (String block : List.of("B07", "B08", "B09", "B10")) {
            assertEquals(0, success(create(owner.client(), personal(TODAY, block, "DRUMS")), 201).get("quotaMinutes").asInt());
        }
        JsonNode canceled = success(cancel(owner.client(), first), 200);
        assertEquals(60, quota(owner, null));
        long operations = count("reservation_operations");
        JsonNode noOp = success(cancel(owner.client(), canceled), 200);
        assertEquals(canceled.get("version").asLong(), noOp.get("version").asLong());
        assertEquals(operations, count("reservation_operations"));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE reservation_id = ? AND entry_kind = 'RELEASE'", Integer.class, id(first)));
        success(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 201);
        assertEquals(120, quota(owner, null));
        assertError(create(owner.client(), personal(TODAY, "B04", "BASS")), 409, "DAILY_LIMIT_EXCEEDED");
    }

    @Test
    void cancellationAtOrAfterBlockStartKeepsConsumedHourlyQuota() throws Exception {
        Person owner = person("MEMBER");
        JsonNode started = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        JsonNode future = success(create(owner.client(), personal(TODAY, "B02", "DRUMS")), 201);
        clock.set(TODAY + "T12:00:00+09:00");
        success(cancel(owner.client(), started), 200);
        assertEquals(120, quota(owner, null));
        success(cancel(owner.client(), future), 200);
        assertEquals(60, quota(owner, null));
        JsonNode third = success(create(owner.client(), personal(TODAY, "B03", "DRUMS")), 201);
        clock.set(TODAY + "T14:10:00+09:00");
        success(cancel(owner.client(), third), 200);
        assertEquals(120, quota(owner, null));
        assertEquals(0, activeClaims());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM quota_entries WHERE entry_kind = 'RELEASE'", Integer.class));
        assertError(create(owner.client(), personal(TODAY, "B04", "DRUMS")), 409, "DAILY_LIMIT_EXCEEDED");
        JsonNode day = success(owner.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200).get("days").get(0);
        assertEquals(120, day.at("/personalQuota/usedMinutes").asInt());
        assertEquals(0, day.at("/personalQuota/remainingMinutes").asInt());
    }

    @Test
    void quotasAreSeparateForPersonalAndEachTeamAndSharedBetweenTeamCreators() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        long regular = team("REGULAR");
        long anotherRegular = team("REGULAR");
        join(regular, first, false);
        join(regular, second, false);
        join(anotherRegular, first, false);
        success(create(first.client(), teamBody(TODAY, "B01", regular)), 201);
        success(create(second.client(), teamBody(TODAY, "B02", regular)), 201);
        assertError(create(first.client(), teamBody(TODAY, "B05", regular)), 409, "DAILY_LIMIT_EXCEEDED");
        success(create(first.client(), teamBody(TODAY, "B03", anotherRegular)), 201);
        success(create(first.client(), teamBody(TODAY, "B04", anotherRegular)), 201);
        success(create(first.client(), personal(TODAY, "B05", "DRUMS")), 201);
        success(create(first.client(), personal(TODAY, "B06", "DRUMS")), 201);
        assertEquals(120, quota(first, null));
        assertEquals(120, quota(null, regular));
        assertEquals(120, quota(null, anotherRegular));
        JsonNode day = success(first.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200).get("days").get(0);
        assertEquals(2, day.get("teamQuotas").size());
        day.get("teamQuotas").valueStream().forEach(q -> assertEquals(120, q.get("usedMinutes").asInt()));
    }

    @Test
    void concurrentSameInstrumentAndPersonalOverlapHaveOneWinnerWhileDifferentInstrumentsBothSucceed() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        JsonNode firstCsrf = first.client().csrf();
        JsonNode secondCsrf = second.client().csrf();
        List<HttpResponse<String>> collision = race(
                () -> first.client().send("POST", "/api/v1/reservations", personal(TODAY, "B07", "DRUMS"), firstCsrf, key()),
                () -> second.client().send("POST", "/api/v1/reservations", personal(TODAY, "B07", "DRUMS"), secondCsrf, key()));
        assertEquals(List.of(201, 409), statuses(collision));
        assertError(collision.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "RESERVATION_CONFLICT");
        assertEquals(1, activeClaims());
        List<HttpResponse<String>> sharing = race(
                () -> first.client().send("POST", "/api/v1/reservations", personal(TODAY, "B08", "DRUMS"), firstCsrf, key()),
                () -> second.client().send("POST", "/api/v1/reservations", personal(TODAY, "B08", "KEYBOARD"), secondCsrf, key()));
        assertEquals(List.of(201, 201), statuses(sharing));
        Client anotherDevice = authenticated(first.member());
        JsonNode otherCsrf = anotherDevice.csrf();
        List<HttpResponse<String>> overlap = race(
                () -> first.client().send("POST", "/api/v1/reservations", personal(TODAY, "B09", "VOCAL"), firstCsrf, key()),
                () -> anotherDevice.send("POST", "/api/v1/reservations", personal(TODAY, "B09", "BASS"), otherCsrf, key()));
        assertEquals(List.of(201, 409), statuses(overlap));
        assertError(overlap.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "PERSONAL_OVERLAP");
        assertEquals(4, count("reservations"));
        assertEquals(4, activeClaims());
        assertEquals(4, count("reservation_operations"));
        assertEquals(4, count("idempotency_requests"));
    }

    @Test
    void concurrentTeamAndPersonalClaimsCommitOneWholeReservationWithoutPartialTeamOccupancy() throws Exception {
        Person bandMember = person("MEMBER");
        Person solo = person("MEMBER");
        long firstTeam = team("REGULAR");
        long otherTeam = team("ADDITIONAL");
        join(firstTeam, bandMember, false);
        join(otherTeam, solo, false);
        JsonNode bandCsrf = bandMember.client().csrf();
        JsonNode soloCsrf = solo.client().csrf();
        List<HttpResponse<String>> responses = race(
                () -> bandMember.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B07", firstTeam), bandCsrf, key()),
                () -> solo.client().send("POST", "/api/v1/reservations", personal(TODAY, "B07", "DRUMS"), soloCsrf, key()));
        assertEquals(List.of(201, 409), statuses(responses));
        JsonNode winner = data(responses.stream().filter(r -> r.statusCode() == 201).findFirst().orElseThrow());
        assertEquals("TEAM".equals(winner.get("subjectType").asString()) ? 6 : 1, activeClaims());
        List<HttpResponse<String>> teams = race(
                () -> bandMember.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B08", firstTeam), bandCsrf, key()),
                () -> solo.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B08", otherTeam), soloCsrf, key()));
        assertEquals(List.of(201, 409), statuses(teams));
        assertEquals(2, count("reservations"));
        assertEquals(2, count("reservation_operations"));
        assertEquals(2, count("idempotency_requests"));
        assertEquals(0, jdbc.queryForObject("""
                SELECT count(*) FROM reservations r WHERE r.owner_type = 'TEAM'
                AND (SELECT count(*) FROM reservation_assets a WHERE a.reservation_id = r.id AND a.released_at IS NULL) <> 6
                """, Integer.class));
    }

    @Test
    void simultaneousPersonalRequestsForLastHourCannotExceedDailyQuota() throws Exception {
        Person owner = person("MEMBER");
        success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        Client otherDevice = authenticated(owner.member());
        JsonNode firstCsrf = owner.client().csrf();
        JsonNode secondCsrf = otherDevice.csrf();
        List<HttpResponse<String>> responses = race(
                () -> owner.client().send("POST", "/api/v1/reservations", personal(TODAY, "B02", "DRUMS"), firstCsrf, key()),
                () -> otherDevice.send("POST", "/api/v1/reservations", personal(TODAY, "B03", "KEYBOARD"), secondCsrf, key()));
        assertEquals(List.of(201, 409), statuses(responses));
        assertError(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "DAILY_LIMIT_EXCEEDED");
        assertEquals(120, quota(owner, null));
        assertEquals(2, count("reservations"));
        assertEquals(2, activeClaims());
        assertEquals(2, count("quota_entries"));
        assertEquals(2, count("idempotency_requests"));
    }

    @Test
    void simultaneousDifferentTeamCreatorsShareTheSameRemainingDailyQuota() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        long group = team("REGULAR");
        join(group, first, false);
        join(group, second, false);
        success(create(first.client(), teamBody(TODAY, "B01", group)), 201);
        JsonNode firstCsrf = first.client().csrf();
        JsonNode secondCsrf = second.client().csrf();
        List<HttpResponse<String>> responses = race(
                () -> first.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B02", group), firstCsrf, key()),
                () -> second.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B03", group), secondCsrf, key()));
        assertEquals(List.of(201, 409), statuses(responses));
        assertError(responses.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "DAILY_LIMIT_EXCEEDED");
        assertEquals(120, quota(null, group));
        assertEquals(12, activeClaims());
        assertEquals(2, count("reservations"));
        assertEquals(2, count("reservation_operations"));
    }

    @Test
    void membershipRemovedWhileCreateWaitsForDirectoryGuardIsCheckedAgain() throws Exception {
        Person creator = person("MEMBER");
        long group = team("REGULAR");
        join(group, creator, false);
        JsonNode csrf = creator.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> creator.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B01", group), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var update = connection.prepareStatement("UPDATE team_memberships SET status = 'REMOVED', version = version + 1 WHERE team_id = ? AND roster_entry_id = ?")) {
                update.setLong(1, group);
                update.setLong(2, creator.rosterId());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
        }
        assertEquals(0, count("reservations"));
        assertEquals(0, activeClaims());
        assertEquals(0, count("idempotency_requests"));
        assertEquals(0, quota(null, group));
    }

    @Test
    void revokedLeaderWhileCancellationWaitsCannotUseOldSessionRights() throws Exception {
        Person creator = person("MEMBER");
        Person leader = person("MEMBER");
        long group = team("REGULAR");
        join(group, creator, false);
        join(group, leader, true);
        JsonNode booking = success(create(creator.client(), teamBody(TODAY, "B01", group)), 201);
        JsonNode csrf = leader.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> leader.client().send("POST", reservationPath(booking) + "/cancel",
                    Map.of("expectedVersion", booking.get("version").asLong()), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var update = connection.prepareStatement("UPDATE team_memberships SET role = 'MEMBER', version = version + 1 WHERE team_id = ? AND roster_entry_id = ?")) {
                update.setLong(1, group);
                update.setLong(2, leader.rosterId());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
        }
        assertEquals("CONFIRMED", success(creator.client().get(reservationPath(booking)), 200).get("status").asString());
        assertEquals(6, activeClaims());
        assertEquals(60, quota(null, group));
        assertEquals(1, count("reservation_operations"));
    }

    @Test
    void inactiveActorWhileCreateWaitsForGuardCannotCommitReservation() throws Exception {
        Person owner = person("MEMBER");
        JsonNode csrf = owner.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("POST", "/api/v1/reservations", personal(TODAY, "B01", "DRUMS"), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var update = connection.prepareStatement("UPDATE users SET status = 'INACTIVE', version = version + 1 WHERE id = ?")) {
                update.setLong(1, owner.member().id());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 403, "INACTIVE_USER");
        }
        assertEquals(0, count("reservations"));
        assertEquals(0, count("reservation_operations"));
        assertEquals(0, count("idempotency_requests"));
    }

    @Test
    void blockEndingDuringDayLockWaitIsRejectedAfterLockWithoutLeakingOperationOrQuota() throws Exception {
        Person owner = person("MEMBER");
        success(create(owner.client(), personal(TODAY, "B07", "DRUMS")), 201);
        clock.set(TODAY + "T12:59:00+09:00");
        JsonNode csrf = owner.client().csrf();
        try (Connection connection = lock("SELECT reservation_date FROM booking_days WHERE reservation_date = '2026-10-30' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("POST", "/api/v1/reservations", personal(TODAY, "B01", "DRUMS"), csrf, key()));
            awaitWaiter("booking_days");
            clock.set(TODAY + "T13:00:00+09:00");
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "RESERVATION_FINISHED");
        }
        assertEquals(1, count("reservations"));
        assertEquals(1, count("reservation_operations"));
        assertEquals(1, count("idempotency_requests"));
        assertEquals(1, activeClaims());
        assertEquals(0, quota(owner, null));
    }

    @Test
    void idempotentReplaysReturnCurrentReservationWithoutResurrectingCanceledClaims() throws Exception {
        Person owner = person("MEMBER");
        String createKey = key();
        var body = personal(TODAY, "B01", "DRUMS");
        JsonNode first = success(owner.client().reservation("POST", "/api/v1/reservations", body, createKey), 201);
        JsonNode replay = success(owner.client().reservation("POST", "/api/v1/reservations", body, createKey), 201);
        assertEquals(first.get("id").asString(), replay.get("id").asString());
        assertError(owner.client().reservation("POST", "/api/v1/reservations", personal(TODAY, "B02", "DRUMS"), createKey),
                409, "IDEMPOTENCY_KEY_REUSED");
        assertError(owner.client().reservation("POST", reservationPath(first) + "/cancel",
                Map.of("expectedVersion", first.get("version").asLong()), createKey), 409, "IDEMPOTENCY_KEY_REUSED");
        String cancelKey = key();
        var cancelBody = Map.of("expectedVersion", first.get("version").asLong());
        JsonNode canceled = success(owner.client().reservation("POST", reservationPath(first) + "/cancel", cancelBody, cancelKey), 200);
        JsonNode replayCancel = success(owner.client().reservation("POST", reservationPath(first) + "/cancel", cancelBody, cancelKey), 200);
        assertEquals(canceled.get("version").asLong(), replayCancel.get("version").asLong());
        JsonNode replayCreate = success(owner.client().reservation("POST", "/api/v1/reservations", body, createKey), 201);
        assertEquals("CANCELED", replayCreate.get("status").asString());
        assertEquals(0, activeClaims());
        assertEquals(0, quota(owner, null));
        assertEquals(2, count("reservation_operations"));
        assertEquals(2, count("idempotency_requests"));
        assertEquals(1, count("reservations"));
    }

    @Test
    void simultaneousSameKeyCreatesExactlyOneBookingAndKeyIsScopedToActor() throws Exception {
        Person owner = person("MEMBER");
        Client secondDevice = authenticated(owner.member());
        JsonNode firstCsrf = owner.client().csrf();
        JsonNode secondCsrf = secondDevice.csrf();
        String sharedKey = key();
        var body = personal(TODAY, "B01", "DRUMS");
        List<HttpResponse<String>> responses = race(
                () -> owner.client().send("POST", "/api/v1/reservations", body, firstCsrf, sharedKey),
                () -> secondDevice.send("POST", "/api/v1/reservations", body, secondCsrf, sharedKey));
        assertEquals(List.of(201, 201), statuses(responses));
        assertEquals(data(responses.get(0)).get("id").asString(), data(responses.get(1)).get("id").asString());
        assertEquals(1, count("reservations"));
        assertEquals(1, activeClaims());
        assertEquals(1, count("reservation_operations"));
        assertEquals(1, count("quota_entries"));
        assertEquals(1, count("idempotency_requests"));
        assertEquals(60, quota(owner, null));
        Person other = person("MEMBER");
        JsonNode separate = success(other.client().reservation("POST", "/api/v1/reservations", personal(TODAY, "B02", "DRUMS"), sharedKey), 201);
        assertNotEquals(data(responses.get(0)).get("id").asString(), separate.get("id").asString());
        assertEquals(2, count("idempotency_requests"));
    }

    @Test
    void failedIdempotentRequestCanRetryAfterConflictClearsAndReplayStillRequiresCurrentMembership() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        JsonNode occupied = success(create(first.client(), personal(TODAY, "B01", "DRUMS")), 201);
        String retryKey = key();
        var body = personal(TODAY, "B01", "DRUMS");
        assertError(second.client().reservation("POST", "/api/v1/reservations", body, retryKey), 409, "RESERVATION_CONFLICT");
        assertEquals(1, count("idempotency_requests"));
        assertEquals(1, count("reservation_operations"));
        success(cancel(first.client(), occupied), 200);
        success(second.client().reservation("POST", "/api/v1/reservations", body, retryKey), 201);
        assertEquals(60, quota(second, null));
        long group = team("REGULAR");
        join(group, first, false);
        String teamKey = key();
        var teamBody = teamBody(TODAY, "B07", group);
        JsonNode teamBooking = success(first.client().reservation("POST", "/api/v1/reservations", teamBody, teamKey), 201);
        jdbc.update("UPDATE team_memberships SET status = 'REMOVED', version = version + 1 WHERE team_id = ? AND roster_entry_id = ?",
                group, first.rosterId());
        assertEquals(403, first.client().reservation("POST", "/api/v1/reservations", teamBody, teamKey).statusCode());
        assertEquals(404, first.client().get(reservationPath(teamBooking)).statusCode());
        assertEquals("CONFIRMED", success(administrator.client().get(reservationPath(teamBooking)), 200).get("status").asString());
        assertEquals(7, activeClaims());
    }

    @Test
    void faultyInstrumentPreservesExistingBookingsRejectsNewPersonalAndWarnsTeamWithoutCanceling() throws Exception {
        Person solo = person("MEMBER");
        Person bandMember = person("MEMBER");
        long group = team("ADDITIONAL");
        join(group, bandMember, false);
        JsonNode existing = success(create(solo.client(), personal(TODAY, "B01", "DRUMS")), 201);
        // No fault management endpoint is claimed here: this synthetic fixture exercises reservation validation only.
        jdbc.update("UPDATE assets SET status = 'FAULTY', version = version + 1 WHERE code = 'DRUMS'");
        assertError(create(solo.client(), personal(TODAY, "B02", "DRUMS")), 409, "ASSET_FAULTY");
        assertEquals("CONFIRMED", success(solo.client().get(reservationPath(existing)), 200).get("status").asString());
        JsonNode exclusive = success(create(bandMember.client(), teamBody(TODAY, "B03", group)), 201);
        assertEquals(Set.of("DRUMS"), strings(exclusive.get("teamFaultWarnings"), null));
        assertEquals(7, activeClaims());
        JsonNode day = success(solo.client().get("/api/v1/schedule?from=" + TODAY + "&to=" + TODAY), 200).get("days").get(0);
        assertTrue(strings(block(day, "B02").get("faultyInstrumentCodes"), null).contains("DRUMS"));
        assertFalse(strings(block(day, "B02").get("availableInstrumentCodes"), null).contains("DRUMS"));
        jdbc.update("UPDATE assets SET status = 'NORMAL', version = version + 1 WHERE code = 'DRUMS'");
        success(create(solo.client(), personal(TODAY, "B04", "DRUMS")), 201);
        assertEquals(8, activeClaims());
        assertEquals(120, quota(solo, null));
    }

    @Test
    void instrumentFaultCommittedBeforeWaitingPersonalClaimIsRecheckedUnderAssetLock() throws Exception {
        Person owner = person("MEMBER");
        JsonNode csrf = owner.client().csrf();
        try (Connection connection = lock("SELECT id FROM assets WHERE code = 'DRUMS' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> owner.client().send("POST", "/api/v1/reservations", personal(TODAY, "B01", "DRUMS"), csrf, key()));
            awaitWaiter("assets");
            try (var update = connection.createStatement()) {
                assertEquals(1, update.executeUpdate("UPDATE assets SET status = 'FAULTY', version = version + 1 WHERE code = 'DRUMS'"));
            }
            connection.commit();
            assertError(waiting.get(20, TimeUnit.SECONDS), 409, "ASSET_FAULTY");
        }
        assertEquals(0, count("reservations"));
        assertEquals(0, activeClaims());
        assertEquals(0, count("quota_entries"));
        assertEquals(0, count("idempotency_requests"));
    }

    @Test
    void teamCreationWaitingForAssetLockReturnsNewFaultWarningAndStillClaimsTheWholeRoom() throws Exception {
        Person creator = person("MEMBER");
        long group = team("REGULAR");
        join(group, creator, false);
        JsonNode csrf = creator.client().csrf();
        try (Connection connection = lock("SELECT id FROM assets WHERE code = 'DRUMS' FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> creator.client().send("POST", "/api/v1/reservations", teamBody(TODAY, "B01", group), csrf, key()));
            awaitWaiter("assets");
            try (var update = connection.createStatement()) {
                assertEquals(1, update.executeUpdate("UPDATE assets SET status = 'FAULTY', version = version + 1 WHERE code = 'DRUMS'"));
            }
            connection.commit();
            JsonNode booking = success(waiting.get(20, TimeUnit.SECONDS), 201);
            assertEquals(Set.of("DRUMS"), strings(booking.get("teamFaultWarnings"), null));
            assertEquals(INSTRUMENTS, strings(booking.get("instrumentCodes"), null));
        }
        assertEquals(6, activeClaims());
        assertEquals(60, quota(null, group));
        assertEquals(1, count("reservation_operations"));
    }

    @Test
    void administratorDemotedDuringCancellationWaitCannotCancelAnotherMembersPersonalBooking() throws Exception {
        Person owner = person("MEMBER");
        JsonNode booking = success(create(owner.client(), personal(TODAY, "B01", "DRUMS")), 201);
        JsonNode csrf = administrator.client().csrf();
        try (Connection connection = lock("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var waiting = executor.submit(() -> administrator.client().send("POST", reservationPath(booking) + "/cancel",
                    Map.of("expectedVersion", booking.get("version").asLong()), csrf, key()));
            awaitWaiter("directory_mutation_guard");
            try (var update = connection.prepareStatement("UPDATE users SET global_role = 'MEMBER', version = version + 1 WHERE id = ?")) {
                update.setLong(1, administrator.member().id());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertEquals(403, waiting.get(20, TimeUnit.SECONDS).statusCode());
        }
        assertEquals("CONFIRMED", success(owner.client().get(reservationPath(booking)), 200).get("status").asString());
        assertEquals(1, activeClaims());
        assertEquals(60, quota(owner, null));
        assertEquals(1, count("reservation_operations"));
    }

    @Test
    void databaseConstraintsRejectDuplicateClaimsPersonalOverlapAndForgedParentCoordinates() throws Exception {
        Person first = person("MEMBER");
        Person second = person("MEMBER");
        JsonNode drums = success(create(first.client(), personal(TODAY, "B07", "DRUMS")), 201);
        JsonNode keys = success(create(second.client(), personal(TODAY, "B07", "KEYBOARD")), 201);
        long unrelatedTeam = team("REGULAR");
        jdbc.update("INSERT INTO booking_days(reservation_date, status, opened_at, created_at) VALUES (?, 'OPEN', ?, ?)",
                TODAY.plusDays(1), Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
        try (Connection connection = fixtureConnection(null)) {
            assertSqlState(connection, "23505", """
                    INSERT INTO reservation_assets(reservation_id, reservation_date, time_block_code, asset_id, claim_kind, created_at)
                    SELECT ?, reservation_date, time_block_code, asset_id, claim_kind, created_at FROM reservation_assets WHERE reservation_id = ?
                    """, id(keys), id(drums));
            assertSqlState(connection, "23503", """
                    INSERT INTO reservation_assets(reservation_id, reservation_date, time_block_code, asset_id, claim_kind, created_at)
                    SELECT id, reservation_date + 1, time_block_code, (SELECT id FROM assets WHERE code = 'VOCAL'), 'PERSONAL', created_at
                    FROM reservations WHERE id = ?
                    """, id(keys));
            assertSqlState(connection, "23505", """
                    INSERT INTO reservations(owner_type, purpose, owner_user_id, creator_user_id, reservation_date, time_block_code,
                        block_start_minute, block_end_minute, duration_minutes, block_start_at, block_end_at,
                        usable_from, status, origin_kind, quota_exempt, dual_asset_exception, created_at, created_operation_id)
                    SELECT owner_type, purpose, owner_user_id, creator_user_id, reservation_date, time_block_code,
                        block_start_minute, block_end_minute, duration_minutes, block_start_at, block_end_at,
                        usable_from, status, origin_kind, quota_exempt, dual_asset_exception, created_at, created_operation_id
                    FROM reservations WHERE id = ?
                    """, id(drums));
            assertSqlState(connection, "23514", "UPDATE reservations SET block_start_at = block_start_at + interval '1 second' WHERE id = ?", id(drums));
            assertSqlState(connection, "23514", "UPDATE reservations SET quota_exempt = true WHERE id = ?", id(drums));
            assertSqlState(connection, "23514", "UPDATE reservations SET dual_asset_exception = true WHERE id = ?", id(drums));
            assertSqlState(connection, "23503", "UPDATE reservation_assets SET claim_kind = 'TEAM' WHERE reservation_id = ?", id(drums));
            assertSqlState(connection, "23503", "UPDATE quota_entries SET quota_date = quota_date + 1 WHERE reservation_id = ?", id(drums));
            assertSqlState(connection, "23503", "UPDATE quota_entries SET owner_user_id = ? WHERE reservation_id = ?", second.member().id(), id(drums));
            assertSqlState(connection, "23503", "UPDATE quota_entries SET owner_user_id = NULL, team_id = ? WHERE reservation_id = ?", unrelatedTeam, id(drums));
        }
        assertEquals(2, count("reservations"));
        assertEquals(2, activeClaims());
        assertEquals(2, count("reservation_operations"));
        assertEquals(Set.of("DRUMS"), strings(success(first.client().get(reservationPath(drums)), 200).get("instrumentCodes"), null));
    }

    @Test
    void upgradesPopulatedV3SchemaWithoutChangingDirectoryInvitationOrJdbcSessionDataAndSeedsOnlyOnce() throws Exception {
        // The schema is disposable, isolated, and populated exclusively with synthetic data.
        String schema = "reservation_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway baseline = fixtureFlyway(schema, "3");
        baseline.migrate();
        String hash = passwords.encode(password);
        Map<String, String> snapshots = new HashMap<>();
        try (Connection connection = fixtureConnection(schema)) {
            long admin;
            long member;
            try (var insert = connection.prepareStatement("""
                    INSERT INTO users(login_id, password_hash, display_name, global_role, status, created_at)
                    VALUES (?, ?, '합성 V4 보존 계정', ?, 'ACTIVE', ?) RETURNING id
                    """)) {
                insert.setString(1, "upgrade-admin");
                insert.setString(2, hash);
                insert.setString(3, "GLOBAL_ADMIN");
                insert.setTimestamp(4, Timestamp.from(clock.instant()));
                try (var rows = insert.executeQuery()) { assertTrue(rows.next()); admin = rows.getLong(1); }
                insert.setString(1, "upgrade-member");
                insert.setString(3, "MEMBER");
                try (var rows = insert.executeQuery()) { assertTrue(rows.next()); member = rows.getLong(1); }
            }
            long linked = insertId(connection, "INSERT INTO roster_entries(name, user_id) VALUES ('합성 가입 명단', " + member + ") RETURNING id");
            long pending = insertId(connection, "INSERT INTO roster_entries(name) VALUES ('합성 미가입 명단') RETURNING id");
            long team = insertId(connection, "INSERT INTO teams(name, kind) VALUES ('합성 보존 조', 'REGULAR') RETURNING id");
            try (var statement = connection.createStatement()) {
                assertEquals(1, statement.executeUpdate("INSERT INTO team_memberships(team_id, roster_entry_id, role) VALUES (" + team + ", " + linked + ", 'LEADER')"));
                assertEquals(1, statement.executeUpdate("INSERT INTO team_memberships(team_id, roster_entry_id) VALUES (" + team + ", " + pending + ")"));
                assertEquals(1, statement.executeUpdate("INSERT INTO team_songs(team_id, title, artist, memo, sort_order, created_by, updated_by) VALUES ("
                        + team + ", '합성 보존 곡', '합성 가수', '합성 메모', 3, " + admin + ", " + member + ")"));
                assertEquals(1, statement.executeUpdate("INSERT INTO member_role_audit(target_user_id, actor_user_id, old_role, new_role) VALUES ("
                        + member + ", " + admin + ", 'GLOBAL_ADMIN', 'MEMBER')"));
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO invitation_codes(code_digest, recipient_name, issued_by, created_at, expires_at, roster_entry_id)
                    VALUES (?, '합성 미가입 초대', ?, ?, ?, ?)
                    """)) {
                insert.setString(1, InvitationTokens.digest(InvitationTokens.create()));
                insert.setLong(2, admin);
                insert.setTimestamp(3, Timestamp.from(clock.instant()));
                insert.setTimestamp(4, Timestamp.from(clock.instant().plusSeconds(86400)));
                insert.setLong(5, pending);
                assertEquals(1, insert.executeUpdate());
            }
            String sessionPrimary = UUID.randomUUID().toString();
            try (var insert = connection.prepareStatement("""
                    INSERT INTO spring_session(primary_id, session_id, creation_time, last_access_time, max_inactive_interval, expiry_time, principal_name)
                    VALUES (?, ?, ?, ?, 7776000, ?, 'upgrade-member')
                    """)) {
                insert.setString(1, sessionPrimary);
                insert.setString(2, UUID.randomUUID().toString());
                insert.setLong(3, clock.instant().toEpochMilli());
                insert.setLong(4, clock.instant().toEpochMilli());
                insert.setLong(5, clock.instant().plus(Duration.ofDays(90)).toEpochMilli());
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = connection.prepareStatement("INSERT INTO spring_session_attributes VALUES (?, 'synthetic.v4-upgrade', ?)")) {
                insert.setString(1, sessionPrimary);
                insert.setBytes(2, "synthetic-session-preservation".getBytes(StandardCharsets.UTF_8));
                assertEquals(1, insert.executeUpdate());
            }
            for (String table : List.of("users", "roster_entries", "teams", "team_memberships", "team_songs", "invitation_codes",
                    "member_role_audit", "directory_mutation_guard", "spring_session", "spring_session_attributes")) {
                snapshots.put(table, snapshot(connection, table));
            }
        }
        Flyway upgrade = fixtureFlyway(schema, "4");
        assertEquals(1, upgrade.migrate().migrationsExecuted);
        assertEquals(0, fixtureFlyway(schema, "4").migrate().migrationsExecuted);
        try (Connection connection = fixtureConnection(schema)) {
            for (var snapshot : snapshots.entrySet()) {
                assertTrue(snapshot.getValue().equals(snapshot(connection, snapshot.getKey())),
                        "V4 must preserve synthetic contents of " + snapshot.getKey());
            }
            assertEquals(10, scalar(connection, "SELECT count(*) FROM time_blocks"));
            assertEquals(6, scalar(connection, "SELECT count(*) FROM assets WHERE status = 'NORMAL' AND version = 0"));
            for (String table : List.of("reservations", "reservation_assets", "booking_days", "quota_entries", "reservation_operations", "idempotency_requests")) {
                assertEquals(0, scalar(connection, "SELECT count(*) FROM " + table));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank")) {
                for (String version : List.of("1", "2", "3", "4")) { assertTrue(rows.next()); assertEquals(version, rows.getString(1)); }
                assertFalse(rows.next());
            }
        }
    }

    private Flyway fixtureFlyway(String schema, String target) {
        return Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).load();
    }

    private String snapshot(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text), '[]'::jsonb)::text FROM " + table + " r")) {
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

    private long insertId(Connection connection, String sql) throws Exception { return scalar(connection, sql); }

    private void assertSqlState(Connection connection, String state, String sql, long... parameters) throws Exception {
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) { statement.setLong(i + 1, parameters[i]); }
            SQLException exception = assertThrows(SQLException.class, statement::executeUpdate);
            assertEquals(state, exception.getSQLState());
        }
    }

    private Connection lock(String sql) throws Exception {
        Connection connection = fixtureConnection(null);
        connection.setAutoCommit(false);
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) { assertTrue(rows.next()); }
        return connection;
    }

    private Connection fixtureConnection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        if (schema != null) { connection.setSchema(schema); }
        return connection;
    }

    private void awaitWaiter(String table) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query ILIKE ?)", Boolean.class, "%" + table + "%")));
    }

    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }

    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).sorted().toList();
    }

    private Person person(String role) throws Exception {
        Member member = members.insert("fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(password), "합성 예약 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name, user_id) VALUES ('합성 예약 명단', ?) RETURNING id",
                Long.class, member.id());
        Client client = authenticated(member);
        return new Person(member, roster, client);
    }

    private Client authenticated(Member member) throws Exception {
        Client client = new Client();
        assertEquals(200, client.mutate("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password)).statusCode());
        return client;
    }

    private long team(String kind) {
        return jdbc.queryForObject("INSERT INTO teams(name, kind) VALUES ('합성 예약 조', ?) RETURNING id", Long.class, kind);
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

    private HttpResponse<String> create(Client client, Object body) throws Exception {
        return client.reservation("POST", "/api/v1/reservations", body, key());
    }

    private HttpResponse<String> cancel(Client client, JsonNode booking) throws Exception {
        return client.reservation("POST", reservationPath(booking) + "/cancel", Map.of("expectedVersion", booking.get("version").asLong()), key());
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    private long activeClaims() {
        return jdbc.queryForObject("SELECT count(*) FROM reservation_assets WHERE released_at IS NULL", Long.class);
    }

    private int quota(Person person, Long team) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(amount_minutes), 0)::integer FROM quota_entries WHERE quota_date = ? AND "
                + (person != null ? "owner_user_id" : "team_id") + " = ?", Integer.class, TODAY, person != null ? person.member().id() : team);
    }

    private JsonNode block(JsonNode day, String code) {
        return day.get("blocks").valueStream().filter(b -> code.equals(b.get("blockCode").asString())).findFirst().orElseThrow();
    }

    private Set<String> strings(JsonNode array, String field) {
        return array.valueStream().map(n -> field == null ? n.asString() : n.get(field).asString()).collect(java.util.stream.Collectors.toSet());
    }

    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertSafe(response);
        return data(response);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertSafe(response);
    }

    private String safeFailure(HttpResponse<String> response) {
        JsonNode error = json.readTree(response.body()).get("error");
        return error == null ? "Unexpected status without API error" : error.get("code").asString();
    }

    private void assertSafe(HttpResponse<String> response) {
        assertFalse(response.body().contains(password));
        for (String field : List.of("passwordHash", "password_hash", "codeDigest", "code_digest", "key_digest", "request_fingerprint")) {
            assertFalse(response.body().contains(field));
        }
    }

    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private long id(JsonNode node) { return Long.parseLong(node.get("id").asString()); }
    private String reservationPath(JsonNode booking) { return "/api/v1/reservations/" + booking.get("id").asString(); }
    private String key() { return UUID.randomUUID().toString(); }

    private record Person(Member member, long rosterId, Client client) {}

    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient transport = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null, null); }

        JsonNode csrf() throws Exception {
            return success(get("/api/v1/auth/csrf"), 200);
        }

        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf(), null);
        }

        HttpResponse<String> reservation(String method, String path, Object body, String idempotencyKey) throws Exception {
            return send(method, path, body, csrf(), idempotencyKey);
        }

        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf, String idempotencyKey) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (idempotencyKey != null) { builder.header("Idempotency-Key", idempotencyKey); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return transport.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTime {
        @Bean @Primary TestClock reservationTestClock() { return new TestClock(); }
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
