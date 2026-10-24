"""Offline tests: fake age authentication, synthetic files, no network/DB/server access."""
import copy
import gzip
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("configuration_backup", Path(__file__).with_name("configuration-backup.py"))
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
RECIPIENT = "age1" + "q" * 58


class FakeAge:
    def __init__(self, identity):
        self.identity, self.archives, self.calls = str(identity), {}, []
        self.after_encrypt = None

    def register(self, archive):
        # Deliberately fake ciphertext; no real secrets or cryptographic claim.
        cipher = b.AGE_HEADER + b.digest(archive).encode() + str(len(self.archives)).encode() + b"UNIT-FAKE-ENCRYPTED" * 30
        self.archives[cipher] = archive
        return cipher

    def call(self, executable, arguments, data=None):
        self.calls.append(arguments)
        if arguments == ["--version"]:
            return b"v1.3.2\n"
        if arguments[0] == "--encrypt":
            cipher = self.register(data)
            if self.after_encrypt:
                self.after_encrypt()
            return cipher
        if arguments[0] == "--decrypt":
            cipher = data
            if arguments[arguments.index("--identity") + 1] != self.identity or cipher not in self.archives:
                raise b.ConfigurationError("CONFIG_BACKUP_AGE_AUTHENTICATION_OR_PROCESS_FAILED")
            return self.archives[cipher]
        raise AssertionError("Unexpected age command")


def archive_entries(value):
    with tarfile.open(fileobj=io.BytesIO(value), mode="r:gz") as archive:
        return [(copy.copy(member), archive.extractfile(member).read()) for member in archive]


