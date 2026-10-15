package kr.jeongeum.scheduler.lifecycle.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import kr.jeongeum.scheduler.common.api.ApiException;

final class LifecycleInputs {
    private LifecycleInputs() {}
    static String keyDigest(String key) {
        if (key == null || key.length() < 16 || key.length() > 128
                || key.chars().anyMatch(character -> character < 33 || character > 126)) {
            throw ApiException.invalidInput();
        }
        return digest(key);
    }
    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
