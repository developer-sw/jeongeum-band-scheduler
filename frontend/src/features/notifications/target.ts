/** An inbox link never grants authority, and never redirects outside known app routes. */
export function notificationTarget(path: string | null): string | null {
  if (!path || /[\\\r\n#]/.test(path)) return null;
  if (/^\/notices\/[1-9]\d*$/.test(path)) return path;
  if (/^\/reservations\?reservationId=[1-9]\d*$/.test(path)) return path;
  if (/^\/exchanges\?exchangeId=[1-9]\d*$/.test(path)) return path;
  return null;
}
