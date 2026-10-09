"use client";

import { FormError, primaryButton, secondaryButton } from "./auth-ui";
import { authErrorMessage } from "@/features/auth/errors";

export const panelClass = "rounded-3xl border border-navy/15 p-6 sm:p-8";
export const teamKindLabel = (kind: "REGULAR" | "ADDITIONAL") => kind === "REGULAR" ? "정규조" : "추가조";

export function QueryState({ pending, error, retry }: { pending: boolean; error: unknown; retry: () => void }) {
  if (pending) return <p role="status" className="py-8 text-navy-soft">목록을 불러오고 있어요.</p>;
  return error ? <div className="space-y-4"><FormError message={authErrorMessage(error)} /><button type="button" className={secondaryButton} onClick={retry}>다시 불러오기</button></div> : null;
}

export function Confirmation({ title, description, pending, onConfirm, onCancel, confirmLabel = "확인" }: { title: string; description: string; pending: boolean; onConfirm: () => void; onCancel: () => void; confirmLabel?: string }) {
  return <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold break-words">{title}</h2><p className="mt-2 text-sm leading-7">{description}</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={pending} onClick={onConfirm}>{pending ? "처리하는 중…" : confirmLabel}</button><button type="button" className={secondaryButton} disabled={pending} onClick={onCancel}>취소</button></div></section>;
}
