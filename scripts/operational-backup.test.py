"""Offline operational backup tests. Docker, AWS, age and SQL are strictly faked."""
import contextlib
import copy
import datetime as dt
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("operational_backup", Path(__file__).with_name("operational-backup.py"))
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)
# Unit fixtures are untrusted user-owned paths; the actual CLI never uses this test-only override.
with patch.object(o, "validate_import_path", side_effect=lambda path: path):
    b = o.core()
NOW = dt.datetime(2026, 10, 26, 0, 0, tzinfo=o.UTC)
PROJECT = "jeongeum-ops-qa-0123456789ab"


@contextlib.contextmanager
def fake_lock(_path):
    yield True


@contextlib.contextmanager
def busy_lock(_path):
    yield False


class FakeAge:
    def __init__(self, command, **_kwargs):
        self.command, self.stdin, self.returncode = command, io.BytesIO(), None

    def wait(self, timeout):
        Path(self.command[self.command.index("--output") + 1]).write_bytes(b"age-encryption.org/v1\n" + b"FAKE-CIPHER-NO-USER-DATA" * 30)
        self.returncode = 0
        return 0

    def poll(self): return self.returncode


class FakeRunner:
    def __init__(self, cfg):
        self.cfg, self.env, self.calls, self.objects = cfg, {}, [], {}
        self.change_sequence, self.tables, self.flyway_bad, self.dump_bad = False, 39, False, False
        self.version_missing, self.head_bad, self.get_bad, self.account_bad = False, False, False, False
        self.failure, self.dump_failures, self.inspect_override, self.role_bad = None, 0, None, False
        self.metric_payload = None

    def run(self, arguments, **_kwargs):
        self.calls.append(("run", *arguments));return b"v1.3.2\n"

    @contextlib.contextmanager
    def snapshot(self):
        self.calls.append(("snapshot",));yield SimpleNamespace(snapshot_id="00000001-00000002-1")

    def inspection(self):
        cfg = self.cfg
        return {"Id": cfg["container_id"], "State": {"Status": "running", "Running": True, "Paused": False, "Dead": False,
                "Restarting": False, "Health": {"Status": "healthy"}},
                "Config": {"Image": cfg["postgres_image"], "Labels": {"com.docker.compose.project": cfg["project"], "com.docker.compose.service": "postgres", "jeongeum.operational-qa": "0123456789ab"}},
                "HostConfig": {"PortBindings": {}, "PublishAllPorts": False, "NetworkMode": "none"}, "NetworkSettings": {"Ports": {"5432/tcp": None}},
                "Mounts": [{"Destination": "/var/lib/postgresql/data", "Type": "volume", "Name": cfg["data_volume"], "RW": True}]}

    def sequences(self):
        return [f"sequence|public.seq_{index}|{index + 1}|{'t' if index % 2 else 'f'}" for index in range(29)]

    def docker(self, *arguments, data=None, output=None, **_kwargs):
        self.calls.append(("docker", *arguments))
        if arguments[0] == "inspect": return json.dumps([self.inspect_override or self.inspection()]).encode()
        if "pg_dump" in arguments:
            if self.dump_failures:
                self.dump_failures -= 1;raise b.BackupError("BACKUP_COMMAND_FAILED")
            output.write((b"INVALID" if self.dump_bad else b"PGDMP") + b"FAKE-DUMP-NO-DATA" * 30)
            return b""
        statement = (data or b"").decode()
        if "current_database()" in statement:
            return (self.cfg["database"] + "|postgres\n0\nt\n" + ("t" if self.role_bad else "f") + "\n").encode()
        if "string_agg(version" in statement:
            tables = [f"table|public.table_{index}|1|{'a' * 32}" for index in range(self.tables)]
            flyway = "flyway|" + ",".join(f"{v}:{'false' if self.flyway_bad and v == 12 else 'true'}" for v in range(1, 13))
            return ("\n".join(tables + self.sequences() + [flyway]) + "\n").encode()
        sequences = self.sequences()
        if self.change_sequence: sequences[0] = "sequence|public.seq_0|9999|t"
        return ("\n".join(sequences) + "\n").encode()

    def aws(self, *arguments):
        self.calls.append(("aws", *arguments))
        if arguments[:2] == ("sts", "get-caller-identity"):
            return json.dumps({"Account": "999999999999" if self.account_bad else self.cfg["expected_account"]}).encode()
        if arguments[:2] == ("cloudwatch", "put-metric-data"):
            self.metric_payload = json.loads(Path(arguments[arguments.index("--metric-data") + 1][7:]).read_text())
            return b""
        operation = arguments[1]
        if self.failure == operation: raise b.BackupError("BACKUP_COMMAND_FAILED")
        key = arguments[arguments.index("--key") + 1]
        if operation == "put-object":
            body = Path(arguments[arguments.index("--body") + 1]).read_bytes()
            version = "version-" + str(len(self.objects));self.objects[key] = (version, body)
            return json.dumps({} if self.version_missing else {"VersionId": version}).encode()
        version, body = self.objects[key]
        if arguments[arguments.index("--version-id") + 1] != version: raise AssertionError("Exact version required")
        if operation == "head-object":
            return json.dumps({"VersionId": version, "ContentLength": len(body), "Metadata": {"sha256": "0" * 64 if self.head_bad else b.hashlib.sha256(body).hexdigest()}, "ServerSideEncryption": "AES256"}).encode()
        if operation == "get-object":
            Path(arguments[arguments.index("--version-id") + 2]).write_bytes(body + (b"CORRUPTION" if self.get_bad else b""))
            return json.dumps({"VersionId": version}).encode()
        raise AssertionError("Unexpected fake AWS operation")


