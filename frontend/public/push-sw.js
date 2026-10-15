/* Push only: do not cache authenticated HTML, API responses, or mutation requests. */
/* global self, indexedDB, URL, setTimeout, clearTimeout */
const DATABASE = "jeongeum-push-binding";
const STORE = "binding";
const WORKER_VERSION = "push-click-recovery-v2";
const CLICK_TTL = 120000;
const NATIVE_TIMEOUT = 1500;
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
  return editState(({ store, pending, diagnostics }) => {
    if (value) store.put({ subscriptionId: value.subscriptionId, bindingVersion: value.bindingVersion }, "current");
    else store.delete("current");
    if (pending && (!value || !sameBinding(value, pending.binding))) {
      store.delete("pending-click");
      store.put({ ...diagnostics, lastRecovery: "CLEARED" }, "diagnostics");
    }
  });
}
function sameBinding(a, b) { return validBinding(a) && validBinding(b) && a.subscriptionId === b.subscriptionId && a.bindingVersion === b.bindingVersion; }
function targetPath(path) {
  if (typeof path !== "string" || /[\\\r\n#]/.test(path)) return "/notifications";
  return /^\/notices\/[1-9]\d*$/.test(path) || /^\/reservations\?reservationId=[1-9]\d*$/.test(path) || /^\/exchanges\?exchangeId=[1-9]\d*$/.test(path) ? path : "/notifications";
}
function diagnosticsValue(value) {
  return { workerVersion: WORKER_VERSION,
    clickReceivedAt: typeof value?.clickReceivedAt === "string" && Number.isFinite(Date.parse(value.clickReceivedAt)) ? new Date(value.clickReceivedAt).toISOString() : null,
    lastNativeResult: /^(EXISTING_FOCUS|EXISTING_NAVIGATE|NAVIGATED_FOCUS|OPEN_WINDOW|OPENED_FOCUS)_(RESOLVED|TIMEOUT|REJECTED)$/.test(value?.lastNativeResult) ? value.lastNativeResult : null,
    lastRecovery: ["PENDING", "CLEARED", "EXPIRED", "INVALID", "WAITING_FOR_BINDING", "ACCOUNT_CHANGED", "CONSUMED"].includes(value?.lastRecovery) ? value.lastRecovery : null,
    windowCount: Number.isSafeInteger(value?.windowCount) && value.windowCount >= 0 ? value.windowCount : null,
    targetMatched: typeof value?.targetMatched === "boolean" ? value.targetMatched : null };
}
// Read/modify all ownership state in one transaction, including a one-time click take.
async function editState(edit) {
  const db = await database();
  try { return await new Promise((resolve, reject) => {
    const transaction = db.transaction(STORE, "readwrite"), store = transaction.objectStore(STORE);
    const values = {}, keys = ["current", "pending-click", "diagnostics"];
    let remaining = keys.length, result;
    transaction.oncomplete = () => resolve(result);
    transaction.onerror = transaction.onabort = () => reject(new Error("Push state unavailable"));
    for (const key of keys) {
      const request = store.get(key);
      request.onerror = () => reject(new Error("Push state unavailable"));
      request.onsuccess = () => {
        values[key] = request.result;
        if (--remaining !== 0) return;
        try { result = edit({ store, current: validBinding(values.current) ? values.current : null,
          pending: values["pending-click"], diagnostics: diagnosticsValue(values.diagnostics) }); }
        catch { transaction.abort(); }
      };
    }
  }); } finally { db.close(); }
}
async function updateDiagnostics(patch) {
  await editState(({ store, diagnostics }) => { store.put({ ...diagnostics, ...patch }, "diagnostics"); });
}
async function clickDestination(data) {
  return editState(({ store, current, diagnostics }) => {
    const bound = sameBinding(current, data?.binding);
    const path = bound ? targetPath(data.targetPath) : "/notifications";
    store.put({ targetPath: path, binding: bound ? current : null, createdAt: Date.now() }, "pending-click");
    store.put({ ...diagnostics, clickReceivedAt: new Date().toISOString(), lastNativeResult: null,
      lastRecovery: "PENDING", windowCount: null, targetMatched: null }, "diagnostics");
    return path;
  });
}
async function takeClick() {
  return editState(({ store, current, pending, diagnostics }) => {
    if (!pending) return null;
    const age = Date.now() - pending.createdAt;
    let outcome, path = null;
    if (!Number.isSafeInteger(pending.createdAt) || !Number.isFinite(age) || age < 0 || age >= CLICK_TTL) outcome = "EXPIRED";
    else if (pending.binding === null) {
      if (pending.targetPath === "/notifications") { path = "/notifications"; outcome = "CONSUMED"; }
      else outcome = "INVALID";
    } else if (!validBinding(pending.binding) || targetPath(pending.targetPath) !== pending.targetPath) outcome = "INVALID";
    else if (!current) {
      store.put({ ...diagnostics, lastRecovery: "WAITING_FOR_BINDING" }, "diagnostics");
      return null;
    } else if (!sameBinding(current, pending.binding)) outcome = "ACCOUNT_CHANGED";
    else { path = pending.targetPath; outcome = "CONSUMED"; }
    store.delete("pending-click");
    store.put({ ...diagnostics, lastRecovery: outcome }, "diagnostics");
    return path;
  });
}
async function nativeCall(task) {
  let timer;
  const timeout = {};
  try {
    const value = await Promise.race([Promise.resolve().then(task), new Promise((_, reject) => {
      timer = setTimeout(() => reject(timeout), NATIVE_TIMEOUT);
    })]);
    return { ok: true, value };
  } catch (error) { return { ok: false, timedOut: error === timeout }; }
  finally { clearTimeout(timer); }
}
function matchingTarget(client, url) {
  if (typeof client?.url !== "string") return null;
  try { return new URL(client.url).href === url; } catch { return false; }
}
async function nativeDiagnostics(result, prefix, targetMatched) {
  const patch = { lastNativeResult: `${prefix}_${result.ok ? "RESOLVED" : result.timedOut ? "TIMEOUT" : "REJECTED"}` };
  if (targetMatched !== undefined) patch.targetMatched = targetMatched;
  try { await updateDiagnostics(patch); } catch { /* Diagnostics must not stop navigation. */ }
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
  // Taking a click must not wait behind a suspended native navigation operation.
  if (["READ_PUSH_BINDING", "TAKE_PUSH_CLICK", "READ_PUSH_DIAGNOSTICS"].includes(event.data?.type)) {
    event.waitUntil((async () => {
      try {
        if (event.data.type === "READ_PUSH_BINDING") reply.postMessage({ ok: true, binding: await readBinding() });
        else if (event.data.type === "TAKE_PUSH_CLICK") reply.postMessage({ ok: true, path: await takeClick() });
        else reply.postMessage({ ok: true, diagnostics: await editState(({ diagnostics }) => diagnostics) });
      } catch { reply.postMessage({ ok: false }); }
    })());
    return;
  }
  event.waitUntil(serial(async () => {
    try {
      if (event.data?.type === "SET_PUSH_BINDING" && (event.data.binding === null || validBinding(event.data.binding))) {
        if (event.data.binding === null && event.data.preservePendingClick === true) {
          await editState(({ store }) => store.delete("current"));
        } else await saveBinding(event.data.binding);
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
  event.waitUntil((async () => {
    let path = "/notifications";
    try { path = await serial(() => clickDestination(event.notification.data)); } catch { /* Native private fallback remains available. */ }
    const url = new URL(path, self.location.origin).href;
    const matched = await nativeCall(() => self.clients.matchAll({ type: "window", includeUncontrolled: true }));
    const windows = matched.ok && Array.isArray(matched.value) ? matched.value : [];
    try { await updateDiagnostics({ windowCount: windows.length }); } catch { /* Metadata only. */ }
    for (const client of windows) {
      if (new URL(client.url).origin !== self.location.origin) continue;
      try { client.postMessage({ type: "JEONGEUM_PUSH_CLICK" }); } catch { /* The page can take the click when it resumes. */ }
    }
    for (const client of windows) {
      if (new URL(client.url).origin !== self.location.origin) continue;
      // Restore the app window before navigating; a failed window must not end the click.
      const restored = await nativeCall(() => client.focus());
      await nativeDiagnostics(restored, "EXISTING_FOCUS");
      const navigation = await nativeCall(() => client.navigate(url));
      await nativeDiagnostics(navigation, "EXISTING_NAVIGATE", navigation.ok ? matchingTarget(navigation.value, url) : null);
      if (navigation.ok && navigation.value) {
        const focused = await nativeCall(() => navigation.value.focus());
        await nativeDiagnostics(focused, "NAVIGATED_FOCUS");
        if (focused.ok) return;
      }
    }
    const opened = await nativeCall(() => self.clients.openWindow(url));
    await nativeDiagnostics(opened, "OPEN_WINDOW", opened.ok ? matchingTarget(opened.value, url) : null);
    if (opened.ok && opened.value) {
      try { opened.value.postMessage({ type: "JEONGEUM_PUSH_CLICK" }); } catch { /* Read back after page initialization instead. */ }
      await nativeDiagnostics(await nativeCall(() => opened.value.focus()), "OPENED_FOCUS");
    }
  })());
});
