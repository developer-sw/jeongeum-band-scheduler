package kr.jeongeum.scheduler.common.api;

public class ApiException extends RuntimeException {
    private final int status;
    private final String code;

    public ApiException(int status, String code, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }

    public static ApiException invalidInput() {
        return new ApiException(400, "INVALID_INPUT", "입력한 항목을 확인해 주세요.");
    }
}