class ReadinessClock:
    def __init__(self):
        self.value, self.sleeps = 0.0, []

    def now(self): return self.value

    def sleep(self, seconds):
        assert 0 < seconds <= 1
        self.sleeps.append(seconds)
        self.value += seconds


class ReadinessRunner(FakeRunner):
    """Observes fake state transitions; any start/recreate command is an error."""
    def __init__(self, cfg, clock, states=None):
        super().__init__(cfg)
        self.clock, self.states = clock, list(states or ["healthy"])
        self.inspections, self.mutations, self.timeouts = 0, {}, []
        self.command_seconds = {}

    def docker(self, *arguments, data=None, output=None, **kwargs):
        assert arguments[0] in ("inspect", "exec"), "Readiness must never start or recreate a source"
        timeout = kwargs.get("timeout")
        if timeout is not None:
            self.timeouts.append((arguments[0], timeout))
            assert 0 < timeout <= 300
        cost = self.command_seconds.get(arguments[0], 0.0)
        if timeout is not None and cost > timeout:
            self.calls.append(("docker", *arguments))
            self.clock.value += timeout
            raise b.BackupError("BACKUP_COMMAND_TIMEOUT")
        self.clock.value += cost
        if arguments[0] != "inspect":
            return super().docker(*arguments, data=data, output=output, **kwargs)
        self.calls.append(("docker", *arguments))
        self.inspections += 1
        item = copy.deepcopy(self.inspect_override or self.inspection())
        state = self.states[0]
        if len(self.states) > 1: self.states.pop(0)
        item["State"].update(Status=state if state in ("created", "exited") else "running",
                             Running=state not in ("created", "exited"), Health={"Status": "starting" if state in ("created", "exited") else state})
        if self.inspections in self.mutations: self.mutations[self.inspections](item)
        return json.dumps([item]).encode()


