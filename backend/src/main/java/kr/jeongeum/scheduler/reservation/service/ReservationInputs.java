package kr.jeongeum.scheduler.reservation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.api.ApiException;

final class ReservationInputs {
    private ReservationInputs() {}
    static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { throw ApiException.invalidInput(); }
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException exception) { throw ApiException.invalidInput(); }
    }
    static String keyDigest(String key) {
        if (key == null || key.length() < 16 || key.length() > 128
                || key.chars().anyMatch(character -> character < 33 || character > 126)) { throw ApiException.invalidInput(); }
        return digest(key);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    static List<String> changeInstruments(List<String> codes) {
        if (codes == null) { return null; }
        Set<String> known = Set.of("VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR");
        TreeSet<String> canonical = new TreeSet<>();
        if (codes.isEmpty() || codes.size() > 2) { throw ApiException.invalidInput(); }
        for (String code : codes) {
            if (code == null || !known.contains(code) || !canonical.add(code)) { throw ApiException.invalidInput(); }
        }
        return List.copyOf(canonical);
    }
    static void openDate(LocalDate date, LocalDate today) {
        if (date.isBefore(today) || date.isAfter(today.plusDays(14))) {
            throw new ApiException(409, "DATE_NOT_OPEN", "오늘부터 14일 뒤까지 예약할 수 있습니다.");
        }
    }
}
