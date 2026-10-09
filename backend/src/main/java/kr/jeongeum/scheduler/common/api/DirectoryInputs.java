package kr.jeongeum.scheduler.common.api;

public final class DirectoryInputs {
    private DirectoryInputs() {}
    public static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]*")) { throw ApiException.invalidInput(); }
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw ApiException.invalidInput(); }
    }
    public static Long optionalId(String value) { return value == null ? null : id(value); }
    public static long version(Long value) {
        if (value == null || value < 0) { throw ApiException.invalidInput(); }
        return value;
    }
    public static String text(String value, int maximum, boolean required) {
        if (value == null) {
            if (required) { throw ApiException.invalidInput(); }
            return null;
        }
        String normalized = value.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length > maximum || normalized.codePoints().anyMatch(Character::isISOControl)
                || (required && length == 0)) { throw ApiException.invalidInput(); }
        return length == 0 ? null : normalized;
    }
    public static ApiException notFound() {
        return new ApiException(404, "RESOURCE_NOT_FOUND", "대상을 찾을 수 없습니다.");
    }
    public static String memo(String value) {
        if (value == null) { return null; }
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > 1000
                || normalized.codePoints().anyMatch(code -> Character.isISOControl(code)
                        && code != '\n' && code != '\r' && code != '\t')) { throw ApiException.invalidInput(); }
        return normalized.isEmpty() ? null : normalized;
    }
    public static void sameVersion(long actual, long expected) {
        if (actual != expected) { throw new ApiException(409, "VERSION_CONFLICT", "내용이 변경되었습니다. 새로고침 후 다시 시도해 주세요."); }
    }
}
