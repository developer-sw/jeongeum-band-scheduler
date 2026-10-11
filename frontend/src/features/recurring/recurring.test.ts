import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import RecurringAssignmentsPage from "@/app/admin/recurring-assignments/page";
import { ApiError } from "@/lib/api/client";
import { IdempotentRequestState } from "@/lib/api/idempotent-request-state";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey, replaceSessionCache } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, pruneDirectoryCache, teamsQuery } from "@/features/directory/queries";
import type { Team } from "@/features/directory/types";
import { MobileSchedule, cancellationNotice, quotaHistoryLabel } from "@/features/reservations/view";
import type { ReservationDetail, ScheduleDay } from "@/features/reservations/types";
import { getAssignments, previewAssignment, submitAssignmentIntent } from "./api";
import { assignmentInputError, nextRevisionAfter, previewInputKey, revisionAt } from "./assignment-input";
import { AssignmentEditor, AssignmentEndPanel } from "./editor";
import { assignmentConflicts, assignmentErrorMessage, staleAssignmentReloadNotice } from "./errors";
import { assignmentsQuery } from "./queries";
import { AssignmentCard, ConflictList, PreviewView } from "./view";
import type { Assignment, AssignmentIntent, AssignmentPreview, AssignmentPreviewInput } from "./types";

const meta = { serverTime: "2026-10-12T14:10:00+09:00", timeZone: "Asia/Seoul" };
const admin: Member = { id: "9007199254740993", loginId: "fixture.recurring", displayName: "가상 관리자", globalRole: "GLOBAL_ADMIN", status: "ACTIVE", teamMemberships: [] };
const teams: Team[] = [{ id: "31", name: "가상 정규조", kind: "REGULAR", status: "ACTIVE", version: 0 }, { id: "32", name: "가상 추가조", kind: "ADDITIONAL", status: "ACTIVE", version: 0 }, { id: "33", name: "해체된 가상 팀", kind: "REGULAR", status: "DISBANDED", version: 1 }];
const assignment: Assignment = { id: "9007199254740994", teamId: "31", teamName: "가상 정규조", teamKind: "REGULAR", status: "ACTIVE", version: 2, createdByUserId: admin.id, endDate: null, revisions: [{ id: "51", revisionNo: 1, effectiveDate: "2026-10-12", weekday: 1, blockCode: "B01" }, { id: "52", revisionNo: 2, effectiveDate: "2026-11-01", weekday: 3, blockCode: "B07" }], occurrences: [{ id: "61", weekStartDate: "2026-10-12", revisionId: "51", originalScheduledDate: "2026-10-12", originalBlockCode: "B01", state: "CANCELED_EXCEPTION", reservationIds: ["71"] }] };
const input: AssignmentPreviewInput = { teamId: "31", weekday: 1, blockCode: "B01", effectiveDate: "2026-10-12" };
const preview: AssignmentPreview = { canApply: true, conflicts: [], plannedOccurrences: [{ weekStartDate: "2026-10-12", date: "2026-10-12", blockCode: "B01", state: "CANCELED_EXCEPTION", preserved: true }, { weekStartDate: "2026-10-19", date: "2026-10-19", blockCode: "B01", state: "PLANNED", preserved: false }] };
afterEach(() => vi.unstubAllGlobals());

function renderPage(member: Member | null, seed?: (client: QueryClient) => void) {
  const client = new QueryClient(); client.setQueryData(memberQueryKey, member); seed?.(client);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(RecurringAssignmentsPage)))); client.clear(); return markup;
}

