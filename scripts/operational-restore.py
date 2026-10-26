#!/usr/bin/env python3
"""Authenticate an operational archive and verify it in a new isolated qualification DB.

Never restores into production, creates databases, drops objects, starts containers,
or reads AWS credentials. A failed post-restore verification preserves the target.
"""
import argparse
import datetime as dt
import gzip
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import stat
import sys
import tarfile

MAX_CIPHER = 128 * 1024 * 1024
MAX_TAR = 256 * 1024 * 1024
MAX_METADATA = 128 * 1024
FILES = ("database.dump", "database.private.manifest", "backup-manifest.json")
IMAGE_KEYS = {"BACKEND_IMAGE", "FRONTEND_IMAGE", "CADDY_IMAGE", "POSTGRES_IMAGE"}
SOURCE_KEYS = {"mode", "sourceProject", "sourceDatabase", "applicationOwner", "sourceSha", "images"}
_OPERATIONS = None


class RestoreError(Exception):
    pass


def need(value, code):
    if not value:
        raise RestoreError(code)


def operations():
    global _OPERATIONS
    if _OPERATIONS is None:
        path = Path(__file__).absolute().with_name("operational-backup.py")
        for item in (*reversed(path.parents), path):
            info = item.lstat()
            need(not item.is_symlink() and info.st_uid == 0 and not info.st_mode & 0o022,
                 "OPRESTORE_MODULE_REJECTED")
        need(stat.S_ISREG(info.st_mode) and info.st_nlink == 1 and info.st_size < 128 * 1024,
             "OPRESTORE_MODULE_REJECTED")
        spec = importlib.util.spec_from_file_location("jeongeum_operational_restore_dependency", path)
        need(spec is not None and spec.loader is not None, "OPRESTORE_MODULE_REJECTED")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        _OPERATIONS = module
    return _OPERATIONS


def core():
    return operations().core()


def private_path(value, directory=False):
    path = core().secure_path(value, directory=directory)
    if not directory:
        need(path.stat().st_nlink == 1, "OPRESTORE_HARDLINK_REJECTED")
    return path


def protected_executable(value):
    original = Path(value)
    need(original.is_absolute() and ".." not in original.parts, "OPRESTORE_EXECUTABLE_REJECTED")
    path = core().secure_path(original.resolve(strict=True), private=False)
    need(path.stat().st_nlink == 1 and os.access(path, os.X_OK), "OPRESTORE_EXECUTABLE_REJECTED")
    return str(path)


def exact_json(value):
    def pairs(items):
        result = {}
        for key, child in items:
            need(key not in result, "OPRESTORE_DUPLICATE_JSON_KEY")
            result[key] = child
        return result
    try:
        return json.loads(value, object_pairs_hook=pairs)
    except (ValueError, UnicodeError):
        raise RestoreError("OPRESTORE_JSON_REJECTED") from None


def validate_source(value):
    need(isinstance(value, dict) and set(value) == SOURCE_KEYS, "OPRESTORE_SOURCE_BINDING_REJECTED")
    need(all(isinstance(value[key], str) for key in SOURCE_KEYS - {"images"}), "OPRESTORE_SOURCE_BINDING_REJECTED")
    mode = value["mode"]
    if mode == "production":
        need((value["sourceProject"], value["sourceDatabase"], value["applicationOwner"]) ==
             ("jeongeum-production-v1", "jeongeum_prod", "jeongeum_app"), "OPRESTORE_SOURCE_BINDING_REJECTED")
    else:
        match = re.fullmatch(r"jeongeum-ops-qa-([a-f0-9]{12})", value.get("sourceProject", ""))
        need(mode == "qualification" and match is not None, "OPRESTORE_SOURCE_BINDING_REJECTED")
        need(value["sourceDatabase"] == value["applicationOwner"] == "opsqa_" + match[1],
             "OPRESTORE_SOURCE_BINDING_REJECTED")
    need(isinstance(value["sourceSha"], str) and re.fullmatch(r"[a-f0-9]{40}", value["sourceSha"]),
         "OPRESTORE_SOURCE_SHA_REJECTED")
    images = value["images"]
    need(isinstance(images, dict) and set(images) == IMAGE_KEYS and all(
        isinstance(image, str) and re.fullmatch(r"[a-z0-9./:_-]+@sha256:[a-f0-9]{64}", image)
        for image in images.values()), "OPRESTORE_IMAGES_REJECTED")
    need(re.fullmatch(r"postgres:17\.11-bookworm@sha256:[a-f0-9]{64}", images["POSTGRES_IMAGE"]),
         "OPRESTORE_POSTGRES_IMAGE_REJECTED")
    return value


