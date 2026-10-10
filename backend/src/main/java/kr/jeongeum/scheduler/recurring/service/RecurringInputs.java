package kr.jeongeum.scheduler.recurring.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import kr.jeongeum.scheduler.common.api.ApiException;

final class RecurringInputs {
    private RecurringInputs() {}
    static LocalDate date(String input) {
        if (input == null || !input.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { throw ApiException.invalidInput(); }
        try { return LocalDate.parse(input); }
        catch (DateTimeParseException exception) { throw ApiException.invalidInput(); }
    }
    static int weekday(Integer input) {
        if (input == null || input < 1 || input > 7) { throw ApiException.invalidInput(); }
        return input;
    }
    static String key(String input) {
        if (input == null || input.length() < 16 || input.length() > 128
                || input.chars().anyMatch(character -> character < 33 || character > 126)) { throw ApiException.invalidInput(); }
        return digest(input);
    }
    static String digest(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
}
