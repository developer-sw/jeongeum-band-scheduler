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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
@Timeout(90)
class DirectoryIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826")
            .withDatabaseName("directory_test").withUsername("directory_test")
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

    private String password;
    private Member admin;
    private Client administrator;

    @BeforeEach
    void setup() throws Exception {
        jdbc.execute("TRUNCATE teams, roster_entries, invitation_codes, users, spring_session RESTART IDENTITY CASCADE");
        password = UUID.randomUUID().toString();
        admin = account("GLOBAL_ADMIN");
        administrator = authenticated(admin);
    }

    @Test
    void upgradesPopulatedV2SchemaWithoutChangingAccountsInvitationStatesOrSessions() throws Exception {
        // This random schema belongs only to this Testcontainers database, never the developer database.
        String schema = "upgrade_fixture_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("2")
                .load().migrate();
        Instant created = Instant.now().minusSeconds(3600);
        String expectedHash = passwords.encode(password);
        String login = uniqueLogin();
        String primarySessionId = UUID.randomUUID().toString();
        String sessionId = UUID.randomUUID().toString();
        byte[] sessionBytes = "synthetic-upgrade-session-value".getBytes(StandardCharsets.UTF_8);
        Map<String, String> invitationDigests = new HashMap<>();
        for (String state : List.of("AVAILABLE", "USED", "REVOKED", "EXPIRED")) {
            invitationDigests.put(state, InvitationTokens.digest(InvitationTokens.create()));
        }
        long administratorId;
        long memberId;
        try (Connection connection = fixtureConnection(schema)) {
            administratorId = insertUpgradeAccount(connection, login, expectedHash, "GLOBAL_ADMIN", created);
            memberId = insertUpgradeAccount(connection, uniqueLogin(), expectedHash, "MEMBER", created);
            for (String state : List.of("AVAILABLE", "USED", "REVOKED", "EXPIRED")) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO invitation_codes(code_digest, recipient_name, issued_by, created_at,
                            expires_at, used_at, used_by, revoked_at, version)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)) {
                    insert.setString(1, invitationDigests.get(state));
                    insert.setString(2, "합성 업그레이드 " + state);
                    insert.setLong(3, administratorId);
                    insert.setTimestamp(4, Timestamp.from(created));
                    insert.setTimestamp(5, Timestamp.from(created.plusSeconds("EXPIRED".equals(state) ? 1800 : 86400)));
                    insert.setTimestamp(6, "USED".equals(state) ? Timestamp.from(created.plusSeconds(60)) : null);
                    if ("USED".equals(state)) { insert.setLong(7, memberId); }
                    else { insert.setNull(7, java.sql.Types.BIGINT); }
                    insert.setTimestamp(8, "REVOKED".equals(state) ? Timestamp.from(created.plusSeconds(120)) : null);
                    insert.setLong(9, "USED".equals(state) || "REVOKED".equals(state) ? 1 : 0);
                    assertEquals(1, insert.executeUpdate());
                }
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO spring_session(primary_id, session_id, creation_time, last_access_time,
                        max_inactive_interval, expiry_time, principal_name) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                insert.setString(1, primarySessionId);
                insert.setString(2, sessionId);
                insert.setLong(3, created.toEpochMilli());
                insert.setLong(4, created.toEpochMilli());
                insert.setInt(5, 7200);
                insert.setLong(6, created.plusSeconds(7200).toEpochMilli());
                insert.setString(7, login);
                assertEquals(1, insert.executeUpdate());
            }
            try (var insert = connection.prepareStatement("""
                    INSERT INTO spring_session_attributes(session_primary_id, attribute_name, attribute_bytes)
                    VALUES (?, 'synthetic.upgrade', ?)
                    """)) {
                insert.setString(1, primarySessionId);
                insert.setBytes(2, sessionBytes);
                assertEquals(1, insert.executeUpdate());
            }
        }
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("3")
                .load().migrate();
        try (Connection connection = fixtureConnection(schema)) {
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL ORDER BY installed_rank")) {
                for (String migration : List.of("1", "2", "3")) {
                    assertTrue(rows.next());
                    assertEquals(migration, rows.getString(1));
                }
                assertFalse(rows.next());
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery(
                    "SELECT id, login_id, password_hash, global_role, status, version FROM users ORDER BY id")) {
                assertTrue(rows.next());
                assertEquals(administratorId, rows.getLong("id"));
                assertEquals(login, rows.getString("login_id"));
                assertTrue(expectedHash.equals(rows.getString("password_hash")), "Synthetic hash must be preserved");
                assertEquals("GLOBAL_ADMIN", rows.getString("global_role"));
                assertEquals("ACTIVE", rows.getString("status"));
                assertEquals(0, rows.getLong("version"));
                assertTrue(rows.next());
                assertEquals(memberId, rows.getLong("id"));
                assertEquals("MEMBER", rows.getString("global_role"));
                assertTrue(expectedHash.equals(rows.getString("password_hash")), "Synthetic member hash must be preserved");
                assertFalse(rows.next());
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT * FROM invitation_codes")) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    String state = rows.getString("recipient_name").substring("합성 업그레이드 ".length());
                    assertTrue(invitationDigests.get(state).equals(rows.getString("code_digest")), "Synthetic digest must be preserved");
                    assertNull(rows.getObject("roster_entry_id"), "Upgrade must not guess identities from names");
                    assertEquals("USED".equals(state), rows.getTimestamp("used_at") != null);
                    assertEquals("USED".equals(state) ? memberId : null, rows.getObject("used_by", Long.class));
                    assertEquals("REVOKED".equals(state), rows.getTimestamp("revoked_at") != null);
                    assertEquals("EXPIRED".equals(state), rows.getTimestamp("expires_at").toInstant().isBefore(Instant.now()));
                    assertEquals("USED".equals(state) || "REVOKED".equals(state) ? 1 : 0, rows.getLong("version"));
                }
                assertEquals(4, count);
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT count(*) FROM roster_entries")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "Existing users must remain unlinked until explicit account linkage");
            }
            try (var query = connection.createStatement(); var rows = query.executeQuery("""
                    SELECT s.primary_id, s.session_id, s.principal_name, a.attribute_bytes FROM spring_session s
                    JOIN spring_session_attributes a ON a.session_primary_id = s.primary_id
                    """)) {
                assertTrue(rows.next());
                assertEquals(primarySessionId, rows.getString("primary_id"));
                assertEquals(sessionId, rows.getString("session_id"));
                assertEquals(login, rows.getString("principal_name"));
                assertArrayEquals(sessionBytes, rows.getBytes("attribute_bytes"));
                assertFalse(rows.next());
            }
        }
    }

    @Test
    void signupLinksIdentityToPreassignedMultipleRegularTeamsAndLeaderWithoutMatchingNames() throws Exception {
        JsonNode person = roster("가입 전 합성 이름");
        JsonNode first = team("합성 정규조 A", "REGULAR");
        JsonNode second = team("합성 정규조 B", "REGULAR");
        JsonNode additional = team("합성 추가조", "ADDITIONAL");
        for (JsonNode team : List.of(first, second, additional)) { add(team, person); }
        assertEquals(200, leader(first, person).statusCode());
        JsonNode invitation = issue(person, null);
        Client newcomer = new Client();
        var inspection = newcomer.mutate("POST", "/api/v1/auth/invitations/inspect",
                Map.of("token", invitation.get("token").asString()));
        assertEquals(200, inspection.statusCode());
        assertEquals(1, data(inspection).size());
        assertNotNull(data(inspection).get("expiresAt"));
        String login = uniqueLogin();
        var registered = register(newcomer, invitation, login, "가입 시 다른 합성 이름");
        assertEquals(201, registered.statusCode());
        assertEquals("MEMBER", data(registered).get("globalRole").asString());
        assertEquals(3, data(registered).get("teamMemberships").size());
        assertEquals(2, countKind(data(registered), "REGULAR"));
        assertEquals("LEADER", membership(data(registered), first).get("role").asString());
        assertError(newcomer.get("/api/v1/auth/me"), 401, "AUTHENTICATION_REQUIRED");
        var loggedIn = newcomer.login(login, password);
        assertEquals(200, loggedIn.statusCode());
        assertEquals(3, data(loggedIn).get("teamMemberships").size());
        assertEquals(3, data(newcomer.get("/api/v1/auth/me")).get("teamMemberships").size());
        JsonNode linked = find(administrator.get("/api/v1/roster"), person.get("id").asString());
        assertEquals(data(registered).get("id").asString(), linked.get("userId").asString());
        assertEquals("가입 전 합성 이름", linked.get("name").asString());
        assertEquals("가입 시 다른 합성 이름", linked.get("displayName").asString());
        JsonNode detail = data(newcomer.get(teamPath(first)));
        assertTrue(detail.get("canManageSongs").asBoolean());
        assertFalse(detail.get("canManageTeam").asBoolean());
        assertEquals("가입 전 합성 이름", detail.get("members").get(0).get("name").asString());
        assertEquals(data(registered).get("id").asString(), detail.get("members").get(0).get("userId").asString());
        assertEquals(data(registered).get("id").asString(), jdbc.queryForObject(
                "SELECT used_by::text FROM invitation_codes WHERE id = ?", String.class, id(invitation)));
        assertSafe(registered);
    }

    @Test
    void sameNamesStayDistinctAndNameOnlyLegacyInvitationCreatesNewUnlinkedRoster() throws Exception {
        JsonNode first = roster("같은 합성 이름");
        JsonNode second = roster("같은 합성 이름");
        assertNotEquals(first.get("id").asString(), second.get("id").asString());
        JsonNode invitation = issue(first, null);
        assertEquals(201, register(new Client(), invitation, uniqueLogin(), "같은 합성 이름").statusCode());
        JsonNode legacy = success(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("recipientName", "같은 합성 이름")), 201);
        assertTrue(legacy.get("rosterEntryId").isString());
        assertNotEquals(first.get("id").asString(), legacy.get("rosterEntryId").asString());
        assertNotEquals(second.get("id").asString(), legacy.get("rosterEntryId").asString());
        assertEquals(3, data(administrator.get("/api/v1/roster")).size());
        assertTrue(find(administrator.get("/api/v1/roster"), second.get("id").asString()).get("userId").isNull());
    }

    @Test
    void legacyNullRosterInvitationsBindNewEntriesAtSignupAndReissueWithoutBackfillingExistingAccounts() throws Exception {
        Member existing = account("MEMBER");
        JsonNode first = issue(roster("기존 초대 합성 대상 A"), null);
        JsonNode second = issue(roster("기존 초대 합성 대상 B"), null);
        jdbc.update("UPDATE invitation_codes SET roster_entry_id = NULL WHERE id IN (?, ?)", id(first), id(second));
        assertTrue(find(administrator.get("/api/v1/users"), Long.toString(existing.id())).get("rosterEntryId").isNull());
        var registered = register(new Client(), first, uniqueLogin(), "기존 초대 합성 가입");
        assertEquals(201, registered.statusCode());
        String newRosterId = jdbc.queryForObject("SELECT roster_entry_id::text FROM invitation_codes WHERE id = ?",
                String.class, id(first));
        assertNotEquals(first.get("rosterEntryId").asString(), newRosterId);
        assertEquals(data(registered).get("id").asString(), jdbc.queryForObject(
                "SELECT user_id::text FROM roster_entries WHERE id = ?", String.class, Long.parseLong(newRosterId)));
        JsonNode renewed = success(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("replacesInvitationId", second.get("id").asString())), 201);
        assertTrue(renewed.get("rosterEntryId").isString());
        assertNotEquals(second.get("rosterEntryId").asString(), renewed.get("rosterEntryId").asString());
        assertTrue(find(administrator.get("/api/v1/users"), Long.toString(existing.id())).get("rosterEntryId").isNull());
        assertEquals(4, data(administrator.get("/api/v1/roster")).size());
    }

    @Test
    void databaseConstraintsRejectDuplicateActiveMembershipLeaderAndAccountLinkEvenWithoutServiceGuard() throws Exception {
        JsonNode firstPerson = roster("제약 합성 대상 A");
        JsonNode secondPerson = roster("제약 합성 대상 B");
        JsonNode assigned = team("제약 합성 조", "REGULAR");
        add(assigned, firstPerson);
        add(assigned, secondPerson);
        assertEquals(200, leader(assigned, firstPerson).statusCode());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO team_memberships(team_id, roster_entry_id, role, status, created_at, updated_at)
                SELECT team_id, roster_entry_id, 'MEMBER', 'ACTIVE', created_at, updated_at
                FROM team_memberships WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'
                """, id(assigned), id(firstPerson)));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update("""
                UPDATE team_memberships SET role = 'LEADER'
                WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'
                """, id(assigned), id(secondPerson)));
        Member existing = account("MEMBER");
        assertEquals(200, administrator.mutate("POST", rosterPath(firstPerson) + "/link-account",
                Map.of("userId", Long.toString(existing.id()), "version", version(firstPerson))).statusCode());
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> jdbc.update(
                "UPDATE roster_entries SET user_id = ? WHERE id = ?", existing.id(), id(secondPerson)));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE status = 'ACTIVE'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE status = 'ACTIVE' AND role = 'LEADER'",
                Integer.class));
        assertTrue(find(administrator.get("/api/v1/roster"), secondPerson.get("id").asString()).get("userId").isNull());
    }

    @Test
    void newDirectoryMutationsRequireCsrfEvenForAuthenticatedAdministrator() throws Exception {
        assertError(administrator.send("POST", "/api/v1/roster", Map.of("name", "CSRF 합성 대상"), null),
                403, "CSRF_INVALID");
        assertError(administrator.send("POST", "/api/v1/teams", Map.of("name", "CSRF 합성 조", "kind", "REGULAR"), null),
                403, "CSRF_INVALID");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM roster_entries", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM teams", Integer.class));
    }

    @Test
    void rejectsFractionalVersionsSortOrdersAndIdsWithoutTruncatingOrChangingStoredData() throws Exception {
        JsonNode person = roster("소수 입력 검증 명단");
        JsonNode assigned = team("소수 입력 검증 조", "REGULAR");
        Member member = account("MEMBER");
        JsonNode account = find(administrator.get("/api/v1/users"), Long.toString(member.id()));
        JsonNode originalSong = success(song(administrator, assigned, "소수 입력 검증 곡"), 201);
        for (double fraction : List.of(0.9, -0.9)) {
            assertError(administrator.mutate("PATCH", rosterPath(person),
                    Map.of("name", "소수 버전 명단 변경", "version", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("PATCH", teamPath(assigned),
                    Map.of("name", "소수 버전 조 변경", "version", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("PATCH", "/api/v1/users/" + member.id() + "/role",
                    Map.of("globalRole", "GLOBAL_ADMIN", "version", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("PATCH", songPath(assigned, originalSong),
                    Map.of("title", "소수 버전 곡 변경", "sortOrder", originalSong.get("sortOrder").asLong(),
                            "version", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("POST", teamPath(assigned) + "/songs",
                    Map.of("title", "소수 순서 새 곡", "sortOrder", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("PATCH", songPath(assigned, originalSong),
                    Map.of("title", "소수 순서 곡 변경", "sortOrder", fraction, "version", version(originalSong))),
                    400, "INVALID_INPUT");
            assertError(administrator.mutate("POST", teamPath(assigned) + "/members",
                    Map.of("rosterEntryId", fraction)), 400, "INVALID_INPUT");
            assertError(administrator.mutate("POST", rosterPath(person) + "/link-account",
                    Map.of("userId", fraction, "version", version(person))), 400, "INVALID_INPUT");
        }
        JsonNode retainedPerson = find(administrator.get("/api/v1/roster"), person.get("id").asString());
        assertEquals(person.get("name").asString(), retainedPerson.get("name").asString());
        assertEquals(version(person), version(retainedPerson));
        assertTrue(retainedPerson.get("userId").isNull());
        JsonNode retainedTeam = data(administrator.get(teamPath(assigned)));
        assertEquals(assigned.get("name").asString(), retainedTeam.get("team").get("name").asString());
        assertEquals(version(assigned), version(retainedTeam.get("team")));
        assertEquals(0, retainedTeam.get("members").size());
        JsonNode retainedAccount = find(administrator.get("/api/v1/users"), Long.toString(member.id()));
        assertEquals("MEMBER", retainedAccount.get("globalRole").asString());
        assertEquals(version(account), version(retainedAccount));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_role_audit", Integer.class));
        JsonNode songs = data(administrator.get(teamPath(assigned) + "/songs"));
        assertEquals(1, songs.size());
        assertEquals(originalSong.get("title").asString(), songs.get(0).get("title").asString());
        assertEquals(originalSong.get("sortOrder").asLong(), songs.get(0).get("sortOrder").asLong());
        assertEquals(version(originalSong), version(songs.get(0)));
    }

    @Test
    void manualLinkIsOneToOneRevokesPendingInviteAndLeavesAccountRoleUnchanged() throws Exception {
        JsonNode person = roster("수동 연결 합성 대상");
        JsonNode assigned = team("수동 연결 합성 조", "REGULAR");
        add(assigned, person);
        assertEquals(200, leader(assigned, person).statusCode());
        JsonNode invitation = issue(person, null);
        Member member = account("MEMBER");
        Client accountOwner = authenticated(member);
        assertEquals(0, data(accountOwner.get("/api/v1/auth/me")).get("teamMemberships").size());
        var linked = administrator.mutate("POST", rosterPath(person) + "/link-account",
                Map.of("userId", Long.toString(member.id()), "version", version(person)));
        assertEquals(200, linked.statusCode());
        assertEquals(Long.toString(member.id()), data(linked).get("userId").asString());
        assertTrue(data(linked).get("version").asLong() > version(person));
        assertEquals("MEMBER", members.findById(member.id()).orElseThrow().globalRole());
        assertEquals("LEADER", membership(data(accountOwner.get("/api/v1/auth/me")), assigned).get("role").asString());
        assertEquals(201, song(accountOwner, assigned, "연결 후 합성 팀장 곡").statusCode());
        assertError(register(new Client(), invitation, uniqueLogin(), "합성 재사용"), 410, "INVITATION_UNAVAILABLE");
        assertTrue(jdbc.queryForObject("SELECT revoked_at IS NOT NULL FROM invitation_codes WHERE id = ?",
                Boolean.class, id(invitation)));
        JsonNode other = roster("다른 수동 연결 대상");
        assertError(administrator.mutate("POST", rosterPath(other) + "/link-account",
                Map.of("userId", Long.toString(member.id()), "version", version(other))),
                409, "ACCOUNT_ALREADY_LINKED");
        Member another = account("MEMBER");
        assertError(administrator.mutate("POST", rosterPath(person) + "/link-account",
                Map.of("userId", Long.toString(another.id()), "version", version(data(linked)))),
                409, "ROSTER_ALREADY_LINKED");
        assertError(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("rosterEntryId", person.get("id").asString())), 409, "ROSTER_ALREADY_LINKED");
        assertSafe(linked);
    }

    @Test
    void reissuePreservesRosterAndAssignmentsRejectsMismatchAndRequiresExplicitPendingReplacement() throws Exception {
        JsonNode person = roster("재발급 합성 대상");
        JsonNode another = roster("재발급 다른 대상");
        JsonNode assigned = team("재발급 합성 조", "REGULAR");
        add(assigned, person);
        JsonNode original = issue(person, null);
        assertError(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("rosterEntryId", person.get("id").asString())), 409, "INVITATION_ALREADY_EXISTS");
        assertError(administrator.mutate("POST", "/api/v1/invitations", Map.of(
                "rosterEntryId", another.get("id").asString(), "replacesInvitationId", original.get("id").asString())),
                400, "INVALID_INPUT");
        assertEquals(200, new Client().mutate("POST", "/api/v1/auth/invitations/inspect",
                Map.of("token", original.get("token").asString())).statusCode());
        jdbc.update("UPDATE invitation_codes SET created_at = ?, expires_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),
                java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), id(original));
        assertError(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("rosterEntryId", person.get("id").asString())), 409, "INVITATION_ALREADY_EXISTS");
        JsonNode replacement = success(administrator.mutate("POST", "/api/v1/invitations",
                Map.of("replacesInvitationId", original.get("id").asString())), 201);
        assertEquals(person.get("id").asString(), replacement.get("rosterEntryId").asString());
        assertNotEquals(original.get("token").asString(), replacement.get("token").asString());
        var registration = register(new Client(), replacement, uniqueLogin(), "재발급 합성 가입");
        assertEquals(201, registration.statusCode());
        assertEquals(assigned.get("id").asString(), data(registration).get("teamMemberships").get(0).get("teamId").asString());
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM roster_entries", Integer.class));
    }

    @Test
    void concurrentMembershipChangesAllowDifferentRegularTeamsButRejectDuplicateSameTeam() throws Exception {
        JsonNode person = roster("동시 소속 합성 대상");
        JsonNode first = team("동시 정규조 A", "REGULAR");
        JsonNode second = team("동시 정규조 B", "REGULAR");
        Client anotherAdmin = authenticated(account("GLOBAL_ADMIN"));
        JsonNode firstCsrf = administrator.csrf();
        JsonNode secondCsrf = anotherAdmin.csrf();
        Map<String, Object> request = Map.of("rosterEntryId", person.get("id").asString());
        List<HttpResponse<String>> different = race(
                () -> administrator.send("POST", teamPath(first) + "/members", request, firstCsrf),
                () -> anotherAdmin.send("POST", teamPath(second) + "/members", request, secondCsrf));
        assertEquals(List.of(201, 201), statuses(different));
        JsonNode third = team("같은 조 동시 소속", "REGULAR");
        List<HttpResponse<String>> duplicate = race(
                () -> administrator.send("POST", teamPath(third) + "/members", request, firstCsrf),
                () -> anotherAdmin.send("POST", teamPath(third) + "/members", request, secondCsrf));
        assertEquals(List.of(201, 409), statuses(duplicate));
        assertError(duplicate.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                409, "MEMBERSHIP_ALREADY_EXISTS");
        assertEquals(3, jdbc.queryForObject("SELECT count(*) FROM team_memberships WHERE status = 'ACTIVE'",
                Integer.class));
    }

    @Test
    void leaderMustBelongToTeamAndRemovalClearsAuthorityWithoutChoosingSuccessor() throws Exception {
        JsonNode person = roster("합성 팀장");
        JsonNode ordinary = roster("합성 일반 조원");
        JsonNode external = roster("합성 비소속 대상");
        JsonNode assigned = team("합성 팀장 조", "REGULAR");
        add(assigned, person);
        add(assigned, ordinary);
        assertError(leader(assigned, external), 400, "INVALID_INPUT");
        assertEquals(200, leader(assigned, person).statusCode());
        JsonNode invite = issue(person, null);
        String login = uniqueLogin();
        Client leader = new Client();
        assertEquals(201, register(leader, invite, login, "합성 팀장 계정").statusCode());
        assertEquals(200, leader.login(login, password).statusCode());
        assertEquals(201, song(leader, assigned, "팀장 권한 합성 곡").statusCode());
        assertEquals(204, administrator.mutate("DELETE", teamPath(assigned) + "/members/" + person.get("id").asString(),
                null).statusCode());
        assertError(song(leader, assigned, "제거 뒤 합성 곡"), 403, "FORBIDDEN");
        assertEquals(0, data(leader.get("/api/v1/auth/me")).get("teamMemberships").size());
        JsonNode remaining = data(administrator.get(teamPath(assigned))).get("members");
        assertEquals(1, remaining.size());
        assertEquals("MEMBER", remaining.get(0).get("role").asString());
        add(assigned, person);
        assertEquals(200, leader(assigned, person).statusCode());
        Map<String, Object> clear = new HashMap<>();
        clear.put("rosterEntryId", null);
        assertEquals(200, administrator.mutate("PUT", teamPath(assigned) + "/leader", clear).statusCode());
        assertError(song(leader, assigned, "해제 뒤 합성 곡"), 403, "FORBIDDEN");
    }

    @Test
    void teamScopedLeaderCanManageOnlyOwnSongsAndGlobalAdminStillCannotMisrouteSongId() throws Exception {
        JsonNode person = roster("여러 조 합성 팀장");
        JsonNode first = team("권한 합성 조 A", "REGULAR");
        JsonNode second = team("권한 합성 조 B", "REGULAR");
        add(first, person);
        add(second, person);
        assertEquals(200, leader(first, person).statusCode());
        Client member = registerAndLogin(person);
        JsonNode ownSong = success(song(member, first, "자기 조 합성 곡"), 201);
        assertError(song(member, second, "다른 조 합성 곡"), 403, "FORBIDDEN");
        assertError(member.mutate("PATCH", teamPath(first), Map.of("name", "권한 위조 조", "version", version(first))),
                403, "FORBIDDEN");
        assertError(member.mutate("POST", teamPath(first) + "/members", Map.of("rosterEntryId", person.get("id").asString())),
                403, "FORBIDDEN");
        assertError(member.mutate("PUT", teamPath(second) + "/leader", Map.of("rosterEntryId", person.get("id").asString())),
                403, "FORBIDDEN");
        assertError(administrator.mutate("PATCH", songPath(second, ownSong), updateSong(ownSong, "경로 위조 곡")),
                404, "RESOURCE_NOT_FOUND");
        assertError(administrator.mutate("DELETE", songPath(second, ownSong) + "?version=" + version(ownSong), null),
                404, "RESOURCE_NOT_FOUND");
        assertEquals("자기 조 합성 곡", data(member.get(teamPath(first) + "/songs")).get(0).get("title").asString());
    }

    @Test
    void songCrudUsesOptimisticVersionsAppendOrderValidationAndRollbackOnConflict() throws Exception {
        JsonNode assigned = team("합성 곡 편집 조", "ADDITIONAL");
        JsonNode first = success(song(administrator, assigned, "합성 첫 곡"), 201);
        JsonNode second = success(song(administrator, assigned, "합성 다음 곡"), 201);
        assertTrue(second.get("sortOrder").asLong() > first.get("sortOrder").asLong());
        JsonNode edited = success(administrator.mutate("PATCH", songPath(assigned, first),
                Map.of("title", "합성 수정 곡", "artist", "합성 아티스트", "memo", "<script>synthetic</script>",
                        "sortOrder", 3, "version", version(first))), 200);
        assertTrue(version(edited) > version(first));
        assertEquals("<script>synthetic</script>", edited.get("memo").asString());
        assertError(administrator.mutate("PATCH", songPath(assigned, first), updateSong(first, "낡은 편집 곡")),
                409, "VERSION_CONFLICT");
        assertError(administrator.mutate("DELETE", songPath(assigned, first) + "?version=" + version(first), null),
                409, "VERSION_CONFLICT");
        assertError(administrator.mutate("POST", teamPath(assigned) + "/songs", Map.of("title", " ")),
                400, "INVALID_INPUT");
        assertError(administrator.mutate("POST", teamPath(assigned) + "/songs", Map.of("title", "가".repeat(121))),
                400, "INVALID_INPUT");
        assertError(administrator.mutate("POST", teamPath(assigned) + "/songs", Map.of("title", "합성 곡", "sortOrder", -1)),
                400, "INVALID_INPUT");
        assertEquals(2, data(administrator.get(teamPath(assigned) + "/songs")).size());
        assertEquals(204, administrator.mutate("DELETE", songPath(assigned, edited) + "?version=" + version(edited),
                null).statusCode());
        assertEquals(1, data(administrator.get(teamPath(assigned) + "/songs")).size());
    }

    @Test
    void directoryReadsHideOtherTeamMembersAndRequireActiveAccountAndAdminForRosterAndAccounts() throws Exception {
        JsonNode person = roster("개인정보 합성 대상");
        JsonNode own = team("개인정보 자기 조", "REGULAR");
        JsonNode other = team("개인정보 타인 조", "ADDITIONAL");
        JsonNode otherPerson = roster("타인 명단 합성 이름");
        add(own, person);
        add(other, otherPerson);
        Client member = registerAndLogin(person);
        assertError(new Client().get("/api/v1/teams"), 401, "AUTHENTICATION_REQUIRED");
        assertError(member.get("/api/v1/roster"), 403, "FORBIDDEN");
        assertError(member.get("/api/v1/users"), 403, "FORBIDDEN");
        assertError(member.mutate("POST", "/api/v1/roster", Map.of("name", "권한 위조 명단")), 403, "FORBIDDEN");
        assertEquals(2, data(member.get("/api/v1/teams")).size());
        JsonNode ownDetail = data(member.get(teamPath(own)));
        assertTrue(ownDetail.get("canViewMembers").asBoolean());
        assertEquals(1, ownDetail.get("members").size());
        var outsider = member.get(teamPath(other));
        assertEquals(200, outsider.statusCode());
        assertFalse(data(outsider).get("canViewMembers").asBoolean());
        assertEquals(0, data(outsider).get("members").size());
        assertFalse(outsider.body().contains("타인 명단 합성 이름"));
        assertEquals(1, data(administrator.get(teamPath(other))).get("members").size());
        var accounts = administrator.get("/api/v1/users");
        assertEquals(200, accounts.statusCode());
        assertSafe(accounts);
        assertSafe(member.get("/api/v1/auth/me"));
        String memberId = data(member.get("/api/v1/auth/me")).get("id").asString();
        jdbc.update("UPDATE users SET status = 'INACTIVE', version = version + 1 WHERE id = ?", Long.parseLong(memberId));
        assertError(member.get("/api/v1/teams"), 403, "INACTIVE_USER");
        assertError(member.get(teamPath(own) + "/songs"), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void rosterTeamAndRoleStaleVersionsDoNotOverwriteCurrentState() throws Exception {
        JsonNode person = roster("합성 원래 이름");
        JsonNode renamed = success(administrator.mutate("PATCH", rosterPath(person),
                Map.of("name", "합성 새 이름", "version", version(person))), 200);
        assertError(administrator.mutate("PATCH", rosterPath(person),
                Map.of("name", "합성 낡은 이름", "version", version(person))), 409, "VERSION_CONFLICT");
        Member unlinked = account("MEMBER");
        assertError(administrator.mutate("POST", rosterPath(person) + "/link-account",
                Map.of("userId", Long.toString(unlinked.id()), "version", version(person))), 409, "VERSION_CONFLICT");
        assertTrue(find(administrator.get("/api/v1/roster"), person.get("id").asString()).get("userId").isNull());
        assertEquals("합성 새 이름", renamed.get("name").asString());
        JsonNode assigned = team("합성 원래 조", "REGULAR");
        assertEquals(200, administrator.mutate("PATCH", teamPath(assigned),
                Map.of("name", "합성 새 조", "version", version(assigned))).statusCode());
        assertError(administrator.mutate("PATCH", teamPath(assigned),
                Map.of("name", "합성 낡은 조", "version", version(assigned))), 409, "VERSION_CONFLICT");
        JsonNode target = find(administrator.get("/api/v1/users"), Long.toString(unlinked.id()));
        assertEquals(200, role(administrator, target, "GLOBAL_ADMIN").statusCode());
        assertError(role(administrator, target, "MEMBER"), 409, "VERSION_CONFLICT");
        assertEquals("GLOBAL_ADMIN", members.findById(unlinked.id()).orElseThrow().globalRole());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member_role_audit", Integer.class));
    }

    @Test
    void concurrentSameVersionTeamEditsHaveExactlyOneSuccess() throws Exception {
        JsonNode assigned = team("동시 편집 원래 조", "REGULAR");
        Client anotherAdmin = authenticated(account("GLOBAL_ADMIN"));
        JsonNode firstCsrf = administrator.csrf();
        JsonNode secondCsrf = anotherAdmin.csrf();
        List<HttpResponse<String>> results = race(
                () -> administrator.send("PATCH", teamPath(assigned),
                        Map.of("name", "동시 편집 조 A", "version", version(assigned)), firstCsrf),
                () -> anotherAdmin.send("PATCH", teamPath(assigned),
                        Map.of("name", "동시 편집 조 B", "version", version(assigned)), secondCsrf));
        assertEquals(List.of(200, 409), statuses(results));
        assertError(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                409, "VERSION_CONFLICT");
        assertEquals(1, data(administrator.get(teamPath(assigned))).get("team").get("version").asLong());
    }

    @Test
    void globalRoleChangesAreAuditedImmediateAndLastActiveAdministratorCannotBeDemoted() throws Exception {
        JsonNode onlyAdmin = find(administrator.get("/api/v1/users"), Long.toString(admin.id()));
        assertError(role(administrator, onlyAdmin, "MEMBER"), 409, "LAST_GLOBAL_ADMIN");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM member_role_audit", Integer.class));
        Member target = account("MEMBER");
        Client promoted = authenticated(target);
        JsonNode original = find(administrator.get("/api/v1/users"), Long.toString(target.id()));
        JsonNode elevated = success(role(administrator, original, "GLOBAL_ADMIN"), 200);
        assertEquals(200, promoted.get("/api/v1/roster").statusCode());
        assertEquals("GLOBAL_ADMIN", data(promoted.get("/api/v1/auth/me")).get("globalRole").asString());
        assertEquals(200, role(administrator, elevated, "MEMBER").statusCode());
        assertError(promoted.get("/api/v1/users"), 403, "FORBIDDEN");
        assertError(promoted.mutate("POST", "/api/v1/teams", Map.of("name", "낡은 관리자 조", "kind", "REGULAR")),
                403, "FORBIDDEN");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM member_role_audit", Integer.class));
        assertEquals(List.of("MEMBER:GLOBAL_ADMIN", "GLOBAL_ADMIN:MEMBER"), jdbc.queryForList(
                "SELECT old_role || ':' || new_role FROM member_role_audit ORDER BY id", String.class));
        assertEquals(List.of(admin.id(), admin.id()), jdbc.queryForList(
                "SELECT actor_user_id FROM member_role_audit ORDER BY id", Long.class));
        jdbc.update("UPDATE users SET status = 'INACTIVE', global_role = 'GLOBAL_ADMIN', version = version + 1 WHERE id = ?",
                target.id());
        assertError(role(administrator, onlyAdmin, "MEMBER"), 409, "LAST_GLOBAL_ADMIN");
    }

    @Test
    void simultaneousAdministratorSelfDemotionsLeaveExactlyOneActiveAdministratorAndOneAudit() throws Exception {
        Member other = account("GLOBAL_ADMIN");
        Client otherAdmin = authenticated(other);
        JsonNode first = find(administrator.get("/api/v1/users"), Long.toString(admin.id()));
        JsonNode second = find(administrator.get("/api/v1/users"), Long.toString(other.id()));
        JsonNode firstCsrf = administrator.csrf();
        JsonNode secondCsrf = otherAdmin.csrf();
        List<HttpResponse<String>> results = race(
                () -> administrator.send("PATCH", "/api/v1/users/" + first.get("id").asString() + "/role",
                        Map.of("globalRole", "MEMBER", "version", version(first)), firstCsrf),
                () -> otherAdmin.send("PATCH", "/api/v1/users/" + second.get("id").asString() + "/role",
                        Map.of("globalRole", "MEMBER", "version", version(second)), secondCsrf));
        assertEquals(List.of(200, 409), statuses(results));
        assertError(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(),
                409, "LAST_GLOBAL_ADMIN");
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM users WHERE global_role = 'GLOBAL_ADMIN' AND status = 'ACTIVE'",
                Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM member_role_audit", Integer.class));
    }

    @Test
    void registrationAndManualLinkRaceCannotBindTwoAccountsOrPartiallyConsumeInvitation() throws Exception {
        JsonNode person = roster("동시 연결 합성 대상");
        JsonNode invitation = issue(person, null);
        Member existing = account("MEMBER");
        Client newcomer = new Client();
        JsonNode registerCsrf = newcomer.csrf();
        JsonNode adminCsrf = administrator.csrf();
        String login = uniqueLogin();
        List<HttpResponse<String>> results = race(
                () -> newcomer.send("POST", "/api/v1/auth/register", registration(invitation, login, "동시 가입 합성 대상"),
                        registerCsrf),
                () -> administrator.send("POST", rosterPath(person) + "/link-account",
                        Map.of("userId", Long.toString(existing.id()), "version", version(person)), adminCsrf));
        boolean signupWon = results.get(0).statusCode() == 201;
        assertEquals(signupWon ? 201 : 410, results.get(0).statusCode());
        assertEquals(signupWon ? 409 : 200, results.get(1).statusCode());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM roster_entries WHERE user_id IS NOT NULL", Integer.class));
        assertEquals(signupWon ? 3 : 2, jdbc.queryForObject("SELECT count(*) FROM users", Integer.class));
        assertEquals(signupWon ? 1 : 0, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE used_at IS NOT NULL",
                Integer.class));
        assertEquals(signupWon ? 0 : 1, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE revoked_at IS NOT NULL",
                Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM invitation_codes WHERE used_at IS NOT NULL AND revoked_at IS NOT NULL",
                Integer.class));
        String linked = jdbc.queryForObject("SELECT user_id::text FROM roster_entries WHERE id = ?", String.class, id(person));
        assertEquals(signupWon ? members.findByLoginId(login).orElseThrow().id() : existing.id(), Long.parseLong(linked));
    }

    @Test
    void administratorDemotionWhileDirectoryMutationWaitsForGuardIsRechecked() throws Exception {
        JsonNode csrf = administrator.csrf();
        try (Connection connection = lockGuard(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var creating = executor.submit(() -> administrator.send("POST", "/api/v1/roster", Map.of("name", "잠금 대기 명단"), csrf));
            awaitGuardWaiter();
            try (var update = connection.prepareStatement("UPDATE users SET global_role = 'MEMBER', version = version + 1 WHERE id = ?")) {
                update.setLong(1, admin.id());
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertError(creating.get(20, TimeUnit.SECONDS), 403, "FORBIDDEN");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM roster_entries", Integer.class));
        }
    }

    @Test
    void leadershipRemovalWhileSongMutationWaitsForGuardIsRechecked() throws Exception {
        JsonNode person = roster("동시 권한 합성 팀장");
        JsonNode assigned = team("동시 권한 조", "REGULAR");
        add(assigned, person);
        assertEquals(200, leader(assigned, person).statusCode());
        Client leader = registerAndLogin(person);
        JsonNode csrf = leader.csrf();
        try (Connection connection = lockGuard(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var creating = executor.submit(() -> leader.send("POST", teamPath(assigned) + "/songs",
                    Map.of("title", "낡은 팀장 권한 곡"), csrf));
            awaitGuardWaiter();
            try (var update = connection.prepareStatement("UPDATE team_memberships SET role = 'MEMBER', version = version + 1 "
                    + "WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'")) {
                update.setLong(1, id(assigned));
                update.setLong(2, id(person));
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertError(creating.get(20, TimeUnit.SECONDS), 403, "FORBIDDEN");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM team_songs", Integer.class));
            assertEquals("MEMBER", membership(data(leader.get("/api/v1/auth/me")), assigned).get("role").asString());
        }
    }

    @Test
    void membershipRemovalWhileSongEditWaitsPreservesSongAndDoesNotTrustOldSessionMembership() throws Exception {
        JsonNode person = roster("동시 제거 합성 팀장");
        JsonNode assigned = team("동시 제거 합성 조", "REGULAR");
        add(assigned, person);
        assertEquals(200, leader(assigned, person).statusCode());
        Client leader = registerAndLogin(person);
        JsonNode song = success(song(leader, assigned, "제거 전 합성 곡"), 201);
        JsonNode csrf = leader.csrf();
        try (Connection connection = lockGuard(); ExecutorService executor = Executors.newSingleThreadExecutor()) {
            var editing = executor.submit(() -> leader.send("PATCH", songPath(assigned, song),
                    updateSong(song, "제거 뒤 낡은 편집"), csrf));
            awaitGuardWaiter();
            try (var update = connection.prepareStatement("UPDATE team_memberships SET status = 'REMOVED', role = 'MEMBER', "
                    + "version = version + 1 WHERE team_id = ? AND roster_entry_id = ? AND status = 'ACTIVE'")) {
                update.setLong(1, id(assigned));
                update.setLong(2, id(person));
                assertEquals(1, update.executeUpdate());
            }
            connection.commit();
            assertError(editing.get(20, TimeUnit.SECONDS), 403, "FORBIDDEN");
            assertEquals("제거 전 합성 곡", data(administrator.get(teamPath(assigned) + "/songs")).get(0).get("title").asString());
            assertEquals(0, data(leader.get("/api/v1/auth/me")).get("teamMemberships").size());
        }
    }

    private Connection lockGuard() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
        try (var statement = connection.prepareStatement("SELECT id FROM directory_mutation_guard WHERE id = 1 FOR UPDATE");
                var rows = statement.executeQuery()) { assertTrue(rows.next()); }
        return connection;
    }

    private Connection fixtureConnection(String schema) throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setSchema(schema);
        return connection;
    }

    private long insertUpgradeAccount(Connection connection, String login, String hash, String role, Instant created)
            throws Exception {
        try (var insert = connection.prepareStatement("""
                INSERT INTO users(login_id, password_hash, display_name, global_role, status, created_at)
                VALUES (?, ?, '합성 업그레이드 계정', ?, 'ACTIVE', ?) RETURNING id
                """)) {
            insert.setString(1, login);
            insert.setString(2, hash);
            insert.setString(3, role);
            insert.setTimestamp(4, Timestamp.from(created));
            try (var rows = insert.executeQuery()) { assertTrue(rows.next()); return rows.getLong(1); }
        }
    }

    private void awaitGuardWaiter() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type = 'Lock' "
                        + "AND query ILIKE '%directory_mutation_guard%')", Boolean.class)));
    }

    private Member account(String role) {
        return members.insert(uniqueLogin(), passwords.encode(password), "합성 계정", role, Instant.now()).orElseThrow();
    }

    private Client authenticated(Member member) throws Exception {
        Client client = new Client();
        assertEquals(200, client.login(member.loginId(), password).statusCode());
        return client;
    }

    private Client registerAndLogin(JsonNode person) throws Exception {
        Client client = new Client();
        String login = uniqueLogin();
        assertEquals(201, register(client, issue(person, null), login, "합성 가입 계정").statusCode());
        assertEquals(200, client.login(login, password).statusCode());
        return client;
    }

    private JsonNode roster(String name) throws Exception {
        return success(administrator.mutate("POST", "/api/v1/roster", Map.of("name", name)), 201);
    }

    private JsonNode team(String name, String kind) throws Exception {
        return success(administrator.mutate("POST", "/api/v1/teams", Map.of("name", name, "kind", kind)), 201);
    }

    private JsonNode add(JsonNode team, JsonNode person) throws Exception {
        return success(administrator.mutate("POST", teamPath(team) + "/members",
                Map.of("rosterEntryId", person.get("id").asString())), 201);
    }

    private HttpResponse<String> leader(JsonNode team, JsonNode person) throws Exception {
        return administrator.mutate("PUT", teamPath(team) + "/leader", Map.of("rosterEntryId", person.get("id").asString()));
    }

    private JsonNode issue(JsonNode person, JsonNode replacing) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("rosterEntryId", person.get("id").asString());
        if (replacing != null) { body.put("replacesInvitationId", replacing.get("id").asString()); }
        return success(administrator.mutate("POST", "/api/v1/invitations", body), 201);
    }

    private HttpResponse<String> register(Client client, JsonNode invitation, String login, String displayName) throws Exception {
        return client.mutate("POST", "/api/v1/auth/register", registration(invitation, login, displayName));
    }

    private Map<String, Object> registration(JsonNode invitation, String login, String displayName) {
        return Map.of("invitationToken", invitation.get("token").asString(), "loginId", login, "password", password,
                "displayName", displayName, "globalRole", "GLOBAL_ADMIN");
    }

    private HttpResponse<String> song(Client client, JsonNode team, String title) throws Exception {
        return client.mutate("POST", teamPath(team) + "/songs", Map.of("title", title));
    }

    private Map<String, Object> updateSong(JsonNode song, String title) {
        return Map.of("title", title, "sortOrder", song.get("sortOrder").asLong(), "version", version(song));
    }

    private HttpResponse<String> role(Client client, JsonNode user, String role) throws Exception {
        return client.mutate("PATCH", "/api/v1/users/" + user.get("id").asString() + "/role",
                Map.of("globalRole", role, "version", version(user)));
    }

    private long countKind(JsonNode profile, String kind) {
        return profile.get("teamMemberships").valueStream().filter(value -> kind.equals(value.get("kind").asString())).count();
    }

    private JsonNode membership(JsonNode profile, JsonNode team) {
        return profile.get("teamMemberships").valueStream().filter(value -> team.get("id").asString().equals(
                value.get("teamId").asString())).findFirst().orElseThrow();
    }

    private JsonNode find(HttpResponse<String> response, String id) {
        assertEquals(200, response.statusCode());
        return data(response).valueStream().filter(value -> id.equals(value.get("id").asString())).findFirst().orElseThrow();
    }

    private JsonNode success(HttpResponse<String> response, int status) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        return data(response);
    }

    private String safeFailure(HttpResponse<String> response) {
        JsonNode error = json.readTree(response.body()).get("error");
        return error == null ? "Unexpected status without API error" : error.get("code").asString();
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), () -> safeFailure(response));
        assertEquals(code, json.readTree(response.body()).at("/error/code").asString());
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertSafe(response);
    }

    private void assertSafe(HttpResponse<String> response) {
        assertFalse(response.body().contains(password));
        assertFalse(response.body().contains("passwordHash"));
        assertFalse(response.body().contains("password_hash"));
        assertFalse(response.body().contains("codeDigest"));
        assertFalse(response.body().contains("code_digest"));
    }

    private String teamPath(JsonNode team) { return "/api/v1/teams/" + team.get("id").asString(); }
    private String rosterPath(JsonNode roster) { return "/api/v1/roster/" + roster.get("id").asString(); }
    private String songPath(JsonNode team, JsonNode song) { return teamPath(team) + "/songs/" + song.get("id").asString(); }
    private long id(JsonNode node) { return Long.parseLong(node.get("id").asString()); }
    private long version(JsonNode node) { return node.get("version").asLong(); }
    private String uniqueLogin() { return "fixture-" + UUID.randomUUID().toString().substring(0, 16); }
    private JsonNode data(HttpResponse<String> response) { return json.readTree(response.body()).get("data"); }
    private List<Integer> statuses(List<HttpResponse<String>> responses) {
        return responses.stream().map(HttpResponse::statusCode).sorted().toList();
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
        HttpResponse<String> login(String login, String newPassword) throws Exception {
            return mutate("POST", "/api/v1/auth/login", Map.of("loginId", login, "password", newPassword));
        }
        HttpResponse<String> mutate(String method, String path, Object body) throws Exception {
            return send(method, path, body, csrf());
        }
        HttpResponse<String> send(String method, String path, Object body, JsonNode csrf) throws Exception {
            var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
            if (csrf != null) { builder.header(csrf.get("headerName").asString(), csrf.get("token").asString()); }
            if (body != null) { builder.header("Content-Type", "application/json"); }
            builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
    }
}
