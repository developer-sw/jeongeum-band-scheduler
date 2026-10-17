// Manual release preparation/publication only. Never reads deployment .env files.
import { createHash, randomUUID } from "node:crypto";
import { createReadStream } from "node:fs";
import { chmod, lstat, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath, pathToFileURL } from "node:url";

export const REPOSITORY = "developer-sw/jeongeum-band-scheduler";
export const SOURCE = `https://github.com/${REPOSITORY}`;
export const COMPONENTS = Object.freeze(["backend", "frontend", "caddy"]);
export const REQUIRED_JOBS = Object.freeze([
  "Backend test and build", "Frontend checks and build", "Production containers with synthetic database",
  "Production image release contract tests",
]);
const SHA = /^[0-9a-f]{40}$/;
const DIGEST = /^sha256:[0-9a-f]{64}$/;
const VERSION = /^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-rc\.(0|[1-9]\d*))?$/;
const MANIFEST_TYPES = ["application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json"];
const ALLOWED_FILES = ["images.tar", "candidate.json", "qa-summary.json", "release.json", "release.env"];
const LABELS = ["org.opencontainers.image.source", "org.opencontainers.image.revision", "org.opencontainers.image.version"];
const REQUIRED_QA_CHECKS = [
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
const validVersion = (value) => typeof value === "string" && value.length <= 80 && VERSION.test(value);

export function fail(code) { throw Object.assign(new Error(code), { releaseCode: code }); }
function insist(condition, code) { if (!condition) fail(code); }
function object(value) { return value !== null && typeof value === "object" && !Array.isArray(value); }
function exactKeys(value, keys, code) {
  insist(object(value) && Object.keys(value).sort().join("|") === [...keys].sort().join("|"), code);
}
export function parseArguments(args) {
  insist(args.length === 5 && ["prepare", "publish"].includes(args[0]) && args[1] === "--version"
    && args[3] === "--directory" && validVersion(args[2]) && args[4] === "release-artifacts", "RELEASE_ARGUMENTS_REJECTED");
  return { phase: args[0], version: args[2], directory: args[4] };
}
export function validateContext(env) {
  insist(env.GITHUB_EVENT_NAME === "workflow_dispatch" && env.GITHUB_REF === "refs/heads/main"
    && env.GITHUB_REPOSITORY === REPOSITORY && SHA.test(env.GITHUB_SHA ?? ""), "RELEASE_MAIN_CONTEXT_REQUIRED");
  insist(/^[1-9]\d*$/.test(env.GITHUB_RUN_ID ?? "") && /^[1-9]\d*$/.test(env.GITHUB_RUN_ATTEMPT ?? "")
    && Number.isSafeInteger(Number(env.GITHUB_RUN_ATTEMPT)), "RELEASE_RUN_CONTEXT_REQUIRED");
  insist(typeof env.GITHUB_TOKEN === "string" && env.GITHUB_TOKEN.length > 0, "RELEASE_TOKEN_REQUIRED");
  insist(/^[A-Za-z0-9][A-Za-z0-9-]*$/.test(env.GITHUB_ACTOR ?? ""), "RELEASE_ACTOR_REJECTED");
  insist(!env.DOCKER_HOST && !env.DOCKER_CONTEXT && !env.ECE_KEYLOG, "RELEASE_DOCKER_OVERRIDE_REJECTED");
  return { sourceSha: env.GITHUB_SHA, runId: env.GITHUB_RUN_ID, runAttempt: Number(env.GITHUB_RUN_ATTEMPT) };
}
async function noLink(target, directory) {
  let stat;
  try { stat = await lstat(target); } catch (error) { if (error.code === "ENOENT") return false; throw error; }
  insist(!stat.isSymbolicLink() && (directory ? stat.isDirectory() : stat.isFile()), "RELEASE_PATH_REJECTED");
  return true;
}
export async function artifactDirectory(repositoryRoot, input, create = false) {
  insist(input === "release-artifacts", "RELEASE_DIRECTORY_REJECTED");
  const root = path.resolve(repositoryRoot);
  insist(await noLink(root, true), "RELEASE_ROOT_MISSING");
  const canonical = await realpath(root);
  insist((process.platform === "win32" ? canonical.toLowerCase() === root.toLowerCase() : canonical === root), "RELEASE_ROOT_REPARSE_REJECTED");
  const directory = path.join(root, "release-artifacts");
  const exists = await noLink(directory, true);
  if (create) {
    insist(!exists, "RELEASE_DIRECTORY_ALREADY_EXISTS");
    await mkdir(directory, { mode: 0o700 });
  } else insist(exists, "RELEASE_DIRECTORY_MISSING");
  return directory;
}
async function jsonRead(target) {
  insist(await noLink(target, false), "RELEASE_INPUT_MISSING");
  const value = await readFile(target);
  insist(value.length <= 1024 * 1024, "RELEASE_METADATA_TOO_LARGE");
  try { return JSON.parse(value.toString("utf8").replace(/^\uFEFF/, "")); } catch { fail("RELEASE_JSON_REJECTED"); }
}
async function output(directory, name, value) {
  insist(ALLOWED_FILES.includes(name) && path.basename(name) === name, "RELEASE_OUTPUT_REJECTED");
  const target = path.join(directory, name);
  const data = typeof value === "string" ? value : `${JSON.stringify(value, null, 2)}\n`;
  if (await noLink(target, false)) {
    insist((await readFile(target, "utf8")) === data, "RELEASE_OUTPUT_ALREADY_EXISTS");
    return;
  }
  await writeFile(target, data, { flag: "wx", mode: 0o600 });
}
export async function archiveFingerprint(target) {
  insist(await noLink(target, false), "RELEASE_ARCHIVE_MISSING");
  const hash = createHash("sha256");
  let size = 0;
  for await (const chunk of createReadStream(target)) { size += chunk.length; hash.update(chunk); }
  insist(size > 0, "RELEASE_ARCHIVE_EMPTY");
  return { name: "images.tar", sha256: hash.digest("hex"), size };
}
export function qaSummary(raw) {
  insist(object(raw) && raw.passed === true && raw.cleanupVerified === true && raw.resourcesKept === false
    && raw.developmentDatabaseAccessed === false && raw.actualPushSent === false, "RELEASE_QA_NOT_PASSED");
  insist(Array.isArray(raw.checks) && raw.checks.length === 57
    && raw.checks.every((item) => typeof item === "string" && /^[a-z][a-z0-9_]{0,119}$/.test(item))
    && JSON.stringify([...raw.checks].sort()) === JSON.stringify([...REQUIRED_QA_CHECKS].sort()), "RELEASE_QA_CHECKS_REJECTED");
  return { passed: true, checkCount: 57, checks: [...raw.checks], cleanupVerified: true, resourcesKept: false,
    developmentDatabaseAccessed: false, actualPushSent: false };
}
function imageRepository(component) {
  insist(COMPONENTS.includes(component), "RELEASE_COMPONENT_REJECTED");
  return `ghcr.io/${REPOSITORY}/${component}`;
}
function expectedLabels(context, version) {
  return { [LABELS[0]]: SOURCE, [LABELS[1]]: context.sourceSha, [LABELS[2]]: version };
}
export function imageSummary(component, raw, context, version) {
  insist(COMPONENTS.includes(component) && object(raw) && DIGEST.test(raw.Id ?? "")
    && raw.Os === "linux" && raw.Architecture === "amd64", "RELEASE_IMAGE_PLATFORM_REJECTED");
  const user = raw.Config?.User;
  insist(typeof user === "string" && /^[a-zA-Z0-9_][a-zA-Z0-9_:-]*$/.test(user)
    && !["root", "0"].includes(user.split(":")[0]), "RELEASE_IMAGE_ROOT_REJECTED");
  const labels = expectedLabels(context, version);
  insist(LABELS.every((key) => raw.Config?.Labels?.[key] === labels[key]), "RELEASE_IMAGE_LABELS_REJECTED");
  return { component, localTag: `jeongeum-${component}:production-qa`, repository: imageRepository(component),
    imageId: raw.Id, platform: "linux/amd64", user, labels };
}
async function inspectImage(io, component, reference, context, version) {
  const result = await io.exec("docker", ["image", "inspect", reference]);
  let images;
  try { images = JSON.parse(result); } catch { fail("RELEASE_IMAGE_INSPECT_REJECTED"); }
  insist(Array.isArray(images) && images.length === 1, "RELEASE_IMAGE_INSPECT_REJECTED");
  return imageSummary(component, images[0], context, version);
}
export async function assertCheckout(io, context) {
  insist((await io.exec("git", ["rev-parse", "HEAD"])).trim() === context.sourceSha, "RELEASE_CHECKOUT_SHA_MISMATCH");
  insist((await io.exec("git", ["status", "--porcelain=v1", "--untracked-files=all"])).trim() === "", "RELEASE_CHECKOUT_DIRTY");
}
export async function assertLocalDocker(io) {
  const endpoint = (await io.exec("docker", ["context", "inspect", "--format", "{{.Endpoints.docker.Host}}"])).trim();
  insist(/^unix:\/\/\/[^\s]+$/.test(endpoint) || /^npipe:\/\/\/\/[.]\/pipe\/[A-Za-z0-9_.-]+$/.test(endpoint), "RELEASE_REMOTE_DOCKER_REJECTED");
  insist((await io.exec("docker", ["info", "--format", "{{.OSType}}"])).trim() === "linux", "RELEASE_LINUX_DOCKER_REQUIRED");
}
function apiUrl(route) {
  insist(route.startsWith(`/repos/${REPOSITORY}/`) || route === `/repos/${REPOSITORY}`
    || route.startsWith("/users/developer-sw/packages/container/") || route.startsWith("/orgs/developer-sw/packages/container/"), "RELEASE_API_ROUTE_REJECTED");
  return `https://api.github.com${route}`;
}
async function github(io, env, route, optional = false) {
  const response = await io.fetch(apiUrl(route), { redirect: "error", headers: {
    Accept: "application/vnd.github+json", Authorization: `Bearer ${env.GITHUB_TOKEN}`,
    "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "jeongeum-production-image-release",
  } });
  if (optional && response.status === 404) return null;
  insist(response.status === 200, response.status === 401 || response.status === 403 ? "RELEASE_GITHUB_AUTH_REJECTED" : "RELEASE_GITHUB_READ_FAILED");
  try { return await response.json(); } catch { fail("RELEASE_GITHUB_JSON_REJECTED"); }
}
export async function githubGate(io, env, context) {
  const repo = await github(io, env, `/repos/${REPOSITORY}`);
  insist(repo.full_name === REPOSITORY && repo.default_branch === "main" && ["User", "Organization"].includes(repo.owner?.type), "RELEASE_REPOSITORY_REJECTED");
  const main = await github(io, env, `/repos/${REPOSITORY}/git/ref/heads/main`);
  insist(main.object?.sha === context.sourceSha, "RELEASE_MAIN_SHA_MOVED");
  const runs = await github(io, env, `/repos/${REPOSITORY}/actions/workflows/ci.yaml/runs?event=push&branch=main&head_sha=${context.sourceSha}&per_page=100`);
  insist(Array.isArray(runs.workflow_runs), "RELEASE_CI_RESPONSE_REJECTED");
  const run = runs.workflow_runs.filter((item) => item.event === "push" && item.head_branch === "main" && item.head_sha === context.sourceSha)
    .sort((a, b) => b.id - a.id)[0];
  insist(run && Number.isSafeInteger(run.id) && run.status === "completed" && run.conclusion === "success"
    && run.path === ".github/workflows/ci.yaml", "RELEASE_CI_SUCCESS_REQUIRED");
  const jobs = await github(io, env, `/repos/${REPOSITORY}/actions/runs/${run.id}/jobs?filter=latest&per_page=100`);
  insist(Array.isArray(jobs.jobs), "RELEASE_CI_JOBS_REJECTED");
  for (const name of REQUIRED_JOBS) {
    const matches = jobs.jobs.filter((job) => job.name === name);
    insist(matches.length === 1 && matches[0].status === "completed" && matches[0].conclusion === "success", "RELEASE_CI_JOBS_NOT_SUCCESSFUL");
  }
  return { ownerType: repo.owner.type, proof: { runId: run.id, url: `${SOURCE}/actions/runs/${run.id}`,
    sourceSha: context.sourceSha, event: "push", branch: "main", workflow: ".github/workflows/ci.yaml", jobs: [...REQUIRED_JOBS] } };
}
function compareImages(actual, expected) {
  insist(JSON.stringify(actual) === JSON.stringify(expected), "RELEASE_TESTED_IMAGE_CHANGED");
}
export async function prepare(options, io) {
  const context = validateContext(io.env);
  await assertCheckout(io, context);
  await assertLocalDocker(io);
  const gate = await githubGate(io, io.env, context);
  // Validate the fixed output path before building, but create it only after QA passes.
  const root = path.resolve(io.root);
  const target = path.join(root, "release-artifacts");
  insist(options.directory === "release-artifacts" && !(await noLink(target, true)), "RELEASE_DIRECTORY_ALREADY_EXISTS");
  const labels = expectedLabels(context, options.version);
  for (const component of COMPONENTS) {
    const buildContext = component === "caddy" ? "deploy/caddy" : component;
    const args = ["buildx", "build", "--load", "--platform", "linux/amd64", "--provenance=false", "--sbom=false"];
    for (const [name, value] of Object.entries(labels)) args.push("--label", `${name}=${value}`);
    if (component === "frontend") args.push("--build-arg", "APP_API_ORIGIN=http://backend:8080");
    args.push("--tag", `jeongeum-${component}:production-qa`, buildContext);
    await io.exec("docker", args, { timeout: 60 * 60 * 1000 });
  }
  const before = [];
  for (const component of COMPONENTS) before.push(await inspectImage(io, component, `jeongeum-${component}:production-qa`, context, options.version));
  const local = path.join(root, ".local");
  if (!(await noLink(local, true))) await mkdir(local, { mode: 0o700 });
  const qaPath = path.join(local, `production-image-release-qa-${randomUUID()}.json`);
  await io.exec("pwsh", ["-NoProfile", "-File", "scripts/Test-ProductionContainers.ps1", "-SkipBuild", "-ResultPath", qaPath], { timeout: 30 * 60 * 1000 });
  const qa = qaSummary(await jsonRead(qaPath));
  for (const image of before) compareImages(await inspectImage(io, image.component, image.localTag, context, options.version), image);
  await assertCheckout(io, context);
  await githubGate(io, io.env, context);
  const directory = await artifactDirectory(root, options.directory, true);
  await io.exec("docker", ["image", "save", "--output", path.join(directory, "images.tar"), ...before.map((image) => image.localTag)], { timeout: 10 * 60 * 1000 });
  const archive = await archiveFingerprint(path.join(directory, "images.tar"));
  const candidate = { schemaVersion: 1, repository: REPOSITORY, sourceSha: context.sourceSha, version: options.version,
    runId: context.runId, preparedAttempt: context.runAttempt, platform: "linux/amd64", ci: gate.proof,
    images: before, archive, qaSha256: createHash("sha256").update(`${JSON.stringify(qa, null, 2)}\n`).digest("hex") };
  await output(directory, "qa-summary.json", qa);
  await output(directory, "candidate.json", candidate);
  return { phase: "prepare", sourceSha: context.sourceSha, version: options.version, imageCount: 3 };
}
export function validateCandidate(candidate, qa, context, version) {
  exactKeys(candidate, ["schemaVersion", "repository", "sourceSha", "version", "runId", "preparedAttempt", "platform", "ci", "images", "archive", "qaSha256"], "RELEASE_CANDIDATE_FIELDS_REJECTED");
  insist(candidate.schemaVersion === 1 && candidate.repository === REPOSITORY && candidate.sourceSha === context.sourceSha
    && candidate.version === version && validVersion(version) && candidate.runId === context.runId
    && Number.isSafeInteger(candidate.preparedAttempt) && candidate.preparedAttempt > 0 && candidate.preparedAttempt <= context.runAttempt
    && candidate.platform === "linux/amd64", "RELEASE_CANDIDATE_CONTEXT_REJECTED");
  exactKeys(candidate.ci, ["runId", "url", "sourceSha", "event", "branch", "workflow", "jobs"], "RELEASE_CI_PROOF_REJECTED");
  insist(Number.isSafeInteger(candidate.ci.runId) && candidate.ci.runId > 0
    && candidate.ci.url === `${SOURCE}/actions/runs/${candidate.ci.runId}` && candidate.ci.sourceSha === context.sourceSha
    && candidate.ci.event === "push" && candidate.ci.branch === "main" && candidate.ci.workflow === ".github/workflows/ci.yaml"
    && JSON.stringify(candidate.ci.jobs) === JSON.stringify(REQUIRED_JOBS), "RELEASE_CI_PROOF_REJECTED");
  exactKeys(candidate.archive, ["name", "sha256", "size"], "RELEASE_ARCHIVE_PROOF_REJECTED");
  insist(candidate.archive.name === "images.tar" && /^[a-f0-9]{64}$/.test(candidate.archive.sha256)
    && Number.isSafeInteger(candidate.archive.size) && candidate.archive.size > 0, "RELEASE_ARCHIVE_PROOF_REJECTED");
  exactKeys(qa, ["passed", "checkCount", "checks", "cleanupVerified", "resourcesKept", "developmentDatabaseAccessed", "actualPushSent"], "RELEASE_QA_FIELDS_REJECTED");
  insist(JSON.stringify(qaSummary(qa)) === JSON.stringify(qa), "RELEASE_QA_SUMMARY_REJECTED");
  insist(candidate.qaSha256 === createHash("sha256").update(`${JSON.stringify(qa, null, 2)}\n`).digest("hex"), "RELEASE_QA_HASH_MISMATCH");
  insist(Array.isArray(candidate.images) && candidate.images.length === 3, "RELEASE_IMAGES_REJECTED");
  for (let index = 0; index < COMPONENTS.length; index++) {
    const image = candidate.images[index];
    exactKeys(image, ["component", "localTag", "repository", "imageId", "platform", "user", "labels"], "RELEASE_IMAGE_FIELDS_REJECTED");
    exactKeys(image.labels, LABELS, "RELEASE_IMAGE_LABEL_FIELDS_REJECTED");
    const validated = imageSummary(COMPONENTS[index], { Id: image.imageId, Os: "linux", Architecture: "amd64", Config: { User: image.user, Labels: image.labels } }, context, version);
    compareImages(image, validated);
  }
}
export async function packageGate(io, env, ownerType, component, allowMissing = true) {
  insist(COMPONENTS.includes(component) && ["User", "Organization"].includes(ownerType), "RELEASE_COMPONENT_REJECTED");
  const scope = ownerType === "Organization" ? "orgs" : "users";
  const packageName = encodeURIComponent(`jeongeum-band-scheduler/${component}`);
  const metadata = await github(io, env, `/${scope}/developer-sw/packages/container/${packageName}`, true);
  if (metadata === null) { insist(allowMissing, "RELEASE_PACKAGE_READBACK_REQUIRED"); return; }
  insist(metadata.visibility === "private" && metadata.repository?.full_name === REPOSITORY, "RELEASE_PACKAGE_PRIVACY_REJECTED");
}
export async function registryToken(io, env, component) {
  insist(COMPONENTS.includes(component), "RELEASE_COMPONENT_REJECTED");
  const repository = `${REPOSITORY}/${component}`;
  const target = `https://ghcr.io/token?service=ghcr.io&scope=${encodeURIComponent(`repository:${repository}:pull`)}`;
  const response = await io.fetch(target, { redirect: "error", headers: {
    Authorization: `Basic ${Buffer.from(`${env.GITHUB_ACTOR}:${env.GITHUB_TOKEN}`).toString("base64")}`,
  } });
  insist(response.status === 200, "RELEASE_REGISTRY_AUTH_REJECTED");
  let data;
  try { data = await response.json(); } catch { fail("RELEASE_REGISTRY_TOKEN_REJECTED"); }
  const token = data.token ?? data.access_token;
  insist(typeof token === "string" && token.length > 0, "RELEASE_REGISTRY_TOKEN_REJECTED");
  return token;
}
export async function readManifest(io, component, reference, token) {
  insist(COMPONENTS.includes(component) && (DIGEST.test(reference) || /^sha-[0-9a-f]{40}$/.test(reference) || validVersion(reference)), "RELEASE_REGISTRY_REF_REJECTED");
  const response = await io.fetch(`https://ghcr.io/v2/${REPOSITORY}/${component}/manifests/${reference}`, { redirect: "error", headers: {
    Accept: MANIFEST_TYPES.join(", "), Authorization: `Bearer ${token}`,
  } });
  if (response.status === 404) return null;
  insist(response.status === 200, response.status === 401 || response.status === 403 ? "RELEASE_REGISTRY_AUTH_REJECTED" : "RELEASE_REGISTRY_READ_FAILED");
  const raw = Buffer.from(await response.arrayBuffer());
  insist(raw.length <= 4 * 1024 * 1024, "RELEASE_REGISTRY_MANIFEST_TOO_LARGE");
  const digest = `sha256:${createHash("sha256").update(raw).digest("hex")}`;
  insist(response.headers.get("docker-content-digest") === digest, "RELEASE_REGISTRY_DIGEST_MISMATCH");
  insist(!DIGEST.test(reference) || reference === digest, "RELEASE_REGISTRY_DIGEST_REF_MISMATCH");
  let manifest;
  try { manifest = JSON.parse(raw.toString("utf8")); } catch { fail("RELEASE_REGISTRY_MANIFEST_REJECTED"); }
  insist(manifest.schemaVersion === 2 && MANIFEST_TYPES.includes(manifest.mediaType)
    && DIGEST.test(manifest.config?.digest ?? "") && !manifest.manifests, "RELEASE_REGISTRY_SINGLE_MANIFEST_REQUIRED");
  return { digest, config: manifest.config.digest };
}
async function currentManifest(io, component, reference) {
  // Registry bearer tokens are short-lived. Never retain a preflight token
  // across docker load/push/pull or reuse its pre-creation permission claims.
  const token = await registryToken(io, io.env, component);
  return readManifest(io, component, reference, token);
}
export function validateExisting(sha, version, imageId) {
  if (sha) insist(sha.config === imageId, "RELEASE_SHA_TAG_CONFLICT");
  if (version) insist(sha && version.digest === sha.digest && version.config === imageId, "RELEASE_VERSION_TAG_CONFLICT");
}
async function temporaryAuth(io) {
  const parent = path.resolve(io.tempRoot ?? os.tmpdir());
  insist(await noLink(parent, true), "RELEASE_TEMP_ROOT_REJECTED");
  const directory = await mkdtemp(path.join(parent, "jeongeum-image-release-"));
  await chmod(directory, 0o700);
  return { directory, async cleanup() {
    insist(path.dirname(directory) === parent && path.basename(directory).startsWith("jeongeum-image-release-")
      && await noLink(directory, true) && await realpath(directory) === directory, "RELEASE_AUTH_CLEANUP_REJECTED");
    await rm(directory, { recursive: true, force: false });
  } };
}
export async function publish(options, io) {
  const context = validateContext(io.env);
  await assertCheckout(io, context);
  const directory = await artifactDirectory(io.root, options.directory);
  const entries = await readdir(directory);
  insist(entries.every((entry) => ALLOWED_FILES.includes(entry)) && ["images.tar", "candidate.json", "qa-summary.json"].every((name) => entries.includes(name)), "RELEASE_ARTIFACT_CONTENTS_REJECTED");
  const candidate = await jsonRead(path.join(directory, "candidate.json"));
  const qa = await jsonRead(path.join(directory, "qa-summary.json"));
  validateCandidate(candidate, qa, context, options.version);
  insist(JSON.stringify(await archiveFingerprint(path.join(directory, "images.tar"))) === JSON.stringify(candidate.archive), "RELEASE_ARCHIVE_HASH_MISMATCH");
  await assertLocalDocker(io);
  const gate = await githubGate(io, io.env, context);
  const states = [];
  // Preflight every component before the first registry write.
  for (const image of candidate.images) {
    await packageGate(io, io.env, gate.ownerType, image.component);
    const sha = await currentManifest(io, image.component, `sha-${context.sourceSha}`);
    const version = await currentManifest(io, image.component, options.version);
    validateExisting(sha, version, image.imageId);
    states.push({ image, sha, version });
  }
  const auth = await temporaryAuth(io);
  const childEnv = { DOCKER_CONFIG: auth.directory };
  try {
    await io.exec("docker", ["login", "ghcr.io", "--username", io.env.GITHUB_ACTOR, "--password-stdin"], { input: `${io.env.GITHUB_TOKEN}\n`, env: childEnv });
    await io.exec("docker", ["image", "load", "--input", path.join(directory, "images.tar")], { env: childEnv, timeout: 10 * 60 * 1000 });
    for (const { image } of states) compareImages(await inspectImage(io, image.component, image.localTag, context, options.version), image);
    await githubGate(io, io.env, context);
    for (const state of states) {
      const { image } = state;
      // A concurrent publication must not turn a previously absent ref into an overwrite.
      const current = await currentManifest(io, image.component, `sha-${context.sourceSha}`);
      validateExisting(current, null, image.imageId);
      if (!current) {
        const shaRef = `${image.repository}:sha-${context.sourceSha}`;
        await io.exec("docker", ["image", "tag", image.localTag, shaRef], { env: childEnv });
        await io.exec("docker", ["image", "push", shaRef], { env: childEnv, timeout: 15 * 60 * 1000 });
      }
      state.sha = await currentManifest(io, image.component, `sha-${context.sourceSha}`);
      insist(state.sha?.config === image.imageId, "RELEASE_PUSH_READBACK_MISMATCH");
      await io.exec("docker", ["image", "pull", `${image.repository}@${state.sha.digest}`], { env: childEnv, timeout: 10 * 60 * 1000 });
      compareImages(await inspectImage(io, image.component, `${image.repository}@${state.sha.digest}`, context, options.version), image);
    }
    await githubGate(io, io.env, context);
    for (const state of states) {
      const { image, sha } = state;
      const current = await currentManifest(io, image.component, options.version);
      validateExisting(sha, current, image.imageId);
      if (!current) await io.exec("docker", ["buildx", "imagetools", "create", "--prefer-index=false", "--tag", `${image.repository}:${options.version}`, `${image.repository}@${sha.digest}`], { env: childEnv, timeout: 10 * 60 * 1000 });
      const promoted = await currentManifest(io, image.component, options.version);
      insist(promoted?.digest === sha.digest && promoted.config === image.imageId, "RELEASE_PROMOTION_READBACK_MISMATCH");
    }
    // New packages must also remain private and linked after their first push.
    for (const { image } of states) await packageGate(io, io.env, gate.ownerType, image.component, false);
    const release = { schemaVersion: 1, repository: REPOSITORY, sourceSha: context.sourceSha, version: options.version,
      runId: context.runId, ci: candidate.ci, images: states.map(({ image, sha }) => ({ component: image.component,
        imageId: image.imageId, digest: sha.digest, reference: `${image.repository}@${sha.digest}` })) };
    const environment = states.map(({ image, sha }) => `${image.component.toUpperCase()}_IMAGE=${image.repository}@${sha.digest}`).join("\n") + "\n";
    await output(directory, "release.json", release);
    await output(directory, "release.env", environment);
    return { phase: "publish", sourceSha: context.sourceSha, version: options.version, imageCount: 3 };
  } finally { await auth.cleanup(); }
}
export function defaultIo(root, env = process.env) {
  return { root, env, fetch: (url, options) => fetch(url, { ...options, signal: AbortSignal.timeout(30000) }),
    exec(file, args, options = {}) {
      return new Promise((resolve, reject) => {
        const childEnvironment = { ...env, ...options.env };
        delete childEnvironment.GITHUB_TOKEN;
        const child = spawn(file, args, { cwd: root, env: childEnvironment, shell: false, windowsHide: true, stdio: ["pipe", "pipe", "pipe"] });
        const chunks = [];
        let size = 0;
        const timer = setTimeout(() => { child.kill(); reject(Object.assign(new Error(), { releaseCode: "RELEASE_PROCESS_TIMEOUT" })); }, options.timeout ?? 120000);
        child.stdout.on("data", (chunk) => { size += chunk.length; if (size <= 8 * 1024 * 1024) chunks.push(chunk); });
        // Consume but never print or retain possibly sensitive stderr.
        child.stderr.resume();
        child.on("error", () => { clearTimeout(timer); reject(Object.assign(new Error(), { releaseCode: "RELEASE_PROCESS_START_FAILED" })); });
        child.on("close", (code) => { clearTimeout(timer); code === 0 && size <= 8 * 1024 * 1024 ? resolve(Buffer.concat(chunks).toString("utf8"))
          : reject(Object.assign(new Error(), { releaseCode: "RELEASE_PROCESS_FAILED" })); });
        child.stdin.on("error", () => {});
        child.stdin.end(options.input ?? "");
      });
    } };
}
export async function main(args = process.argv.slice(2), io = defaultIo(path.resolve(path.dirname(fileURLToPath(import.meta.url)), ".."))) {
  const options = parseArguments(args);
  return options.phase === "prepare" ? prepare(options, io) : publish(options, io);
}
if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  main().then((result) => process.stdout.write(`Release ${result.phase} completed: ${result.imageCount} images.\n`)).catch((error) => {
    process.stderr.write(`${/^RELEASE_[A-Z0-9_]+$/.test(error.releaseCode ?? "") ? error.releaseCode : "RELEASE_UNEXPECTED_FAILURE"}\n`);
    process.exitCode = 1;
  });
}
