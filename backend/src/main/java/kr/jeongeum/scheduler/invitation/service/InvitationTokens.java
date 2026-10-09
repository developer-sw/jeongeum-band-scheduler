package kr.jeongeum.scheduler.invitation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class InvitationTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private InvitationTokens() {}

    public static String create() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    public static String requireToken(String value) {
        if (value == null || value.isBlank() || value.length() > 128) { throw ApiException.invalidInput(); }
        if (!value.matches("[A-Za-z0-9_-]{43}")) { throw unavailable(); }
        return value;
    }

    public static ApiException unavailable() {
        return new ApiException(410, "INVITATION_UNAVAILABLE", "사용할 수 없는 초대입니다. 관리자에게 재발급을 요청해 주세요.");
    }
}
