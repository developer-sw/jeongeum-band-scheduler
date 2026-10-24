"""Offline synthetic-source start guards; fake Docker only, no DB or network access."""
import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import stat
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


r = module("backup_source_readiness_tests_target", "backup-source-readiness.py")
b = module("production_backup_readiness_tests_dependency", "production-backup.py")
PROJECT = "jeongeum-prod-qa-0123456789ab"


class Clock:
    def __init__(self):
        self.value = 0.0

    def now(self):
        return self.value

    def sleep(self, value):
        self.value += value


class FakeDocker:
    def __init__(self, cfg, clock):
        self.cfg, self.clock, self.calls = cfg, clock, []
        self.failure, self.mutations, self.inspections = None, {}, 0
        self.health = ["healthy"]
        self.item = {"Id": cfg["container_id"],
                     "Config": {"Image": cfg["postgres_image"], "Labels": {
                         "com.docker.compose.project": cfg["project"], "com.docker.compose.service": "postgres"}},
                     "HostConfig": {"PortBindings": {}, "PublishAllPorts": False, "NetworkMode": PROJECT + "_internal"},
                     "NetworkSettings": {"Ports": {"5432/tcp": None}},
                     "Mounts": [{"Type": "volume", "Name": cfg["data_volume"], "Destination": "/var/lib/postgresql/data"},
                                {"Type": "bind", "Source": "/private/fake-password", "Destination": "/run/secrets/postgres_password"}],
                     "State": {"Status": "running", "Running": True, "Paused": False, "Restarting": False, "Dead": False,
                               "Health": {"Status": "healthy"}}}

    def docker(self, *arguments, **kwargs):
        self.calls.append((arguments, kwargs))
        assert 0 < kwargs["timeout"] <= r.COMMAND_SECONDS
        self.clock.value += 0.01
        if self.failure:
            raise self.failure
        if arguments[0] == "inspect":
            assert arguments == ("inspect", self.cfg["container_id"])
            self.inspections += 1
            item = copy.deepcopy(self.item)
            if self.health:
                item["State"]["Health"]["Status"] = self.health[0]
                if len(self.health) > 1:
                    self.health.pop(0)
            if self.inspections in self.mutations:
                self.mutations[self.inspections](item)
            return json.dumps([item]).encode()
        if arguments[0] == "start":
            assert arguments == ("start", self.cfg["container_id"])
            self.item["State"]["Status"], self.item["State"]["Running"] = "running", True
            return self.cfg["container_id"].encode()
        if arguments[0] == "exec":
            assert arguments[:4] == ("exec", "-i", "--user", "postgres")
            assert arguments[4] == self.cfg["container_id"]
            sql = kwargs["data"].decode()
            assert "BEGIN READ ONLY;" in sql and "ROLLBACK;" in sql
            assert "current_database()" in sql and "tableowner=" in sql
            return f"{self.cfg['database']}|postgres\n0\nt\n".encode()
        raise AssertionError("Only fake inspect/start and final read-only binding query are allowed")


