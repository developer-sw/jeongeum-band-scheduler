package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.jeongeum.scheduler.auth.security.JdbcSessionCsrfTokenRepository;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.session.Session;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"jeongeum.recurring.worker.enabled=false", "jeongeum.push.worker.enabled=false",
                "jeongeum.reminder.worker.enabled=false"})
@Timeout(90)
class CsrfConcurrencyIntegrationTest {
    private static final String ATTRIBUTE = JdbcSessionCsrfTokenRepository.ATTRIBUTE_NAME;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("csrf_concurrency_test").withUsername("csrf_concurrency_test")
            .withPassword(UUID.randomUUID().toString()).withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("APP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcIndexedSessionRepository sessions;
    @Autowired MemberRepository members;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String password;
    private Member member;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        password = UUID.randomUUID().toString();
        member = members.insert("fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(password), "합성 CSRF 경합 회원", "MEMBER", Instant.now()).orElseThrow();
    }

    @Test
    void independentConcurrentFirstGetsAfterLoginReturnUsableTokensWithoutPrewarming() throws Exception {
        HttpResponse<String> anonymous = request(port, "GET", "/api/v1/auth/csrf", null, null, null);
        String oldCookie = cookie(anonymous);
        HttpResponse<String> login = request(port, "POST", "/api/v1/auth/login", oldCookie, data(anonymous),
                Map.of("loginId", member.loginId(), "password", password));
        assertEquals(200, login.statusCode());
        String authenticated = cookie(login);
        assertFalse(oldCookie.equals(authenticated), "Login rotates the session cookie");
        assertEquals(0, tokenCount(authenticated), "No prewarming: both requests must initialize the absent token");
        List<HttpResponse<String>> responses = blockedInitializations(authenticated, port, port);
        for (HttpResponse<String> response : responses) {
            assertEquals(200, response.statusCode());
            assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
            assertEquals(200, request(port, "POST", "/api/v1/notifications/read-all", authenticated, data(response),
                    Map.of("through", Instant.now().minusSeconds(1).toString())).statusCode());
        }
        assertEquals(1, tokenCount(authenticated));
        assertFalse(data(responses.get(0)).get("token").asString().equals(data(responses.get(1)).get("token").asString()),
                "BREACH XOR masking remains independently randomized per response");
        assertCsrfRejected(request(port, "POST", "/api/v1/notifications/read-all", authenticated, null,
                Map.of("through", Instant.now().minusSeconds(1).toString())));
    }

    @Test
    void concurrentFirstGetsForAnExistingAnonymousSessionShareOneCanonicalRandomToken() throws Exception {
        Session session = newSession();
        String existingCookie = encode(session.getId());
        assertEquals(0, tokenCount(existingCookie));
        List<HttpResponse<String>> responses = blockedInitializations(existingCookie, port, port);
        for (HttpResponse<String> response : responses) {
            assertEquals(200, response.statusCode());
            assertEquals(410, inspect(port, existingCookie, data(response)).statusCode());
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty(), "Anonymous reads do not renew cookie");
        }
        assertEquals(1, tokenCount(existingCookie));
    }

    @Test
    void simultaneousCookielessFirstGetsKeepTheirIndependentSessionAndCsrfPairings() throws Exception {
        List<HttpResponse<String>> responses = race(
                () -> request(port, "GET", "/api/v1/auth/csrf", null, null, null),
                () -> request(port, "GET", "/api/v1/auth/csrf", null, null, null));
        assertEquals(200, responses.get(0).statusCode());
        assertEquals(200, responses.get(1).statusCode());
        String first = cookie(responses.get(0));
        String second = cookie(responses.get(1));
        assertFalse(first.equals(second), "Independent anonymous sessions have distinct cookies");
        assertEquals(410, inspect(port, first, data(responses.get(0))).statusCode());
        assertEquals(410, inspect(port, second, data(responses.get(1))).statusCode());
        assertCsrfRejected(inspect(port, second, data(responses.get(0))));
    }