describe("recurring assignment API and exact retry", () => {
  it("sends create/update/end with their supplied key and version, while preview never claims to acquire time", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta }) : Response.json({ data: path.endsWith("/preview") ? preview : assignment, meta }));
    vi.stubGlobal("fetch", fetchMock);
    await previewAssignment({ ...input, assignmentId: assignment.id, expectedVersion: 2 });
    await submitAssignmentIntent({ kind: "CREATE", input }, "fixture-create-key");
    await submitAssignmentIntent({ kind: "UPDATE", assignmentId: assignment.id, input: { expectedVersion: 2, weekday: 7, blockCode: "B10", effectiveDate: "2026-10-19" } }, "fixture-update-key");
    await submitAssignmentIntent({ kind: "END", assignmentId: assignment.id, input: { expectedVersion: 2, endDate: "2026-12-11" } }, "fixture-end-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).has("Idempotency-Key")).toBe(false);
    expect(fetchMock.mock.calls[5][0]).toBe(`/api/v1/recurring-assignments/${assignment.id}`);
    expect(fetchMock.mock.calls[5][1].method).toBe("PATCH");
    expect(JSON.parse(fetchMock.mock.calls[5][1].body)).toEqual({ expectedVersion: 2, weekday: 7, blockCode: "B10", effectiveDate: "2026-10-19" });
    expect(fetchMock.mock.calls[7][0]).toBe(`/api/v1/recurring-assignments/${assignment.id}/end`);
    for (const [index, key] of [[3, "fixture-create-key"], [5, "fixture-update-key"], [7, "fixture-end-key"]] as const) expect(new Headers(fetchMock.mock.calls[index][1].headers).get("Idempotency-Key")).toBe(key);
    expect(fetchMock).toHaveBeenCalledTimes(8);
  });
  it("preserves exact mutation input after an unknown outcome and retry preparation errors, without allowing a second intent", () => {
    const state = new IdempotentRequestState<AssignmentIntent>(() => "fixture-stable-key");
    const intent: AssignmentIntent = { kind: "UPDATE", assignmentId: assignment.id, input: { expectedVersion: 2, weekday: 2, blockCode: "B08", effectiveDate: "2026-10-19" } };
    state.prepare(intent); intent.input.blockCode = "B09";
    expect(state.failed(new TypeError("network disconnected"))).toBe(true);
    for (const [status, code] of [[0, "OFFLINE"], [403, "CSRF_INVALID"], [200, "INVALID_CSRF_RESPONSE"], [429, "TOO_MANY_REQUESTS"]] as const) expect(state.failed(new ApiError(status, code, ""))).toBe(true);
    expect(state.prepare({ kind: "END", assignmentId: assignment.id, input: { expectedVersion: 3, endDate: "2026-12-11" } })).toBeUndefined();
    expect(state.request).toEqual({ key: "fixture-stable-key", intent: { ...intent, input: { ...intent.input, blockCode: "B08" } } });
    expect(state.failed(new ApiError(409, "ASSIGNMENT_CONFLICT", ""))).toBe(false); expect(state.request).toBeNull();
  });
  it("reads server time for the local date and releases known first-attempt preparation failures", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({ data: [assignment], meta })));
    expect(await getAssignments()).toMatchObject({ assignments: [assignment], serverTime: meta.serverTime });
    const state = new IdempotentRequestState<AssignmentIntent>(() => "fixture-first-key");
    for (const [status, code] of [[0, "OFFLINE"], [403, "CSRF_INVALID"], [200, "INVALID_CSRF_RESPONSE"]] as const) { state.prepare({ kind: "CREATE", input }); expect(state.failed(new ApiError(status, code, ""))).toBe(false); expect(state.request).toBeNull(); }
  });
  it("requires reopening a known stale edit after reload while keeping uncertain requests intact", () => {
    const state = new IdempotentRequestState<AssignmentIntent>(() => "fixture-edit-key");
    const edit: AssignmentIntent = { kind: "UPDATE", assignmentId: assignment.id, input: { expectedVersion: 2, weekday: 3, blockCode: "B03", effectiveDate: "2026-11-01" } };
    state.prepare(edit);
    const versionConflict = new ApiError(409, "VERSION_CONFLICT", "");
    expect(state.failed(versionConflict)).toBe(false);
    expect(state.request).toBeNull();
    expect(staleAssignmentReloadNotice(versionConflict, state.uncertain)).toContain("변경 내용을 확인");
    expect(staleAssignmentReloadNotice(versionConflict, state.uncertain)).toContain("다시 열어 주세요");
    state.prepare(edit);
    const networkFailure = new TypeError("response disconnected");
    expect(state.failed(networkFailure)).toBe(true);
    expect(staleAssignmentReloadNotice(networkFailure, state.uncertain)).toBeNull();
    expect(staleAssignmentReloadNotice(versionConflict, true)).toBeNull();
    expect(state.request).toEqual({ key: "fixture-edit-key", intent: edit });
    expect(staleAssignmentReloadNotice(new ApiError(409, "ASSIGNMENT_CONFLICT", ""), false)).toBeNull();
  });
});

