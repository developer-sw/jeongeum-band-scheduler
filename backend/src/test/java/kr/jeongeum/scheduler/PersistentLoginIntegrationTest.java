package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
class PersistentLoginIntegrationTest {
    private static final int PERSISTENT_SECONDS = 90 * 24 * 60 * 60;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("persistent_login_test").withUsername("persistent_login_test")
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
    @Autowired SessionRepository<?> sessions;
    @Autowired ObjectMapper json;

    private String password;
    private Member member;

    @BeforeEach
    void setup() {
        jdbc.execute("TRUNCATE invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        password = UUID.randomUUID().toString();
        member = members.insert("fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(password), "합성 로그인 유지 회원", "MEMBER", Instant.now()).orElseThrow();
    }

    @Test
    void loginIssuesNinetyDayHttpOnlyCookieAndJdbcSessionAndRotatesAnonymousIdentity() throws Exception {
        Client client = new Client(port);
        JsonNode csrf = client.csrf();
        String anonymousCookie = client.cookie();
        String anonymousId = decodeCookie(anonymousCookie);
        var loggedIn = client.send("POST", "/api/v1/auth/login",
                Map.of("loginId", member.loginId(), "password", password), csrf);
        assertEquals(200, loggedIn.statusCode());
        String cookie = persistentCookie(loggedIn);
        assertFalse(anonymousCookie.equals(cookie), "Login must rotate the session identity");
        assertNull(sessions.findById(anonymousId));
        assertPersistentMetadata(decodeCookie(cookie));
        assertEquals(Long.toString(member.id()), data(loggedIn).get("id").asString());
        assertFalse(loggedIn.body().contains(password));
    }

    @Test
    void repeatedAnonymousCsrfAndUnauthorizedReadsDoNotContinuouslyRenewPersistentCookie() throws Exception {
        Client client = new Client(port);
        var firstCsrf = client.get("/api/v1/auth/csrf");
        assertEquals(200, firstCsrf.statusCode());
        // The configured lifetime also applies when the first CSRF session is created.
        persistentCookie(firstCsrf);
        for (int repeat = 0; repeat < 2; repeat++) {
            var csrf = client.get("/api/v1/auth/csrf");
            assertEquals(200, csrf.statusCode());
            assertNoPositiveSessionCookie(csrf);
            var anonymous = client.get("/api/v1/auth/me");
            assertError(anonymous, 401, "AUTHENTICATION_REQUIRED");
            assertNoPositiveSessionCookie(anonymous);
        }
    }

    @Test
    void restoresCookieInNewClientAfterFortyFiveIdleDaysAndSlidesSameSessionAndCookie() throws Exception {
        Client original = login(new Client(port));
        String originalCookie = original.cookie();
        String sessionId = decodeCookie(originalCookie);
        ageSession(sessionId, Duration.ofDays(45), PERSISTENT_SECONDS);
        assertTrue(jdbc.queryForObject("SELECT last_access_time FROM spring_session WHERE session_id = ?",
                Long.class, sessionId) < System.currentTimeMillis() - Duration.ofMinutes(30).toMillis());

        // A fresh transport/cookie store has only the persisted browser cookie, no previous client state.
        Client reopened = new Client(port, originalCookie);
        long before = System.currentTimeMillis();
        var restored = reopened.get("/api/v1/auth/me");
        long after = System.currentTimeMillis();
        assertEquals(200, restored.statusCode());
        String refreshedCookie = persistentCookie(restored);
        assertTrue(originalCookie.equals(refreshedCookie), "A refresh must keep the existing session identity");
        assertEquals(Long.toString(member.id()), data(restored).get("id").asString());
        assertPersistentMetadata(sessionId);
        long accessed = jdbc.queryForObject("SELECT last_access_time FROM spring_session WHERE session_id = ?",
                Long.class, sessionId);
        long expires = jdbc.queryForObject("SELECT expiry_time FROM spring_session WHERE session_id = ?",
                Long.class, sessionId);
        assertTrue(accessed >= before - 2000 && accessed <= after + 2000, "Successful use must refresh last access");
        assertTrue(expires >= before + Duration.ofDays(90).toMillis() - 2000
                && expires <= after + Duration.ofDays(90).toMillis() + 2000, "Successful use must slide the JDBC expiry");
    }

    @Test
    void exactlyNinetyDaysIdleCannotAuthenticateOrIssuePositivePersistentCookie() throws Exception {
        Client client = login(new Client(port));
        String cookie = client.cookie();
        ageSession(decodeCookie(cookie), Duration.ofDays(90), PERSISTENT_SECONDS);
        var expired = new Client(port, cookie).get("/api/v1/auth/me");
        assertError(expired, 401, "AUTHENTICATION_REQUIRED");
        assertNoPositiveSessionCookie(expired);
        assertNull(sessions.findById(decodeCookie(cookie)));
    }

    @Test
    void moreThanNinetyDaysIdleCannotAuthenticateOrExtendExpiredCookie() throws Exception {
        Client client = login(new Client(port));
        String cookie = client.cookie();
        ageSession(decodeCookie(cookie), Duration.ofDays(91), PERSISTENT_SECONDS);
        var expired = new Client(port, cookie).get("/api/v1/auth/me");
        assertError(expired, 401, "AUTHENTICATION_REQUIRED");
        assertNoPositiveSessionCookie(expired);
        assertNull(sessions.findById(decodeCookie(cookie)));
    }

    @Test
    void logoutDeletesPersistentCookieRejectsReplayAndPreservesOtherDeviceSession() throws Exception {
        Client first = login(new Client(port));
        Client otherDevice = login(new Client(port));
        String oldCookie = first.cookie();
        String otherCookie = otherDevice.cookie();
        var logout = first.mutate("POST", "/api/v1/auth/logout", Map.of());
        assertEquals(204, logout.statusCode());
        assertDeletionCookie(logout);
        assertNoPositiveSessionCookie(logout);
        assertNull(sessions.findById(decodeCookie(oldCookie)));
        var replayed = new Client(port, oldCookie).get("/api/v1/auth/me");
        assertError(replayed, 401, "AUTHENTICATION_REQUIRED");
        assertNoPositiveSessionCookie(replayed);
        var unaffected = otherDevice.get("/api/v1/auth/me");
        assertEquals(200, unaffected.statusCode());
        assertTrue(otherCookie.equals(persistentCookie(unaffected)), "Logout must preserve the other device session");
        assertNotNull(sessions.findById(decodeCookie(otherCookie)));
    }

    @Test
    void inactiveAndWithdrawnAccountsDeleteSessionWithoutRefreshingPersistentCookie() throws Exception {
        for (String status : List.of("INACTIVE", "WITHDRAWN")) {
            jdbc.update("UPDATE users SET status = 'ACTIVE', version = version + 1 WHERE id = ?", member.id());
            Client client = login(new Client(port));
            String cookie = client.cookie();
            jdbc.update("UPDATE users SET status = ?, version = version + 1 WHERE id = ?", status, member.id());
            var rejected = client.get("/api/v1/auth/me");
            assertError(rejected, 403, "INACTIVE_USER");
            assertDeletionCookie(rejected);
            assertNoPositiveSessionCookie(rejected);
            assertNull(sessions.findById(decodeCookie(cookie)));
            var replayed = new Client(port, cookie).get("/api/v1/auth/me");
            assertError(replayed, 401, "AUTHENTICATION_REQUIRED");
            assertNoPositiveSessionCookie(replayed);
        }
    }

    @Test
    void loginUpgradesShortLegacyAnonymousSessionToNinetyDays() throws Exception {
        Client client = new Client(port);
        JsonNode csrf = client.csrf();
        String oldCookie = client.cookie();
        ageSession(decodeCookie(oldCookie), Duration.ZERO, 1800);
        var loggedIn = client.send("POST", "/api/v1/auth/login",
                Map.of("loginId", member.loginId(), "password", password), csrf);
        assertEquals(200, loggedIn.statusCode());
        String upgraded = persistentCookie(loggedIn);
        assertFalse(oldCookie.equals(upgraded), "Legacy login must still rotate the session identity");
        assertNull(sessions.findById(decodeCookie(oldCookie)));
        assertPersistentMetadata(decodeCookie(upgraded));
    }

    @Test
    void successfulAuthenticatedRequestUpgradesStillLiveShortLegacySession() throws Exception {
        Client client = login(new Client(port));
        String oldCookie = client.cookie();
        String sessionId = decodeCookie(oldCookie);
        ageSession(sessionId, Duration.ZERO, 1800);
        var upgraded = new Client(port, oldCookie).get("/api/v1/auth/me");
        assertEquals(200, upgraded.statusCode());
        assertTrue(oldCookie.equals(persistentCookie(upgraded)), "Legacy renewal must keep the authenticated identity");
        assertPersistentMetadata(sessionId);
    }

    @Test
    void expiredThirtyMinuteLegacySessionCannotBeRevivedAsNinetyDaySession() throws Exception {
        Client client = login(new Client(port));
        String oldCookie = client.cookie();
        String sessionId = decodeCookie(oldCookie);
        ageSession(sessionId, Duration.ofMinutes(31), 1800);
        var expired = new Client(port, oldCookie).get("/api/v1/auth/me");
        assertError(expired, 401, "AUTHENTICATION_REQUIRED");
        assertNoPositiveSessionCookie(expired);
        assertNull(sessions.findById(sessionId));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM spring_session WHERE session_id = ?",
                Integer.class, sessionId));
    }

