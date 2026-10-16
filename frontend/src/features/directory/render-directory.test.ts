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
import type { AdminMember, RosterEntry, TeamDetail } from "./types";

const member: Member = { id: "21", loginId: "fixture.member", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "10", teamName: "가상 A조", kind: "REGULAR", role: "LEADER" }, { teamId: "11", teamName: "가상 B조", kind: "REGULAR", role: "MEMBER" }] };
const detail: TeamDetail = { team: { id: "10", name: "가상 A조", kind: "REGULAR", status: "ACTIVE", version: 1 }, members: [{ rosterEntryId: "31", name: "가상 팀원 정보", userId: "21", role: "LEADER" }], songs: [{ id: "41", teamId: "10", title: "<script>unsafe title</script>", artist: "가상 아티스트", memo: "<img onerror=unsafe>", sortOrder: 0, version: 1 }], canViewMembers: true, canManageTeam: false, canManageSongs: false };

function render(element: React.ReactNode, current: Member | null, seed?: (client: QueryClient) => void) {
  const client = new QueryClient(); client.setQueryData(memberQueryKey, current); seed?.(client);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, element)));
  client.clear(); return markup;
}

function rosterEntry(id: string, patch: Partial<RosterEntry> = {}): RosterEntry {
  return { id, name: `합성 명단 ${id}`, userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0, ...patch };
}
function memberAccount(id: string, patch: Partial<AdminMember> = {}): AdminMember {
  return { id, loginId: `fixture.role.${id}`, displayName: `합성 계정 ${id}`, globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: null, version: 0, ...patch };
}
function renderMembers(roster: RosterEntry[], accounts: AdminMember[]) {
  const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
  return render(createElement(MembersPage), admin, (client) => {
    client.setQueryData(rosterQuery(admin.id).queryKey, roster);
    client.setQueryData(membersQuery(admin.id).queryKey, accounts);
  });
}
function roleSection(markup: string, role: "admins" | "leaders" | "members") {
  const section = markup.match(new RegExp(`<section id="directory-${role}"[\\s\\S]*?<\\/section>`))?.[0];
  expect(section).toBeDefined();
  return section!;
}

