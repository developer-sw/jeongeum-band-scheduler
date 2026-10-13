import { ApiError } from "@/lib/api/client";
export function notificationErrorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === "OFFLINE") return "연결을 확인한 뒤 직접 다시 시도해 주세요. 읽음 요청은 자동으로 보내지 않아요.";
    if (error.status === 401 || error.code === "INACTIVE_USER") return "본인 계정으로 로그인한 뒤 알림함을 다시 확인해 주세요.";
    if (error.code === "CSRF_INVALID") return "요청의 보안 확인을 완료하지 못했어요. 직접 다시 시도하면 보안 정보를 새로 확인해요. 이전 읽음 결과를 확인 중이라면 같은 읽음 결과 다시 확인을 눌러 주세요.";
    if (error.status === 403) return "현재 계정으로 이 알림을 처리할 수 없어요. 회원 상태를 다시 확인해 주세요.";
    if (error.status === 404) return "본인의 알림을 확인할 수 없어요. 최신 알림 목록을 다시 불러와 주세요.";
    if (error.status === 400) return "알림과 확인 기준 시각을 다시 불러온 뒤 진행해 주세요.";
  }
  return "읽음 결과나 알림 목록을 확인하지 못했어요. 연결 후 다시 확인해 주세요.";
}
