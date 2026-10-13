export interface Notification { id: string; eventType: string; title: string; body: string; targetPath: string | null; createdAt: string; readAt: string | null }
export interface NotificationList { serverTime: string; notifications: Notification[]; unreadCount: number; nextBeforeId: string | null }
export interface ReadAllResult { through: string; readCount: number; unreadCount: number }
export type ReadIntent = { kind: "SINGLE"; id: string } | { kind: "ALL"; through: string };
