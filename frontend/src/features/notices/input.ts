import type { Notice, NoticeInput, NoticeIntent } from "./types";

export function noticeInputError(input: NoticeInput): string | null {
  const title = input.title.trim(); const body = input.body.trim();
  if (!title) return "공지 제목을 입력해 주세요.";
  if (title.length > 120) return "공지 제목은 120자 이내로 입력해 주세요.";
  if (!body) return "공지 본문을 입력해 주세요.";
  if (body.length > 10_000) return "공지 본문은 10000자 이내로 입력해 주세요.";
  return null;
}
export function noticeIntent(input: NoticeInput, notice?: Notice): NoticeIntent {
  const normalized = { title: input.title.trim(), body: input.body.trim(), pinned: input.pinned };
  return notice ? { kind: "UPDATE", id: notice.id, input: { ...normalized, expectedVersion: notice.version } } : { kind: "CREATE", input: normalized };
}
export function noticeChanged(selected: Notice, current: Notice | undefined): boolean { return !current || current.deleted || current.id !== selected.id || current.version !== selected.version; }
