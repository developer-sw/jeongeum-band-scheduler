package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.jeongeum.scheduler.auth.api.AuthRequests;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.auth.service.AuthenticationInputs;
import kr.jeongeum.scheduler.invitation.service.InvitationTokens;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.SessionRepository;
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
@Timeout(90)
class AuthenticationIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("authentication_test").withUsername("authentication_test")
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
    @Autowired SessionRepository<?> sessions;
    @Autowired ApplicationContext application;

    private String password;
    private Member admin;
    private Client administrator;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        password = UUID.randomUUID().toString();
        admin = members.insert(uniqueLogin(), passwords.encode(password), "합성 관리자", "GLOBAL_ADMIN",
                Instant.now()).orElseThrow();
        administrator = new Client();
        assertEquals(200, administrator.login(admin.loginId(), password).statusCode());
    }

    @Test
    void invitationRegistrationLoginAndLogoutPersistOnlyIdentityAndRotateSessionAndCsrf() throws Exception {
        JsonNode invite = issue(administrator, null, null);
        String token = invite.get("token").asString();
        assertEquals(43, token.length());
        assertEquals(InvitationTokens.digest(token), jdbc.queryForObject(
                "SELECT code_digest FROM invitation_codes WHERE id = ?", String.class, id(invite)));

        Client member = new Client();
        var inspection = member.mutate("POST", "/api/v1/auth/invitations/inspect", Map.of("token", token));
        assertEquals(200, inspection.statusCode());
        assertEquals(1, data(inspection).size(), "Inspection must not reveal recipient or administrator data");
        String loginId = uniqueLogin();
        var registration = member.mutate("POST", "/api/v1/auth/register", Map.of(
                "invitationToken", token, "loginId", " " + loginId.toUpperCase() + " ",
                "password", password, "displayName", " 합성 회원 ", "globalRole", "GLOBAL_ADMIN"));
        assertEquals(201, registration.statusCode());
        JsonNode profile = data(registration);
        assertEquals(loginId, profile.get("loginId").asString());
        assertEquals("합성 회원", profile.get("displayName").asString());
        assertEquals("MEMBER", profile.get("globalRole").asString());
        assertTrue(profile.get("id").isString());
        assertEquals(0, profile.get("teamMemberships").size());
        assertFalse(registration.body().contains(password));
        assertFalse(registration.body().contains("passwordHash"));
        assertError(member.get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");

        JsonNode beforeLoginCsrf = member.csrf();
        String oldCookie = member.cookie();
        var login = member.send("POST", "/api/v1/auth/login", Map.of("loginId", loginId, "password", password),
                beforeLoginCsrf);
        assertEquals(200, login.statusCode());
        assertNotEquals(oldCookie, member.cookie());
        String currentSession = member.sessionId();
        assertNull(sessions.findById(decodeCookie(oldCookie)));
        var stored = sessions.findById(currentSession);
        SecurityContext context = stored.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertEquals(new MemberPrincipal(Long.parseLong(profile.get("id").asString())),
                context.getAuthentication().getPrincipal());
        assertNull(context.getAuthentication().getCredentials());
        assertTrue(context.getAuthentication().getAuthorities().isEmpty());
        String storedBytes = new String(jdbc.queryForObject("""
                SELECT a.attribute_bytes FROM spring_session_attributes a JOIN spring_session s
                    ON s.primary_id = a.session_primary_id WHERE s.session_id = ? AND a.attribute_name = ?
                """, byte[].class, currentSession,
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY), StandardCharsets.ISO_8859_1);
        assertFalse(storedBytes.contains(password));
        assertFalse(storedBytes.contains(members.findByLoginId(loginId).orElseThrow().passwordHash()));
        assertError(member.send("POST", "/api/v1/auth/logout", Map.of(), beforeLoginCsrf), 403, "CSRF_INVALID");
        assertEquals(200, member.get("/api/v1/auth/me").statusCode());
        assertError(member.get("/api/v1/invitations"), 403, "FORBIDDEN");

        JsonNode newCsrf = member.csrf();
        String logoutCookie = member.cookie();
        var logout = member.send("POST", "/api/v1/auth/logout", Map.of(), newCsrf);
        assertEquals(204, logout.statusCode());
        assertTrue(logout.headers().allValues("Set-Cookie").stream().anyMatch(value -> value.contains("Max-Age=0")));
        assertNull(sessions.findById(currentSession));
        assertError(replayCookie(logoutCookie, "/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        assertError(replayCookie(oldCookie, "/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        assertNotEquals(newCsrf.get("token").asString(), member.csrf().get("token").asString());
    }

    @Test
    void rejectsReusedExpiredRevokedAndUnknownInvitationsAndReturnsSafeAdminList() throws Exception {
        JsonNode used = issue(administrator, null, null);
        Client newcomer = new Client();
        assertEquals(201, register(newcomer, used, uniqueLogin(), password).statusCode());
        assertError(register(new Client(), used, uniqueLogin(), password), 410, "INVITATION_UNAVAILABLE");
        assertError(administrator.mutate("DELETE", "/api/v1/invitations/" + id(used), Map.of()),
                409, "INVITATION_ALREADY_USED");

        JsonNode revoked = issue(administrator, null, null);
        assertEquals(204, administrator.mutate("DELETE", "/api/v1/invitations/" + id(revoked), Map.of()).statusCode());
        assertEquals(204, administrator.mutate("DELETE", "/api/v1/invitations/" + id(revoked), Map.of()).statusCode());
        assertError(register(new Client(), revoked, uniqueLogin(), password), 410, "INVITATION_UNAVAILABLE");

        JsonNode expired = issue(administrator, null, null);
        jdbc.update("UPDATE invitation_codes SET created_at = ?, expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), id(expired));
        assertError(register(new Client(), expired, uniqueLogin(), password), 410, "INVITATION_UNAVAILABLE");
        assertError(new Client().mutate("POST", "/api/v1/auth/invitations/inspect",
                Map.of("token", InvitationTokens.create())), 410, "INVITATION_UNAVAILABLE");

        var list = administrator.get("/api/v1/invitations?limit=2");
        assertEquals(200, list.statusCode());
        assertEquals(2, data(list).get("items").size());
        assertTrue(json.readTree(list.body()).at("/meta/nextCursor").isString());
        assertFalse(list.body().contains(used.get("token").asString()));
        assertFalse(list.body().contains("codeDigest"));
        assertFalse(list.body().contains("\"token\""));
        assertEquals("EXPIRED", data(list).get("items").get(0).get("status").asString());
        assertTrue(data(list).get("items").get(0).get("usedAt").isNull());
        assertEquals("REVOKED", data(list).get("items").get(1).get("status").asString());
    }

    @Test
    void validatesRegistrationBytesAndNormalizedIdsWithoutLeakingRejectedSecrets() throws Exception {
        JsonNode invite = issue(administrator, null, null);
        Client client = new Client();
        assertError(register(client, invite, ".invalid", password), 400, "INVALID_INPUT");
        assertError(register(client, invite, uniqueLogin(), "short"), 400, "INVALID_INPUT");
        String oversized = "가".repeat(25);
        var invalid = register(client, invite, uniqueLogin(), oversized);
        assertError(invalid, 400, "INVALID_INPUT");
        assertFalse(invalid.body().contains(oversized));
        assertEquals(201, register(client, invite, uniqueLogin(), "가".repeat(24)).statusCode());
        assertEquals("AVAILABLE", issue(administrator, null, null).get("status").asString());
        assertFalse(new AuthRequests.Login("not-displayed", password).toString().contains(password));
        assertFalse(new AuthRequests.Register(invite.get("token").asString(), "not-displayed", password, "name")
                .toString().contains(invite.get("token").asString()));
        assertThrows(RuntimeException.class, () -> AuthenticationInputs.name("😀".repeat(41)));
        assertEquals("😀".repeat(40), AuthenticationInputs.name("😀".repeat(40)));
    }

    @Test
    void publicMutationsRequireCsrfAndLoginFailuresAreUniformAndLimitedPerNormalizedIdentifier() throws Exception {
        Client client = new Client();
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/invitations/inspect")) {
            assertError(client.send("POST", path, Map.of(), null), 403, "CSRF_INVALID");
        }
        assertError(client.login(admin.loginId(), "incorrect-value"), 401, "INVALID_CREDENTIALS");
        assertError(client.login(uniqueLogin(), password), 401, "INVALID_CREDENTIALS");
        jdbc.update("UPDATE users SET status = 'INACTIVE', version = version + 1 WHERE id = ?", admin.id());
        assertError(client.login(admin.loginId(), password), 401, "INVALID_CREDENTIALS");
        jdbc.update("UPDATE users SET status = 'ACTIVE', version = version + 1 WHERE id = ?", admin.id());
        assertError(client.login("invalid!", password), 401, "INVALID_CREDENTIALS");

        String missingId = uniqueLogin();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertError(client.login(" " + missingId.toUpperCase() + " ", password), 401, "INVALID_CREDENTIALS");
        }
        assertError(client.login(missingId, password), 429, "TOO_MANY_REQUESTS");
        // An unrelated member sharing this process/proxy remains able to log in.
        assertEquals(200, client.login(admin.loginId(), password).statusCode());
    }

    @Test
    void demotionAndInactiveStatusImmediatelyAffectExistingSessionAndAdministratorService() throws Exception {
        jdbc.update("UPDATE users SET global_role = 'MEMBER', version = version + 1 WHERE id = ?", admin.id());
        assertEquals("MEMBER", data(administrator.get("/api/v1/auth/me")).get("globalRole").asString());
        assertError(administrator.get("/api/v1/invitations"), 403, "FORBIDDEN");
        assertError(administrator.mutate("POST", "/api/v1/invitations", Map.of("recipientName", "합성 대상")),
                403, "FORBIDDEN");
        jdbc.update("UPDATE users SET status = 'WITHDRAWN', version = version + 1 WHERE id = ?", admin.id());
        String oldCookie = administrator.cookie();
        assertError(administrator.get("/api/v1/auth/me"), 403, "INACTIVE_USER");
        assertNull(sessions.findById(decodeCookie(oldCookie)));
        assertError(administrator.get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void concurrentInvitationConsumptionCreatesExactlyOneMember() throws Exception {
        JsonNode invitation = issue(administrator, null, null);
        Client first = new Client();
        Client second = new Client();
        JsonNode firstCsrf = first.csrf();
        JsonNode secondCsrf = second.csrf();
        List<HttpResponse<String>> results = race(
                () -> first.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin(), password), firstCsrf),
                () -> second.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin(), password), secondCsrf));
        assertEquals(List.of(201, 410), results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE used_at IS NOT NULL", Integer.class));
    }

    @Test
    void concurrentDuplicateLoginRollsBackLosingInvitationAndAllowsRetry() throws Exception {
        JsonNode firstInvite = issue(administrator, null, null);
        JsonNode secondInvite = issue(administrator, null, null);
        Client first = new Client();
        Client second = new Client();
        JsonNode firstCsrf = first.csrf();
        JsonNode secondCsrf = second.csrf();
        String duplicate = uniqueLogin();
        List<HttpResponse<String>> results = race(
                () -> first.send("POST", "/api/v1/auth/register", registration(firstInvite, duplicate, password), firstCsrf),
                () -> second.send("POST", "/api/v1/auth/register", registration(secondInvite, duplicate.toUpperCase(), password), secondCsrf));
        assertEquals(List.of(201, 409), results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE used_at IS NOT NULL", Integer.class));
        JsonNode unused = jdbc.queryForObject("SELECT used_at IS NULL FROM invitation_codes WHERE id = ?",
                Boolean.class, id(firstInvite)) ? firstInvite : secondInvite;
        assertEquals(201, register(new Client(), unused, uniqueLogin(), password).statusCode());
    }

    @Test
    void concurrentReissueHasOneSuccessAndDoesNotRevokeSameNamedIndependentInvitation() throws Exception {
        JsonNode original = issue(administrator, null, null);
        JsonNode independent = issue(administrator, null, null);
        Member distinctAdministrator = members.insert(uniqueLogin(), passwords.encode(password),
                "다른 합성 관리자", "GLOBAL_ADMIN", Instant.now()).orElseThrow();
        Client anotherAdmin = new Client();
        assertEquals(200, anotherAdmin.login(distinctAdministrator.loginId(), password).statusCode());
        JsonNode firstCsrf = administrator.csrf();
        JsonNode secondCsrf = anotherAdmin.csrf();
        Map<String, Object> body = Map.of("recipientName", "합성 대상", "replacesInvitationId", Long.toString(id(original)));
        List<HttpResponse<String>> results = race(
                () -> administrator.send("POST", "/api/v1/invitations", body, firstCsrf),
                () -> anotherAdmin.send("POST", "/api/v1/invitations", body, secondCsrf));
        assertEquals(List.of(201, 410), results.stream().map(HttpResponse::statusCode).sorted().toList());
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM invitation_codes", Integer.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE revoked_at IS NULL", Integer.class));
        assertEquals(200, new Client().mutate("POST", "/api/v1/auth/invitations/inspect",
                Map.of("token", independent.get("token").asString())).statusCode());
    }

    @Test
    void administratorRoleIsRecheckedAfterWaitingForTransactionRowLock() throws Exception {
        JsonNode csrf = administrator.csrf();
        try (var connection = java.sql.DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE")) {
                lock.setLong(1, admin.id());
                try (var rows = lock.executeQuery()) { assertTrue(rows.next()); }
            }
            var issuing = executor.submit(() -> administrator.send("POST", "/api/v1/invitations",
                    Map.of("recipientName", "합성 대상"), csrf));
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                    Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                                AND query LIKE 'SELECT * FROM users WHERE id = % FOR NO KEY UPDATE')
                            """, Boolean.class)));
            try (var update = connection.prepareStatement(
                    "UPDATE users SET global_role = 'MEMBER', version = version + 1 WHERE id = ?")) {
                update.setLong(1, admin.id());
                update.executeUpdate();
            }
            connection.commit();
            assertError(issuing.get(20, TimeUnit.SECONDS), 403, "FORBIDDEN");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_codes", Integer.class));
        }
    }

    @Test
    void registerAndRevokeRaceNeverLeavesUsedAndRevokedTogether() throws Exception {
        JsonNode invitation = issue(administrator, null, null);
        Client newcomer = new Client();
        JsonNode registerCsrf = newcomer.csrf();
        JsonNode adminCsrf = administrator.csrf();
        List<HttpResponse<String>> results = race(
                () -> newcomer.send("POST", "/api/v1/auth/register", registration(invitation, uniqueLogin(), password), registerCsrf),
                () -> administrator.send("DELETE", "/api/v1/invitations/" + id(invitation), Map.of(), adminCsrf));
        int registrationStatus = results.get(0).statusCode();
        assertTrue(registrationStatus == 201 || registrationStatus == 410);
        assertEquals(registrationStatus == 201 ? 409 : 204, results.get(1).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE used_at IS NOT NULL "
                + "AND revoked_at IS NOT NULL", Integer.class));
        assertEquals(registrationStatus == 201 ? 2 : 1, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
    }

    @Test
    @Timeout(180)
    void nonWebBootstrapRunsOnceAndConcurrentProcessesCannotCreateOrPromoteAdditionalMembers() throws Exception {
        assertFalse(application.containsBean("adminBootstrapService"), "Web profile cannot initialize administrators");
        jdbc.execute("TRUNCATE invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        String firstLogin = uniqueLogin();
        String firstPassword = UUID.randomUUID().toString();
        Process first = bootstrap(firstLogin, firstPassword);
        Process second = bootstrap(uniqueLogin(), UUID.randomUUID().toString());
        ProcessResult firstResult = finish(first);
        ProcessResult secondResult = finish(second);
        assertEquals(List.of(0, 1), List.of(firstResult.exit(), secondResult.exit()).stream().sorted().toList());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role = 'GLOBAL_ADMIN'", Integer.class));
        String existingHash = jdbc.queryForObject("SELECT password_hash FROM users", String.class);
        ProcessResult retry = finish(bootstrap(uniqueLogin(), UUID.randomUUID().toString()));
        assertNotEquals(0, retry.exit());
        assertEquals(existingHash, jdbc.queryForObject("SELECT password_hash FROM users", String.class));
        for (ProcessResult result : List.of(firstResult, secondResult, retry)) {
            assertFalse(result.output().contains("Tomcat started"));
            assertFalse(result.output().contains(firstPassword));
            assertFalse(result.output().contains(firstLogin));
        }
    }

    private Process bootstrap(String loginId, String bootstrapPassword) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name")
                .toLowerCase().contains("windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar",
                "build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar",
                "--spring.profiles.active=bootstrap-admin", "--spring.main.web-application-type=none");
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("APP_DB_URL", POSTGRES.getJdbcUrl());
        env.put("APP_DB_USERNAME", POSTGRES.getUsername());
        env.put("APP_DB_PASSWORD", POSTGRES.getPassword());
        env.put("APP_BOOTSTRAP_LOGIN_ID", loginId);
        env.put("APP_BOOTSTRAP_PASSWORD", bootstrapPassword);
        env.put("APP_BOOTSTRAP_DISPLAY_NAME", "합성 초기 관리자");
        return builder.start();
    }

    private ProcessResult finish(Process process) throws Exception {
        CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
            try { return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); }
            catch (Exception exception) { throw new RuntimeException("Could not capture bootstrap process output"); }
        });
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Bootstrap process did not exit");
        }
        return new ProcessResult(process.exitValue(), output.get(10, TimeUnit.SECONDS));
    }

    private record ProcessResult(int exit, String output) {
        @Override public String toString() { return "ProcessResult[exit=" + exit + "]"; }
    }

    private JsonNode issue(Client client, String replaces, Instant expiresAt) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("recipientName", "합성 대상");
        if (replaces != null) { body.put("replacesInvitationId", replaces); }
        if (expiresAt != null) { body.put("expiresAt", expiresAt.toString()); }
        var response = client.mutate("POST", "/api/v1/invitations", body);
        assertEquals(201, response.statusCode());
        return data(response);
    }

    private Map<String, Object> registration(JsonNode invitation, String loginId, String newPassword) {
        return Map.of("invitationToken", invitation.get("token").asString(), "loginId", loginId,
                "password", newPassword, "displayName", "합성 회원");
    }

    private HttpResponse<String> register(Client client, JsonNode invitation, String loginId, String newPassword)
            throws Exception {
        return client.mutate("POST", "/api/v1/auth/register", registration(invitation, loginId, newPassword));
    }

    private long id(JsonNode node) { return Long.parseLong(node.get("id").asString()); }
    private String uniqueLogin() { return "fixture-" + UUID.randomUUID().toString().substring(0, 16); }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private String decodeCookie(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    private HttpResponse<String> replayCookie(String cookie, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Cookie", "SESSION=" + cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @FunctionalInterface
    private interface Request { HttpResponse<String> run() throws Exception; }

    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private final class Client {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        JsonNode csrf() throws Exception {
            var response = get("/api/v1/auth/csrf");
            assertEquals(200, response.statusCode());
            return data(response);
        }
        HttpResponse<String> login(String loginId, String newPassword) throws Exception {
            return mutate("POST", "/api/v1/auth/login", Map.of("loginId", loginId, "password", newPassword));
        }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf());
        }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(20));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        String cookie() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("SESSION"))
                    .findFirst().orElseThrow().getValue();
        }
        String sessionId() { return decodeCookie(cookie()); }
    }
}
