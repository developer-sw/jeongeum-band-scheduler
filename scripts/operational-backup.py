#!/usr/bin/env python3
"""Explicit operational DB backup; never changes the existing synthetic backup resources."""
import argparse
import configparser
import datetime as dt
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import signal
import stat
import sys
import tempfile
import time
import uuid

sys.dont_write_bytecode = True
UTC = dt.timezone.utc
FILES = ("database.dump", "database.private.manifest", "backup-manifest.json")
POSTGRES_IMAGE = "postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826"
NAMESPACE = "JEONGEUM/OperationalBackups"
METRIC = "RecoverableSnapshotAgeMinutes"
STALE_MINUTES = 1_000_000.0
MAX_CIPHER_BYTES = 128 * 1024 * 1024
MAX_DUMP_BYTES = 128 * 1024 * 1024
_CORE = None


class BackupError(Exception):
    pass


def need(value, code):
    if not value:
        raise BackupError(code)


def match(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def validate_import_path(path):
    path = Path(path)
    need(path.is_absolute() and ".." not in path.parts and path.name == "production-backup.py", "OPERATIONAL_MODULE_PATH_REJECTED")
    for parent in reversed(path.parents):
        info = parent.lstat()
        need(stat.S_ISDIR(info.st_mode) and not parent.is_symlink() and info.st_uid == 0
             and not info.st_mode & 0o022, "OPERATIONAL_MODULE_PARENT_REJECTED")
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and not path.is_symlink() and info.st_uid == 0 and info.st_nlink == 1
         and not info.st_mode & 0o022 and info.st_size < 128 * 1024, "OPERATIONAL_MODULE_FILE_REJECTED")
    return path


def core():
    global _CORE
    if _CORE is None:
        path = validate_import_path(Path(__file__).absolute().with_name("production-backup.py"))
        spec = importlib.util.spec_from_file_location("jeongeum_validated_operational_primitives", path)
        need(spec is not None and spec.loader is not None, "OPERATIONAL_MODULE_REJECTED")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _CORE = module
    return _CORE


class _Primitives:
    def __getattr__(self, name):
        return getattr(core(), name)


b = _Primitives()


class Runner:
    def __init__(self, cfg):
        self.cfg, self.delegate = cfg, core().Runner(cfg)
        self.env = self.delegate.env

    def run(self, *args, **kwargs): return self.delegate.run(*args, **kwargs)
    def docker(self, *args, **kwargs): return self.delegate.docker(*args, **kwargs)
    def aws(self, *args): return self.delegate.aws(*args)
    def snapshot(self): return self.delegate.snapshot()


