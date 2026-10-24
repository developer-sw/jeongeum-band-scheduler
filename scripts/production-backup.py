#!/usr/bin/env python3
"""Root-only, synthetic-only encrypted backup runner; never restores or deletes S3 objects."""
import argparse
import configparser
import contextlib
import datetime as dt
import hashlib
import json
import math
import os
from pathlib import Path
import re
import selectors
import signal
import stat
import subprocess
import sys
import tarfile
import tempfile
import uuid

try:
    import fcntl
except ImportError:
    fcntl = None

UTC = dt.timezone.utc
NAMESPACE = "JEONGEUM/Backups"
METRIC = "VerifiedBackupAgeMinutes"
STALE_MINUTES = 1_000_000.0
FILES = ("database.dump", "database.private.manifest", "backup-manifest.json")
MANIFEST_SQL = r"""
SET TIME ZONE 'UTC';
SET LOCAL statement_timeout='120s';
SELECT format('SELECT %L || ''|'' || count(*) || ''|'' || coalesce(md5(string_agg(row_to_json(q)::text, E''\n'' ORDER BY row_to_json(q)::text)),md5('''')) FROM %I.%I q;',
 'table|' || schemaname || '.' || tablename, schemaname, tablename)
FROM pg_tables WHERE schemaname='public' ORDER BY tablename
\gexec
"""
SEQUENCE_SQL = r"""
SELECT format('SELECT %L || ''|'' || last_value || ''|'' || CASE WHEN is_called THEN ''t'' ELSE ''f'' END FROM %I.%I;',
 'sequence|' || schemaname || '.' || sequencename, schemaname, sequencename)
FROM pg_sequences WHERE schemaname='public' ORDER BY sequencename
\gexec
"""
FLYWAY_SQL = "SELECT 'flyway|' || string_agg(version || ':' || success::text, ',' ORDER BY installed_rank) FROM public.flyway_schema_history;"


class BackupError(Exception):
    pass


def need(condition, code):
    if not condition:
        raise BackupError(code)


def utc_now():
    return dt.datetime.now(UTC)


def timestamp(value):
    return value.astimezone(UTC).isoformat(timespec="seconds").replace("+00:00", "Z")


def sha_file(path):
    value = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(1024 * 1024):
            value.update(chunk)
    return value.hexdigest()


def secure_path(value, *, directory=False, private=True):
    """Reject link traversal, foreign owners and writable parents, including dangling links."""
    path = Path(value)
    need(path.is_absolute() and ".." not in path.parts and path.resolve() == path, "BACKUP_PATH_REJECTED")
    for parent in reversed(path.parents):
        info = parent.lstat()
        need(stat.S_ISDIR(info.st_mode) and not parent.is_symlink() and info.st_uid == 0, "BACKUP_PARENT_REJECTED")
        # Root-owned sticky /tmp is safe only above a private, root-owned child.
        need(not info.st_mode & 0o022 or (str(parent) == "/tmp" and info.st_mode & stat.S_ISVTX), "BACKUP_PARENT_WRITABLE")
    info = path.lstat()
    need(not path.is_symlink(), "BACKUP_FILE_OWNER_OR_LINK_REJECTED")
    validate_file_info(info, directory=directory, private=private)
    if directory:
        need(info.st_mode & 0o700 == 0o700, "BACKUP_DIRECTORY_PERMISSIONS_REJECTED")
    return path


def validate_file_info(info, *, directory=False, private=True):
    need(info.st_uid == 0, "BACKUP_FILE_OWNER_OR_LINK_REJECTED")
    need(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "BACKUP_FILE_TYPE_REJECTED")
    need(not info.st_mode & (0o077 if private else 0o022), "BACKUP_FILE_PERMISSIONS_REJECTED")


def private_dir(path):
    path = Path(path)
    if not path.exists() and not path.is_symlink():
        path.mkdir(mode=0o700)
    return secure_path(path, directory=True)


def read_private_json(path, maximum=128 * 1024):
    path = secure_path(path)
    need(path.stat().st_size <= maximum, "BACKUP_JSON_TOO_LARGE")
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError):
        raise BackupError("BACKUP_JSON_INVALID") from None


