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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
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

/** Synthetic accounts, direct SQL, time control, failed inserts and locks are restricted to disposable PostgreSQL. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties={"jeongeum.recurring.worker.enabled=false","jeongeum.exchange.worker.enabled=false"})
@Import(NoticeNotificationIntegrationTest.TestTime.class)
@Timeout(90)
class NoticeNotificationIntegrationTest {
    private static final String NOTICES="/api/v1/notices",INBOX="/api/v1/notifications";
    private static final LocalDate TODAY=LocalDate.of(2026,10,30);
    @Container
    static final PostgreSQLContainer POSTGRES=new PostgreSQLContainer("postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("notice_notification_test").withUsername("notice_notification_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL",POSTGRES::getJdbcUrl); registry.add("APP_DB_USERNAME",POSTGRES::getUsername); registry.add("APP_DB_PASSWORD",POSTGRES::getPassword);
    }
    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    private Person admin;
    private String password,passwordHash;
    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE booking_days,teams,roster_entries,invitation_codes,users,spring_session RESTART IDENTITY CASCADE");
        clock.set("2026-10-30T02:00:00Z"); password=UUID.randomUUID().toString(); passwordHash=passwords.encode(password); admin=person("GLOBAL_ADMIN");
    }

    @Test void administratorCrudPreservesAuthorAndCreationAndAdvancesVersionWithSoftDelete() throws Exception {
        JsonNode created=create("  공지 제목  ","  첫 줄\n둘째 줄  ",true); assertEquals("공지 제목",created.get("title").asString());
        assertEquals("첫 줄\n둘째 줄",created.get("body").asString()); assertEquals(admin.member().id(),created.get("authorUserId").asLong());
        assertEquals(0,created.get("version").asLong()); assertFalse(created.get("deleted").asBoolean());
        Person second=person("GLOBAL_ADMIN"); clock.set("2026-10-30T03:00:00Z");
        JsonNode updated=success(second.client().write("PATCH",path(created),updateBody(created,"수정 제목","본문 유지",false),key()),200);
        assertEquals(created.get("createdAt"),updated.get("createdAt")); assertEquals(created.get("authorUserId"),updated.get("authorUserId"));
        assertEquals(clock.instant(),Instant.parse(updated.get("updatedAt").asString())); assertEquals(1,updated.get("version").asLong());
        JsonNode deleted=success(second.client().write("DELETE",path(updated),Map.of("expectedVersion",1),key()),200);
        assertTrue(deleted.get("deleted").asBoolean()); assertEquals(2,deleted.get("version").asLong()); assertEquals(1,count("announcements"));
        assertError(admin.client().get(path(deleted)),404,"RESOURCE_NOT_FOUND"); assertEquals(0,list(admin.client(),"").get("notices").size());
    }
    @Test void activeMembersReadButMembersAndTeamLeadersCannotManageAnyNotice() throws Exception {
        JsonNode notice=create("조회 공지","본문",false); Person member=person("MEMBER");
        assertEquals(notice,success(member.client().get(path(notice)),200)); assertEquals(1,list(member.client(),"").get("notices").size());
        assertError(member.client().write("POST",NOTICES,createBody("권한 우회","본문",true),key()),403,"FORBIDDEN");
        assertError(member.client().write("PATCH",path(notice),updateBody(notice,"권한 우회","본문",true),key()),403,"FORBIDDEN");
        assertError(member.client().write("DELETE",path(notice),Map.of("expectedVersion",0),key()),403,"FORBIDDEN");
        assertEquals(1,count("announcements")); assertEquals(0,count("notifications"));
    }
    @Test void authenticationAndCsrfProtectNoticeAndInboxMutationsWithoutLeakingData() throws Exception {
        JsonNode notice=create("보호 공지","본문",true); String notification=inbox(admin.client(),"").get("notifications").get(0).get("id").asString(); Client anon=new Client();
        for (String endpoint:List.of(NOTICES,path(notice),INBOX)) { assertError(anon.get(endpoint),401,"AUTHENTICATION_REQUIRED"); }
        assertError(anon.write("POST",NOTICES,createBody("제목","본문",true),key()),401,"AUTHENTICATION_REQUIRED");
        assertError(admin.client().send("POST",NOTICES,createBody("제목","본문",true),null,key()),403,"CSRF_INVALID");
        assertError(admin.client().send("PATCH",INBOX+"/"+notification+"/read",null,null,null),403,"CSRF_INVALID");
        assertError(admin.client().send("POST",INBOX+"/read-all",Map.of("through",clock.instant().toString()),null,null),403,"CSRF_INVALID");
        assertEquals(1,count("announcements")); assertEquals(1,inbox(admin.client(),"").get("unreadCount").asLong());
    }
    @Test void titleBodyValidatePlaintextCodepointLengthsWhitespaceAndControlCharacters() throws Exception {
        for (Map<String,Object> body:List.of(createBody(" ","본문",false),createBody("x".repeat(121),"본문",false),
                createBody("제목\n","본문",false),createBody("제목"," ",false),createBody("제목","x".repeat(10001),false),createBody("제목","악성\u0001본문",false))) {
            // A trailing newline is stripped, so use an internal control character for the title check.
            if (body.get("title").equals("제목\n")) { body=new HashMap<>(body); body.put("title","제\n목"); }
            assertError(admin.client().write("POST",NOTICES,body,key()),400,"INVALID_INPUT");
        }
        JsonNode text=create("😀".repeat(120),"<script>alert('synthetic')</script>\n\t평문",false);
        assertEquals("😀".repeat(120),text.get("title").asString()); assertTrue(text.get("body").asString().startsWith("<script>"));
        assertEquals(1,count("announcements"));
    }
    @Test void missingMalformedKeysVersionsPinnedAndUnknownRequestFieldsAreRejected() throws Exception {
        for (String requestKey:List.of("short"," ".repeat(16),"x".repeat(129))) { assertError(admin.client().write("POST",NOTICES,createBody("제목","본문",false),requestKey),400,"INVALID_INPUT"); }
        assertError(admin.client().write("POST",NOTICES,createBody("제목","본문",false),null),400,"INVALID_INPUT");
        assertError(admin.client().write("POST",NOTICES,Map.of("title","제목","body","본문"),key()),400,"INVALID_INPUT");
        Map<String,Object> unknown=new HashMap<>(createBody("제목","본문",false)); unknown.put("authorUserId","999");
        assertError(admin.client().write("POST",NOTICES,unknown,key()),400,"INVALID_INPUT");
        JsonNode notice=create("제목","본문",false);
        for (Object invalid:List.of(-1,"0.5","not-a-version")) {
            Map<String,Object> body=new HashMap<>(updateBody(notice,"제목","본문",false)); body.put("expectedVersion",invalid);
            assertError(admin.client().write("PATCH",path(notice),body,key()),400,"INVALID_INPUT");
        }
        assertError(admin.client().write("DELETE",path(notice),Map.of("other",0),key()),400,"INVALID_INPUT"); assertEquals(0,success(admin.client().get(path(notice)),200).get("version").asLong());
    }
    @Test void noticePaginationPinsFirstThenCreationTimeAndExclusiveIdTieBreak() throws Exception {
        List<JsonNode> ordinary=new ArrayList<>(),pinned=new ArrayList<>();
        for (int i=0;i<4;i++) { ordinary.add(create("일반 "+i,"본문",false)); pinned.add(create("중요 "+i,"본문",true)); }
        List<String> expected=new ArrayList<>(ids(pinned.reversed())); expected.addAll(ids(ordinary.reversed()));
        List<String> actual=new ArrayList<>(); String query="?limit=3";
        for (int page=0;page<3;page++) {
            JsonNode result=list(admin.client(),query); actual.addAll(ids(result.get("notices"))); assertTrue(result.has("serverTime"));
            if (result.get("nextCursor").isNull()) { break; } query="?limit=3&cursor="+result.get("nextCursor").asString();
        }
        assertEquals(expected,actual); assertEquals(8,actual.stream().distinct().count());
        clock.set("2026-10-30T03:00:00Z"); JsonNode later=create("나중 일반","본문",false);
        JsonNode result=list(admin.client(),""); assertEquals(pinned.getLast().get("id"),result.get("notices").get(0).get("id")); assertEquals(later.get("id"),result.get("notices").get(4).get("id"));
    }
    @Test void invalidNoticeCursorIdAndLimitsNeverExposeData() throws Exception {
        for (String query:List.of("?limit=0","?limit=51","?limit=no","?cursor=","?cursor=bogus","?cursor=a%7Cb","?cursor=1234=")) {
            assertError(admin.client().get(NOTICES+query),400,"INVALID_INPUT");
        }
        for (String id:List.of("0","-1","1.0","999999999999999999999999")) { assertError(admin.client().get(NOTICES+"/"+id),400,"INVALID_INPUT"); }
        assertError(admin.client().get(NOTICES+"/99999"),404,"RESOURCE_NOT_FOUND");
    }
    @Test void onlyInitiallyPinnedCreationNotOrdinaryCreateEditsUnpinOrRepinCreatesImportantNotice() throws Exception {
        Person member=person("MEMBER"); JsonNode ordinary=create("일반","본문",false); assertEquals(0,count("notifications"));
        ordinary=success(admin.client().write("PATCH",path(ordinary),updateBody(ordinary,"중요 변경","본문",true),key()),200); assertEquals(0,count("notifications"));
        JsonNode important=create("첫 중요","본문",true); assertEquals(2,count("notifications"));
        for (boolean pinned:List.of(true,false,true)) { important=success(admin.client().write("PATCH",path(important),updateBody(important,"수정 중요","수정 본문",pinned),key()),200); }
        assertEquals(2,count("notifications")); JsonNode row=inbox(member.client(),"").get("notifications").get(0);
        assertEquals("IMPORTANT_NOTICE",row.get("eventType").asString()); assertEquals("첫 중요",row.get("body").asString()); assertEquals("/notices/"+important.get("id").asString(),row.get("targetPath").asString());
    }
    @Test void recipientsAreExactlyActiveAtCreationAndLaterJoinDoesNotBackfill() throws Exception {
        Person active=person("MEMBER"),inactive=person("MEMBER"),withdrawn=person("MEMBER");
        jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?",inactive.member().id());
        jdbc.update("UPDATE users SET status='WITHDRAWN',version=version+1 WHERE id=?",withdrawn.member().id());
        create("중요","본문",true); assertEquals(List.of(admin.member().id(),active.member().id()),jdbc.queryForList("SELECT user_id FROM notifications ORDER BY user_id",Long.class));
        Person newcomer=person("MEMBER"); assertEquals(0,inbox(newcomer.client(),"").get("notifications").size()); assertEquals(1,list(newcomer.client(),"").get("notices").size());
    }
    @Test void deletedNoticeKeepsAuditTextAndReceivedInboxButDetailBecomesUnavailable() throws Exception {
        Person member=person("MEMBER"); JsonNode notice=create("삭제할 중요","감사 이력",true);
        success(admin.client().write("DELETE",path(notice),Map.of("expectedVersion",0),key()),200);
        assertEquals("감사 이력",jdbc.queryForObject("SELECT body FROM announcements WHERE id=?",String.class,id(notice)));
        assertEquals(1,inbox(member.client(),"").get("notifications").size()); assertError(member.client().get(path(notice)),404,"RESOURCE_NOT_FOUND");
        assertError(admin.client().write("PATCH",path(notice),updateBody(notice,"제목","본문",false),key()),409,"NOTICE_DELETED");
    }
    @Test void creationReplayReturnsSameIdCurrentEditedOrDeletedStateWithoutAnotherInboxEvent() throws Exception {
        String requestKey=key(); Map<String,Object> body=createBody("  원본 제목  "," 원본 본문 ",true);
        JsonNode notice=success(admin.client().write("POST",NOTICES,body,requestKey),201);
        JsonNode changed=success(admin.client().write("PATCH",path(notice),updateBody(notice,"수정","본문",false),key()),200);
        assertEquals(changed,success(admin.client().write("POST",NOTICES,createBody("원본 제목","원본 본문",true),requestKey),201));
        JsonNode deleted=success(admin.client().write("DELETE",path(changed),Map.of("expectedVersion",1),key()),200);
        assertEquals(deleted,success(admin.client().write("POST",NOTICES,body,requestKey),201)); assertEquals(1,count("announcements")); assertEquals(1,count("notifications"));
    }
    @Test void updateAndDeleteReplayAvoidVersionIncrementAndReturnCurrentState() throws Exception {
        JsonNode notice=create("원본","본문",false); String updateKey=key(); Map<String,Object> body=updateBody(notice,"수정","본문",true);
        JsonNode updated=success(admin.client().write("PATCH",path(notice),body,updateKey),200);
        assertEquals(updated,success(admin.client().write("PATCH",path(notice),body,updateKey),200));
        String deleteKey=key(); Map<String,Object> deletion=Map.of("expectedVersion",1); JsonNode deleted=success(admin.client().write("DELETE",path(updated),deletion,deleteKey),200);
        assertEquals(deleted,success(admin.client().write("DELETE",path(updated),deletion,deleteKey),200));
        assertEquals(deleted,success(admin.client().write("PATCH",path(notice),body,updateKey),200)); assertEquals(3,count("notice_idempotency_requests"));
    }
    @Test void fingerprintRejectsChangedTitleBodyImportanceVersionMethodAndRouteButKeysAreActorScoped() throws Exception {
        String requestKey=key(); JsonNode notice=success(admin.client().write("POST",NOTICES,createBody("원본","본문",false),requestKey),201);
        for (Map<String,Object> body:List.of(createBody("변경","본문",false),createBody("원본","변경",false),createBody("원본","본문",true))) {
            assertError(admin.client().write("POST",NOTICES,body,requestKey),409,"IDEMPOTENCY_KEY_REUSED");
        }
        assertError(admin.client().write("PATCH",path(notice),updateBody(notice,"원본","본문",false),requestKey),409,"IDEMPOTENCY_KEY_REUSED");
        assertError(admin.client().write("DELETE",path(notice),Map.of("expectedVersion",0),requestKey),409,"IDEMPOTENCY_KEY_REUSED");
        Person other=person("GLOBAL_ADMIN"); success(other.client().write("POST",NOTICES,createBody("별도","본문",false),requestKey),201); assertEquals(2,count("announcements"));
    }
    @Test void currentRoleAndActiveStatusAreRecheckedOnSuccessfulReplay() throws Exception {
        String requestKey=key(); Map<String,Object> body=createBody("원본","본문",true); success(admin.client().write("POST",NOTICES,body,requestKey),201);
        jdbc.update("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?",admin.member().id());
        assertError(admin.client().write("POST",NOTICES,body,requestKey),403,"FORBIDDEN");
        jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?",admin.member().id());
        assertError(admin.client().get(NOTICES),403,"INACTIVE_USER"); assertEquals(1,count("announcements")); assertEquals(1,count("notifications"));
    }
    @Test void concurrentSameCreateKeyCreatesOneNoticeAndOneEventPerRecipient() throws Exception {
        Person member=person("MEMBER"); String requestKey=key(); Map<String,Object> body=createBody("동시 중요","본문",true);
        Client secondSession=new Client();
        success(secondSession.write("POST","/api/v1/auth/login",Map.of("loginId",admin.member().loginId(),"password",password),null),200);
        // Login rotates CSRF. Load each session's token before starting the business race so CSRF persistence cannot serialize it.
        JsonNode firstCsrf=admin.client().csrf(),secondCsrf=secondSession.csrf();
        List<HttpResponse<String>> results=race(() -> admin.client().send("POST",NOTICES,body,firstCsrf,requestKey),
                () -> secondSession.send("POST",NOTICES,body,secondCsrf,requestKey));
        assertEquals(List.of(201,201),results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(success(results.getFirst(),201).get("id"),success(results.getLast(),201).get("id")); assertEquals(1,count("announcements")); assertEquals(2,count("notifications"));
        assertEquals(1,inbox(member.client(),"").get("unreadCount").asLong());
    }
    @Test void concurrentDifferentUpdatesOnlyOneExpectedVersionWins() throws Exception {
        JsonNode notice=create("원본","본문",false);
        var results=race(() -> admin.client().write("PATCH",path(notice),updateBody(notice,"첫 수정","본문",false),key()),
                () -> admin.client().write("PATCH",path(notice),updateBody(notice,"둘 수정","본문",true),key()));
        assertEquals(List.of(200,409),results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1,success(admin.client().get(path(notice)),200).get("version").asLong()); assertEquals(0,count("notifications"));
    }
    @Test void failedFinalIdempotencyInsertRollsBackNoticeAndEveryRecipientThenSameKeySucceeds() throws Exception {
        person("MEMBER"); String requestKey=key(); Map<String,Object> body=createBody("원자적 중요","본문",true);
        failure("notice_idempotency_requests");
        try { assertError(admin.client().write("POST",NOTICES,body,requestKey),409,"DATA_CONFLICT"); }
        finally { removeFailure("notice_idempotency_requests"); }
        assertEquals(0,count("announcements")); assertEquals(0,count("notifications")); assertEquals(0,count("notice_idempotency_requests"));
        success(admin.client().write("POST",NOTICES,body,requestKey),201); assertEquals(2,count("notifications"));
    }
    @Test void failedNotificationInsertRollsBackNoticeAndKeyThenExplicitRetryCreatesExactlyOneEvent() throws Exception {
        person("MEMBER"); String requestKey=key(); Map<String,Object> body=createBody("실패 후 재시도","본문",true);
        failure("notifications");
        try { assertError(admin.client().write("POST",NOTICES,body,requestKey),409,"DATA_CONFLICT"); }
        finally { removeFailure("notifications"); }
        assertEquals(0,count("announcements")); assertEquals(0,count("notifications")); assertEquals(0,count("notice_idempotency_requests"));
        success(admin.client().write("POST",NOTICES,body,requestKey),201); assertEquals(1,count("announcements")); assertEquals(2,count("notifications"));
    }
    @Test void updateAndDeleteLateInsertFailurePreserveOriginalTextVisibilityAndVersion() throws Exception {
        JsonNode notice=create("원본","본문",true); Map<String,String> before=snapshots("announcements","notifications","notice_idempotency_requests");
        failure("notice_idempotency_requests");
        try {
            assertError(admin.client().write("PATCH",path(notice),updateBody(notice,"실패 수정","다른 본문",false),key()),409,"DATA_CONFLICT");
            assertError(admin.client().write("DELETE",path(notice),Map.of("expectedVersion",0),key()),409,"DATA_CONFLICT");
        } finally { removeFailure("notice_idempotency_requests"); }
        assertEquals(before,snapshots("announcements","notifications","notice_idempotency_requests")); assertEquals(notice,success(admin.client().get(path(notice)),200));
    }
    @Test void directoryGuardTimeoutReturnsRetryable503AndSameKeyLaterSucceedsWithoutPartialRows() throws Exception {
        String requestKey=key(); Map<String,Object> body=createBody("잠금 대기","본문",true);
        try (Connection held=lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE")) {
            long start=System.nanoTime(); assertError(admin.client().write("POST",NOTICES,body,requestKey),503,"NOTICE_TEMPORARILY_UNAVAILABLE");
            assertTrue(Duration.ofNanos(System.nanoTime()-start).toSeconds()>=8); assertEquals(0,count("announcements")); held.commit();
        }
        success(admin.client().write("POST",NOTICES,body,requestKey),201); assertEquals(1,count("notifications"));
    }
    @Test void lateTableInsertLockTimesOutAndRollsBackAlreadyWrittenNoticeAndInbox() throws Exception {
        String requestKey=key(); Map<String,Object> body=createBody("마지막 쓰기 잠금","본문",true);
        try (Connection held=fixtureConnection()) {
            held.setAutoCommit(false); try (var statement=held.createStatement()) { statement.execute("LOCK TABLE notice_idempotency_requests IN SHARE MODE"); }
            assertError(admin.client().write("POST",NOTICES,body,requestKey),503,"NOTICE_TEMPORARILY_UNAVAILABLE");
            assertEquals(0,count("announcements")); assertEquals(0,count("notifications")); assertEquals(0,count("notice_idempotency_requests")); held.commit();
        }
        success(admin.client().write("POST",NOTICES,body,requestKey),201); assertEquals(1,count("announcements"));
    }
    @Test void authorityIsRevalidatedAfterWaitingOnGuard() throws Exception {
        Client client=admin.client(); JsonNode csrf=client.csrf();
        try (Connection held=lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");var pool=Executors.newSingleThreadExecutor()) {
            var future=pool.submit(() -> client.send("POST",NOTICES,createBody("회수 권한","본문",true),csrf,key()));
            awaitWaiter("directory_mutation_guard");
            try (var statement=held.prepareStatement("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?")) { statement.setLong(1,admin.member().id()); assertEquals(1,statement.executeUpdate()); }
            held.commit(); assertError(future.get(25,TimeUnit.SECONDS),403,"FORBIDDEN");
        }
        assertEquals(0,count("announcements")); assertEquals(0,count("notifications"));
    }
    @Test void inboxPaginationUnreadFilterAndGlobalUnreadCountOnlyIncludeAuthenticatedOwner() throws Exception {
        Person first=person("MEMBER"),second=person("MEMBER");
        for (int i=0;i<4;i++) { create("중요 "+i,"본문",true); }
        JsonNode page=inbox(first.client(),"?limit=2"); assertEquals(2,page.get("notifications").size()); assertEquals(4,page.get("unreadCount").asLong());
        String marked=page.get("notifications").get(0).get("id").asString(); success(first.client().write("PATCH",INBOX+"/"+marked+"/read",null,null),200);
        JsonNode next=inbox(first.client(),"?limit=2&beforeId="+page.get("nextBeforeId").asString()); assertEquals(2,next.get("notifications").size()); assertTrue(next.get("nextBeforeId").isNull());
        JsonNode unread=inbox(first.client(),"?unreadOnly=true"); assertEquals(3,unread.get("notifications").size()); assertEquals(3,unread.get("unreadCount").asLong());
        assertTrue(unread.get("notifications").valueStream().allMatch(n -> n.get("readAt").isNull()));
        assertEquals(4,inbox(second.client(),"").get("unreadCount").asLong());
        List<String> all=ids(page.get("notifications")); all=new ArrayList<>(all); all.addAll(ids(next.get("notifications"))); assertEquals(4,all.stream().distinct().count());
    }
    @Test void crossOwnerReadReturns404AndInactiveAccountCannotReadItsOldInbox() throws Exception {
        Person member=person("MEMBER"); create("중요","본문",true);
        String other=inbox(admin.client(),"").get("notifications").get(0).get("id").asString();
        assertError(member.client().write("PATCH",INBOX+"/"+other+"/read",null,null),404,"RESOURCE_NOT_FOUND");
        assertEquals(1,inbox(admin.client(),"").get("unreadCount").asLong());
        jdbc.update("UPDATE users SET status='INACTIVE',version=version+1 WHERE id=?",member.member().id()); assertError(member.client().get(INBOX),403,"INACTIVE_USER");
    }
    @Test void singleReadIsNaturallyIdempotentAndKeepsFirstReadTimeAcrossConcurrentRetries() throws Exception {
        create("중요","본문",true); String path=INBOX+"/"+inbox(admin.client(),"").get("notifications").get(0).get("id").asString()+"/read";
        clock.set("2026-10-30T03:00:00Z"); JsonNode first=success(admin.client().write("PATCH",path,null,null),200); assertEquals(clock.instant(),Instant.parse(first.get("readAt").asString()));
        clock.set("2026-10-30T04:00:00Z"); var results=race(() -> admin.client().write("PATCH",path,null,null),() -> admin.client().write("PATCH",path,null,null));
        for (var result:results) { assertEquals(first,success(result,200)); } assertEquals(0,inbox(admin.client(),"").get("unreadCount").asLong());
    }
    @Test void readAllUsesInclusiveServerCutoffLeavesNewerUnreadAndIsOwnerOnlyAndIdempotent() throws Exception {
        Person member=person("MEMBER"); create("이전","본문",true); Instant through=clock.instant();
        clock.set("2026-10-30T02:00:01Z"); create("이후","본문",true);
        JsonNode response=success(member.client().write("POST",INBOX+"/read-all",Map.of("through",through.toString()),null),200);
        assertEquals(through,Instant.parse(response.get("through").asString())); assertEquals(1,response.get("readCount").asLong()); assertEquals(1,response.get("unreadCount").asLong());
        assertEquals(0,success(member.client().write("POST",INBOX+"/read-all",Map.of("through",through.toString()),null),200).get("readCount").asLong());
        assertEquals(2,inbox(admin.client(),"").get("unreadCount").asLong());
        JsonNode remaining=inbox(member.client(),"?unreadOnly=true").get("notifications").get(0); assertEquals("이후",remaining.get("body").asString());
    }
    @Test void malformedFutureCutoffAndInboxPaginationFieldsCannotModifyReadState() throws Exception {
        create("중요","본문",true);
        for (String query:List.of("?limit=0","?limit=51","?beforeId=0","?beforeId=nan","?unreadOnly=notbool")) { assertError(admin.client().get(INBOX+query),400,"INVALID_INPUT"); }
        for (Map<String,Object> body:List.<Map<String,Object>>of(Map.of(),Map.of("through","not-a-date"),Map.of("through",clock.instant().plusSeconds(1).toString()),Map.of("through",clock.instant().toString(),"userId","999"))) {
            assertError(admin.client().write("POST",INBOX+"/read-all",body,null),400,"INVALID_INPUT");
        }
        assertEquals(1,inbox(admin.client(),"").get("unreadCount").asLong());
    }
    @Test void finerThanMicrosecondCutoffCannotRoundForwardIntoNewerNotification() throws Exception {
        clock.set("2026-10-30T02:00:00.000121Z"); create("이전 경계","본문",true);
        clock.set("2026-10-30T02:00:00.000122Z"); create("다음 경계","본문",true);
        JsonNode result=success(admin.client().write("POST",INBOX+"/read-all",Map.of("through","2026-10-30T02:00:00.000121600Z"),null),200);
        assertEquals(1,result.get("readCount").asLong()); assertEquals(1,result.get("unreadCount").asLong());
        assertEquals(Instant.parse("2026-10-30T02:00:00.000121Z"),Instant.parse(result.get("through").asString()));
        assertEquals("다음 경계",inbox(admin.client(),"?unreadOnly=true").get("notifications").get(0).get("body").asString());
    }

    @Test
    void populatedV8UpgradePreservesEveryOldColumnAndDoesNotBackfillInboxWhileRestartStaysIdempotent() throws Exception {
        String schema = "notice_upgrade_"+UUID.randomUUID().toString().replace("-", "");
        assertEquals(8,fixtureFlyway(schema,"8").migrate().migrationsExecuted);
        Map<String,String> before;
        try (Connection connection = fixtureConnection(schema)) {
            Timestamp now = Timestamp.from(clock.instant());
            long admin = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v8-admin',?,'합성 V8 관리자','GLOBAL_ADMIN','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long member = insertId(connection, "INSERT INTO users(login_id,password_hash,display_name,global_role,status,created_at) "
                    + "VALUES ('synthetic-v8-member',?,'합성 V8 회원','MEMBER','ACTIVE',?) RETURNING id", passwords.encode(password), now);
            long roster = insertId(connection, "INSERT INTO roster_entries(name,user_id,version) VALUES ('합성 연결 명단',?,2) RETURNING id", member);
            long pending = insertId(connection, "INSERT INTO roster_entries(name) VALUES ('합성 미가입 명단') RETURNING id");
            long group = insertId(connection, "INSERT INTO teams(name,kind,version) VALUES ('합성 V8 조','REGULAR',3) RETURNING id");
            execute(connection, "INSERT INTO team_memberships(team_id,roster_entry_id,role,version) VALUES (?,?,'LEADER',1)", group, roster);
            execute(connection, "INSERT INTO team_songs(team_id,title,artist,memo,sort_order,created_by,updated_by) "
                    + "VALUES (?,'합성 V8 곡','합성 아티스트','합성 메모',1,?,?)", group, admin, member);
            execute(connection, "INSERT INTO member_role_audit(target_user_id,actor_user_id,old_role,new_role,created_at) "
                    + "VALUES (?,?,'GLOBAL_ADMIN','MEMBER',?)", member, admin, now);
            execute(connection, "INSERT INTO invitation_codes(code_digest,recipient_name,issued_by,created_at,expires_at,roster_entry_id) "
                    + "VALUES (?,'합성 V8 초대',?,?,?,?)",
                    kr.jeongeum.scheduler.invitation.service.InvitationTokens.digest(UUID.randomUUID().toString()),
                    admin, now, Timestamp.from(clock.instant().plus(Duration.ofDays(7))), pending);
            String session = UUID.randomUUID().toString();
            execute(connection, "INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name) "
                    + "VALUES (?,?,?,?,7776000,?,'synthetic-v8-member')", session, UUID.randomUUID().toString(),
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
                    + "VALUES ('CANCEL','USER',?,?,'합성 V8 취소') RETURNING id", admin, now);
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
            execute(connection, "UPDATE reservations SET status='CANCELED',canceled_at=?,canceled_by_user_id=?,canceled_operation_id=?,cancel_reason='합성 V8 취소',version=1 WHERE id=?",
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
            long incident=insertId(connection,"INSERT INTO asset_incidents(asset_id,status,description,origin,reported_by_user_id,reported_at) VALUES ((SELECT id FROM assets WHERE code='VOCAL'),'OPEN','합성 기존 고장','ADMIN',?,?) RETURNING id",admin,now);
            execute(connection,"INSERT INTO instrument_fault_idempotency_requests(actor_user_id,key_digest,request_fingerprint,http_method,route,response_status,result_incident_id,created_at) VALUES (?,?,?,'POST','/api/v1/instrument-faults',201,?,?)",admin,"g".repeat(64),"h".repeat(64),incident,now);
            before=schemaSnapshots(connection);
            assertEquals(32,before.size(),"Preservation fixture must cover all V1-V8 feature and session tables");
            for (var entry:before.entrySet()) { assertNotEquals("[]",entry.getValue(),"Migration requires populated "+entry.getKey()); }
        }
        assertEquals(1,fixtureFlyway(schema,"9").migrate().migrationsExecuted);
        assertEquals(0,fixtureFlyway(schema,"9").migrate().migrationsExecuted);
        assertTrue(fixtureFlyway(schema,"9").validateWithResult().validationSuccessful);
        try (Connection connection=fixtureConnection(schema)) {
            for (var entry:before.entrySet()) {
                assertEquals(entry.getValue(),tableSnapshot(connection,entry.getKey()), "V9 must preserve every old column in "+entry.getKey());
            }
            assertEquals(9,scalar(connection,"SELECT count(*) FROM flyway_schema_history WHERE success AND version IS NOT NULL"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM asset_incidents WHERE origin='ADMIN' AND status='OPEN' AND reported_by_user_id IS NOT NULL"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM asset_incidents i JOIN assets a ON a.id=i.asset_id WHERE a.code='VOCAL' AND a.status='FAULTY' AND a.version=4 AND i.reported_at IS NOT NULL"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM instrument_fault_idempotency_requests"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM announcements"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM notifications"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM notice_idempotency_requests"));
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


    private List<HttpResponse<String>> race(Request first,Request second) throws Exception {
        try (Connection held=lock("SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(first::run); var b=pool.submit(second::run);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid() AND wait_event_type='Lock' AND query ILIKE '%directory_mutation_guard%'",Integer.class)>=2);
            held.commit(); return List.of(a.get(25,TimeUnit.SECONDS),b.get(25,TimeUnit.SECONDS));
        }
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }
    private void awaitWaiter(String query) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE ?)",Boolean.class,"%"+query+"%")));
    }
    private Connection fixtureConnection() throws Exception { return DriverManager.getConnection(POSTGRES.getJdbcUrl(),POSTGRES.getUsername(),POSTGRES.getPassword()); }
    private Connection lock(String sql) throws Exception {
        Connection connection=fixtureConnection(); connection.setAutoCommit(false);
        try (var statement=connection.createStatement();var rows=statement.executeQuery(sql)) { assertTrue(rows.next()); } return connection;
    }
    private void failure(String table) {
        jdbc.execute("CREATE FUNCTION notice_qa_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'synthetic notice write failure' USING ERRCODE='23514'; END $$");
        jdbc.execute("CREATE TRIGGER notice_qa_fail BEFORE INSERT ON "+table+" FOR EACH ROW EXECUTE FUNCTION notice_qa_fail()");
    }
    private void removeFailure(String table) { jdbc.execute("DROP TRIGGER notice_qa_fail ON "+table); jdbc.execute("DROP FUNCTION notice_qa_fail()"); }
    private Map<String,String> snapshots(String... tables) {
        Map<String,String> result=new HashMap<>(); for (String table:tables) { result.put(table,jdbc.queryForObject("SELECT COALESCE(jsonb_agg(to_jsonb(r) ORDER BY to_jsonb(r)::text),'[]'::jsonb)::text FROM "+table+" r",String.class)); } return result;
    }
    private JsonNode create(String title,String body,boolean pinned) throws Exception { return success(admin.client().write("POST",NOTICES,createBody(title,body,pinned),key()),201); }
    private Map<String,Object> createBody(String title,String body,boolean pinned) { return Map.of("title",title,"body",body,"pinned",pinned); }
    private Map<String,Object> updateBody(JsonNode notice,String title,String body,boolean pinned) { return Map.of("expectedVersion",notice.get("version").asLong(),"title",title,"body",body,"pinned",pinned); }
    private String path(JsonNode notice) { return NOTICES+"/"+notice.get("id").asString(); }
    private long id(JsonNode notice) { return Long.parseLong(notice.get("id").asString()); }
    private JsonNode list(Client client,String query) throws Exception { return success(client.get(NOTICES+query),200); }
    private JsonNode inbox(Client client,String query) throws Exception { return success(client.get(INBOX+query),200); }
    private List<String> ids(JsonNode array) { return array.valueStream().map(n -> n.get("id").asString()).toList(); }
    private List<String> ids(List<JsonNode> rows) { return rows.stream().map(n -> n.get("id").asString()).toList(); }
    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM "+table,Long.class); }
    private String key() { return UUID.randomUUID().toString(); }
    private Person person(String role) throws Exception {
        Member member=members.insert("notice-test-"+UUID.randomUUID().toString().substring(0,16),passwordHash,"합성 공지 회원",role,clock.instant()).orElseThrow();
        Client client=new Client(); success(client.write("POST","/api/v1/auth/login",Map.of("loginId",member.loginId(),"password",password),null),200); return new Person(member,client);
    }
    private record Person(Member member,Client client) {}
    private JsonNode success(HttpResponse<String> response,int expected) {
        assertEquals(expected,response.statusCode(),() -> json.readTree(response.body()).path("error").path("code").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store")); assertSafe(response); return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response,int expected,String code) {
        assertEquals(expected,response.statusCode()); assertEquals(code,json.readTree(response.body()).at("/error/code").asString()); assertSafe(response);
    }
    private void assertSafe(HttpResponse<String> response) {
        assertFalse(response.body().contains(password));
        for (String secret:List.of("passwordHash","password_hash","key_digest","request_fingerprint","SESSION=")) { assertFalse(response.body().contains(secret)); }
        if (response.uri().getPath().startsWith(NOTICES) || response.uri().getPath().startsWith(INBOX)) { assertFalse(response.body().contains("loginId")); }
    }
    private final class Client {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET",path,null,null,null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"),200); }
        HttpResponse<String> write(String method,String path,Object body,String key) throws Exception { return send(method,path,body,csrf(),key); }
        HttpResponse<String> send(String method,String path,Object body,JsonNode csrf,String key) throws Exception {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(30));
            if (csrf!=null) { request.header(csrf.get("headerName").asString(),csrf.get("token").asString()); }
            if (key!=null) { request.header("Idempotency-Key",key); } if (body!=null) { request.header("Content-Type","application/json"); }
            request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
    @TestConfiguration(proxyBeanMethods=false) static class TestTime { @Bean @Primary TestClock noticeTestClock() { return new TestClock(); } }
    static final class TestClock extends Clock {
        private final AtomicReference<Instant> now; private final ZoneId zone;
        TestClock() { this(new AtomicReference<>(Instant.parse("2026-10-30T02:00:00Z")),ZoneId.of("UTC")); }
        private TestClock(AtomicReference<Instant> now,ZoneId zone) { this.now=now; this.zone=zone; }
        void set(String value) { now.set(Instant.parse(value)); }
        @Override public Instant instant() { return now.get(); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new TestClock(now,zone); }
    }
}
