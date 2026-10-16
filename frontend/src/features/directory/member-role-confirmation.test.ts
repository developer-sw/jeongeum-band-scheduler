import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { MemberRoleConfirmation, type RoleTarget } from "./member-role-confirmation";

const signup: RoleTarget = { kind: "SIGNUP", entry: { id: "31", name: "합성 미가입자", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 2 } };
const account: RoleTarget = { kind: "ACCOUNT", account: { id: "41", loginId: "fixture.existing", displayName: "합성 가입자", globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: "32", version: 3 } };
function render(target: RoleTarget, options: { pending?: boolean; stale?: boolean; error?: string } = {}) {
  return renderToStaticMarkup(createElement(MemberRoleConfirmation, { target, pending: options.pending ?? false, stale: options.stale ?? false, error: options.error ?? null, onConfirm: () => {}, onCancel: () => {}, onRefresh: () => {} }));
}
const button = (markup: string, label: string) => markup.match(new RegExp(`<button[^>]*>${label}<\\/button>`))?.[0];

describe("inline administrator confirmation", () => {
  it("identifies the exact pre-signup roster and explains when the role takes effect", () => {
    const markup = render(signup);
    expect(markup).toContain('role="group" aria-label="전체 관리자 변경 확인"');
    expect(markup).toContain("합성 미가입자님을 전체 관리자로 지정할까요?");
    expect(markup).toContain("명단 #31"); expect(markup).toContain("개인 초대로 가입하면 전체 관리자 권한이 적용돼요.");
    expect(markup).toContain("가입한 계정도 연결 후 카드에서 ‘전체 관리자 지정’을 할 수 있어요.");
    expect(markup).toContain("연결만으로 권한이 바뀌지는 않아요.");
    expect(button(markup, "전체 관리자 지정")).not.toContain('disabled=""');
    expect(button(markup, "취소")).not.toContain('disabled=""');
  });

  it("explains that revoking a preassignment makes the future signup a member", () => {
    if (signup.kind !== "SIGNUP") throw new Error("Invalid synthetic fixture");
    const markup = render({ kind: "SIGNUP", entry: { ...signup.entry, signupGlobalRole: "GLOBAL_ADMIN" } });
    expect(markup).toContain("합성 미가입자님의 전체 관리자 지정을 해제할까요?");
    expect(markup).toContain("개인 초대 가입 시 일반 회원으로 가입해요.");
    expect(button(markup, "전체 관리자 해제")).not.toContain('disabled=""');
  });

  it("offers an existing account promotion without claiming that another signup is needed", () => {
    const markup = render(account);
    expect(markup).toContain("fixture.existing (계정 #41)");
    expect(markup).toContain("합성 가입자님을 전체 관리자로 지정할까요?");
    expect(markup).not.toContain("개인 초대 가입");
    expect(markup).toContain("마지막 활성 전체 관리자의 권한은 해제할 수 없어요.");
  });

  it("supports demotion of an existing administrator and preserves leader responsibilities", () => {
    if (account.kind !== "ACCOUNT") throw new Error("Invalid synthetic fixture");
    const markup = render({ kind: "ACCOUNT", account: { ...account.account, globalRole: "GLOBAL_ADMIN" } });
    expect(markup).toContain("합성 가입자님의 전체 관리자 지정을 해제할까요?");
    expect(markup).toContain("팀장 겸임은 유지되며");
    expect(button(markup, "전체 관리자 해제")).not.toContain('disabled=""');
  });

  it("blocks confirm and cancel throughout an in-flight change", () => {
    const markup = render(signup, { pending: true });
    expect(button(markup, "처리하는 중…")).toContain('disabled=""');
    expect(button(markup, "취소")).toContain('disabled=""');
  });

  it("blocks a stale selection while allowing cancellation beside the explanation", () => {
    const markup = render(signup, { stale: true });
    expect(markup).toContain("이 구성원의 상태가 바뀌었어요.");
    expect(button(markup, "전체 관리자 지정")).toContain('disabled=""');
    expect(button(markup, "취소")).not.toContain('disabled=""');
  });

  it("renders the failure inside the same confirmation without claiming success", () => {
    const markup = render(signup, { error: "오프라인에서는 요청할 수 없어요." });
    expect(markup).toContain('role="alert"');
    expect(markup).toContain("오프라인에서는 요청할 수 없어요.");
    expect(markup).not.toContain('role="status"');
    expect(button(markup, "현재 상태 다시 불러오기")).not.toContain('disabled=""');
    expect(markup.indexOf("오프라인에서는 요청할 수 없어요.")).toBeLessThan(markup.indexOf("전체 관리자 지정</button>"));
  });
});
