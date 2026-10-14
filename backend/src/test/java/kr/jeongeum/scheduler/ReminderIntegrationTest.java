package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.exchange.api.ExchangeRequests;
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.notification.service.ReminderProcessor;
import kr.jeongeum.scheduler.recurring.api.RecurringRequests;
import kr.jeongeum.scheduler.recurring.service.RecurringOpeningService;
import kr.jeongeum.scheduler.recurring.service.RecurringService;
import kr.jeongeum.scheduler.reservation.api.ReservationRequests;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.service.ReservationService;
import org.junit.jupiter.api.*;
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

/** Faults, clock control, and bookings use only this disposable PostgreSQL. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "jeongeum.recurring.worker.enabled=false","jeongeum.exchange.worker.enabled=false",
        "jeongeum.push.worker.enabled=false","jeongeum.reminder.worker.enabled=false"})
@Import(ReminderIntegrationTest.Time.class)
@Timeout(90)
class ReminderIntegrationTest {
    private static final LocalDate TODAY=LocalDate.of(2026,10,30);
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("reminder_test").withUsername("reminder_test").withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofMinutes(2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL",POSTGRES::getJdbcUrl); registry.add("APP_DB_USERNAME",POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD",POSTGRES::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired ReservationService reservations;
    @Autowired ExchangeService exchanges;
    @Autowired RecurringService recurring;
    @Autowired RecurringOpeningService opening;
    @Autowired ReminderProcessor processor;
    @Autowired TestClock clock;
    private Person admin;
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE booking_days,teams,roster_entries,invitation_codes,users,spring_session RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE assets SET status='NORMAL',version=0"); clock.set("2026-10-30T11:00:00+09:00");
        admin=person("GLOBAL_ADMIN"); opening.prepareRange(TODAY,TODAY.plusDays(14));
    }
    @AfterEach void removeFailure() {
        jdbc.execute("DROP TRIGGER IF EXISTS reminder_test_failure ON notification_jobs");
        jdbc.execute("DROP FUNCTION IF EXISTS reminder_test_failure()");
        jdbc.execute("DROP TRIGGER IF EXISTS reminder_inbox_failure ON notifications");
        jdbc.execute("DROP FUNCTION IF EXISTS reminder_inbox_failure()");
    }
    @Test void futurePlanIsInvisibleUntilDueThenExactlyOneInboxEvent() {
        Person owner=person("MEMBER"); var booking=personal(owner,"B07","DRUMS");
        assertEquals(1,plans("PENDING")); assertEquals(0,reminders()); assertEquals(0,processor.drain(50));
        clock.set("2026-10-30T16:59:59+09:00"); assertEquals(0,processor.drain(50));
        clock.set("2026-10-30T17:00:00+09:00"); assertEquals(1,processor.drain(50));
        assertEquals(1,reminders()); assertEquals(1,plans("COMPLETED")); assertEquals(0,processor.drain(50));
        assertEquals(id(booking),jdbc.queryForObject("SELECT reservation_id FROM notifications WHERE event_type='START_REMINDER'",Long.class));
    }
    @Test void passedOrExactlyCurrentDueNeverRegistersRetroactivePlan() {
        clock.set("2026-10-30T17:00:00+09:00"); personal(person("MEMBER"),"B07","DRUMS");
        clock.set("2026-10-30T17:30:00+09:00"); personal(person("MEMBER"),"B07","VOCAL");
        assertEquals(0,jobs()); assertEquals(2,created()); assertEquals(0,reminders());
    }
    @Test void lateWorkerBeforeStartCatchesUpButAfterStartCancels() {
        personal(person("MEMBER"),"B07","DRUMS"); personal(person("MEMBER"),"B08","VOCAL");
        clock.set("2026-10-30T17:40:00+09:00"); processor.drain(50); assertEquals(1,reminders());
        clock.set("2026-10-30T19:30:00+09:00"); processor.drain(50);
        assertEquals(1,reminders()); assertEquals(1,plans("CANCELED"));
    }
    @Test void replayAndNoOpDoNotDuplicatePlansOrCreatedEvents() {
        Person owner=person("MEMBER"); String key=key(); var input=input("B07","DRUMS");
        var booking=reservations.create(owner.id(),key,input).reservation(); reservations.create(owner.id(),key,input);
        reservations.change(owner.id(),id(booking),key(),new ReservationRequests.Change(booking.version(),TODAY.toString(),"B07",List.of("DRUMS")));
        assertEquals(1,jobs()); assertEquals(1,created());
    }
    @Test void sameStartInstrumentChangeReusesPlanAndLogicalRoots() {
        Person owner=person("MEMBER"); var original=personal(owner,"B07","DRUMS"); long plan=jobId(id(original));
        var changed=change(owner,original,"B07","BASS");
        assertEquals(plan,jobId(id(changed))); assertEquals(1,jobs());
        assertEquals("{"+id(original)+"}",roots(jobId(id(changed))));
        clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50); assertEquals(1,reminders());
    }
    @Test void changingStartCancelsOldPlanAndMovingBackReusesUnsentIdentity() {
        Person owner=person("MEMBER"); var original=personal(owner,"B07","DRUMS"); long first=jobId(id(original));
        var moved=change(owner,original,"B08","DRUMS"); assertEquals(1,plans("CANCELED"));
        var returned=change(owner,moved,"B07","DRUMS"); assertEquals(first,jobId(id(returned)));
        assertEquals(1,plans("PENDING")); assertEquals(1,plans("CANCELED"));
        clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50); assertEquals(1,reminders());
    }
    @Test void alreadySentReminderIsNotRepeatedAfterSameTimeChange() {
        Person owner=person("MEMBER"); var original=personal(owner,"B07","DRUMS");
        clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50);
        clock.set("2026-10-30T17:10:00+09:00"); change(owner,original,"B07","BASS");
        processor.drain(50); assertEquals(1,reminders()); assertEquals(1,plans("COMPLETED"));
    }
    @Test void cancellationAndRepeatedCancellationNeverResurrectPlan() {
        Person owner=person("MEMBER"); var original=personal(owner,"B07","DRUMS");
        var canceled=reservations.cancel(owner.id(),id(original),key(),new ReservationRequests.Cancel(original.version(),null)).reservation();
        reservations.cancel(owner.id(),id(canceled),key(),new ReservationRequests.Cancel(canceled.version(),null));
        processor.recoverFuturePlans(); clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50);
        assertEquals(1,plans("CANCELED")); assertEquals(0,reminders());
    }
    @Test void teamRecipientsAreRecomputedAtDueAndCreatorInactivityDoesNotCancelSurvivingTeam() {
        Person author=person("MEMBER"), left=person("MEMBER"), joined=person("MEMBER"); long team=team();
        join(team,author); join(team,left);
        reservations.create(author.id(),key(),new ReservationRequests.Create("TEAM",Long.toString(team),TODAY.toString(),"B07",null));
        jdbc.update("UPDATE team_memberships SET status='REMOVED' WHERE team_id=? AND roster_entry_id=?",team,left.roster());
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?",author.id()); join(team,joined);
        clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50);
        assertEquals(Set.of(joined.id()),Set.copyOf(jdbc.queryForList("SELECT user_id FROM notifications WHERE event_type='START_REMINDER'",Long.class)));
    }
    @Test void inactivePersonalAndDisbandedTeamPlansAreCanceled() {
        Person owner=person("MEMBER"), author=person("MEMBER"); long team=team(); join(team,author);
        personal(owner,"B07","DRUMS");
        reservations.create(author.id(),key(),new ReservationRequests.Create("TEAM",Long.toString(team),TODAY.toString(),"B08",null));
        jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?",owner.id()); jdbc.update("UPDATE teams SET status='DISBANDED' WHERE id=?",team);
        clock.set("2026-10-30T18:30:00+09:00"); processor.drain(50);
        assertEquals(0,reminders()); assertEquals(2,plans("CANCELED"));
    }
    @Test void planInsertFailureRollsBackBusinessInboxAndIdempotency() {
        Person owner=person("MEMBER");
        jdbc.execute("CREATE FUNCTION reminder_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.job_kind='REMINDER_PLAN' THEN RAISE EXCEPTION 'synthetic plan failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reminder_test_failure BEFORE INSERT ON notification_jobs FOR EACH ROW EXECUTE FUNCTION reminder_test_failure()");
        String key=key(); assertThrows(RuntimeException.class,()->reservations.create(owner.id(),key,input("B07","DRUMS")));
        assertEquals(0,count("reservations")); assertEquals(0,count("reservation_assets")); assertEquals(0,count("notifications")); assertEquals(0,count("idempotency_requests"));
        removeFailure(); reservations.create(owner.id(),key,input("B07","DRUMS")); assertEquals(1,jobs());
    }
    @Test void reminderInboxFailureRollsBackAndRetriesWithoutDuplicate() {
        personal(person("MEMBER"),"B07","DRUMS");
        jdbc.execute("CREATE FUNCTION reminder_inbox_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.event_type='START_REMINDER' THEN RAISE EXCEPTION 'synthetic reminder failure'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER reminder_inbox_failure BEFORE INSERT ON notifications FOR EACH ROW EXECUTE FUNCTION reminder_inbox_failure()");
        clock.set("2026-10-30T17:00:00+09:00"); assertEquals(0,processor.drain(50));
        assertEquals(0,reminders()); assertEquals(1,plans("RETRY_WAIT")); removeFailure();
        clock.set("2026-10-30T17:00:31+09:00"); assertEquals(1,processor.drain(50)); assertEquals(1,reminders());
    }
    @Test void twoWorkersCommitOneReminderUsingActualPostgreSqlGuard() throws Exception {
        personal(person("MEMBER"),"B07","DRUMS"); clock.set("2026-10-30T17:00:00+09:00");
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->{start.await(); return processor.drain(50);});
            var second=executor.submit(()->{start.await(); return processor.drain(50);}); start.countDown();
            assertEquals(1,first.get(20,TimeUnit.SECONDS)+second.get(20,TimeUnit.SECONDS));
        }
        assertEquals(1,reminders()); assertEquals(1,plans("COMPLETED"));
    }
    @Test void startupRecoveryAddsOnlyFuturePlansAndPreservesRetryAndCompletedHistory() {
        personal(person("MEMBER"),"B07","DRUMS"); personal(person("MEMBER"),"B08","VOCAL");
        jdbc.update("DELETE FROM notification_jobs WHERE actual_start_at=(SELECT min(actual_start_at) FROM notification_jobs)");
        jdbc.update("UPDATE notification_jobs SET status='RETRY_WAIT',attempts=2,last_error_code='SYNTHETIC'");
        processor.recoverFuturePlans(); assertEquals(2,jobs()); assertEquals(1,plans("RETRY_WAIT")); assertEquals(2,created());
        processor.recoverFuturePlans(); assertEquals(2,jobs());
    }
    @Test void expiredProcessingLeaseRecoversWhileCanceledLeaseStaysCanceled() {
        personal(person("MEMBER"),"B07","DRUMS"); personal(person("MEMBER"),"B08","VOCAL");
        jdbc.update("UPDATE notification_jobs SET status='PROCESSING',lease_token=?::uuid,lease_expires_at=scheduled_at-interval '1 second'",key());
        jdbc.update("UPDATE notification_jobs SET status='CANCELED',canceled_at=created_at WHERE actual_start_at=(SELECT max(actual_start_at) FROM notification_jobs)");
        clock.set("2026-10-30T18:30:00+09:00"); processor.drain(50);
        assertEquals(0,reminders()); assertEquals(2,plans("CANCELED"));
    }
    @Test void recurringGenerationAndEndPlanOnlyRealOccurrencesWithoutRecreation() {
        Person member=person("MEMBER"); long team=team(); join(team,member);
        var series=recurring.create(admin.id(),key(),new RecurringRequests.Create(Long.toString(team),5,"B07",TODAY.toString())).assignment();
        assertEquals(3,plans("PENDING")); opening.openBookableWindow(); assertEquals(3,jobs());
        recurring.end(admin.id(),Long.parseLong(series.id()),key(),new RecurringRequests.End(series.version(),TODAY.toString()));
        assertEquals(1,plans("PENDING")); assertEquals(2,plans("CANCELED"));
    }
    @Test void bundledExchangePreservesSortedRootSetsAndDoesNotEmitSuccessorCreatedEvents() {
        Person owner=person("MEMBER"), other=person("MEMBER"); var first=personal(owner,"B01","DRUMS");
        var second=personal(owner,"B02","DRUMS"); var target=personal(other,"B07","VOCAL");
        var request=exchanges.create(owner.id(),key(),new ExchangeRequests.Create(List.of(item(first),item(second)),List.of(item(target)))).response();
        var approved=exchanges.approve(other.id(),Long.parseLong(request.id()),key(),new ExchangeRequests.Action(request.version()));
        assertEquals("COMPLETED",approved.status()); assertEquals(3,created());
        long merged=jdbc.queryForObject("SELECT id FROM reservations WHERE owner_user_id=? AND status='CONFIRMED'",Long.class,owner.id());
        assertEquals("{"+id(first)+","+id(second)+"}",roots(jobId(merged)));
        assertEquals(2,plans("PENDING"));
        clock.set("2026-10-30T17:00:00+09:00"); processor.drain(50); assertEquals(1,reminders());
    }
    private Person person(String role) {
        var member=members.insert("reminder-"+key().substring(0,16),"synthetic-unused-password-hash","합성 알림 회원",role,clock.instant()).orElseThrow();
        long roster=jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 명단',?) RETURNING id",Long.class,member.id());
        return new Person(member.id(),roster);
    }
    private long team() { return jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 알림 조','REGULAR') RETURNING id",Long.class); }
    private void join(long team,Person person) { jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,'MEMBER')",team,person.roster()); }
    private ReservationRequests.Create input(String block,String instrument) { return new ReservationRequests.Create("PERSONAL",null,TODAY.toString(),block,List.of(instrument)); }
    private ReservationResponse personal(Person owner,String block,String instrument) { return reservations.create(owner.id(),key(),input(block,instrument)).reservation(); }
    private ReservationResponse change(Person owner,ReservationResponse original,String block,String instrument) {
        return reservations.change(owner.id(),id(original),key(),new ReservationRequests.Change(original.version(),TODAY.toString(),block,List.of(instrument))).reservations().getFirst();
    }
    private ExchangeRequests.Item item(ReservationResponse reservation) { return new ExchangeRequests.Item(reservation.id(),reservation.version()); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private int jobs() { return count("notification_jobs"); }
    private int plans(String status) { return jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE job_kind='REMINDER_PLAN' AND status=?",Integer.class,status); }
    private int reminders() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type='START_REMINDER'",Integer.class); }
    private int created() { return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE event_type='RESERVATION_CREATED'",Integer.class); }
    private long jobId(long reservation) { return jdbc.queryForObject("SELECT id FROM notification_jobs WHERE reservation_id=? AND job_kind='REMINDER_PLAN'",Long.class,reservation); }
    private String roots(long job) { return jdbc.queryForObject("SELECT logical_root_ids::text FROM notification_jobs WHERE id=?",String.class,job); }
    private static long id(ReservationResponse reservation) { return Long.parseLong(reservation.id()); }
    private static String key() { return UUID.randomUUID().toString(); }
    private record Person(long id,long roster) {}
    @TestConfiguration(proxyBeanMethods=false) static class Time { @Bean @Primary TestClock reminderTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z"));
        void set(String value) { now.set(java.time.OffsetDateTime.parse(value).toInstant()); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