def validate_shape(cfg):
    keys = {"schema_version", "mode", "activation_approved", "project", "container_id", "data_volume", "database", "database_user", "application_owner",
            "postgres_image", "release_images", "source_sha", "recipient", "bucket", "expected_account", "region", "prefix",
            "aws_credentials_file", "state_directory", "docker_binary", "age_binary", "aws_binary", "maximum_ciphertext_bytes"}
    need(isinstance(cfg, dict) and set(cfg) == keys, "OPERATIONAL_CONFIG_KEYS_REJECTED")
    need(type(cfg["schema_version"]) is int and cfg["schema_version"] == 2
         and cfg["mode"] in ("production", "qualification") and type(cfg["activation_approved"]) is bool, "OPERATIONAL_CONFIG_MODE_REJECTED")
    if cfg["mode"] == "production":
        need((cfg["project"], cfg["database"], cfg["application_owner"], cfg["state_directory"]) ==
             ("jeongeum-production-v1", "jeongeum_prod", "jeongeum_app", "/var/lib/jeongeum-operational-backup"), "OPERATIONAL_PRODUCTION_BINDING_REJECTED")
    else:
        need(cfg["activation_approved"] is False and match(r"jeongeum-ops-qa-[a-f0-9]{12}", cfg["project"]), "OPERATIONAL_QUALIFICATION_BINDING_REJECTED")
        suffix = cfg["project"].removeprefix("jeongeum-ops-qa-")
        need(cfg["database"] == cfg["application_owner"] == "opsqa_" + suffix
             and cfg["state_directory"] != "/var/lib/jeongeum-operational-backup", "OPERATIONAL_QUALIFICATION_BINDING_REJECTED")
    need(cfg["database_user"] == "postgres" and cfg["data_volume"] == cfg["project"] + "_postgres_data", "OPERATIONAL_DATABASE_BINDING_REJECTED")
    need(match(r"[a-f0-9]{64}", cfg["container_id"]), "OPERATIONAL_CONTAINER_ID_REJECTED")
    need(cfg["postgres_image"] == POSTGRES_IMAGE, "OPERATIONAL_POSTGRES_PIN_REJECTED")
    images = cfg["release_images"]
    need(isinstance(images, dict) and set(images) == {"BACKEND_IMAGE", "FRONTEND_IMAGE", "CADDY_IMAGE", "POSTGRES_IMAGE"}, "OPERATIONAL_IMAGE_SET_REJECTED")
    for name in ("backend", "frontend", "caddy"):
        need(match(r"ghcr\.io/developer-sw/jeongeum-band-scheduler/" + name + r"@sha256:[a-f0-9]{64}", images[name.upper() + "_IMAGE"]), "OPERATIONAL_IMAGE_PIN_REJECTED")
    need(images["POSTGRES_IMAGE"] == POSTGRES_IMAGE and match(r"[a-f0-9]{40}", cfg["source_sha"]), "OPERATIONAL_SOURCE_PIN_REJECTED")
    need(match(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", cfg["recipient"]), "OPERATIONAL_PUBLIC_RECIPIENT_REQUIRED")
    need(match(r"jeongeum-[a-z0-9-]{3,50}", cfg["bucket"]) and not cfg["bucket"].endswith("-"), "OPERATIONAL_BUCKET_REJECTED")
    need(match(r"[0-9]{12}", cfg["expected_account"]) and cfg["region"] == "ap-northeast-2"
         and cfg["prefix"] == cfg["mode"] + "/", "OPERATIONAL_ACCOUNT_REGION_PREFIX_REJECTED")
    need(type(cfg["maximum_ciphertext_bytes"]) is int and 1024 ** 2 <= cfg["maximum_ciphertext_bytes"] <= MAX_CIPHER_BYTES, "OPERATIONAL_SIZE_LIMIT_REJECTED")
    return cfg


def load_config(path):
    cfg = validate_shape(core().read_private_json(path))
    need(core().secure_path(path).stat().st_nlink == 1, "OPERATIONAL_CONFIG_HARDLINK_REJECTED")
    core().secure_path(cfg["state_directory"], directory=True)
    for name in ("docker_binary", "age_binary", "aws_binary"):
        original = Path(cfg[name])
        need(original.is_absolute() and ".." not in original.parts, "OPERATIONAL_TOOL_PATH_REJECTED")
        resolved = original.resolve()
        core().secure_path(resolved, private=False)
        need(os.access(resolved, os.X_OK), "OPERATIONAL_TOOL_NOT_EXECUTABLE")
        cfg[name] = str(resolved)
    credentials = core().secure_path(cfg["aws_credentials_file"])
    need(credentials.stat().st_nlink == 1 and credentials.stat().st_size < 16 * 1024, "OPERATIONAL_CREDENTIAL_FILE_REJECTED")
    ini = configparser.ConfigParser(interpolation=None)
    try:
        ini.read_string(credentials.read_text(encoding="utf-8"))
        need(ini.sections() == ["backup"] and not ini.defaults(), "OPERATIONAL_CREDENTIAL_PROFILE_REJECTED")
        names = set(ini["backup"])
        need({"aws_access_key_id", "aws_secret_access_key"} <= names <= {"aws_access_key_id", "aws_secret_access_key", "aws_session_token"}
             and all(ini["backup"][name].strip() for name in names), "OPERATIONAL_CREDENTIAL_FIELDS_REJECTED")
    except (configparser.Error, UnicodeError):
        raise BackupError("OPERATIONAL_CREDENTIAL_FILE_REJECTED") from None
    return cfg


def enabled(cfg):
    need(cfg["mode"] == "qualification" or cfg["activation_approved"] is True, "OPERATIONAL_ACTIVATION_NOT_APPROVED")


def bound_runner(cfg, runner):
    runner = runner or Runner(cfg)
    need(isinstance(getattr(runner, "cfg", None), dict) and runner.cfg == cfg, "OPERATIONAL_RUNNER_BINDING_REJECTED")
    return runner


def postgres_command(cfg, executable, *arguments):
    return core().postgres_command(cfg, executable, *arguments)


def sql(runner, statement):
    return core().sql(runner, statement)


def inspect_source(runner):
    cfg = runner.cfg
    raw = runner.docker("inspect", cfg["container_id"])
    need(len(raw) <= 1024 * 1024, "OPERATIONAL_INSPECTION_LIMIT_REJECTED")
    info = json.loads(raw)
    need(isinstance(info, list) and len(info) == 1 and isinstance(info[0], dict), "OPERATIONAL_INSPECTION_REJECTED")
    item = info[0]
    need(item.get("Id") == cfg["container_id"] and item.get("State", {}).get("Running") is True
         and item.get("State", {}).get("Health", {}).get("Status") == "healthy", "OPERATIONAL_SOURCE_NOT_HEALTHY")
    config, host, network = item.get("Config", {}), item.get("HostConfig", {}), item.get("NetworkSettings", {})
    labels = config.get("Labels", {})
    need(labels.get("com.docker.compose.project") == cfg["project"] and labels.get("com.docker.compose.service") == "postgres"
         and config.get("Image") == cfg["postgres_image"], "OPERATIONAL_SOURCE_BINDING_REJECTED")
    if cfg["mode"] == "qualification":
        need(labels.get("jeongeum.operational-qa") == cfg["project"].removeprefix("jeongeum-ops-qa-"), "OPERATIONAL_QUALIFICATION_LABEL_REJECTED")
    mounts = item.get("Mounts", [])
    need(isinstance(mounts, list) and all(isinstance(m, dict) for m in mounts), "OPERATIONAL_SOURCE_VOLUME_REJECTED")
    named = [m for m in mounts if m.get("Type") == "volume"]
    data = [m for m in mounts if m.get("Destination") == "/var/lib/postgresql/data"]
    need(len(named) == 1 and named[0].get("Name") == cfg["data_volume"]
         and len(data) == 1 and data[0] == named[0] and named[0].get("RW") is True, "OPERATIONAL_SOURCE_VOLUME_REJECTED")
    bind_targets = {"/run/secrets/postgres_admin_password", "/run/secrets/app_db_password", "/docker-entrypoint-initdb.d/10-init-app.sh"}
    extra = [m for m in mounts if m is not named[0]]
    need(all((m.get("Type") == "bind" and m.get("Destination") in bind_targets and m.get("RW") is False)
             or (cfg["mode"] == "qualification" and m.get("Type") == "tmpfs" and m.get("Destination") == "/qa") for m in extra)
         and len({m.get("Destination") for m in mounts}) == len(mounts), "OPERATIONAL_SOURCE_EXTRA_MOUNT_REJECTED")
    network_mode = host.get("NetworkMode")
    need(not host.get("PortBindings") and host.get("PublishAllPorts") is False and not any((network.get("Ports") or {}).values())
         and isinstance(network_mode, str) and network_mode and network_mode != "host" and not network_mode.startswith("container:"), "OPERATIONAL_SOURCE_PUBLIC_PORT_REJECTED")
    guard = ("BEGIN READ ONLY; SELECT current_database() || '|' || current_user; "
             "SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('public','pg_catalog','information_schema'); "
             f"SELECT bool_and(tableowner='{cfg['application_owner']}') FROM pg_tables WHERE schemaname='public'; "
             f"SELECT rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication FROM pg_roles WHERE rolname='{cfg['application_owner']}'; ROLLBACK;")
    need(sql(runner, guard).strip().splitlines() == [cfg["database"] + "|postgres", "0", "t", "f"], "OPERATIONAL_DATABASE_IDENTITY_REJECTED")


def identity(runner):
    need(json.loads(runner.aws("sts", "get-caller-identity", "--output", "json")).get("Account") == runner.cfg["expected_account"], "OPERATIONAL_AWS_ACCOUNT_MISMATCH")


def capture(runner, workspace, now):
    cfg = runner.cfg
    inspect_source(runner)
    with runner.snapshot() as snapshot:
        begin = f"BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY; SET TRANSACTION SNAPSHOT '{snapshot.snapshot_id}';\n"
        manifest = sql(runner, begin + core().MANIFEST_SQL + core().SEQUENCE_SQL + core().FLYWAY_SQL + "\nROLLBACK;\n")
        lines = manifest.strip().splitlines()
        tables, sequences = [x for x in lines if x.startswith("table|")], [x for x in lines if x.startswith("sequence|")]
        need(len(tables) == 39 and len(sequences) == 29, "OPERATIONAL_SCHEMA_COUNTS_REJECTED")
        need([x for x in lines if x.startswith("flyway|")] == ["flyway|" + ",".join(f"{v}:true" for v in range(1, 13))], "OPERATIONAL_FLYWAY_HISTORY_REJECTED")
        need(all(match(r"table\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[a-f0-9]{32}", x) for x in tables)
             and all(match(r"sequence\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[tf]", x) for x in sequences)
             and len(lines) == 69, "OPERATIONAL_PRIVATE_MANIFEST_REJECTED")
        (workspace / FILES[1]).write_text(manifest, encoding="utf-8")
        dump = workspace / FILES[0]
        with dump.open("xb") as output:
            runner.docker("exec", "--user", "postgres", cfg["container_id"], *postgres_command(cfg, "pg_dump", "--format=custom", "--no-owner", "--no-acl", "--snapshot", snapshot.snapshot_id), output=output, timeout=300)
        with dump.open("rb") as output:
            need(20 < dump.stat().st_size <= MAX_DUMP_BYTES and output.read(5) == b"PGDMP", "OPERATIONAL_DUMP_INCOMPLETE")
        need(sql(runner, begin + core().SEQUENCE_SQL + "\nROLLBACK;\n").strip().splitlines() == sequences, "OPERATIONAL_SEQUENCE_CHANGED_DURING_SNAPSHOT")
    value = {"formatVersion": 2, "kind": "operational-database", "mode": cfg["mode"], "sourceProject": cfg["project"], "sourceDatabase": cfg["database"],
             "applicationOwner": cfg["application_owner"], "snapshotStartedAtUtc": core().timestamp(now), "tables": 39, "sequences": 29,
             "sourceSha": cfg["source_sha"], "images": cfg["release_images"],
             "files": {name: {"sha256": core().sha_file(workspace / name), "bytes": (workspace / name).stat().st_size} for name in FILES[:2]}}
    (workspace / FILES[2]).write_text(json.dumps(value, sort_keys=True) + "\n", encoding="utf-8")


def object_key(cfg, kind, now, identifier=None):
    need(kind in ("hourly", "weekly") and cfg["prefix"] == cfg["mode"] + "/", "OPERATIONAL_KEY_KIND_REJECTED")
    identifier = identifier or uuid.uuid4().hex
    need(match(r"[a-f0-9]{32}", identifier), "OPERATIONAL_KEY_ID_REJECTED")
    return f"{cfg['prefix']}{kind}/{now:%Y/%m/%d}/{now:%Y%m%dT%H%M%SZ}-{identifier}.tar.gz.age"


def valid_key(cfg, key, kind=None):
    return match(re.escape(cfg["prefix"]) + (kind or "(?:hourly|weekly)") + r"/\d{4}/\d{2}/\d{2}/\d{8}T\d{6}Z-[a-f0-9]{32}\.tar\.gz\.age", key)


def upload_verified(runner, ciphertext, workspace, key):
    cfg = runner.cfg
    need(valid_key(cfg, key), "OPERATIONAL_KEY_PREFIX_REJECTED")
    digest, size = core().sha_file(ciphertext), ciphertext.stat().st_size
    common = ["--bucket", cfg["bucket"], "--key", key, "--expected-bucket-owner", cfg["expected_account"]]
    put = json.loads(runner.aws("s3api", "put-object", *common, "--body", str(ciphertext), "--metadata", f"sha256={digest}",
                                "--content-type", "application/octet-stream", "--server-side-encryption", "AES256", "--if-none-match", "*", "--output", "json"))
    version = put.get("VersionId")
    need(match(r"[a-zA-Z0-9._+/=-]{1,1024}", version) and version != "null", "OPERATIONAL_S3_VERSION_REQUIRED")
    head = json.loads(runner.aws("s3api", "head-object", *common, "--version-id", version, "--output", "json"))
    need(head.get("VersionId") == version and head.get("ContentLength") == size and head.get("Metadata", {}).get("sha256") == digest
         and head.get("ServerSideEncryption") == "AES256", "OPERATIONAL_S3_HEAD_MISMATCH")
    downloaded = workspace / ("download-" + uuid.uuid4().hex + ".age")
    result = json.loads(runner.aws("s3api", "get-object", *common, "--version-id", version, str(downloaded), "--output", "json"))
    core().secure_path(downloaded)
    need(result.get("VersionId") == version and downloaded.stat().st_size == size and core().sha_file(downloaded) == digest, "OPERATIONAL_S3_DOWNLOAD_MISMATCH")
    downloaded.unlink()
    return {"bucket": cfg["bucket"], "key": key, "versionId": version, "bytes": size, "sha256": digest}


def state_identity(cfg):
    return {name: cfg[name] for name in ("mode", "project", "container_id", "data_volume", "database", "application_owner", "postgres_image", "release_images", "source_sha", "recipient", "bucket", "prefix", "expected_account")}


def load_success(cfg):
    path = Path(cfg["state_directory"]) / "last-success.json"
    if not path.exists() and not path.is_symlink(): return None
    need(core().secure_path(path).stat().st_nlink == 1, "OPERATIONAL_SUCCESS_STATE_HARDLINK_REJECTED")
    state = core().read_private_json(path)
    need(isinstance(state, dict) and state.get("schemaVersion") == 2 and state.get("identity") == state_identity(cfg), "OPERATIONAL_SUCCESS_STATE_BINDING_REJECTED")
    need(match(r"\d{4}-W\d{2}", state.get("weeklySlot")), "OPERATIONAL_SUCCESS_WEEK_REJECTED")
    for kind in ("hourly", "weekly"):
        record = state.get(kind)
        need(isinstance(record, dict) and record.get("bucket") == cfg["bucket"] and valid_key(cfg, record.get("key"), kind)
             and match(r"[a-zA-Z0-9._+/=-]{1,1024}", record.get("versionId")) and record.get("versionId") != "null"
             and match(r"[a-f0-9]{64}", record.get("sha256")) and type(record.get("bytes")) is int and 200 <= record["bytes"] <= cfg["maximum_ciphertext_bytes"], "OPERATIONAL_SUCCESS_OBJECT_REJECTED")
    return state


def success_times(state, now):
    values = []
    for field in ("snapshotStartedAtUtc", "verifiedAtUtc"):
        value = state.get(field)
        need(match(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value), "OPERATIONAL_SUCCESS_TIME_REJECTED")
        values.append(dt.datetime.fromisoformat(value.replace("Z", "+00:00")))
    need(values[0] <= values[1] <= now, "OPERATIONAL_SUCCESS_TIME_REJECTED")
    return values


def backup(cfg, runner=None, now=None):
    enabled(cfg)
    runner, now = bound_runner(cfg, runner), now or core().utc_now()
    state_dir = core().secure_path(cfg["state_directory"], directory=True)
    with core().locked(state_dir / "backup.lock") as acquired:
        if not acquired: return {"ok": True, "status": "locked", "successUpdated": False}
        previous = load_success(cfg)
        if previous: success_times(previous, now)
        identity(runner)
        temp_root = core().private_dir(state_dir / "temporary")
        retryable = {"BACKUP_COMMAND_TIMEOUT", "BACKUP_COMMAND_FAILED", "BACKUP_SNAPSHOT_TIMEOUT", "OPERATIONAL_SEQUENCE_CHANGED_DURING_SNAPSHOT"}
        for attempt in range(2):
            try:
                with tempfile.TemporaryDirectory(prefix=".operational-", dir=temp_root) as directory:
                    workspace = core().secure_path(directory, directory=True)
                    started = now if attempt == 0 else core().utc_now()
                    capture(runner, workspace, started)
                    ciphertext = core().encrypt(runner, workspace)
                    hourly = upload_verified(runner, ciphertext, workspace, object_key(cfg, "hourly", started))
                    slot = f"{started.isocalendar().year:04d}-W{started.isocalendar().week:02d}"
                    weekly = previous.get("weekly") if previous and previous.get("weeklySlot") == slot else None
                    if weekly is None: weekly = upload_verified(runner, ciphertext, workspace, object_key(cfg, "weekly", started))
                    verified = core().utc_now()
                    need(verified >= started, "OPERATIONAL_CLOCK_MOVED_BACKWARDS")
                    state = {"schemaVersion": 2, "identity": state_identity(cfg), "snapshotStartedAtUtc": core().timestamp(started),
                             "verifiedAtUtc": core().timestamp(verified), "hourly": hourly, "weeklySlot": slot, "weekly": weekly}
                    core().atomic_json(state_dir / "last-success.json", state)
                return {"ok": True, "status": "verified", "successUpdated": True, "weeklyCreated": slot != (previous or {}).get("weeklySlot"), "attempts": attempt + 1}
            except Exception as error:
                if attempt == 1 or str(error) not in retryable or not isinstance(error, (BackupError, core().BackupError)): raise
                time.sleep(1)


def snapshot_age(cfg, now):
    try:
        state = load_success(cfg)
        if state is None: return STALE_MINUTES
        started, _verified = success_times(state, now)
        age = (now - started).total_seconds() / 60
        need(math.isfinite(age) and age >= 0, "OPERATIONAL_SUCCESS_TIME_REJECTED")
        return min(age, STALE_MINUTES)
    except (BackupError, core().BackupError, ValueError, OSError, TypeError): return STALE_MINUTES


def metric(cfg, runner=None, now=None):
    enabled(cfg)
    runner, now = bound_runner(cfg, runner), now or core().utc_now()
    state_dir = core().secure_path(cfg["state_directory"], directory=True)
    with core().locked(state_dir / "metric.lock") as acquired:
        if not acquired: return {"ok": True, "status": "locked", "successUpdated": False}
        identity(runner)
        age = snapshot_age(cfg, now)
        with tempfile.TemporaryDirectory(prefix=".metric-", dir=core().private_dir(state_dir / "temporary")) as directory:
            payload = Path(directory) / "metric.json"
            payload.write_text(json.dumps([{"MetricName": METRIC, "Dimensions": [{"Name": "Environment", "Value": cfg["mode"]},
                {"Name": "Instance", "Value": "jeongeum-prod-01"}], "Timestamp": core().timestamp(now), "Value": age, "Unit": "None", "StorageResolution": 60}]), encoding="utf-8")
            runner.aws("cloudwatch", "put-metric-data", "--namespace", NAMESPACE, "--metric-data", "file://" + str(payload))
    return {"ok": True, "status": "metric-published", "stale": age > 90, "successUpdated": False}


def main():
    class Parser(argparse.ArgumentParser):
        def error(self, _message): raise BackupError("OPERATIONAL_ARGUMENTS_REJECTED")
    try:
        parser = Parser(description=__doc__)
        parser.add_argument("action", choices=("validate", "backup", "metric"))
        parser.add_argument("--config", default="/etc/jeongeum-operational-backup/config.json")
        args = parser.parse_args()
        need(sys.platform == "linux" and os.geteuid() == 0, "OPERATIONAL_LINUX_ROOT_REQUIRED")
        os.umask(0o077)
        signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(BackupError("OPERATIONAL_INTERRUPTED")))
        cfg = load_config(args.config)
        result = {"ok": True, "status": "configuration-valid", "mode": cfg["mode"], "externalCommandsRun": False} if args.action == "validate" else (backup(cfg) if args.action == "backup" else metric(cfg))
        print(json.dumps(result, separators=(",", ":")));return 0
    except SystemExit as error: return int(error.code or 0)
    except BaseException as error:
        safe = isinstance(error, BackupError) or (_CORE is not None and isinstance(error, _CORE.BackupError))
        code = str(error) if safe and match(r"(?:BACKUP|OPERATIONAL)_[A-Z0-9_]+", str(error)) else "OPERATIONAL_OPERATION_FAILED"
        print(json.dumps({"ok": False, "error": code}, separators=(",", ":")));return 1


if __name__ == "__main__": sys.exit(main())
