package kr.jeongeum.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
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
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(30)
class BootstrapIntegrationTest {
    private static final String POSTGRES_IMAGE =
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("bootstrap_test")
            .withUsername("bootstrap_test")
            .withPassword(UUID.randomUUID().toString())
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("APP_DB_URL", POSTGRES::getJdbcUrl);
        registry.add("APP_DB_USERNAME", POSTGRES::getUsername);
        registry.add("APP_DB_PASSWORD", POSTGRES::getPassword);
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SessionRepository<?> sessions;

    @Autowired
    ObjectMapper json;

    @Autowired
    MemberRepository members;

    @Autowired
    PasswordEncoder passwords;

    private CookieManager cookies;
    private HttpClient client;

    @BeforeEach
    void newClient() {
        cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void migratesPostgreSqlAndReturnsMinimalHealthContract() throws Exception {
        assertEquals(List.of("1", "2", "3", "4", "5", "6"), jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = true ORDER BY installed_rank", String.class));
        assertEquals(2, jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                        + "AND table_name IN ('spring_session', 'spring_session_attributes')", Integer.class));

        var response = get("/api/v1/health");
        assertEquals(200, response.statusCode());
        JsonNode body = json.readTree(response.body());
        assertEquals("UP", body.at("/data/status").asString());
        assertEquals("Asia/Seoul", body.at("/meta/timeZone").asString());
        assertTrue(body.at("/meta/serverTime").asString().endsWith("+09:00"));
        assertEquals(1, body.get("data").size());
        assertTrue(response.headers().firstValue("X-Request-ID").isPresent());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertTrue(cookies.getCookieStore().getCookies().isEmpty(), "Health must not create a session");
    }

    @Test
    void returnsMaskedCsrfAndPersistsSessionWithHttpOnlySameSiteCookie() throws Exception {
        var first = get("/api/v1/auth/csrf");
        assertEquals(200, first.statusCode());
        JsonNode firstData = json.readTree(first.body()).get("data");
        assertEquals("X-CSRF-TOKEN", firstData.get("headerName").asString());
        assertFalse(firstData.get("token").asString().isBlank());

        String setCookie = first.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(setCookie.startsWith("SESSION="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
        assertFalse(setCookie.contains("Secure"), "Only local HTTP disables Secure");
        assertTrue(first.headers().firstValue("Cache-Control").orElse("").contains("no-store"));

        String id = sessionId();
        assertNotNull(sessions.findById(id), "Session must be persisted in JDBC");
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM spring_session_attributes a JOIN spring_session s "
                        + "ON s.primary_id = a.session_primary_id WHERE s.session_id = ? "
                        + "AND a.attribute_name LIKE '%CSRF_TOKEN'", Integer.class, id));

        String secondToken = csrf().get("token").asString();
        assertNotEquals(firstData.get("token").asString(), secondToken,
                "BREACH protection masks the same stored token differently per response");
        assertEquals(401, post("/api/v1/unknown-protected-probe", firstData).statusCode(),
                "A masked token from the previous request remains valid for its session");
    }

    @Test
    void protectsUnknownApiWithJsonAndDoesNotEnableDefaultLoginOrBasicAuth() throws Exception {
        var anonymous = get("/api/v1/unknown-protected-probe");
        assertError(anonymous, 401, "AUTHENTICATION_REQUIRED");
        assertEquals(anonymous.headers().firstValue("X-Request-ID").orElseThrow(),
                json.readTree(anonymous.body()).at("/error/requestId").asString());
        assertFalse(anonymous.headers().firstValue("Location").isPresent());
        assertFalse(anonymous.headers().firstValue("WWW-Authenticate").isPresent());

        var basic = send(HttpRequest.newBuilder(uri("/api/v1/unknown-protected-probe"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "synthetic-user:unused-test-value".getBytes(StandardCharsets.UTF_8))).GET().build());
        assertError(basic, 401, "AUTHENTICATION_REQUIRED");
        assertError(get("/login"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rejectsMissingInvalidAndOtherSessionsCsrfWithoutBypassingAuthentication() throws Exception {
        JsonNode valid = csrf();
        assertError(post("/api/v1/unknown-protected-probe", null), 403, "CSRF_INVALID");
        JsonNode invalid = json.readTree("{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"invalid\"}");
        assertError(post("/api/v1/unknown-protected-probe", invalid), 403, "CSRF_INVALID");
        assertError(post("/api/v1/unknown-protected-probe", valid), 401, "AUTHENTICATION_REQUIRED");

        newClient();
        csrf();
        assertError(post("/api/v1/unknown-protected-probe", valid), 403, "CSRF_INVALID");
    }

    @Test
    void checksCsrfEvenWithPersistedAuthenticatedSecurityContext() throws Exception {
        JsonNode valid = csrf();
        installAuthenticatedContext(sessions, sessionId());

        assertError(get("/api/v1/unknown-protected-probe"), 403, "FORBIDDEN");
        assertError(post("/api/v1/unknown-protected-probe", null), 403, "CSRF_INVALID");
        assertError(post("/api/v1/unknown-protected-probe", valid), 403, "FORBIDDEN");
    }

    private <S extends Session> void installAuthenticatedContext(SessionRepository<S> repository, String id) {
        S session = repository.findById(id);
        assertNotNull(session);
        var context = SecurityContextHolder.createEmptyContext();
        var member = members.insert("fixture-" + UUID.randomUUID().toString().substring(0, 16),
                passwords.encode(UUID.randomUUID().toString()), "합성 회원", "MEMBER", java.time.Instant.now())
                .orElseThrow();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new MemberPrincipal(member.id()), null, List.of()));
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        repository.save(session);
    }

    private JsonNode csrf() throws Exception {
        var response = get("/api/v1/auth/csrf");
        assertEquals(200, response.statusCode());
        return json.readTree(response.body()).get("data");
    }

    private String sessionId() {
        String cookie = cookies.getCookieStore().getCookies().stream()
                .filter(value -> value.getName().equals("SESSION")).findFirst().orElseThrow().getValue();
        return new String(Base64.getDecoder().decode(cookie), StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpResponse<String> post(String path, JsonNode csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (csrf != null) {
            request.header(csrf.get("headerName").asString(), csrf.get("token").asString());
        }
        return send(request.build());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode());
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").contains("application/json"));
    }
}
