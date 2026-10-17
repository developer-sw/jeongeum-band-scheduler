import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, mkdir, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import {
  COMPONENTS, REPOSITORY, REQUIRED_JOBS, SOURCE, archiveFingerprint, artifactDirectory, assertLocalDocker, fail,
  githubGate, imageSummary, main, packageGate, parseArguments, prepare, publish, qaSummary,
  readManifest, registryToken, validateCandidate, validateContext, validateExisting,
} from "./production-image-release.mjs";

const sourceSha = "a".repeat(40);
const version = "v1.2.3-rc.1";
const ids = Object.fromEntries(COMPONENTS.map((component, index) => [component, `sha256:${String(index + 1).repeat(64)}`]));
const env = () => ({ GITHUB_EVENT_NAME: "workflow_dispatch", GITHUB_REF: "refs/heads/main",
  GITHUB_REPOSITORY: REPOSITORY, GITHUB_SHA: sourceSha, GITHUB_RUN_ID: "1001", GITHUB_RUN_ATTEMPT: "1",
  GITHUB_ACTOR: "fixture-actor", GITHUB_TOKEN: "fixture-token-not-a-real-credential" });
const context = () => ({ sourceSha, runId: "1001", runAttempt: 1 });
const options = () => ({ phase: "prepare", version, directory: "release-artifacts" });
// This fixture lists the actual container QA contract, independently of the release helper.
// Restart and restored-database API probes intentionally repeat the same six checks.
const containerChecks = [
  "random_synthetic_secrets_generated_privately", "resolved_configuration_cannot_mount_or_connect_development_data",
  "backend_nonroot", "frontend_nonroot", "caddy_nonroot", "database_and_application_ports_private",
  "all_four_workers_and_external_push_disabled", "qa_https_http_bind_only_loopback", "flyway_v1_through_v12_success",
  "application_database_role_not_cluster_administrator", "https_certificate_trusted_and_database_up",
  "api_health_no_store_and_security_headers", "http_redirects_to_https", "production_frontend_login_page",
  "login_html_no_store_and_security_headers", "actual_next_static_asset_public_and_immutable",
  "client_forwarded_headers_do_not_break_tls_api_proxy", "push_worker_javascript_and_no_store",
  "anonymous_protected_api_denied", "missing_csrf_write_denied", "bootstrap_admin_login_secure_cookie",
  "last_active_administrator_protected", "preassigned_admin_applied_to_personal_invitation", "member_admin_api_denied",
  "existing_account_can_be_promoted", "existing_account_role_revocation_immediate", "team_membership_and_leader_assignment",
  "bookable_date_prepared_with_workers_off", "reservation_created_and_idempotent", "instrument_occupancy_conflict_preserved",
  "reservation_change_preserves_original_history", "reservation_cancel_succeeds", "team_reservation_created", "external_push_disabled",
  "backend_graceful_stop_before_restart", "frontend_graceful_stop_before_restart", "caddy_graceful_stop_before_restart",
  "restart_preserves_all_table_rows_sessions_sequences_flyway", "administrator_session_survives_restart",
  "member_session_and_team_survive_restart", "preassigned_admin_survives_restart", "confirmed_reservation_survives_restart",
  "reservation_lineage_survives_restart", "roster_link_and_signup_role_survive_restart", "backend_graceful_stop_before_dump",
  "frontend_graceful_stop_before_dump", "caddy_graceful_stop_before_dump", "separate_qa_database_restore_preserves_all_rows_sessions_sequences_flyway",
  "restore_is_same_qa_cluster_not_external_disaster_recovery", "restored_tables_owned_and_readable_by_non_superuser_application_role",
  "administrator_session_survives_restart", "member_session_and_team_survive_restart", "preassigned_admin_survives_restart",
  "confirmed_reservation_survives_restart", "reservation_lineage_survives_restart", "roster_link_and_signup_role_survive_restart",
  "restored_database_serves_existing_sessions_and_reservation_apis",
];
const qa = () => ({ passed: true, checks: [...containerChecks],
  cleanupVerified: true, resourcesKept: false, developmentDatabaseAccessed: false, actualPushSent: false,
  project: "private-fixture-project", secret: "must-not-be-exported", dumpPath: "must-not-be-exported" });
