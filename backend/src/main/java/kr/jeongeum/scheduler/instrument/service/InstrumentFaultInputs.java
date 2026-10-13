package kr.jeongeum.scheduler.instrument.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;

final class InstrumentFaultInputs {
    private static final Set<String> CODES = Set.of("VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");
    private InstrumentFaultInputs() {}
    static String code(String value) {
        if (value == null || !CODES.contains(value)) { throw ApiException.invalidInput(); }
        return value;
    }
    static String description(String value) {
        String normalized = DirectoryInputs.memo(value);
        if (normalized == null) { throw ApiException.invalidInput(); }
        return normalized;
    }
    static String key(String value) {
        if (value == null || value.length() < 16 || value.length() > 128 || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw ApiException.invalidInput();
        }
        return digest(value);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable", error); }
    }
}
