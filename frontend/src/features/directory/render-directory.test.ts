import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import MembersPage from "@/app/admin/members/page";
import TeamsPage from "@/app/teams/page";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, membersQuery, rosterQuery, teamsQuery } from "./queries";
import { TeamDetailView } from "./team-detail";
import type { TeamDetail } from "./types";

const member: Member = { id: "21", loginId: "fixture.member", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "10", teamName: "가상 A조", kind: "REGULAR", role: "LEADER" }, { teamId: "11", teamName: "가상 B조", kind: "REGULAR", role: "MEMBER" }] };
const detail: TeamDetail = { team: { id: "10", name: "가상 A조", kind: "REGULAR", status: "ACTIVE", version: 1 }, members: [{ rosterEntryId: "31", name: "가상 팀원 정보", userId: "21", role: "LEADER" }], songs: [{ id: "41", teamId: "10", title: "<script>unsafe title</script>", artist: "가상 아티스트", memo: "<img onerror=unsafe>", sortOrder: 0, version: 1 }], canViewMembers: true, canManageTeam: false, canManageSongs: false };

function render(element: React.ReactNode, current: Member | null, seed?: (client: QueryClient) => void) {
  const client = new QueryClient(); client.setQueryData(memberQueryKey, current); seed?.(client);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, element)));
  client.clear(); return markup;
}

describe("directory views enforce current permissions and privacy", () => {
  it("hides management inputs and cached roster data from anonymous and ordinary members", () => {
    for (const current of [null, member]) {
      const markup = render(createElement(MembersPage), current, (client) => client.setQueryData(rosterQuery(member.id).queryKey, [{ id: "31", name: "관리자 캐시의 이름", userId: null, loginId: null, displayName: null, accountStatus: null, version: 0 }]));
      expect(markup).not.toContain('id="new-roster-name"');
      expect(markup).not.toContain("관리자 캐시의 이름");
    }
  });

  it("shows explicit roster IDs and only unlinked active account options on the admin linking form", () => {
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
    const markup = render(createElement(MembersPage), admin, (client) => {
      client.setQueryData(rosterQuery(admin.id).queryKey, [{ id: "31", name: "가상 동명이인", userId: null, loginId: null, displayName: null, accountStatus: null, version: 0 }, { id: "32", name: "가상 동명이인", userId: null, loginId: null, displayName: null, accountStatus: null, version: 0 }]);
      client.setQueryData(membersQuery(admin.id).queryKey, [{ id: "21", loginId: "fixture.available", displayName: "가상 계정", globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: null, version: 0 }, { id: "22", loginId: "fixture.linked", displayName: "연결된 가상 계정", globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: "33", version: 0 }, { id: "23", loginId: "fixture.inactive", displayName: "비활성 가상 계정", globalRole: "MEMBER", status: "INACTIVE", rosterEntryId: null, version: 0 }]);
    });
    expect(markup).toContain('id="new-roster-name"');
    expect(markup).toContain("명단 번호 #31"); expect(markup).toContain("명단 번호 #32");
    const select = markup.match(/<select id="link-account-31"[\s\S]*?<\/select>/)?.[0];
    expect(select).toContain("fixture.available"); expect(select).not.toContain("fixture.linked"); expect(select).not.toContain("fixture.inactive");
  });

  it("never renders team member names when canViewMembers is false, including an accidental nonempty payload", () => {
    const markup = render(createElement(TeamDetailView, { detail: { ...detail, canViewMembers: false } }), member);
    expect(markup).not.toContain("가상 팀원 정보");
    expect(markup).toContain("해당 팀 소속 회원과 전체 관리자만");
    expect(markup).not.toContain('id="edit-team-name"');
    expect(markup).not.toContain('id="new-song-title"');
  });

  it("offers only active linked accounts and unlinked roster entries when adding team members", () => {
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
    const markup = render(createElement(TeamDetailView, { detail: { ...detail, canManageTeam: true } }), admin, (client) => {
      client.setQueryData(rosterQuery(admin.id).queryKey, [
        { id: "31", name: "현재 팀원", userId: "21", loginId: "fixture.existing", displayName: "현재 팀원", accountStatus: "ACTIVE", version: 0 },
        { id: "32", name: "활성 가입 회원", userId: "22", loginId: "fixture.active", displayName: "활성 가입 회원", accountStatus: "ACTIVE", version: 0 },
        { id: "33", name: "가입 전 명단", userId: null, loginId: null, displayName: null, accountStatus: null, version: 0 },
        { id: "34", name: "탈퇴한 회원", userId: "24", loginId: "fixture.withdrawn", displayName: "탈퇴한 회원", accountStatus: "WITHDRAWN", version: 1 },
        { id: "35", name: "비활성 회원", userId: "25", loginId: "fixture.inactive", displayName: "비활성 회원", accountStatus: "INACTIVE", version: 1 },
      ]);
    });
    const select = markup.match(/<select id="add-team-roster"[\s\S]*?<\/select>/)?.[0];
    expect(select).toBeDefined();
    expect(select).toContain('<option value="32">활성 가입 회원');
    expect(select).toContain('<option value="33">가입 전 명단');
    expect(select).not.toContain('<option value="31">');
    expect(select).not.toContain("탈퇴한 회원");
    expect(select).not.toContain("비활성 회원");
    expect(markup).toContain("가상 팀원 정보");
  });

  it("does not transfer a leader's song-edit authority to a different team", () => {
    const own = render(createElement(TeamDetailView, { detail: { ...detail, canManageSongs: true } }), member);
    const other = render(createElement(TeamDetailView, { detail: { ...detail, team: { ...detail.team, id: "11", name: "가상 B조" } } }), member);
    expect(own).toContain('id="new-song-title"'); expect(own).not.toContain('id="edit-team-name"');
    expect(other).not.toContain('id="new-song-title"'); expect(other).not.toContain("곡 수정</button>");
    expect(own).toContain("&lt;script&gt;unsafe title&lt;/script&gt;"); expect(own).not.toContain("<script>unsafe title");
    expect(own).toContain("&lt;img onerror=unsafe&gt;");
  });

  it("explains that a disbanded team's preserved songs are read-only, including to administrators", () => {
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
    const markup = render(createElement(TeamDetailView, { detail: { ...detail, team: { ...detail.team, status: "DISBANDED" }, canManageSongs: true } }), admin);
    expect(markup).toContain("해체된 팀의 곡 목록은 조회만 할 수 있어요.");
    expect(markup).not.toContain("곡 등록·수정·삭제는 전체 관리자와");
    expect(markup).not.toContain('id="new-song-title"');
    expect(markup).not.toContain("곡 수정</button>");
    expect(markup).not.toContain("곡 삭제</button>");
    expect(markup).toContain("&lt;script&gt;unsafe title&lt;/script&gt;");
  });

  it("renders multiple regular memberships separately without administrator creation controls", () => {
    const markup = render(createElement(TeamsPage), member, (client) => client.setQueryData(teamsQuery(directoryScope(member)).queryKey, [{ id: "10", name: "가상 A조", kind: "REGULAR", status: "ACTIVE", version: 0 }, { id: "11", name: "가상 B조", kind: "REGULAR", status: "ACTIVE", version: 0 }]));
    expect(markup).toContain('href="/teams/10"'); expect(markup).toContain('href="/teams/11"');
    expect(markup).toContain("내 팀 · 팀장"); expect(markup).not.toContain('id="new-team-name"');
  });
});