describe("preview and revision inputs", () => {
  it("accepts several kinds of active teams and future dates, rejecting inactive teams, invalid dates and noncanonical blocks", () => {
    expect(assignmentInputError(input, "2026-10-12", teams)).toBeNull();
    expect(assignmentInputError({ ...input, teamId: "32", effectiveDate: "2026-12-11" }, "2026-10-12", teams)).toBeNull();
    for (const candidate of [{ ...input, teamId: "33" }, { ...input, effectiveDate: "2026-10-11" }, { ...input, effectiveDate: "2026-02-30" }, { ...input, blockCode: "B99" as never }, { ...input, weekday: 0 as never }]) expect(assignmentInputError(candidate, "2026-10-12", teams)).toBeTypeOf("string");
  });
  it("invalidates a checked preview for every changed field, series identity or expected version", () => {
    const editing = { ...input, assignmentId: assignment.id, expectedVersion: 2 };
    const original = previewInputKey(editing);
    for (const candidate of [{ ...editing, teamId: "32" }, { ...editing, weekday: 2 as const }, { ...editing, blockCode: "B02" as const }, { ...editing, effectiveDate: "2026-10-19" }, { ...editing, assignmentId: "other" }, { ...editing, expectedVersion: 3 }]) expect(previewInputKey(candidate)).not.toBe(original);
    expect(revisionAt(assignment, "2026-10-12")?.id).toBe("51"); expect(revisionAt(assignment, "2026-11-02")?.id).toBe("52");
  });
  it("uses the latest revision of the nearest future effective date in the card and pre-start editor", () => {
    const scheduled: Assignment = { ...assignment, revisions: [
      { id: "51", revisionNo: 1, effectiveDate: "2026-11-01", weekday: 2, blockCode: "B02" },
      { id: "52", revisionNo: 2, effectiveDate: "2026-11-08", weekday: 5, blockCode: "B07" },
      { id: "53", revisionNo: 3, effectiveDate: "2026-11-01", weekday: 3, blockCode: "B03" },
    ] };
    const original = structuredClone(scheduled.revisions);
    expect(nextRevisionAfter(scheduled, "2026-10-12")?.id).toBe("53");
    expect(revisionAt(scheduled, "2026-10-12")?.id).toBe("53");
    expect(revisionAt(scheduled, "2026-11-01")?.id).toBe("53");
    expect(nextRevisionAfter(scheduled, "2026-11-01")?.id).toBe("52");
    const card = renderToStaticMarkup(createElement(AssignmentCard, { assignment: scheduled, today: "2026-10-12", disabled: false, onEdit: vi.fn(), onEnd: vi.fn() }));
    expect(card).toContain("다음 패턴: 11월 1일");
    expect(card).toContain("부터 매주 수요일 · 14:00~15:00");
    const editor = renderToStaticMarkup(createElement(AssignmentEditor, { assignment: scheduled, teams, today: "2026-10-12", disabled: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(editor).toContain('<option value="3" selected="">수요일</option>');
    expect(editor).toContain('<option value="B03" selected="">14:00~15:00 · 60분</option>');
    expect(scheduled.revisions).toEqual(original);
  });
  it("hides future patterns beyond the inclusive end date while keeping patterns on that date", () => {
    const ended: Assignment = { ...assignment, status: "ENDED", endDate: "2026-10-10", revisions: [
      { id: "51", revisionNo: 1, effectiveDate: "2026-10-11", weekday: 7, blockCode: "B01" },
    ] };
    expect(nextRevisionAfter(ended, "2026-10-10")).toBeUndefined();
    expect(revisionAt(ended, "2026-10-10")).toBeUndefined();
    const card = renderToStaticMarkup(createElement(AssignmentCard, { assignment: ended, today: "2026-10-10", disabled: false, onEdit: vi.fn(), onEnd: vi.fn() }));
    expect(card).not.toContain("다음 패턴:");
    expect(card).not.toContain("적용 시작 전이에요");
    expect(card).toContain("현재 적용 중인 패턴이 없어요");
    const inclusive = { ...ended, endDate: "2026-10-11" };
    expect(nextRevisionAfter(inclusive, "2026-10-10")?.id).toBe("51");
    expect(renderToStaticMarkup(createElement(AssignmentCard, { assignment: inclusive, today: "2026-10-10", disabled: false, onEdit: vi.fn(), onEnd: vi.fn() }))).toContain("다음 패턴: 10월 11일");
    expect(nextRevisionAfter({ ...ended, status: "ACTIVE", endDate: null }, "2026-10-10")?.id).toBe("51");
  });
  it("shows weekly-pattern and actual-reservation conflicts, preserved exceptions and final revalidation guidance", () => {
    const conflicts = [{ type: "PATTERN", assignmentId: "82", weekday: 1, blockCode: "B01", teamName: "가상 상대조" }, { type: "RESERVATION", reservationId: "83", date: "2026-10-19", weekday: 1, blockCode: "B01" }] as const;
    const error = new ApiError(409, "ASSIGNMENT_CONFLICT", "private echoed credential", undefined, { conflicts: [...conflicts, { type: "OTHER", weekday: 1, blockCode: "B01" }] });
    expect(assignmentConflicts(error)).toHaveLength(2); expect(assignmentErrorMessage(error)).not.toContain("credential");
    const markup = renderToStaticMarkup(createElement(PreviewView, { preview: { ...preview, canApply: false, conflicts: assignmentConflicts(error) } }));
    expect(markup).toContain("고정 배정 #82"); expect(markup).toContain("기존 예약 #83"); expect(markup).toContain("개별 취소 · 예외 유지"); expect(markup).toContain("기존 회차 보존"); expect(markup).toContain("시간을 확보하지 않아요"); expect(markup).toContain("기존 예약과 배정을 유지");
    expect(renderToStaticMarkup(createElement(ConflictList, { conflicts: [] }))).toBe("");
  });
});

describe("administrator views, scheduled endings and scoped cache", () => {
  it("never exposes recurring data or controls to anonymous, ordinary, inactive or team-leader-only accounts", () => {
    for (const current of [null, { ...admin, globalRole: "MEMBER" as const }, { ...admin, status: "INACTIVE" } as unknown as Member, { ...admin, globalRole: "MEMBER" as const, teamMemberships: [{ teamId: "31", teamName: "가상 정규조", kind: "REGULAR" as const, role: "LEADER" as const }] }]) {
      const markup = renderPage(current, (client) => client.setQueryData(assignmentsQuery(directoryScope(admin)).queryKey, { assignments: [assignment], serverTime: meta.serverTime, receivedAtMillis: 0 }));
      expect(markup).not.toContain("새 고정 배정</button>"); expect(markup).not.toContain(`고정 배정 #${assignment.id}`);
    }
  });
  it("renders admin navigation and both current and future revisions while preserving the footer", () => {
    const markup = renderPage(admin, (client) => { client.setQueryData(assignmentsQuery(directoryScope(admin)).queryKey, { assignments: [assignment], serverTime: meta.serverTime, receivedAtMillis: 0 }); client.setQueryData(teamsQuery(directoryScope(admin)).queryKey, teams); });
    expect(markup).toContain("새 고정 배정</button>"); expect(markup).toContain("현재 패턴: 매주 월요일"); expect(markup).toContain("다음 패턴: 11월 1일"); expect(markup).toContain("JEONGEUM · SENTIMENTAL MUSIC"); expect(markup).toContain("이 날짜의 회차를 취소");
  });
  it("labels an ENDED series with a future inclusive end as scheduled, hiding unsupported subsequent mutation actions", () => {
    const ending = { ...assignment, status: "ENDED" as const, endDate: "2026-12-11" };
    const markup = renderToStaticMarkup(createElement(AssignmentCard, { assignment: ending, today: "2026-10-12", disabled: false, onEdit: vi.fn(), onEnd: vi.fn() }));
    expect(markup).toContain("종료일 지정"); expect(markup).toContain("12월 11일"); expect(markup).toContain("까지 고정 배정을 유지"); expect(markup).not.toContain("패턴 수정</button>"); expect(markup).not.toContain("종료 지정</button>"); expect(markup).toContain("종료일 앞당기기</button>");
    const panel = renderToStaticMarkup(createElement(AssignmentEndPanel, { assignment, today: "2026-10-12", disabled: false, onEnd: vi.fn(), onClose: vi.fn() }));
    expect(panel).toContain("종료일 당일까지 배정을 유지"); expect(panel).toContain("미래 종료일까지 회차가 생성될 수 있어요"); expect(panel).toContain("개별 취소·변경·교환 예외와 진행·과거 회차는 보존");
    const shortenPanel = renderToStaticMarkup(createElement(AssignmentEndPanel, { assignment: ending, today: "2026-10-12", disabled: false, onEnd: vi.fn(), onClose: vi.fn() }));
    expect(shortenPanel).toContain('max="2026-12-11"'); expect(shortenPanel).toContain("뒤로 연장하지 않아요");
  });
  it("only offers active teams and immutable team identity on an edit", () => {
    const newMarkup = renderToStaticMarkup(createElement(AssignmentEditor, { assignment: null, teams, today: "2026-10-12", disabled: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(newMarkup).toContain("가상 정규조"); expect(newMarkup).toContain("가상 추가조"); expect(newMarkup).not.toContain("해체된 가상 팀"); expect(newMarkup).toContain('value="B10"');
    const editMarkup = renderToStaticMarkup(createElement(AssignmentEditor, { assignment, teams, today: "2026-10-12", disabled: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(editMarkup).toContain('<select id="assignment-team"'); expect(editMarkup.match(/<select id="assignment-team"[^>]+>/)?.[0]).toContain("disabled"); expect(editMarkup).toContain("개별 취소·변경·교환과 진행·과거 회차는 보존");
  });
  it("removes old administrator scopes after account or role change and clears them on logout", async () => {
    const client = new QueryClient(); const old = directoryScope(admin); const newAdmin = { ...admin, id: "other-admin" }; const next = directoryScope(newAdmin);
    const currentData = { assignments: [assignment], serverTime: meta.serverTime, receivedAtMillis: 1 };
    client.setQueryData(assignmentsQuery(old).queryKey, { ...currentData, receivedAtMillis: 0 }); client.setQueryData(assignmentsQuery(next).queryKey, currentData);
    await pruneDirectoryCache(client, newAdmin); expect(client.getQueryData(assignmentsQuery(old).queryKey)).toBeUndefined(); expect(client.getQueryData(assignmentsQuery(next).queryKey)).toEqual(currentData);
    await pruneDirectoryCache(client, { ...newAdmin, globalRole: "MEMBER" }); expect(client.getQueryData(assignmentsQuery(next).queryKey)).toBeUndefined();
    client.setQueryData(assignmentsQuery(next).queryKey, currentData); await replaceSessionCache(client, null); expect(client.getQueryData(assignmentsQuery(next).queryKey)).toBeUndefined(); client.clear();
  });
});

describe("fixed reservation visibility", () => {
  it("marks the team booking fixed and exempt, with no automatic restoration of a canceled weekly occurrence", () => {
    const reservation: ReservationDetail = { id: "71", version: 0, subjectType: "TEAM", purpose: "REGULAR", teamId: "31", teamName: "가상 정규조", instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "FUTURE", isMine: false, canCancel: true, canChange: true, ownerUserId: null, creatorUserId: admin.id, date: "2026-10-19", blockCode: "B01", startsAt: "2026-10-19T12:00:00+09:00", endsAt: "2026-10-19T13:00:00+09:00", usableFrom: "2026-10-19T12:00:00+09:00", quotaMinutes: 0, quotaExempt: true, originKind: "FIXED", teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false };
    const day: ScheduleDay = { date: reservation.date, openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [], blocks: [{ blockCode: "B01", startsAt: reservation.startsAt, endsAt: reservation.endsAt, durationMinutes: 60, phase: "FUTURE", remainingMinutes: 60, canReservePersonal: false, canReserveTeam: false, availableInstrumentCodes: [], faultyInstrumentCodes: [], teamWarnings: [], reservations: [reservation] }] };
    const markup = renderToStaticMarkup(createElement(MobileSchedule, { day, instruments: [], now: Date.parse(meta.serverTime), onSelect: vi.fn() }));
    expect(markup).toContain("관리자 고정 배정"); expect(markup).toContain("60분 한도 면제"); expect(markup).toContain("현재 예약 불가"); expect(quotaHistoryLabel(reservation)).toContain("관리자 원래 배정"); expect(cancellationNotice(reservation, Date.parse(meta.serverTime))).toContain("한도 면제");
  });
});
