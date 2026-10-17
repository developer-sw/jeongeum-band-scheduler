// Synthetic, loopback-only HTTP checks for Test-ProductionContainers.ps1.
// Never pass credentials on the command line or print cookies/response bodies.
import { randomBytes, randomUUID } from "node:crypto";
import { readFile, writeFile, chmod } from "node:fs/promises";
import https from "node:https";
import http from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";

const [phase, directoryInput] = process.argv.slice(2);
const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const directory = path.resolve(directoryInput ?? "");
const leaf = path.basename(directory);
const fail = (code) => { throw Object.assign(new Error(code), { qaCode: code }); };
const requireThat = (condition, code) => { if (!condition) fail(code); };
// Validate phase before creating a report filename or doing any filesystem I/O.
requireThat(["init", "api", "restart", "restore"].includes(phase), "QA_PHASE_REJECTED");
requireThat(/^production-qa-[a-f0-9]{12}$/.test(leaf)
  && path.dirname(directory) === path.join(repository, ".local"), "QA_DIRECTORY_REJECTED");
const readJson = async (name) => JSON.parse(await readFile(path.join(directory, name), "utf8"));
const privateWrite = async (name, value) => {
  requireThat(typeof name === "string" && /^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name)
    && path.basename(name) === name, "QA_OUTPUT_BASENAME_REJECTED");
  const target = path.resolve(directory, name);
  requireThat(path.dirname(target) === directory, "QA_OUTPUT_DIRECTORY_REJECTED");
  await writeFile(target, typeof value === "string" ? value : JSON.stringify(value, null, 2), { mode: 0o600 });
  await chmod(target, 0o600);
};
let state;
let report = { phase, passed: false, checks: [] };
const check = (name, condition = true) => {
  requireThat(condition, name);
  report.checks.push(name);
};
const password = () => randomBytes(32).toString("base64url");

class Session {
  constructor(cookies = {}) { this.cookies = cookies; this.csrf = null; }
  receive(headers) {
    for (const cookie of headers["set-cookie"] ?? []) {
      const pair = cookie.split(";", 1)[0];
      const divider = pair.indexOf("=");
      const name = pair.slice(0, divider);
      if (divider < 1) continue;
      if (/;\s*max-age=0(?:;|$)/i.test(cookie) || !pair.slice(divider + 1)) delete this.cookies[name];
      else this.cookies[name] = pair.slice(divider + 1);
    }
  }
}

