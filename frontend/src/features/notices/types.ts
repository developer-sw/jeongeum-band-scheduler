export interface Notice {
  id: string;
  title: string;
  body: string;
  pinned: boolean;
  authorUserId: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  deleted: boolean;
}
export interface NoticeList { serverTime: string; notices: Notice[]; nextCursor: string | null }
export interface NoticeInput { title: string; body: string; pinned: boolean }
export type NoticeIntent =
  | { kind: "CREATE"; input: NoticeInput }
  | { kind: "UPDATE"; id: string; input: NoticeInput & { expectedVersion: number } }
  | { kind: "DELETE"; id: string; input: { expectedVersion: number } };
