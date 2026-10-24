"""Offline contract/failure tests. All Docker/AWS/age processes are fakes, no database access."""
import contextlib
import datetime as dt
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys = __import__("sys")
sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("production_backup", Path(__file__).with_name("production-backup.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
NOW = dt.datetime(2026, 10, 23, 9, 0, tzinfo=b.UTC)
PROJECT = "jeongeum-prod-qa-0123456789ab"


@contextlib.contextmanager
def fake_lock(_path):
    yield True


@contextlib.contextmanager
def busy_lock(_path):
    yield False


def write_json(path, value):
    Path(path).write_text(json.dumps(value), encoding="utf-8")


class FakeAge:
    """Consumes the archive but emits a clearly fake ciphertext fixture."""
    failure = False
    bad_header = False
    calls = []

    def __init__(self, command, **_kwargs):
        self.command = command
        self.__class__.calls.append(command)
        self.stdin = io.BytesIO()
        self.returncode = None

    def wait(self, timeout):
        self.returncode = 1 if self.failure else 0
        output = Path(self.command[self.command.index("--output") + 1])
        output.write_bytes((b"not-age\n" if self.bad_header else b"age-encryption.org/v1\n") + b"FAKE-CIPHER-FOR-UNIT-TESTS" * 30)
        return self.returncode

    def poll(self):
        return self.returncode


class FakeRunner:
    def __init__(self, cfg):
        self.cfg, self.env, self.calls, self.objects = cfg, {}, [], {}
        self.failure, self.sequence_changed, self.table_count = None, False, 39
        self.sequence_boolean_override = None
        self.version_missing, self.head_wrong, self.get_corrupt = False, False, False
        self.account_wrong, self.inspect_wrong = False, False
        self.metric_payload = None

    def run(self, arguments, **_kwargs):
        self.calls.append(arguments)
        return b"v1.3.2\n"

    @contextlib.contextmanager
    def snapshot(self):
        yield SimpleNamespace(snapshot_id="00000001-00000002-1")

    def sequences(self):
        lines = [f"sequence|public.seq_{index}|{index + 1}|{'t' if index % 2 == 0 else 'f'}" for index in range(29)]
        if self.sequence_boolean_override is not None:
            lines[0] = f"sequence|public.seq_0|1|{self.sequence_boolean_override}"
        return lines

    def docker(self, *arguments, data=None, output=None, **_kwargs):
        self.calls.append(arguments)
        if arguments[0] == "inspect":
            cfg = self.cfg
            return json.dumps([{"Id": cfg["container_id"], "State": {"Running": True, "Health": {"Status": "healthy"}},
                                "Config": {"Image": cfg["postgres_image"], "Labels": {"com.docker.compose.project": "wrong" if self.inspect_wrong else cfg["project"], "com.docker.compose.service": "postgres"}},
                                "Mounts": [{"Destination": "/var/lib/postgresql/data", "Type": "volume", "Name": cfg["data_volume"]}]}]).encode()
        if "pg_dump" in arguments:
            output.write(b"PGDMP" + b"FAKE-DUMP-NO-REAL-DATA" * 30)
            if self.failure == "dump":
                raise b.BackupError("BACKUP_COMMAND_FAILED")
            return b""
        statement = (data or b"").decode()
        if "current_database()" in statement:
            return f"{self.cfg['database']}|postgres\n0\nt\n".encode()
        if "string_agg(version" in statement:
            if self.failure == "manifest":
                raise b.BackupError("BACKUP_COMMAND_FAILED")
            tables = [f"table|public.table_{index}|1|{'a' * 32}" for index in range(self.table_count)]
            return ("\n".join(tables + self.sequences() + ["flyway|" + ",".join(f"{v}:true" for v in range(1, 13))]) + "\n").encode()
        sequences = self.sequences()
        if self.sequence_changed:
            sequences[0] = "sequence|public.seq_0|999|t"
        return ("\n".join(sequences) + "\n").encode()

    def aws(self, *arguments):
        self.calls.append(arguments)
        if arguments[:2] == ("sts", "get-caller-identity"):
            return json.dumps({"Account": "999999999999" if self.account_wrong else self.cfg["expected_account"]}).encode()
        if arguments[:2] == ("cloudwatch", "put-metric-data"):
            path = arguments[arguments.index("--metric-data") + 1].removeprefix("file://")
            self.metric_payload = json.loads(Path(path).read_text())
            return b""
        operation = arguments[1]
        if self.failure == operation:
            raise b.BackupError("BACKUP_COMMAND_FAILED")
        key = arguments[arguments.index("--key") + 1]
        if operation == "put-object":
            body = Path(arguments[arguments.index("--body") + 1]).read_bytes()
            version = f"version-{len(self.objects)}"
            self.objects[key] = (version, body)
            return json.dumps({} if self.version_missing else {"VersionId": version}).encode()
        version, body = self.objects[key]
        assert arguments[arguments.index("--version-id") + 1] == version
        if operation == "head-object":
            return json.dumps({"VersionId": "wrong" if self.head_wrong else version, "ContentLength": len(body),
                               "Metadata": {"sha256": b.hashlib.sha256(body).hexdigest()}, "ServerSideEncryption": "AES256"}).encode()
        if operation == "get-object":
            path = Path(arguments[arguments.index("--version-id") + 2])
            path.write_bytes(body + (b"CORRUPTION" if self.get_corrupt else b""))
            return json.dumps({"VersionId": version}).encode()
        raise AssertionError("Unexpected fake operation")


class BackupTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.root = Path(self.directory.name)
        self.cfg = {"schema_version": 1, "mode": "synthetic", "project": PROJECT, "container_id": "a" * 64,
                    "data_volume": PROJECT + "_postgres_data", "database": "qa_0123456789ab", "database_user": "postgres", "application_owner": "qa_0123456789ab",
                    "postgres_image": "postgres:17.11-bookworm@sha256:" + "b" * 64,
                    "release_images": {name: ("postgres:17.11-bookworm" if name == "POSTGRES_IMAGE" else "ghcr.io/example/component") + "@sha256:" + "b" * 64 for name in ("BACKEND_IMAGE", "FRONTEND_IMAGE", "CADDY_IMAGE", "POSTGRES_IMAGE")},
                    "source_sha": "c" * 40, "recipient": "age1" + "q" * 58, "bucket": "jeongeum-backup-unit-tests", "expected_account": "111111111111",
                    "region": "ap-northeast-2", "prefix": "synthetic/", "aws_credentials_file": str(self.root / "credentials"), "state_directory": str(self.root),
                    "docker_binary": b.sys.executable, "age_binary": b.sys.executable, "aws_binary": b.sys.executable, "maximum_ciphertext_bytes": 1024 * 1024}
        (self.root / "credentials").write_text("[backup]\naws_access_key_id=UNITKEY\naws_secret_access_key=UNIT-SECRET-NOT-REAL\n", encoding="utf-8")
        self.runner = FakeRunner(self.cfg)
        self.patches = [patch.object(b, "secure_path", side_effect=lambda path, **_kwargs: Path(path)), patch.object(b, "locked", fake_lock),
                        patch.object(b, "atomic_json", write_json), patch.object(b, "utc_now", return_value=NOW), patch.object(b.subprocess, "Popen", FakeAge),
                        patch.object(b.signal, "SIGKILL", 9, create=True)]
        for item in self.patches:
            item.start()
        FakeAge.failure, FakeAge.bad_header, FakeAge.calls = False, False, []

    def tearDown(self):
        for item in reversed(self.patches):
            item.stop()
        self.directory.cleanup()

    def run_backup(self, now=NOW):
        return b.backup(self.cfg, self.runner, now)

    def state(self):
        return json.loads(b.success_path(self.cfg).read_text())

    def assert_no_success(self):
        self.assertFalse(b.success_path(self.cfg).exists())
        self.assertEqual(list((self.root / "temporary").glob(".run-*")), [])
        self.assertFalse(any("delete" in str(call).lower() for call in self.runner.calls))

    def test_complete_snapshot_encrypt_upload_version_verify_then_success(self):
        result = self.run_backup()
        self.assertTrue(result["successUpdated"])
        self.assertTrue(result["weeklyCreated"])
        state = self.state()
        self.assertEqual(state["verifiedAtUtc"], "2026-10-23T09:00:00Z")
        self.assertEqual(len(self.runner.objects), 2)
        self.assertNotEqual(state["hourly"]["key"], state["weekly"]["key"])
        self.assertTrue(all("--if-none-match" in call for call in self.runner.calls if "put-object" in call))
        self.assertEqual(list((self.root / "temporary").iterdir()), [])
        self.assertIn("--recipient", FakeAge.calls[0])
        self.assertNotIn("--identity", FakeAge.calls[0])

    def test_dump_failure_does_not_upload_or_write_success(self):
        self.runner.failure = "dump"
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertEqual(self.runner.objects, {})
        self.assert_no_success()

    def test_manifest_failure_prevents_dump_and_upload(self):
        self.runner.failure = "manifest"
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertFalse(any("pg_dump" in call for call in self.runner.calls))
        self.assert_no_success()

    def test_incomplete_schema_and_sequence_change_prevent_upload(self):
        for option in ("schema", "sequence"):
            self.runner.table_count, self.runner.sequence_changed = (38, False) if option == "schema" else (39, True)
            with self.subTest(option=option), self.assertRaises(b.BackupError): self.run_backup()
            self.assertEqual(self.runner.objects, {})
            self.assert_no_success()

    def test_sequence_sql_quotes_and_normalizes_both_boolean_states(self):
        # Evaluate the actual dynamically generated SELECT, rather than a copied
        # expected SQL string. SQLite is an in-memory SQL model, not PostgreSQL
        # verification and never opens the application database or a DB file.
        match = re.search(r"format\('((?:''|[^'])*)',\s*\n", b.SEQUENCE_SQL)
        self.assertIsNotNone(match)
        template = match.group(1).replace("''", "'")
        generated = template.replace("%L", "'sequence|public.demo'").replace("%I.%I", '"public"."demo"')
        self.assertNotIn("%", generated)
        with contextlib.closing(sqlite3.connect(":memory:")) as database:
            database.execute("ATTACH DATABASE ':memory:' AS public")
            database.execute("CREATE TABLE public.demo (last_value INTEGER, is_called BOOLEAN)")
            database.executemany("INSERT INTO public.demo VALUES (?, ?)", [(17, True), (18, False)])
            actual = [row[0] for row in database.execute(generated)]
        self.assertEqual(actual, ["sequence|public.demo|17|t", "sequence|public.demo|18|f"])

    def test_malformed_sequence_boolean_never_dumps_uploads_or_records_success(self):
        for invalid in ("true", "false", "TRUE", "FALSE", "1", "0", "t|extra", ""):
            self.runner.sequence_boolean_override = invalid
            with self.subTest(invalid=invalid), self.assertRaisesRegex(b.BackupError, "^BACKUP_MANIFEST_REJECTED$"):
                self.run_backup()
            self.assertFalse(any("pg_dump" in call for call in self.runner.calls))
            self.assertEqual(self.runner.objects, {})
            self.assert_no_success()

    def test_age_exit_failure_never_uploads_partial_output(self):
        FakeAge.failure = True
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertEqual(self.runner.objects, {})
        self.assert_no_success()

    def test_snapshot_export_failure_never_starts_dump_or_upload(self):
        @contextlib.contextmanager
        def unavailable():
            raise b.BackupError("BACKUP_SNAPSHOT_TIMEOUT")
            yield
        self.runner.snapshot = unavailable
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertFalse(any("pg_dump" in call for call in self.runner.calls))
        self.assertEqual(self.runner.objects, {})
        self.assert_no_success()

    def test_age_broken_pipe_cleans_partial_files_and_never_updates_success(self):
        class BrokenInput(io.BytesIO):
            def write(self, value):
                raise BrokenPipeError("UNIT-SECRET-DETAIL-MUST-NOT-BE-LOGGED")
        class BrokenAge(FakeAge):
            def __init__(self, command, **kwargs):
                super().__init__(command, **kwargs)
                self.stdin, self.pid = BrokenInput(), 123456
        with patch.object(b.subprocess, "Popen", BrokenAge), patch.object(b.os, "killpg", create=True) as kill:
            with self.assertRaises(BrokenPipeError): self.run_backup()
            kill.assert_called_once()
        self.assertEqual(self.runner.objects, {})
        self.assert_no_success()

    def test_command_timeout_kills_group_and_returns_fixed_error(self):
        class TimedOut:
            pid = 123456
            def communicate(self, _data, timeout):
                raise b.subprocess.TimeoutExpired("SECRET-COMMAND", timeout, output="SECRET-OUTPUT")
            def poll(self): return None
            def wait(self, timeout): self.returncode = -9
        with patch.object(b.subprocess, "Popen", return_value=TimedOut()), patch.object(b.os, "killpg", create=True) as kill:
            with self.assertRaisesRegex(b.BackupError, "^BACKUP_COMMAND_TIMEOUT$"):
                b.Runner(self.cfg).run(["FAKE-DO-NOT-EXECUTE"], timeout=1)
            kill.assert_called_once()

    def test_ciphertext_header_failure_never_uploads(self):
        FakeAge.bad_header = True
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertEqual(self.runner.objects, {})
        self.assert_no_success()

    def test_upload_head_get_failure_and_integrity_mismatch_keep_old_success(self):
        self.run_backup()
        original = b.success_path(self.cfg).read_bytes()
        for flag in ("put-object", "head-object", "get-object", "version", "head", "corruption"):
            self.runner.failure, self.runner.version_missing, self.runner.head_wrong, self.runner.get_corrupt = None, False, False, False
            if flag in ("put-object", "head-object", "get-object"): self.runner.failure = flag
            if flag == "version": self.runner.version_missing = True
            if flag == "head": self.runner.head_wrong = True
            if flag == "corruption": self.runner.get_corrupt = True
            with self.subTest(flag=flag), self.assertRaises(b.BackupError): self.run_backup()
            self.assertEqual(b.success_path(self.cfg).read_bytes(), original)
            self.assertEqual(list((self.root / "temporary").iterdir()), [])
        self.assertFalse(any("delete-object" in call for call in self.runner.calls))

    def test_same_week_reuses_verified_weekly_but_creates_unique_hourly(self):
        self.run_backup()
        old = self.state()
        result = self.run_backup()
        new = self.state()
        self.assertFalse(result["weeklyCreated"])
        self.assertNotEqual(new["hourly"]["key"], old["hourly"]["key"])
        self.assertEqual(new["weekly"], old["weekly"])
        self.assertEqual(len(self.runner.objects), 3)

    def test_new_iso_week_creates_new_weekly(self):
        self.run_backup()
        next_week = NOW + dt.timedelta(days=7)
        with patch.object(b, "utc_now", return_value=next_week): result = self.run_backup(next_week)
        self.assertTrue(result["weeklyCreated"])
        self.assertEqual(len(self.runner.objects), 4)

    def test_wrong_container_and_aws_account_prevent_access_or_upload(self):
        self.runner.account_wrong = True
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertFalse(any("inspect" in call for call in self.runner.calls))
        self.runner.account_wrong, self.runner.inspect_wrong = False, True
        with self.assertRaises(b.BackupError): self.run_backup()
        self.assertFalse(any("pg_dump" in call for call in self.runner.calls))
        self.assert_no_success()

    def test_overlapping_run_is_skipped_without_commands(self):
        with patch.object(b, "locked", busy_lock): result = self.run_backup()
        self.assertEqual(result, {"ok": True, "status": "locked", "successUpdated": False})
        self.assertEqual(self.runner.calls, [])

    def test_metric_independent_lock_does_not_block_on_backup_lock(self):
        self.run_backup()
        paths = []
        @contextlib.contextmanager
        def select_lock(path):
            paths.append(Path(path).name)
            yield Path(path).name == "metric.lock"
        with patch.object(b, "locked", select_lock): result = b.metric(self.cfg, self.runner, NOW + dt.timedelta(minutes=91))
        self.assertTrue(result["stale"])
        self.assertEqual(paths, ["metric.lock"])
        metric = self.runner.metric_payload[0]
        self.assertEqual(metric["Value"], 91)
        self.assertEqual(metric["Unit"], "None")
        self.assertEqual(metric["Dimensions"], [{"Name": "Environment", "Value": "synthetic"}, {"Name": "Instance", "Value": "jeongeum-prod-01"}])
        self.assertFalse(result["successUpdated"])

    def test_missing_corrupt_future_state_all_report_stale_not_zero(self):
        self.assertEqual(b.verified_age(self.cfg, NOW), b.STALE_MINUTES)
        b.success_path(self.cfg).write_text("broken-json")
        self.assertEqual(b.verified_age(self.cfg, NOW), b.STALE_MINUTES)
        b.success_path(self.cfg).unlink()
        self.run_backup()
        value = self.state()
        value["verifiedAtUtc"] = "2026-10-24T09:00:00Z"
        write_json(b.success_path(self.cfg), value)
        self.assertEqual(b.verified_age(self.cfg, NOW), b.STALE_MINUTES)
        value["verifiedAtUtc"] = "2026-10-23T09:00:00Z"
        value["identity"]["bucket"] = "wrong"
        write_json(b.success_path(self.cfg), value)
        self.assertEqual(b.verified_age(self.cfg, NOW), b.STALE_MINUTES)

    def test_missing_state_metric_publishes_alarm_value_without_success_file(self):
        result = b.metric(self.cfg, self.runner, NOW)
        self.assertTrue(result["stale"])
        self.assertEqual(self.runner.metric_payload[0]["Value"], b.STALE_MINUTES)
        self.assertFalse(b.success_path(self.cfg).exists())

    def test_clock_reversal_never_commits_success(self):
        with patch.object(b, "utc_now", return_value=NOW - dt.timedelta(seconds=1)):
            with self.assertRaises(b.BackupError): self.run_backup()
        self.assert_no_success()

    def test_key_scope_and_uuid_are_strict(self):
        for kind in ("../production", "production", "hourly/../../"):
            with self.assertRaises(b.BackupError): b.object_key(self.cfg, kind, NOW)
        with self.assertRaises(b.BackupError): b.object_key(self.cfg, "hourly", NOW, "../bad")
        for key in ("production/hourly/file.age", "synthetic/hourly/../../file.age"):
            with self.assertRaises(b.BackupError): b.upload_verified(self.runner, self.root / "absent", self.root, key)
        self.assertEqual(self.runner.calls, [])

    def test_config_rejects_production_target_prefix_endpoint_and_secret_identity(self):
        path = self.root / "config.json"
        changes = [{"mode": "production"}, {"database": "jeongeum"}, {"project": "jeongeum-dev"}, {"prefix": "production/"},
                   {"recipient": "AGE-SECRET-KEY-NEVER-ACCEPT"}, {"region": "us-east-1"}, {"endpoint_url": "http://untrusted.invalid"}]
        for changed in changes:
            write_json(path, self.cfg | changed)
            with self.subTest(changed=changed), self.assertRaises(b.BackupError): b.load_config(path)
        write_json(path, self.cfg)
        self.assertEqual(b.load_config(path)["mode"], "synthetic")

    def test_credentials_reject_process_and_foreign_profiles(self):
        path = self.root / "config.json"
        write_json(path, self.cfg)
        for text in ("[backup]\ncredential_process=evil\n", "[default]\naws_access_key_id=x\naws_secret_access_key=y\n", "[DEFAULT]\nendpoint_url=http://invalid\n[backup]\naws_access_key_id=x\naws_secret_access_key=y\n"):
            Path(self.cfg["aws_credentials_file"]).write_text(text)
            with self.subTest(text=text), self.assertRaises(b.BackupError): b.load_config(path)

    def test_environment_cannot_override_account_endpoint_or_docker_host(self):
        with patch.dict(os.environ, {"AWS_ACCESS_KEY_ID": "wrong", "AWS_ENDPOINT_URL": "http://wrong", "AWS_PROFILE": "evil", "DOCKER_HOST": "tcp://wrong", "AWS_WEB_IDENTITY_TOKEN_FILE": "/secret"}):
            runner = b.Runner(self.cfg)
        self.assertNotIn("AWS_ACCESS_KEY_ID", runner.env)
        self.assertNotIn("AWS_ENDPOINT_URL", runner.env)
        self.assertNotIn("AWS_WEB_IDENTITY_TOKEN_FILE", runner.env)
        self.assertNotIn("DOCKER_HOST", runner.env)
        self.assertEqual(runner.env["AWS_PROFILE"], "backup")
        self.assertEqual(runner.env["AWS_EC2_METADATA_DISABLED"], "true")

    def test_owner_permissions_and_type_checks_are_fail_closed(self):
        b.validate_file_info(SimpleNamespace(st_uid=0, st_mode=stat.S_IFREG | 0o600))
        b.validate_file_info(SimpleNamespace(st_uid=0, st_mode=stat.S_IFDIR | 0o700), directory=True)
        for uid, mode in ((1000, stat.S_IFREG | 0o600), (0, stat.S_IFREG | 0o640), (0, stat.S_IFREG | 0o604), (0, stat.S_IFLNK | 0o600), (0, stat.S_IFDIR | 0o700)):
            with self.subTest(uid=uid, mode=mode), self.assertRaises(b.BackupError): b.validate_file_info(SimpleNamespace(st_uid=uid, st_mode=mode))

    def test_main_sanitizes_unexpected_error(self):
        with patch.object(b.sys, "argv", ["backup", "validate"]), patch.object(b.sys, "platform", "linux"), patch.object(b.os, "geteuid", return_value=0, create=True), patch.object(b.os, "umask"), patch.object(b.signal, "signal"), patch.object(b, "load_config", side_effect=RuntimeError("PRIVATE-SECRET-ENDPOINT")), patch.object(b.sys, "stdout", new_callable=io.StringIO) as output:
            self.assertEqual(b.main(), 1)
            self.assertEqual(json.loads(output.getvalue()), {"ok": False, "error": "BACKUP_OPERATION_FAILED"})

    def test_validate_cli_runs_no_external_commands(self):
        with patch.object(b.sys, "argv", ["backup", "validate"]), patch.object(b.sys, "platform", "linux"), patch.object(b.os, "geteuid", return_value=0, create=True), patch.object(b.os, "umask"), patch.object(b.signal, "signal"), patch.object(b, "load_config", return_value=self.cfg), patch.object(b, "Runner") as external, patch.object(b.sys, "stdout", new_callable=io.StringIO) as output:
            self.assertEqual(b.main(), 0)
            self.assertEqual(json.loads(output.getvalue()), {"ok": True, "status": "configuration-valid", "mode": "synthetic", "externalCommandsRun": False})
            external.assert_not_called()


if __name__ == "__main__":
    unittest.main(verbosity=2)
