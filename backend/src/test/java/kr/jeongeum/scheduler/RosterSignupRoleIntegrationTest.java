package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jeongeum.recurring.worker.enabled=false", "jeongeum.exchange.worker.enabled=false",
        "jeongeum.reminder.worker.enabled=false", "jeongeum.push.worker.enabled=false", "jeongeum.push.enabled=false"})
@Timeout(120)
class RosterSignupRoleIntegrationTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("roster_signup_role_test").withUsername("roster_signup_role_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("APP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private String password;
    private Member admin;
    private Client administrator;

    @BeforeEach void setup() throws Exception {
        assertEquals("roster_signup_role_test", jdbc.queryForObject("SELECT current_database()", String.class));
        jdbc.execute("TRUNCATE teams,roster_entries,invitation_codes,users,spring_session RESTART IDENTITY CASCADE");
        password = UUID.randomUUID().toString();
        admin = account("GLOBAL_ADMIN");
        administrator = authenticated(admin);
    }

    @Test void preassignedAdministratorUsesCurrentRosterRoleAndKeepsPreassignedTeamLeadership() throws Exception {
        JsonNode person = roster("합성 가입 예정 관리자");
        JsonNode invitation = issue(person);
        JsonNode team = success(administrator.mutate("POST", "/api/v1/teams",
                Map.of("name", "합성 예정 관리자 조", "kind", "REGULAR")), 201);
        String path = "/api/v1/teams/" + team.get("id").asString();
        assertEquals(201, administrator.mutate("POST", path + "/members",
                Map.of("rosterEntryId", person.get("id").asString())).statusCode());
        assertEquals(200, administrator.mutate("PUT", path + "/leader",
                Map.of("rosterEntryId", person.get("id").asString())).statusCode());
        JsonNode assigned = success(signupRole(person, "GLOBAL_ADMIN"), 200);
        assertEquals("GLOBAL_ADMIN", assigned.get("signupGlobalRole").asString());
        assertTrue(assigned.get("userId").isNull());

        Client applicant = new Client();
        String login = uniqueLogin();
        Map<String, Object> input = registration(invitation, login);
        input.put("globalRole", "MEMBER"); // Public input cannot override either direction.
        JsonNode registered = success(applicant.mutate("POST", "/api/v1/auth/register", input), 201);
        assertEquals("GLOBAL_ADMIN", registered.get("globalRole").asString());
        assertEquals("LEADER", registered.get("teamMemberships").get(0).get("role").asString());
        assertEquals(team.get("id").asString(), registered.get("teamMemberships").get(0).get("teamId").asString());
        JsonNode linked = rosterEntry(person);
        assertEquals(registered.get("id").asString(), linked.get("userId").asString());
        assertEquals("GLOBAL_ADMIN", linked.get("signupGlobalRole").asString());
        assertEquals(0, count("member_role_audit"), "Initial account creation is not an existing-account role change");
        assertEquals(200, applicant.login(login).statusCode());
        assertEquals(200, applicant.get("/api/v1/users").statusCode());
    }

    @Test void ordinaryInvitationCannotForgeAdminOrUseAnotherSameNamedRosterIdentity() throws Exception {
        JsonNode privileged = roster("동명 합성 명단");
        JsonNode ordinary = roster("동명 합성 명단");
        success(signupRole(privileged, "GLOBAL_ADMIN"), 200);
        JsonNode privilegedInvite = issue(privileged);
        JsonNode invitation = issue(ordinary);
        assertEquals("MEMBER", ordinary.get("signupGlobalRole").asString());
        Map<String, Object> input = registration(invitation, uniqueLogin());
        input.put("globalRole", "GLOBAL_ADMIN");
        input.put("signupGlobalRole", "GLOBAL_ADMIN");
        input.put("rosterEntryId", privileged.get("id").asString());
        JsonNode registered = success(new Client().mutate("POST", "/api/v1/auth/register", input), 201);
        assertEquals("MEMBER", registered.get("globalRole").asString());
        assertEquals(registered.get("id").asString(), rosterEntry(ordinary).get("userId").asString());
        assertTrue(rosterEntry(privileged).get("userId").isNull());
        assertEquals("GLOBAL_ADMIN", rosterEntry(privileged).get("signupGlobalRole").asString());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE id=? AND used_at IS NOT NULL",
                Integer.class, id(privilegedInvite)));
    }

    @Test void clearingPreassignmentBeforeSignupUsesMemberEvenForAnAlreadyIssuedInvitation() throws Exception {
        JsonNode person = roster("합성 예정 권한 해제");
        JsonNode assigned = success(signupRole(person, "GLOBAL_ADMIN"), 200);
        JsonNode invitation = issue(person);
        JsonNode cleared = success(signupRole(assigned, "MEMBER"), 200);
        assertTrue(version(cleared) > version(assigned));
        JsonNode registered = success(new Client().mutate("POST", "/api/v1/auth/register",
                registration(invitation, uniqueLogin())), 201);
        assertEquals("MEMBER", registered.get("globalRole").asString());
        assertEquals("MEMBER", rosterEntry(person).get("signupGlobalRole").asString());
    }

    @Test void preassignmentRequiresAdminCsrfValidRoleAndCurrentVersionWithoutPartialChanges() throws Exception {
        JsonNode person = roster("합성 예정 권한 검증");
        Map<String, Object> grant = Map.of("globalRole", "GLOBAL_ADMIN", "version", version(person));
        assertError(new Client().mutate("PATCH", rolePath(person), grant), 401, "AUTHENTICATION_REQUIRED");
        assertError(authenticated(account("MEMBER")).mutate("PATCH", rolePath(person), grant), 403, "FORBIDDEN");
        assertError(administrator.send("PATCH", rolePath(person), grant, null), 403, "CSRF_INVALID");
        assertError(administrator.mutate("PATCH", rolePath(person),
                Map.of("globalRole", "LEADER", "version", version(person))), 400, "INVALID_INPUT");
        assertError(administrator.mutate("PATCH", rolePath(person),
                Map.of("globalRole", "GLOBAL_ADMIN", "version", 0.5)), 400, "INVALID_INPUT");
        assertEquals("MEMBER", rosterEntry(person).get("signupGlobalRole").asString());
        JsonNode assigned = success(signupRole(person, "GLOBAL_ADMIN"), 200);
        assertError(signupRole(person, "MEMBER"), 409, "VERSION_CONFLICT");
        assertEquals(version(assigned), version(rosterEntry(person)));
        assertEquals("GLOBAL_ADMIN", rosterEntry(person).get("signupGlobalRole").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role='GLOBAL_ADMIN'", Integer.class));
        assertEquals(0, count("member_role_audit"));
    }

    @Test void manualLinkPreservesExistingAccountRoleAndLinkedRosterCannotChangeSignupRole() throws Exception {
        for (String currentRole : List.of("MEMBER", "GLOBAL_ADMIN")) {
            JsonNode person = roster("합성 기존 계정 연결 " + currentRole);
            if ("MEMBER".equals(currentRole)) { person = success(signupRole(person, "GLOBAL_ADMIN"), 200); }
            Member existing = account(currentRole);
            JsonNode linked = success(administrator.mutate("POST", "/api/v1/roster/" + person.get("id").asString()
                    + "/link-account", Map.of("userId", Long.toString(existing.id()), "version", version(person))), 200);
            assertEquals(currentRole, members.findById(existing.id()).orElseThrow().globalRole());
            assertEquals(person.get("signupGlobalRole").asString(), linked.get("signupGlobalRole").asString());
            assertError(signupRole(linked, "GLOBAL_ADMIN"), 409, "ROSTER_ALREADY_LINKED");
            assertError(signupRole(person, "MEMBER"), 409, "ROSTER_ALREADY_LINKED");
        }
        assertEquals(0, count("member_role_audit"));
    }

    @Test void administratorDemotedWhilePreassignmentWaitsCannotUseTheOldAuthenticatedSession() throws Exception {
        account("GLOBAL_ADMIN"); // A separate administrator remains active.
        JsonNode person = roster("합성 배정 권한 경합");
        JsonNode csrf = administrator.csrf();
        try (Connection blocker = fixtureConnection(); var executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            execute(blocker, "SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
            var pending = executor.submit(() -> administrator.send("PATCH", rolePath(person),
                    Map.of("globalRole", "GLOBAL_ADMIN", "version", version(person)), csrf));
            awaitGuardWaiters(1);
            try (var update = blocker.prepareStatement("UPDATE users SET global_role='MEMBER',version=version+1 WHERE id=?")) {
                update.setLong(1, admin.id()); update.executeUpdate();
            }
            blocker.commit();
            assertError(pending.get(30, TimeUnit.SECONDS), 403, "FORBIDDEN");
        }
        assertEquals("MEMBER", jdbc.queryForObject("SELECT signup_global_role FROM roster_entries WHERE id=?",
                String.class, id(person)));
    }

    @Test void failedSignupPreservesAdminPreassignmentInvitationAndIdentityForRetry() throws Exception {
        Member collision = account("MEMBER");
        JsonNode person = success(signupRole(roster("합성 가입 재시도"), "GLOBAL_ADMIN"), 200);
        JsonNode invitation = issue(person);
        assertError(new Client().mutate("POST", "/api/v1/auth/register", registration(invitation, collision.loginId())),
                409, "LOGIN_ID_TAKEN");
        assertTrue(rosterEntry(person).get("userId").isNull());
        assertEquals("GLOBAL_ADMIN", rosterEntry(person).get("signupGlobalRole").asString());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE id=? AND used_at IS NULL",
                Integer.class, id(invitation)));
        assertEquals(2, count("users"));
        JsonNode registered = success(new Client().mutate("POST", "/api/v1/auth/register",
                registration(invitation, uniqueLogin())), 201);
        assertEquals("GLOBAL_ADMIN", registered.get("globalRole").asString());
        assertEquals(id(registered), jdbc.queryForObject("SELECT used_by FROM invitation_codes WHERE id=?",
                Long.class, id(invitation)));
    }

    @Test void lateRosterLinkFailureRollsBackTheNewAdministratorAndInvitationConsumptionTogether() throws Exception {
        JsonNode person = success(signupRole(roster("합성 관리자 가입 후반 실패"), "GLOBAL_ADMIN"), 200);
        JsonNode invitation = issue(person);
        String login = uniqueLogin();
        jdbc.execute("""
                CREATE FUNCTION roster_signup_link_failure() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id IS NOT NULL AND OLD.user_id IS NULL THEN
                        RAISE EXCEPTION 'synthetic final signup link failure' USING ERRCODE='23514';
                    END IF;
                    RETURN NEW;
                END $$
                """);
        jdbc.execute("CREATE TRIGGER roster_signup_link_failure BEFORE UPDATE OF user_id ON roster_entries "
                + "FOR EACH ROW EXECUTE FUNCTION roster_signup_link_failure()");
        try {
            assertError(new Client().mutate("POST", "/api/v1/auth/register", registration(invitation, login)),
                    409, "DATA_CONFLICT");
            assertEquals(1, count("users"));
            JsonNode unchanged = rosterEntry(person);
            assertTrue(unchanged.get("userId").isNull());
            assertEquals(version(person), version(unchanged));
            assertEquals("GLOBAL_ADMIN", unchanged.get("signupGlobalRole").asString());
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE id=? "
                    + "AND used_at IS NULL AND used_by IS NULL", Integer.class, id(invitation)));
            assertEquals(0, count("member_role_audit"));
        } finally {
            jdbc.execute("DROP TRIGGER roster_signup_link_failure ON roster_entries");
            jdbc.execute("DROP FUNCTION roster_signup_link_failure()");
        }
        JsonNode registered = success(new Client().mutate("POST", "/api/v1/auth/register",
                registration(invitation, login)), 201);
        assertEquals("GLOBAL_ADMIN", registered.get("globalRole").asString());
        assertEquals(2, count("users"));
        assertEquals(registered.get("id").asString(), rosterEntry(person).get("userId").asString());
    }

    @Test void pendingAdministratorAssignmentsDoNotCountAsAccountsForLastAdministratorProtection() throws Exception {
        for (int index = 0; index < 3; index++) {
            success(signupRole(roster("합성 미가입 관리자 " + index), "GLOBAL_ADMIN"), 200);
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role='GLOBAL_ADMIN' "
                + "AND status='ACTIVE'", Integer.class));
        assertError(administrator.mutate("PATCH", "/api/v1/users/" + admin.id() + "/role",
                Map.of("globalRole", "MEMBER", "version", admin.version())), 409, "LAST_GLOBAL_ADMIN");
        assertError(administrator.send("POST", "/api/v1/users/" + admin.id() + "/withdraw",
                Map.of("expectedVersion", admin.version()), administrator.csrf(), UUID.randomUUID().toString()),
                409, "LAST_GLOBAL_ADMIN");
        assertEquals("ACTIVE", members.findById(admin.id()).orElseThrow().status());
        assertEquals("GLOBAL_ADMIN", members.findById(admin.id()).orElseThrow().globalRole());
        assertEquals(0, count("lifecycle_operations"));
        assertEquals(0, count("member_role_audit"));
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM roster_entries WHERE user_id IS NULL "
                + "AND signup_global_role='GLOBAL_ADMIN'", Integer.class));
    }

    @Test void adminPreassignmentQueuedBeforeSignupIsAppliedInsideTheSameDirectoryGuard() throws Exception {
        JsonNode person = roster("합성 먼저 배정 경합");
        JsonNode invitation = issue(person);
        JsonNode adminCsrf = administrator.csrf();
        Client applicant = new Client(); JsonNode signupCsrf = applicant.csrf();
        List<HttpResponse<String>> responses = queued(
                () -> administrator.send("PATCH", rolePath(person),
                        Map.of("globalRole", "GLOBAL_ADMIN", "version", version(person)), adminCsrf),
                () -> applicant.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin()), signupCsrf));
        assertEquals(200, responses.get(0).statusCode());
        assertEquals("GLOBAL_ADMIN", success(responses.get(1), 201).get("globalRole").asString());
    }

    @Test void signupQueuedBeforePreassignmentMakesLateRoleWriteFailWithoutPromotingTheAccount() throws Exception {
        JsonNode person = roster("합성 먼저 가입 경합");
        JsonNode invitation = issue(person);
        Client applicant = new Client(); JsonNode signupCsrf = applicant.csrf();
        JsonNode adminCsrf = administrator.csrf();
        List<HttpResponse<String>> responses = queued(
                () -> applicant.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin()), signupCsrf),
                () -> administrator.send("PATCH", rolePath(person),
                        Map.of("globalRole", "GLOBAL_ADMIN", "version", version(person)), adminCsrf));
        JsonNode registered = success(responses.get(0), 201);
        assertEquals("MEMBER", registered.get("globalRole").asString());
        assertError(responses.get(1), 409, "ROSTER_ALREADY_LINKED");
        assertEquals("MEMBER", members.findById(id(registered)).orElseThrow().globalRole());
        assertEquals(0, count("member_role_audit"));
    }

    @Test void concurrentUseOfPreassignedAdminInvitationCreatesExactlyOneAdministratorAndLink() throws Exception {
        JsonNode person = success(signupRole(roster("합성 동시 관리자 가입"), "GLOBAL_ADMIN"), 200);
        JsonNode invitation = issue(person);
        Client first = new Client(); JsonNode firstCsrf = first.csrf();
        Client second = new Client(); JsonNode secondCsrf = second.csrf();
        List<HttpResponse<String>> responses = queued(
                () -> first.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin()), firstCsrf),
                () -> second.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin()), secondCsrf));
        JsonNode registered = success(responses.get(0), 201);
        assertEquals("GLOBAL_ADMIN", registered.get("globalRole").asString());
        assertError(responses.get(1), 410, "INVITATION_UNAVAILABLE");
        assertEquals(2, count("users"));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role='GLOBAL_ADMIN'", Integer.class));
        assertEquals(registered.get("id").asString(), rosterEntry(person).get("userId").asString());
        assertEquals(id(registered), jdbc.queryForObject("SELECT used_by FROM invitation_codes WHERE id=?",
                Long.class, id(invitation)));
    }

    @Test void v12UpgradeDefaultsRosterRolesAndPreservesAllV11RowsSequencesAndRoleAssignments() throws Exception {
        String schema = "signup_role_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway flyway = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("11").load();
        flyway.migrate();
        try (Connection connection = fixtureConnection()) {
            execute(connection, "SET search_path TO " + schema);
            execute(connection, """
                    INSERT INTO users(id,login_id,password_hash,display_name,global_role,status,created_at,version) VALUES
                    (101,'synthetic-upgrade-admin','synthetic-upgrade-hash','합성 업그레이드 관리자','GLOBAL_ADMIN','ACTIVE',CURRENT_TIMESTAMP,5),
                    (102,'synthetic-upgrade-member','synthetic-upgrade-hash','합성 업그레이드 회원','MEMBER','ACTIVE',CURRENT_TIMESTAMP,6);
                    INSERT INTO roster_entries(id,name,user_id,version) VALUES
                    (101,'합성 연결 관리자',101,3),(102,'합성 연결 회원',102,4),(103,'합성 미가입 팀장',NULL,5);
                    INSERT INTO teams(id,name,kind,version) VALUES
                    (201,'합성 보존 정규조','REGULAR',7),(202,'합성 보존 추가조','ADDITIONAL',8);
                    INSERT INTO team_memberships(team_id,roster_entry_id,role) VALUES
                    (201,101,'LEADER'),(201,102,'MEMBER'),(202,103,'LEADER');
                    INSERT INTO invitation_codes(code_digest,recipient_name,issued_by,roster_entry_id,created_at,expires_at,version)
                    VALUES (repeat('a',64),'합성 미가입 팀장',101,103,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP+INTERVAL '1 day',4);
                    INSERT INTO team_songs(team_id,title,sort_order,created_by,updated_by)
                    VALUES(201,'합성 보존 곡',2,101,101);
                    INSERT INTO member_role_audit(target_user_id,actor_user_id,old_role,new_role,created_at)
                    VALUES(102,101,'GLOBAL_ADMIN','MEMBER',CURRENT_TIMESTAMP);
                    INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
                    VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002',1,2,7776000,7776000002,'101');
                    INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes)
                    VALUES('00000000-0000-0000-0000-000000000001','synthetic-preserved-attribute',decode('abcd','hex'));
                    """);
            Map<String, List<String>> rowsBefore = snapshot(connection, schema);
            List<String> sequencesBefore = query(connection,
                    "SELECT sequencename||':'||COALESCE(last_value::text,'null') FROM pg_sequences WHERE schemaname='"
                            + schema + "' ORDER BY sequencename");
            List<String> historyBefore = query(connection,
                    "SELECT to_jsonb(h)::text FROM " + schema + ".flyway_schema_history h ORDER BY installed_rank");
            Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                    .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load().migrate();
            assertEquals(rowsBefore, snapshot(connection, schema));
            assertEquals(sequencesBefore, query(connection,
                    "SELECT sequencename||':'||COALESCE(last_value::text,'null') FROM pg_sequences WHERE schemaname='"
                            + schema + "' ORDER BY sequencename"));
            assertEquals(historyBefore, query(connection,
                    "SELECT to_jsonb(h)::text FROM " + schema
                            + ".flyway_schema_history h WHERE version IS DISTINCT FROM '12' ORDER BY installed_rank"));
            assertEquals(List.of("MEMBER", "MEMBER", "MEMBER"), query(connection,
                    "SELECT signup_global_role FROM " + schema + ".roster_entries ORDER BY id"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE " + schema + ".roster_entries SET signup_global_role='LEADER' WHERE id=103"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE " + schema + ".roster_entries SET signup_global_role=NULL WHERE id=103"));
        }
    }

    private Member account(String role) {
        return members.insert(uniqueLogin(), passwords.encode(password), "합성 계정", role, Instant.now()).orElseThrow();
    }
    private Client authenticated(Member member) throws Exception {
        Client client = new Client(); assertEquals(200, client.login(member.loginId()).statusCode()); return client;
    }
    private JsonNode roster(String name) throws Exception {
        return success(administrator.mutate("POST", "/api/v1/roster", Map.of("name", name)), 201);
    }
    private JsonNode issue(JsonNode person) throws Exception {
        return success(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("rosterEntryId", person.get("id").asString())), 201);
    }
    private HttpResponse<String> signupRole(JsonNode person, String role) throws Exception {
        return administrator.mutate("PATCH", rolePath(person), Map.of("globalRole", role, "version", version(person)));
    }
    private JsonNode rosterEntry(JsonNode person) throws Exception {
        return success(administrator.get("/api/v1/roster"), 200).valueStream()
                .filter(entry -> person.get("id").asString().equals(entry.get("id").asString())).findFirst().orElseThrow();
    }
    private Map<String, Object> registration(JsonNode invitation, String login) {
        Map<String, Object> input = new HashMap<>();
        input.put("invitationToken", invitation.get("token").asString());
        input.put("loginId", login); input.put("password", password); input.put("displayName", "합성 가입 계정");
        input.put("globalRole", "GLOBAL_ADMIN"); return input;
    }
    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> errorCode(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertFalse(response.body().contains(password));
        return json.readTree(response.body()).get("data");
    }
    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), () -> errorCode(response));
        assertEquals(code, errorCode(response));
        assertFalse(response.body().contains(password));
        assertFalse(response.body().contains("password_hash"));
    }
    private String errorCode(HttpResponse<String> response) {
        JsonNode code = json.readTree(response.body()).at("/error/code"); return code.isMissingNode() ? "No API error" : code.asString();
    }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private long id(JsonNode value) { return Long.parseLong(value.get("id").asString()); }
    private long version(JsonNode value) { return value.get("version").asLong(); }
    private String rolePath(JsonNode value) { return "/api/v1/roster/" + value.get("id").asString() + "/signup-role"; }
    private String uniqueLogin() { return "fixture-" + UUID.randomUUID().toString().substring(0, 16); }
    private Connection fixtureConnection() throws SQLException {
        assertEquals("roster_signup_role_test", POSTGRES.getDatabaseName());
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
    private void awaitGuardWaiters(int count) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject("""
                SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock'
                AND query ILIKE '%directory_mutation_guard%'
                """, Integer.class) >= count);
    }
    private List<HttpResponse<String>> queued(Request first, Request second) throws Exception {
        try (Connection blocker = fixtureConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false); execute(blocker, "SELECT id FROM directory_mutation_guard WHERE id=1 FOR UPDATE");
            var a = executor.submit(first::run); awaitGuardWaiters(1);
            var b = executor.submit(second::run); awaitGuardWaiters(2);
            blocker.commit(); return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }
    private static void execute(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.execute(sql); }
    }
    private static List<String> query(Connection connection, String sql) throws SQLException {
        List<String> result = new ArrayList<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            while (rows.next()) { result.add(rows.getString(1)); }
        }
        return result;
    }
    private static Map<String, List<String>> snapshot(Connection connection, String schema) throws SQLException {
        Map<String, List<String>> result = new TreeMap<>();
        for (String table : query(connection, "SELECT tablename FROM pg_tables WHERE schemaname='" + schema
                + "' AND tablename<>'flyway_schema_history' ORDER BY tablename")) {
            assertTrue(table.matches("[a-z_][a-z0-9_]*"));
            result.put(table, query(connection, "SELECT (to_jsonb(t)-'signup_global_role')::text FROM "
                    + schema + "." + table + " t ORDER BY 1"));
        }
        return result;
    }
    @FunctionalInterface private interface Request { HttpResponse<String> run() throws Exception; }

    private final class Client {
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        JsonNode csrf() throws Exception { return success(get("/api/v1/auth/csrf"), 200); }
        HttpResponse<String> login(String login) throws Exception {
            return mutate("POST", "/api/v1/auth/login", Map.of("loginId", login, "password", password));
        }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf());
        }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf) throws Exception {
            return send(method, path, body, csrf, null);
        }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf, String key) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (key != null) { builder.header("Idempotency-Key", key); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
