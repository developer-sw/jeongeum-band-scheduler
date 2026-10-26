// Isolated Linux file-permission checks. No development env, DB, secrets or Docker socket mounts.
import { spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { lstat, mkdir, readFile, writeFile } from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const suffix = randomBytes(6).toString("hex");
const prefix = `jeongeum-secret-qa-${suffix}`;
const label = "jeongeum.secret-qa";
const directory = path.join(root, ".local", `runtime-secrets-qa-${suffix}`);
const pgImage = "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826";
const backendImage = "ghcr.io/developer-sw/jeongeum-band-scheduler/backend@sha256:7dd69db8f16bfb7282b4bed2a2ccf41b58f2e8f59a659e10f9a9ab82510e0a61";
const pythonImage = "docker.io/library/python:3.13-slim-bookworm@sha256:4cea12ab7d78a3312c85f8cab19b6971d4fe9704da25443986c0bc20a6678850";
const checks = [];
const containers = new Map();
const volumes = new Set();
let mountpoint;
let helper;
const report = { passed: false, checks, cleanupVerified: false, developmentDatabaseAccessed: false,
  actualPushSent: false, backendScope: "rc.3 entrypoint with mock java; Spring/Flyway not started", linuxNativeFiles: true };
const fail = (code) => { throw Object.assign(new Error(code), { qaCode: code }); };
const need = (condition, code) => { if (!condition) fail(code); };
function docker(args, code = "DOCKER_COMMAND_FAILED", { input, failed = false, timeout = 120000 } = {}) {
  const env = { ...process.env };
  for (const name of Object.keys(env)) if (/^(APP_|JEONGEUM_|POSTGRES_|VAPID_|COMPOSE_|BACKEND_IMAGE|FRONTEND_IMAGE|CADDY_IMAGE|ACME_EMAIL)/.test(name)) delete env[name];
  const result = spawnSync("docker", args, { cwd: root, env, input, encoding: "utf8", timeout, maxBuffer: 2 * 1024 * 1024, windowsHide: true });
  need(!result.error && (failed || result.status === 0), code);
  return { status: result.status, stdout: (result.stdout ?? "").trim(), stderr: result.stderr ?? "" };
}
const json = (args, code) => JSON.parse(docker(args, code).stdout);
const check = (code, condition = true) => { need(condition, code); checks.push(code); };
function volume(name) {
  need(name.startsWith(prefix + "-"), "VOLUME_NAME_REJECTED");
  need(docker(["volume", "inspect", name], "VOLUME_QUERY", { failed: true }).status !== 0, "VOLUME_EXISTS");
  docker(["volume", "create", "--label", `${label}=${suffix}`, name]); volumes.add(name);
  const info = json(["volume", "inspect", name])[0];
  need(info.Name === name && info.Labels?.[label] === suffix && info.Driver === "local", "VOLUME_IDENTITY_REJECTED");
  return info;
}
function create(name, options, image, command = []) {
  need(name.startsWith(prefix + "-") && !containers.has(name), "CONTAINER_NAME_REJECTED");
  need(docker(["container", "inspect", name], "CONTAINER_QUERY", { failed: true }).status !== 0, "CONTAINER_EXISTS");
  const id = docker(["create", "--name", name, "--label", `${label}=${suffix}`, ...options, image, ...command]).stdout;
  need(/^[a-f0-9]{64}$/.test(id), "CONTAINER_ID_REJECTED"); containers.set(name, id);
  guard(id);
  return id;
}
function guard(id) {
  need([...containers.values()].includes(id), "CONTAINER_NOT_OWNED");
  const info = json(["inspect", id])[0];
  need(info.Id === id && info.Config.Labels?.[label] === suffix && info.Name.startsWith("/" + prefix + "-"), "CONTAINER_IDENTITY_REJECTED");
  need(!info.HostConfig.Privileged && !info.HostConfig.Binds?.length && info.HostConfig.NetworkMode === "none", "CONTAINER_ISOLATION_REJECTED");
  for (const item of info.Mounts) {
    if (item.Type === "volume") need(volumes.has(item.Name), "EXISTING_VOLUME_REJECTED");
    else if (item.Type === "bind") need(mountpoint && item.Source.startsWith(mountpoint + "/") && item.RW === false, "EXISTING_OR_WRITABLE_BIND_REJECTED");
    else need(item.Type === "tmpfs", "MOUNT_TYPE_REJECTED");
  }
  return info;
}
const bind = (source, target) => ["--mount", `type=bind,source=${mountpoint}/${source},target=${target},readonly`];
const hardened = ["--network", "none", "--memory", "128m", "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m"];
function exec(id, args, code, options = {}) { guard(id); return docker(["exec", id, ...args], code, options); }
function startExit(id, code, expected = 0) {
  guard(id); docker(["start", id], code);
  const result = docker(["wait", id], code, { timeout: 40000 }).stdout;
  need(result === String(expected), code);
}
async function ready(id) {
  for (let n = 0; n < 40; n++) {
    const info = guard(id); need(info.State.Running, "PG_STOPPED_DURING_INIT");
    if (docker(["exec", id, "pg_isready", "-q", "-h", "127.0.0.1", "-U", "postgres"], "PG_READY_QUERY", { failed: true }).status === 0) return;
    await new Promise(resolve => setTimeout(resolve, 500));
  }
  fail("PG_READY_TIMEOUT");
}
function pgSql(id, sql, code) {
  guard(id);
  return docker(["exec", "-i", "--user", "postgres", id, "sh", "-c", "PGPASSWORD=\"$(cat /run/secrets/app_db_password)\" psql --no-password -h 127.0.0.1 -U \"$APP_DB_USERNAME\" -d \"$POSTGRES_DB\" --set ON_ERROR_STOP=1 -At"], code, { input: sql + "\n" }).stdout;
}
try {
  need(process.argv.length === 2, "ARGUMENTS_REJECTED");
  need(!process.env.DOCKER_HOST && !process.env.DOCKER_CONTEXT && !process.env.DOCKER_TLS_VERIFY, "REMOTE_DOCKER_ENV_REJECTED");
  const context = docker(["context", "show"]).stdout;
  need(["desktop-linux", "default"].includes(context), "DOCKER_CONTEXT_REJECTED");
  const endpoint = json(["context", "inspect", context])[0]?.Endpoints?.docker?.Host;
  need(typeof endpoint === "string" && /^(unix:\/\/\/|npipe:\/\/\/\/\.\/pipe\/)/.test(endpoint), "REMOTE_DOCKER_ENDPOINT_REJECTED");
  need(docker(["info", "--format", "{{.OSType}}"]).stdout === "linux", "LINUX_DOCKER_REQUIRED");
  const localDirectory = path.join(root, ".local");
  try { await mkdir(localDirectory, { mode: 0o700 }); }
  catch (error) { if (error.code !== "EEXIST") throw error; }
  const localInfo = await lstat(localDirectory);
  need(localInfo.isDirectory() && !localInfo.isSymbolicLink(), "QA_PARENT_REJECTED");
  await mkdir(directory, { recursive: false, mode: 0o700 });
  for (const image of [pgImage, backendImage, pythonImage]) {
    if (docker(["image", "inspect", image], "IMAGE_QUERY", { failed: true }).status !== 0) docker(["pull", "--platform", "linux/amd64", image], "PINNED_IMAGE_PULL_FAILED", { timeout: 300000 });
    const imageInfo = json(["image", "inspect", image])[0];
    need(imageInfo.Os === "linux" && imageInfo.Architecture === "amd64", "IMAGE_PLATFORM_REJECTED");
  }
  check("local_linux_daemon_and_pinned_images");
  const filesVolume = volume(prefix + "-files");
  mountpoint = filesVolume.Mountpoint;
  need(/^\/var\/lib\/docker\/volumes\/[a-z0-9-]+\/_data$/.test(mountpoint), "VOLUME_MOUNTPOINT_REJECTED");
  const pgNoData = ["--network", "none", "--memory", "512m", "--tmpfs", "/var/lib/postgresql/data:rw,noexec,nosuid,size=128m"];
  const uidProbe = create(prefix + "-uid", [...pgNoData, "--entrypoint", "sh"], pgImage, ["-c", "set -eu; id -u postgres; id -g postgres"]);
  startExit(uidProbe, "PG_UID_PROBE_FAILED");
  const identity = docker(["logs", uidProbe]).stdout.split(/\s+/);
  need(identity.length === 2 && identity.every(v => /^[1-9][0-9]{0,5}$/.test(v)), "PG_UID_REJECTED");
  const [pgUid, pgGid] = identity;
  need(pgUid !== "10001", "PG_BACKEND_UID_COLLISION");
  report.postgresUid = Number(pgUid); report.postgresGid = Number(pgGid);
  const entrypointFile = path.join(directory, "postgres-entrypoint.private.sh");
  docker(["cp", `${uidProbe}:/usr/local/bin/docker-entrypoint.sh`, entrypointFile]);
  const entrypoint = await readFile(entrypointFile, "utf8");
  const mainIndex = entrypoint.indexOf("_main()");
  const setupIndex = entrypoint.indexOf("docker_setup_env", mainIndex);
  const gosuIndex = entrypoint.indexOf("exec gosu postgres", mainIndex);
  check("pinned_pg_root_file_env_before_gosu", mainIndex > 0 && setupIndex > mainIndex && gosuIndex > setupIndex
    && /docker_setup_env\(\)[\s\S]*?file_env 'POSTGRES_PASSWORD'/.test(entrypoint) && entrypoint.includes('unset "$fileVar"'));
  helper = create(prefix + "-helper", ["--network", "none", "--memory", "256m", "--mount", `type=volume,source=${filesVolume.Name},target=/qa`, "--entrypoint", "sleep"], pythonImage, ["infinity"]);
  docker(["cp", path.join(root, "scripts/runtime-secrets.py"), `${helper}:/runtime-secrets.py`]);
  docker(["cp", path.join(root, "scripts/runtime-secrets.test.py"), `${helper}:/runtime-secrets.test.py`]);
  docker(["cp", path.join(root, "deploy/postgres/init-app.sh"), `${helper}:/init-app.sh`]);
  docker(["start", helper]);
  const unit = exec(helper, ["python3", "-I", "-B", "/runtime-secrets.test.py"], "LINUX_ROOT_HELPER_TESTS_FAILED");
  const count = unit.stderr.match(/Ran ([0-9]+) tests?/);
  need(count && !/skipped=/.test(unit.stderr), "LINUX_ROOT_HELPER_TESTS_SKIPPED");
  report.linuxHelperTests = Number(count[1]); check("linux_root_helper_unit_tests_without_skips");
  const setup = `import os,pathlib,secrets\np=pathlib.Path('/qa');p.chmod(0o700);m=p/'master';m.mkdir(mode=0o700)\nfor n in ['postgres-admin-password.txt','app-db-password.txt','vapid-public-key.txt','vapid-private-key.txt','vapid-subject.txt']:\n f=m/n;f.write_text(secrets.token_urlsafe(32)+'\\n');f.chmod(0o600)\nb=p/'probe-bin';b.mkdir(mode=0o755);(b/'java').write_text(${JSON.stringify("#!/bin/sh\nset -eu\n[ \"$(id -u)\" = 10001 ]\n[ \"$APP_DB_PASSWORD\" = \"$(cat /run/secrets/app_db_password)\" ]\n[ \"$APP_VAPID_PUBLIC_KEY\" = \"$(cat /run/secrets/vapid_public_key)\" ]\n[ \"$APP_VAPID_PRIVATE_KEY\" = \"$(cat /run/secrets/vapid_private_key)\" ]\n[ \"$APP_VAPID_SUBJECT\" = \"$(cat /run/secrets/vapid_subject)\" ]\nfor f in /run/secrets/*; do [ \"$(stat -c '%u:%g:%a' \"$f\")\" = 10001:10001:400 ]; [ ! -w \"$f\" ]; if (printf qa-write-probe >> \"$f\") 2>/dev/null; then exit 1; fi; done\n")});(b/'java').chmod(0o555)\nq=p/'init-app.sh';q.write_bytes(pathlib.Path('/init-app.sh').read_bytes().replace(b'\\r\\n',b'\\n'));q.chmod(0o555)\n`;
  exec(helper, ["python3", "-c", setup], "FAKE_FIXTURE_SETUP_FAILED");
  const helperArgs = ["/runtime-secrets.py", "prepare", "--source-directory", "/qa/master", "--destination-directory", "/qa/runtime", "--postgres-uid", pgUid, "--postgres-gid", pgGid];
  exec(helper, ["python3", ...helperArgs], "RUNTIME_PREPARE_FAILED");
  exec(helper, ["python3", ...helperArgs.map(v => v === "prepare" ? "check" : v)], "RUNTIME_CHECK_FAILED");
  check("linux_helper_prepares_and_checks_six_consumer_files");

  const env = {
    COMPOSE_PROJECT_NAME: prefix, APP_DOMAIN: "example.invalid", ACME_EMAIL: "operator@example.invalid",
    BACKEND_IMAGE: backendImage, FRONTEND_IMAGE: "example.invalid/frontend:unused", CADDY_IMAGE: "example.invalid/caddy:unused",
    POSTGRES_DB: `qa_${suffix}`, APP_DB_USERNAME: `qa_${suffix}`, APP_NETWORK_SUBNET: "172.30.249.0/24", CADDY_INTERNAL_IP: "172.30.249.10", APP_TRUSTED_PROXY_REGEX: "172[.]30[.]249[.]10",
    POSTGRES_ADMIN_PASSWORD_FILE: `${mountpoint}/runtime/postgres-admin-password.txt`, POSTGRES_APP_DB_PASSWORD_FILE: `${mountpoint}/runtime/postgres-app-db-password.txt`,
    APP_DB_PASSWORD_FILE: `${mountpoint}/runtime/app-db-password.txt`, VAPID_PUBLIC_KEY_FILE: `${mountpoint}/runtime/vapid-public-key.txt`, VAPID_PRIVATE_KEY_FILE: `${mountpoint}/runtime/vapid-private-key.txt`, VAPID_SUBJECT_FILE: `${mountpoint}/runtime/vapid-subject.txt`,
  };
  const envFile = path.join(directory, "qa.env");
  await writeFile(envFile, Object.entries(env).map(([key, value]) => `${key}=${value}`).join("\n") + "\n", { mode: 0o600 });
  const config = json(["compose", "--env-file", envFile, "-f", "deploy/compose.production.yaml", "-f", "deploy/compose.runtime-secrets.yaml", "config", "--format", "json"], "COMPOSE_RENDER_FAILED");
  const mounted = service => config.services[service].secrets ?? [];
  // Compose versions render an implicit target as either a basename or an absolute path.
  const secretTarget = secret => (secret.target ?? secret.source).startsWith("/")
    ? secret.target : `/run/secrets/${secret.target ?? secret.source}`;
  check("compose_pg_two_backend_four_other_services_zero", mounted("postgres").length === 2 && mounted("backend").length === 4 && mounted("frontend").length === 0 && mounted("caddy").length === 0);
  check("compose_pg_app_source_replaced_without_duplicate_target", mounted("postgres").some(v => v.source === "postgres_app_db_password" && secretTarget(v) === "/run/secrets/app_db_password") && !mounted("postgres").some(v => v.source === "app_db_password"));
  check("compose_backend_source_retained", mounted("backend").some(v => v.source === "app_db_password" && secretTarget(v) === "/run/secrets/app_db_password"));
  const secretPaths = { postgres_admin_password: env.POSTGRES_ADMIN_PASSWORD_FILE, postgres_app_db_password: env.POSTGRES_APP_DB_PASSWORD_FILE,
    app_db_password: env.APP_DB_PASSWORD_FILE, vapid_public_key: env.VAPID_PUBLIC_KEY_FILE, vapid_private_key: env.VAPID_PRIVATE_KEY_FILE, vapid_subject: env.VAPID_SUBJECT_FILE };
  check("compose_each_secret_uses_its_expected_consumer_file", Object.entries(secretPaths).every(([name, file]) => typeof config.secrets[name]?.file === "string" && path.resolve(config.secrets[name].file) === path.resolve(file))
    && path.resolve(config.secrets.postgres_app_db_password.file) !== path.resolve(config.secrets.app_db_password.file));
  check("compose_no_duplicate_secret_targets", ["postgres", "backend"].every(name => new Set(mounted(name).map(secretTarget)).size === mounted(name).length));
  report.composeScope = process.platform === "win32" ? "Windows config render plus daemon-native Linux file bind checks" : "Linux config render plus daemon-native Linux file bind checks";

  const appBind = bind("runtime/app-db-password.txt", "/run/secrets/app_db_password");
  const backendOpts = [...hardened, ...appBind, ...bind("runtime/vapid-public-key.txt", "/run/secrets/vapid_public_key"), ...bind("runtime/vapid-private-key.txt", "/run/secrets/vapid_private_key"), ...bind("runtime/vapid-subject.txt", "/run/secrets/vapid_subject"), ...bind("probe-bin/java", "/qa-bin/java"),
    "--env", "PATH=/qa-bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "--env", "SPRING_PROFILES_ACTIVE=production", "--env", "APP_DB_URL=jdbc:postgresql://unused/qa", "--env", "APP_DB_USERNAME=qa_unused", "--env", "APP_DB_PASSWORD_FILE=/run/secrets/app_db_password", "--env", "APP_VAPID_PUBLIC_KEY_FILE=/run/secrets/vapid_public_key", "--env", "APP_VAPID_PRIVATE_KEY_FILE=/run/secrets/vapid_private_key", "--env", "APP_VAPID_SUBJECT_FILE=/run/secrets/vapid_subject", "--env", "APP_TRUSTED_PROXY_REGEX=127[.]0[.]0[.]1", "--env", "JEONGEUM_PUSH_ENABLED=true"];
  const backend = create(prefix + "-backend", backendOpts, backendImage);
  startExit(backend, "BACKEND_ENTRYPOINT_READ_FAILED");
  check("rc3_backend_nonroot_entrypoint_reads_only_own_0400_files");
  docker(["restart", backend]); need(docker(["wait", backend]).stdout === "0", "BACKEND_RESTART_FAILED");
  check("backend_restart_reloads_same_files");
  const deny = create(prefix + "-wrong-user", [...hardened, ...appBind, "--user", "10002:10002", "--entrypoint", "sh"], backendImage, ["-c", "set -eu; test ! -r /run/secrets/app_db_password; test ! -w /run/secrets/app_db_password; if (printf qa-write-probe >> /run/secrets/app_db_password) 2>/dev/null; then exit 1; fi"]);
  startExit(deny, "OTHER_UID_CAN_READ_SECRET"); check("other_uid_read_and_write_denied");
  const wrongBackend = create(prefix + "-root-only", [...hardened, ...bind("runtime/postgres-admin-password.txt", "/run/secrets/app_db_password"), "--env", "APP_DB_PASSWORD_FILE=/run/secrets/app_db_password"], backendImage);
  startExit(wrongBackend, "BACKEND_ROOT_ONLY_NOT_REJECTED", 1);
  const backendDeniedLog = docker(["logs", wrongBackend]);
  check("backend_root_only_secret_fails_before_java", (backendDeniedLog.stdout + backendDeniedLog.stderr).trim() === "Cannot read APP_DB_PASSWORD_FILE.");
  const wrongPg = create(prefix + "-pg-deny", [...pgNoData, "--user", `${pgUid}:${pgGid}`, ...appBind, "--entrypoint", "sh"], pgImage, ["-c", "set -eu; test ! -r /run/secrets/app_db_password"]);
  startExit(wrongPg, "PG_CAN_READ_BACKEND_COPY"); check("postgres_uid_cannot_read_backend_copy");
  const pgEnv = ["--env", `POSTGRES_DB=qa_${suffix}`, "--env", `APP_DB_USERNAME=qa_${suffix}`, "--env", "POSTGRES_USER=postgres", "--env", "POSTGRES_PASSWORD_FILE=/run/secrets/postgres_admin_password", "--env", "APP_DB_PASSWORD_FILE=/run/secrets/app_db_password", "--env", "POSTGRES_INITDB_ARGS=--auth-host=scram-sha-256"];
  const badPg = create(prefix + "-pg-wrong-owner", [...pgNoData, ...appBind, ...bind("runtime/postgres-admin-password.txt", "/run/secrets/postgres_admin_password"), ...bind("init-app.sh", "/docker-entrypoint-initdb.d/10-init-app.sh"), ...pgEnv], pgImage);
  startExit(badPg, "PG_INIT_WRONG_OWNER_NOT_REJECTED", 1);
  const pgDeniedLog = docker(["logs", badPg]);
  check("fresh_pg_init_rejects_backend_owned_app_secret", (pgDeniedLog.stdout + pgDeniedLog.stderr).includes("Application database secret is unreadable"));

  const data = volume(prefix + "-data");
  const pg = create(prefix + "-postgres", ["--network", "none", "--memory", "512m", "--mount", `type=volume,source=${data.Name},target=/var/lib/postgresql/data`, ...bind("init-app.sh", "/docker-entrypoint-initdb.d/10-init-app.sh"), ...bind("runtime/postgres-admin-password.txt", "/run/secrets/postgres_admin_password"), ...bind("runtime/postgres-app-db-password.txt", "/run/secrets/app_db_password"), ...pgEnv], pgImage);
  docker(["start", pg]); await ready(pg);
  const role = `qa_${suffix}`;
  check("pinned_pg_root_admin_and_postgres_init_secret_read", pgSql(pg, "SELECT current_user;", "PG_APP_AUTH_FAILED") === role);
  pgSql(pg, "CREATE TABLE secret_qa_canary(id integer PRIMARY KEY); INSERT INTO secret_qa_canary VALUES (1);", "PG_CANARY_FAILED");
  docker(["restart", "--time", "20", pg]); await ready(pg);
  check("pg_restart_preserves_role_auth_and_synthetic_data", pgSql(pg, "SELECT count(*) FROM secret_qa_canary;", "PG_RESTART_AUTH_FAILED") === "1");
  exec(helper, ["python3", ...helperArgs.map(v => v === "prepare" ? "check" : v)], "RUNTIME_CHANGED_AFTER_RESTART");
  check("master_and_runtime_permissions_unchanged_after_restarts");
  report.passed = true;
} catch (error) { report.failureCode = error.qaCode ?? "UNEXPECTED_QA_ERROR"; }
finally {
  let clean = true;
  for (const [name, id] of [...containers].reverse()) {
    try { guard(id); docker(["rm", "-f", id], "CLEANUP_CONTAINER_FAILED"); containers.delete(name); }
    catch { clean = false; }
  }
  for (const name of volumes) {
    try {
      const info = json(["volume", "inspect", name])[0];
      need(info.Name === name && info.Labels?.[label] === suffix && name.startsWith(prefix + "-"), "CLEANUP_VOLUME_IDENTITY_REJECTED");
      docker(["volume", "rm", name], "CLEANUP_VOLUME_FAILED");
      volumes.delete(name);
    } catch { clean = false; }
  }
  report.cleanupVerified = clean && containers.size === 0 && volumes.size === 0;
  if (!report.cleanupVerified) { report.passed = false; report.failureCode ??= "CLEANUP_FAILED"; }
  try { await writeFile(path.join(directory, "result.private.json"), JSON.stringify(report, null, 2) + "\n", { mode: 0o600, flag: "wx" }); }
  catch { report.passed = false; report.failureCode ??= "REPORT_WRITE_FAILED"; }
  console.log(JSON.stringify(report));
  process.exitCode = report.passed ? 0 : 1;
}