function rawImage(component) {
  return { Id: ids[component], Os: "linux", Architecture: "amd64", Config: { User: component === "frontend" ? "node" : "10001:10001",
    Labels: { "org.opencontainers.image.source": SOURCE, "org.opencontainers.image.revision": sourceSha,
      "org.opencontainers.image.version": version } } };
}
function manifest(config) {
  const raw = Buffer.from(JSON.stringify({ schemaVersion: 2, mediaType: "application/vnd.oci.image.manifest.v1+json",
    config: { mediaType: "application/vnd.oci.image.config.v1+json", digest: config, size: 123 }, layers: [] }));
  return { raw, digest: `sha256:${createHash("sha256").update(raw).digest("hex")}`, config };
}
const response = (body, status = 200, headers = {}) => new Response(typeof body === "string" || Buffer.isBuffer(body) ? body : JSON.stringify(body), { status, headers });
function assertCode(code) { return (error) => error.releaseCode === code && error.message === code; }

async function fixture(t) {
  const root = await mkdtemp(path.join(os.tmpdir(), "jeongeum-release-test-"));
  t.after(() => rm(root, { recursive: true, force: true }));
  const commands = [], requests = [], registry = new Map(), published = new Set();
  const state = { dirty: false, mainSha: sourceSha, ciStatus: "completed", ciConclusion: "success", badJob: null,
    inspectChanges: null, failPushOnce: null, failPromotionOnce: null, packageStatus: null, packageVisibility: "private", packageLinked: true,
    dockerEndpoint: "unix:///var/run/docker.sock", dockerOs: "linux" };
  const io = { root, tempRoot: root, env: env(), async exec(file, args, settings = {}) {
    commands.push({ file, args: [...args], settings });
    if (file === "git") return args[0] === "rev-parse" ? sourceSha : state.dirty ? " M fixture-file" : "";
    if (file === "pwsh") { await writeFile(args[args.indexOf("-ResultPath") + 1], JSON.stringify(qa())); return "private QA logs are never exported"; }
    if (file !== "docker") throw new Error("Unexpected fixture process");
    if (args[0] === "context" && args[1] === "inspect") return state.dockerEndpoint;
    if (args[0] === "info") return state.dockerOs;
    if (args[0] === "buildx" && args[1] === "build") return "fixture build";
    if (args[0] === "image" && args[1] === "inspect") {
      const component = COMPONENTS.find((item) => args[2].includes(`${item}:`) || args[2].includes(`/${item}@`));
      const value = rawImage(component);
      if (state.inspectChanges === component) value.Id = `sha256:${"f".repeat(64)}`;
      return JSON.stringify([value]);
    }
    if (args[0] === "image" && args[1] === "save") { await writeFile(args[args.indexOf("--output") + 1], "fixture-docker-save-archive"); return ""; }
    if (args[0] === "login") {
      assert.equal(settings.input, `${io.env.GITHUB_TOKEN}\n`);
      assert.ok(settings.env.DOCKER_CONFIG.startsWith(`${root}${path.sep}jeongeum-image-release-`));
      return "";
    }
    if (args[0] === "image" && ["load", "tag", "pull"].includes(args[1])) return "";
    if (args[0] === "image" && args[1] === "push") {
      const component = COMPONENTS.find((item) => args[2].includes(`/${item}:`));
      if (state.failPushOnce === component) { state.failPushOnce = null; fail("RELEASE_PROCESS_FAILED"); }
      const item = manifest(ids[component]);
      registry.set(`${component}:sha-${sourceSha}`, item);
      registry.set(`${component}:${item.digest}`, item);
      published.add(component);
      return "";
    }
    if (args[0] === "buildx" && args[1] === "imagetools") {
      const target = args[args.indexOf("--tag") + 1];
      const component = COMPONENTS.find((item) => target.includes(`/${item}:`));
      if (state.failPromotionOnce === component) { state.failPromotionOnce = null; fail("RELEASE_PROCESS_FAILED"); }
      registry.set(`${component}:${version}`, registry.get(`${component}:sha-${sourceSha}`));
      return "";
    }
    throw new Error("Unexpected fixture Docker arguments");
  }, async fetch(url, settings) {
    requests.push({ url, settings });
    assert.equal(settings.redirect, "error");
    const parsed = new URL(url);
    assert.ok(["api.github.com", "ghcr.io"].includes(parsed.hostname));
    if (parsed.hostname === "api.github.com") {
      if (parsed.pathname.endsWith(`/repos/${REPOSITORY}`)) return response({ full_name: REPOSITORY, default_branch: "main", owner: { type: "User" } });
      if (parsed.pathname.endsWith("/git/ref/heads/main")) return response({ object: { sha: state.mainSha } });
      if (parsed.pathname.endsWith("/ci.yaml/runs")) return response({ workflow_runs: [{ id: 2002, event: "push", head_branch: "main", head_sha: sourceSha,
        path: ".github/workflows/ci.yaml", status: state.ciStatus, conclusion: state.ciConclusion }] });
      if (parsed.pathname.endsWith("/jobs")) return response({ jobs: REQUIRED_JOBS.map((name) => ({ name, status: "completed", conclusion: name === state.badJob ? "failure" : "success" })) });
      if (parsed.pathname.includes("/packages/container/")) {
        const component = COMPONENTS.find((item) => decodeURIComponent(parsed.pathname).endsWith(`/${item}`));
        if (state.packageStatus) return response({}, state.packageStatus);
        return published.has(component) ? response({ visibility: state.packageVisibility, repository: state.packageLinked ? { full_name: REPOSITORY } : null }) : response({}, 404);
      }
    }
    if (parsed.hostname === "ghcr.io" && parsed.pathname === "/token") return response({ token: "fixture-registry-bearer" });
    if (parsed.hostname === "ghcr.io" && parsed.pathname.includes("/manifests/")) {
      const parts = parsed.pathname.split("/");
      const item = registry.get(`${parts.at(-3)}:${parts.at(-1)}`);
      return item ? response(item.raw, 200, { "docker-content-digest": item.digest }) : response({}, 404);
    }
    throw new Error("Unexpected fixture URL");
  } };
  return { root, io, commands, requests, registry, published, state };
}
async function prepared(t) { const result = await fixture(t); await prepare(options(), result.io); return result; }
const candidateFrom = (f) => readFile(path.join(f.root, "release-artifacts/candidate.json"), "utf8").then(JSON.parse);

