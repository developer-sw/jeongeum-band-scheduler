package kr.jeongeum.scheduler.notice.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.notice.api.NoticeResponse;

final class NoticeInputs {
    record Cursor(boolean pinned, Instant createdAt, long id) {}
    private NoticeInputs() {}
    static String body(String value) {
        if (value == null) { throw ApiException.invalidInput(); }
        String normalized=value.strip();
        if (normalized.isEmpty() || normalized.codePointCount(0,normalized.length())>10000
                || normalized.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')) {
            throw ApiException.invalidInput();
        }
        return normalized;
    }
    static boolean pinned(Boolean value) { if (value==null) { throw ApiException.invalidInput(); } return value; }
    static String key(String value) {
        if (value==null || value.length()<16 || value.length()>128 || value.chars().anyMatch(c -> c<33 || c>126)) {
            throw ApiException.invalidInput();
        }
        return digest(value);
    }
    static String fingerprint(String method,String route,long version,String title,String body,boolean pinned) {
        return digest(method+"|"+route+"|"+version+"|"+pinned+"|"+title.length()+":"+title+body.length()+":"+body);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); }
    }
    static Cursor cursor(String value) {
        if (value==null) { return null; }
        if (value.isEmpty() || value.length()>160 || !value.matches("[A-Za-z0-9_-]+")) { throw ApiException.invalidInput(); }
        try {
            String[] parts=new String(Base64.getUrlDecoder().decode(value),StandardCharsets.UTF_8).split("\\|",-1);
            if (parts.length!=3 || !(parts[0].equals("0") || parts[0].equals("1"))) { throw ApiException.invalidInput(); }
            Cursor cursor=new Cursor(parts[0].equals("1"),Instant.parse(parts[1]),DirectoryInputs.id(parts[2]));
            if (cursor.createdAt().isBefore(Instant.parse("0001-01-01T00:00:00Z"))
                    || cursor.createdAt().isAfter(Instant.parse("9999-12-31T23:59:59.999999Z"))) { throw ApiException.invalidInput(); }
            if (!encode(cursor).equals(value)) { throw ApiException.invalidInput(); }
            return cursor;
        } catch (IllegalArgumentException | java.time.DateTimeException error) { throw ApiException.invalidInput(); }
    }
    static String cursor(NoticeResponse notice) {
        return encode(new Cursor(notice.pinned(),notice.createdAt(),Long.parseLong(notice.id())));
    }
    private static String encode(Cursor cursor) {
        String source=(cursor.pinned()?"1":"0")+"|"+cursor.createdAt()+"|"+cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(source.getBytes(StandardCharsets.UTF_8));
    }
}
