"use client";

import { useEffect, useRef } from "react";
import { FormError, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { AdminMember, RosterEntry } from "./types";

export type RoleTarget = { kind: "ACCOUNT"; account: AdminMember } | { kind: "SIGNUP"; entry: RosterEntry };

export function MemberRoleConfirmation({ target, pending, stale, error, onConfirm, onCancel, onRefresh }: {
  target: RoleTarget; pending: boolean; stale: boolean; error: string | null;
  onConfirm: () => void; onCancel: () => void; onRefresh: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const role = target.kind === "ACCOUNT" ? target.account.globalRole : target.entry.signupGlobalRole;
  const name = target.kind === "ACCOUNT" ? target.account.displayName : target.entry.name;
  const assigning = role !== "GLOBAL_ADMIN";

  useEffect(() => {
    heading.current?.focus({ preventScroll: true });
    heading.current?.scrollIntoView({ block: "nearest" });
  }, []);

  return <div role="group" aria-label="전체 관리자 변경 확인" className="mt-4 rounded-2xl border border-yellow bg-yellow/10 p-4">
    <h4 ref={heading} tabIndex={-1} className="font-bold break-words">{assigning ? `${name}님을 전체 관리자로 지정할까요?` : `${name}님의 전체 관리자 지정을 해제할까요?`}</h4>
    <p className="mt-2 text-sm leading-7 break-words">{target.kind === "SIGNUP"
      ? `명단 #${target.entry.id}. ${assigning ? "이 명단의 개인 초대로 가입하면 전체 관리자 권한이 적용돼요." : "개인 초대 가입 시 일반 회원으로 가입해요."} 팀·팀장 배정은 유지돼요. 가입한 계정도 연결 후 카드에서 ‘전체 관리자 지정’을 할 수 있어요. 연결만으로 권한이 바뀌지는 않아요.`
      : `${target.account.loginId} (계정 #${target.account.id}). 전체 관리자는 회원·팀·초대·예약을 관리할 수 있어요. 팀장 겸임은 유지되며 마지막 활성 전체 관리자의 권한은 해제할 수 없어요.`}</p>
    {stale && <p role="alert" className="mt-3 text-sm leading-7">이 구성원의 상태가 바뀌었어요. 취소 후 현재 정보를 확인하고 다시 선택해 주세요.</p>}
    <FormError message={error} />
    <div className="mt-4 flex flex-wrap gap-3">
      <button type="button" className={primaryButton} disabled={pending || stale} onClick={onConfirm}>{pending ? "처리하는 중…" : assigning ? "전체 관리자 지정" : "전체 관리자 해제"}</button>
      <button type="button" className={secondaryButton} disabled={pending} onClick={onCancel}>취소</button>
      {error && <button type="button" className={secondaryButton} disabled={pending} onClick={onRefresh}>현재 상태 다시 불러오기</button>}
    </div>
  </div>;
}
