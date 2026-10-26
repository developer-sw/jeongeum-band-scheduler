#!/usr/bin/env python3
"""Fake values only. POSIX ownership cases require Linux root, otherwise skip."""
import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
SPEC = importlib.util.spec_from_file_location("runtime_secrets", Path(__file__).with_name("runtime-secrets.py"))
runtime = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(runtime)


class PortableTests(unittest.TestCase):
    def test_non_linux_refused_without_filesystem_access(self):
        with mock.patch.object(runtime.sys, "platform", "win32"):
            with self.assertRaisesRegex(runtime.SecretError, "LINUX_ROOT_REQUIRED"):
                runtime.execute("prepare", "/not-read", "/not-created", 999, 999)

    def test_argument_failure_does_not_echo_input(self):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = runtime.main(["prepare", "--source-directory", "private-path-canary",
                                   "--destination-directory", "/unused", "--postgres-uid",
                                   "private-value-canary", "--postgres-gid", "999"])
        self.assertEqual(result, 1)
        self.assertEqual(output.getvalue(), "")
        self.assertEqual(json.loads(errors.getvalue()), {"ok": False, "error": "ARGUMENTS_REJECTED"})


@unittest.skipUnless(sys.platform.startswith("linux") and hasattr(os, "geteuid") and os.geteuid() == 0,
                     "Requires Linux root and native POSIX filesystem; Windows permissions are not equivalent")
