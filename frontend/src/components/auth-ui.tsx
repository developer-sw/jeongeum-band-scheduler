"use client";

import Link from "next/link";
import { memberAccess } from "@/features/auth/access";
import { useSession } from "@/features/auth/session-provider";

export const inputClass = "w-full min-h-11 rounded-xl border border-diary-border bg-white px-4 py-3 text-base text-navy outline-none focus:border-navy focus:ring-2 focus:ring-yellow disabled:bg-paper";
export const primaryButton = "inline-flex min-h-11 items-center justify-center rounded-xl bg-yellow px-5 py-3 text-sm font-bold text-navy hover:bg-yellow/80 disabled:cursor-not-allowed disabled:opacity-60";
export const secondaryButton = "inline-flex min-h-11 items-center justify-center rounded-xl border border-diary-border bg-white px-5 py-3 text-sm font-semibold text-navy hover:bg-paper disabled:cursor-not-allowed disabled:opacity-60";

export function FormCard({ title, description, children }: { title: string; description: string; children: React.ReactNode }) {
  return <section className="warm-panel mx-auto max-w-lg rounded-3xl p-6 sm:p-10"><h1 className="text-3xl font-extrabold tracking-tight">{title}</h1><p className="mt-3 mb-8 text-sm leading-7 text-navy-soft">{description}</p>{children}</section>;
}

export function FormError({ message }: { message?: string | null }) {
  return message ? <p role="alert" className="rounded-xl border border-red-200 bg-red-50 px-4 py-3 text-sm leading-6 text-red-800">{message}</p> : null;
}

export function Field({ id, label, hint, children }: { id: string; label: string; hint?: string; children: React.ReactNode }) {
  return <div className="space-y-2"><label htmlFor={id} className="block text-sm font-semibold">{label}</label>{children}{hint && <p id={`${id}-hint`} className="text-sm leading-6 text-navy-soft">{hint}</p>}</div>;
}

export function SessionGate({ children, admin = false }: { children: React.ReactNode; admin?: boolean }) {
  const session = useSession();
  if (session.loading) return <p role="status" className="py-12 text-center text-navy-soft">회원 정보를 확인하고 있어요.</p>;
  if (session.error && !session.member) return <div className="warm-panel rounded-3xl p-8"><h1 className="text-xl font-bold">연결을 확인해 주세요</h1><p className="my-4 text-sm leading-7">회원 정보를 불러오지 못했어요.</p><button type="button" className={secondaryButton} onClick={() => void session.refresh()}>다시 확인</button></div>;
  const access = memberAccess(session.member, admin);
  if (access !== "ALLOWED") return <div className="warm-panel rounded-3xl p-8"><h1 className="text-xl font-bold">{access === "FORBIDDEN" ? "관리자만 이용할 수 있어요" : "로그인이 필요해요"}</h1><p className="my-4 text-sm leading-7">{access === "FORBIDDEN" ? "회원·팀 구성과 초대 관리는 전체 관리자가 처리해요." : "초대 가입을 마쳤다면 본인 계정으로 로그인해 주세요."}</p><Link className={primaryButton} href={access === "FORBIDDEN" ? "/" : "/login"}>{access === "FORBIDDEN" ? "홈으로" : "로그인하기"}</Link></div>;
  return children;
}
