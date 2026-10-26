"""Offline restore failures and isolation contract. No process, key or DB is real."""
import copy
import gzip
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("operational_restore", Path(__file__).with_name("operational-restore.py"))
r = importlib.util.module_from_spec(spec)
spec.loader.exec_module(r)
SUFFIX = "0123456789ab"
PIN = "postgres:17.11-bookworm@sha256:" + "b" * 64
SOURCE = {"mode": "qualification", "sourceProject": "jeongeum-ops-qa-" + SUFFIX,
          "sourceDatabase": "opsqa_" + SUFFIX, "applicationOwner": "opsqa_" + SUFFIX, "sourceSha": "c" * 40,
          "images": {name: ("postgres:17.11-bookworm" if name == "POSTGRES_IMAGE" else "ghcr.io/example/" + name.lower())
                     + "@sha256:" + "b" * 64 for name in r.IMAGE_KEYS}}


def private_manifest():
    tables = [f"table|public.table_{index}|{index}|{'a' * 32}" for index in range(39)]
    sequences = [f"sequence|public.seq_{index}|{index}|{'t' if index % 2 else 'f'}" for index in range(29)]
    flyway = "flyway|" + ",".join(f"{index}:true" for index in range(1, 13))
    return ("\n".join(tables + sequences + [flyway]) + "\n").encode()