class LinuxRootTests(unittest.TestCase):
    def setUp(self):
        # /tmp is intentionally rejected: all ancestors must be root-controlled.
        self.base = Path(tempfile.mkdtemp(prefix="jeongeum-runtime-secrets-test-", dir="/run"))
        self.source, self.target = self.base / "master", self.base / "runtime"
        self.source.mkdir(mode=0o700)
        self.values = {name: (b"fake-admin-password" if index == 0 else
                             b"fake-application-password" if index == 1 else b"")
                       for index, name in enumerate(runtime.MASTERS)}
        for name, value in self.values.items():
            (self.source / name).write_bytes(value)
            (self.source / name).chmod(0o600)

    def tearDown(self):
        resolved = self.base.resolve()
        self.assertEqual(resolved.parent, Path("/run"))
        self.assertTrue(resolved.name.startswith("jeongeum-runtime-secrets-test-"))
        self.assertFalse(self.base.is_symlink())
        shutil.rmtree(resolved)

    def run_tool(self, action="prepare", source=None, target=None, uid=999, gid=999):
        return runtime.execute(action, str(source or self.source), str(target or self.target), uid, gid)

    def reject(self, code, **kwargs):
        with self.assertRaisesRegex(runtime.SecretError, code):
            self.run_tool(**kwargs)

    def test_prepare_and_check_six_distinct_files_with_exact_ownership(self):
        before = {name: runtime.identity((self.source / name).stat()) for name in runtime.MASTERS}
        self.assertEqual(self.run_tool(), {"ok": True, "action": "prepare", "logicalSecrets": 5, "runtimeFiles": 6})
        self.assertEqual(stat.S_IMODE(self.target.stat().st_mode), 0o700)
        for name, (master, uid, gid) in runtime.layout(999, 999).items():
            item = self.target / name
            value = item.stat()
            self.assertEqual((value.st_uid, value.st_gid, stat.S_IMODE(value.st_mode), value.st_nlink),
                             (uid, gid, 0o400, 1))
            self.assertEqual(item.read_bytes(), self.values[master])
        self.assertEqual(len({item.stat().st_ino for item in self.target.iterdir()}), 6)
        self.assertTrue(self.run_tool("check")["ok"])
        self.assertEqual(before, {name: runtime.identity((self.source / name).stat()) for name in runtime.MASTERS})

    def test_existing_destination_preserved(self):
        self.target.mkdir(mode=0o700)
        marker = self.target / "marker"
        marker.write_bytes(b"preserve")
        self.reject("DESTINATION_EXISTS")
        self.assertEqual(marker.read_bytes(), b"preserve")

    def test_symlink_destination_preserved(self):
        self.target.symlink_to(self.source, target_is_directory=True)
        self.reject("DESTINATION_EXISTS")
        self.assertTrue(self.target.is_symlink())

    def test_source_hardlink_rejected(self):
        os.link(self.source / runtime.MASTERS[0], self.base / "hardlink")
        self.reject("FILE_TYPE_REJECTED")
        self.assertFalse(self.target.exists())

    def test_source_file_symlink_rejected(self):
        item = self.source / runtime.MASTERS[0]
        item.unlink()
        item.symlink_to(self.source / runtime.MASTERS[1])
        self.reject("FILE_TYPE_REJECTED")

    def test_symlink_parent_rejected(self):
        linked = self.base / "linked"
        linked.symlink_to(self.source, target_is_directory=True)
        with self.assertRaises(OSError):
            self.run_tool(source=linked)

    def test_uncontrolled_parent_rejected(self):
        self.base.chmod(0o770)
        self.reject("PARENT_NOT_CONTROLLED")

    def test_invalid_source_permissions_rejected(self):
        item = self.source / runtime.MASTERS[0]
        for mode, uid, gid in ((0o644, 0, 0), (0o600, 10001, 10001), (0o600, 0, 999)):
            with self.subTest(mode=mode, uid=uid, gid=gid):
                os.chown(item, uid, gid)
                item.chmod(mode)
                self.reject("FILE_PERMISSIONS_REJECTED")

    def test_source_directory_not_private_rejected(self):
        self.source.chmod(0o755)
        self.reject("DIRECTORY_MODE_REJECTED")

    def test_overlapping_directories_rejected(self):
        for target in (self.source, self.source / "child", self.base):
            with self.subTest(target_kind=target.name):
                self.reject("DIRECTORIES_OVERLAP", target=target)

    def test_postgres_identity_must_be_nonroot_and_distinct(self):
        for uid, gid in ((0, 999), (999, 0), (10001, 999), (-1, 999), (2**31, 999)):
            with self.subTest(uid=uid, gid=gid):
                self.reject("POSTGRES_IDENTITY_REJECTED", uid=uid, gid=gid)

    def test_empty_password_oversize_and_extra_source_entries_rejected(self):
        item = self.source / runtime.MASTERS[0]
        item.write_bytes(b"")
        self.reject("PASSWORD_EMPTY")
        item.write_bytes(b"x" * (runtime.MAX_BYTES + 1))
        self.reject("FILE_SIZE_REJECTED")
        item.write_bytes(self.values[runtime.MASTERS[0]])
        (self.source / "unexpected").write_bytes(b"unused")
        self.reject("SOURCE_ENTRIES_REJECTED")

    def test_runtime_value_changes_are_rejected(self):
        self.run_tool()
        (self.target / "postgres-app-db-password.txt").write_bytes(b"different-fake-value")
        self.reject("RUNTIME_VALUE_MISMATCH", action="check")

    def test_runtime_mode_owner_and_hardlinks_rejected(self):
        self.run_tool()
        item = self.target / "app-db-password.txt"
        item.chmod(0o600)
        self.reject("FILE_PERMISSIONS_REJECTED", action="check")
        item.chmod(0o400)
        os.chown(item, 0, 0)
        self.reject("FILE_PERMISSIONS_REJECTED", action="check")
        os.chown(item, 10001, 10001)
        os.link(item, self.base / "runtime-hardlink")
        self.reject("FILE_TYPE_REJECTED", action="check")

    def test_partial_failure_cleans_only_new_files(self):
        original = os.fchown
        calls = 0

        def fail_on_file(handle, uid, gid):
            nonlocal calls
            calls += 1
            if calls == 3:
                raise OSError("synthetic failure")
            return original(handle, uid, gid)

        with mock.patch.object(runtime.os, "fchown", side_effect=fail_on_file):
            with self.assertRaises(OSError):
                self.run_tool()
        self.assertFalse(self.target.exists())
        self.assertEqual({name: (self.source / name).read_bytes() for name in runtime.MASTERS}, self.values)

    def test_source_change_during_operation_detected_and_output_cleaned(self):
        original = runtime.verify

        def change_then_verify(source, target, values, entries):
            (self.source / "app-db-password.txt").write_bytes(b"changed-fake-master")
            return original(source, target, values, entries)

        with mock.patch.object(runtime, "verify", side_effect=change_then_verify):
            self.reject("SOURCE_CHANGED")
        self.assertFalse(self.target.exists())


if __name__ == "__main__":
    unittest.main()