def atomic_json(path, value):
    path = Path(path)
    secure_path(path.parent, directory=True)
    if path.exists() or path.is_symlink():
        secure_path(path)
    fd, temporary = tempfile.mkstemp(prefix=".state-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(value, output, sort_keys=True, separators=(",", ":"))
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, path)
        descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


@contextlib.contextmanager
def locked(path):
    need(fcntl is not None, "BACKUP_LINUX_REQUIRED")
    flags = os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW
    fd = os.open(path, flags, 0o600)
    try:
        info = os.fstat(fd)
        need(info.st_uid == 0 and stat.S_ISREG(info.st_mode) and not info.st_mode & 0o077, "BACKUP_LOCK_REJECTED")
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False
            return
        try:
            yield True
        finally:
            fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


def load_config(path):
    cfg = read_private_json(path)
    required = {"schema_version", "mode", "project", "container_id", "data_volume", "database", "database_user", "application_owner",
                "postgres_image", "release_images", "source_sha", "recipient", "bucket", "expected_account", "region", "prefix",
                "aws_credentials_file", "state_directory", "docker_binary", "age_binary", "aws_binary", "maximum_ciphertext_bytes"}
    need(isinstance(cfg, dict) and set(cfg) == required, "BACKUP_CONFIG_KEYS_REJECTED")
    need(cfg["schema_version"] == 1 and cfg["mode"] == "synthetic", "BACKUP_SYNTHETIC_ONLY")
    need(re.fullmatch(r"jeongeum-prod-qa-[0-9a-f]{12}", cfg["project"] or ""), "BACKUP_PROJECT_REJECTED")
    suffix = cfg["project"].removeprefix("jeongeum-prod-qa-")
    need(cfg["database"] in (f"qa_{suffix}", f"qa_{suffix}_restore"), "BACKUP_DATABASE_REJECTED")
    need(cfg["database_user"] == "postgres" and cfg["application_owner"] == f"qa_{suffix}", "BACKUP_DATABASE_ROLE_REJECTED")
    need(re.fullmatch(r"[0-9a-f]{64}", cfg["container_id"] or ""), "BACKUP_CONTAINER_ID_REJECTED")
    need(cfg["data_volume"].startswith(cfg["project"] + "_") and re.fullmatch(r"[a-z0-9_-]+", cfg["data_volume"]), "BACKUP_VOLUME_REJECTED")
    need(re.fullmatch(r"postgres:17\.11-bookworm@sha256:[a-f0-9]{64}", cfg["postgres_image"] or ""), "BACKUP_POSTGRES_PIN_REQUIRED")
    need(isinstance(cfg["release_images"], dict) and set(cfg["release_images"]) == {"BACKEND_IMAGE", "FRONTEND_IMAGE", "CADDY_IMAGE", "POSTGRES_IMAGE"}, "BACKUP_IMAGE_SET_REJECTED")
    for image in cfg["release_images"].values():
        need(isinstance(image, str) and re.fullmatch(r"[a-z0-9./:_-]+@sha256:[0-9a-f]{64}", image), "BACKUP_IMAGE_PIN_REQUIRED")
    need(cfg["release_images"]["POSTGRES_IMAGE"] == cfg["postgres_image"], "BACKUP_POSTGRES_PIN_MISMATCH")
    need(re.fullmatch(r"[0-9a-f]{40}", cfg["source_sha"] or ""), "BACKUP_SOURCE_SHA_REJECTED")
    need(re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", cfg["recipient"] or ""), "BACKUP_PUBLIC_RECIPIENT_REQUIRED")
    need(re.fullmatch(r"jeongeum-[a-z0-9-]{3,50}", cfg["bucket"] or "") and not cfg["bucket"].endswith("-"), "BACKUP_BUCKET_REJECTED")
    need(re.fullmatch(r"[0-9]{12}", cfg["expected_account"] or ""), "BACKUP_ACCOUNT_REQUIRED")
    need(cfg["region"] == "ap-northeast-2" and cfg["prefix"] == "synthetic/", "BACKUP_REGION_OR_PREFIX_REJECTED")
    need(type(cfg["maximum_ciphertext_bytes"]) is int and 1024 * 1024 <= cfg["maximum_ciphertext_bytes"] <= 1024 ** 3, "BACKUP_SIZE_LIMIT_REJECTED")
    secure_path(cfg["state_directory"], directory=True)
    for binary in ("docker_binary", "age_binary", "aws_binary"):
        # Official installers may expose a root-owned symlink to a root-owned binary.
        original = Path(cfg[binary])
        need(original.is_absolute() and ".." not in original.parts, "BACKUP_TOOL_PATH_REJECTED")
        resolved = original.resolve()
        secure_path(resolved, private=False)
        need(os.access(resolved, os.X_OK), "BACKUP_TOOL_NOT_EXECUTABLE")
        cfg[binary] = str(resolved)
    credentials = secure_path(cfg["aws_credentials_file"])
    need(credentials.stat().st_size < 16 * 1024, "BACKUP_CREDENTIAL_FILE_REJECTED")
    ini = configparser.ConfigParser(interpolation=None)
    try:
        ini.read_string(credentials.read_text(encoding="utf-8"))
        need(ini.sections() == ["backup"] and not ini.defaults(), "BACKUP_CREDENTIAL_PROFILE_REJECTED")
        fields = set(ini["backup"])
        need({"aws_access_key_id", "aws_secret_access_key"} <= fields <= {"aws_access_key_id", "aws_secret_access_key", "aws_session_token"}, "BACKUP_CREDENTIAL_FIELDS_REJECTED")
        need(all(ini["backup"][name].strip() for name in fields), "BACKUP_CREDENTIAL_FIELDS_REJECTED")
    except (configparser.Error, UnicodeError):
        raise BackupError("BACKUP_CREDENTIAL_FILE_REJECTED") from None
    return cfg


class Runner:
    def __init__(self, cfg):
        self.cfg = cfg
        # Construct an allowlist environment: no inherited endpoint, profile,
        # web identity, container provider, Docker host, or secret env override.
        self.env = {"PATH": "/usr/local/bin:/usr/bin:/bin", "HOME": "/nonexistent", "LANG": "C.UTF-8", "TZ": "UTC",
                    "AWS_SHARED_CREDENTIALS_FILE": cfg["aws_credentials_file"], "AWS_CONFIG_FILE": "/dev/null", "AWS_PROFILE": "backup",
                    "AWS_REGION": cfg["region"], "AWS_DEFAULT_REGION": cfg["region"], "AWS_EC2_METADATA_DISABLED": "true",
                    "AWS_PAGER": "", "AWS_CLI_AUTO_PROMPT": "off", "AWS_RETRY_MODE": "standard", "AWS_MAX_ATTEMPTS": "3"}

    def run(self, arguments, *, data=None, output=None, timeout=180):
        stdout = output if output is not None else subprocess.PIPE
        process = subprocess.Popen(arguments, stdin=subprocess.PIPE if data is not None else subprocess.DEVNULL,
                                   stdout=stdout, stderr=subprocess.DEVNULL, env=self.env, cwd="/", start_new_session=True)
        try:
            try:
                result, _ = process.communicate(data, timeout=timeout)
            except subprocess.TimeoutExpired:
                raise BackupError("BACKUP_COMMAND_TIMEOUT") from None
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        need(process.returncode == 0, "BACKUP_COMMAND_FAILED")
        return result or b""

    def docker(self, *arguments, **kwargs):
        return self.run([self.cfg["docker_binary"], "--host=unix:///var/run/docker.sock", *arguments], **kwargs)

    def aws(self, *arguments):
        return self.run([self.cfg["aws_binary"], "--region", self.cfg["region"], "--no-cli-pager", *arguments], timeout=180)

    def snapshot(self):
        return Snapshot(self)


class Snapshot:
    def __init__(self, runner):
        self.runner = runner

    def __enter__(self):
        cfg = self.runner.cfg
        command = postgres_command(cfg, "psql", "-X", "-qAt", "--set", "ON_ERROR_STOP=1")
        command = [cfg["docker_binary"], "--host=unix:///var/run/docker.sock", "exec", "-i", "--user", "postgres", cfg["container_id"], *command]
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        env=self.runner.env, cwd="/", start_new_session=True)
        try:
            self.process.stdin.write(b"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT pg_export_snapshot();\n")
            self.process.stdin.flush()
            with selectors.DefaultSelector() as selector:
                selector.register(self.process.stdout, selectors.EVENT_READ)
                need(selector.select(timeout=15), "BACKUP_SNAPSHOT_TIMEOUT")
                snapshot = self.process.stdout.readline(128).decode("ascii").strip()
            need(re.fullmatch(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{8}-[0-9]+", snapshot), "BACKUP_SNAPSHOT_REJECTED")
            self.snapshot_id = snapshot
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def __exit__(self, *_):
        if self.process.stdin:
            try:
                self.process.stdin.close()  # EOF rolls back the read-only transaction.
            except BrokenPipeError:
                pass
        try:
            self.process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, signal.SIGKILL)
            self.process.wait(timeout=5)
        self.process.stdout.close()


def postgres_command(cfg, executable, *arguments):
    return [executable, "--username", cfg["database_user"], "--dbname", cfg["database"], *arguments]


def sql(runner, statement):
    cfg = runner.cfg
    return runner.docker("exec", "-i", "--user", "postgres", cfg["container_id"], *postgres_command(cfg, "psql", "-X", "-qAt", "--set", "ON_ERROR_STOP=1"), data=statement.encode("utf-8")).decode("utf-8")


def inspect_source(runner):
    cfg = runner.cfg
    info = json.loads(runner.docker("inspect", cfg["container_id"]))
    need(isinstance(info, list) and len(info) == 1, "BACKUP_CONTAINER_INSPECTION_REJECTED")
    item = info[0]
    need(item.get("Id") == cfg["container_id"] and item.get("State", {}).get("Running") is True
         and item.get("State", {}).get("Health", {}).get("Status") == "healthy", "BACKUP_CONTAINER_NOT_HEALTHY")
    labels = item.get("Config", {}).get("Labels", {})
    need(labels.get("com.docker.compose.project") == cfg["project"] and labels.get("com.docker.compose.service") == "postgres", "BACKUP_CONTAINER_BINDING_REJECTED")
    need(item.get("Config", {}).get("Image") == cfg["postgres_image"], "BACKUP_CONTAINER_IMAGE_REJECTED")
    volumes = [mount for mount in item.get("Mounts", []) if mount.get("Destination") == "/var/lib/postgresql/data"]
    need(len(volumes) == 1 and volumes[0].get("Type") == "volume" and volumes[0].get("Name") == cfg["data_volume"], "BACKUP_CONTAINER_VOLUME_REJECTED")
    guard = f"BEGIN READ ONLY; SELECT current_database() || '|' || current_user; SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('public','pg_catalog','information_schema'); SELECT bool_and(tableowner='{cfg['application_owner']}') FROM pg_tables WHERE schemaname='public'; ROLLBACK;"
    need(sql(runner, guard).strip().splitlines() == [f"{cfg['database']}|{cfg['database_user']}", "0", "t"], "BACKUP_DATABASE_BINDING_REJECTED")


def identity(runner):
    result = json.loads(runner.aws("sts", "get-caller-identity", "--output", "json"))
    need(result.get("Account") == runner.cfg["expected_account"], "BACKUP_AWS_ACCOUNT_MISMATCH")


def capture(runner, workspace, now):
    cfg = runner.cfg
    inspect_source(runner)
    with runner.snapshot() as snapshot:
        begin = f"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '{snapshot.snapshot_id}';\n"
        manifest = sql(runner, begin + MANIFEST_SQL + SEQUENCE_SQL + FLYWAY_SQL + "\nROLLBACK;\n")
        lines = manifest.strip().splitlines()
        tables = [line for line in lines if line.startswith("table|")]
        sequences = [line for line in lines if line.startswith("sequence|")]
        need(len(tables) == 39 and len(sequences) == 29, "BACKUP_SCHEMA_COUNTS_REJECTED")
        need([line for line in lines if line.startswith("flyway|")] == ["flyway|" + ",".join(f"{version}:true" for version in range(1, 13))], "BACKUP_FLYWAY_HISTORY_REJECTED")
        for line in tables:
            need(re.fullmatch(r"table\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[a-f0-9]{32}", line), "BACKUP_MANIFEST_REJECTED")
        for line in sequences:
            need(re.fullmatch(r"sequence\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[tf]", line), "BACKUP_MANIFEST_REJECTED")
        (workspace / "database.private.manifest").write_text(manifest, encoding="utf-8")
        dump = workspace / "database.dump"
        with dump.open("xb") as output:
            runner.docker("exec", "--user", "postgres", cfg["container_id"], *postgres_command(cfg, "pg_dump", "--format=custom", "--no-owner", "--no-acl", "--snapshot", snapshot.snapshot_id), output=output, timeout=300)
        with dump.open("rb") as source:
            need(dump.stat().st_size > 20 and source.read(5) == b"PGDMP", "BACKUP_DUMP_INCOMPLETE")
        after = sql(runner, begin + SEQUENCE_SQL + "\nROLLBACK;\n").strip().splitlines()
        need(after == sequences, "BACKUP_SEQUENCE_CHANGED_DURING_SNAPSHOT")
    manifest_json = {"formatVersion": 1, "syntheticOnly": True, "sourceProject": cfg["project"], "sourceDatabase": cfg["database"],
                     "applicationOwner": cfg["application_owner"], "snapshotStartedAtUtc": timestamp(now), "tables": 39, "sequences": 29,
                     "sourceSha": cfg["source_sha"], "images": cfg["release_images"],
                     "files": {name: {"sha256": sha_file(workspace / name), "bytes": (workspace / name).stat().st_size} for name in FILES[:2]}}
    (workspace / "backup-manifest.json").write_text(json.dumps(manifest_json, sort_keys=True) + "\n", encoding="utf-8")


def encrypt(runner, workspace):
    cfg = runner.cfg
    need(runner.run([cfg["age_binary"], "--version"]).decode().strip() == "v1.3.2", "BACKUP_AGE_VERSION_REJECTED")
    archive = workspace / "backup.tar.gz.age"
    # Only the public recipient is passed. The archive itself is never written in plaintext.
    process = subprocess.Popen([cfg["age_binary"], "--recipient", cfg["recipient"], "--output", str(archive)], stdin=subprocess.PIPE,
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=runner.env, cwd="/", start_new_session=True)
    try:
        with tarfile.open(fileobj=process.stdin, mode="w|gz", compresslevel=6) as output:
            for name in FILES:
                path = workspace / name
                info = tarfile.TarInfo(name)
                info.mode, info.size, info.mtime = 0o600, path.stat().st_size, 0
                with path.open("rb") as source:
                    output.addfile(info, source)
        process.stdin.close()
        need(process.wait(timeout=120) == 0, "BACKUP_ENCRYPTION_FAILED")
    except BaseException:
        if process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        raise
    secure_path(archive)
    with archive.open("rb") as source:
        need(source.read(22) == b"age-encryption.org/v1\n", "BACKUP_CIPHERTEXT_HEADER_REJECTED")
    need(200 <= archive.stat().st_size <= cfg["maximum_ciphertext_bytes"], "BACKUP_CIPHERTEXT_SIZE_REJECTED")
    return archive


def object_key(cfg, kind, now, identifier=None):
    need(kind in ("hourly", "weekly"), "BACKUP_KEY_KIND_REJECTED")
    identifier = identifier or uuid.uuid4().hex
    need(re.fullmatch(r"[0-9a-f]{32}", identifier or ""), "BACKUP_KEY_ID_REJECTED")
    return f"{cfg['prefix']}{kind}/{now:%Y/%m/%d}/{now:%Y%m%dT%H%M%SZ}-{identifier}.tar.gz.age"


def upload_verified(runner, ciphertext, workspace, key):
    cfg = runner.cfg
    need(re.fullmatch(r"synthetic/(hourly|weekly)/\d{4}/\d{2}/\d{2}/\d{8}T\d{6}Z-[0-9a-f]{32}\.tar\.gz\.age", key), "BACKUP_KEY_PREFIX_REJECTED")
    digest, size = sha_file(ciphertext), ciphertext.stat().st_size
    common = ["--bucket", cfg["bucket"], "--key", key, "--expected-bucket-owner", cfg["expected_account"]]
    put = json.loads(runner.aws("s3api", "put-object", *common, "--body", str(ciphertext), "--metadata", f"sha256={digest}",
                                "--content-type", "application/octet-stream", "--server-side-encryption", "AES256", "--if-none-match", "*", "--output", "json"))
    version = put.get("VersionId")
    need(isinstance(version, str) and version != "null" and re.fullmatch(r"[a-zA-Z0-9._+/=-]{1,1024}", version), "BACKUP_S3_VERSION_REQUIRED")
    head = json.loads(runner.aws("s3api", "head-object", *common, "--version-id", version, "--output", "json"))
    need(head.get("VersionId") == version and head.get("ContentLength") == size and head.get("Metadata", {}).get("sha256") == digest
         and head.get("ServerSideEncryption") == "AES256", "BACKUP_S3_HEAD_MISMATCH")
    downloaded = workspace / f"download-{uuid.uuid4().hex}.age"
    response = json.loads(runner.aws("s3api", "get-object", *common, "--version-id", version, str(downloaded), "--output", "json"))
    secure_path(downloaded)
    need(response.get("VersionId") == version and downloaded.stat().st_size == size and sha_file(downloaded) == digest, "BACKUP_S3_DOWNLOAD_MISMATCH")
    downloaded.unlink()
    return {"bucket": cfg["bucket"], "key": key, "versionId": version, "bytes": size, "sha256": digest}


def success_path(cfg):
    return Path(cfg["state_directory"]) / "last-success.json"


def state_identity(cfg):
    return {name: cfg[name] for name in ("mode", "project", "container_id", "database", "bucket", "prefix", "expected_account")}


def load_success(cfg):
    path = success_path(cfg)
    if not path.exists() and not path.is_symlink():
        return None
    state = read_private_json(path)
    need(isinstance(state, dict) and state.get("identity") == state_identity(cfg) and state.get("schemaVersion") == 1, "BACKUP_SUCCESS_STATE_BINDING_REJECTED")
    need(re.fullmatch(r"\d{4}-W\d{2}", state.get("weeklySlot", "")), "BACKUP_SUCCESS_WEEK_REJECTED")
    for kind in ("hourly", "weekly"):
        record = state.get(kind)
        need(isinstance(record, dict) and record.get("bucket") == cfg["bucket"] and
             re.fullmatch(r"synthetic/" + kind + r"/\d{4}/\d{2}/\d{2}/\d{8}T\d{6}Z-[0-9a-f]{32}\.tar\.gz\.age", record.get("key", "")) and
             re.fullmatch(r"[a-zA-Z0-9._+/=-]{1,1024}", record.get("versionId", "")) and record.get("versionId") != "null" and
             re.fullmatch(r"[a-f0-9]{64}", record.get("sha256", "")) and type(record.get("bytes")) is int and record["bytes"] >= 200,
             "BACKUP_SUCCESS_OBJECT_REJECTED")
    return state


def backup(cfg, runner=None, now=None):
    runner, now = runner or Runner(cfg), now or utc_now()
    state_dir = secure_path(cfg["state_directory"], directory=True)
    with locked(state_dir / "backup.lock") as acquired:
        if not acquired:
            return {"ok": True, "status": "locked", "successUpdated": False}
        previous = load_success(cfg)
        identity(runner)
        temp_root = private_dir(state_dir / "temporary")
        with tempfile.TemporaryDirectory(prefix=".run-", dir=temp_root) as name:
            workspace = secure_path(name, directory=True)
            capture(runner, workspace, now)
            ciphertext = encrypt(runner, workspace)
            hourly = upload_verified(runner, ciphertext, workspace, object_key(cfg, "hourly", now))
            weekly_slot = f"{now.isocalendar().year:04d}-W{now.isocalendar().week:02d}"
            weekly = previous.get("weekly") if previous and previous.get("weeklySlot") == weekly_slot else None
            if weekly is None:
                weekly = upload_verified(runner, ciphertext, workspace, object_key(cfg, "weekly", now))
            verified = utc_now()
            need(verified >= now, "BACKUP_CLOCK_MOVED_BACKWARDS")
            new_state = {"schemaVersion": 1, "identity": state_identity(cfg), "snapshotStartedAtUtc": timestamp(now),
                         "verifiedAtUtc": timestamp(verified), "hourly": hourly, "weeklySlot": weekly_slot, "weekly": weekly}
            atomic_json(success_path(cfg), new_state)
    return {"ok": True, "status": "verified", "successUpdated": True, "weeklyCreated": weekly_slot != (previous or {}).get("weeklySlot")}


def verified_age(cfg, now):
    try:
        state = load_success(cfg)
        if state is None:
            return STALE_MINUTES
        value = state.get("verifiedAtUtc")
        need(isinstance(value, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value), "BACKUP_SUCCESS_TIME_REJECTED")
        verified = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
        age = (now - verified).total_seconds() / 60
        need(math.isfinite(age) and age >= 0, "BACKUP_SUCCESS_TIME_IN_FUTURE")
        return min(age, STALE_MINUTES)
    except (BackupError, ValueError, OSError, TypeError):
        # Missing, corrupt, untrusted or future state must never report zero age.
        return STALE_MINUTES


def metric(cfg, runner=None, now=None):
    runner, now = runner or Runner(cfg), now or utc_now()
    state_dir = secure_path(cfg["state_directory"], directory=True)
    with locked(state_dir / "metric.lock") as acquired:
        if not acquired:
            return {"ok": True, "status": "locked", "successUpdated": False}
        identity(runner)
        age = verified_age(cfg, now)
        temp_root = private_dir(state_dir / "temporary")
        with tempfile.TemporaryDirectory(prefix=".metric-", dir=temp_root) as name:
            payload = Path(name) / "metric.json"
            payload.write_text(json.dumps([{"MetricName": METRIC, "Dimensions": [{"Name": "Environment", "Value": "synthetic"},
                                                                                  {"Name": "Instance", "Value": "jeongeum-prod-01"}],
                                            "Timestamp": timestamp(now), "Value": age, "Unit": "None", "StorageResolution": 60}]), encoding="utf-8")
            runner.aws("cloudwatch", "put-metric-data", "--namespace", NAMESPACE, "--metric-data", "file://" + str(payload))
    return {"ok": True, "status": "metric-published", "stale": age > 90, "successUpdated": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("validate", "backup", "metric"))
    parser.add_argument("--config", default="/etc/jeongeum-backup/config.json")
    args = parser.parse_args()
    try:
        need(sys.platform == "linux" and os.geteuid() == 0, "BACKUP_LINUX_ROOT_REQUIRED")
        os.umask(0o077)
        def interrupted(_signum, _frame):
            raise BackupError("BACKUP_INTERRUPTED")
        signal.signal(signal.SIGTERM, interrupted)
        cfg = load_config(args.config)
        if args.action == "validate":
            result = {"ok": True, "status": "configuration-valid", "mode": "synthetic", "externalCommandsRun": False}
        else:
            result = backup(cfg) if args.action == "backup" else metric(cfg)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except BaseException as error:
        code = str(error) if isinstance(error, BackupError) and re.fullmatch(r"BACKUP_[A-Z0-9_]+", str(error)) else "BACKUP_OPERATION_FAILED"
        # Never serialize subprocess arguments/output, config values, SQL or exceptions.
        print(json.dumps({"ok": False, "error": code}, separators=(",", ":")))
        return 1


if __name__ == "__main__":
    sys.exit(main())
