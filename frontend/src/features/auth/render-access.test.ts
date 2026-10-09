import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import InvitationsPage from "@/app/admin/invitations/page";
import { SessionProvider } from "./session-provider";
import { memberQueryKey } from "./session-cache";
import type { Member } from "./types";

const member: Member = { id: "member-test", loginId: "member-test", displayName: "테스트 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [] };

function render(member: Member | null) {
  const client = new QueryClient();
  client.setQueryData(memberQueryKey, member);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(InvitationsPage))));
  client.clear();
  return markup;
}

describe("administrator invitation route", () => {
  it("does not render invitation management fields for anonymous, ordinary, or inactive accounts", () => {
    const anonymous = render(null); const ordinary = render(member);
    const inactive = render({ ...member, globalRole: "GLOBAL_ADMIN", status: "INACTIVE" } as unknown as Member);
    for (const markup of [anonymous, ordinary, inactive]) expect(markup).not.toContain('id="recipient-name"');
    expect(anonymous).toContain("로그인이 필요해요");
    expect(ordinary).toContain("관리자만 이용할 수 있어요");
    expect(inactive).toContain("로그인이 필요해요");
  });

  it("renders the actual invitation form only for an active global administrator", () => {
    expect(render({ ...member, globalRole: "GLOBAL_ADMIN" })).toContain('id="recipient-name"');
  });
});
