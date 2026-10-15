import { cleanupCurrentPushDevice } from "@/features/push/browser";

/** The server already revoked all sessions; browser cleanup must not delay clearing access. */
export async function finishSelfWithdrawal(clearSession: () => Promise<void>, navigate: () => void) {
  const cleanup = cleanupCurrentPushDevice().catch(() => ({ browserCleanupComplete: false }));
  await clearSession();
  navigate();
  return cleanup;
}
