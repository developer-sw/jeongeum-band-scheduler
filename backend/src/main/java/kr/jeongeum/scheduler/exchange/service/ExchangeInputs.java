package kr.jeongeum.scheduler.exchange.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.exchange.api.ExchangeRequests;

final class ExchangeInputs {
    private ExchangeInputs() {}
    static String key(String value) {
        if (value == null || value.length()<16 || value.length()>128 || value.chars().anyMatch(c -> c<33 || c>126)) { throw ApiException.invalidInput(); }
        return digest(value);
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException error) { throw new IllegalStateException("SHA-256 unavailable",error); }
    }
    static List<ExchangeRequests.Item> items(List<ExchangeRequests.Item> values) {
        if (values==null || values.isEmpty() || values.size()>2 || values.stream().anyMatch(java.util.Objects::isNull)) { throw shape(); }
        for (var item:values) { DirectoryInputs.id(item.reservationId()); DirectoryInputs.version(item.expectedVersion()); }
        if (values.stream().map(ExchangeRequests.Item::reservationId).distinct().count()!=values.size()) { throw shape(); }
        return values;
    }
    static ApiException shape() { return new ApiException(400,"INVALID_EXCHANGE_SHAPE","한 건끼리 또는 연속 60분 두 건과 90분 한 건을 교환할 수 있습니다."); }
}