def metadata(contents=None):
    contents = contents or {"database.dump": b"PGDMP" + b"FAKE-DUMP-ONLY" * 20, "database.private.manifest": private_manifest()}
    return {"formatVersion": 2, "kind": "operational-database", **copy.deepcopy(SOURCE),
            "snapshotStartedAtUtc": "2026-10-25T09:00:00Z", "tables": 39, "sequences": 29,
            "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)} for name, data in contents.items()}}


def archive(meta=None, contents=None, alter_member=None, manifest_bytes=None, extra=None):
    contents = dict(contents or {"database.dump": b"PGDMP" + b"FAKE-DUMP-ONLY" * 20, "database.private.manifest": private_manifest()})
    contents["backup-manifest.json"] = manifest_bytes if manifest_bytes is not None else json.dumps(meta or metadata()).encode()
    memory = io.BytesIO()
    with tarfile.open(fileobj=memory, mode="w:", format=tarfile.PAX_FORMAT) as output:
        for name, data in [*contents.items(), *(extra or [])]:
            member = tarfile.TarInfo(name)
            member.mode, member.uid, member.gid, member.mtime, member.size = 0o600, 0, 0, 0, len(data)
            if alter_member:
                alter_member(member)
            output.addfile(member, io.BytesIO(data))
    return gzip.compress(memory.getvalue())


class FakeRunner:
    def __init__(self, cfg):
        self.cfg, self.calls, self.restored = cfg, [], False
        self.auth_fails, self.version, self.plain = False, b"v1.3.2", archive()
        self.inspect_change = None
        self.preexisting, self.bad_role, self.bad_fingerprint, self.restore_fails = False, False, False, False
        self.user_objects = "0"

    def run(self, arguments, **kwargs):
        self.calls.append(("run", arguments))
        if arguments[-1] == "--version":
            return self.version
        if self.auth_fails:
            raise r.RestoreError("OPRESTORE_TEST_AUTHENTICATION_FAILED")
        return self.plain

    def docker(self, *arguments, data=None, **kwargs):
        self.calls.append(("docker", arguments))
        if arguments[0] == "inspect":
            row = {"Id": self.cfg["container_id"], "State": {"Running": True, "Health": {"Status": "healthy"}},
                   "Config": {"Image": PIN, "Labels": {"com.docker.compose.project": self.cfg["project"],
                   "com.docker.compose.service": "postgres", "jeongeum.operational-qa": SUFFIX}},
                   "HostConfig": {"NetworkMode": "none", "PortBindings": {}, "Privileged": False},
                   "NetworkSettings": {"Ports": {"5432/tcp": None}},
                   "Mounts": [{"Type": "volume", "Name": self.cfg["data_volume"], "Destination": "/var/lib/postgresql/data"}]}
            if self.inspect_change:
                self.inspect_change(row)
            return json.dumps([row]).encode()
        if "pg_restore" in arguments:
            if self.restore_fails:
                raise r.RestoreError("OPRESTORE_TEST_PG_RESTORE_FAILED")
            self.restored = True
            return b""
        raise AssertionError("Unexpected fake Docker operation")

    def sql(self, statement):
        self.calls.append(("sql", statement))
        if "operational_restore_empty_objects" in statement:
            return self.user_objects + "\n"
        if "current_database()" in statement:
            count = ["39", "29"] if self.restored else (["1", "0"] if self.preexisting else ["0", "0"])
            role = "true|false|false|false|false" if self.bad_role else "false|false|false|false|false"
            return "\n".join([self.cfg["database"] + "|postgres", "t", role, "0", *count, "t"]) + "\n"
        result = private_manifest().decode()
        return result.replace("|0|", "|999|", 1) if self.bad_fingerprint else result


class RestoreTests(unittest.TestCase):
    def setUp(self):
        local = Path(__file__).resolve().parent.parent / ".local"
        local.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="operational-restore-unit-", dir=local)
        self.directory = Path(self.temp.name)
        self.cfg = {"schema_version": 2, "mode": "qualification-restore", "project": "jeongeum-ops-restore-" + SUFFIX,
                    "container_id": "d" * 64, "data_volume": "jeongeum-ops-restore-" + SUFFIX + "_postgres_data",
                    "database": "restore_" + SUFFIX, "database_user": "postgres", "application_owner": "restore_" + SUFFIX,
                    "postgres_image": PIN, "docker_binary": "/fake/docker", "state_directory": str(self.directory),
                    "expected_source": copy.deepcopy(SOURCE)}
        self.identity, self.cipher = self.directory / "fake-identity.txt", self.directory / "cipher.age"
        self.identity.write_bytes(b"CLEARLY-NOT-A-REAL-KEY")
        self.cipher.write_bytes(b"age-encryption.org/v1\n" + b"FAKE-CIPHER" * 30)
        fake_core = SimpleNamespace(MANIFEST_SQL="MANIFEST_SQL;", SEQUENCE_SQL="SEQUENCE_SQL;", FLYWAY_SQL="FLYWAY_SQL;")
        self.fake_ops = SimpleNamespace(core=lambda: fake_core, sql=lambda runner, statement: runner.sql(statement))
        self.patches = [patch.object(r, "operations", return_value=self.fake_ops),
                        patch.object(r, "private_path", side_effect=lambda value, **kwargs: Path(value)),
                        patch.object(r, "protected_executable", side_effect=str)]
        for item in self.patches:
            item.start()
        self.runner = FakeRunner(self.cfg)

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def restore(self):
        return r.restore(self.cfg, self.cipher, self.identity, "/fake/age", self.runner)

    def assert_no_database(self):
        self.assertFalse(any(call[0] in ("docker", "sql") for call in self.runner.calls))

    def test_full_archive_authentication_empty_target_restore_and_fingerprint(self):
        result = self.restore()
        self.assertTrue(result["ok"])
        self.assertTrue(result["targetPreserved"])
        self.assertFalse(result["productionTargetTouched"])
        command = next(call[1] for call in self.runner.calls if call[0] == "docker" and "pg_restore" in call[1])
        for argument in ("--single-transaction", "--no-owner", "--no-acl", "--exit-on-error", "--role"):
            self.assertIn(argument, command)
        self.assertNotIn("--clean", command)

    def test_production_source_can_only_restore_into_qualification_target(self):
        source = {**SOURCE, "mode": "production", "sourceProject": "jeongeum-production-v1",
                  "sourceDatabase": "jeongeum_prod", "applicationOwner": "jeongeum_app"}
        self.cfg["expected_source"] = source
        self.runner.plain = archive({**metadata(), **source})
        self.assertTrue(self.restore()["ok"])

    def test_production_or_other_targets_rejected_before_processes(self):
        for project in ("jeongeum-production-v1", "jeongeum-dev", "jeongeum-ops-qa-" + SUFFIX):
            with self.subTest(project=project):
                self.cfg["project"] = project
                with self.assertRaises(r.RestoreError):
                    self.restore()
        self.assertEqual(self.runner.calls, [])

    def test_target_database_volume_role_and_extra_fields_rejected(self):
        for key, value in (("database", "jeongeum_prod"), ("data_volume", "existing-data"), ("application_owner", "postgres"),
                           ("database_user", "other"), ("container_id", "short"), ("extra", "not-allowed")):
            cfg = copy.deepcopy(self.cfg)
            cfg[key] = value
            with self.subTest(field=key), self.assertRaises(r.RestoreError):
                r.validate_config(cfg)

    def test_source_metadata_mismatch_rejected_before_database(self):
        self.cfg["expected_source"]["sourceSha"] = "e" * 40
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_SOURCE_MISMATCH"):
            self.restore()
        self.assert_no_database()

    def test_authentication_failure_cannot_reach_archive_parser_or_database(self):
        self.runner.auth_fails = True
        with patch.object(r, "decode_archive") as parser, self.assertRaises(r.RestoreError):
            self.restore()
        parser.assert_not_called()
        self.assert_no_database()

    def test_wrong_age_version_rejected_before_authentication_and_database(self):
        self.runner.version = b"v0.0.0"
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_AGE_VERSION_REJECTED"):
            self.restore()
        self.assertEqual(len(self.runner.calls), 1)
        self.assert_no_database()

    def test_small_cipher_rejected_before_processes(self):
        self.cipher.write_bytes(b"invalid")
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_CIPHER_SIZE_REJECTED"):
            self.restore()
        self.assertEqual(self.runner.calls, [])

    def test_cipher_change_during_authentication_rejected_before_database(self):
        original = self.runner.run
        def changed(arguments, **kwargs):
            result = original(arguments, **kwargs)
            if "--decrypt" in arguments:
                self.cipher.write_bytes(b"changed" * 40)
            return result
        with patch.object(self.runner, "run", side_effect=changed), self.assertRaisesRegex(r.RestoreError, "OPRESTORE_CIPHER_CHANGED"):
            self.restore()
        self.assert_no_database()

    def test_traversal_symlink_hardlink_pax_duplicate_and_special_members_rejected(self):
        changes = [lambda member: setattr(member, "name", "../" + member.name),
                   lambda member: setattr(member, "type", tarfile.SYMTYPE),
                   lambda member: setattr(member, "type", tarfile.LNKTYPE),
                   lambda member: setattr(member, "pax_headers", {"comment": "unexpected"}),
                   lambda member: setattr(member, "mode", 0o644),
                   lambda member: setattr(member, "uid", 999)]
        for change in changes:
            with self.subTest(kind=changes.index(change)), self.assertRaises(r.RestoreError):
                r.decode_archive(archive(alter_member=change))
        with self.assertRaises(r.RestoreError):
            r.decode_archive(archive(extra=[("database.dump", b"duplicate")]))

    def test_missing_or_additional_members_rejected(self):
        missing = {"database.dump": b"PGDMP" + b"FAKE" * 20}
        for candidate in (archive(contents=missing), archive(extra=[("extra", b"extra")])):
            with self.assertRaises(r.RestoreError):
                r.decode_archive(candidate)

    def test_corrupt_and_truncated_gzip_rejected(self):
        data = archive()
        corrupted = bytearray(data)
        corrupted[-5] ^= 1
        for candidate in (data[:-8], bytes(corrupted), b"not-gzip"):
            with self.assertRaises(r.RestoreError):
                r.decode_archive(candidate)

    def test_decompression_limit_and_nonzero_trailing_data_rejected(self):
        with patch.object(r, "MAX_TAR", 1024), self.assertRaises(r.RestoreError):
            r.decode_archive(archive())
        with self.assertRaises(r.RestoreError):
            r.decode_archive(gzip.compress(gzip.decompress(archive()) + b"x" * 512))

    def test_manifest_format_kind_counts_unknown_fields_and_timestamp_rejected(self):
        for key, value in (("formatVersion", 1), ("kind", "synthetic"), ("tables", True), ("sequences", 28),
                           ("unknown", "field"), ("snapshotStartedAtUtc", "2026-99-99T00:00:00Z")):
            meta = metadata()
            meta[key] = value
            with self.subTest(field=key), self.assertRaises(r.RestoreError):
                r.decode_archive(archive(meta))

    def test_duplicate_json_key_rejected(self):
        content = json.dumps(metadata()).encode().replace(b'{', b'{"formatVersion":1,', 1)
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_DUPLICATE_JSON_KEY"):
            r.decode_archive(archive(manifest_bytes=content))

    def test_file_hash_size_and_dump_signature_rejected(self):
        meta = metadata()
        meta["files"]["database.dump"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_FILE_HASH_REJECTED"):
            r.decode_archive(archive(meta))
        contents = {"database.dump": b"not-pgdmp" * 10, "database.private.manifest": private_manifest()}
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_DUMP_REJECTED"):
            r.decode_archive(archive(metadata(contents), contents))

    def test_private_manifest_counts_flyway_and_duplicate_objects_rejected(self):
        for data in (private_manifest().replace(b"flyway|1:true", b"flyway|1:false"),
                     private_manifest().replace(b"public.table_1|", b"public.table_0|", 1),
                     private_manifest() + b"unexpected\n"):
            with self.assertRaises(r.RestoreError):
                r.validate_private_manifest(data)

    def test_nonempty_target_never_reaches_pg_restore(self):
        self.runner.preexisting = True
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_TARGET_NOT_EMPTY"):
            self.restore()
        self.assertFalse(any(call[0] == "docker" and "pg_restore" in call[1] for call in self.runner.calls))

    def test_target_user_objects_rejected_even_without_tables_or_sequences(self):
        for objects in ("1", "23", "", "0\n0", "unexpected"):
            with self.subTest(objects=objects):
                self.runner.calls.clear()
                self.runner.user_objects = objects
                with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_TARGET_NOT_EMPTY"):
                    self.restore()
                self.assertFalse(self.runner.restored)
                self.assertFalse(any(call[0] == "docker" and "pg_restore" in call[1] for call in self.runner.calls))

    def test_empty_object_query_runs_only_before_restore_and_covers_user_catalogs(self):
        self.restore()
        checks = [call[1] for call in self.runner.calls if call[0] == "sql" and "operational_restore_empty_objects" in call[1]]
        self.assertEqual(len(checks), 1)
        for catalog in ("pg_class", "pg_proc", "pg_type", "pg_namespace", "pg_extension", "pg_event_trigger",
                        "pg_collation", "pg_conversion", "pg_operator", "pg_ts_config", "pg_default_acl",
                        "pg_foreign_server", "pg_publication", "pg_subscription", "pg_largeobject_metadata"):
            self.assertIn(catalog, checks[0])
        self.assertIn("BEGIN READ ONLY;", checks[0])
        self.assertIn("ROLLBACK;", checks[0])

    def test_source_or_other_runner_binding_rejected_before_any_database_query(self):
        self.runner.cfg = {**self.cfg, "container_id": "a" * 64, "database": SOURCE["sourceDatabase"]}
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_RUNNER_BINDING_REJECTED"):
            r.validate_target(self.cfg, self.runner)
        self.assertEqual(self.runner.calls, [])

    def test_superuser_target_role_rejected(self):
        self.runner.bad_role = True
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_DATABASE_BINDING_REJECTED"):
            self.restore()
        self.assertFalse(self.runner.restored)

    def test_wrong_container_labels_image_volume_health_and_exposure_rejected(self):
        changes = [lambda row: row.update(Id="a" * 64),
                   lambda row: row["Config"]["Labels"].update({"jeongeum.operational-qa": "wrong"}),
                   lambda row: row["Config"].update(Image="postgres:latest"),
                   lambda row: row["State"]["Health"].update(Status="unhealthy"),
                   lambda row: row["HostConfig"].update(NetworkMode="host"),
                   lambda row: row["HostConfig"].update(PortBindings={"5432/tcp": [{"HostPort": "5432"}]}),
                   lambda row: row["Mounts"][0].update(Name="existing-volume"),
                   lambda row: row["Mounts"].append({"Type": "bind", "Destination": "/outside"})]
        for change in changes:
            self.runner.inspect_change = change
            with self.subTest(kind=changes.index(change)), self.assertRaises(r.RestoreError):
                r.validate_target(self.cfg, self.runner)

    def test_private_qa_tmpfs_allowed_but_additional_volumes_rejected(self):
        self.runner.inspect_change = lambda row: row["Mounts"].append({"Type": "tmpfs", "Destination": "/qa"})
        r.validate_target(self.cfg, self.runner)
        self.runner.inspect_change = lambda row: row["Mounts"].append({"Type": "volume", "Destination": "/other"})
        with self.assertRaises(r.RestoreError):
            r.validate_target(self.cfg, self.runner)

    def test_pg_restore_failure_does_not_retry_clean_drop_or_remove_target(self):
        self.runner.restore_fails = True
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_TEST_PG_RESTORE_FAILED"):
            self.restore()
        self.assertFalse(self.runner.restored)
        commands = [call[1] for call in self.runner.calls if call[0] == "docker"]
        self.assertEqual(sum("pg_restore" in command for command in commands), 1)
        self.assertFalse(any(command[0] in ("rm", "start", "restart") or "--clean" in command for command in commands))

    def test_fingerprint_failure_preserves_restored_target(self):
        self.runner.bad_fingerprint = True
        with self.assertRaisesRegex(r.RestoreError, "OPRESTORE_FINGERPRINT_MISMATCH"):
            self.restore()
        self.assertTrue(self.runner.restored)

    def test_success_does_not_call_aws_or_transfer_key_to_docker(self):
        self.restore()
        docker_arguments = repr([call[1] for call in self.runner.calls if call[0] == "docker"])
        self.assertNotIn(str(self.identity), docker_arguments)
        self.assertNotIn("FAKE-KEY", docker_arguments)

    def test_invalid_source_modes_projects_and_images_rejected(self):
        for key, value in (("mode", "synthetic"), ("sourceProject", "jeongeum-dev"), ("sourceSha", "short"),
                           ("images", {}), ("sourceDatabase", "jeongeum_prod")):
            source = copy.deepcopy(SOURCE)
            source[key] = value
            with self.subTest(field=key), self.assertRaises(r.RestoreError):
                r.validate_source(source)


if __name__ == "__main__":
    unittest.main()
