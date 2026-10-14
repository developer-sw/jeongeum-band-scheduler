import { createElement, type ComponentType } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import Home from "@/app/page";
import AccountPage from "@/app/account/page";
import MorePage from "@/app/more/page";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey, replaceSessionCache } from "@/features/auth/session-cache";
import type { Member, TeamMembership } from "@/features/auth/types";
import { directoryScope, teamsQuery } from "@/features/directory/queries";
import { myReservationsQuery, scheduleQuery } from "@/features/reservations/queries";
import { addDate } from "@/features/reservations/time";
import type { BlockCode, InstrumentCode, ReservationDetail, ScheduleSnapshot } from "@/features/reservations/types";

const navigation = vi.hoisted(() => ({ pathname: "/" }));
vi.mock("next/navigation", () => ({
  usePathname: () => navigation.pathname,
  useRouter: () => ({ replace: vi.fn() }),
  useSearchParams: () => new URLSearchParams(),
}));

const regular: TeamMembership = { teamId: "71", teamName: "가상 정규 공연조", kind: "REGULAR", role: "MEMBER" };
const additional: TeamMembership = { teamId: "72", teamName: "가상 추가 공연조", kind: "ADDITIONAL", role: "LEADER" };
const member: Member = { id: "501", loginId: "synthetic.diary.member", displayName: "가상 다이어리 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [regular, additional] };
const today = "2026-10-12";
const instrumentCodes: InstrumentCode[] = ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"];
const snapshot: ScheduleSnapshot = {
  today, bookableThrough: addDate(today, 14), serverTime: `${today}T11:00:00+09:00`, timeZone: "Asia/Seoul", receivedAtMillis: 0,
  instruments: instrumentCodes.map((code) => ({ code, name: code, status: "NORMAL", version: 0 })),
  days: Array.from({ length: 15 }, (_, index) => {
    const date = addDate(today, index);
    return {
      date, openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [],
      blocks: Array.from({ length: 10 }, (_, blockIndex) => {
        const durationMinutes = blockIndex < 6 ? 60 : 90;
        const startsAt = new Date(Date.parse(`${date}T12:00:00+09:00`) + (blockIndex < 6 ? blockIndex * 60 : 360 + (blockIndex - 6) * 90) * 60_000).toISOString();
        return {
          blockCode: `B${String(blockIndex + 1).padStart(2, "0")}` as BlockCode,
          startsAt, endsAt: new Date(Date.parse(startsAt) + durationMinutes * 60_000).toISOString(), durationMinutes: durationMinutes as 60 | 90,
          phase: "FUTURE", remainingMinutes: durationMinutes, canReservePersonal: true, canReserveTeam: true,
          availableInstrumentCodes: instrumentCodes, faultyInstrumentCodes: [], teamWarnings: [], reservations: [],
        };
      }),
    };
  }),
};

function booking(team: TeamMembership, id: string): ReservationDetail {
  return {
    id, version: 0, subjectType: "TEAM", purpose: team.kind, teamId: team.teamId, teamName: team.teamName,
    instrumentCodes, status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: true, canCancel: true, canChange: true,
    ownerUserId: null, creatorUserId: member.id, date: today, blockCode: "B01", startsAt: `${today}T12:00:00+09:00`, endsAt: `${today}T13:00:00+09:00`, usableFrom: `${today}T12:00:00+09:00`,
    quotaMinutes: 60, teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false,
  };
}

const clients = new Set<QueryClient>();
afterEach(() => { clients.forEach((client) => client.clear()); clients.clear(); navigation.pathname = "/"; });

function seededClient(current: Member | null, reservations: ReservationDetail[] = []) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  clients.add(client);
  client.setQueryData(memberQueryKey, current);
  if (current) {
    const scope = directoryScope(current);
    client.setQueryData(scheduleQuery(scope).queryKey, snapshot);
    client.setQueryData(myReservationsQuery(scope, today, snapshot.bookableThrough).queryKey, reservations);
  }
  return client;
}

function render(client: QueryClient, page: ComponentType, pathname = "/") {
  navigation.pathname = pathname;
  return renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(page))));
}

