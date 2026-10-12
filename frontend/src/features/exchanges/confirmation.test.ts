import { isValidElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Member } from "@/features/auth/types";
import type { ReservationDetail } from "@/features/reservations/types";
import type { ExchangeResponse } from "./types";
import { ExchangeDetail } from "./detail";

const controlled = vi.hoisted(() => ({ confirmation: { kind: "APPROVE", version: 0 } as { kind: "APPROVE"; version: number } | null, set: vi.fn() }));
vi.mock("react", async (original) => ({ ...await original<typeof import("react")>(), useState: () => [controlled.confirmation, controlled.set] }));

const date = "2026-10-12"; const now = Date.parse(`${date}T11:00:00+09:00`);
const member: Member = { id: "502", loginId: "synthetic.confirmation", displayName: "가상 승인 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [] };
const source: ReservationDetail = { id: "601", version: 0, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: false, canCancel: false, canChange: false, ownerUserId: "501", creatorUserId: "501", date, blockCode: "B01", startsAt: `${date}T12:00:00+09:00`, endsAt: `${date}T13:00:00+09:00`, usableFrom: `${date}T12:00:00+09:00`, quotaMinutes: 60, teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false };
const request: ExchangeResponse = { id: "901", version: 0, status: "PENDING", requesterUserId: "501", expiresAt: source.startsAt, createdAt: `${date}T11:00:00+09:00`, processedAt: null, processedByUserId: null, stateReason: null, serverTime: `${date}T11:00:00+09:00`, offered: [source], requested: [{ ...source, id: "602", ownerUserId: member.id, creatorUserId: member.id }], preview: [], allocationReservationIds: [], canAllocateReservationIds: [], canApprove: true, canReject: true, canCancel: false, replacedReservationIds: [], reservations: [] };
function elements(node: ReactNode): { type: unknown; props: { children?: ReactNode; onClick?: () => void } }[] {
  if (Array.isArray(node)) return node.flatMap(elements);
  if (!isValidElement<{ children?: ReactNode; onClick?: () => void }>(node)) return [];
  return [node, ...elements(node.props.children)];
}
beforeEach(() => { controlled.confirmation = { kind: "APPROVE", version: 0 }; controlled.set.mockClear(); });

describe("final exchange confirmation state transitions", () => {
  it("clears the reviewed confirmation before sending the versioned action, avoiding a false stale warning on own success", () => {
    const action = vi.fn(); const tree = ExchangeDetail({ request, member, now, disabled: false, onAction: action });
    const button = elements(tree).find((node) => node.type === "button" && Array.isArray(node.props.children) && node.props.children.join("") === "교환 승인 확정");
    expect(button).toBeDefined(); button!.props.onClick!();
    expect(controlled.set).toHaveBeenCalledWith(null);
    expect(action).toHaveBeenCalledWith({ kind: "APPROVE", id: request.id, input: { expectedVersion: 0 } });
    expect(controlled.set.mock.invocationCallOrder[0]).toBeLessThan(action.mock.invocationCallOrder[0]);
  });
  it("retains a real changed-version review warning while pending, but never labels terminal success as stale", () => {
    const render = (value: ExchangeResponse, clock = now) => renderToStaticMarkup(ExchangeDetail({ request: value, member, now: clock, disabled: false, onAction: vi.fn() }));
    expect(render({ ...request, version: 1 })).toContain("요청의 배분·버전·상태가 바뀌었어요");
    expect(render({ ...request, version: 1 })).not.toContain("교환 승인 확정");
    expect(render({ ...request, version: 1, status: "COMPLETED" })).toContain("양측 교환이 모두 완료됐어요");
    for (const status of ["COMPLETED", "REJECTED", "CANCELED", "EXPIRED", "INVALIDATED"] as const) expect(render({ ...request, version: 1, status })).not.toContain("요청의 배분·버전·상태가 바뀌었어요");
    expect(render(request, Date.parse(request.expiresAt))).not.toContain("요청의 배분·버전·상태가 바뀌었어요");
  });
});
