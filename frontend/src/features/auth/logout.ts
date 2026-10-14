import { cleanupCurrentPushDevice } from "@/features/push/browser";
import { logout } from "./api";

/** Attempt server logout immediately; local Push cleanup is independent and best effort. */
export async function logoutWithPushCleanup(signal?: AbortSignal): Promise<{ browserCleanupComplete: boolean }> {
  const cleanup = cleanupCurrentPushDevice().catch(() => ({ browserCleanupComplete: false }));
  await logout(signal);
  return cleanup;
}

export const logoutPushCleanupMessage = "로그아웃했어요. 서버에서 이 기기의 알림은 중단했지만 브라우저의 알림 정리를 마치지 못했어요. 공용 기기라면 브라우저 알림 권한을 끄거나 사이트 데이터를 삭제해 주세요. 다른 기기의 설정은 유지돼요.";
