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
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.notification.repository.NotificationRepository;
import kr.jeongeum.scheduler.notification.service.ReminderProcessor;
import kr.jeongeum.scheduler.recurring.service.RecurringOpeningService;
import kr.jeongeum.scheduler.reservation.api.ReservationRequests;
import kr.jeongeum.scheduler.reservation.service.ReservationService;
import kr.jeongeum.scheduler.exchange.api.ExchangeRequests;
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
import kr.jeongeum.scheduler.push.api.PushRequests;
import kr.jeongeum.scheduler.push.api.PushSubscriptionResponse;
import kr.jeongeum.scheduler.push.domain.PushDelivery;
import kr.jeongeum.scheduler.push.service.PushDeliveryProcessor;
import kr.jeongeum.scheduler.push.service.PushOutbox;
import kr.jeongeum.scheduler.push.service.PushSender;
import kr.jeongeum.scheduler.push.service.PushSubscriptionService;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** All mutable data, deliberate failures, and clock control use only disposable PostgreSQL; sends are fake. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "jeongeum.recurring.worker.enabled=false","jeongeum.exchange.worker.enabled=false",
        "jeongeum.push.worker.enabled=false","jeongeum.reminder.worker.enabled=false"})
@Import(PushIntegrationTest.Fakes.class)
@Timeout(90)
class PushIntegrationTest {
    private static final String SUBS="/api/v1/push-subscriptions";
    private static final String PUBLIC_KEY=Base64.getUrlEncoder().withoutPadding().encodeToString(HexFormat.of().parseHex(
            "046b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c2964fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5"));
    private static final String AUTH=Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
    @Container static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("push_test").withUsername("push_test").withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofMinutes(2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL",POSTGRES::getJdbcUrl); registry.add("APP_DB_USERNAME",POSTGRES::getUsername); registry.add("APP_DB_PASSWORD",POSTGRES::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @Autowired FakeSender sender;
    @Autowired PushSubscriptionService subscriptions;
    @Autowired PushDeliveryProcessor processor;
    @Autowired PushOutbox outbox;
    @Autowired NotificationRepository inbox;
    @Autowired ReservationService reservations;
    @Autowired RecurringOpeningService opening;
    @Autowired ReminderProcessor reminders;
    @Autowired ExchangeService exchanges;
    @Autowired PlatformTransactionManager manager;
    @Autowired DirectoryMutationGuard guard;
    private Person owner;
    private String password,passwordHash;
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE booking_days,teams,roster_entries,invitation_codes,users,spring_session RESTART IDENTITY CASCADE");
        clock.set("2026-10-30T02:00:00Z"); sender.reset(); password=UUID.randomUUID().toString(); passwordHash=encoder.encode(password); owner=person();
    }
    @Test void publicKeyDefaultsDisabledAndAllSubscriptionApisRequireAuthenticationAndCsrf() throws Exception {
        JsonNode key=success(owner.client().get("/api/v1/push/public-key"),200); assertFalse(key.get("enabled").asBoolean()); assertTrue(key.get("publicKey").isNull());
        Client anonymous=new Client();
        for (String path:List.of(SUBS,"/api/v1/push/public-key")) { assertError(anonymous.get(path),401,"AUTHENTICATION_REQUIRED"); }
        assertError(owner.client().send("POST",SUBS,body("a"),null),403,"CSRF_INVALID");
        assertError(owner.client().send("DELETE",SUBS+"/1",null,null),403,"CSRF_INVALID"); assertEquals(0,count("push_subscriptions"));
    }
    @Test void registerReturnsOnlySafeFieldsListsCurrentDeviceAndIdempotentlyUpdatesName() throws Exception {
        JsonNode first=success(owner.client().write("POST",SUBS,body("a")),201);
        assertTrue(first.get("active").asBoolean()); assertTrue(first.get("currentDevice").asBoolean()); assertEquals("0",first.get("bindingVersion").asString());
        Map<String,Object> renamed=new HashMap<>(body("a")); renamed.put("deviceName","  합성 스마트폰  ");
        JsonNode next=success(owner.client().write("POST",SUBS,renamed),201);
        assertEquals(first.get("id"),next.get("id")); assertEquals(first.get("bindingVersion"),next.get("bindingVersion")); assertEquals("합성 스마트폰",next.get("deviceName").asString());
        JsonNode listed=success(owner.client().get(SUBS),200).get("subscriptions"); assertEquals(1,listed.size()); assertEquals(next,listed.get(0)); assertEquals(1,count("push_subscriptions"));
    }
    @Test void anotherDeviceIsVisibleButNotCurrentAndOnlyOwnedSubscriptionsCanBeDeleted() throws Exception {
        JsonNode first=success(owner.client().write("POST",SUBS,body("a")),201); Client second=login(owner.member());
        JsonNode other=success(second.write("POST",SUBS,body("b")),201);
        JsonNode list=success(owner.client().get(SUBS),200).get("subscriptions"); assertEquals(2,list.size());
        assertFalse(list.valueStream().filter(n -> n.get("id").equals(other.get("id"))).findFirst().orElseThrow().get("currentDevice").asBoolean());
        Person intruder=person(); assertError(intruder.client().write("DELETE",SUBS+"/"+first.get("id").asString(),null),404,"RESOURCE_NOT_FOUND");
        success(owner.client().write("DELETE",SUBS+"/"+first.get("id").asString(),null),204);
        success(owner.client().write("DELETE",SUBS+"/"+first.get("id").asString(),null),204);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE active",Integer.class));
    }
    @Test void logoutDisablesOnlyTheCurrentSessionDeviceAndCancelsItsPendingJobs() throws Exception {
        JsonNode first=success(owner.client().write("POST",SUBS,body("a")),201); Client second=login(owner.member());
        JsonNode other=success(second.write("POST",SUBS,body("b")),201); notification(owner.member().id(),"logout-event"); assertEquals(2,jobs("PENDING"));
        success(owner.client().write("POST","/api/v1/auth/logout",null),204);
        assertFalse(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,first.get("id").asLong()));
        assertTrue(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,other.get("id").asLong()));
        assertEquals(1,jobs("CANCELED")); assertEquals(1,jobs("PENDING")); assertError(owner.client().get(SUBS),401,"AUTHENTICATION_REQUIRED");
        drain(); assertEquals(1,sender.deliveries.size()); assertEquals(other.get("id").asLong(),sender.deliveries.getFirst().subscriptionId());
    }
    @Test void registrationAuthenticatedBeforeLogoutCannotReactivateAfterWaitingForRevocationCommit() throws Exception {
        var first=register(owner.member().id(),"stale-session","a"); var other=register(owner.member().id(),"other-session","b");
        notification(owner.member().id(),"logout-race"); CountDownLatch revoked=new CountDownLatch(1),commit=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var logout=executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                guard.lock(); subscriptions.logout(owner.member().id(),"stale-session"); revoked.countDown();
                try { assertTrue(commit.await(10,TimeUnit.SECONDS)); } catch(InterruptedException failure) { Thread.currentThread().interrupt(); throw new RuntimeException(failure); }
            }));
            assertTrue(revoked.await(10,TimeUnit.SECONDS));
            // This service invocation carries the identity/session captured by an earlier HTTP authentication.
            var stale=executor.submit(() -> subscriptions.register(owner.member().id(),"stale-session",new PushRequests.Register(endpoint("a"),new PushRequests.Keys(PUBLIC_KEY,AUTH),"합성 기기")));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'",Integer.class)>=1);
            commit.countDown(); logout.get(10,TimeUnit.SECONDS);
            var rejected=assertThrows(java.util.concurrent.ExecutionException.class,()->stale.get(10,TimeUnit.SECONDS));
            assertInstanceOf(kr.jeongeum.scheduler.common.api.ApiException.class,rejected.getCause());
            assertEquals("AUTHENTICATION_REQUIRED",((kr.jeongeum.scheduler.common.api.ApiException)rejected.getCause()).code());
        }
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id='stale-session'",Integer.class));
        assertFalse(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,Long.parseLong(first.id())));
        assertTrue(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,Long.parseLong(other.id())));
        assertEquals(1,pushJobs("CANCELED")); assertEquals(1,pushJobs("PENDING"));
    }
    @Test void registrationRejectsExpiredMissingOrDifferentAccountSessionsInsideItsGuard() {
        seedSession(owner.member().id(),"expired-session"); jdbc.update("UPDATE spring_session SET expiry_time=? WHERE session_id='expired-session'",clock.instant().toEpochMilli());
        PushRequests.Register input=new PushRequests.Register(endpoint("a"),new PushRequests.Keys(PUBLIC_KEY,AUTH),"합성 기기");
        for(String session:List.of("expired-session","missing-session")) {
            var rejected=assertThrows(kr.jeongeum.scheduler.common.api.ApiException.class,()->subscriptions.register(owner.member().id(),session,input)); assertEquals("AUTHENTICATION_REQUIRED",rejected.code());
        }
        seedSession(owner.member().id()+100,"different-account");
        var rejected=assertThrows(kr.jeongeum.scheduler.common.api.ApiException.class,()->subscriptions.register(owner.member().id(),"different-account",input)); assertEquals("AUTHENTICATION_REQUIRED",rejected.code());
        assertEquals(0,count("push_subscriptions"));
    }
    @Test void sameEndpointAccountTransferRebindsCancelsOldJobsAndDoesNotBackfillPreviousInbox() throws Exception {
        PushSubscriptionResponse old=register(owner.member().id(),"first","a"); notification(owner.member().id(),"old-event"); Person next=person();
        PushSubscriptionResponse rebound=register(next.member().id(),"new-session","a");
        assertEquals(old.id(),rebound.id()); assertEquals("1",rebound.bindingVersion()); assertEquals(1,jobs("CANCELED"));
        assertEquals(0,subscriptions.list(owner.member().id(),"first").size()); assertEquals(1,subscriptions.list(next.member().id(),"new-session").size());
        notification(next.member().id(),"new-event"); drain(); assertEquals(1,sender.deliveries.size()); assertEquals(1,sender.deliveries.getFirst().bindingVersion());
    }
    @Test void reenableAndSessionRotationAdvanceBindingButOrdinaryReplayPreservesIt() {
        var first=register(owner.member().id(),"first","a"); subscriptions.delete(owner.member().id(),Long.parseLong(first.id()));
        var reenabled=register(owner.member().id(),"first","a"); assertEquals("2",reenabled.bindingVersion());
        var rotated=register(owner.member().id(),"second","a"); assertEquals("3",rotated.bindingVersion());
        assertEquals("3",register(owner.member().id(),"second","a").bindingVersion());
    }
    @Test void transferRequiresOriginalBrowserKeysAndUnknownUserFieldCannotSetOwner() throws Exception {
        register(owner.member().id(),"first","a"); Person next=person(); Map<String,Object> bad=new HashMap<>(body("a"));
        byte[] different=new byte[16]; different[0]=1; bad.put("keys",Map.of("p256dh",PUBLIC_KEY,"auth",Base64.getUrlEncoder().withoutPadding().encodeToString(different)));
        assertError(next.client().write("POST",SUBS,bad),409,"PUSH_SUBSCRIPTION_CONFLICT");
        bad=new HashMap<>(body("b")); bad.put("userId",owner.member().id()); assertError(next.client().write("POST",SUBS,bad),400,"INVALID_INPUT");
        assertEquals(owner.member().id(),jdbc.queryForObject("SELECT user_id FROM push_subscriptions",Long.class));
    }
    @Test void invalidEndpointsKeysAndNamesNeverCreateSubscriptionsOrLeakSecrets() throws Exception {
        for (String endpoint:List.of("http://fcm.googleapis.com/x","https://127.0.0.1/x","https://localhost/x","https://192.168.1.1/x",
                "https://fcm.googleapis.com.attacker.test/x","https://fcm.googleapis.com@localhost/x","https://fcm.googleapis.com:444/x",
                "https://fcm.googleapis.com/x?q=secret","https://fcm.googleapis.com/x#secret","https://fcm.googleapis.com/")) {
            Map<String,Object> input=new HashMap<>(body("a")); input.put("endpoint",endpoint); assertError(owner.client().write("POST",SUBS,input),400,"INVALID_INPUT");
        }
        for (Object keys:List.of(Map.of("p256dh","bad","auth",AUTH),Map.of("p256dh",PUBLIC_KEY,"auth","bad"),
                Map.of("p256dh",Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]),"auth",AUTH))) {
            Map<String,Object> input=new HashMap<>(body("a")); input.put("keys",keys); assertError(owner.client().write("POST",SUBS,input),400,"INVALID_INPUT");
        }
        for (String name:List.of(" ","x".repeat(81),"bad\u0001name")) { Map<String,Object> input=new HashMap<>(body("a")); input.put("deviceName",name); assertError(owner.client().write("POST",SUBS,input),400,"INVALID_INPUT"); }
        assertEquals(0,count("push_subscriptions"));
    }
    @Test void inactiveMemberCannotRegisterReadOrDelete() throws Exception {
        success(owner.client().write("POST",SUBS,body("a")),201); jdbc.update("UPDATE users SET status='INACTIVE' WHERE id=?",owner.member().id());
        assertError(owner.client().get(SUBS),403,"INACTIVE_USER"); assertError(owner.client().get("/api/v1/push/public-key"),401,"AUTHENTICATION_REQUIRED");
        assertError(owner.client().write("POST",SUBS,body("b")),401,"AUTHENTICATION_REQUIRED");
        var rejected=assertThrows(kr.jeongeum.scheduler.common.api.ApiException.class,()->subscriptions.delete(owner.member().id(),1)); assertEquals("INACTIVE_USER",rejected.code());
        assertEquals(1,count("push_subscriptions"));
    }
    @Test void activeDeviceLimitCanBeFreedWithoutDeletingHistory() {
        for (int i=0;i<20;i++) { register(owner.member().id(),"device-"+i,"device-"+i); }
        var failure=assertThrows(kr.jeongeum.scheduler.common.api.ApiException.class,()->register(owner.member().id(),"extra","extra")); assertEquals("PUSH_DEVICE_LIMIT",failure.code());
        subscriptions.delete(owner.member().id(),1); register(owner.member().id(),"extra","extra"); assertEquals(21,count("push_subscriptions"));
    }
    @Test void concurrentSameEndpointRegistrationCreatesOneBindingUnderActualPostgreSqlGuard() throws Exception {
        CountDownLatch start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(() -> { start.await(); return register(owner.member().id(),"same-session","same"); });
            var second=executor.submit(() -> { start.await(); return register(owner.member().id(),"same-session","same"); }); start.countDown();
            var a=first.get(15,TimeUnit.SECONDS); var b=second.get(15,TimeUnit.SECONDS);
            assertEquals(a.id(),b.id()); assertEquals("0",a.bindingVersion()); assertEquals(a.bindingVersion(),b.bindingVersion());
        }
        assertEquals(1,count("push_subscriptions"));
    }
    @Test void outboxIsAtomicIdempotentAndDoesNotBackfillOnLaterRegistration() {
        long first=notification(owner.member().id(),"before-subscription"); register(owner.member().id(),"first","a"); register(owner.member().id(),"second","b");
        assertEquals(0,count("notification_jobs")); long second=notification(owner.member().id(),"after-subscription");
        new TransactionTemplate(manager).executeWithoutResult(status -> outbox.enqueue(second,clock.instant()));
        assertEquals(2,count("notification_jobs")); assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE notification_id=?",Integer.class,first));
        assertThrows(RuntimeException.class,()->new TransactionTemplate(manager).executeWithoutResult(status -> { notification(owner.member().id(),"rolled-back"); throw new IllegalStateException("synthetic rollback"); }));
        assertEquals(2,count("notifications")); assertEquals(2,count("notification_jobs")); assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,()->outbox.enqueue(second,clock.instant()));
    }
    @Test void deliveryInsertFailureRollsBackActualImportantNoticeAndItsInboxThenRetryIsSafe() throws Exception {
        register(owner.member().id(),"first","a"); jdbc.update("UPDATE users SET global_role='GLOBAL_ADMIN' WHERE id=?",owner.member().id());
        jdbc.execute("CREATE FUNCTION push_test_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic outbox failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER push_test_failure BEFORE INSERT ON notification_jobs FOR EACH ROW EXECUTE FUNCTION push_test_failure()");
        try {
            JsonNode token=success(owner.client().get("/api/v1/auth/csrf"),200);
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1/notices")).timeout(Duration.ofSeconds(20))
                    .header("Content-Type","application/json").header("Idempotency-Key",UUID.randomUUID().toString())
                    .header(token.get("headerName").asString(),token.get("token").asString())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("title","합성 중요","body","합성 본문","pinned",true)))).build();
            var failed=owner.client().http.send(request,HttpResponse.BodyHandlers.ofString()); assertTrue(failed.statusCode()>=400); safe(failed);
            assertEquals(0,count("announcements")); assertEquals(0,count("notifications")); assertEquals(0,count("notification_jobs")); assertEquals(0,count("notice_idempotency_requests"));
        } finally { jdbc.execute("DROP TRIGGER push_test_failure ON notification_jobs"); jdbc.execute("DROP FUNCTION push_test_failure()"); }
    }
    @Test void successfulSendPreservesInboxAndMarksEveryDeviceIndependently() {
        register(owner.member().id(),"first","a"); register(owner.member().id(),"second","b"); notification(owner.member().id(),"success");
        drain(); assertEquals(2,jobs("SENT")); assertEquals(1,count("notifications")); assertEquals(2,sender.deliveries.size()); assertEquals(0,jobs("PENDING"));
        assertNotEquals(sender.deliveries.getFirst().subscriptionId(),sender.deliveries.getLast().subscriptionId()); assertFalse(processor.runOnce());
    }
    @Test void senderDisabledLeavesDurableJobsUntouchedUntilEnabled() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"disabled"); sender.enabled=false;
        assertFalse(processor.runOnce()); assertEquals(1,jobs("PENDING")); assertEquals(0,sender.deliveries.size());
        sender.enabled=true; assertTrue(processor.runOnce()); assertEquals(1,jobs("SENT"));
    }
    @Test void transientAndRateLimitedResponsesRespectRetryAfterAndRecover() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"retry"); sender.result=PushSender.Result.response(429,Duration.ofSeconds(120));
        assertTrue(processor.runOnce()); assertEquals(1,jobs("RETRY_WAIT")); assertEquals(clock.instant().plusSeconds(120),jdbc.queryForObject("SELECT next_attempt_at FROM notification_jobs",Timestamp.class).toInstant());
        clock.set("2026-10-30T02:01:59Z"); assertFalse(processor.runOnce()); sender.result=PushSender.Result.response(503,null);
        clock.set("2026-10-30T02:02:00Z"); assertTrue(processor.runOnce()); assertEquals(clock.instant().plusSeconds(60),jdbc.queryForObject("SELECT next_attempt_at FROM notification_jobs",Timestamp.class).toInstant());
        sender.result=PushSender.Result.response(201,null); clock.set("2026-10-30T02:03:00Z"); assertTrue(processor.runOnce()); assertEquals(1,jobs("SENT")); assertEquals(3,sender.deliveries.size());
    }
    @Test void networkFailureHasBoundedAttemptsAndInboxSurvives() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"timeout"); sender.result=PushSender.Result.transientFailure("PUSH_NETWORK_UNAVAILABLE");
        for (int i=0;i<8;i++) { assertTrue(processor.runOnce()); jdbc.update("UPDATE notification_jobs SET next_attempt_at=? WHERE status='RETRY_WAIT'",Timestamp.from(clock.instant())); }
        assertEquals(1,jobs("FAILED")); assertEquals(8,sender.deliveries.size()); assertEquals(8,jdbc.queryForObject("SELECT attempts FROM notification_jobs",Integer.class)); assertEquals(1,count("notifications"));
    }
    @Test void expiredEndpointDisablesOnlyThatDeviceAndCancelsItsOtherWaitingJobs() {
        var expired=register(owner.member().id(),"first","a"); var other=register(owner.member().id(),"second","b");
        notification(owner.member().id(),"expiry-one"); notification(owner.member().id(),"expiry-two"); sender.result=PushSender.Result.response(410,null);
        processor.runOnce(); assertEquals(1,jobs("FAILED")); assertEquals(1,jobs("CANCELED"));
        assertFalse(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,Long.parseLong(expired.id())));
        assertTrue(jdbc.queryForObject("SELECT active FROM push_subscriptions WHERE id=?",Boolean.class,Long.parseLong(other.id())));
        sender.result=PushSender.Result.response(201,null); drain(); assertEquals(2,jobs("SENT")); assertEquals(2,count("notifications"));
    }
    @Test void permanentProviderRejectionDoesNotDeactivateDeviceOrRetry() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"forbidden"); sender.result=PushSender.Result.response(403,null);
        processor.runOnce(); assertEquals(1,jobs("FAILED")); assertTrue(jdbc.queryForObject("SELECT active FROM push_subscriptions",Boolean.class)); assertFalse(processor.runOnce());
    }
    @Test void inactiveRecipientCancelsDeliveryWithoutSendingOrDeletingInbox() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"inactive"); jdbc.update("UPDATE users SET status='WITHDRAWN',withdrawn_at=? WHERE id=?",Timestamp.from(clock.instant()),owner.member().id());
        processor.runOnce(); assertEquals(1,jobs("CANCELED")); assertEquals(0,sender.deliveries.size()); assertEquals(1,count("notifications"));
    }
    @Test void replacedReservationDeliveryIsCanceledAndOnlyCurrentChangedEventIsSent() {
        register(owner.member().id(),"first","a"); LocalDate date=LocalDate.of(2026,10,30); opening.prepareRange(date,date.plusDays(14));
        var booked=reservations.create(owner.member().id(),UUID.randomUUID().toString(),new ReservationRequests.Create("PERSONAL",null,date.toString(),"B07",List.of("DRUMS"))).reservation();
        reservations.change(owner.member().id(),Long.parseLong(booked.id()),UUID.randomUUID().toString(),new ReservationRequests.Change(booked.version(),date.toString(),"B08",List.of("DRUMS")));
        drain(); assertEquals(1,pushJobs("CANCELED")); assertEquals(1,pushJobs("SENT")); assertEquals(1,sender.deliveries.size()); assertEquals("RESERVATION_CHANGED",sender.deliveries.getFirst().eventType());
        assertEquals(2,count("notifications"));
    }
    @Test void currentTeamMembershipIsRecheckedAfterEnqueueAndRemainingMemberStillReceives() throws Exception {
        Person remaining=person(); register(owner.member().id(),"first","a"); register(remaining.member().id(),"second","b");
        long team=jdbc.queryForObject("INSERT INTO teams(name,kind) VALUES ('합성 Push 조','REGULAR') RETURNING id",Long.class);
        long departingRoster=join(team,owner.member()),remainingRoster=join(team,remaining.member());
        LocalDate date=LocalDate.of(2026,10,30); opening.prepareRange(date,date.plusDays(14));
        reservations.create(owner.member().id(),UUID.randomUUID().toString(),new ReservationRequests.Create("TEAM",Long.toString(team),date.toString(),"B07",null));
        jdbc.update("UPDATE team_memberships SET status='REMOVED' WHERE roster_entry_id=?",departingRoster); drain();
        assertEquals(1,pushJobs("CANCELED")); assertEquals(1,pushJobs("SENT")); assertEquals(1,sender.deliveries.size());
        assertEquals(Long.parseLong(subscriptions.list(remaining.member().id(),"second").getFirst().id()),sender.deliveries.getFirst().subscriptionId());
    }
    @Test void reminderDeliveryIsCanceledWhenItsStartPassesAndInboxRemains() {
        register(owner.member().id(),"first","a"); LocalDate date=LocalDate.of(2026,10,30); opening.prepareRange(date,date.plusDays(14));
        reservations.create(owner.member().id(),UUID.randomUUID().toString(),new ReservationRequests.Create("PERSONAL",null,date.toString(),"B07",List.of("DRUMS"))); drain();
        clock.set("2026-10-30T08:00:00Z"); reminders.drain(50); assertEquals(1,pushJobs("PENDING"));
        clock.set("2026-10-30T09:00:00Z"); drain(); assertEquals(1,pushJobs("CANCELED")); assertEquals(1,pushJobs("SENT")); assertEquals(1,sender.deliveries.size()); assertEquals(2,count("notifications"));
    }
    @Test void expiredPendingExchangeRequestIsNotPushedEvenBeforeExpiryWorkerUpdatesItsState() throws Exception {
        Person other=person(); register(owner.member().id(),"first","a"); LocalDate date=LocalDate.of(2026,10,30); opening.prepareRange(date,date.plusDays(14));
        var offered=reservations.create(owner.member().id(),UUID.randomUUID().toString(),new ReservationRequests.Create("PERSONAL",null,date.toString(),"B07",List.of("DRUMS"))).reservation();
        var target=reservations.create(other.member().id(),UUID.randomUUID().toString(),new ReservationRequests.Create("PERSONAL",null,date.toString(),"B08",List.of("VOCAL"))).reservation(); drain();
        var request=exchanges.create(owner.member().id(),UUID.randomUUID().toString(),new ExchangeRequests.Create(
                List.of(new ExchangeRequests.Item(offered.id(),offered.version())),List.of(new ExchangeRequests.Item(target.id(),target.version())))).response();
        assertEquals(1,pushJobs("PENDING")); clock.set("2026-10-30T09:00:00Z"); drain(); assertEquals(1,pushJobs("CANCELED")); assertEquals(1,sender.deliveries.size());
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM exchange_requests WHERE id=?",String.class,Long.parseLong(request.id())));
    }
    @Test void expiredProcessingLeaseRecoversButLiveAndCanceledLeasesAreUntouched() {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"crash");
        jdbc.update("UPDATE notification_jobs SET status='PROCESSING',attempts=1,lease_token=?::uuid,lease_expires_at=?",UUID.randomUUID().toString(),Timestamp.from(clock.instant().plusSeconds(45)));
        assertFalse(processor.runOnce()); clock.set("2026-10-30T02:00:46Z"); assertTrue(processor.runOnce()); assertEquals(1,jobs("SENT"));
        assertEquals(2,jdbc.queryForObject("SELECT attempts FROM notification_jobs",Integer.class));
    }
    @Test void twoWorkersNeverSendTheSameLiveLeaseAndNetworkHoldsNoBookingGuard() throws Exception {
        register(owner.member().id(),"first","a"); notification(owner.member().id(),"race"); CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        sender.before=() -> {
            try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
                connection.setAutoCommit(false); try (var statement=connection.createStatement();var rows=statement.executeQuery("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE NOWAIT")) { assertTrue(rows.next()); }
                connection.rollback(); entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS));
            } catch (Exception failure) { throw new RuntimeException("synthetic sender coordination failure",failure); }
        };
        try (var threads=Executors.newFixedThreadPool(2)) {
            var first=threads.submit(processor::runOnce); assertTrue(entered.await(10,TimeUnit.SECONDS)); var second=threads.submit(processor::runOnce);
            assertFalse(second.get(10,TimeUnit.SECONDS)); release.countDown(); assertTrue(first.get(10,TimeUnit.SECONDS));
        }
        assertEquals(1,sender.deliveries.size()); assertEquals(1,jobs("SENT"));
    }
    @Test void accountRebindingDuringSendCannotFinishOrRetryAnOldLease() throws Exception {
        var old=register(owner.member().id(),"first","a"); notification(owner.member().id(),"in-flight-old"); Person newOwner=person();
        sender.before=() -> register(newOwner.member().id(),"new","a"); sender.result=PushSender.Result.response(503,null); processor.runOnce();
        assertEquals(1,jobs("CANCELED")); assertEquals(0,jobs("RETRY_WAIT")); assertEquals(0,jobs("SENT"));
        assertEquals("1",subscriptions.list(newOwner.member().id(),"new").getFirst().bindingVersion());
        assertEquals(0,sender.deliveries.getFirst().bindingVersion()); assertEquals(Long.parseLong(old.id()),sender.deliveries.getFirst().subscriptionId());
    }
    @Test void populatedV9UpgradePreservesEveryExistingRowAndCreatesOnlyEmptyNewTables() throws Exception {
        String schema="push_upgrade_"+UUID.randomUUID().toString().replace("-","");
        Flyway old=Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).target("9").load(); old.migrate();
        try (Connection connection=DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword())) {
            connection.setSchema(schema);
            try (var statement=connection.createStatement()) {
                statement.execute("INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) VALUES ('synthetic-upgrade','synthetic-hash','합성 관리자','GLOBAL_ADMIN','ACTIVE',now())");
                statement.execute("INSERT INTO notifications(user_id,event_key,event_type,title,body,target_path,created_at) VALUES (1,'synthetic:old','IMPORTANT_NOTICE','합성 제목','보존 본문','/notifications',now())");
            }
            Map<String,String> before=snapshots(connection,schema);
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()).schemas(schema).defaultSchema(schema).load().migrate();
            Map<String,String> after=snapshots(connection,schema); for (String table:before.keySet()) { assertEquals(before.get(table),after.get(table),table); }
            assertEquals("[]",after.get("push_subscriptions")); assertEquals("[]",after.get("notification_jobs")); assertEquals(before.size()+2,after.size());
        }
    }
    private Map<String,String> snapshots(Connection connection,String schema) throws Exception {
        List<String> tables=new java.util.ArrayList<>();
        try (var statement=connection.prepareStatement("SELECT table_name FROM information_schema.tables WHERE table_schema=? AND table_type='BASE TABLE' AND table_name<>'flyway_schema_history' ORDER BY table_name")) {
            statement.setString(1,schema); try (var rows=statement.executeQuery()) { while(rows.next()) { tables.add(rows.getString(1)); } }
        }
        Map<String,String> values=new HashMap<>(); for (String table:tables) {
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "+table+" r")) { assertTrue(rows.next()); values.put(table,rows.getString(1)); }
        } return values;
    }
    private PushSubscriptionResponse register(long user,String session,String suffix) {
        seedSession(user,session); return subscriptions.register(user,session,new PushRequests.Register(endpoint(suffix),new PushRequests.Keys(PUBLIC_KEY,AUTH),"합성 기기"));
    }
    private void seedSession(long user,String session) {
        jdbc.update("INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) VALUES (?,?,?,?,?,?,?) ON CONFLICT(session_id) DO NOTHING",
                UUID.randomUUID().toString(),session,clock.instant().toEpochMilli(),clock.instant().toEpochMilli(),7776000,clock.instant().plus(Duration.ofDays(90)).toEpochMilli(),
                new kr.jeongeum.scheduler.auth.security.MemberPrincipal(user).toString());
    }
    private String endpoint(String suffix) { return "https://fcm.googleapis.com/wpush/v2/synthetic-"+suffix; }
    private Map<String,Object> body(String suffix) { return Map.of("endpoint",endpoint(suffix),"keys",Map.of("p256dh",PUBLIC_KEY,"auth",AUTH),"deviceName","합성 기기"); }
    private long notification(long user,String event) {
        new TransactionTemplate(manager).executeWithoutResult(status -> inbox.insert(user,event,"IMPORTANT_NOTICE",null,null,null,null,"합성 알림","합성 본문","/notifications",clock.instant()));
        return jdbc.queryForObject("SELECT id FROM notifications WHERE user_id=? AND event_key=?",Long.class,user,event);
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Integer.class); }
    private int jobs(String state) { return jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE status=?",Integer.class,state); }
    private int pushJobs(String state) { return jdbc.queryForObject("SELECT count(*) FROM notification_jobs WHERE job_kind='PUSH_DELIVERY' AND status=?",Integer.class,state); }
    private long join(long team,Member member) {
        long roster=jdbc.queryForObject("INSERT INTO roster_entries(name,user_id) VALUES ('합성 Push 명단',?) RETURNING id",Long.class,member.id());
        jdbc.update("INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES (?,?,'MEMBER')",team,roster); return roster;
    }
    private void drain() { for (int i=0;i<30 && processor.runOnce();i++) { } }
    private Person person() throws Exception {
        Member member=members.insert("push-"+UUID.randomUUID().toString().substring(0,16),passwordHash,"합성 Push 회원","MEMBER",clock.instant()).orElseThrow(); return new Person(member,login(member));
    }
    private Client login(Member member) throws Exception { Client client=new Client(); success(client.write("POST","/api/v1/auth/login",Map.of("loginId",member.loginId(),"password",password)),200); return client; }
    private record Person(Member member,Client client) {}
    private JsonNode success(HttpResponse<String> response,int expected) {
        assertEquals(expected,response.statusCode(),() -> json.readTree(response.body()).path("error").path("code").asString()); safe(response);
        return expected==204?null:json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response,int expected,String code) { assertEquals(expected,response.statusCode()); assertEquals(code,json.readTree(response.body()).at("/error/code").asString()); safe(response); }
    private void safe(HttpResponse<String> response) {
        if (!response.body().isEmpty()) { assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); }
        assertFalse(response.body().contains(password));
        if (response.uri().getPath().startsWith(SUBS)) { for(String secret:List.of("p256dh","endpoint","sessionDigest","session_digest",AUTH,PUBLIC_KEY)) { assertFalse(response.body().contains(secret)); } }
    }
    private final class Client {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null); }
        HttpResponse<String> write(String method,String path,Object body) throws Exception { return send(method,path,body,success(get("/api/v1/auth/csrf"),200)); }
        HttpResponse<String> send(String method,String path,Object body,JsonNode csrf) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20));
            if (csrf!=null) { request.header(csrf.get("headerName").asString(),csrf.get("token").asString()); }
            if (body!=null) { request.header("Content-Type","application/json"); }
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class Fakes {
        @Bean @Primary TestClock pushTestClock() { return new TestClock(); }
        @Bean @Primary FakeSender fakePushSender() { return new FakeSender(); }
    }
    static final class FakeSender implements PushSender {
        final CopyOnWriteArrayList<PushDelivery> deliveries=new CopyOnWriteArrayList<>();
        volatile boolean enabled=true; volatile Result result=Result.response(201,null); volatile Runnable before=() -> {};
        void reset() { enabled=true; result=Result.response(201,null); before=() -> {}; deliveries.clear(); }
        @Override public boolean enabled() { return enabled; }
        @Override public Result send(PushDelivery delivery) { deliveries.add(delivery); before.run(); return result; }
    }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z"));
        void set(String value) { now.set(Instant.parse(value)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
