package kr.jeongeum.scheduler.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.UUID;
import org.springframework.core.serializer.support.DeserializingConverter;
import org.springframework.core.serializer.support.SerializingConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;

/**
 * A session keeps one random token even when independent requests initialize it concurrently.
 * JDBC sessions must use IMMEDIATE flushing so their parent row exists before token insertion.
 * The existing Spring Session Java serialization format and attribute name remain compatible.
 */
public final class JdbcSessionCsrfTokenRepository implements CsrfTokenRepository {
    public static final String ATTRIBUTE_NAME =
            "org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository.CSRF_TOKEN";
    private final JdbcTemplate jdbc;
    private final SerializingConverter serializer = new SerializingConverter();
    private final DeserializingConverter deserializer = new DeserializingConverter();

    public JdbcSessionCsrfTokenRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CsrfToken generateToken(HttpServletRequest request) {
        return new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", UUID.randomUUID().toString());
    }

    @Override
    public void saveToken(CsrfToken token, HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(token != null);
        if (session == null) return;
        persistRotatedIdentity(request, session);
        if (token == null) {
            jdbc.update("""
                    DELETE FROM spring_session_attributes a USING spring_session s
                    WHERE a.session_primary_id = s.primary_id AND s.session_id = ? AND a.attribute_name = ?
                    """, session.getId(), ATTRIBUTE_NAME);
        } else {
            jdbc.update("""
                    INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                    SELECT primary_id, ?, ? FROM spring_session WHERE session_id = ?
                    ON CONFLICT (session_primary_id, attribute_name)
                    DO UPDATE SET attribute_bytes = EXCLUDED.attribute_bytes
                    """, ATTRIBUTE_NAME, serializer.convert(token), session.getId());
        }
        // Do not set the request's HttpSession attribute: a stale response save must not replace the winner.
    }

    @Override
    public CsrfToken loadToken(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) return null;
        CsrfToken token = load(session.getId());
        if (token == null) {
            // JdbcSession.changeSessionId() defers its write even with IMMEDIATE flushing.
            // Authentication's CSRF strategy must see the existing token under the new ID
            // so it clears that token before the login response publishes the rotated session.
            persistRotatedIdentity(request, session);
            token = load(session.getId());
        }
        return token;
    }

    @Override
    public DeferredCsrfToken loadDeferredToken(HttpServletRequest request, HttpServletResponse response) {
        return new DeferredCsrfToken() {
            private CsrfToken token;
            private boolean generated;

            @Override
            public synchronized CsrfToken get() {
                if (token == null) {
                    token = loadToken(request);
                    generated = token == null;
                    if (generated) token = initialize(request);
                }
                return token;
            }

            @Override
            public boolean isGenerated() {
                get();
                return generated;
            }
        };
    }

    private CsrfToken initialize(HttpServletRequest request) {
        HttpSession session = request.getSession();
        persistRotatedIdentity(request, session);
        String sessionId = session.getId();
        jdbc.update("""
                INSERT INTO spring_session_attributes (session_primary_id, attribute_name, attribute_bytes)
                SELECT primary_id, ?, ? FROM spring_session WHERE session_id = ?
                ON CONFLICT (session_primary_id, attribute_name) DO NOTHING
                """, ATTRIBUTE_NAME, serializer.convert(generateToken(request)), sessionId);
        // This is a separate statement: after a conflict wait, READ COMMITTED sees the committed winner.
        CsrfToken winner = load(sessionId);
        if (winner == null) throw new IllegalStateException("CSRF session is no longer available");
        return winner;
    }

    private void persistRotatedIdentity(HttpServletRequest request, HttpSession session) {
        // This supported setter flushes pending metadata without changing the configured lifetime.
        // An unchanged, stale request must not write its old ID after another request rotated it.
        String requested = request.getRequestedSessionId();
        if (requested != null && !requested.equals(session.getId())) {
            session.setMaxInactiveInterval(session.getMaxInactiveInterval());
        }
    }

    private CsrfToken load(String sessionId) {
        List<byte[]> values = jdbc.query("""
                SELECT a.attribute_bytes FROM spring_session_attributes a JOIN spring_session s
                ON s.primary_id = a.session_primary_id WHERE s.session_id = ? AND a.attribute_name = ?
                """, (row, index) -> row.getBytes(1), sessionId, ATTRIBUTE_NAME);
        if (values.isEmpty()) return null;
        Object stored = deserializer.convert(values.getFirst());
        if (!(stored instanceof CsrfToken token)) throw new IllegalStateException("Invalid stored CSRF token");
        return token;
    }
}
