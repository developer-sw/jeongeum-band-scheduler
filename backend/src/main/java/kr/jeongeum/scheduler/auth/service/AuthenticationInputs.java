package kr.jeongeum.scheduler.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class AuthenticationInputs {
    private AuthenticationInputs() {}

    public static String loginId(String value) {
        if (value == null) { throw ApiException.invalidInput(); }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,31}")) { throw ApiException.invalidInput(); }
        return normalized;
    }

    public static String name(String value) {
        if (value == null) { throw ApiException.invalidInput(); }
        String normalized = value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 40 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw ApiException.invalidInput();
        }
        return normalized;
    }

    public static void password(String value) {
        if (value == null || value.codePointCount(0, value.length()) < 12
                || value.getBytes(StandardCharsets.UTF_8).length > 72 || value.indexOf('\0') >= 0) {
            throw ApiException.invalidInput();
        }
    }
}
