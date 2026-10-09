package kr.jeongeum.scheduler.auth.security;

import java.io.Serializable;

/** The only application identity persisted in the server session. */
public record MemberPrincipal(long memberId) implements Serializable {
    private static final long serialVersionUID = 1L;
}
