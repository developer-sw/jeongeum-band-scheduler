import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import InvitationsPage from "@/app/admin/invitations/page";
import LoginPage from "@/app/login/page";
import { SessionProvider } from "./session-provider";
import { memberQueryKey } from "./session-cache";
import { logoutPushCleanupMessage } from "./logout";
import type { Member } from "./types";

const member: Member = { id: "member-test", loginId: "member-test", displayName: "테스트 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [] };
const navigation = vi.hoisted(() => ({ query: "" }));
vi.mock("next/navigation", () => ({ usePathname: () => "/login", useRouter: () => ({ replace: vi.fn() }), useSearchParams: () => new URLSearchParams(navigation.query) }));
afterEach(() => { navigation.query = ""; });

function render(member: Member | null, Page = InvitationsPage) {
  const client = new QueryClient();
  client.setQueryData(memberQueryKey, member);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(Page))));
  client.clear();
  return markup;
}

describe("administrator invitation route", () => {
  it("does not render invitation management fields for anonymous, ordinary, or inactive accounts", () => {
    const anonymous = render(null); const ordinary = render(member);
    const inactive = render({ ...member, globalRole: "GLOBAL_ADMIN", status: "INACTIVE" } as unknown as Member);
    for (const markup of [anonymous, ordinary, inactive]) expect(markup).not.toContain('id="invitation-roster"');
    expect(anonymous).toContain("로그인이 필요해요");
    expect(ordinary).toContain("관리자만 이용할 수 있어요");
    expect(inactive).toContain("로그인이 필요해요");
  });

  it("renders the actual invitation form only for an active global administrator", () => {
    expect(render({ ...member, globalRole: "GLOBAL_ADMIN" })).toContain('id="invitation-roster"');
  });
});

describe("logout with incomplete local Push cleanup", () => {
  it("shows a clear alert on the login page after confirmed server logout with incomplete browser cleanup", () => {
    navigation.query = "pushCleanup=incomplete";
    const html = render(null, LoginPage);
    expect(html).toContain(logoutPushCleanupMessage);
    expect(html).toContain('role="alert"');
    expect(html).toContain('id="login-id"');
  });
  it("does not claim a cleanup failure for normal login or echo arbitrary URL values", () => {
    expect(render(null, LoginPage)).not.toContain(logoutPushCleanupMessage);
    navigation.query = "joined=1&pushCleanup=untrusted-private-endpoint";
    const html = render(null, LoginPage);
    expect(html).toContain("가입을 마쳤어요.");
    expect(html).not.toContain(logoutPushCleanupMessage);
    expect(html).not.toContain("untrusted-private-endpoint");
  });
});