describe("directory views enforce current permissions and privacy", () => {
  it("hides management inputs and cached roster data from anonymous and ordinary members", () => {
    for (const current of [null, member]) {
      const markup = render(createElement(MembersPage), current, (client) => client.setQueryData(rosterQuery(member.id).queryKey, [{ id: "31", name: "관리자 캐시의 이름", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 }]));
      expect(markup).not.toContain('id="new-roster-name"');
      expect(markup).not.toContain("관리자 캐시의 이름");
    }
  });

  it("shows explicit roster IDs and only unlinked active account options on the admin linking form", () => {
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
    const markup = render(createElement(MembersPage), admin, (client) => {
      client.setQueryData(rosterQuery(admin.id).queryKey, [{ id: "31", name: "가상 동명이인", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 }, { id: "32", name: "가상 동명이인", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 }]);
      client.setQueryData(membersQuery(admin.id).queryKey, [{ id: "21", loginId: "fixture.available", displayName: "가상 계정", globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: null, version: 0 }, { id: "22", loginId: "fixture.linked", displayName: "연결된 가상 계정", globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: "33", version: 0 }, { id: "23", loginId: "fixture.inactive", displayName: "비활성 가상 계정", globalRole: "MEMBER", status: "INACTIVE", rosterEntryId: null, version: 0 }]);
    });
    expect(markup).toContain('id="new-roster-name"');
    expect(markup).toContain("명단 번호 #31"); expect(markup).toContain("명단 번호 #32");
    const select = markup.match(/<select id="link-account-31"[\s\S]*?<\/select>/)?.[0];
    expect(select).toContain("fixture.available"); expect(select).not.toContain("fixture.linked"); expect(select).not.toContain("fixture.inactive");
  });

  it("renders three ordered role sections with each linked or unregistered person in exactly one card", () => {
    const leaderTeams = [{ id: "10", name: "합성 정규 팀", kind: "REGULAR" as const }, { id: "11", name: "합성 추가 팀", kind: "ADDITIONAL" as const }];
    const rows = [
      rosterEntry("33", { name: "합성 일반 다", userId: "23", accountStatus: "ACTIVE" }),
      rosterEntry("32", { name: "합성 팀장 나", userId: "22", accountStatus: "ACTIVE", leaderTeams }),
      rosterEntry("31", { name: "합성 겸임 가", userId: "21", accountStatus: "ACTIVE", leaderTeams }),
      rosterEntry("34", { name: "합성 미가입 라", leaderTeams }),
      rosterEntry("35", { name: "합성 미가입 마", signupGlobalRole: "GLOBAL_ADMIN" }),
    ];
    const accounts = [memberAccount("21", { globalRole: "GLOBAL_ADMIN", rosterEntryId: "31" }), memberAccount("22", { rosterEntryId: "32" }), memberAccount("23", { rosterEntryId: "33" }), memberAccount("24", { displayName: "합성 명단없는 바" })];
    const markup = renderMembers(rows, accounts);
    const admins = roleSection(markup, "admins"), leaders = roleSection(markup, "leaders"), members = roleSection(markup, "members");
    expect(markup.indexOf('id="directory-admins"')).toBeLessThan(markup.indexOf('id="directory-leaders"'));
    expect(markup.indexOf('id="directory-leaders"')).toBeLessThan(markup.indexOf('id="directory-members"'));
    for (const section of [admins, leaders, members]) expect(section.match(/<h3\b/g)).toHaveLength(2);
    expect(admins).toContain("합성 겸임 가"); expect(admins).toContain("합성 미가입 마"); expect(admins).toContain("겸임 팀장");
    expect(leaders).toContain("합성 팀장 나"); expect(leaders).toContain("합성 미가입 라"); expect(leaders).not.toContain("합성 겸임 가");
    expect(members).toContain("합성 일반 다"); expect(members).toContain("합성 명단없는 바");
    expect(markup.match(/<h3\b/g)).toHaveLength(6);
    expect(markup).not.toContain('id="accounts-title"'); expect(markup).not.toContain('id="roster-title"');
    const leaderCard = leaders.match(/<li\b[\s\S]*?<\/li>/)?.[0];
    expect(leaderCard).toBeDefined();
    expect(leaderCard!.indexOf("전체 관리자 지정</button>")).toBeLessThan(leaderCard!.indexOf("명단 이름 수정</button>"));
    expect(leaderCard!.indexOf("전체 관리자 지정</button>")).toBeLessThan(leaderCard!.indexOf("탈퇴 처리</button>"));
  });

  it("offers a signup administrator assignment before name editing and explains invitation-only application", () => {
    const markup = renderMembers([rosterEntry("31", { signupGlobalRole: "GLOBAL_ADMIN" }), rosterEntry("32")], []);
    const planned = roleSection(markup, "admins"), ordinary = roleSection(markup, "members");
    expect(planned).toContain("이 명단의 개인 초대로 가입하면 전체 관리자 권한이 적용돼요.");
    expect(planned.match(/<button[^>]*>전체 관리자 해제<\/button>/)?.[0]).not.toContain('disabled=""');
    expect(ordinary.match(/<button[^>]*>전체 관리자 지정<\/button>/)?.[0]).not.toContain('disabled=""');
    expect(ordinary.indexOf("전체 관리자 지정</button>")).toBeLessThan(ordinary.indexOf("명단 이름 수정</button>"));
    expect(planned).not.toContain("탈퇴 처리</button>");
  });

  it("does not count planned or inactive administrators as another active administrator", () => {
    const markup = renderMembers([rosterEntry("31", { signupGlobalRole: "GLOBAL_ADMIN" })], [
      memberAccount("21", { globalRole: "GLOBAL_ADMIN" }), memberAccount("22", { globalRole: "GLOBAL_ADMIN", status: "INACTIVE" }),
    ]);
    const admins = roleSection(markup, "admins");
    expect(admins).toContain("마지막 활성 전체 관리자의 권한은 해제할 수 없어요.");
    expect(admins.match(/<button[^>]*>전체 관리자 해제<\/button>/g)?.filter((button) => button.includes('disabled=""'))).toHaveLength(2);
    expect(admins.match(/<button[^>]*>전체 관리자 해제<\/button>/g)?.filter((button) => !button.includes('disabled=""'))).toHaveLength(1);
    expect(admins).toContain("비활성");
  });

  it.each(["INACTIVE", "WITHDRAWN"])("renders %s accounts once in the ordinary section without role assignment controls enabled", (status) => {
    const markup = renderMembers([rosterEntry("31", { userId: "22", accountStatus: status, leaderTeams: [{ id: "10", name: "합성 이전 팀", kind: "REGULAR" }] })], [memberAccount("22", { rosterEntryId: "31", status })]);
    expect(roleSection(markup, "leaders")).not.toContain('<h3');
    const ordinary = roleSection(markup, "members");
    expect(ordinary.match(/<h3\b/g)).toHaveLength(1);
    expect(ordinary.match(/<button[^>]*>전체 관리자 지정<\/button>/)?.[0]).toContain('disabled=""');
    expect(ordinary).toContain(status === "WITHDRAWN" ? "탈퇴" : "비활성");
  });

  it("uses the current account role for an explicit reverse link and hides the stale roster's link form", () => {
    const markup = renderMembers([rosterEntry("31", { signupGlobalRole: "GLOBAL_ADMIN" })], [memberAccount("22", { rosterEntryId: "31" })]);
    expect(roleSection(markup, "admins")).not.toContain('<h3');
    const ordinary = roleSection(markup, "members");
    expect(ordinary.match(/<h3\b/g)).toHaveLength(1);
    expect(ordinary).toContain("전체 관리자 지정</button>"); expect(ordinary).toContain("fixture.role.22");
    expect(ordinary).not.toContain('id="link-account-31"');
    expect(ordinary).not.toContain("이 명단의 개인 초대로 가입하면 전체 관리자 권한이 적용돼요.");
  });

  it("shows explicit empty states and hides cached role cards after a list error", () => {
    const empty = renderMembers([], []);
    expect(empty).toContain("전체 관리자로 지정된 사람이 없어요.");
    expect(empty).toContain("전체 관리자를 제외한 팀장이 없어요."); expect(empty).toContain("이 영역에 표시할 구성원이 없어요.");
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" } as Member;
    const error = render(createElement(MembersPage), admin, (client) => {
      const key = rosterQuery(admin.id).queryKey;
      client.setQueryData(key, [rosterEntry("31", { name: "합성 오류시 숨길 명단", signupGlobalRole: "GLOBAL_ADMIN" })]);
      client.setQueryData(membersQuery(admin.id).queryKey, []);
      client.getQueryCache().find({ queryKey: key })!.setState({ status: "error", error: new Error("Synthetic directory read failure") });
    });
    expect(error).not.toContain("합성 오류시 숨길 명단"); expect(error).not.toContain('id="directory-admins"');
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
        { id: "31", name: "현재 팀원", userId: "21", loginId: "fixture.existing", displayName: "현재 팀원", accountStatus: "ACTIVE", signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 },
        { id: "32", name: "활성 가입 회원", userId: "22", loginId: "fixture.active", displayName: "활성 가입 회원", accountStatus: "ACTIVE", signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 },
        { id: "33", name: "가입 전 명단", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0 },
        { id: "34", name: "탈퇴한 회원", userId: "24", loginId: "fixture.withdrawn", displayName: "탈퇴한 회원", accountStatus: "WITHDRAWN", signupGlobalRole: "MEMBER", leaderTeams: [], version: 1 },
        { id: "35", name: "비활성 회원", userId: "25", loginId: "fixture.inactive", displayName: "비활성 회원", accountStatus: "INACTIVE", signupGlobalRole: "MEMBER", leaderTeams: [], version: 1 },
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