class ReadinessTests(unittest.TestCase):
    def setUp(self):
        self.cfg = {"container_id": "a" * 64, "project": PROJECT, "data_volume": PROJECT + "_postgres_data",
                    "postgres_image": "postgres:17.11-bookworm@sha256:" + "b" * 64,
                    "database": "qa_0123456789ab", "database_user": "postgres", "application_owner": "qa_0123456789ab"}
        self.clock = Clock()
        self.runner = FakeDocker(self.cfg, self.clock)

    def ready(self):
        return r.ensure_ready(b, self.cfg, self.runner, clock=self.clock.now, sleep=self.clock.sleep)

    def starts(self):
        return [args for args, _kwargs in self.runner.calls if args[0] == "start"]

    def stopped(self, status="exited"):
        self.runner.item["State"].update(Status=status, Running=False)

    def assert_refused_without_start(self, mutate, code):
        self.stopped()
        mutate(self.runner.item)
        with self.assertRaisesRegex(r.ReadinessError, "^" + code + "$"):
            self.ready()
        self.assertEqual(self.starts(), [])
        self.assertFalse(any(args[0] == "exec" for args, _kwargs in self.runner.calls))

    def test_running_healthy_source_is_not_restarted_and_database_binding_is_reused(self):
        result = self.ready()
        self.assertEqual(result, {"ok": True, "status": "source-ready", "syntheticOnly": True, "existingSourceStarted": False})
        self.assertEqual(self.starts(), [])
        self.assertEqual([args[0] for args, _kwargs in self.runner.calls], ["inspect", "inspect", "inspect", "exec"])

    def test_exited_and_created_source_start_exactly_once_and_wait_for_health(self):
        for status in ("exited", "created"):
            with self.subTest(status=status):
                self.setUp()
                self.stopped(status)
                self.runner.health = ["starting", "starting", "healthy"]
                self.assertTrue(self.ready()["existingSourceStarted"])
                self.assertEqual(self.starts(), [("start", self.cfg["container_id"])])
                self.assertGreater(self.clock.value, 1)
                self.assertTrue(all(args[0] in ("inspect", "start", "exec") for args, _kwargs in self.runner.calls))

    def test_missing_source_never_creates_or_starts_anything(self):
        self.runner.failure = b.BackupError("BACKUP_COMMAND_FAILED")
        with self.assertRaisesRegex(b.BackupError, "BACKUP_COMMAND_FAILED"):
            self.ready()
        self.assertEqual(self.starts(), [])
        self.assertEqual(len(self.runner.calls), 1)

    def test_wrong_or_abbreviated_container_identity_is_refused_before_start(self):
        for value in ("a" * 12, "c" * 64, ""):
            with self.subTest(value=value):
                self.setUp()
                self.assert_refused_without_start(lambda item: item.update(Id=value), "BACKUP_SOURCE_ID_REJECTED")

    def test_wrong_project_or_other_service_never_starts(self):
        for label, value in (("com.docker.compose.project", "other-project"), ("com.docker.compose.service", "backend")):
            with self.subTest(label=label):
                self.setUp()
                self.assert_refused_without_start(lambda item: item["Config"]["Labels"].update({label: value}), "BACKUP_SOURCE_LABELS_REJECTED")

    def test_unpinned_or_wrong_postgresql_image_never_starts(self):
        for image in ("postgres:17", "postgres:17.11-bookworm@sha256:" + "c" * 64):
            with self.subTest(image=image):
                self.setUp()
                self.assert_refused_without_start(lambda item: item["Config"].update(Image=image), "BACKUP_SOURCE_IMAGE_REJECTED")

    def test_wrong_bind_missing_duplicate_or_additional_data_volume_never_starts(self):
        mutations = [lambda item: item["Mounts"][0].update(Name="wrong-data"),
                     lambda item: item["Mounts"][0].update(Type="bind"),
                     lambda item: item.update(Mounts=[]),
                     lambda item: item["Mounts"].append(copy.deepcopy(item["Mounts"][0])),
                     lambda item: item["Mounts"].append({"Type": "volume", "Name": "another", "Destination": "/other"})]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.setUp()
                self.assert_refused_without_start(mutate, "BACKUP_SOURCE_VOLUME_REJECTED")

    def test_published_ports_host_network_and_publish_all_never_start(self):
        mutations = [lambda item: item["HostConfig"].update(PortBindings={"5432/tcp": [{"HostPort": "5432"}]}),
                     lambda item: item["HostConfig"].update(PortBindings={"5432/tcp": []}),
                     lambda item: item["NetworkSettings"].update(Ports={"5432/tcp": [{"HostIp": "0.0.0.0", "HostPort": "5432"}]}),
                     lambda item: item["HostConfig"].update(PublishAllPorts=True),
                     lambda item: item["HostConfig"].update(NetworkMode="host"),
                     lambda item: item["HostConfig"].update(NetworkMode="container:some-other-app")]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.setUp()
                self.assert_refused_without_start(mutate, "BACKUP_SOURCE_PORTS_REJECTED")

    def test_dead_paused_restarting_removing_or_unknown_states_never_start(self):
        mutations = [lambda item: item["State"].update(Dead=True), lambda item: item["State"].update(Paused=True),
                     lambda item: item["State"].update(Restarting=True), lambda item: item["State"].update(Status="removing"),
                     lambda item: item["State"].update(Status="dead"), lambda item: item["State"].update(Status="unknown"),
                     lambda item: item["State"].update(Status="running", Running=False)]
        for index, mutate in enumerate(mutations):
            with self.subTest(index=index):
                self.setUp()
                self.assert_refused_without_start(mutate, "BACKUP_SOURCE_STATE_REJECTED")

    def test_invalid_inspection_payloads_never_start(self):
        for payload in (b"not-json", b"[]", b"[null]", b"{}", b"[{},{}]", b"x" * (r.MAX_INSPECT_BYTES + 1)):
            with self.subTest(bytes=len(payload)):
                self.setUp()
                self.runner.docker = Mock(return_value=payload)
                with self.assertRaisesRegex(r.ReadinessError, "BACKUP_SOURCE_INSPECTION_REJECTED"):
                    self.ready()
                self.assertEqual(self.runner.docker.call_count, 1)

    def test_unhealthy_source_has_bounded_wait_no_repeated_starts_and_no_database_query(self):
        self.stopped()
        self.runner.health = ["unhealthy"]
        with self.assertRaisesRegex(r.ReadinessError, "BACKUP_SOURCE_READY_TIMEOUT"):
            self.ready()
        self.assertEqual(self.starts(), [("start", self.cfg["container_id"])])
        self.assertLess(self.clock.value, r.WAIT_SECONDS + 1)
        self.assertFalse(any(args[0] == "exec" for args, _kwargs in self.runner.calls))

    def test_source_stopping_during_wait_fails_instead_of_start_loop(self):
        self.stopped()
        self.runner.mutations[2] = lambda item: item["State"].update(Status="exited", Running=False)
        with self.assertRaisesRegex(r.ReadinessError, "BACKUP_SOURCE_STOPPED_DURING_READINESS"):
            self.ready()
        self.assertEqual(len(self.starts()), 1)

    def test_identity_rechecked_after_start_before_any_database_access(self):
        self.stopped()
        self.runner.mutations[2] = lambda item: item["Config"].update(Image="changed")
        with self.assertRaisesRegex(r.ReadinessError, "BACKUP_SOURCE_IMAGE_REJECTED"):
            self.ready()
        self.assertEqual(len(self.starts()), 1)
        self.assertFalse(any(args[0] == "exec" for args, _kwargs in self.runner.calls))

    def test_missing_or_unknown_healthcheck_fails(self):
        for status in (None, "other"):
            with self.subTest(status=status):
                self.setUp()
                self.runner.health = [status]
                with self.assertRaisesRegex(r.ReadinessError, "BACKUP_SOURCE_HEALTHCHECK_REJECTED"):
                    self.ready()

    def test_final_reused_database_binding_failure_is_not_reported_as_ready(self):
        original = self.runner.docker

        def wrong_database(*args, **kwargs):
            return b"wrong-database|postgres\n0\nt\n" if args[0] == "exec" else original(*args, **kwargs)

        self.runner.docker = wrong_database
        with self.assertRaisesRegex(b.BackupError, "BACKUP_DATABASE_BINDING_REJECTED"):
            self.ready()

    def test_command_timeouts_cover_final_reused_query_and_share_total_deadline(self):
        self.ready()
        timeouts = [kwargs["timeout"] for _args, kwargs in self.runner.calls]
        self.assertTrue(all(0 < timeout <= r.COMMAND_SECONDS for timeout in timeouts))
        self.assertEqual(self.runner.calls[-1][0][0], "exec")
        self.runner.failure = b.BackupError("BACKUP_COMMAND_TIMEOUT")
        with self.assertRaisesRegex(b.BackupError, "BACKUP_COMMAND_TIMEOUT"):
            self.ready()

    def test_only_trusted_explicit_sibling_module_is_imported(self):
        sibling = Path(__file__).with_name("production-backup.py").absolute()
        with patch.object(r, "validate_import_path", return_value=sibling) as checked:
            actual = r.load_backup_module()
        checked.assert_called_once_with(sibling)
        self.assertTrue(callable(actual.load_config) and callable(actual.inspect_source) and callable(actual.Runner))

    def test_module_path_validation_refuses_writable_foreign_linked_or_special_files(self):
        def fixture():
            parent = Mock()
            parent.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFDIR | 0o755, st_uid=0)
            parent.is_symlink.return_value = False
            path = Mock()
            path.parts, path.parents, path.name = ("/", "safe", "production-backup.py"), (parent,), "production-backup.py"
            path.is_absolute.return_value, path.is_symlink.return_value = True, False
            path.lstat.return_value = SimpleNamespace(st_mode=stat.S_IFREG | 0o644, st_uid=0, st_nlink=1)
            path.stat.return_value = SimpleNamespace(st_size=20000)
            return path, parent

        def reject_path_and_parent(path, parent):
            with patch.object(r, "Path", return_value=path), self.assertRaises(r.ReadinessError):
                r.validate_import_path("ignored-safe-test-value")

        path, parent = fixture()
        with patch.object(r, "Path", return_value=path):
            self.assertIs(r.validate_import_path("ignored-safe-test-value"), path)
        for mutation in (lambda p, d: setattr(p.lstat.return_value, "st_uid", 1000),
                         lambda p, d: setattr(p.lstat.return_value, "st_nlink", 2),
                         lambda p, d: setattr(p.lstat.return_value, "st_mode", stat.S_IFREG | 0o666),
                         lambda p, d: setattr(p.lstat.return_value, "st_mode", stat.S_IFIFO | 0o600),
                         lambda p, d: setattr(p.is_symlink, "return_value", True),
                         lambda p, d: setattr(d.lstat.return_value, "st_mode", stat.S_IFDIR | 0o777),
                         lambda p, d: setattr(d.lstat.return_value, "st_uid", 1000),
                         lambda p, d: setattr(d.is_symlink, "return_value", True)):
            path, parent = fixture()
            mutation(path, parent)
            reject_path_and_parent(path, parent)

    def test_main_errors_are_fixed_codes_without_secret_arguments_stderr_or_tracebacks(self):
        marker = "SYNTHETIC-SECRET-MUST-NOT-BE-PRINTED"
        fake_backup = SimpleNamespace(load_config=Mock(return_value=self.cfg), BackupError=b.BackupError)
        for error, expected in ((ValueError(marker), "BACKUP_SOURCE_OPERATION_FAILED"),
                                (b.BackupError("BACKUP_COMMAND_TIMEOUT"), "BACKUP_COMMAND_TIMEOUT"),
                                (b.BackupError(marker), "BACKUP_SOURCE_OPERATION_FAILED"),
                                (r.ReadinessError("BACKUP_SOURCE_READY_TIMEOUT"), "BACKUP_SOURCE_READY_TIMEOUT")):
            with self.subTest(expected=expected), patch.object(r.sys, "platform", "linux"), \
                 patch.object(r.os, "geteuid", return_value=0, create=True), patch.object(r.os, "umask"), \
                 patch.object(r.signal, "signal"), patch.object(r, "load_backup_module", return_value=fake_backup), \
                 patch.object(r, "ensure_ready", side_effect=error), contextlib.redirect_stdout(io.StringIO()) as output:
                self.assertEqual(r.main(["--config", marker]), 1)
                self.assertEqual(json.loads(output.getvalue()), {"ok": False, "error": expected})
                self.assertNotIn(marker, output.getvalue())
                self.assertNotIn("Traceback", output.getvalue())

    def test_main_invalid_config_or_import_cannot_start_source(self):
        with patch.object(r.sys, "platform", "linux"), patch.object(r.os, "geteuid", return_value=0, create=True), \
             patch.object(r.os, "umask"), patch.object(r.signal, "signal"), \
             patch.object(r, "load_backup_module", side_effect=r.ReadinessError("BACKUP_SOURCE_MODULE_FILE_REJECTED")), \
             patch.object(r, "ensure_ready") as ensure, contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(r.main([]), 1)
            self.assertEqual(json.loads(output.getvalue())["error"], "BACKUP_SOURCE_MODULE_FILE_REJECTED")
            ensure.assert_not_called()

        dependency = SimpleNamespace(load_config=Mock(side_effect=b.BackupError("BACKUP_SYNTHETIC_ONLY")), BackupError=b.BackupError)
        with patch.object(r.sys, "platform", "linux"), patch.object(r.os, "geteuid", return_value=0, create=True), \
             patch.object(r.os, "umask"), patch.object(r.signal, "signal"), \
             patch.object(r, "load_backup_module", return_value=dependency), patch.object(r, "ensure_ready") as ensure, \
             contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(r.main([]), 1)
            self.assertEqual(json.loads(output.getvalue())["error"], "BACKUP_SYNTHETIC_ONLY")
            ensure.assert_not_called()

    def test_cli_rejects_unknown_arguments_without_echoing_values_and_supports_help(self):
        marker = "SECRET-FAKE-ARGUMENT"
        with contextlib.redirect_stdout(io.StringIO()) as output, contextlib.redirect_stderr(io.StringIO()) as error:
            self.assertEqual(r.main(["--unknown", marker]), 1)
            self.assertEqual(json.loads(output.getvalue())["error"], "BACKUP_SOURCE_ARGUMENTS_REJECTED")
            self.assertNotIn(marker, output.getvalue() + error.getvalue())
        with contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(r.main(["--help"]), 0)
            self.assertIn("--config", output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)