    @Test
    void preservesExistingSpringSessionSerializedTokenAndRejectsOldTokenAfterLoginAndLogout() throws Exception {
        Session session = newSession();
        session.setAttribute(ATTRIBUTE, new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString()));
        String anonymousCookie = encode(session.getId());
        byte[] before = storedToken(anonymousCookie);
        HttpResponse<String> prepared = request(port, "GET", "/api/v1/auth/csrf", anonymousCookie, null, null);
        assertEquals(200, prepared.statusCode());
        assertTrue(Arrays.equals(before, storedToken(anonymousCookie)), "Existing serialized CSRF token is preserved");
        assertEquals(410, inspect(port, anonymousCookie, data(prepared)).statusCode());
        HttpResponse<String> login = request(port, "POST", "/api/v1/auth/login", anonymousCookie, data(prepared),
                Map.of("loginId", member.loginId(), "password", password));
        assertEquals(200, login.statusCode());
        String currentCookie = cookie(login);
        assertNull(sessions.findById(decode(anonymousCookie)));
        assertCsrfRejected(request(port, "POST", "/api/v1/auth/logout", currentCookie, data(prepared), null));
        HttpResponse<String> newToken = request(port, "GET", "/api/v1/auth/csrf", currentCookie, null, null);
        assertEquals(200, newToken.statusCode());
        assertEquals(204, request(port, "POST", "/api/v1/auth/logout", currentCookie, data(newToken), null).statusCode());
        assertNull(sessions.findById(decode(currentCookie)));
        assertEquals(401, request(port, "GET", "/api/v1/auth/me", currentCookie, null, null).statusCode());
        assertEquals(0, tokenCount(currentCookie));
    }

    @Test
    @Timeout(120)
    void independentJvmFirstInitializationAndRestartUseTheSamePersistedSessionToken() throws Exception {
        Session session = newSession();
        String existingCookie = encode(session.getId());
        int otherPort;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            otherPort = socket.getLocalPort();
        }
        Process other = startJvm(otherPort);
        try {
            awaitJvm(other, otherPort);
            List<HttpResponse<String>> results = blockedInitializations(existingCookie, port, otherPort);
            for (HttpResponse<String> result : results) assertEquals(200, result.statusCode());
            assertEquals(410, inspect(otherPort, existingCookie, data(results.get(0))).statusCode());
            assertEquals(410, inspect(port, existingCookie, data(results.get(1))).statusCode());
            byte[] beforeRestart = storedToken(existingCookie);
            stopJvm(other);
            other = startJvm(otherPort);
            awaitJvm(other, otherPort);
            assertEquals(410, inspect(otherPort, existingCookie, data(results.get(0))).statusCode());
            assertTrue(Arrays.equals(beforeRestart, storedToken(existingCookie)), "Restart preserves serialized token");
            assertEquals(1, tokenCount(existingCookie));
        } finally { stopJvm(other); }
    }

    private List<HttpResponse<String>> blockedInitializations(String cookie, int firstPort, int secondPort)
            throws Exception {
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword()); var executor = Executors.newFixedThreadPool(2)) {
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement()) {
                statement.execute("LOCK TABLE spring_session_attributes IN SHARE MODE");
            }
            CountDownLatch start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return request(firstPort, "GET", "/api/v1/auth/csrf", cookie, null, null); });
            var second = executor.submit(() -> { start.await(); return request(secondPort, "GET", "/api/v1/auth/csrf", cookie, null, null); });
            start.countDown();
            try {
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() ->
                        jdbc.queryForObject("""
                                SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock'
                                AND query LIKE '%INSERT INTO spring_session_attributes%'
                                """, Integer.class) == 2);
            } finally { connection.commit(); }
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        }
    }

    private Process startJvm(int otherPort) throws Exception {
        Path javaBinary = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name")
                .toLowerCase().contains("windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(javaBinary.toString(), "-Xmx192m", "-jar",
                "build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar", "--spring.profiles.active=local",
                "--server.port=" + otherPort, "--jeongeum.recurring.worker.enabled=false",
                "--jeongeum.push.worker.enabled=false", "--jeongeum.reminder.worker.enabled=false");
        builder.environment().put("APP_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("APP_DB_USERNAME", POSTGRES.getUsername());
        builder.environment().put("APP_DB_PASSWORD", POSTGRES.getPassword());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        CompletableFuture.runAsync(() -> {
            try { process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (java.io.IOException ignored) { }
        });
        return process;
    }

    private void awaitJvm(Process process, int otherPort) {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(40)).pollInterval(Duration.ofMillis(250)).until(() -> {
            assertTrue(process.isAlive(), "Isolated CSRF JVM exited during startup");
            try { return request(otherPort, "GET", "/api/v1/health", null, null, null).statusCode() == 200; }
            catch (Exception ignored) { return false; }
        });
    }

    private void stopJvm(Process process) throws Exception {
        if (!process.isAlive()) return;
        process.destroy();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Isolated CSRF JVM did not stop");
        }
    }

    @FunctionalInterface
    private interface Request { HttpResponse<String> run() throws Exception; }

    private List<HttpResponse<String>> race(Request first, Request second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> { start.await(); return first.run(); });
            var b = executor.submit(() -> { start.await(); return second.run(); });
            start.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private HttpResponse<String> inspect(int targetPort, String cookie, JsonNode token) throws Exception {
        return request(targetPort, "POST", "/api/v1/auth/invitations/inspect", cookie, token,
                Map.of("token", UUID.randomUUID().toString() + UUID.randomUUID()));
    }

    private HttpResponse<String> request(int targetPort, String method, String path, String cookie, JsonNode token,
            Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
                .timeout(Duration.ofSeconds(20));
        if (cookie != null) builder.header("Cookie", "SESSION=" + cookie);
        if (token != null) builder.header(token.get("headerName").asString(), token.get("token").asString());
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertCsrfRejected(HttpResponse<String> response) {
        assertEquals(403, response.statusCode());
        assertEquals("CSRF_INVALID", json.readTree(response.body()).at("/error/code").asString());
    }

    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private String encode(String id) { return Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)); }
    private String decode(String cookie) { return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8); }
    private String cookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
                .map(value -> value.substring("SESSION=".length(), value.indexOf(';'))).findFirst().orElseThrow();
    }
    private int tokenCount(String cookie) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM spring_session_attributes a JOIN spring_session s
                ON s.primary_id = a.session_primary_id WHERE s.session_id = ? AND a.attribute_name = ?
                """, Integer.class, decode(cookie), ATTRIBUTE);
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private Session newSession() {
        SessionRepository repository = sessions;
        Session session = repository.createSession();
        repository.save(session);
        return session;
    }
    private byte[] storedToken(String cookie) {
        return jdbc.queryForObject("""
                SELECT a.attribute_bytes FROM spring_session_attributes a JOIN spring_session s
                ON s.primary_id = a.session_primary_id WHERE s.session_id = ? AND a.attribute_name = ?
                """, byte[].class, decode(cookie), ATTRIBUTE);
    }
}