    @Test
    @Timeout(240)
    void cookieRestoresAuthenticationAfterRestartingIndependentWebJvmAgainstSameDatabase() throws Exception {
        String persistedCookie;
        String persistedSessionId;
        String firstLogs;
        try (WebProcess first = startWebProcess()) {
            Client original = login(new Client(first.port));
            persistedCookie = original.cookie();
            persistedSessionId = decodeCookie(persistedCookie);
            ageSession(persistedSessionId, Duration.ofDays(45), PERSISTENT_SECONDS);
            firstLogs = first.stop();
        }
        assertPersistentMetadata(persistedSessionId);
        assertSafeProcessLogs(firstLogs, persistedCookie, persistedSessionId);

        String secondLogs;
        try (WebProcess restarted = startWebProcess()) {
            Client recovered = new Client(restarted.port, persistedCookie);
            var restored = recovered.get("/api/v1/auth/me");
            assertEquals(200, restored.statusCode());
            assertEquals(Long.toString(member.id()), data(restored).get("id").asString());
            assertTrue(persistedCookie.equals(persistentCookie(restored)), "Restart must restore the same JDBC session identity");
            assertPersistentMetadata(persistedSessionId);
            assertEquals(204, recovered.mutate("POST", "/api/v1/auth/logout", Map.of()).statusCode());
            assertNull(sessions.findById(persistedSessionId));
            assertError(new Client(port, persistedCookie).get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
            secondLogs = restarted.stop();
        }
        assertSafeProcessLogs(secondLogs, persistedCookie, persistedSessionId);
    }

    private Client login(Client client) throws Exception {
        var response = client.mutate("POST", "/api/v1/auth/login", Map.of("loginId", member.loginId(), "password", password));
        assertEquals(200, response.statusCode());
        persistentCookie(response);
        return client;
    }

    private void ageSession(String sessionId, Duration elapsed, int seconds) {
        long accessed = System.currentTimeMillis() - elapsed.toMillis();
        assertEquals(1, jdbc.update("""
                UPDATE spring_session SET last_access_time = ?, max_inactive_interval = ?, expiry_time = ?
                WHERE session_id = ?
                """, accessed, seconds, accessed + seconds * 1000L, sessionId));
    }

    private void assertPersistentMetadata(String sessionId) {
        assertEquals(PERSISTENT_SECONDS, jdbc.queryForObject(
                "SELECT max_inactive_interval FROM spring_session WHERE session_id = ?", Integer.class, sessionId));
        Long accessed = jdbc.queryForObject("SELECT last_access_time FROM spring_session WHERE session_id = ?", Long.class, sessionId);
        Long expires = jdbc.queryForObject("SELECT expiry_time FROM spring_session WHERE session_id = ?", Long.class, sessionId);
        assertEquals(accessed + PERSISTENT_SECONDS * 1000L, expires.longValue());
    }

    private String persistentCookie(HttpResponse<String> response) {
        List<String> headers = response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION=")).toList();
        assertFalse(headers.isEmpty(), "A successful authentication/use must issue or renew the persistent cookie");
        String header = headers.getLast();
        assertTrue(header.contains("HttpOnly"), "Persistent cookie must remain HttpOnly");
        assertTrue(header.contains("SameSite=Lax"), "Persistent cookie must retain SameSite=Lax");
        assertTrue(header.contains("Path=/"), "Persistent cookie must use the application root path");
        assertTrue(header.contains("Expires="), "Persistent cookie must have a browser expiry");
        String expiryHeader = Arrays.stream(header.split(";")).map(String::strip)
                .filter(value -> value.startsWith("Expires=")).findFirst().orElseThrow().substring("Expires=".length());
        Instant expires = ZonedDateTime.parse(expiryHeader, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        long remaining = Duration.between(Instant.now(), expires).toSeconds();
        assertTrue(remaining >= PERSISTENT_SECONDS - 10 && remaining <= PERSISTENT_SECONDS + 2,
                "Issuing/renewing a cookie must set its actual expiry ninety days ahead");
        assertFalse(header.contains("Secure"), "Only the local HTTP profile disables Secure");
        HttpCookie cookie = HttpCookie.parse(header).getFirst();
        assertEquals(PERSISTENT_SECONDS, cookie.getMaxAge());
        assertFalse(cookie.getValue().isBlank(), "Persistent cookie must have a nonempty session identity");
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return cookie.getValue();
    }

    private void assertNoPositiveSessionCookie(HttpResponse<String> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            if (header.startsWith("SESSION=")) {
                assertTrue(HttpCookie.parse(header).getFirst().getMaxAge() <= 0,
                        "Rejected/logged-out session must never receive a positive persistent lifetime");
            }
        }
    }

    private void assertDeletionCookie(HttpResponse<String> response) {
        assertTrue(response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith("SESSION="))
                .map(value -> HttpCookie.parse(value).getFirst()).anyMatch(cookie -> cookie.getMaxAge() == 0),
                "Session termination must clear the browser cookie");
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode());
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertFalse(response.body().contains(password));
    }

    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private String decodeCookie(String cookie) {
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    private WebProcess startWebProcess() throws Exception {
        int childPort;
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) { childPort = socket.getLocalPort(); }
        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows") ? "java.exe" : "java");
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-jar",
                "build/libs/jeongeum-band-scheduler-backend-0.0.1-SNAPSHOT.jar", "--spring.profiles.active=local",
                "--server.address=127.0.0.1", "--server.port=" + childPort);
        builder.redirectErrorStream(true);
        builder.environment().put("APP_DB_URL", POSTGRES.getJdbcUrl());
        builder.environment().put("APP_DB_USERNAME", POSTGRES.getUsername());
        builder.environment().put("APP_DB_PASSWORD", POSTGRES.getPassword());
        WebProcess child = new WebProcess(builder.start(), childPort);
        try {
            HttpClient probe = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(75)).until(() -> {
                assertTrue(child.process.isAlive(), "Restart test child JVM exited before health was ready");
                try {
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + child.port + "/api/v1/health"))
                            .timeout(Duration.ofSeconds(2)).GET().build();
                    return probe.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (java.io.IOException exception) { return false; }
            });
            return child;
        } catch (Throwable failure) {
            child.close();
            throw failure;
        }
    }

    private void assertSafeProcessLogs(String output, String cookie, String sessionId) {
        assertFalse(output.contains(password), "Web process must not log the synthetic account password");
        assertFalse(output.contains(POSTGRES.getPassword()), "Web process must not log its database password");
        assertFalse(output.contains(cookie), "Web process must not log the session cookie");
        assertFalse(output.contains(sessionId), "Web process must not log the session identifier");
    }

    private final class WebProcess implements AutoCloseable {
        private final Process process;
        private final int port;
        private final CompletableFuture<String> output;
        private String completedOutput;
        private volatile boolean stopping;

        WebProcess(Process process, int port) {
            this.process = process;
            this.port = port;
            output = CompletableFuture.supplyAsync(() -> {
                ByteArrayOutputStream captured = new ByteArrayOutputStream();
                try {
                    byte[] buffer = new byte[8192];
                    int length;
                    while ((length = process.getInputStream().read(buffer)) != -1) { captured.write(buffer, 0, length); }
                } catch (java.io.IOException exception) {
                    // Windows may close the pipe as an owned process is terminated.
                    if (!stopping && process.isAlive()) {
                        throw new RuntimeException("Could not capture restart test process output");
                    }
                }
                return captured.toString(StandardCharsets.UTF_8);
            });
        }

        String stop() throws Exception {
            if (completedOutput != null) { return completedOutput; }
            stopping = true;
            try {
                if (process.isAlive()) { process.destroy(); }
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "Owned restart test process must terminate");
                }
                completedOutput = output.get(10, TimeUnit.SECONDS);
                return completedOutput;
            } catch (InterruptedException exception) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw exception;
            }
        }

        @Override public void close() throws Exception { stop(); }
    }

    private final class Client {
        private final int targetPort;
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();

        Client(int port) { this.targetPort = port; }
        Client(int port, String restoredCookie) {
            this(port);
            HttpCookie cookie = new HttpCookie("SESSION", restoredCookie);
            cookie.setVersion(0);
            cookie.setPath("/");
            cookie.setHttpOnly(true);
            cookie.setMaxAge(PERSISTENT_SECONDS);
            cookies.getCookieStore().add(URI.create("http://127.0.0.1:" + targetPort), cookie);
        }

        HttpResponse<String> get(String path) throws Exception { return send("GET", path, null, null); }
        JsonNode csrf() throws Exception {
            var response = get("/api/v1/auth/csrf");
            assertEquals(200, response.statusCode());
            return data(response);
        }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf());
        }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path)).timeout(Duration.ofSeconds(20));
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
    }
}
