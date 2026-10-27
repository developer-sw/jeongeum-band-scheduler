#!/usr/bin/env node
/** Real systemd in an offline TCG guest. Never connects to AWS or existing DBs. */
import {spawnSync} from 'node:child_process';
import {randomBytes} from 'node:crypto';
import {mkdir, lstat, writeFile, copyFile} from 'node:fs/promises';
import {writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const suffix = randomBytes(6).toString('hex');
const label = 'jeongeum.operational-catchup';
const directory = path.join(repository, '.local', `operational-catchup-qa-${suffix}`);
const image = `jeongeum-operational-catchup-qa:${suffix}`;
const name = `jeongeum-operational-catchup-${suffix}`;
const pgImage = 'postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826';
const images = {
  BACKEND_IMAGE: 'ghcr.io/developer-sw/jeongeum-band-scheduler/backend@sha256:7dd69db8f16bfb7282b4bed2a2ccf41b58f2e8f59a659e10f9a9ab82510e0a61',
  FRONTEND_IMAGE: 'ghcr.io/developer-sw/jeongeum-band-scheduler/frontend@sha256:9957a66e491dfbba8d84a119015ab323c4fbaa95ced16381e42e7692732ca687',
  CADDY_IMAGE: 'ghcr.io/developer-sw/jeongeum-band-scheduler/caddy@sha256:9876276ae2e2de72212e1028c5b3cba74a99ee32e7ce17dd7f0a7d4c2000cb5c',
  POSTGRES_IMAGE: pgImage,
};
const report = {passed: false, cleanupVerified: false, existingMetadataPreserved: false,
  actualAwsAccessed: false, developmentDatabaseAccessed: false, actualRecoveryIdentityUsed: false,
  scope: 'Real PID 1 systemd/PG17 in offline, private-disk QEMU TCG guest inside unprivileged Docker; strict synthetic Docker/AWS shims'};
let ownedId, ownedImageId, before, stage = 'preflight', diagnostic = 0;
const need = (condition, code) => {if (!condition) throw Object.assign(new Error(code), {qaCode: code});};

function docker(args, {allowFailure = false, timeout = 120000} = {}) {
  const env = {...process.env};
  for (const key of Object.keys(env)) if (/^(AWS_|APP_|JEONGEUM_|POSTGRES_|VAPID_|COMPOSE_)/.test(key)) delete env[key];
  const result = spawnSync('docker', args, {cwd: repository, env, timeout, maxBuffer: 16 * 1024 * 1024, windowsHide: true});
  if ((result.status !== 0 || result.error) && !allowFailure) {
    try {writeFileSync(path.join(directory, `docker-${++diagnostic}.private.log`), result.stderr ?? '', {flag: 'wx', mode: 0o600});} catch {}
    need(false, 'LOCAL_DOCKER_COMMAND_FAILED');
  }
  return result;
}
const text = args => docker(args).stdout.toString('utf8').trim();
const json = args => JSON.parse(text(args));
function baseline() {
  const ids = text(['ps', '-aq']).split(/\s+/).filter(Boolean);
  const rows = ids.length ? json(['inspect', ...ids]) : [];
  return {
    containers: rows.map(r => ({id: r.Id, name: r.Name, image: r.Image, state: r.State.Status,
      started: r.State.StartedAt, restart: r.HostConfig.RestartPolicy,
      mounts: r.Mounts.map(m => ({type: m.Type, name: m.Name, target: m.Destination, rw: m.RW}))})).sort((a, b) => a.id.localeCompare(b.id)),
    volumes: text(['volume', 'ls', '-q']).split(/\s+/).filter(Boolean).sort(),
    networks: text(['network', 'ls', '-q']).split(/\s+/).filter(Boolean).sort(),
  };
}
function guardImage() {
  const row = json(['image', 'inspect', image])[0];
  need(row.Id === ownedImageId && row.Config.Labels?.['jeongeum.operational-catchup-image'] === suffix,
    'OWNED_IMAGE_BINDING_REJECTED');
}
function guardContainer() {
  need(/^[a-f0-9]{64}$/.test(ownedId ?? ''), 'OWNED_CONTAINER_ID_REJECTED');
  const row = json(['inspect', ownedId])[0];
  need(row.Id === ownedId && row.Name === '/' + name && row.Config.Labels?.[label] === suffix
    && row.Image === ownedImageId, 'OWNED_CONTAINER_BINDING_REJECTED');
  need(!row.HostConfig.Privileged && row.HostConfig.NetworkMode === 'none'
    && !row.HostConfig.Binds?.length && !row.HostConfig.CapAdd?.length
    && !row.HostConfig.Devices?.length && !row.HostConfig.DeviceRequests?.length
    && !Object.keys(row.HostConfig.PortBindings ?? {}).length && !row.HostConfig.PublishAllPorts,
    'UNPRIVILEGED_OFFLINE_SCOPE_REJECTED');
  const tmpfs = row.HostConfig.Tmpfs ?? {};
  need(Object.keys(tmpfs).length === 1
    && tmpfs['/var/lib/postgresql/data'] === 'rw,nosuid,nodev,size=1m,mode=0700', 'TMPFS_CONFIGURATION_REJECTED');
  // Docker reports configured tmpfs in HostConfig but not Mounts before start
  // or after exit. Any active mount must still be the one exact private tmpfs.
  need((['created', 'exited'].includes(row.State.Status) && row.State.Running === false && row.Mounts.length === 0)
    || (row.Mounts.length === 1 && row.Mounts[0].Type === 'tmpfs'
      && row.Mounts[0].Destination === '/var/lib/postgresql/data'), 'UNEXPECTED_MOUNT_REJECTED');
  return row;
}

try {
  need(process.argv.length === 2, 'ARGUMENTS_REJECTED');
  need(!process.env.DOCKER_HOST && !process.env.DOCKER_CONTEXT && !process.env.DOCKER_TLS_VERIFY, 'REMOTE_DOCKER_ENV_REJECTED');
  const context = text(['context', 'show']);
  need(['desktop-linux', 'default'].includes(context), 'LOCAL_DOCKER_CONTEXT_REQUIRED');
  const endpoint = json(['context', 'inspect', context])[0]?.Endpoints?.docker?.Host;
  need(typeof endpoint === 'string' && /^(unix:\/\/\/|npipe:\/\/\/\/\.\/pipe\/)/.test(endpoint), 'LOCAL_ENDPOINT_REQUIRED');
  need(text(['info', '--format', '{{.OSType}}']) === 'linux', 'LINUX_DOCKER_REQUIRED');
  const local = path.join(repository, '.local');
  try {await mkdir(local, {mode: 0o700});} catch (e) {if (e.code !== 'EEXIST') throw e;}
  const info = await lstat(local);
  need(info.isDirectory() && !info.isSymbolicLink(), 'LOCAL_PARENT_REJECTED');
  await mkdir(directory, {mode: 0o700});
  before = baseline();
  await writeFile(path.join(directory, 'before.private.json'), JSON.stringify(before), {mode: 0o600, flag: 'wx'});
  stage = 'prepare-public-only-build-context';
  const contextDirectory = path.join(directory, 'build');
  await mkdir(contextDirectory, {mode: 0o700});
  await copyFile(path.join(repository, 'deploy/operational-backup/qa/Dockerfile.catchup'), path.join(contextDirectory, 'Dockerfile'));
  await copyFile(path.join(repository, 'scripts/operational-catchup-qa.py'), path.join(contextDirectory, 'operational-catchup-qa.py'));
  need(docker(['image', 'inspect', image], {allowFailure: true}).status !== 0, 'IMAGE_ALREADY_EXISTS');
  stage = 'build-official-package-guest-image';
  const build = docker(['build', '--platform', 'linux/amd64', '--build-arg', `QA_OWNER=${suffix}`, '--tag', image, contextDirectory], {allowFailure: true, timeout: 600000});
  await writeFile(path.join(directory, 'build.private.log'), Buffer.concat([build.stdout ?? Buffer.alloc(0), build.stderr ?? Buffer.alloc(0)]), {mode: 0o600, flag: 'wx'});
  // Record/guard even a finished tagged build if the client reported a later failure.
  const built = docker(['image', 'inspect', image], {allowFailure: true});
  if (built.status === 0) {
    const row = JSON.parse(built.stdout)[0];
    need(row.Config.Labels?.['jeongeum.operational-catchup-image'] === suffix, 'BUILT_IMAGE_OWNER_REJECTED');
    ownedImageId = row.Id;
  }
  need(build.status === 0 && ownedImageId, 'OFFICIAL_IMAGE_BUILD_FAILED');
  guardImage();
  stage = 'create-offline-unprivileged-outer-container';
  need(docker(['inspect', name], {allowFailure: true}).status !== 0, 'CONTAINER_ALREADY_EXISTS');
  ownedId = text(['create', '--name', name, '--label', `${label}=${suffix}`, '--network', 'none',
    '--memory', '1536m', '--cpus', '2', '--pids-limit', '256',
    '--tmpfs', '/var/lib/postgresql/data:rw,nosuid,nodev,size=1m,mode=0700', image]);
  guardContainer();
  stage = 'copy-only-public-payload-and-synthetic-binding';
  const payload = path.join(directory, 'payload');
  await mkdir(payload, {mode: 0o700});
  await mkdir(path.join(payload, 'scripts'), {mode: 0o700});
  await mkdir(path.join(payload, 'bin'), {mode: 0o700});
  for (const filename of ['operational-backup.py', 'production-backup.py', 'operational-backup-qa.py'])
    await copyFile(path.join(repository, 'scripts', filename), path.join(payload, 'scripts', filename));
  for (const filename of ['age', 'age-keygen']) {
    const source = path.join(repository, '.local/tools/age-linux-1.3.2/age', filename);
    const info = await lstat(source);
    need(info.isFile() && !info.isSymbolicLink(), 'OFFICIAL_AGE_BINARY_REQUIRED');
    await copyFile(source, path.join(payload, 'bin', filename));
  }
  for (const [source, target] of [['jeongeum-operational-backup.service', 'production.service'], ['jeongeum-operational-backup.timer', 'production.timer']])
    await copyFile(path.join(repository, 'deploy/operational-backup', source), path.join(payload, target));
  const project = 'jeongeum-ops-qa-' + suffix, database = 'opsqa_' + suffix;
  const binding = {kind: 'source', suffix, project, database, application_owner: database,
    container_id: ownedId, data_volume: project + '_postgres_data', postgres_image: pgImage, images,
    source_sha: 'a00a791b069f20943c5e956968e970bf138fa3dc',
    inspect: {Id: ownedId, State: {Status: 'running', Running: true, Paused: false, Dead: false, Restarting: false},
      Config: {Image: pgImage, Labels: {'com.docker.compose.project': project, 'com.docker.compose.service': 'postgres', 'jeongeum.operational-qa': suffix}},
      HostConfig: {NetworkMode: 'none', Privileged: false, PortBindings: {}, PublishAllPorts: false},
      NetworkSettings: {Ports: {}}, Mounts: [{Type: 'volume', Name: project + '_postgres_data', Destination: '/var/lib/postgresql/data', RW: true}]} };
  await writeFile(path.join(payload, 'binding.json'), JSON.stringify(binding), {mode: 0o600, flag: 'wx'});
  guardContainer();
  docker(['cp', payload + path.sep + '.', ownedId + ':/qa-payload']);
  stage = 'run-real-systemd-guest';
  guardContainer();
  const execution = docker(['start', '-a', ownedId], {allowFailure: true, timeout: 840000});
  await writeFile(path.join(directory, 'execution.private.log'), Buffer.concat([execution.stdout ?? Buffer.alloc(0), execution.stderr ?? Buffer.alloc(0)]), {mode: 0o600, flag: 'wx'});
  const lines = (execution.stdout ?? Buffer.alloc(0)).toString('utf8').trim().split(/\r?\n/);
  const guest = JSON.parse(lines.at(-1));
  report.guest = guest;
  need(!execution.error && execution.status === 0 && guest.passed, 'REAL_SYSTEMD_GUEST_FAILED');
  need(text(['wait', ownedId]) === '0', 'OUTER_CONTAINER_EXIT_FAILED');
  report.passed = true;
} catch (error) {
  report.failureStage = stage;
  report.failureCode = error.qaCode ?? 'UNEXPECTED_CATCHUP_FAILURE';
  report.exceptionType = error.constructor.name;
} finally {
  let clean = true;
  if (ownedId) {
    try {
      guardContainer();
      docker(['cp', ownedId + ':/vmwork/serial.private.log', path.join(directory, 'serial.private.log')], {allowFailure: true});
      docker(['cp', ownedId + ':/vmwork/last-command.private.log', path.join(directory, 'last-command.private.log')], {allowFailure: true});
      docker(['rm', '-f', ownedId]);
      need(docker(['inspect', ownedId], {allowFailure: true}).status !== 0, 'CONTAINER_NOT_REMOVED');
    } catch {clean = false;}
  }
  if (ownedImageId) {
    try {guardImage(); docker(['image', 'rm', image]);} catch {clean = false;}
  }
  report.cleanupVerified = clean;
  if (before) {
    try {
      const after = baseline();
      await writeFile(path.join(directory, 'after.private.json'), JSON.stringify(after), {mode: 0o600, flag: 'wx'});
      report.existingMetadataPreserved = JSON.stringify(before) === JSON.stringify(after);
    } catch {report.existingMetadataPreserved = false;}
  }
  if (!report.cleanupVerified || !report.existingMetadataPreserved) report.passed = false;
  try {await writeFile(path.join(directory, 'result.private.json'), JSON.stringify(report, null, 2), {mode: 0o600, flag: 'wx'});} catch {}
  console.log(JSON.stringify(report));
  process.exitCode = report.passed ? 0 : 1;
}