def archive_bytes(entries, archive_format=tarfile.USTAR_FORMAT):
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz", format=archive_format) as archive:
        for info, data in entries:
            info = copy.copy(info)
            if info.isfile():
                info.size = len(data)
            archive.addfile(info, io.BytesIO(data) if info.isfile() else None)
    return output.getvalue()


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = self.root / "allowlist.json"
        self.source = self.root / "backend-config.txt"
        self.source.write_bytes(b"UNIT-SYNTHETIC-CONFIG\nVAPID_PRIVATE_KEY=base64url-fixture\n")
        self.identity = self.root / "identity-unit-test.txt"
        self.identity.write_bytes(b"NOT-A-REAL-KEY; FAKE-AGE-ONLY")
        self.output = self.root / "configuration.age"
        self.destination = self.root / "independent-restore"
        self.fake = FakeAge(self.identity)
        self.settings = {"scope": b.SCOPE, "productionConfigurationCreated": False,
                         "sourceMetadata": {"schema": "synthetic", "workersEnabled": False, "productionSecretsCreated": False},
                         "files": [{"source": str(self.source), "archivePath": "files/backend/backend-config.txt"}]}
        self.save_config()
        # Native Windows ACL and POSIX owner checks remain real-integration
        # responsibilities. All path/type/link checks still run in these tests.
        self.patches = [patch.object(b, "private_path", side_effect=lambda value, directory=False: b.regular_path(value, directory)),
                        patch.object(b, "age_call", side_effect=self.fake.call)]
        for item in self.patches: item.start()

    def tearDown(self):
        for item in reversed(self.patches): item.stop()
        self.temp.cleanup()

    def save_config(self):
        self.config.write_text(json.dumps(self.settings), encoding="utf-8")

    def pack(self, *, scope=b.SCOPE):
        return b.pack(self.config, RECIPIENT, Path(sys.executable), self.output, scope=scope)

    def restore(self, *, scope=b.SCOPE):
        return b.restore(self.output, self.identity, Path(sys.executable), self.destination, scope=scope)

    def baseline_archive(self, *, scope=b.SCOPE):
        self.pack(scope=scope)
        return self.fake.archives[self.output.read_bytes()]

    def configure_inactive_production(self):
        self.settings.update(scope=b.INACTIVE_PRODUCTION_SCOPE, productionConfigurationCreated=True,
                             configurationState=b.INACTIVE_PRODUCTION_STATE,
                             sourceMetadata={"schema": "unit-staged-inactive", "productionSecretsCreated": True})
        self.save_config()

    def install_archive(self, archive):
        self.output.write_bytes(self.fake.register(archive))

    def rejected_restore(self, archive, *, scope=b.SCOPE):
        self.install_archive(archive)
        with self.assertRaises((b.ConfigurationError, tarfile.TarError, ValueError, UnicodeError)):
            self.restore(scope=scope)
        self.assertFalse(self.destination.exists())

    def test_roundtrip_preserves_bytes_private_manifest_and_original_source(self):
        before = b.file_snapshot(self.source.stat()), self.source.read_bytes()
        packed = self.pack()
        restored = self.restore()
        self.assertEqual(self.source.read_bytes(), (self.destination / "files/backend/backend-config.txt").read_bytes())
        self.assertEqual(before, (b.file_snapshot(self.source.stat()), self.source.read_bytes()))
        manifest = json.loads((self.destination / "manifest.json").read_bytes())
        self.assertEqual(manifest["sourceMetadata"], self.settings["sourceMetadata"])
        self.assertEqual(manifest["scope"], b.SCOPE)
        self.assertFalse(manifest["productionConfigurationCreated"])
        self.assertEqual(manifest["files"][0]["mode"], stat.S_IMODE(self.source.stat().st_mode))
        self.assertEqual(manifest["files"][0]["sha256"], b.digest(self.source.read_bytes()))
        self.assertTrue(packed["sourcesUnchanged"] and restored["authenticated"] and restored["allFilesVerifiedBeforeWrite"])
        self.assertFalse(restored["filesActivated"])
        self.assertEqual(set(packed), {"passed", "cipherSha256", "ciphertextBytes", "fileCount", "sourcesUnchanged", "productionConfigurationCreated"})
        self.assertNotIn("sourcePath", json.dumps(packed))
        self.assertFalse(any("--identity" in call for call in self.fake.calls if "--encrypt" in call))

    def test_empty_and_extra_allowlist_rejected_without_age_or_output(self):
        for values in ([], None, [{"source": str(self.source), "archivePath": "files/a", "glob": "*"}]):
            self.settings["files"] = values
            self.save_config()
            with self.assertRaises(b.ConfigurationError): self.pack()
            self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.calls, [])

    def test_scope_requires_preparation_and_no_production_configuration(self):
        for changed in ({"scope": "production"}, {"productionConfigurationCreated": True}, {"unknownField": True}):
            original = copy.deepcopy(self.settings)
            self.settings.update(changed); self.save_config()
            with self.assertRaises(b.ConfigurationError): self.pack()
            self.settings = original
        self.assertEqual(self.fake.calls, [])

    def test_inactive_production_pack_requires_explicit_scope_before_age_or_output(self):
        self.configure_inactive_production()
        with self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_SCOPE_REJECTED$"):
            self.pack()
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_inactive_production_restore_requires_explicit_scope_before_destination(self):
        self.configure_inactive_production()
        self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        with self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_MANIFEST_REJECTED$"):
            self.restore()
        self.assertFalse(self.destination.exists())

    def test_legacy_archive_cannot_be_reinterpreted_as_inactive_production(self):
        archive = self.baseline_archive()
        self.rejected_restore(archive, scope=b.INACTIVE_PRODUCTION_SCOPE)

    def test_inactive_production_roundtrip_is_truthful_and_never_activates(self):
        self.configure_inactive_production()
        before = b.file_snapshot(self.source.stat()), self.source.read_bytes()
        packed = self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        restored = self.restore(scope=b.INACTIVE_PRODUCTION_SCOPE)
        manifest = json.loads((self.destination / "manifest.json").read_bytes())
        self.assertEqual(manifest["formatVersion"], 2)
        self.assertIs(manifest["productionConfigurationCreated"], True)
        self.assertEqual(manifest["configurationState"], "staged-inactive")
        self.assertEqual(manifest["scope"], b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(manifest["limitations"], b.INACTIVE_PRODUCTION_LIMITATIONS)
        self.assertNotIn(b.LIMITATIONS[0], manifest["limitations"])
        self.assertEqual(before, (b.file_snapshot(self.source.stat()), self.source.read_bytes()))
        self.assertEqual(self.source.read_bytes(), (self.destination / "files/backend/backend-config.txt").read_bytes())
        for result in (packed, restored):
            self.assertIs(result["productionConfigurationCreated"], True)
            self.assertEqual(result["scope"], b.INACTIVE_PRODUCTION_SCOPE)
            self.assertEqual(result["configurationState"], "staged-inactive")
            self.assertNotIn("sourcePath", json.dumps(result))
        self.assertIs(restored["filesActivated"], False)
        self.assertEqual(self.fake.calls, [["--version"], ["--encrypt", "--recipient", RECIPIENT],
                                          ["--version"], ["--decrypt", "--identity", str(self.identity)]])

    def test_inactive_production_config_is_exact_and_boolean_is_not_coerced(self):
        self.configure_inactive_production()
        original = copy.deepcopy(self.settings)
        changes = [{"productionConfigurationCreated": value} for value in (False, 1, 0, "true", None)]
        changes += [{"configurationState": value} for value in ("active", "inactive", True, None)]
        changes += [{"scope": b.SCOPE}, {"formatVersion": 2}, {"filesActivated": False}]
        for change in changes:
            self.settings = copy.deepcopy(original)
            self.settings.update(change); self.save_config()
            with self.subTest(change=change), self.assertRaises(b.ConfigurationError):
                self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.settings = copy.deepcopy(original)
        del self.settings["configurationState"]; self.save_config()
        with self.assertRaises(b.ConfigurationError): self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_legacy_config_boolean_and_new_state_are_not_coerced_or_ignored(self):
        original = copy.deepcopy(self.settings)
        for change in ({"productionConfigurationCreated": 0}, {"productionConfigurationCreated": "false"},
                       {"configurationState": "staged-inactive"}):
            self.settings = copy.deepcopy(original)
            self.settings.update(change); self.save_config()
            with self.subTest(change=change), self.assertRaises(b.ConfigurationError): self.pack()
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_inactive_production_manifest_version_state_and_boolean_fail_before_write(self):
        self.configure_inactive_production()
        entries = archive_entries(self.baseline_archive(scope=b.INACTIVE_PRODUCTION_SCOPE))
        original = json.loads(entries[0][1])
        changes = [{"formatVersion": value} for value in (1, True, 2.0, "2")]
        changes += [{"productionConfigurationCreated": value} for value in (False, 1, "true")]
        changes += [{"configurationState": value} for value in ("active", False, None)]
        changes += [{"scope": b.SCOPE}, {"limitations": b.LIMITATIONS}, {"unexpected": True}]
        for change in changes:
            manifest = copy.deepcopy(original); manifest.update(change)
            modified = [(entries[0][0], json.dumps(manifest).encode()), *entries[1:]]
            with self.subTest(change=change):
                self.rejected_restore(archive_bytes(modified), scope=b.INACTIVE_PRODUCTION_SCOPE)
        manifest = copy.deepcopy(original); del manifest["configurationState"]
        self.rejected_restore(archive_bytes([(entries[0][0], json.dumps(manifest).encode()), *entries[1:]]),
                              scope=b.INACTIVE_PRODUCTION_SCOPE)

    def test_legacy_manifest_version_requires_an_exact_integer(self):
        entries = archive_entries(self.baseline_archive())
        for version in (True, 1.0, "1", 2):
            manifest = json.loads(entries[0][1]); manifest["formatVersion"] = version
            with self.subTest(version=version):
                self.rejected_restore(archive_bytes([(entries[0][0], json.dumps(manifest).encode()), *entries[1:]]))

    def test_scope_argument_is_a_closed_set_for_both_python_entry_points(self):
        for scope in ("production", "any", None, True, [b.INACTIVE_PRODUCTION_SCOPE]):
            with self.subTest(scope=scope), self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_SCOPE_REJECTED$"):
                self.pack(scope=scope)
            with self.subTest(scope=scope), self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_SCOPE_REJECTED$"):
                self.restore(scope=scope)
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())
        self.assertFalse(self.destination.exists())

    def test_inactive_scope_does_not_bypass_key_content_exclusions(self):
        self.configure_inactive_production()
        for data in (b"AGE-SECRET-KEY-SYNTHETIC-FORBIDDEN", b"-----BEGIN OPENSSH PRIVATE KEY-----",
                     b"ROOT_ACCESS_KEY=forbidden"):
            self.source.write_bytes(data)
            with self.subTest(data=data), self.assertRaises(b.ConfigurationError):
                self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_inactive_scope_does_not_bypass_archive_name_and_path_exclusions(self):
        self.configure_inactive_production()
        for name in ("files/id_ed25519", "files/recovery-private-key.txt", "files/root-credentials.txt",
                     "files/tls.pem", "files/../outside.txt", "files/CONIN$.txt", "files/COM¹.txt"):
            self.settings["files"][0]["archivePath"] = name; self.save_config()
            with self.subTest(name=name), self.assertRaises(b.ConfigurationError):
                self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(self.fake.calls, [])
        self.assertFalse(self.output.exists())

    def test_inactive_scope_still_rejects_forbidden_renamed_archive_content(self):
        self.configure_inactive_production()
        entries = archive_entries(self.baseline_archive(scope=b.INACTIVE_PRODUCTION_SCOPE))
        data = b"-----BEGIN OPENSSH PRIVATE KEY-----"
        manifest = json.loads(entries[0][1])
        manifest["files"][0].update(size=len(data), sha256=b.digest(data))
        modified = [(entries[0][0], json.dumps(manifest).encode()), (entries[1][0], data)]
        self.rejected_restore(archive_bytes(modified), scope=b.INACTIVE_PRODUCTION_SCOPE)

    def test_inactive_scope_keeps_existing_output_and_destination_intact(self):
        self.configure_inactive_production()
        self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        ciphertext = self.output.read_bytes()
        with self.assertRaises(b.ConfigurationError): self.pack(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(self.output.read_bytes(), ciphertext)
        self.destination.mkdir()
        sentinel = self.destination / "existing.txt"
        sentinel.write_bytes(b"existing-data")
        with self.assertRaises(b.ConfigurationError): self.restore(scope=b.INACTIVE_PRODUCTION_SCOPE)
        self.assertEqual(list(self.destination.iterdir()), [sentinel])
        self.assertEqual(sentinel.read_bytes(), b"existing-data")

    def test_cli_routes_explicit_scope_for_pack_and_restore_and_rejects_other_scopes(self):
        operations = [
            ("pack", ["--config", str(self.config), "--recipient", RECIPIENT, "--age", sys.executable,
                      "--output", str(self.output)]),
            ("restore", ["--cipher", str(self.output), "--identity", str(self.identity), "--age", sys.executable,
                         "--destination", str(self.destination)]),
        ]
        for operation, arguments in operations:
            argv = ["helper", operation, *arguments, "--scope", b.INACTIVE_PRODUCTION_SCOPE]
            with patch.object(b.sys, "argv", argv), patch.object(b, operation, return_value={"passed": True}) as called, \
                    patch.object(b.sys, "stdout", new_callable=io.StringIO):
                self.assertEqual(b.main(), 0)
                self.assertEqual(called.call_args.kwargs, {"scope": b.INACTIVE_PRODUCTION_SCOPE})
            argv[-1] = "arbitrary-scope"
            with patch.object(b.sys, "argv", argv), patch.object(b, operation) as called, \
                    patch.object(b.sys, "stdout", new_callable=io.StringIO) as output:
                self.assertEqual(b.main(), 1)
                self.assertEqual(json.loads(output.getvalue()), {"passed": False, "error": "CONFIG_BACKUP_ARGUMENTS_REJECTED"})
                called.assert_not_called()

    def test_source_metadata_type_depth_and_size_are_bounded(self):
        nested = {"leaf": False}
        for _ in range(10): nested = {"nested": nested}
        for value in ([], {"tooLarge": "x" * (b.MAX_SOURCE_METADATA + 1)}, {"notFinite": float("nan")}, nested):
            self.settings["sourceMetadata"] = value; self.save_config()
            with self.assertRaises((b.ConfigurationError, ValueError)): self.pack()
        self.assertFalse(self.output.exists())

    def test_metadata_cannot_smuggle_recovery_or_ssh_private_key_markers(self):
        for value in ("AGE-SECRET-KEY-SYNTHETIC-FORBIDDEN", "-----BEGIN OPENSSH PRIVATE KEY-----", "ROOT_ACCESS_KEY=forbidden"):
            self.settings["sourceMetadata"] = {"accidentalSecret": value}; self.save_config()
            with self.assertRaises(b.ConfigurationError): self.pack()
        self.assertFalse(self.output.exists())
        self.assertEqual(self.fake.calls, [])

    def test_no_overwrite_pack_or_restore(self):
        self.output.write_bytes(b"EXISTING-UNCHANGED")
        with self.assertRaises(b.ConfigurationError): self.pack()
        self.assertEqual(self.output.read_bytes(), b"EXISTING-UNCHANGED")
        self.assertEqual(self.fake.calls, [])
        self.output.unlink(); self.pack()
        self.destination.mkdir()
        (self.destination / "keep.txt").write_bytes(b"EXISTING-UNCHANGED")
        with self.assertRaises(b.ConfigurationError): self.restore()
        self.assertEqual((self.destination / "keep.txt").read_bytes(), b"EXISTING-UNCHANGED")

    def test_tamper_truncation_and_wrong_identity_never_reach_parser_or_write(self):
        self.pack()
        original = self.output.read_bytes()
        wrong = self.root / "other-identity-unit.txt"
        wrong.write_bytes(b"WRONG-FAKE-KEY")
        for mode in ("tamper", "truncated", "wrong-key"):
            self.output.write_bytes((original[:-1] + b"X") if mode == "tamper" else original[:-1] if mode == "truncated" else original)
            with patch.object(b, "parse_archive", wraps=b.parse_archive) as parser:
                with self.subTest(mode=mode), self.assertRaises(b.ConfigurationError):
                    b.restore(self.output, wrong if mode == "wrong-key" else self.identity, Path(sys.executable), self.destination)
                parser.assert_not_called()
            self.assertFalse(self.destination.exists())

    def test_decryption_uses_captured_cipher_bytes_and_source_change_prevents_writes(self):
        self.pack()
        original = self.output.read_bytes()
        def changed_during_decrypt(executable, arguments, data=None):
            if arguments[0] == "--decrypt":
                self.assertEqual(data, original)
                self.assertEqual(arguments, ["--decrypt", "--identity", str(self.identity)])
                self.output.write_bytes(original[:-1] + b"X")
            return self.fake.call(executable, arguments, data)
        with patch.object(b, "age_call", side_effect=changed_during_decrypt):
            with self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_CIPHERTEXT_CHANGED$"):
                self.restore()
        self.assertFalse(self.destination.exists())

    def test_source_mutation_after_encryption_leaves_no_output(self):
        self.fake.after_encrypt = lambda: self.source.write_bytes(b"MUTATED")
        with self.assertRaisesRegex(b.ConfigurationError, "CONFIG_BACKUP_SOURCE_CHANGED"): self.pack()
        self.assertFalse(self.output.exists())

    def test_source_mutation_after_output_write_removes_only_new_output(self):
        original_writer = b.write_new
        def changing_write(path, data, mode=0o600):
            original_writer(path, data, mode)
            self.source.write_bytes(b"MUTATED-AFTER-WRITE")
        with patch.object(b, "write_new", side_effect=changing_write):
            with self.assertRaises(b.ConfigurationError): self.pack()
        self.assertFalse(self.output.exists())

    def test_private_key_names_and_renamed_key_markers_are_rejected(self):
        for name in ("recovery-identity.txt", "id_rsa", "id_ed25519", "root-access-keys.csv", "server.pem", "age-secret-key.txt", "recovery-key.txt"):
            path = self.root / name
            path.write_bytes(b"not-a-real-private-key")
            self.settings["files"] = [{"source": str(path), "archivePath": "files/settings.txt"}]; self.save_config()
            with self.subTest(name=name), self.assertRaises(b.ConfigurationError): self.pack()
        for data in (b"AGE-SECRET-KEY-UNIT-FAKE", b"-----BEGIN OPENSSH PRIVATE KEY-----UNIT", b"-----BEGIN PRIVATE KEY-----UNIT", b"PuTTY-User-Key-File-3: unit", b"AWS_ROOT_ACCESS_KEY=unit", b"SSH_PRIVATE_KEY=unit"):
            self.source.write_bytes(data)
            self.settings["files"] = [{"source": str(self.source), "archivePath": "files/settings.txt"}]; self.save_config()
            with self.subTest(data=data), self.assertRaises(b.ConfigurationError): self.pack()
        self.assertFalse(self.output.exists())

    def test_directory_relative_glob_duplicate_and_hierarchy_sources_rejected(self):
        cases = [[{"source": str(self.root), "archivePath": "files/directory"}],
                 [{"source": "relative.conf", "archivePath": "files/settings"}],
                 [{"source": str(self.root / "*.conf"), "archivePath": "files/settings"}],
                 self.settings["files"] * 2]
        second = self.root / "second.txt"; second.write_bytes(b"second")
        cases += [[{"source": str(self.source), "archivePath": "files/a"}, {"source": str(second), "archivePath": "files/a/b"}],
                  [{"source": str(self.source), "archivePath": "files/A"}, {"source": str(second), "archivePath": "files/a"}]]
        for entries in cases:
            self.settings["files"] = entries; self.save_config()
            with self.subTest(entries=entries), self.assertRaises((b.ConfigurationError, OSError)): self.pack()
        self.assertFalse(self.output.exists())

    def test_source_hardlink_is_not_collected(self):
        alias = self.root / "hardlink.txt"
        try: os.link(self.source, alias)
        except OSError: self.skipTest("Local test filesystem disallows hardlinks")
        with self.assertRaises(b.ConfigurationError): self.pack()
        self.assertFalse(self.output.exists())

    def test_newly_closed_files_have_stable_source_content_and_metadata(self):
        for index in range(32):
            source = self.root / f"fresh-{index}.txt"
            expected = f"synthetic freshly closed fixture {index}".encode()
            source.write_bytes(expected)
            path, data, info = b.read_source(source)
            self.assertEqual(data, expected)
            b.verify_sources([(path, data, info)])

    def test_windows_ctime_cache_difference_preserves_other_required_guards(self):
        from types import SimpleNamespace
        values = dict(st_dev=1, st_ino=2, st_size=3, st_mode=0o100600, st_uid=0, st_gid=0, st_mtime_ns=4, st_ctime_ns=5)
        before = SimpleNamespace(**values)
        changed_ctime = SimpleNamespace(**{**values, "st_ctime_ns": 6})
        with patch.object(b.os, "name", "nt"):
            self.assertEqual(b.file_snapshot(before), b.file_snapshot(changed_ctime))
            for field in ("st_dev", "st_ino", "st_size", "st_mode", "st_uid", "st_gid", "st_mtime_ns"):
                changed = SimpleNamespace(**{**values, field: values[field] + 1})
                self.assertNotEqual(b.file_snapshot(before), b.file_snapshot(changed))
        with patch.object(b.os, "name", "posix"):
            self.assertNotEqual(b.file_snapshot(before), b.file_snapshot(changed_ctime))

    def test_archive_paths_reject_escape_ads_windows_devices_and_ambiguous_names(self):
        for value in ("../escape", "/files/a", "files/../escape", "files//a", "files/a\\b", "files/a:stream", "files/CON", "files/nul.txt", "files/a.", "files/a ", "files/\x00x"):
            with self.subTest(value=value), self.assertRaises(b.ConfigurationError): b.archive_path(value)

    def test_portable_device_aliases_rejected_before_restore_destination_or_writes(self):
        entries = archive_entries(self.baseline_archive())
        devices = ["CONIN$", "CONOUT$", *[f"{prefix}{digit}" for prefix in ("COM", "LPT") for digit in "¹²³"]]
        for device in devices:
            for member in (f"files/{device}", f"files/{device.lower()}.txt",
                           f"files/nested/{device}/settings.txt", f"files/nested/{device.lower()}.cfg/settings.txt"):
                damaged = copy.deepcopy(entries)
                manifest = json.loads(damaged[0][1])
                manifest["files"][0]["archivePath"] = member
                damaged[0] = (damaged[0][0], json.dumps(manifest).encode())
                damaged[1][0].name = member
                with self.subTest(member=member), patch.object(b, "write_new", wraps=b.write_new) as writer:
                    self.install_archive(archive_bytes(damaged))
                    with self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_MEMBER_PATH_REJECTED$"):
                        self.restore()
                    writer.assert_not_called()
                    self.assertFalse(self.destination.exists())

    def test_file_count_and_total_plaintext_limit(self):
        self.settings["files"] *= b.MAX_FILES + 1; self.save_config()
        with self.assertRaises(b.ConfigurationError): self.pack()
        self.settings["files"] = [{"source": str(self.source), "archivePath": "files/a"}]; self.save_config()
        with patch.object(b, "MAX_TOTAL", 16):
            with self.assertRaises(b.ConfigurationError): self.pack()
        self.assertFalse(self.output.exists())

    def test_tar_path_traversal_extra_file_and_duplicate_rejected_before_write(self):
        entries = archive_entries(self.baseline_archive())
        for name in ("../outside", "/absolute", "files/../outside", "files/not-listed.txt"):
            damaged = copy.deepcopy(entries)
            damaged[1][0].name = name
            with self.subTest(name=name): self.rejected_restore(archive_bytes(damaged))
        self.rejected_restore(archive_bytes(entries + [copy.deepcopy(entries[1])]))

    def test_tar_symlink_hardlink_directory_special_and_mode_rejected(self):
        entries = archive_entries(self.baseline_archive())
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.DIRTYPE, tarfile.FIFOTYPE):
            damaged = copy.deepcopy(entries)
            damaged[1][0].type, damaged[1][0].linkname = kind, "../outside"
            with self.subTest(kind=kind): self.rejected_restore(archive_bytes(damaged))
        damaged = copy.deepcopy(entries); damaged[1][0].mode = 0o4600
        self.rejected_restore(archive_bytes(damaged))

    def test_tar_hash_size_missing_manifest_and_missing_file_rejected(self):
        entries = archive_entries(self.baseline_archive())
        damaged = copy.deepcopy(entries)
        damaged[1] = (damaged[1][0], b"same-size-fake".ljust(len(damaged[1][1]), b"X"))
        self.rejected_restore(archive_bytes(damaged))
        self.rejected_restore(archive_bytes(entries[:1]))
        self.rejected_restore(archive_bytes(entries[1:]))
        self.rejected_restore(archive_bytes(list(reversed(entries))))

    def test_gzip_crc_truncation_and_decompression_ceiling_precede_writes(self):
        baseline = self.baseline_archive()
        damaged = bytearray(baseline); damaged[-8] ^= 1
        self.rejected_restore(bytes(damaged))
        self.rejected_restore(baseline[:-4])
        with patch.object(b, "MAX_TAR", 4096):
            self.rejected_restore(gzip.compress(b"X" * 4097))

    def test_tar_hidden_gnu_extension_is_rejected(self):
        entries = archive_entries(self.baseline_archive())
        manifest = json.loads(entries[0][1])
        long_member = "files/" + "x" * 120
        manifest["files"][0]["archivePath"] = long_member
        entries[0] = (entries[0][0], json.dumps(manifest).encode())
        entries[1][0].name = long_member
        self.rejected_restore(archive_bytes(entries, tarfile.GNU_FORMAT))

    def test_tar_hidden_trailing_payload_is_rejected(self):
        original = gzip.decompress(self.baseline_archive())
        self.rejected_restore(gzip.compress(original + b"UNLISTED".ljust(512, b"\0")))

    def test_manifest_required_paths_count_hash_mode_scope_and_limits(self):
        entries = archive_entries(self.baseline_archive())
        original = json.loads(entries[0][1])
        for change in ("hash", "mode", "size", "scope", "metadata", "duplicate", "extra", "empty"):
            manifest = copy.deepcopy(original)
            if change == "hash": manifest["files"][0]["sha256"] = "0" * 64
            if change == "mode": manifest["files"][0]["mode"] = 0o4755
            if change == "size": manifest["files"][0]["size"] = b.MAX_TOTAL + 1
            if change == "scope": manifest["productionConfigurationCreated"] = True
            if change == "metadata": manifest["sourceMetadata"] = []
            if change == "duplicate": manifest["files"] *= 2
            if change == "extra": manifest["files"][0]["unexpected"] = "reject"
            if change == "empty": manifest["files"] = []
            modified = [(entries[0][0], json.dumps(manifest).encode()), *entries[1:]]
            with self.subTest(change=change): self.rejected_restore(archive_bytes(modified))

    def test_cli_errors_are_fixed_and_never_print_exception_secrets(self):
        with patch.object(b.sys, "argv", ["helper", "pack", "--config", str(self.config)]), patch.object(b.sys, "stdout", new_callable=io.StringIO) as output:
            self.assertEqual(b.main(), 1)
            self.assertEqual(json.loads(output.getvalue()), {"passed": False, "error": "CONFIG_BACKUP_ARGUMENTS_REJECTED"})
        arguments = ["helper", "pack", "--config", str(self.config), "--recipient", RECIPIENT, "--age", sys.executable, "--output", str(self.output)]
        with patch.object(b.sys, "argv", arguments), patch.object(b, "pack", side_effect=RuntimeError("SECRET-PATH-AND-CONTENT")), patch.object(b.sys, "stdout", new_callable=io.StringIO) as output:
            self.assertEqual(b.main(), 1)
            self.assertEqual(json.loads(output.getvalue()), {"passed": False, "error": "CONFIG_BACKUP_OPERATION_FAILED"})

    def test_native_age_process_error_discards_partial_stdout_and_stderr(self):
        # Linux CI may expose Python through a symlink. Use the real executable
        # so this test reaches the mocked child failure after the real path guard.
        executable = Path(sys.executable).resolve(strict=True)
        with patch.object(b, "age_call", wraps=ORIGINAL_AGE_CALL):
            with patch.object(b.subprocess, "run", return_value=subprocess.CompletedProcess(["fake"], 1, stdout=b"PARTIAL-SECRET", stderr=b"SECRET-ERROR")):
                with self.assertRaisesRegex(b.ConfigurationError, "^CONFIG_BACKUP_AGE_AUTHENTICATION_OR_PROCESS_FAILED$"):
                    b.age_call(executable, ["--decrypt"], None)


ORIGINAL_AGE_CALL = b.age_call

if __name__ == "__main__":
    unittest.main(verbosity=2)
