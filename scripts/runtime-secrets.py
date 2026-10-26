#!/usr/bin/env python3
"""Explicit Linux administrator tool; never invoked automatically by the app.

Copy five protected master values to six consumer-owned runtime files. This does
not install Compose, contact Docker, activate services, or create a backup.
"""
import argparse
import hmac
import json
import os
import stat
import sys

MAX_BYTES = 8192
MASTERS = ("postgres-admin-password.txt", "app-db-password.txt", "vapid-public-key.txt",
           "vapid-private-key.txt", "vapid-subject.txt")


class SecretError(Exception):
    pass


def need(condition, code):
    if not condition:
        raise SecretError(code)


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise SecretError("ARGUMENTS_REJECTED")


def identity(value):
    return (value.st_dev, value.st_ino, value.st_mode, value.st_uid, value.st_gid,
            value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def absolute(value):
    need(isinstance(value, str) and value.startswith("/") and value != "/"
         and value == os.path.normpath(value), "PATH_REJECTED")
    return value


def directory(path, private=False):
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    current = os.open("/", flags)
    try:
        for part in [""] + path.split("/")[1:]:
            if part:
                child = os.open(part, flags, dir_fd=current)
                os.close(current)
                current = child
            value = os.fstat(current)
            need(stat.S_ISDIR(value.st_mode) and value.st_uid == 0
                 and not stat.S_IMODE(value.st_mode) & 0o022, "PARENT_NOT_CONTROLLED")
        if private:
            need(value.st_gid == 0 and stat.S_IMODE(value.st_mode) == 0o700, "DIRECTORY_MODE_REJECTED")
        return current
    except BaseException:
        os.close(current)
        raise


def read_file(parent, name, uid, gid, mode):
    before = os.stat(name, dir_fd=parent, follow_symlinks=False)
    need(stat.S_ISREG(before.st_mode) and before.st_nlink == 1, "FILE_TYPE_REJECTED")
    need((before.st_uid, before.st_gid, stat.S_IMODE(before.st_mode)) == (uid, gid, mode),
         "FILE_PERMISSIONS_REJECTED")
    need(before.st_size <= MAX_BYTES, "FILE_SIZE_REJECTED")
    handle = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    try:
        need(identity(before) == identity(os.fstat(handle)), "FILE_CHANGED")
        value = os.read(handle, MAX_BYTES + 1)
        need(len(value) == before.st_size and identity(before) == identity(os.fstat(handle))
             and identity(before) == identity(os.stat(name, dir_fd=parent, follow_symlinks=False)),
             "FILE_CHANGED")
        return value, identity(before)
    finally:
        os.close(handle)


def masters(parent):
    need(set(os.listdir(parent)) == set(MASTERS), "SOURCE_ENTRIES_REJECTED")
    result = {name: read_file(parent, name, 0, 0, 0o600) for name in MASTERS}
    need(all(result[name][0] for name in MASTERS[:2]), "PASSWORD_EMPTY")
    return result


def layout(pg_uid, pg_gid):
    return {
        "postgres-admin-password.txt": (MASTERS[0], 0, 0),
        "postgres-app-db-password.txt": (MASTERS[1], pg_uid, pg_gid),
        "app-db-password.txt": (MASTERS[1], 10001, 10001),
        **{name: (name, 10001, 10001) for name in MASTERS[2:]},
    }


def verify(source, target, original, entries):
    need(set(os.listdir(target)) == set(entries), "RUNTIME_ENTRIES_REJECTED")
    for name, (master, uid, gid) in entries.items():
        value, _ = read_file(target, name, uid, gid, 0o400)
        need(hmac.compare_digest(value, original[master][0]), "RUNTIME_VALUE_MISMATCH")
    current = masters(source)
    need(all(current[name][1] == original[name][1]
             and hmac.compare_digest(current[name][0], original[name][0]) for name in MASTERS),
         "SOURCE_CHANGED")


def execute(action, source_directory, destination_directory, postgres_uid, postgres_gid):
    need(os.name == "posix" and hasattr(os, "geteuid") and os.geteuid() == 0
         and hasattr(os, "O_NOFOLLOW"), "LINUX_ROOT_REQUIRED")
    need(sys.platform.startswith("linux"), "LINUX_ROOT_REQUIRED")
    need(all(type(number) is int and 0 < number < 2**31 - 1 for number in (postgres_uid, postgres_gid))
         and postgres_uid != 10001, "POSTGRES_IDENTITY_REJECTED")
    need(action in ("prepare", "check"), "ACTION_REJECTED")
    source_path, target_path = absolute(source_directory), absolute(destination_directory)
    need(os.path.commonpath((source_path, target_path)) not in (source_path, target_path),
         "DIRECTORIES_OVERLAP")
    source = directory(source_path, private=True)
    parent = target = None
    created, target_id = [], None
    target_name = os.path.basename(target_path)
    try:
        original = masters(source)
        entries = layout(postgres_uid, postgres_gid)
        parent = directory(os.path.dirname(target_path))
        if action == "prepare":
            try:
                os.stat(target_name, dir_fd=parent, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise SecretError("DESTINATION_EXISTS")
            os.mkdir(target_name, 0o700, dir_fd=parent)
            target = os.open(target_name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
            value = os.fstat(target)
            target_id = (value.st_dev, value.st_ino)
            os.fchown(target, 0, 0)
            os.fchmod(target, 0o700)
            for name, (master, uid, gid) in entries.items():
                handle = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=target)
                try:
                    value = os.fstat(handle)
                    created.append((name, value.st_dev, value.st_ino))
                    data = memoryview(original[master][0])
                    while data:
                        size = os.write(handle, data)
                        need(size > 0, "WRITE_FAILED")
                        data = data[size:]
                    os.fchown(handle, uid, gid)
                    os.fchmod(handle, 0o400)
                    os.fsync(handle)
                finally:
                    os.close(handle)
        else:
            target = directory(target_path, private=True)
        verify(source, target, original, entries)
        if action == "prepare":
            os.fsync(target)
            os.fsync(parent)
        return {"ok": True, "action": action, "logicalSecrets": 5, "runtimeFiles": 6}
    except BaseException:
        # Remove only regular files and the exact directory created by this call.
        if action == "prepare" and target is not None and target_id is not None:
            for name, device, inode in reversed(created):
                try:
                    value = os.stat(name, dir_fd=target, follow_symlinks=False)
                    if stat.S_ISREG(value.st_mode) and (value.st_dev, value.st_ino) == (device, inode):
                        os.unlink(name, dir_fd=target)
                except OSError:
                    pass
            try:
                value = os.stat(target_name, dir_fd=parent, follow_symlinks=False)
                if stat.S_ISDIR(value.st_mode) and (value.st_dev, value.st_ino) == target_id:
                    os.rmdir(target_name, dir_fd=parent)
            except OSError:
                pass
        raise
    finally:
        for handle in (target, parent, source):
            if handle is not None:
                os.close(handle)


def main(argv=None):
    try:
        parser = Parser(description=__doc__)
        parser.add_argument("action", choices=("prepare", "check"))
        parser.add_argument("--source-directory", required=True)
        parser.add_argument("--destination-directory", required=True)
        parser.add_argument("--postgres-uid", required=True, type=int)
        parser.add_argument("--postgres-gid", required=True, type=int)
        args = parser.parse_args(argv)
        result = execute(args.action, args.source_directory, args.destination_directory,
                         args.postgres_uid, args.postgres_gid)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except SecretError as error:
        print(json.dumps({"ok": False, "error": str(error)}, separators=(",", ":")), file=sys.stderr)
    except (OSError, ValueError, OverflowError):
        print('{"ok":false,"error":"FILESYSTEM_REJECTED"}', file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