def load_config(path):
    path = private_path(path)
    need(path.stat().st_size <= 64 * 1024, "OPRESTORE_CONFIG_TOO_LARGE")
    cfg = exact_json(path.read_bytes())
    return validate_config(cfg)


def validate_config(cfg):
    fields = {"schema_version", "mode", "project", "container_id", "data_volume", "database", "database_user",
              "application_owner", "postgres_image", "docker_binary", "state_directory", "expected_source"}
    need(isinstance(cfg, dict) and set(cfg) == fields and type(cfg["schema_version"]) is int
         and cfg["schema_version"] == 2 and cfg["mode"] == "qualification-restore", "OPRESTORE_CONFIG_REJECTED")
    need(all(isinstance(cfg[key], str) for key in fields - {"schema_version", "expected_source"}), "OPRESTORE_CONFIG_REJECTED")
    match = re.fullmatch(r"jeongeum-ops-restore-([a-f0-9]{12})", cfg.get("project", ""))
    need(match is not None, "OPRESTORE_PROJECT_REJECTED")
    suffix = match[1]
    need(cfg["database"] == cfg["application_owner"] == "restore_" + suffix and cfg["database_user"] == "postgres",
         "OPRESTORE_TARGET_DATABASE_REJECTED")
    need(cfg["data_volume"] == cfg["project"] + "_postgres_data" and
         isinstance(cfg["container_id"], str) and re.fullmatch(r"[a-f0-9]{64}", cfg["container_id"]),
         "OPRESTORE_TARGET_ID_REJECTED")
    source = validate_source(cfg["expected_source"])
    need(cfg["postgres_image"] == source["images"]["POSTGRES_IMAGE"], "OPRESTORE_TARGET_IMAGE_REJECTED")
    private_path(cfg["state_directory"], directory=True)
    cfg["docker_binary"] = protected_executable(cfg["docker_binary"])
    return cfg


def validate_private_manifest(data):
    try:
        lines = data.decode("utf-8").strip().splitlines()
    except UnicodeError:
        raise RestoreError("OPRESTORE_PRIVATE_MANIFEST_REJECTED") from None
    tables = [line for line in lines if line.startswith("table|")]
    sequences = [line for line in lines if line.startswith("sequence|")]
    flyway = ["flyway|" + ",".join(f"{version}:true" for version in range(1, 13))]
    need(len(lines) == 69 and len(tables) == 39 and len(sequences) == 29
         and [line for line in lines if line.startswith("flyway|")] == flyway,
         "OPRESTORE_PRIVATE_MANIFEST_REJECTED")
    need(all(re.fullmatch(r"table\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[a-f0-9]{32}", line) for line in tables)
         and all(re.fullmatch(r"sequence\|public\.[a-z_][a-z0-9_]*\|[0-9]+\|[tf]", line) for line in sequences),
         "OPRESTORE_PRIVATE_MANIFEST_REJECTED")
    need(len({line.split('|')[1] for line in tables}) == 39 and len({line.split('|')[1] for line in sequences}) == 29,
         "OPRESTORE_DUPLICATE_OBJECT_REJECTED")
    return lines


