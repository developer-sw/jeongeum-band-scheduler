package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.exchange.api.ExchangeRequests;
import kr.jeongeum.scheduler.exchange.api.ExchangeResponse;
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.recurring.api.RecurringRequests;
import kr.jeongeum.scheduler.recurring.service.RecurringOpeningService;
import kr.jeongeum.scheduler.recurring.service.RecurringService;
import kr.jeongeum.scheduler.reservation.api.ReservationRequests;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.service.ReservationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Business writes and failure injection exclusively use this disposable PostgreSQL, never the development DB. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jeongeum.recurring.worker.enabled=false", "jeongeum.exchange.worker.enabled=false"})
@Import(NotificationEventIntegrationTest.TestTime.class)
@Timeout(90)
class NotificationEventIntegrationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private static final List<String> STATE_TABLES = List.of("reservations", "reservation_assets", "quota_entries",
            "reservation_operations", "reservation_lineage", "idempotency_requests", "exchange_requests",
            "exchange_request_items", "exchange_idempotency_requests", "recurring_assignments",
            "recurring_assignment_revisions", "recurring_occurrences", "recurring_occurrence_reservations",
            "recurring_idempotency_requests", "notifications");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("notification_event_test").withUsername("notification_event_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("APP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired ReservationService reservations;
    @Autowired ExchangeService exchanges;
    @Autowired RecurringService recurring;
    @Autowired RecurringOpeningService opening;
    @Autowired TestClock clock;
    private Person administrator;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE booking_days, teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL',version=0");
        clock.set("2026-10-30T11:00:00+09:00");
        administrator = person("GLOBAL_ADMIN");
        opening.prepareRange(TODAY, TODAY.plusDays(14));
    }

    @AfterEach
    void removeFailureInjection() {
        jdbc.execute("DROP TRIGGER IF EXISTS notification_event_test_failure ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS notification_event_test_failure()");
    }

    @Test
    void personalCreateReplayAndNoOpChangeProduceOneEventWithoutAdministratorBroadcast() {
        Person owner = person("MEMBER");
        String requestKey = key();
        var input = personal(TODAY, "B01", "DRUMS");
        ReservationResponse created = reservations.create(owner.id(), requestKey, input).reservation();
        assertEquals(created.id(), reservations.create(owner.id(), requestKey, input).reservation().id());
        assertRecipients("RESERVATION_CREATED", Set.of(owner.id()));
        String noOpKey = key();
        var unchanged = new ReservationRequests.Change(created.version(), TODAY.toString(), "B01", List.of("DRUMS"));
        assertTrue(reservations.change(owner.id(), id(created), noOpKey, unchanged).replacedReservationIds().isEmpty());
        reservations.change(owner.id(), id(created), noOpKey, unchanged);
        assertEquals(1, count("notifications"));
        assertEquals(0, eventCount("RESERVATION_CHANGED"));
        assertEquals(0, notificationsFor(administrator.id()));
    }

    @Test
    void teamEventsReachOnlyCurrentActiveMembersAndCancelOnlyOnce() {
        Person author = person("MEMBER"), active = person("MEMBER"), inactive = person("MEMBER"), left = person("MEMBER");
        long team = team();
        join(team, author, "MEMBER"); join(team, active, "LEADER"); join(team, inactive, "MEMBER"); join(team, left, "MEMBER");
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", inactive.id());
        jdbc.update("UPDATE team_memberships SET status='REMOVED' WHERE team_id=? AND roster_entry_id=?", team, left.rosterId());
        ReservationResponse created = teamReservation(author, team, TODAY, "B07");
        assertRecipients("RESERVATION_CREATED", Set.of(author.id(), active.id()));
        String cancelKey = key();
        ReservationResponse canceled = reservations.cancel(author.id(), id(created), cancelKey,
                new ReservationRequests.Cancel(created.version(), "합성 취소")).reservation();
        reservations.cancel(author.id(), id(created), cancelKey, new ReservationRequests.Cancel(created.version(), "합성 취소"));
        reservations.cancel(author.id(), id(created), key(), new ReservationRequests.Cancel(canceled.version(), null));
        assertRecipients("RESERVATION_CANCELED", Set.of(author.id(), active.id()));
        assertEquals(4, count("notifications"));
        assertEquals(0, notificationsFor(administrator.id()));
    }

    @Test
    void changePublishesOneLogicalChangeAndInvalidationInTheSameTransaction() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ReservationResponse source = personal(owner, TODAY, "B01", "DRUMS");
        ReservationResponse target = personal(counterpart, TODAY, "B07", "VOCAL");
        ExchangeResponse request = request(owner, source, target);
        var change = new ReservationRequests.Change(source.version(), TODAY.toString(), "B03", List.of("BASS"));
        String changeKey = key();
        ReservationResponse next = reservations.change(owner.id(), id(source), changeKey, change).reservations().getFirst();
        assertEquals(next.id(), reservations.change(owner.id(), id(source), changeKey, change).reservations().getFirst().id());
        assertRecipients("RESERVATION_CHANGED", Set.of(owner.id()));
        assertEquals(2, eventCount("RESERVATION_CREATED"));
        assertEquals("INVALIDATED", exchangeStatus(request));
        assertRecipients("EXCHANGE_INVALIDATED", Set.of(owner.id(), counterpart.id()));
        assertEquals(id(next), jdbc.queryForObject("SELECT reservation_id FROM notifications WHERE event_type='RESERVATION_CHANGED'", Long.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE exchange_request_id=? AND event_type='EXCHANGE_INVALIDATED'", Integer.class, id(request)));
        exchanges.detail(owner.id(), id(request)); exchanges.refreshPending();
        assertEquals(2, eventCount("EXCHANGE_INVALIDATED"));
    }

    @Test
    void cancellationImmediatelyInvalidatesRelatedRequestsAndFiltersInactiveCounterpart() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ReservationResponse source = personal(owner, TODAY, "B01", "DRUMS");
        ExchangeResponse request = request(owner, source, personal(counterpart, TODAY, "B07", "VOCAL"));
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", counterpart.id());
        reservations.cancel(owner.id(), id(source), key(), new ReservationRequests.Cancel(source.version(), null));
        assertEquals("INVALIDATED", exchangeStatus(request));
        assertRecipients("RESERVATION_CANCELED", Set.of(owner.id()));
        assertRecipients("EXCHANGE_INVALIDATED", Set.of(owner.id()));
    }

    @Test
    void notificationInsertFailureRollsBackReservationClaimsQuotaAndIdempotencyThenSameKeyRetries() {
        Person owner = person("MEMBER");
        var before = snapshot();
        String createKey = key();
        var input = personal(TODAY, "B01", "DRUMS");
        failOn("RESERVATION_CREATED");
        assertThrows(RuntimeException.class, () -> reservations.create(owner.id(), createKey, input));
        assertEquals(before, snapshot());
        removeFailureInjection();
        reservations.create(owner.id(), createKey, input);
        assertEquals(1, count("reservations"));
        assertEquals(1, count("notifications"));
    }

    @Test
    void secondTeamRecipientInsertFailureRollsBackTheFirstInboxItemAndTheWholeReservation() {
        Person author = person("MEMBER"), teammate = person("MEMBER");
        long team = team(); join(team, author, "MEMBER"); join(team, teammate, "MEMBER");
        var before = snapshot();
        String createKey = key();
        var input = new ReservationRequests.Create("TEAM", Long.toString(team), TODAY.toString(), "B07", null);
        failOn("RESERVATION_CREATED", teammate.id());
        assertThrows(RuntimeException.class, () -> reservations.create(author.id(), createKey, input));
        assertEquals(before, snapshot());
        removeFailureInjection();
        reservations.create(author.id(), createKey, input);
        assertEquals(1, count("reservations")); assertEquals(2, count("notifications"));
    }

    @Test
    void authorityStaleVersionFaultAndPlacementFailuresNeverLeaveNotificationsOrPartialChanges() {
        Person owner = person("MEMBER"), other = person("MEMBER");
        ReservationResponse source = personal(owner, TODAY, "B01", "DRUMS");
        personal(other, TODAY, "B03", "BASS");
        var before = snapshot();
        assertThrows(ApiException.class, () -> reservations.cancel(other.id(), id(source), key(), new ReservationRequests.Cancel(source.version(), null)));
        assertEquals(before, snapshot());
        assertThrows(ApiException.class, () -> reservations.change(owner.id(), id(source), key(), new ReservationRequests.Change(source.version() + 1, TODAY.toString(), "B02", List.of("DRUMS"))));
        assertEquals(before, snapshot());
        assertThrows(ApiException.class, () -> reservations.change(owner.id(), id(source), key(), new ReservationRequests.Change(source.version(), TODAY.toString(), "B03", List.of("BASS"))));
        assertEquals(before, snapshot());
        jdbc.update("UPDATE assets SET status='FAULTY',version=version+1 WHERE code='VOCAL'");
        assertThrows(ApiException.class, () -> reservations.create(owner.id(), key(), personal(TODAY, "B04", "VOCAL")));
        assertEquals(before, snapshot());
    }

    @Test
    void lateChangeNotificationFailureRestoresSourceAndPendingExchangeIncludingAllInboxRows() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ReservationResponse source = personal(owner, TODAY, "B01", "DRUMS");
        ExchangeResponse request = request(owner, source, personal(counterpart, TODAY, "B07", "VOCAL"));
        var before = snapshot();
        String changeKey = key();
        var change = new ReservationRequests.Change(source.version(), TODAY.toString(), "B03", List.of("BASS"));
        failOn("EXCHANGE_INVALIDATED");
        assertThrows(RuntimeException.class, () -> reservations.change(owner.id(), id(source), changeKey, change));
        assertEquals(before, snapshot());
        assertEquals("PENDING", exchangeStatus(request));
        removeFailureInjection();
        reservations.change(owner.id(), id(source), changeKey, change);
        assertEquals("INVALIDATED", exchangeStatus(request));
        assertEquals(1, eventCount("RESERVATION_CHANGED"));
        assertEquals(2, eventCount("EXCHANGE_INVALIDATED"));
    }

    @Test
    void exchangeCompletionCreatesOneBusinessEventAndOnlyInvalidatesCompetingRequest() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER"), alternative = person("MEMBER");
        ReservationResponse source = personal(owner, TODAY, "B01", "DRUMS");
        ExchangeResponse request = request(owner, source, personal(counterpart, TODAY, "B07", "VOCAL"));
        ExchangeResponse competitor = request(owner, source, personal(alternative, TODAY, "B08", "BASS"));
        String approveKey = key();
        var action = new ExchangeRequests.Action(request.version());
        ExchangeResponse result = exchanges.approve(counterpart.id(), id(request), approveKey, action);
        assertEquals("COMPLETED", result.status());
        assertEquals("COMPLETED", exchanges.approve(counterpart.id(), id(request), approveKey, action).status());
        assertEquals("INVALIDATED", exchangeStatus(competitor));
        assertRecipients("EXCHANGE_COMPLETED", Set.of(owner.id(), counterpart.id()));
        assertRecipients("EXCHANGE_INVALIDATED", Set.of(owner.id(), alternative.id()));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE exchange_request_id=? AND event_type='EXCHANGE_INVALIDATED'", Integer.class, id(request)));
        assertEquals(3, eventCount("RESERVATION_CREATED"));
        assertEquals(0, eventCount("RESERVATION_CHANGED"));
        assertEquals(0, notificationsFor(administrator.id()));
    }

    @Test
    void exchangeFinalNotificationFailureRollsBackEveryReplacementAndCanRetrySameApprovalKey() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ReservationResponse first = personal(owner, TODAY, "B01", "DRUMS"), second = personal(owner, TODAY, "B02", "BASS");
        ReservationResponse target = personal(counterpart, TODAY, "B07", "VOCAL");
        ExchangeResponse request = exchanges.create(owner.id(), key(), new ExchangeRequests.Create(
                List.of(new ExchangeRequests.Item(first.id(), first.version()), new ExchangeRequests.Item(second.id(), second.version())),
                List.of(new ExchangeRequests.Item(target.id(), target.version())))).response();
        var before = snapshot();
        String approveKey = key();
        var action = new ExchangeRequests.Action(request.version());
        failOn("EXCHANGE_COMPLETED");
        assertThrows(RuntimeException.class, () -> exchanges.approve(counterpart.id(), id(request), approveKey, action));
        assertEquals(before, snapshot());
        removeFailureInjection();
        ExchangeResponse completed = exchanges.approve(counterpart.id(), id(request), approveKey, action);
        assertEquals("COMPLETED", completed.status());
        assertEquals(3, completed.reservations().size());
        assertEquals(3, eventCount("RESERVATION_CREATED"));
        assertEquals(0, eventCount("RESERVATION_CHANGED"));
        assertRecipients("EXCHANGE_COMPLETED", Set.of(owner.id(), counterpart.id()));
        assertEquals(2, eventCount("EXCHANGE_COMPLETED"));
    }

    @Test
    void exchangeRejectAndWithdrawalPublishOnceForRelatedPeople() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ReservationResponse left = personal(owner, TODAY, "B01", "DRUMS"), right = personal(counterpart, TODAY, "B07", "VOCAL");
        ExchangeResponse rejected = request(owner, left, right);
        String rejectKey = key();
        exchanges.reject(counterpart.id(), id(rejected), rejectKey, new ExchangeRequests.Action(rejected.version()));
        exchanges.reject(counterpart.id(), id(rejected), rejectKey, new ExchangeRequests.Action(rejected.version()));
        assertRecipients("EXCHANGE_REJECTED", Set.of(owner.id(), counterpart.id()));
        ExchangeResponse withdrawn = request(owner, left, right);
        String cancelKey = key();
        exchanges.cancel(owner.id(), id(withdrawn), cancelKey, new ExchangeRequests.Action(withdrawn.version()));
        exchanges.cancel(owner.id(), id(withdrawn), cancelKey, new ExchangeRequests.Action(withdrawn.version()));
        assertRecipients("EXCHANGE_WITHDRAWN", Set.of(owner.id(), counterpart.id()));
        assertEquals(2, eventCount("EXCHANGE_REJECTED"));
        assertEquals(2, eventCount("EXCHANGE_WITHDRAWN"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM reservations WHERE status='CONFIRMED'", Integer.class));
    }

    @Test
    void expiredApprovalReturnsConflictAfterCommittingExpiryAndItsNotifications() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ExchangeResponse request = request(owner, personal(owner, TODAY, "B01", "DRUMS"), personal(counterpart, TODAY, "B07", "VOCAL"));
        clock.set("2026-10-30T12:00:00+09:00");
        ApiException error = assertThrows(ApiException.class, () -> exchanges.approve(counterpart.id(), id(request), key(), new ExchangeRequests.Action(request.version())));
        assertEquals("EXCHANGE_EXPIRED", error.code());
        assertEquals("EXPIRED", exchangeStatus(request));
        assertRecipients("EXCHANGE_EXPIRED", Set.of(owner.id(), counterpart.id()));
        exchanges.refreshPending(); exchanges.detail(owner.id(), id(request));
        assertEquals(2, eventCount("EXCHANGE_EXPIRED"));
        assertEquals(0, eventCount("EXCHANGE_COMPLETED"));
    }

    @Test
    void permissionLossRefreshPublishesInvalidationToRemainingActiveRecipientsOnly() {
        Person owner = person("MEMBER"), counterpart = person("MEMBER");
        ExchangeResponse request = request(owner, personal(owner, TODAY, "B01", "DRUMS"), personal(counterpart, TODAY, "B07", "VOCAL"));
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?", owner.id());
        exchanges.refreshPending();
        assertEquals("INVALIDATED", exchangeStatus(request));
        assertRecipients("EXCHANGE_INVALIDATED", Set.of(counterpart.id()));
        exchanges.refreshPending();
        assertEquals(1, eventCount("EXCHANGE_INVALIDATED"));
    }

    @Test
    void unionOfTwoTeamsAndAdministratorRequesterIsDeduplicatedWithoutGrantingApprovalAuthority() {
        Person left = person("MEMBER"), right = person("MEMBER"), shared = person("MEMBER"), leftMember = person("MEMBER"), rightMember = person("MEMBER");
        long a = team(), b = team();
        join(a, left, "MEMBER"); join(a, shared, "MEMBER"); join(a, leftMember, "MEMBER");
        join(b, right, "MEMBER"); join(b, shared, "MEMBER"); join(b, rightMember, "MEMBER");
        ExchangeResponse request = request(administrator, teamReservation(left, a, TODAY, "B01"), teamReservation(right, b, TODAY, "B07"));
        assertRecipients("EXCHANGE_REQUESTED", Set.of(administrator.id(), left.id(), right.id(), shared.id(), leftMember.id(), rightMember.id()));
        assertEquals(6, eventCount("EXCHANGE_REQUESTED"));
        assertFalse(exchanges.detail(shared.id(), id(request)).canApprove());
        ApiException denied = assertThrows(ApiException.class, () -> exchanges.approve(shared.id(), id(request), key(), new ExchangeRequests.Action(request.version())));
        assertEquals(403, denied.status());
        assertEquals("PENDING", exchangeStatus(request));
    }

    @Test
    void recurringMaterializationIsImmediateAndRerunsDoNotDuplicateEventsWhileGroupedEndKeepsEachCancellation() {
        Person member = person("MEMBER"), teammate = person("MEMBER");
        long team = team(); join(team, member, "MEMBER"); join(team, teammate, "MEMBER");
        String seriesKey = key();
        var input = new RecurringRequests.Create(Long.toString(team), 5, "B07", TODAY.toString());
        var assignment = recurring.create(administrator.id(), seriesKey, input).assignment();
        assertEquals(3, count("reservations"));
        assertEquals(6, eventCount("RESERVATION_CREATED"));
        assertRecipients("RESERVATION_CREATED", Set.of(member.id(), teammate.id()));
        recurring.create(administrator.id(), seriesKey, input); opening.openBookableWindow(); opening.prepareRange(TODAY, TODAY.plusDays(14));
        assertEquals(6, eventCount("RESERVATION_CREATED"));
        recurring.end(administrator.id(), Long.parseLong(assignment.id()), key(), new RecurringRequests.End(assignment.version(), TODAY.toString()));
        assertEquals(4, eventCount("RESERVATION_CANCELED"));
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT event_key) FROM notifications WHERE event_type='RESERVATION_CANCELED'", Integer.class));
        assertEquals(0, notificationsFor(administrator.id()));
    }

    @Test
    void recurringEndInvalidatesAffectedPendingExchangeAndItsNotificationFailureRollsEverythingBack() {
        Person member = person("MEMBER"), counterpart = person("MEMBER");
        // Fixed occurrences preserve the administrator creator; the team leader may request their exchange.
        long team = team(); join(team, member, "LEADER");
        var assignment = recurring.create(administrator.id(), key(), new RecurringRequests.Create(Long.toString(team), 5, "B07", TODAY.toString())).assignment();
        long sourceId = jdbc.queryForObject("SELECT id FROM reservations WHERE reservation_date=?", Long.class, TODAY.plusDays(7));
        ReservationResponse source = reservations.detail(member.id(), sourceId);
        ExchangeResponse request = request(member, source, personal(counterpart, TODAY.plusDays(7), "B08", "VOCAL"));
        var before = snapshot();
        String endKey = key();
        var end = new RecurringRequests.End(assignment.version(), TODAY.toString());
        failOn("EXCHANGE_INVALIDATED");
        assertThrows(RuntimeException.class, () -> recurring.end(administrator.id(), Long.parseLong(assignment.id()), endKey, end));
        assertEquals(before, snapshot());
        removeFailureInjection();
        recurring.end(administrator.id(), Long.parseLong(assignment.id()), endKey, end);
        assertEquals("INVALIDATED", exchangeStatus(request));
        assertRecipients("EXCHANGE_INVALIDATED", Set.of(member.id(), counterpart.id()));
    }

    @Test
    void concurrentIdenticalCreateRetriesCommitOneReservationAndOneInboxItem() throws Exception {
        Person owner = person("MEMBER");
        String createKey = key();
        var input = personal(TODAY, "B01", "DRUMS");
        CountDownLatch start = new CountDownLatch(1);
        try (Connection lock = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                var executor = Executors.newFixedThreadPool(2)) {
            lock.setAutoCommit(false);
            try (var statement = lock.prepareStatement("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE")) { statement.executeQuery(); }
            var first = executor.submit(() -> { start.await(); return reservations.create(owner.id(), createKey, input); });
            var second = executor.submit(() -> { start.await(); return reservations.create(owner.id(), createKey, input); });
            start.countDown();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'", Integer.class) == 2);
            lock.commit();
            assertEquals(first.get(25, TimeUnit.SECONDS).reservation().id(), second.get(25, TimeUnit.SECONDS).reservation().id());
        }
        assertEquals(1, count("reservations")); assertEquals(1, count("notifications"));
        assertEquals(1, count("idempotency_requests"));
    }

    private Person person(String role) {
        Member member = members.insert("inbox-event-" + key().substring(0, 16), "synthetic-unused-password-hash", "합성 알림 회원", role, clock.instant()).orElseThrow();
        long roster = jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 알림 명단',?) RETURNING id", Long.class, member.id());
        return new Person(member.id(), roster);
    }
    private long team() { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 알림 조','REGULAR') RETURNING id", Long.class); }
    private void join(long team, Person person, String role) { jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,?)", team, person.rosterId(), role); }
    private ReservationRequests.Create personal(LocalDate date, String block, String code) { return new ReservationRequests.Create("PERSONAL", null, date.toString(), block, List.of(code)); }
    private ReservationResponse personal(Person owner, LocalDate date, String block, String code) { return reservations.create(owner.id(), key(), personal(date, block, code)).reservation(); }
    private ReservationResponse teamReservation(Person creator, long team, LocalDate date, String block) {
        return reservations.create(creator.id(), key(), new ReservationRequests.Create("TEAM", Long.toString(team), date.toString(), block, null)).reservation();
    }
    private ExchangeResponse request(Person requester, ReservationResponse offered, ReservationResponse requested) {
        return exchanges.create(requester.id(), key(), new ExchangeRequests.Create(List.of(new ExchangeRequests.Item(offered.id(), offered.version())),
                List.of(new ExchangeRequests.Item(requested.id(), requested.version())))).response();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private int eventCount(String type) { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type=?", Integer.class, type); }
    private int notificationsFor(long user) { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?", Integer.class, user); }
    private String exchangeStatus(ExchangeResponse request) { return jdbc.queryForObject("SELECT status FROM exchange_requests WHERE id=?", String.class, id(request)); }
    private void assertRecipients(String type, Set<Long> expected) {
        assertEquals(expected, Set.copyOf(jdbc.queryForList("SELECT DISTINCT user_id FROM notifications WHERE event_type=?", Long.class, type)));
    }
    private Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : STATE_TABLES) {
            result.put(table, jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM " + table + " t", String.class));
        }
        return result;
    }
    private void failOn(String type) {
        failOn(type, null);
    }
    private void failOn(String type, Long userId) {
        jdbc.execute("CREATE FUNCTION notification_event_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='" + type
                + "'" + (userId == null ? "" : " AND NEW.user_id=" + userId) + " THEN RAISE EXCEPTION 'synthetic inbox insert failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER notification_event_test_failure BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION notification_event_test_failure()");
    }
    private static long id(ReservationResponse value) { return Long.parseLong(value.id()); }
    private static long id(ExchangeResponse value) { return Long.parseLong(value.id()); }
    private static String key() { return UUID.randomUUID().toString(); }
    private record Person(long id, long rosterId) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class TestTime { @Bean @Primary TestClock notificationEventTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        TestClock() { this(new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z")), ZoneId.of("UTC")); }
        private TestClock(AtomicReference<Instant> now, ZoneId zone) { this.now = now; this.zone = zone; }
        void set(String offsetDateTime) { now.set(java.time.OffsetDateTime.parse(offsetDateTime).toInstant()); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId value) { return new TestClock(now, value); }
        @Override public Instant instant() { return now.get(); }
    }
}