function highlightedOptions(markup: string) {
  return markup.match(/<select id="diary-highlight-team"[^>]*>[\s\S]*?<\/select>/)?.[0] ?? "";
}

describe("diary routes preserve session, membership and role boundaries", () => {
  it("renders the public welcome for anonymous/inactive accounts and protects account/more content", () => {
    for (const current of [null, { ...member, status: "INACTIVE" } as unknown as Member]) {
      const client = seededClient(current, [booking(regular, "901")]);
      const welcome = render(client, Home);
      expect(welcome).toContain("함께 연주할");
      expect(welcome).toContain('href="/login"');
      expect(welcome).toContain('href="/join"');
      expect(welcome).not.toContain('id="diary-highlight-team"');
      expect(welcome).not.toContain(regular.teamName);
      expect(welcome).not.toContain(member.displayName);
      expect(welcome).not.toContain(member.loginId);
      for (const page of [AccountPage, MorePage]) {
        const markup = render(client, page);
        expect(markup).toContain("로그인이 필요해요");
        expect(markup).not.toContain(member.loginId);
        expect(markup).not.toContain('href="/admin/recurring-assignments"');
      }
    }
  });

  it("keeps the real timetable and personal booking entry available with zero memberships", () => {
    const client = seededClient({ ...member, teamMemberships: [] });
    const markup = render(client, Home);
    expect(markup).toContain("합주실 시간표");
    expect(markup).toContain("하루 10개 블록");
    expect(markup).toContain("개인 연습은 바로 예약할 수 있어요");
    expect(highlightedOptions(markup)).toContain('disabled=""');
    expect(highlightedOptions(markup)).not.toContain('value="71"');
    expect(markup).toContain('href="/reservations?date=2026-10-12"');
  });

  it("limits a single-member highlight and upcoming summary to that current team", () => {
    const single = { ...member, teamMemberships: [regular] };
    const markup = render(seededClient(single, [booking(regular, "901"), booking(additional, "902")]), Home);
    expect(highlightedOptions(markup)).toContain('value="71"');
    expect(highlightedOptions(markup)).not.toContain('value="72"');
    expect(markup).toContain(regular.teamName);
    expect(markup).not.toContain(additional.teamName);
    expect(markup).toContain('href="/my-schedule"');
  });

  it("supports all regular/additional memberships while keeping highlight separate from booking subject", () => {
    const client = seededClient(member, [booking(regular, "901"), booking(additional, "902")]);
    const markup = render(client, Home);
    expect(highlightedOptions(markup)).toContain(`${regular.teamName} · 정규조`);
    expect(highlightedOptions(markup)).toContain(`${additional.teamName} · 추가조`);
    expect(markup).toContain("예약 주체는 별도로 선택해요");
    expect(markup).toContain("시간을 먼저 선택해 주세요");
    const account = render(client, AccountPage, "/account");
    expect(account).toContain('href="/teams/71"');
    expect(account).toContain('href="/teams/72"');
  });

  it("exposes team-leader song management only for led teams and global management only for admins", () => {
    const ordinaryClient = seededClient(member);
    const ordinary = render(ordinaryClient, MorePage, "/more");
    expect(ordinary).toContain("내가 이끄는 공연조");
    expect(ordinary).toContain('href="/teams/72"');
    expect(ordinary).not.toContain('href="/teams/71"');
    for (const path of ["/admin/instrument-faults", "/admin/recurring-assignments", "/admin/members", "/admin/invitations"]) expect(ordinary).not.toContain(`href="${path}"`);
    expect(render(ordinaryClient, Home)).toContain("역할: 회원");
    expect(render(ordinaryClient, Home)).not.toContain("역할: 전체 관리자");
    const adminClient = seededClient({ ...member, globalRole: "GLOBAL_ADMIN", teamMemberships: [] });
    const admin = render(adminClient, MorePage, "/more");
    for (const path of ["/admin/instrument-faults", "/admin/recurring-assignments", "/admin/members", "/admin/invitations"]) expect(admin).toContain(`href="${path}"`);
    expect(admin).not.toContain("내가 이끄는 공연조");
    expect(render(adminClient, Home)).toContain("역할: 전체 관리자");
  });

  it("keeps unimplemented functions explicitly disabled without fake routes or notification counts", () => {
    const ordinary = render(seededClient(member), MorePage, "/more");
    const admin = render(seededClient({ ...member, globalRole: "GLOBAL_ADMIN" }), MorePage, "/more");
    for (const [markup, count] of [[ordinary, 1], [admin, 1]] as const) {
      const buttons = markup.match(/<button[^>]*class="planned-feature"[^>]*>[\s\S]*?<\/button>/g) ?? [];
      expect(buttons).toHaveLength(count);
      for (const button of buttons) { expect(button).toContain('disabled=""'); expect(button).toContain("준비 중"); }
      for (const route of ["/announcements", "/pwa"]) expect(markup).not.toContain(`href="${route}"`);
      for (const route of ["/notices", "/notifications", "/notifications/settings"]) expect(markup).toContain(`href="${route}"`);
      expect(markup).toContain('href="/exchanges"');
    }
    expect(ordinary).not.toContain("악기 고장 등록·복구");
    expect(admin).toContain('href="/admin/instrument-faults"');
    expect(ordinary).toContain("예약 조회·변경·취소·이력");
    expect(ordinary).toContain("예약 교환");
    expect(ordinary).not.toContain("예약 변경·교환");
  });

  it("preserves account identification, logout access, navigation and the user-edited brand footer", () => {
    const client = seededClient(member);
    expect(render(client, Home)).toContain(member.loginId);
    const account = render(client, AccountPage, "/account");
    expect(account).toContain(member.displayName);
    expect(account).toContain(member.loginId);
    expect(account).toContain(">로그아웃</button>");
    expect(account).toContain("공용 기기를 사용했다면 이용 후 로그아웃해 주세요");
    for (const page of [Home, AccountPage, MorePage]) {
      const markup = render(client, page);
      expect(markup).toContain("JEONGEUM · SENTIMENTAL MUSIC");
      expect(markup).toContain('aria-label="모바일 주요 메뉴"');
      for (const path of ["/", "/my-schedule", "/teams", "/more", "/account", "/reservations"]) expect(markup).toContain(`href="${path}"`);
    }
  });

  it("clears old scoped data on account replacement and never renders the previous member's summary", async () => {
    const client = seededClient(member, [booking(regular, "901")]);
    const oldScope = directoryScope(member);
    const oldScheduleKey = scheduleQuery(oldScope).queryKey;
    const oldMineKey = myReservationsQuery(oldScope, today, snapshot.bookableThrough).queryKey;
    const oldTeamsKey = teamsQuery(oldScope).queryKey;
    client.setQueryData(oldTeamsKey, [{ id: regular.teamId, name: regular.teamName, kind: regular.kind, status: "ACTIVE", version: 0 }]);
    client.setQueryData(["admin", "roster", member.id], [{ id: "private-old-roster" }]);
    const replacement = { ...member, id: "502", loginId: "synthetic.other", displayName: "다른 가상 회원", teamMemberships: [] };
    await replaceSessionCache(client, replacement);
    for (const key of [oldScheduleKey, oldMineKey, oldTeamsKey, ["admin", "roster", member.id]]) expect(client.getQueryData(key)).toBeUndefined();
    client.setQueryData(scheduleQuery(directoryScope(replacement)).queryKey, snapshot);
    const markup = render(client, Home);
    expect(markup).toContain(replacement.displayName);
    expect(markup).toContain(replacement.loginId);
    expect(markup).not.toContain(member.displayName);
    expect(markup).not.toContain(regular.teamName);
    expect(markup).not.toContain(member.loginId);
    await replaceSessionCache(client, null);
    const welcome = render(client, Home);
    expect(welcome).toContain("함께 연주할");
    for (const value of [member.displayName, member.loginId, replacement.displayName, replacement.loginId, regular.teamName]) expect(welcome).not.toContain(value);
    expect(render(client, AccountPage, "/account")).toContain("로그인이 필요해요");
  });
});