def decode_archive(authenticated, expected_source=None):
    """Receives bytes only after age has authenticated the complete ciphertext."""
    need(isinstance(authenticated, bytes) and 0 < len(authenticated) <= MAX_CIPHER, "OPRESTORE_ARCHIVE_SIZE_REJECTED")
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(authenticated), mode="rb") as compressed:
            raw = compressed.read(MAX_TAR + 1)
    except (OSError, EOFError):
        raise RestoreError("OPRESTORE_GZIP_REJECTED") from None
    need(len(raw) <= MAX_TAR and len(raw) % tarfile.BLOCKSIZE == 0, "OPRESTORE_ARCHIVE_SIZE_REJECTED")
    contents, offset, total = {}, 0, 0
    try:
        with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
            for member in archive:
                need(member.isfile() and not member.issym() and not member.islnk() and not member.pax_headers
                     and member.name in FILES and member.name not in contents and len(contents) < 3
                     and member.mode == 0o600 and member.uid == member.gid == member.mtime == 0
                     and member.offset == offset and member.offset_data == offset + tarfile.BLOCKSIZE,
                     "OPRESTORE_ARCHIVE_MEMBER_REJECTED")
                limit = MAX_METADATA if member.name == "backup-manifest.json" else MAX_TAR
                need(0 < member.size <= limit and total + member.size <= MAX_TAR, "OPRESTORE_ARCHIVE_SIZE_REJECTED")
                reader = archive.extractfile(member)
                need(reader is not None, "OPRESTORE_ARCHIVE_MEMBER_REJECTED")
                with reader:
                    value = reader.read(member.size + 1)
                need(len(value) == member.size, "OPRESTORE_ARCHIVE_MEMBER_REJECTED")
                contents[member.name] = value
                total += len(value)
                offset = member.offset_data + ((member.size + 511) // 512) * 512
    except (tarfile.TarError, OSError, EOFError):
        raise RestoreError("OPRESTORE_TAR_REJECTED") from None
    need(set(contents) == set(FILES) and len(raw) - offset >= 1024 and not any(raw[offset:]),
         "OPRESTORE_ARCHIVE_COMPLETENESS_REJECTED")
    metadata = exact_json(contents["backup-manifest.json"])
    fields = {"formatVersion", "kind", "mode", "sourceProject", "sourceDatabase", "applicationOwner", "sourceSha",
              "images", "snapshotStartedAtUtc", "tables", "sequences", "files"}
    need(isinstance(metadata, dict) and set(metadata) == fields and type(metadata["formatVersion"]) is int
         and metadata["formatVersion"] == 2 and metadata["kind"] == "operational-database"
         and type(metadata["tables"]) is int and metadata["tables"] == 39
         and type(metadata["sequences"]) is int and metadata["sequences"] == 29, "OPRESTORE_MANIFEST_REJECTED")
    source = validate_source({key: metadata[key] for key in SOURCE_KEYS})
    if expected_source is not None:
        need(source == validate_source(expected_source), "OPRESTORE_SOURCE_MISMATCH")
    timestamp = metadata["snapshotStartedAtUtc"]
    need(isinstance(timestamp, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", timestamp),
         "OPRESTORE_TIMESTAMP_REJECTED")
    try:
        dt.datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except ValueError:
        raise RestoreError("OPRESTORE_TIMESTAMP_REJECTED") from None
    records = metadata["files"]
    need(isinstance(records, dict) and set(records) == set(FILES[:2]), "OPRESTORE_FILE_RECORD_REJECTED")
    for name, record in records.items():
        need(isinstance(record, dict) and set(record) == {"sha256", "bytes"}
             and type(record["bytes"]) is int and record["bytes"] == len(contents[name])
             and isinstance(record["sha256"], str) and re.fullmatch(r"[a-f0-9]{64}", record["sha256"])
             and hashlib.sha256(contents[name]).hexdigest() == record["sha256"], "OPRESTORE_FILE_HASH_REJECTED")
    need(len(contents["database.dump"]) > 20 and contents["database.dump"].startswith(b"PGDMP"), "OPRESTORE_DUMP_REJECTED")
    validate_private_manifest(contents["database.private.manifest"])
    return metadata, contents


class Runner:
    def __init__(self, cfg):
        self.cfg = cfg
        self.delegate = operations().Runner({**cfg, "region": "ap-northeast-2", "aws_credentials_file": "/dev/null"})

    def run(self, *args, **kwargs):
        return self.delegate.run(*args, **kwargs)

    def docker(self, *args, **kwargs):
        return self.delegate.docker(*args, **kwargs)

    def aws(self, *_args, **_kwargs):
        raise RestoreError("OPRESTORE_AWS_FORBIDDEN")


EMPTY_TARGET_SQL = """BEGIN READ ONLY;
-- operational_restore_empty_objects: a zero table count alone is not an empty DB.
SELECT
 (SELECT count(*) FROM pg_class WHERE relnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_proc WHERE pronamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_type WHERE typnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_collation WHERE collnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_conversion WHERE connamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_operator WHERE oprnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_opclass WHERE opcnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_opfamily WHERE opfnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_constraint WHERE connamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_ts_config WHERE cfgnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_ts_dict WHERE dictnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_ts_parser WHERE prsnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_ts_template WHERE tmplnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_statistic_ext WHERE stxnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_default_acl WHERE defaclnamespace='public'::regnamespace)
 + (SELECT count(*) FROM pg_namespace WHERE nspname NOT IN ('public','pg_catalog','information_schema')
      AND nspname !~ '^pg_(toast|temp)(_|$)')
 + (SELECT count(*) FROM pg_extension WHERE extname <> 'plpgsql')
 + (SELECT count(*) FROM pg_event_trigger)
 + (SELECT count(*) FROM pg_foreign_data_wrapper)
 + (SELECT count(*) FROM pg_foreign_server)
 + (SELECT count(*) FROM pg_publication)
 + (SELECT count(*) FROM pg_subscription)
 + (SELECT count(*) FROM pg_largeobject_metadata);
ROLLBACK;
"""


def validate_target(cfg, runner, empty=True):
    binding_keys = ("project", "container_id", "data_volume", "database", "database_user", "application_owner", "postgres_image")
    need(isinstance(getattr(runner, "cfg", None), dict) and all(runner.cfg.get(key) == cfg[key] for key in binding_keys),
         "OPRESTORE_RUNNER_BINDING_REJECTED")
    info = json.loads(runner.docker("inspect", cfg["container_id"]))
    need(isinstance(info, list) and len(info) == 1, "OPRESTORE_INSPECTION_REJECTED")
    row = info[0]
    labels = row.get("Config", {}).get("Labels", {})
    host = row.get("HostConfig", {})
    need(row.get("Id") == cfg["container_id"] and row.get("State", {}).get("Running") is True
         and row.get("State", {}).get("Health", {}).get("Status") == "healthy", "OPRESTORE_TARGET_NOT_HEALTHY")
    need(labels.get("com.docker.compose.project") == cfg["project"] and labels.get("com.docker.compose.service") == "postgres"
         and labels.get("jeongeum.operational-qa") == cfg["project"].removeprefix("jeongeum-ops-restore-")
         and row.get("Config", {}).get("Image") == cfg["postgres_image"], "OPRESTORE_TARGET_BINDING_REJECTED")
    need(host.get("NetworkMode") == "none" and not host.get("Privileged") and not host.get("PortBindings")
         and not any(row.get("NetworkSettings", {}).get("Ports", {}).values()), "OPRESTORE_TARGET_EXPOSURE_REJECTED")
    mounts = row.get("Mounts", [])
    data_mounts = [mount for mount in mounts if mount.get("Destination") == "/var/lib/postgresql/data"]
    need(len(data_mounts) == 1 and data_mounts[0].get("Type") == "volume"
         and data_mounts[0].get("Name") == cfg["data_volume"]
         and all(mount in data_mounts or (mount.get("Type") == "tmpfs" and mount.get("Destination") == "/qa")
                 for mount in mounts),
         "OPRESTORE_TARGET_VOLUME_REJECTED")
    owner = cfg["application_owner"]
    statement = f"""BEGIN READ ONLY;
SELECT current_database() || '|' || current_user;
SELECT pg_get_userbyid(datdba)='{owner}' FROM pg_database WHERE datname=current_database();
SELECT rolsuper::text || '|' || rolcreaterole::text || '|' || rolcreatedb::text || '|' || rolreplication::text || '|' || rolbypassrls::text FROM pg_roles WHERE rolname='{owner}';
SELECT count(*) FROM pg_tables WHERE schemaname NOT IN ('public','pg_catalog','information_schema');
SELECT count(*) FROM pg_tables WHERE schemaname='public';
SELECT count(*) FROM pg_sequences WHERE schemaname='public';
SELECT coalesce(bool_and(tableowner='{owner}'),true) FROM pg_tables WHERE schemaname='public';
ROLLBACK;
"""
    lines = operations().sql(runner, statement).strip().splitlines()
    need(len(lines) == 7 and lines[:4] == [f"{cfg['database']}|postgres", "t", "false|false|false|false|false", "0"]
         and lines[6] == "t", "OPRESTORE_DATABASE_BINDING_REJECTED")
    need(lines[4:6] == (["0", "0"] if empty else ["39", "29"]), "OPRESTORE_TARGET_NOT_EMPTY" if empty else "OPRESTORE_SCHEMA_REJECTED")
    if empty:
        # Do not overwrite a view/function/type-only database or preexisting DB metadata.
        objects = operations().sql(runner, EMPTY_TARGET_SQL).strip().splitlines()
        need(objects == ["0"], "OPRESTORE_TARGET_NOT_EMPTY")


def restore(cfg, cipher_path, identity_path, age_binary, runner=None):
    validate_config(cfg)
    cipher, identity = private_path(cipher_path), private_path(identity_path)
    age = protected_executable(age_binary)
    size = cipher.stat().st_size
    need(200 <= size <= MAX_CIPHER, "OPRESTORE_CIPHER_SIZE_REJECTED")
    encrypted = cipher.read_bytes()
    cipher_sha = hashlib.sha256(encrypted).hexdigest()
    runner = runner or Runner(cfg)
    need(runner.run([age, "--version"]).strip() == b"v1.3.2", "OPRESTORE_AGE_VERSION_REJECTED")
    authenticated = runner.run([age, "--decrypt", "--identity", str(identity)], data=encrypted, timeout=180)
    _metadata, contents = decode_archive(authenticated, cfg["expected_source"])
    need(cipher.stat().st_size == size and hashlib.sha256(cipher.read_bytes()).hexdigest() == cipher_sha,
         "OPRESTORE_CIPHER_CHANGED")
    # Authentication and complete parsing precede every Docker/DB query.
    validate_target(cfg, runner, empty=True)
    dump = contents["database.dump"]
    runner.docker("exec", "-i", "--user", "postgres", cfg["container_id"], "pg_restore", "--username", "postgres",
                  "--dbname", cfg["database"], "--role", cfg["application_owner"], "--no-owner", "--no-acl",
                  "--exit-on-error", "--single-transaction", data=dump, timeout=300)
    validate_target(cfg, runner, empty=False)
    c = core()
    statement = "BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n" + c.MANIFEST_SQL + c.SEQUENCE_SQL + c.FLYWAY_SQL + "\nROLLBACK;\n"
    actual = operations().sql(runner, statement).strip().splitlines()
    need(actual == validate_private_manifest(contents["database.private.manifest"]), "OPRESTORE_FINGERPRINT_MISMATCH")
    return {"ok": True, "status": "independent-restore-verified", "restoredTables": 39, "restoredSequences": 29,
            "sourceMode": cfg["expected_source"]["mode"], "targetMode": "qualification-restore",
            "productionTargetTouched": False, "filesActivated": False, "targetPreserved": True}


def main(argv=None):
    class Parser(argparse.ArgumentParser):
        def error(self, _message):
            raise RestoreError("OPRESTORE_ARGUMENTS_REJECTED")
    try:
        need(sys.platform == "linux" and os.geteuid() == 0, "OPRESTORE_LINUX_ROOT_REQUIRED")
        os.umask(0o077)
        parser = Parser(description=__doc__)
        for name in ("config", "cipher", "identity", "age"):
            parser.add_argument("--" + name, required=True)
        args = parser.parse_args(argv)
        result = restore(load_config(args.config), args.cipher, args.identity, args.age)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except SystemExit as error:
        return int(error.code or 0)
    except BaseException as error:
        code = str(error) if isinstance(error, RestoreError) and re.fullmatch(r"OPRESTORE_[A-Z0-9_]+", str(error)) else "OPRESTORE_OPERATION_FAILED"
        print(json.dumps({"ok": False, "error": code}, separators=(",", ":")))
        return 1


if __name__ == "__main__":
    sys.exit(main())