test("CLI accepts only manual phases, strict version and fixed artifact directory", () => {
  assert.equal(parseArguments(["prepare", "--version", "v0.0.0", "--directory", "release-artifacts"]).version, "v0.0.0");
  assert.equal(parseArguments(["publish", "--version", version, "--directory", "release-artifacts"]).phase, "publish");
  const longest = `v${"1".repeat(75)}.0.0`;
  assert.equal(longest.length, 80);
  assert.equal(parseArguments(["prepare", "--version", longest, "--directory", "release-artifacts"]).version, longest);
  assert.throws(() => parseArguments(["prepare", "--version", `v${"1".repeat(76)}.0.0`, "--directory", "release-artifacts"]), assertCode("RELEASE_ARGUMENTS_REJECTED"));
  for (const bad of ["latest", "main", "1.2.3", "v01.2.3", "v1.2.3+build", "v1.2.3-rc.01", "v1.2.3;echo", "v1.2.3\n"]) {
    assert.throws(() => parseArguments(["prepare", "--version", bad, "--directory", "release-artifacts"]), assertCode("RELEASE_ARGUMENTS_REJECTED"));
  }
  for (const bad of ["../release-artifacts", "release-artifacts/sub", "/release-artifacts"]) {
    assert.throws(() => parseArguments(["prepare", "--version", version, "--directory", bad]), assertCode("RELEASE_ARGUMENTS_REJECTED"));
  }
});
test("release context denies PR, fork, non-main, short SHA and remote Docker overrides", () => {
  assert.equal(validateContext(env()).sourceSha, sourceSha);
  for (const patch of [{ GITHUB_EVENT_NAME: "pull_request" }, { GITHUB_REF: "refs/heads/codex/test" }, { GITHUB_REPOSITORY: "other/repo" },
    { GITHUB_SHA: "a".repeat(7) }, { GITHUB_TOKEN: "" }, { GITHUB_RUN_ID: "" }, { GITHUB_ACTOR: "bad/actor" },
    { DOCKER_HOST: "tcp://remote" }, { DOCKER_CONTEXT: "remote-production" }, { ECE_KEYLOG: "1" }]) {
    assert.throws(() => validateContext({ ...env(), ...patch }));
  }
});
test("Docker guard permits only local Linux sockets and rejects remote active contexts", async (t) => {
  const f = await fixture(t);
  for (const endpoint of ["unix:///var/run/docker.sock", "unix:///home/runner/.docker/run/docker.sock", "npipe:////./pipe/docker_engine"]) {
    f.state.dockerEndpoint = endpoint;
    await assertLocalDocker(f.io);
  }
  for (const endpoint of ["tcp://127.0.0.1:2375", "ssh://fixture-host", "https://fixture-host:2376", "unix://relative.sock", "unix:///var/run/docker.sock\nssh://fixture-host", ""]) {
    f.state.dockerEndpoint = endpoint;
    await assert.rejects(assertLocalDocker(f.io), assertCode("RELEASE_REMOTE_DOCKER_REJECTED"));
  }
  f.state.dockerEndpoint = "unix:///var/run/docker.sock";
  f.state.dockerOs = "windows";
  await assert.rejects(assertLocalDocker(f.io), assertCode("RELEASE_LINUX_DOCKER_REQUIRED"));
  assert.equal(f.requests.length, 0);
  assert.ok(f.commands.every(({ file, args }) => file === "docker" && ["context", "info"].includes(args[0])));
});
test("output directory refuses existing preparations", async (t) => {
  const f = await fixture(t);
  await artifactDirectory(f.root, "release-artifacts", true);
  await assert.rejects(artifactDirectory(f.root, "release-artifacts", true), assertCode("RELEASE_DIRECTORY_ALREADY_EXISTS"));
});
test("output directory refuses symlinks or Windows junctions", async (t) => {
  const f = await fixture(t);
  await mkdir(path.join(f.root, "other"));
  try { await symlink(path.join(f.root, "other"), path.join(f.root, "release-artifacts"), process.platform === "win32" ? "junction" : "dir"); }
  catch (error) { if (["EPERM", "EACCES"].includes(error.code)) { t.skip("Symlink creation requires platform permission"); return; } throw error; }
  await assert.rejects(artifactDirectory(f.root, "release-artifacts"), assertCode("RELEASE_PATH_REJECTED"));
});
test("QA export allowlist excludes private project, paths and secret fields", () => {
  const safe = qaSummary(qa());
  assert.equal(safe.checkCount, 57);
  assert.equal(JSON.stringify(safe).includes("must-not-be-exported"), false);
  assert.equal("project" in safe, false);
  for (const patch of [{ passed: false }, { cleanupVerified: false }, { resourcesKept: true }, { actualPushSent: true }, { developmentDatabaseAccessed: true },
    { checks: qa().checks.slice(1) }, { checks: [...qa().checks.slice(1), "bad/path"] },
    { checks: [...qa().checks.slice(1), "backend_nonroot"] },
    { checks: Array(57).fill("backend_nonroot") }]) assert.throws(() => qaSummary({ ...qa(), ...patch }));
  assert.equal(qa().checks.filter((name) => name === "administrator_session_survives_restart").length, 2);
  assert.equal(qaSummary({ ...qa(), checks: qa().checks.reverse() }).checkCount, 57);
});
test("image metadata rejects root, wrong architecture, revision and mutable repositories", () => {
  assert.equal(imageSummary("backend", rawImage("backend"), context(), version).repository, `ghcr.io/${REPOSITORY}/backend`);
  for (const patch of [{ Id: "short" }, { Os: "windows" }, { Architecture: "arm64" }, { Config: { ...rawImage("backend").Config, User: "0:1000" } },
    { Config: { ...rawImage("backend").Config, User: "root" } }, { Config: { ...rawImage("backend").Config, Labels: {} } }]) {
    assert.throws(() => imageSummary("backend", { ...rawImage("backend"), ...patch }, context(), version));
  }
  assert.throws(() => imageSummary("unknown", rawImage("backend"), context(), version));
});
test("main gate requires current exact source and every required completed CI job", async (t) => {
  const f = await fixture(t);
  assert.equal((await githubGate(f.io, f.io.env, context())).proof.jobs.length, 4);
  f.state.mainSha = "b".repeat(40);
  await assert.rejects(githubGate(f.io, f.io.env, context()), assertCode("RELEASE_MAIN_SHA_MOVED"));
  f.state.mainSha = sourceSha;
  f.state.ciStatus = "in_progress";
  await assert.rejects(githubGate(f.io, f.io.env, context()), assertCode("RELEASE_CI_SUCCESS_REQUIRED"));
  f.state.ciStatus = "completed";
  for (const job of REQUIRED_JOBS) { f.state.badJob = job; await assert.rejects(githubGate(f.io, f.io.env, context()), assertCode("RELEASE_CI_JOBS_NOT_SUCCESSFUL")); }
});
test("dirty worktree and invalid CLI stop before build or network activity", async (t) => {
  const f = await fixture(t);
  f.state.dirty = true;
  await assert.rejects(prepare(options(), f.io), assertCode("RELEASE_CHECKOUT_DIRTY"));
  assert.equal(f.requests.length, 0);
  assert.equal(f.commands.some((item) => item.file === "docker"), false);
  await assert.rejects(main(["prepare", "--version", "latest", "--directory", "release-artifacts"], f.io));
});
test("prepare builds three labelled single-platform images, QA checks identity, and exports only three safe files", async (t) => {
  const f = await prepared(t);
  const directory = path.join(f.root, "release-artifacts");
  assert.deepEqual((await readdir(directory)).sort(), ["candidate.json", "images.tar", "qa-summary.json"]);
  const candidate = await candidateFrom(f);
  const safeQa = JSON.parse(await readFile(path.join(directory, "qa-summary.json"), "utf8"));
  validateCandidate(candidate, safeQa, context(), version);
  assert.equal(candidate.archive.sha256, (await archiveFingerprint(path.join(directory, "images.tar"))).sha256);
  assert.equal(JSON.stringify(candidate).includes(f.io.env.GITHUB_TOKEN), false);
  assert.equal(JSON.stringify(safeQa).includes("private-fixture"), false);
  const builds = f.commands.filter((item) => item.args[0] === "buildx" && item.args[1] === "build");
  assert.equal(builds.length, 3);
  assert.ok(builds.every(({ args }) => args.includes("--provenance=false") && args.includes("--sbom=false") && args.includes("linux/amd64")));
  assert.equal(f.commands.some((item) => item.args.includes("push") || item.args.includes("login")), false);
});
test("prepare never overwrites an existing artifact directory", async (t) => {
  const f = await fixture(t);
  await mkdir(path.join(f.root, "release-artifacts"));
  await assert.rejects(prepare(options(), f.io), assertCode("RELEASE_DIRECTORY_ALREADY_EXISTS"));
  assert.equal(f.commands.some((item) => item.file === "docker" && !["context", "info"].includes(item.args[0])), false);
});
test("prepare rejects a remote active Docker context before builds, QA or network calls", async (t) => {
  const f = await fixture(t); f.state.dockerEndpoint = "ssh://production-host";
  await assert.rejects(prepare(options(), f.io), assertCode("RELEASE_REMOTE_DOCKER_REJECTED"));
  assert.equal(f.requests.length, 0);
  assert.equal(f.commands.some(({ file, args }) => file === "pwsh" || (file === "docker" && args[0] !== "context")), false);
  assert.equal((await readdir(f.root)).includes("release-artifacts"), false);
});
test("QA image replacement is detected before saving release artifacts", async (t) => {
  const f = await fixture(t);
  const original = f.io.exec;
  f.io.exec = async (...args) => { const result = await original(...args); if (args[0] === "pwsh") f.state.inspectChanges = "backend"; return result; };
  await assert.rejects(prepare(options(), f.io), assertCode("RELEASE_TESTED_IMAGE_CHANGED"));
  assert.equal(f.commands.some((item) => item.args[1] === "save"), false);
});
test("publish verifies archive before any network, Docker login or load", async (t) => {
  const f = await prepared(t);
  await writeFile(path.join(f.root, "release-artifacts/images.tar"), "tampered");
  f.commands.length = 0; f.requests.length = 0;
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_ARCHIVE_HASH_MISMATCH"));
  assert.equal(f.requests.length, 0);
  assert.equal(f.commands.some((item) => item.file === "docker"), false);
});
test("publish rejects a remote active Docker context before credentials, load or registry access", async (t) => {
  const f = await prepared(t); f.state.dockerEndpoint = "tcp://production-host:2375";
  f.commands.length = 0; f.requests.length = 0;
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_REMOTE_DOCKER_REJECTED"));
  assert.equal(f.requests.length, 0);
  assert.equal(f.commands.some(({ file, args }) => file === "docker" && args[0] !== "context"), false);
  assert.equal((await readdir(path.join(f.root, "release-artifacts"))).includes("release.json"), false);
});
test("candidate rejects changed run/source/version, unknown fields and repository injection", async (t) => {
  const f = await prepared(t);
  const candidate = await candidateFrom(f);
  const safe = qaSummary(qa());
  for (const patch of [{ runId: "9999" }, { sourceSha: "b".repeat(40) }, { version: "v9.9.9" }, { unexpected: "private" }]) {
    assert.throws(() => validateCandidate({ ...candidate, ...patch }, safe, context(), version));
  }
  const injected = structuredClone(candidate);
  injected.images[0].repository = "ghcr.io/attacker/other";
  assert.throws(() => validateCandidate(injected, safe, context(), version), assertCode("RELEASE_TESTED_IMAGE_CHANGED"));
  validateCandidate(candidate, safe, { ...context(), runAttempt: 2 }, version);
  assert.throws(() => validateCandidate(candidate, safe, { ...context(), runAttempt: 0 }, version));
});
test("package metadata distinguishes missing private default from authentication failure", async (t) => {
  const f = await fixture(t);
  await packageGate(f.io, f.io.env, "User", "backend");
  await assert.rejects(packageGate(f.io, f.io.env, "User", "backend", false), assertCode("RELEASE_PACKAGE_READBACK_REQUIRED"));
  f.state.packageStatus = 401;
  await assert.rejects(packageGate(f.io, f.io.env, "User", "backend"), assertCode("RELEASE_GITHUB_AUTH_REJECTED"));
  f.state.packageStatus = 403;
  await assert.rejects(packageGate(f.io, f.io.env, "User", "backend"), assertCode("RELEASE_GITHUB_AUTH_REJECTED"));
});
test("public, internal and unlinked existing packages are rejected", async (t) => {
  const f = await fixture(t); f.published.add("backend");
  for (const visibility of ["public", "internal"]) { f.state.packageVisibility = visibility; await assert.rejects(packageGate(f.io, f.io.env, "User", "backend"), assertCode("RELEASE_PACKAGE_PRIVACY_REJECTED")); }
  f.state.packageVisibility = "private"; f.state.packageLinked = false;
  await assert.rejects(packageGate(f.io, f.io.env, "User", "backend"), assertCode("RELEASE_PACKAGE_PRIVACY_REJECTED"));
});
test("registry reads distinguish tag 404 and auth 401; reject index and digest spoofing", async () => {
  const io = { async fetch() { return response({}, 404); } };
  assert.equal(await readManifest(io, "backend", version, "fixture"), null);
  io.fetch = async () => response({}, 401);
  await assert.rejects(readManifest(io, "backend", version, "fixture"), assertCode("RELEASE_REGISTRY_AUTH_REJECTED"));
  io.fetch = async () => response({}, 403);
  await assert.rejects(readManifest(io, "backend", version, "fixture"), assertCode("RELEASE_REGISTRY_AUTH_REJECTED"));
  const item = manifest(ids.backend);
  io.fetch = async () => response(item.raw, 200, { "docker-content-digest": `sha256:${"f".repeat(64)}` });
  await assert.rejects(readManifest(io, "backend", version, "fixture"), assertCode("RELEASE_REGISTRY_DIGEST_MISMATCH"));
  const raw = Buffer.from(JSON.stringify({ schemaVersion: 2, mediaType: "application/vnd.oci.image.index.v1+json", manifests: [] }));
  io.fetch = async () => response(raw, 200, { "docker-content-digest": `sha256:${createHash("sha256").update(raw).digest("hex")}` });
  await assert.rejects(readManifest(io, "backend", version, "fixture"), assertCode("RELEASE_REGISTRY_SINGLE_MANIFEST_REQUIRED"));
  await assert.rejects(readManifest(io, "backend", "latest", "fixture"), assertCode("RELEASE_REGISTRY_REF_REJECTED"));
});
test("registry authentication never follows an untrusted challenge or redirect", async () => {
  for (const status of [302, 401, 403]) {
    const calls = [];
    const io = { async fetch(url, settings) {
      calls.push({ url, settings });
      return response({}, status, { location: "https://untrusted.invalid/steal-token",
        "www-authenticate": 'Bearer realm="https://untrusted.invalid/token",service="ghcr.io"' });
    } };
    await assert.rejects(registryToken(io, env(), "backend"), assertCode("RELEASE_REGISTRY_AUTH_REJECTED"));
    assert.equal(calls.length, 1);
    const target = new URL(calls[0].url);
    assert.equal(target.origin, "https://ghcr.io");
    assert.equal(target.pathname, "/token");
    assert.equal(target.searchParams.get("scope"), `repository:${REPOSITORY}/backend:pull`);
    assert.equal(calls[0].settings.redirect, "error");
    assert.equal(calls[0].url.includes(env().GITHUB_TOKEN), false);
  }
});
test("existing immutable tag contract accepts identical digest/config only", () => {
  const item = manifest(ids.backend);
  validateExisting(item, item, ids.backend);
  assert.throws(() => validateExisting({ ...item, config: ids.frontend }, null, ids.backend), assertCode("RELEASE_SHA_TAG_CONFLICT"));
  assert.throws(() => validateExisting(null, item, ids.backend), assertCode("RELEASE_VERSION_TAG_CONFLICT"));
  assert.throws(() => validateExisting(item, { ...item, digest: `sha256:${"f".repeat(64)}` }, ids.backend), assertCode("RELEASE_VERSION_TAG_CONFLICT"));
});
test("successful publish preflights all tags, uses stdin-only auth, digest pulls and version promotion", async (t) => {
  const f = await prepared(t); f.commands.length = 0; f.requests.length = 0;
  await publish(options(), f.io);
  const files = await readdir(path.join(f.root, "release-artifacts"));
  assert.ok(files.includes("release.json") && files.includes("release.env"));
  const release = JSON.parse(await readFile(path.join(f.root, "release-artifacts/release.json"), "utf8"));
  assert.equal(release.images.length, 3);
  const environment = await readFile(path.join(f.root, "release-artifacts/release.env"), "utf8");
  assert.equal(environment.trim().split("\n").length, 3);
  assert.ok(environment.trim().split("\n").every((line) => /^(BACKEND|FRONTEND|CADDY)_IMAGE=ghcr\.io\/developer-sw\/jeongeum-band-scheduler\/(backend|frontend|caddy)@sha256:[a-f0-9]{64}$/.test(line)));
  assert.equal(f.commands.filter((item) => item.args[1] === "push").length, 3);
  assert.equal(f.commands.filter((item) => item.args[1] === "pull").length, 3);
  const promotions = f.commands.filter((item) => item.args[1] === "imagetools");
  assert.equal(promotions.length, 3);
  assert.ok(promotions.every((item) => item.args.includes("--prefer-index=false")));
  assert.ok(f.commands.every((item) => !JSON.stringify(item.args).includes(f.io.env.GITHUB_TOKEN)));
  assert.equal((await readdir(f.root)).some((name) => name.startsWith("jeongeum-image-release-")), false);
  assert.equal(JSON.stringify(release).includes("fixture-token"), false);
});
test("publish refreshes registry credentials for later reads after long-running image operations", async (t) => {
  const f = await prepared(t);
  const originalFetch = f.io.fetch;
  let nextToken = 0, unusedToken = null, manifestReads = 0;
  f.io.fetch = async (url, settings) => {
    const parsed = new URL(url);
    if (parsed.hostname === "ghcr.io" && parsed.pathname.includes("/manifests/")) {
      manifestReads++;
      if (unusedToken === null || settings.headers.Authorization !== `Bearer ${unusedToken}`) return response({}, 401);
      unusedToken = null;
    }
    const result = await originalFetch(url, settings);
    if (parsed.hostname === "ghcr.io" && parsed.pathname === "/token") {
      unusedToken = `fixture-short-lived-${++nextToken}`;
      return response({ token: unusedToken });
    }
    return result;
  };
  await publish(options(), f.io);
  assert.ok(manifestReads > COMPONENTS.length * 2);
  assert.equal(nextToken, manifestReads);
  assert.equal((await readdir(path.join(f.root, "release-artifacts"))).includes("release.json"), true);
});
test("a conflict in the third component prevents all registry writes", async (t) => {
  const f = await prepared(t);
  f.registry.set(`caddy:sha-${sourceSha}`, manifest(ids.backend));
  f.commands.length = 0;
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_SHA_TAG_CONFLICT"));
  assert.equal(f.commands.some((item) => item.args[0] === "login" || item.args[1] === "push"), false);
});
test("partial SHA push failure keeps existing refs and retries the same archive idempotently", async (t) => {
  const f = await prepared(t); f.commands.length = 0; f.state.failPushOnce = "frontend";
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_PROCESS_FAILED"));
  assert.ok(f.registry.has(`backend:sha-${sourceSha}`));
  assert.equal(f.registry.has(`backend:${version}`), false);
  assert.equal((await readdir(path.join(f.root, "release-artifacts"))).includes("release.json"), false);
  assert.equal((await readdir(f.root)).some((name) => name.startsWith("jeongeum-image-release-")), false);
  f.io.env.GITHUB_RUN_ATTEMPT = "2";
  await publish(options(), f.io);
  assert.equal(f.commands.filter((item) => item.args[1] === "push" && item.args[2].includes("/backend:")).length, 1);
  assert.ok(COMPONENTS.every((component) => f.registry.has(`${component}:${version}`)));
});
test("partial version promotion failure is resumed without overwriting a successful version", async (t) => {
  const f = await prepared(t); f.commands.length = 0; f.state.failPromotionOnce = "frontend";
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_PROCESS_FAILED"));
  assert.ok(f.registry.has(`backend:${version}`));
  f.io.env.GITHUB_RUN_ATTEMPT = "2";
  await publish(options(), f.io);
  assert.equal(f.commands.filter((item) => item.args[1] === "imagetools" && item.args.some((arg) => arg.includes(`/backend:${version}`))).length, 1);
  assert.equal(f.commands.filter((item) => item.args[1] === "push").length, 3);
});
test("fully completed publication is idempotent and never pushes or promotes again", async (t) => {
  const f = await prepared(t); await publish(options(), f.io);
  f.commands.length = 0; f.io.env.GITHUB_RUN_ATTEMPT = "2";
  await publish(options(), f.io);
  assert.equal(f.commands.some((item) => item.args[1] === "push" || item.args[1] === "imagetools"), false);
});
test("post-push package privacy failure produces no release outputs", async (t) => {
  const f = await prepared(t); f.state.packageVisibility = "public";
  await assert.rejects(publish(options(), f.io), assertCode("RELEASE_PACKAGE_PRIVACY_REJECTED"));
  assert.equal((await readdir(path.join(f.root, "release-artifacts"))).includes("release.json"), false);
  assert.equal((await readdir(f.root)).some((name) => name.startsWith("jeongeum-image-release-")), false);
});