let origin;
let agent;
async function request(session, route, { method = "GET", body, csrf = true, headers = {}, plainHttp = false } = {}) {
  requireThat(typeof route === "string" && route.startsWith("/") && !route.startsWith("//"), "QA_PATH_REJECTED");
  const target = new URL(route, plainHttp ? `http://localhost:${state.httpPort}` : origin);
  requireThat(target.hostname === "localhost", "QA_HOST_REJECTED");
  const payload = body === undefined ? null : Buffer.from(JSON.stringify(body));
  const outgoing = { Accept: "application/json", Origin: origin, ...headers };
  if (session && Object.keys(session.cookies).length) outgoing.Cookie = Object.entries(session.cookies).map(([key, value]) => `${key}=${value}`).join("; ");
  if (payload) { outgoing["Content-Type"] = "application/json"; outgoing["Content-Length"] = payload.length; }
  if (session?.csrf && csrf && method !== "GET") outgoing[session.csrf.headerName] = session.csrf.token;
  return await new Promise((resolve, reject) => {
    const transport = plainHttp ? http : https;
    const req = transport.request(target, {
      method, headers: outgoing, agent: plainHttp ? undefined : agent, family: 4,
      // Force loopback while retaining localhost certificate and Host validation.
      lookup: (_host, _options, callback) => callback(null, "127.0.0.1", 4),
    }, (res) => {
      const tlsAuthorized = plainHttp ? false : res.socket.authorized === true;
      const chunks = [];
      let bytes = 0;
      res.on("data", (chunk) => {
        bytes += chunk.length;
        if (bytes > 3 * 1024 * 1024) { req.destroy(); reject(Object.assign(new Error(), { qaCode: "QA_RESPONSE_TOO_LARGE" })); }
        else chunks.push(chunk);
      });
      res.on("end", () => {
        session?.receive(res.headers);
        const text = Buffer.concat(chunks).toString("utf8");
        let json = null;
        try { json = JSON.parse(text); } catch { /* HTML/static routes are checked separately. */ }
        resolve({ status: res.statusCode, headers: res.headers, json, text, tlsAuthorized });
      });
      res.on("error", () => reject(Object.assign(new Error(), { qaCode: "QA_RESPONSE_STREAM_FAILED" })));
    });
    req.setTimeout(20000, () => req.destroy(Object.assign(new Error(), { qaCode: "QA_REQUEST_TIMEOUT" })));
    req.on("error", (error) => reject(Object.assign(new Error(), { qaCode: error.qaCode ?? "QA_TLS_OR_HTTP_REQUEST_FAILED" })));
    if (payload) req.write(payload);
    req.end();
  });
}
async function api(session, route, options, expectedStatus = 200) {
  const response = await request(session, `/api/v1${route}`, options);
  requireThat(response.status === expectedStatus, `API_${expectedStatus}_EXPECTED`);
  requireThat(response.json && response.json.data !== undefined, "API_ENVELOPE_INVALID");
  return response.json.data;
}
async function csrf(session) {
  const value = await api(session, "/auth/csrf");
  requireThat(typeof value.headerName === "string" && typeof value.token === "string", "CSRF_RESPONSE_INVALID");
  session.csrf = value;
}
async function login(account) {
  const session = new Session();
  await csrf(session);
  const response = await request(session, "/api/v1/auth/login", {
    method: "POST", body: { loginId: account.loginId, password: account.password },
  });
  requireThat(response.status === 200, "SYNTHETIC_LOGIN_FAILED");
  const cookie = (response.headers["set-cookie"] ?? []).find((value) => value.startsWith("SESSION="));
  requireThat(cookie && /;\s*Secure(?:;|$)/i.test(cookie) && /;\s*HttpOnly(?:;|$)/i.test(cookie)
    && /;\s*SameSite=Lax(?:;|$)/i.test(cookie) && /;\s*Max-Age=7776000(?:;|$)/i.test(cookie), "SECURE_PERSISTENT_COOKIE_INVALID");
  requireThat(response.json?.data?.id, "LOGIN_ACCOUNT_MISSING");
  await csrf(session); // Login rotates the session and CSRF token.
  return { session, member: response.json.data };
}
function nextDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + 1);
  return value.toISOString().slice(0, 10);
}
async function waitForHealth() {
  for (let attempt = 0; attempt < 90; attempt++) {
    try {
      const response = await request(null, "/api/v1/health");
      if (response.status === 200 && response.json?.data?.status === "UP" && response.tlsAuthorized) return;
    } catch { /* Bounded startup retry; credentials and response bodies stay private. */ }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  fail("HTTPS_HEALTH_TIMEOUT");
}
async function registerInvited(admin, account, preassign) {
  let entry = await api(admin, "/roster", { method: "POST", body: { name: account.displayName } }, 201);
  if (preassign) entry = await api(admin, `/roster/${entry.id}/signup-role`, {
    method: "PATCH", body: { globalRole: "GLOBAL_ADMIN", version: entry.version },
  });
  const invitation = await api(admin, "/invitations", {
    method: "POST", body: { rosterEntryId: entry.id, expiresAt: new Date(Date.now() + 86400000).toISOString() },
  }, 201);
  requireThat(typeof invitation.token === "string", "INVITATION_TOKEN_MISSING");
  const visitor = new Session();
  await csrf(visitor);
  const member = await api(visitor, "/auth/register", {
    method: "POST", body: { invitationToken: invitation.token, loginId: account.loginId, password: account.password, displayName: account.displayName },
  }, 201);
  requireThat(member.globalRole === (preassign ? "GLOBAL_ADMIN" : "MEMBER"), "INVITED_ROLE_INCORRECT");
  requireThat((await request(visitor, "/api/v1/auth/me")).status === 401, "SIGNUP_SHOULD_NOT_AUTOLOGIN");
  return { entry, ...(await login(account)) };
}
async function runApiChecks() {
  await waitForHealth();
  check("https_certificate_trusted_and_database_up");
  const hardened = (response) => /no-store/i.test(response.headers["cache-control"] ?? "")
    && response.headers["x-content-type-options"]?.toLowerCase() === "nosniff"
    && response.headers["x-frame-options"]?.toUpperCase() === "DENY"
    && response.headers["referrer-policy"]?.toLowerCase() === "strict-origin-when-cross-origin";
  const health = await request(null, "/api/v1/health");
  check("api_health_no_store_and_security_headers", health.status === 200 && hardened(health));
  const redirect = await request(null, "/login", { plainHttp: true });
  check("http_redirects_to_https", [301, 302, 307, 308].includes(redirect.status)
    && /^https:\/\/localhost(?::\d+)?\//.test(redirect.headers.location ?? ""));
  const html = await request(null, "/login", { headers: { Accept: "text/html" } });
  check("production_frontend_login_page", html.status === 200 && /<html/i.test(html.text));
  check("login_html_no_store_and_security_headers", hardened(html));
  const assetPath = html.text.match(/\b(?:src|href)=["'](\/_next\/static\/[^"']+)["']/i)?.[1]?.replaceAll("&amp;", "&");
  requireThat(assetPath, "NEXT_STATIC_ASSET_NOT_IN_HTML");
  const asset = await request(null, assetPath);
  check("actual_next_static_asset_public_and_immutable", asset.status === 200
    && /(?:^|,)\s*public\b/i.test(asset.headers["cache-control"] ?? "")
    && /(?:^|,)\s*immutable\b/i.test(asset.headers["cache-control"] ?? ""));
  const spoofed = await request(null, "/api/v1/health", { headers: {
    Forwarded: 'for=192.0.2.44;host="attacker.invalid";proto=http',
    "X-Forwarded-For": "192.0.2.44", "X-Forwarded-Proto": "http", "X-Forwarded-Host": "attacker.invalid",
    "X-Forwarded-Port": "80", "X-Real-IP": "192.0.2.44",
  } });
  check("client_forwarded_headers_do_not_break_tls_api_proxy", spoofed.status === 200
    && spoofed.json?.data?.status === "UP" && spoofed.tlsAuthorized && hardened(spoofed));
  const worker = await request(null, "/push-sw.js");
  check("push_worker_javascript_and_no_store", worker.status === 200 && /javascript/i.test(worker.headers["content-type"] ?? "")
    && /no-store/i.test(worker.headers["cache-control"] ?? "") && !/<html/i.test(worker.text));
  check("anonymous_protected_api_denied", (await request(null, "/api/v1/users")).status === 401);
  check("missing_csrf_write_denied", (await request(new Session(), "/api/v1/roster", {
    method: "POST", csrf: false, body: { name: "Synthetic rejected write" },
  })).status === 403);

  const admin = await login(state.accounts.admin);
  state.adminId = admin.member.id;
  check("bootstrap_admin_login_secure_cookie", admin.member.globalRole === "GLOBAL_ADMIN");
  const users = await api(admin.session, "/users");
  const originalAdmin = users.find((member) => member.id === state.adminId);
  const demote = await request(admin.session, `/api/v1/users/${state.adminId}/role`, {
    method: "PATCH", body: { globalRole: "MEMBER", version: originalAdmin.version },
  });
  check("last_active_administrator_protected", demote.status === 409 && demote.json?.error?.code === "LAST_GLOBAL_ADMIN");
  const preassigned = await registerInvited(admin.session, state.accounts.preassigned, true);
  check("preassigned_admin_applied_to_personal_invitation", preassigned.member.globalRole === "GLOBAL_ADMIN");
  const ordinary = await registerInvited(admin.session, state.accounts.member, false);
  check("member_admin_api_denied", (await request(ordinary.session, "/api/v1/roster")).status === 403);
  let target = (await api(admin.session, "/users")).find((member) => member.id === ordinary.member.id);
  target = await api(admin.session, `/users/${target.id}/role`, { method: "PATCH", body: { globalRole: "GLOBAL_ADMIN", version: target.version } });
  check("existing_account_can_be_promoted", (await api(ordinary.session, "/auth/me")).globalRole === "GLOBAL_ADMIN");
  await api(admin.session, `/users/${target.id}/role`, { method: "PATCH", body: { globalRole: "MEMBER", version: target.version } });
  check("existing_account_role_revocation_immediate", (await request(ordinary.session, "/api/v1/roster")).status === 403);

  const team = await api(admin.session, "/teams", { method: "POST", body: { name: "Synthetic production QA team", kind: "REGULAR" } }, 201);
  await api(admin.session, `/teams/${team.id}/members`, { method: "POST", body: { rosterEntryId: ordinary.entry.id } }, 201);
  const led = await api(admin.session, `/teams/${team.id}/leader`, { method: "PUT", body: { rosterEntryId: ordinary.entry.id } });
  check("team_membership_and_leader_assignment", led.members.some((member) => member.rosterEntryId === ordinary.entry.id && member.role === "LEADER"));
  const schedule = await api(admin.session, "/schedule");
  state.reservationDate = nextDate(schedule.today);
  const open = await api(admin.session, `/schedule?from=${state.reservationDate}&to=${state.reservationDate}`);
  check("bookable_date_prepared_with_workers_off", open.days[0].openingStatus === "OPEN");
  const createBody = { subjectType: "PERSONAL", date: state.reservationDate, blockCode: "B01", instrumentCodes: ["DRUMS"] };
  const key = randomUUID();
  const reservation = await api(admin.session, "/reservations", { method: "POST", headers: { "Idempotency-Key": key }, body: createBody }, 201);
  const repeated = await api(admin.session, "/reservations", { method: "POST", headers: { "Idempotency-Key": key }, body: createBody }, 201);
  check("reservation_created_and_idempotent", reservation.id === repeated.id && reservation.status === "CONFIRMED");
  const conflict = await request(ordinary.session, "/api/v1/reservations", {
    method: "POST", headers: { "Idempotency-Key": randomUUID() }, body: createBody,
  });
  check("instrument_occupancy_conflict_preserved", conflict.status === 409);
  const changed = await api(admin.session, `/reservations/${reservation.id}`, {
    method: "PATCH", headers: { "Idempotency-Key": randomUUID() },
    body: { expectedVersion: reservation.version, date: state.reservationDate, blockCode: "B02", instrumentCodes: ["DRUMS"] },
  });
  const successor = changed.reservations?.[0];
  requireThat(successor?.id && successor.id !== reservation.id, "RESERVATION_SUCCESSOR_MISSING");
  check("reservation_change_preserves_original_history", (await api(admin.session, `/reservations/${reservation.id}`)).status === "REPLACED");
  const canceled = await api(admin.session, `/reservations/${successor.id}/cancel`, {
    method: "POST", headers: { "Idempotency-Key": randomUUID() }, body: { expectedVersion: successor.version, reason: "Synthetic QA cancellation" },
  });
  check("reservation_cancel_succeeds", canceled.status === "CANCELED");
  const teamReservation = await api(ordinary.session, "/reservations", {
    method: "POST", headers: { "Idempotency-Key": randomUUID() },
    body: { subjectType: "TEAM", teamId: team.id, date: state.reservationDate, blockCode: "B03", instrumentCodes: null },
  }, 201);
  check("team_reservation_created", teamReservation.teamId === team.id && teamReservation.status === "CONFIRMED");
  const push = await api(admin.session, "/push/public-key");
  check("external_push_disabled", push.enabled === false && push.publicKey === null);
  state.persisted = { teamId: team.id, reservationId: teamReservation.id, originalId: reservation.id, canceledId: successor.id,
    memberId: ordinary.member.id, preassignedId: preassigned.member.id, preassignedEntryId: preassigned.entry.id };
  state.sessions = { admin: admin.session.cookies, member: ordinary.session.cookies, preassigned: preassigned.session.cookies };
}
async function runRestartChecks() {
  await waitForHealth();
  requireThat(state.persisted && state.sessions, "RESTART_FIXTURE_MISSING");
  const admin = new Session(state.sessions.admin);
  const member = new Session(state.sessions.member);
  const preassigned = new Session(state.sessions.preassigned);
  check("administrator_session_survives_restart", (await api(admin, "/auth/me")).id === state.adminId);
  const current = await api(member, "/auth/me");
  check("member_session_and_team_survive_restart", current.id === state.persisted.memberId
    && current.teamMemberships.some((item) => item.teamId === state.persisted.teamId && item.role === "LEADER"));
  check("preassigned_admin_survives_restart", (await api(preassigned, "/auth/me")).globalRole === "GLOBAL_ADMIN");
  check("confirmed_reservation_survives_restart", (await api(member, `/reservations/${state.persisted.reservationId}`)).status === "CONFIRMED");
  check("reservation_lineage_survives_restart", (await api(admin, `/reservations/${state.persisted.originalId}`)).status === "REPLACED"
    && (await api(admin, `/reservations/${state.persisted.canceledId}`)).status === "CANCELED");
  const entry = (await api(admin, "/roster")).find((value) => value.id === state.persisted.preassignedEntryId);
  check("roster_link_and_signup_role_survive_restart", entry?.signupGlobalRole === "GLOBAL_ADMIN" && entry.userId === state.persisted.preassignedId);
  state.sessions = { admin: admin.cookies, member: member.cookies, preassigned: preassigned.cookies };
}

try {
  if (phase === "init") {
    await chmod(directory, 0o700);
    await privateWrite("docker.private.log", "");
    const metadata = await readJson("qa-run.json");
    requireThat(metadata.project === `jeongeum-prod-qa-${leaf.slice(-12)}`
      && /^qa_[a-f0-9]{12}$/.test(metadata.database) && /^qa_[a-f0-9]{12}$/.test(metadata.databaseUser), "QA_METADATA_REJECTED");
    requireThat([metadata.httpPort, metadata.httpsPort].every((port) => Number.isInteger(port) && port >= 1024 && port <= 65535), "QA_PORT_REJECTED");
    state = { ...metadata, accounts: {
      admin: { loginId: `qa-admin-${leaf.slice(-12)}`, password: password(), displayName: "Synthetic QA administrator" },
      member: { loginId: `qa-user-${leaf.slice(-12)}`, password: password(), displayName: "Synthetic QA member" },
      preassigned: { loginId: `qa-pre-${leaf.slice(-12)}`, password: password(), displayName: "Synthetic QA planned administrator" },
    } };
    for (const [file, value] of Object.entries({
      "postgres-admin-password.txt": password(), "app-db-password.txt": password(),
      "bootstrap-login-id.txt": state.accounts.admin.loginId, "bootstrap-password.txt": state.accounts.admin.password,
      "bootstrap-display-name.txt": state.accounts.admin.displayName,
      "vapid-public-key.txt": "", "vapid-private-key.txt": "", "vapid-subject.txt": "",
    })) {
      await privateWrite(file, value);
      // Compose file secrets are bind mounts: their uid/gid/mode are not
      // remapped. The host's 0700 parent remains private; only these explicitly
      // mounted files are readable by the nonroot runtime/container DB users.
      if (process.platform !== "win32") await chmod(path.join(directory, file), 0o444);
    }
    check("random_synthetic_secrets_generated_privately");
  } else {
    state = await readJson("qa-state.private.json");
    requireThat(state.project === `jeongeum-prod-qa-${leaf.slice(-12)}`, "QA_STATE_REJECTED");
    origin = `https://localhost:${state.httpsPort}`;
    agent = new https.Agent({ ca: await readFile(path.join(directory, "caddy-root.crt")), rejectUnauthorized: true });
    if (phase === "api") await runApiChecks();
    else if (phase === "restart" || phase === "restore") await runRestartChecks();
    else fail("QA_PHASE_REJECTED");
  }
  report.passed = true;
} catch (error) {
  report.error = /^[A-Z0-9_]{1,100}$/.test(error?.qaCode ?? "") ? error.qaCode : "QA_CHECK_FAILED";
  process.exitCode = 1;
} finally {
  agent?.destroy();
  if (state) await privateWrite("qa-state.private.json", state);
  await privateWrite(`${phase}-result.json`, report);
  // Deliberately emit only fixed check names and a boolean, never request data.
  process.stdout.write(JSON.stringify(report) + "\n");
}
