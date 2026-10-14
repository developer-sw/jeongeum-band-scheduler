import { ApiError } from "@/lib/api/client";

export function pushErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === "OFFLINE") return "오프라인에서는 알림 설정을 변경할 수 없어요. 연결 후 직접 다시 시도해 주세요.";
    if (error.status === 401) return "로그인 상태를 다시 확인해 주세요.";
    if (error.code === "CSRF_INVALID") return "보안 확인이 갱신됐어요. 직접 다시 시도해 주세요.";
    if (error.status === 403) return "현재 계정으로 이 기기의 알림을 변경할 수 없어요.";
    if (error.status === 404) return "이 기기의 등록이 이미 해제됐어요. 기기 목록을 다시 확인해 주세요.";
    if (error.code === "PUSH_DISABLED") return "현재 서버의 Push 발송이 꺼져 있어요. 내 알림함은 계속 이용할 수 있어요.";
  }
  if (error instanceof Error && error.name === "NotAllowedError") return "알림이 허용되지 않았어요. 브라우저나 휴대폰 설정에서 정음 알림을 허용해 주세요.";
  return "기기 연결 결과를 확인하지 못했어요. 이미 연결됐을 수 있으니 목록을 새로 확인한 뒤 직접 다시 시도해 주세요. 내 알림함은 계속 이용할 수 있어요.";
}