class OperationalTests(unittest.TestCase):
    def setUp(self):
        self.fixture_parent = None
        if os.name == "nt":
            self.fixture_parent = Path(__file__).resolve().parent.parent / ".local" / "operational-backup-test-tmp"
            self.fixture_parent.mkdir(parents=True, exist_ok=True)
            self.assertFalse(self.fixture_parent.is_symlink())
        self.tmp = tempfile.TemporaryDirectory(prefix="operational-backup-test-", dir=self.fixture_parent)
        self.directory = Path(self.tmp.name)
        self.cfg = {"schema_version": 2, "mode": "qualification", "activation_approved": False, "project": PROJECT,
                    "container_id": "a" * 64, "data_volume": PROJECT + "_postgres_data", "database": "opsqa_0123456789ab", "database_user": "postgres", "application_owner": "opsqa_0123456789ab",
                    "postgres_image": o.POSTGRES_IMAGE, "release_images": {key.upper() + "_IMAGE": "ghcr.io/developer-sw/jeongeum-band-scheduler/" + key + "@sha256:" + "b" * 64 for key in ("backend", "frontend", "caddy")},
                    "source_sha": "c" * 40, "recipient": "age1" + "q" * 58, "bucket": "jeongeum-unit-only", "expected_account": "123456789012", "region": "ap-northeast-2", "prefix": "qualification/",
                    "aws_credentials_file": str(self.directory / "credentials"), "state_directory": str(self.directory), "docker_binary": "/fake/docker", "age_binary": "/fake/age", "aws_binary": "/fake/aws", "maximum_ciphertext_bytes": 128 * 1024 * 1024}
        self.cfg["release_images"]["POSTGRES_IMAGE"] = o.POSTGRES_IMAGE
        self.stack = contextlib.ExitStack()
        self.stack.enter_context(patch.object(b, "secure_path", side_effect=lambda path, **_kw: Path(path)))
        self.stack.enter_context(patch.object(b, "locked", fake_lock))
        self.stack.enter_context(patch.object(b.subprocess, "Popen", FakeAge))
        self.stack.enter_context(patch.object(b, "utc_now", return_value=NOW + dt.timedelta(seconds=30)))
        self.stack.enter_context(patch.object(o.time, "sleep"))
        if os.name == "nt":
            # Native directory fsync belongs to the Linux integration; Windows tests fake this primitive.
            self.stack.enter_context(patch.object(b, "atomic_json", side_effect=lambda path, value: Path(path).write_text(json.dumps(value))))
        self.runner = FakeRunner(self.cfg)

    def tearDown(self):
        self.stack.close()
        if self.fixture_parent is not None:
            self.assertEqual(self.directory.resolve().parent, self.fixture_parent.resolve())
            self.assertTrue(self.directory.name.startswith("operational-backup-test-"))
            self.assertFalse(self.directory.is_symlink())
        self.tmp.cleanup()

    def backup(self):
        now = NOW + dt.timedelta(seconds=30) if (self.directory / "last-success.json").exists() else NOW
        return o.backup(self.cfg, self.runner, now)
    def state(self):return json.loads((self.directory / "last-success.json").read_text())
    def save_state(self, state): (self.directory / "last-success.json").write_text(json.dumps(state))
    def prod(self):
        value = copy.deepcopy(self.cfg)
        value.update(mode="production", project="jeongeum-production-v1", database="jeongeum_prod", application_owner="jeongeum_app", state_directory="/var/lib/jeongeum-operational-backup", data_volume="jeongeum-production-v1_postgres_data", prefix="production/")
        return value

    def test_qualification_and_unapproved_production_shape_validate(self):
        self.assertIs(o.validate_shape(self.cfg), self.cfg)
        self.assertEqual(o.validate_shape(self.prod())["activation_approved"], False)

    def test_legacy_config_mode_and_prefix_are_rejected(self):
        for change in ({"schema_version": 1}, {"mode": "synthetic"}, {"prefix": "synthetic/"}, {"unexpected": True}):
            with self.subTest(change=change), self.assertRaises(o.BackupError): o.validate_shape({**self.cfg, **change})

    def test_production_exact_identity_required(self):
        for key, value in (("database", "jeongeum_dev"), ("application_owner", "postgres"), ("project", PROJECT), ("state_directory", str(self.directory)), ("data_volume", "foreign")):
            with self.subTest(key=key), self.assertRaises(o.BackupError): o.validate_shape({**self.prod(), key: value})

    def test_qualification_cannot_enable_activation(self):
        with self.assertRaises(o.BackupError):o.validate_shape({**self.cfg, "activation_approved": True})
        with self.assertRaises(o.BackupError):o.validate_shape({**self.cfg, "activation_approved": 0})

    def test_production_backup_and_metric_require_approval_before_external_calls(self):
        with patch.object(o, "Runner") as runner:
            for action in (o.backup, o.metric):
                with self.assertRaisesRegex(o.BackupError, "ACTIVATION_NOT_APPROVED"):action(self.prod())
            runner.assert_not_called()

    def test_injected_runner_cannot_redirect_to_another_source(self):
        foreign = FakeRunner(self.prod())
        for action in (o.backup, o.metric):
            with self.assertRaisesRegex(o.BackupError, "RUNNER_BINDING_REJECTED"):action(self.cfg, foreign, NOW)
        self.assertEqual(foreign.calls, [])

    def test_injected_runner_cannot_change_credentials_or_state_directory(self):
        for field, value in (("aws_credentials_file", "/foreign/credentials"), ("state_directory", "/foreign/state")):
            foreign = FakeRunner({**self.cfg, field: value})
            for action in (o.backup, o.metric):
                with self.subTest(field=field), self.assertRaisesRegex(o.BackupError, "RUNNER_BINDING_REJECTED"):action(self.cfg, foreign, NOW)
            self.assertEqual(foreign.calls, [])

    def test_size_pins_and_public_recipient_are_strict(self):
        for change in ({"maximum_ciphertext_bytes": 129 * 1024 * 1024}, {"maximum_ciphertext_bytes": True}, {"postgres_image": "postgres:latest"}, {"recipient": "AGE-PRIVATE-NOT-A-RECIPIENT"}, {"expected_account": "bad"}):
            with self.subTest(change=change), self.assertRaises(o.BackupError):o.validate_shape({**self.cfg, **change})

    def test_sibling_loader_rejects_relative_and_foreign_names(self):
        for path in (Path("production-backup.py"), Path("/safe/unknown.py")):
            with self.assertRaises(o.BackupError):o.validate_import_path(path)

    def test_sibling_loader_rejects_foreign_owner_writable_parent_and_hardlink(self):
        def info(path, *, bad_parent=False, hard=False):
            file = path.name == "production-backup.py"
            return SimpleNamespace(st_mode=(stat.S_IFREG | 0o644) if file else (stat.S_IFDIR | (0o777 if bad_parent else 0o755)), st_uid=0, st_nlink=2 if hard and file else 1, st_size=100)
        for bad_parent, hard in ((True, False), (False, True)):
            with patch.object(Path, "lstat", autospec=True, side_effect=lambda path: info(path, bad_parent=bad_parent, hard=hard)), patch.object(Path, "is_symlink", return_value=False):
                with self.assertRaises(o.BackupError):o.validate_import_path(Path("/safe/production-backup.py"))

    def test_capture_has_v2_operational_manifest_and_shared_snapshot(self):
        work = self.directory / "capture";work.mkdir()
        o.capture(self.runner, work, NOW)
        manifest = json.loads((work / "backup-manifest.json").read_text())
        self.assertEqual((manifest["formatVersion"], manifest["kind"], manifest["mode"]), (2, "operational-database", "qualification"))
        self.assertNotIn("syntheticOnly", manifest)
        self.assertEqual(set(manifest["files"]), set(o.FILES[:2]))
        dumps = [call for call in self.runner.calls if "pg_dump" in call]
        self.assertIn("--snapshot", dumps[0])
        self.assertIn("00000001-00000002-1", dumps[0])

    def test_source_labels_id_image_health_and_volume_are_checked(self):
        for key in ("id", "label", "image", "health", "volume", "read_only"):
            item = self.runner.inspection()
            if key == "id":item["Id"] = "d" * 64
            elif key == "label":item["Config"]["Labels"]["jeongeum.operational-qa"] = "wrong"
            elif key == "image":item["Config"]["Image"] = "postgres:latest"
            elif key == "health":item["State"]["Running"] = False
            elif key == "volume":item["Mounts"].append({"Type": "volume", "Name": "foreign"})
            else:item["Mounts"][0]["RW"] = False
            self.runner.inspect_override = item
            with self.subTest(key=key), self.assertRaises(o.BackupError):o.inspect_source(self.runner)

    def test_source_host_port_and_namespace_sharing_rejected(self):
        for change in ("bindings", "ports", "publish", "host", "container"):
            item = self.runner.inspection()
            if change == "bindings":item["HostConfig"]["PortBindings"] = {"5432/tcp": [{"HostPort": "5432"}]}
            elif change == "ports":item["NetworkSettings"]["Ports"] = {"5432/tcp": [{"HostPort": "5432"}]}
            elif change == "publish":item["HostConfig"]["PublishAllPorts"] = True
            else:item["HostConfig"]["NetworkMode"] = "host" if change == "host" else "container:foreign"
            self.runner.inspect_override = item
            with self.subTest(change=change), self.assertRaisesRegex(o.BackupError, "PUBLIC_PORT_REJECTED"):o.inspect_source(self.runner)

    def test_privileged_application_role_rejected(self):
        self.runner.role_bad = True
        with self.assertRaisesRegex(o.BackupError, "DATABASE_IDENTITY_REJECTED"):o.inspect_source(self.runner)

    def test_extra_bind_and_writable_secret_mounts_rejected(self):
        for target, writable in (("/var/run/docker.sock", False), ("/run/secrets/app_db_password", True), ("/var/lib/postgresql/data", False)):
            item = self.runner.inspection()
            item["Mounts"].append({"Type": "bind", "Destination": target, "RW": writable})
            self.runner.inspect_override = item
            with self.subTest(target=target), self.assertRaises(o.BackupError):o.inspect_source(self.runner)

    def test_only_named_readonly_bind_targets_and_qualification_tmpfs_allowed(self):
        item = self.runner.inspection()
        for target in ("/run/secrets/postgres_admin_password", "/run/secrets/app_db_password", "/docker-entrypoint-initdb.d/10-init-app.sh"):
            item["Mounts"].append({"Type": "bind", "Destination": target, "RW": False})
        item["Mounts"].append({"Type": "tmpfs", "Destination": "/qa", "RW": True})
        self.runner.inspect_override = item
        o.inspect_source(self.runner)
        self.runner.cfg = self.prod()
        item["Config"]["Labels"]["com.docker.compose.project"] = self.runner.cfg["project"]
        item["Mounts"][0]["Name"] = self.runner.cfg["data_volume"]
        with self.assertRaisesRegex(o.BackupError, "EXTRA_MOUNT_REJECTED"):o.inspect_source(self.runner)

    def test_schema_and_flyway_mismatch_do_not_upload_or_retry(self):
        for field in ("tables", "flyway_bad"):
            self.runner = FakeRunner(self.cfg);setattr(self.runner, field, 38 if field == "tables" else True)
            with self.subTest(field=field), self.assertRaises(o.BackupError):self.backup()
            self.assertEqual(sum(call[0] == "snapshot" for call in self.runner.calls), 1)
            self.assertEqual(self.runner.objects, {})

    def test_incomplete_dump_is_rejected_without_upload(self):
        self.runner.dump_bad = True
        with self.assertRaisesRegex(o.BackupError, "DUMP_INCOMPLETE"):self.backup()
        self.assertEqual(self.runner.objects, {})

    def test_changing_sequence_retries_at_most_twice_and_keeps_previous_success(self):
        self.backup();previous = (self.directory / "last-success.json").read_bytes()
        self.runner.change_sequence = True;self.runner.calls.clear()
        with self.assertRaisesRegex(o.BackupError, "SEQUENCE_CHANGED"):self.backup()
        self.assertEqual(sum(call[0] == "snapshot" for call in self.runner.calls), 2)
        self.assertEqual((self.directory / "last-success.json").read_bytes(), previous)

    def test_transient_command_failure_retries_and_succeeds(self):
        self.runner.dump_failures = 1
        self.assertEqual(self.backup()["attempts"], 2)
        self.assertEqual(len(self.runner.objects), 2)

    def test_success_uploads_hourly_and_weekly_with_exact_version_readback(self):
        result = self.backup()
        self.assertTrue(result["successUpdated"]);self.assertEqual(len(self.runner.objects), 2)
        self.assertEqual(self.state()["schemaVersion"], 2)
        self.assertTrue(all(key.startswith("qualification/") for key in self.runner.objects))
        self.assertTrue(all("--version-id" in call for call in self.runner.calls if call[:3] in (("aws", "s3api", "head-object"), ("aws", "s3api", "get-object"))))

    def test_same_iso_week_reuses_weekly_record(self):
        self.backup();weekly = self.state()["weekly"]
        self.assertFalse(self.backup()["weeklyCreated"])
        self.assertEqual(self.state()["weekly"], weekly);self.assertEqual(len(self.runner.objects), 3)

    def test_new_iso_week_creates_new_weekly_record(self):
        self.backup();later = NOW + dt.timedelta(days=7)
        with patch.object(b, "utc_now", return_value=later + dt.timedelta(seconds=30)):
            result = o.backup(self.cfg, self.runner, later)
        self.assertTrue(result["weeklyCreated"]);self.assertEqual(len(self.runner.objects), 4)

    def test_missing_version_head_or_get_corruption_never_updates_success(self):
        for field in ("version_missing", "head_bad", "get_bad"):
            self.runner = FakeRunner(self.cfg);setattr(self.runner, field, True)
            with self.subTest(field=field), self.assertRaises(o.BackupError):self.backup()
            self.assertFalse((self.directory / "last-success.json").exists())

    def test_failed_upload_keeps_success_and_cleans_temporary_plaintext(self):
        self.backup();before = (self.directory / "last-success.json").read_bytes();self.runner.failure = "put-object"
        with self.assertRaises(b.BackupError):self.backup()
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)
        self.assertEqual(list((self.directory / "temporary").iterdir()), [])

    def test_wrong_account_stops_before_snapshot_dump_and_upload(self):
        self.runner.account_bad = True
        with self.assertRaisesRegex(o.BackupError, "ACCOUNT_MISMATCH"):self.backup()
        self.assertFalse(any(call[0] == "snapshot" or "pg_dump" in call for call in self.runner.calls))
        self.assertFalse(any(call[:2] == ("aws", "s3api") for call in self.runner.calls))
        self.assertFalse((self.directory / "last-success.json").exists())

    def test_duplicate_backup_lock_does_not_start_commands(self):
        with patch.object(b, "locked", busy_lock), patch.object(o, "wait_ready") as readiness: result = self.backup()
        self.assertEqual(result["status"], "locked");self.assertEqual(self.runner.calls, [])
        readiness.assert_not_called()

    def test_foreign_state_binding_rejected(self):
        self.backup();state = self.state();state["identity"]["container_id"] = "f" * 64;self.save_state(state)
        self.assertEqual(o.snapshot_age(self.cfg, NOW + dt.timedelta(hours=1)), o.STALE_MINUTES)
        with self.assertRaisesRegex(o.BackupError, "STATE_BINDING"):self.backup()

    def test_recipient_rotation_cannot_reuse_previous_weekly_or_success(self):
        self.backup()
        changed = {**self.cfg, "recipient": "age1" + "p" * 58}
        self.assertEqual(o.snapshot_age(changed, NOW + dt.timedelta(minutes=1)), o.STALE_MINUTES)
        self.assertNotEqual(o.state_identity(changed), o.state_identity(self.cfg))

    def test_hardlinked_success_state_rejected(self):
        self.backup()
        with patch.object(b, "secure_path", return_value=SimpleNamespace(stat=lambda: SimpleNamespace(st_nlink=2))):
            with self.assertRaisesRegex(o.BackupError, "HARDLINK_REJECTED"):o.load_success(self.cfg)

    def test_snapshot_age_counts_data_age_not_verification_finish(self):
        self.backup();state = self.state();state["verifiedAtUtc"] = b.timestamp(NOW + dt.timedelta(minutes=80));self.save_state(state)
        self.assertEqual(o.snapshot_age(self.cfg, NOW + dt.timedelta(minutes=85)), 85)

    def test_missing_malformed_future_and_reversed_state_are_stale(self):
        self.assertEqual(o.snapshot_age(self.cfg, NOW), o.STALE_MINUTES)
        self.backup();original = self.state()
        for started, verified in (("invalid", b.timestamp(NOW)), (b.timestamp(NOW + dt.timedelta(hours=1)), b.timestamp(NOW + dt.timedelta(hours=2))), (b.timestamp(NOW + dt.timedelta(seconds=1)), b.timestamp(NOW))):
            state = copy.deepcopy(original);state.update(snapshotStartedAtUtc=started, verifiedAtUtc=verified);self.save_state(state)
            with self.subTest(started=started):self.assertEqual(o.snapshot_age(self.cfg, NOW), o.STALE_MINUTES)

    def test_metric_has_operational_namespace_dimension_and_no_database_access(self):
        self.backup();self.runner.calls.clear();before = (self.directory / "last-success.json").read_bytes()
        result = o.metric(self.cfg, self.runner, NOW + dt.timedelta(minutes=91))
        self.assertTrue(result["stale"])
        value = self.runner.metric_payload[0]
        self.assertEqual((value["MetricName"], value["Unit"], value["Value"]), (o.METRIC, "None", 91))
        self.assertIn({"Name": "Environment", "Value": "qualification"}, value["Dimensions"])
        self.assertTrue(any(o.NAMESPACE in call for call in self.runner.calls))
        self.assertFalse(any(call[0] == "docker" for call in self.runner.calls))
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)

    def test_missing_success_metric_publishes_stale_without_false_success(self):
        result = o.metric(self.cfg, self.runner, NOW)
        self.assertTrue(result["stale"]);self.assertEqual(self.runner.metric_payload[0]["Value"], o.STALE_MINUTES)
        self.assertFalse((self.directory / "last-success.json").exists())

    def test_keys_cannot_cross_mode_or_inject_paths(self):
        with self.assertRaises(o.BackupError):o.object_key({**self.cfg, "prefix": "production/"}, "hourly", NOW)
        with self.assertRaises(o.BackupError):o.object_key(self.cfg, "hourly", NOW, "../escape")
        self.assertFalse(o.valid_key(self.cfg, "production/hourly/2026/10/26/20261026T000000Z-" + "a" * 32 + ".tar.gz.age"))

    def test_main_never_serializes_unsafe_failure_text(self):
        out = io.StringIO()
        with patch.object(sys, "argv", ["operational-backup.py", "backup"]), patch.object(o.sys, "platform", "linux"), patch.object(o.os, "geteuid", return_value=0, create=True), patch.object(o, "load_config", side_effect=RuntimeError("private-customer-canary")), contextlib.redirect_stdout(out):
            self.assertEqual(o.main(), 1)
        self.assertEqual(json.loads(out.getvalue()), {"ok": False, "error": "OPERATIONAL_OPERATION_FAILED"})

    def readiness_fixture(self, states=None):
        clock = ReadinessClock()
        return clock, ReadinessRunner(self.cfg, clock, states)

    def ready(self, runner, clock, timeout=120):
        return o.wait_ready(self.cfg, runner, timeout_seconds=timeout, clock=clock.now, sleep=clock.sleep)

    def test_readiness_healthy_source_is_readonly_and_all_commands_are_bounded(self):
        clock, runner = self.readiness_fixture()
        self.assertEqual(self.ready(runner, clock), {"ok": True, "status": "source-ready", "sourceStarted": False})
        self.assertEqual([call[1] for call in runner.calls], ["inspect", "inspect", "exec"])
        self.assertEqual(clock.sleeps, [])
        self.assertTrue(all(0 < timeout <= 10 for _, timeout in runner.timeouts))
        self.assertFalse((self.directory / "last-success.json").exists())

    def test_readiness_observes_created_exited_and_delayed_health_without_starting(self):
        clock, runner = self.readiness_fixture(["created", "exited", "starting", "unhealthy", "healthy"])
        self.assertEqual(self.ready(runner, clock)["status"], "source-ready")
        self.assertEqual(clock.value, 4)
        self.assertTrue(all(call[1] in ("inspect", "exec") for call in runner.calls))
        self.assertEqual(sum(call[1] == "exec" for call in runner.calls), 1)

    def test_readiness_timeout_uses_one_global_budget_and_no_database_or_aws(self):
        for state in ("starting", "unhealthy", "created", "exited"):
            with self.subTest(state=state):
                clock, runner = self.readiness_fixture([state])
                with self.assertRaisesRegex(o.BackupError, "SOURCE_READY_TIMEOUT"): self.ready(runner, clock, timeout=3)
                self.assertEqual(clock.value, 3)
                self.assertFalse(any(call[0] == "aws" or call[1] == "exec" for call in runner.calls))
                self.assertTrue(all(timeout <= 3 for _, timeout in runner.timeouts))

    def test_readiness_rejects_invalid_timeout_before_external_commands(self):
        for value in (True, False, 0, -1, 121, 1.5, float("nan"), float("inf"), "2", None):
            clock, runner = self.readiness_fixture()
            with self.subTest(value=repr(value)), self.assertRaisesRegex(o.BackupError, "READINESS_TIMEOUT_REJECTED"):
                o.wait_ready(self.cfg, runner, timeout_seconds=value, clock=clock.now, sleep=clock.sleep)
            self.assertEqual(runner.calls, [])

    def test_readiness_rejects_unapproved_production_and_foreign_runner_before_commands(self):
        with patch.object(o, "Runner") as constructor:
            with self.assertRaisesRegex(o.BackupError, "ACTIVATION_NOT_APPROVED"): o.wait_ready(self.prod())
            constructor.assert_not_called()
        clock, runner = self.readiness_fixture()
        runner.cfg = {**self.cfg, "state_directory": "/foreign-state"}
        with self.assertRaisesRegex(o.BackupError, "RUNNER_BINDING_REJECTED"): self.ready(runner, clock)
        self.assertEqual(runner.calls, [])

    def test_readiness_checks_static_identity_and_isolation_before_waiting(self):
        mutations = {
            "id": lambda item: item.update(Id="f" * 64),
            "project": lambda item: item["Config"]["Labels"].update({"com.docker.compose.project": "foreign"}),
            "service": lambda item: item["Config"]["Labels"].update({"com.docker.compose.service": "backend"}),
            "qa-label": lambda item: item["Config"]["Labels"].update({"jeongeum.operational-qa": "foreign"}),
            "image": lambda item: item["Config"].update(Image="postgres:latest"),
            "volume": lambda item: item["Mounts"][0].update(Name="foreign"),
            "bind": lambda item: item["Mounts"].append({"Type": "bind", "Destination": "/var/run/docker.sock", "RW": False}),
            "port": lambda item: item["HostConfig"].update(PortBindings={"5432/tcp": [{"HostPort": "5432"}]}),
        }
        for name, mutate in mutations.items():
            clock, runner = self.readiness_fixture(["starting"]); runner.mutations[1] = mutate
            with self.subTest(name=name), self.assertRaises(o.BackupError): self.ready(runner, clock)
            self.assertEqual(clock.sleeps, [])
            self.assertEqual([call[1] for call in runner.calls], ["inspect"])

    def test_readiness_rejects_paused_dead_restarting_and_inconsistent_states(self):
        mutations = [lambda item: item["State"].update(Paused=True), lambda item: item["State"].update(Dead=True),
                     lambda item: item["State"].update(Restarting=True), lambda item: item["State"].update(Status="removing"),
                     lambda item: item["State"].update(Running=False), lambda item: item["State"].pop("Paused")]
        for index, mutate in enumerate(mutations):
            clock, runner = self.readiness_fixture(); runner.mutations[1] = mutate
            with self.subTest(index=index), self.assertRaisesRegex(o.BackupError, "SOURCE_STATE_REJECTED"):
                self.ready(runner, clock)
            self.assertEqual(clock.sleeps, [])
            self.assertFalse(any(call[1] == "exec" for call in runner.calls))

    def test_readiness_rejects_missing_or_unknown_running_health_without_query(self):
        mutations = [lambda item: item["State"].pop("Health"), lambda item: item["State"].update(Health=None),
                     lambda item: item["State"].update(Health={"Status": "unknown"})]
        for index, mutate in enumerate(mutations):
            clock, runner = self.readiness_fixture(); runner.mutations[1] = mutate
            with self.subTest(index=index), self.assertRaisesRegex(o.BackupError, "HEALTHCHECK_REJECTED"):
                self.ready(runner, clock)
            self.assertFalse(any(call[1] == "exec" for call in runner.calls))

    def test_readiness_rechecks_target_during_wait_and_before_final_database_guard(self):
        for states in (["starting", "healthy"], ["healthy"]):
            clock, runner = self.readiness_fixture(states)
            runner.mutations[2] = lambda item: item["Mounts"][0].update(Name="foreign")
            with self.subTest(states=states), self.assertRaisesRegex(o.BackupError, "VOLUME_REJECTED"):
                self.ready(runner, clock)
            self.assertFalse(any(call[1] == "exec" for call in runner.calls))

    def test_readiness_uses_remaining_budget_for_each_docker_call(self):
        clock, runner = self.readiness_fixture(); runner.command_seconds["inspect"] = 0.75
        with self.assertRaisesRegex(o.BackupError, "SOURCE_READY_TIMEOUT"): self.ready(runner, clock, timeout=1)
        self.assertEqual(clock.value, 1)
        self.assertEqual(runner.timeouts, [("inspect", 1), ("inspect", 0.25)])

    def test_readiness_deadline_includes_final_readonly_database_guard(self):
        clock, runner = self.readiness_fixture(); runner.command_seconds["exec"] = 1
        with self.assertRaisesRegex(o.BackupError, "SOURCE_READY_TIMEOUT"): self.ready(runner, clock, timeout=1)
        self.assertEqual(clock.value, 1)
        self.assertEqual(sum(call[1] == "exec" for call in runner.calls), 1)
        self.assertFalse(any(call[0] == "aws" for call in runner.calls))

    def test_readiness_database_identity_failure_is_not_polled_or_uploaded(self):
        clock, runner = self.readiness_fixture(); runner.role_bad = True
        with self.assertRaisesRegex(o.BackupError, "DATABASE_IDENTITY_REJECTED"): self.ready(runner, clock)
        self.assertEqual(clock.sleeps, [])
        self.assertEqual(sum(call[1] == "exec" for call in runner.calls), 1)
        self.assertFalse(any(call[0] == "aws" for call in runner.calls))

    def test_backup_timeout_preserves_previous_success_weekly_and_does_not_contact_aws(self):
        self.backup(); before = (self.directory / "last-success.json").read_bytes()
        clock, runner = self.readiness_fixture(["starting"]); original_ready = o.wait_ready
        def bounded_wait(cfg, injected):
            return original_ready(cfg, injected, timeout_seconds=2, clock=clock.now, sleep=clock.sleep)
        with patch.object(o, "wait_ready", side_effect=bounded_wait), self.assertRaisesRegex(o.BackupError, "SOURCE_READY_TIMEOUT"):
            o.backup(self.cfg, runner, NOW + dt.timedelta(seconds=30))
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)
        self.assertEqual(list((self.directory / "temporary").iterdir()), [])
        self.assertFalse(any(call[0] in ("aws", "snapshot") or "pg_dump" in call for call in runner.calls))
        self.assertEqual(runner.objects, {})

    def test_backup_rejects_wrong_source_before_aws_or_creating_temporary_files(self):
        clock, runner = self.readiness_fixture(["starting"])
        runner.mutations[1] = lambda item: item.update(Id="f" * 64)
        with self.assertRaisesRegex(o.BackupError, "SOURCE_BINDING_REJECTED"): o.backup(self.cfg, runner, NOW)
        self.assertFalse(any(call[0] in ("aws", "snapshot") for call in runner.calls))
        self.assertFalse((self.directory / "temporary").exists())
        self.assertFalse((self.directory / "last-success.json").exists())

    def test_backup_readiness_holds_lock_and_capture_time_follows_delay(self):
        clock, runner = self.readiness_fixture(["starting", "starting", "healthy"])
        original_ready = o.wait_ready; held = []
        @contextlib.contextmanager
        def tracked_lock(_path):
            held.append(True)
            try: yield True
            finally: held.pop()
        def bounded_wait(cfg, injected):
            self.assertTrue(held)
            return original_ready(cfg, injected, clock=clock.now, sleep=clock.sleep)
        with patch.object(b, "locked", tracked_lock), patch.object(o, "wait_ready", side_effect=bounded_wait), patch.object(b, "utc_now", side_effect=lambda: NOW + dt.timedelta(seconds=clock.value)):
            self.assertTrue(o.backup(self.cfg, runner)["successUpdated"])
        state = self.state()
        self.assertEqual(b.timestamp(NOW + dt.timedelta(seconds=2)), state["snapshotStartedAtUtc"])
        first_aws = next(i for i, call in enumerate(runner.calls) if call[0] == "aws")
        first_sql = next(i for i, call in enumerate(runner.calls) if call[0] == "docker" and call[1] == "exec")
        self.assertLess(first_sql, first_aws)

    def test_backup_clock_regression_during_readiness_without_previous_success_stops_before_aws(self):
        entered = NOW + dt.timedelta(seconds=60); wall = [entered]
        def backwards_ready(_cfg, _runner):
            wall[0] = entered - dt.timedelta(seconds=1)
            return {"ok": True, "status": "source-ready", "sourceStarted": False}
        with patch.object(o, "wait_ready", side_effect=backwards_ready), patch.object(b, "utc_now", side_effect=lambda: wall[0]), self.assertRaisesRegex(o.BackupError, "CLOCK_MOVED_BACKWARDS"):
            o.backup(self.cfg, self.runner)
        self.assertEqual(self.runner.calls, [])
        self.assertFalse((self.directory / "last-success.json").exists())
        self.assertFalse((self.directory / "temporary").exists())

    def test_backup_clock_regression_during_readiness_keeps_existing_success_before_aws(self):
        self.backup(); before = (self.directory / "last-success.json").read_bytes(); objects = copy.deepcopy(self.runner.objects)
        self.runner.calls.clear(); entered = NOW + dt.timedelta(seconds=60); wall = [entered]
        def backwards_ready(_cfg, _runner):
            # Still newer than the old snapshot: the invocation floor must detect this.
            wall[0] = entered - dt.timedelta(seconds=1)
            return {"ok": True, "status": "source-ready", "sourceStarted": False}
        with patch.object(o, "wait_ready", side_effect=backwards_ready), patch.object(b, "utc_now", side_effect=lambda: wall[0]), self.assertRaisesRegex(o.BackupError, "CLOCK_MOVED_BACKWARDS"):
            o.backup(self.cfg, self.runner)
        self.assertEqual(self.runner.calls, [])
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)
        self.assertEqual(self.runner.objects, objects)

    def test_backup_clock_regression_during_identity_stops_before_capture_and_preserves_success(self):
        self.backup(); before = (self.directory / "last-success.json").read_bytes(); objects = copy.deepcopy(self.runner.objects)
        self.runner.calls.clear(); entered = NOW + dt.timedelta(seconds=60); wall = [entered]
        def ready(_cfg, _runner):
            wall[0] = entered + dt.timedelta(seconds=1)
            return {"ok": True, "status": "source-ready", "sourceStarted": False}
        original_aws = self.runner.aws
        def backwards_identity(*arguments):
            result = original_aws(*arguments)
            if arguments[:2] == ("sts", "get-caller-identity"): wall[0] = entered
            return result
        with patch.object(o, "wait_ready", side_effect=ready), patch.object(b, "utc_now", side_effect=lambda: wall[0]), patch.object(self.runner, "aws", side_effect=backwards_identity), self.assertRaisesRegex(o.BackupError, "CLOCK_MOVED_BACKWARDS"):
            o.backup(self.cfg, self.runner)
        self.assertEqual([call[:3] for call in self.runner.calls], [("aws", "sts", "get-caller-identity")])
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)
        self.assertEqual(self.runner.objects, objects)
        self.assertEqual(list((self.directory / "temporary").iterdir()), [])

    def test_metric_does_not_wait_for_unavailable_database_or_change_success(self):
        self.backup(); before = (self.directory / "last-success.json").read_bytes(); self.runner.calls.clear()
        self.runner.inspect_override = {"State": {"Running": False}}
        with patch.object(o, "wait_ready", side_effect=AssertionError("Metric must remain independent")):
            self.assertFalse(o.metric(self.cfg, self.runner, NOW + dt.timedelta(seconds=30))["stale"])
        self.assertFalse(any(call[0] == "docker" for call in self.runner.calls))
        self.assertEqual((self.directory / "last-success.json").read_bytes(), before)

    def test_readiness_cli_wait_option_is_rejected_for_backup_metric_and_validate(self):
        for action in ("backup", "metric", "validate"):
            out = io.StringIO()
            with self.subTest(action=action), patch.object(sys, "argv", ["operational-backup.py", action, "--wait-seconds", "2"]), patch.object(o, "load_config") as config, contextlib.redirect_stdout(out):
                self.assertEqual(o.main(), 1)
            self.assertEqual(json.loads(out.getvalue()), {"ok": False, "error": "OPERATIONAL_ARGUMENTS_REJECTED"})
            config.assert_not_called()

    def test_readiness_cli_never_prints_unsafe_probe_failure_details(self):
        out = io.StringIO()
        with patch.object(sys, "argv", ["operational-backup.py", "ready", "--wait-seconds", "2"]), patch.object(o.sys, "platform", "linux"), patch.object(o.os, "geteuid", return_value=0, create=True), patch.object(o, "load_config", return_value=self.cfg), patch.object(o, "wait_ready", side_effect=RuntimeError("private-source-canary")), contextlib.redirect_stdout(out):
            self.assertEqual(o.main(), 1)
        self.assertEqual(json.loads(out.getvalue()), {"ok": False, "error": "OPERATIONAL_OPERATION_FAILED"})


if __name__ == "__main__":unittest.main()
