/* Push only: do not cache authenticated HTML, API responses, or mutation requests. */
/* global self, indexedDB, URL */
const DATABASE = "jeongeum-push-binding";
const STORE = "binding";
let operations = Promise.resolve();
function serial(task) { const next = operations.then(task, task); operations = next.catch(() => {}); return next; }
function validBinding(value) { return value && typeof value.subscriptionId === "string" && /^[1-9]\d*$/.test(value.subscriptionId) && typeof value.bindingVersion === "string" && /^(0|[1-9]\d*)$/.test(value.bindingVersion); }
function database() {
  return new Promise((resolve, reject) => {
    const request = indexedDB.open(DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(new Error("Push binding storage unavailable"));
  });
}
async function readBinding() {
  const db = await database();
  try { return await new Promise((resolve, reject) => { const transaction = db.transaction(STORE, "readonly"); const request = transaction.objectStore(STORE).get("current"); request.onsuccess = () => resolve(validBinding(request.result) ? request.result : null); request.onerror = () => reject(new Error("Push binding unavailable")); }); }
  finally { db.close(); }
}
async function saveBinding(value) {
  const db = await database();
  try { await new Promise((resolve, reject) => { const transaction = db.transaction(STORE, "readwrite"); const store = transaction.objectStore(STORE); if (value) store.put({ subscriptionId: value.subscriptionId, bindingVersion: value.bindingVersion }, "current"); else store.delete("current"); transaction.oncomplete = () => resolve(); transaction.onerror = transaction.onabort = () => reject(new Error("Push binding unavailable")); }); }
  finally { db.close(); }
}
function sameBinding(a, b) { return validBinding(a) && validBinding(b) && a.subscriptionId === b.subscriptionId && a.bindingVersion === b.bindingVersion; }
function targetPath(path) {
  if (typeof path !== "string" || /[\\\r\n#]/.test(path)) return "/notifications";
  return /^\/notices\/[1-9]\d*$/.test(path) || /^\/reservations\?reservationId=[1-9]\d*$/.test(path) || /^\/exchanges\?exchangeId=[1-9]\d*$/.test(path) ? path : "/notifications";
}
async function closeNotifications() {
  const notifications = await self.registration.getNotifications();
  for (const notification of notifications) if (notification.tag?.startsWith("jeongeum:")) notification.close();
}
self.addEventListener("install", (event) => event.waitUntil(self.skipWaiting()));
self.addEventListener("activate", (event) => event.waitUntil(self.clients.claim()));
self.addEventListener("message", (event) => {
  const reply = event.ports?.[0];
  if (!reply || !event.source?.url || new URL(event.source.url).origin !== self.location.origin) return;
  event.waitUntil(serial(async () => {
    try {
      if (event.data?.type === "READ_PUSH_BINDING") reply.postMessage({ ok: true, binding: await readBinding() });
      else if (event.data?.type === "SET_PUSH_BINDING" && (event.data.binding === null || validBinding(event.data.binding))) {
        await saveBinding(event.data.binding);
        // Old notifications must not remain visible after logout or account transfer.
        await closeNotifications();
        reply.postMessage({ ok: true, binding: event.data.binding });
      } else reply.postMessage({ ok: false, binding: null });
    } catch { reply.postMessage({ ok: false, binding: null }); }
  }));
});
self.addEventListener("push", (event) => event.waitUntil(serial(async () => {
  let payload = null;
  try { payload = event.data?.json() ?? null; } catch { /* Empty/malformed push still has a visible, private fallback. */ }
  let binding = null;
  try { binding = await readBinding(); } catch { /* Never show personal content without a verified binding. */ }
  const bound = sameBinding(binding, payload);
  const title = bound && typeof payload.title === "string" ? payload.title.slice(0, 120) : "정음 알림";
  const body = bound && typeof payload.body === "string" ? payload.body.slice(0, 1000) : "로그인 후 내 알림함을 확인해 주세요.";
  const eventKey = bound && typeof (payload.eventKey ?? payload.eventId ?? payload.tag) === "string" ? String(payload.eventKey ?? payload.eventId ?? payload.tag).slice(0, 200) : "account-check";
  await self.registration.showNotification(title || "정음 알림", {
    body, icon: "/icons/jeongeum-192.png", badge: "/icons/jeongeum-192.png",
    tag: `jeongeum:${bound ? `${binding.subscriptionId}:${eventKey}` : "account-check"}`, renotify: false,
    data: { targetPath: bound ? targetPath(payload.targetPath) : "/notifications", binding: bound ? binding : null },
  });
})));
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  event.waitUntil(serial(async () => {
    let binding = null;
    try { binding = await readBinding(); } catch { /* Only use a private fallback. */ }
    const path = sameBinding(binding, event.notification.data?.binding) ? targetPath(event.notification.data.targetPath) : "/notifications";
    const url = new URL(path, self.location.origin).href;
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    const client = windows.find((candidate) => new URL(candidate.url).origin === self.location.origin);
    if (client) { const navigated = await client.navigate(url); if (navigated) { await navigated.focus(); return; } }
    await self.clients.openWindow(url);
  }));
});
