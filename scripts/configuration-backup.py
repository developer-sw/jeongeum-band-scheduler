#!/usr/bin/env python3
"""Explicit configuration archives with scope opt-in. Never activates restored files."""
import argparse
import ctypes
import datetime as dt
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tarfile
import unicodedata

SCOPE = "server-preparation-and-synthetic"
INACTIVE_PRODUCTION_SCOPE = "inactive-production-settings"
INACTIVE_PRODUCTION_STATE = "staged-inactive"
MAX_FILES = 128
MAX_TOTAL = 32 * 1024 * 1024
MAX_MANIFEST = 256 * 1024
MAX_SOURCE_METADATA = 64 * 1024
MAX_CIPHER = MAX_TOTAL + 1024 * 1024
MAX_TAR = MAX_TOTAL + (MAX_FILES + 1) * 1024 + tarfile.RECORDSIZE
AGE_HEADER = b"age-encryption.org/v1\n"
LIMITATIONS = [
    "Production application configuration has not been generated or activated.",
    "Only explicit regular files are collected; no automatic discovery or environment activation.",
    "Recorded POSIX metadata is provenance; Windows access is protected by a separately verified parent ACL.",
]
INACTIVE_PRODUCTION_LIMITATIONS = [
    "Production settings are declared staged-inactive; this archive does not prove server activation state.",
    *LIMITATIONS[1:],
]


class ConfigurationError(Exception):
    pass


def need(value, code):
    if not value:
        raise ConfigurationError(code)


def scope_profile(scope):
    # An explicit closed set, not a way to bypass the legacy scope restriction.
    need(isinstance(scope, str) and scope in (SCOPE, INACTIVE_PRODUCTION_SCOPE), "CONFIG_BACKUP_SCOPE_REJECTED")
    production = scope == INACTIVE_PRODUCTION_SCOPE
    return production, 2 if production else 1, INACTIVE_PRODUCTION_LIMITATIONS if production else LIMITATIONS


def digest(value):
    return hashlib.sha256(value).hexdigest()


def regular_path(value, directory=False):
    need(isinstance(value, (str, Path)), "CONFIG_BACKUP_PATH_REJECTED")
    path = Path(value)
    need(path.is_absolute() and ".." not in path.parts, "CONFIG_BACKUP_PATH_REJECTED")
    need(path.resolve(strict=True) == path, "CONFIG_BACKUP_LINK_REJECTED")
    for entry in (*reversed(path.parents), path):
        info = entry.lstat()
        need(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400, "CONFIG_BACKUP_LINK_REJECTED")
    info = path.lstat()
    need(stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode), "CONFIG_BACKUP_SPECIAL_FILE_REJECTED")
    if not directory:
        need(info.st_nlink == 1, "CONFIG_BACKUP_HARD_LINK_REJECTED")
    return path


def windows_private_acl(path):
    """Inspect the existing ACL with Windows APIs; never changes an ACL or runs a shell."""
    from ctypes import wintypes as w
    adv = ctypes.WinDLL("advapi32", use_last_error=True)
    kernel = ctypes.WinDLL("kernel32", use_last_error=True)
    adv.OpenProcessToken.argtypes = [w.HANDLE, w.DWORD, ctypes.POINTER(w.HANDLE)]
    adv.OpenProcessToken.restype = w.BOOL
    adv.GetTokenInformation.argtypes = [w.HANDLE, ctypes.c_int, w.LPVOID, w.DWORD, ctypes.POINTER(w.DWORD)]
    adv.GetTokenInformation.restype = w.BOOL
    adv.ConvertSidToStringSidW.argtypes = [w.LPVOID, ctypes.POINTER(w.LPWSTR)]
    adv.ConvertSidToStringSidW.restype = w.BOOL
    adv.GetNamedSecurityInfoW.argtypes = [w.LPWSTR, ctypes.c_int, w.DWORD, ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID),
                                        ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID), ctypes.POINTER(w.LPVOID)]
    adv.GetNamedSecurityInfoW.restype = w.DWORD
    adv.GetAclInformation.argtypes = [w.LPVOID, w.LPVOID, w.DWORD, ctypes.c_int]
    adv.GetAclInformation.restype = w.BOOL
    adv.GetAce.argtypes = [w.LPVOID, w.DWORD, ctypes.POINTER(w.LPVOID)]
    adv.GetAce.restype = w.BOOL
    kernel.GetCurrentProcess.restype = w.HANDLE
    kernel.CloseHandle.argtypes = [w.HANDLE]
    kernel.LocalFree.argtypes = [w.LPVOID]
    kernel.LocalFree.restype = w.LPVOID

    def sid_text(pointer):
        value = w.LPWSTR()
        need(adv.ConvertSidToStringSidW(pointer, ctypes.byref(value)), "CONFIG_BACKUP_ACL_REJECTED")
        try:
            return value.value
        finally:
            kernel.LocalFree(ctypes.cast(value, w.LPVOID))

    token = w.HANDLE()
    need(adv.OpenProcessToken(kernel.GetCurrentProcess(), 8, ctypes.byref(token)), "CONFIG_BACKUP_ACL_REJECTED")
    try:
        size = w.DWORD()
        adv.GetTokenInformation(token, 1, None, 0, ctypes.byref(size))
        need(0 < size.value < 65536, "CONFIG_BACKUP_ACL_REJECTED")
        buffer = ctypes.create_string_buffer(size.value)
        need(adv.GetTokenInformation(token, 1, buffer, size, ctypes.byref(size)), "CONFIG_BACKUP_ACL_REJECTED")
        current_sid = sid_text(ctypes.cast(buffer, ctypes.POINTER(w.LPVOID))[0])
    finally:
        kernel.CloseHandle(token)
    owner, dacl, descriptor = w.LPVOID(), w.LPVOID(), w.LPVOID()
    code = adv.GetNamedSecurityInfoW(str(path), 1, 5, ctypes.byref(owner), None, ctypes.byref(dacl), None, ctypes.byref(descriptor))
    need(code == 0 and owner and dacl and descriptor, "CONFIG_BACKUP_ACL_REJECTED")
    try:
        allowed = {current_sid, "S-1-5-18"}
        need(sid_text(owner) in allowed, "CONFIG_BACKUP_ACL_OWNER_REJECTED")
        class AclSize(ctypes.Structure):
            _fields_ = [("count", w.DWORD), ("used", w.DWORD), ("free", w.DWORD)]
        acl = AclSize()
        need(adv.GetAclInformation(dacl, ctypes.byref(acl), ctypes.sizeof(acl), 2), "CONFIG_BACKUP_ACL_REJECTED")
        need(0 < acl.count <= 128, "CONFIG_BACKUP_ACL_REJECTED")
        for index in range(acl.count):
            ace = w.LPVOID()
            need(adv.GetAce(dacl, index, ctypes.byref(ace)), "CONFIG_BACKUP_ACL_REJECTED")
            header = ctypes.string_at(ace, 4)
            ace_type, ace_size = header[0], int.from_bytes(header[2:4], "little")
            need(ace_type in (0, 1) and ace_size >= 16, "CONFIG_BACKUP_ACL_REJECTED")
            if ace_type == 0:
                need(sid_text(ace.value + 8) in allowed, "CONFIG_BACKUP_ACL_REJECTED")
    finally:
        kernel.LocalFree(descriptor)


def private_path(value, directory=False):
    path = regular_path(value, directory=directory)
    if os.name == "nt":
        windows_private_acl(path)
    else:
        info = path.stat()
        need(info.st_uid == os.geteuid() and not info.st_mode & 0o077, "CONFIG_BACKUP_PRIVATE_PERMISSIONS_REQUIRED")
    return path


def new_path(value, directory=False):
    path = Path(value)
    need(path.is_absolute() and ".." not in path.parts and path.name not in ("", ".", ".."), "CONFIG_BACKUP_PATH_REJECTED")
    private_path(path.parent, directory=True)
    need(not path.exists() and not path.is_symlink(), "CONFIG_BACKUP_DESTINATION_EXISTS")
    return path


def archive_path(value):
    need(isinstance(value, str) and len(value) <= 512 and value == unicodedata.normalize("NFC", value), "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
    need(not any(ord(char) < 32 or ord(char) == 127 for char in value) and "\\" not in value and ":" not in value, "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
    parts = value.split("/")
    need(2 <= len(parts) <= 16 and parts[0] == "files" and all(part not in ("", ".", "..") and len(part) <= 128 and not part.endswith((".", " ")) for part in parts), "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
    for part in parts:
        stem = part.split(".", 1)[0].upper()
        # Reject Windows device aliases on every platform before restore creates
        # a destination. Windows also treats superscript 1/2/3 as COM/LPT digits.
        need(stem not in {"CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
                          *[f"{prefix}{digit}" for prefix in ("COM", "LPT") for digit in "123456789¹²³"]},
             "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
    need(str(PurePosixPath(value)) == value and not PurePosixPath(value).is_absolute(), "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
    return value


def allowed_name(value):
    normalized = str(value).replace("\\", "/").lower()
    parts = normalized.split("/")
    name = parts[-1]
    need(".ssh" not in parts and not name.startswith(".env.root"), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")
    need(not re.search(r"(^|[-_.])(identity|identities)([-_.]|$)", name), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")
    need(not re.search(r"(^|[-_.])(recovery|age)[-_.]*(private|secret|key)([-_.]|$)", name), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")
    need(not re.search(r"(^|[-_.])root[-_.]*(access[-_.]*)?(key|keys|credential|credentials|secret)([-_.]|$)", name), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")
    need(not re.match(r"id_(rsa|ed25519|ecdsa|dsa)(\.|$)", name), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")
    need(not name.endswith((".pem", ".p12", ".pfx", ".ppk")) and (not name.endswith(".key") or name.startswith("vapid")), "CONFIG_BACKUP_FORBIDDEN_KEY_FILE")


def allowed_content(data):
    # Private-key file markers are denied regardless of a renamed file. VAPID's
    # intentional base64url secret is a different format and remains allowed.
    upper = data.upper()
    need(b"AGE-SECRET-KEY-" not in upper and b"PRIVATE KEY-----" not in upper and b"PUTTY-USER-KEY-FILE-" not in upper,
         "CONFIG_BACKUP_FORBIDDEN_KEY_CONTENT")
    need(not re.search(rb"(?:AWS_ROOT_ACCESS_KEY|AWS_ROOT_SECRET|ROOT_ACCESS_KEY|ROOT_SECRET_KEY|SSH_PRIVATE_KEY)\s*=", upper), "CONFIG_BACKUP_FORBIDDEN_KEY_CONTENT")


def file_snapshot(info):
    # Windows lstat and fstat can report different cached creation/change times
    # for a just-closed writer. Size, identity, mode and mtime remain mandatory;
    # full content hashes are also compared before and after age/output writes.
    changed = info.st_ctime_ns if os.name != "nt" else None
    return (info.st_dev, info.st_ino, info.st_size, stat.S_IMODE(info.st_mode), getattr(info, "st_uid", 0), getattr(info, "st_gid", 0), info.st_mtime_ns, changed)


def read_source(value):
    path = regular_path(value)
    allowed_name(path)
    before = path.lstat()
    need(not stat.S_IMODE(before.st_mode) & 0o7000 and before.st_size <= MAX_TOTAL, "CONFIG_BACKUP_SOURCE_LIMIT_REJECTED")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0) | getattr(os, "O_BINARY", 0)
    descriptor = os.open(path, flags)
    with os.fdopen(descriptor, "rb") as source:
        need(file_snapshot(os.fstat(source.fileno())) == file_snapshot(before), "CONFIG_BACKUP_SOURCE_CHANGED")
        data = source.read(MAX_TOTAL + 1)
        after = os.fstat(source.fileno())
    need(len(data) == before.st_size and file_snapshot(before) == file_snapshot(after) == file_snapshot(path.lstat()), "CONFIG_BACKUP_SOURCE_CHANGED")
    allowed_content(data)
    return path, data, before


def verify_sources(sources):
    for path, original, info in sources:
        current, data, after = read_source(path)
        need(current == path and file_snapshot(info) == file_snapshot(after) and digest(original) == digest(data), "CONFIG_BACKUP_SOURCE_CHANGED")


def age_call(executable, arguments, data=None):
    executable = regular_path(executable)
    # Child diagnostics, inherited key/askpass/plugin settings and exception
    # details never go to stdout. Only this explicitly selected age is launched.
    env = {name: os.environ[name] for name in ("SystemRoot", "WINDIR", "COMSPEC", "LANG") if name in os.environ}
    env["PATH"] = str(executable.parent)
    try:
        result = subprocess.run([str(executable), *arguments], input=data, stdin=subprocess.DEVNULL if data is None else None,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=env, timeout=180, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ConfigurationError("CONFIG_BACKUP_AGE_FAILED") from None
    need(result.returncode == 0, "CONFIG_BACKUP_AGE_AUTHENTICATION_OR_PROCESS_FAILED")
    need(len(result.stdout) <= MAX_CIPHER, "CONFIG_BACKUP_AGE_OUTPUT_TOO_LARGE")
    return result.stdout


def check_age(executable):
    need(age_call(executable, ["--version"]).strip() == b"v1.3.2", "CONFIG_BACKUP_AGE_VERSION_REJECTED")


def public_recipient(value):
    need(isinstance(value, str) and re.fullmatch(r"age1[023456789acdefghjklmnpqrstuvwxyz]{58}", value), "CONFIG_BACKUP_X25519_RECIPIENT_REQUIRED")


def record_for(path, member, data, info):
    return {"archivePath": member, "sourcePath": str(path), "size": len(data), "sha256": digest(data), "mode": stat.S_IMODE(info.st_mode),
            "uid": getattr(info, "st_uid", 0), "gid": getattr(info, "st_gid", 0), "mtimeNs": info.st_mtime_ns, "ctimeNs": info.st_ctime_ns}


def add_tar(archive, name, data):
    info = tarfile.TarInfo(name)
    info.mode, info.uid, info.gid, info.mtime, info.size = 0o600, 0, 0, 0, len(data)
    archive.addfile(info, io.BytesIO(data))


def write_new(path, data, mode=0o600):
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0), 0o600)
    try:
        with os.fdopen(descriptor, "wb") as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        if os.name != "nt":
            os.chmod(path, mode)
        else:
            private_path(path)
    except BaseException:
        Path(path).unlink()
        raise


def source_metadata(value):
    need(isinstance(value, dict), "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
    def visit(item, depth=0):
        need(depth <= 8, "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
        if isinstance(item, dict):
            need(len(item) <= 256 and all(isinstance(key, str) for key in item), "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
            for child in item.values(): visit(child, depth + 1)
        elif isinstance(item, list):
            need(len(item) <= 256, "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
            for child in item: visit(child, depth + 1)
        else:
            need(item is None or type(item) in (str, bool, int, float), "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
    visit(value)
    encoded = json.dumps(value, allow_nan=False, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    need(len(encoded) <= MAX_SOURCE_METADATA, "CONFIG_BACKUP_SOURCE_METADATA_REJECTED")
    allowed_content(encoded)
    return value


def pack(config_path, recipient, executable, output_path, *, scope=SCOPE):
    production, version, limitations = scope_profile(scope)
    output = new_path(output_path)
    config_path = private_path(config_path)
    need(config_path.stat().st_size <= MAX_MANIFEST, "CONFIG_BACKUP_CONFIG_TOO_LARGE")
    config = json.loads(config_path.read_text(encoding="utf-8"))
    required = {"scope", "productionConfigurationCreated", "files"} | ({"configurationState"} if production else set())
    need(isinstance(config, dict) and required <= set(config) <= required | {"sourceMetadata"}
         and config["scope"] == scope and config["productionConfigurationCreated"] is production
         and (not production or config["configurationState"] == INACTIVE_PRODUCTION_STATE), "CONFIG_BACKUP_SCOPE_REJECTED")
    need(isinstance(config["files"], list) and 1 <= len(config["files"]) <= MAX_FILES, "CONFIG_BACKUP_FILE_COUNT_REJECTED")
    metadata = source_metadata(config.get("sourceMetadata", {}))
    public_recipient(recipient)
    sources, records, contents, paths, members, identities = [], [], {}, set(), set(), set()
    total = 0
    for item in config["files"]:
        need(isinstance(item, dict) and set(item) == {"source", "archivePath"}, "CONFIG_BACKUP_ALLOWLIST_REJECTED")
        member = archive_path(item["archivePath"])
        allowed_name(member)
        path, data, info = read_source(item["source"])
        canonical = os.path.normcase(str(path))
        folded = member.casefold()
        need(canonical not in paths and folded not in members and not any(folded.startswith(previous + "/") or previous.startswith(folded + "/") for previous in members)
             and (info.st_dev, info.st_ino) not in identities, "CONFIG_BACKUP_DUPLICATE_FILE_REJECTED")
        need(path not in (output, config_path), "CONFIG_BACKUP_SOURCE_OUTPUT_COLLISION")
        paths.add(canonical); members.add(member.casefold()); identities.add((info.st_dev, info.st_ino))
        sources.append((path, data, info)); contents[member] = data
        records.append(record_for(path, member, data, info))
        total += len(data)
        need(total <= MAX_TOTAL, "CONFIG_BACKUP_TOTAL_LIMIT_REJECTED")
    manifest = {"formatVersion": version, "scope": scope, "productionConfigurationCreated": production,
                "createdAtUtc": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z"),
                "sourcePlatform": "windows" if os.name == "nt" else "posix", "limitations": limitations, "sourceMetadata": metadata, "files": records}
    if production:
        manifest["configurationState"] = INACTIVE_PRODUCTION_STATE
    encoded = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    need(len(encoded) <= MAX_MANIFEST and total + len(encoded) <= MAX_TOTAL, "CONFIG_BACKUP_TOTAL_LIMIT_REJECTED")
    memory = io.BytesIO()
    with tarfile.open(fileobj=memory, mode="w:gz", format=tarfile.USTAR_FORMAT) as archive:
        add_tar(archive, "manifest.json", encoded)
        for member, data in contents.items():
            add_tar(archive, member, data)
    check_age(executable)
    ciphertext = age_call(executable, ["--encrypt", "--recipient", recipient], memory.getvalue())
    need(200 <= len(ciphertext) <= MAX_CIPHER and ciphertext.startswith(AGE_HEADER), "CONFIG_BACKUP_CIPHERTEXT_REJECTED")
    verify_sources(sources)
    created = False
    try:
        # Exclusive creation, never atomic replacement of a caller's existing file.
        need(not output.exists() and not output.is_symlink(), "CONFIG_BACKUP_DESTINATION_EXISTS")
        write_new(output, ciphertext)
        created = True
        verify_sources(sources)
    except BaseException:
        if created:
            output.unlink()
        raise
    result = {"passed": True, "cipherSha256": digest(ciphertext), "ciphertextBytes": len(ciphertext), "fileCount": len(records),
              "sourcesUnchanged": True, "productionConfigurationCreated": production}
    if production:
        result.update(scope=scope, configurationState=INACTIVE_PRODUCTION_STATE)
    return result


def validate_manifest(value, *, expected_scope=SCOPE):
    production, version, limitations = scope_profile(expected_scope)
    fields = {"formatVersion", "scope", "productionConfigurationCreated", "createdAtUtc", "sourcePlatform", "limitations", "sourceMetadata", "files"} | ({"configurationState"} if production else set())
    need(isinstance(value, dict) and set(value) == fields, "CONFIG_BACKUP_MANIFEST_REJECTED")
    need(type(value["formatVersion"]) is int and value["formatVersion"] == version
         and value["scope"] == expected_scope and value["productionConfigurationCreated"] is production
         and (not production or value["configurationState"] == INACTIVE_PRODUCTION_STATE)
         and value["sourcePlatform"] in ("windows", "posix") and value["limitations"] == limitations, "CONFIG_BACKUP_MANIFEST_REJECTED")
    source_metadata(value["sourceMetadata"])
    need(isinstance(value["createdAtUtc"], str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", value["createdAtUtc"]), "CONFIG_BACKUP_MANIFEST_REJECTED")
    need(isinstance(value["files"], list) and 1 <= len(value["files"]) <= MAX_FILES, "CONFIG_BACKUP_FILE_COUNT_REJECTED")
    records, casefold = {}, set()
    fields = {"archivePath", "sourcePath", "size", "sha256", "mode", "uid", "gid", "mtimeNs", "ctimeNs"}
    total = 0
    for record in value["files"]:
        need(isinstance(record, dict) and set(record) == fields, "CONFIG_BACKUP_MANIFEST_REJECTED")
        member = archive_path(record["archivePath"])
        allowed_name(member)
        folded = member.casefold()
        need(folded not in casefold and not any(folded.startswith(previous + "/") or previous.startswith(folded + "/") for previous in casefold), "CONFIG_BACKUP_DUPLICATE_FILE_REJECTED")
        need(isinstance(record["sourcePath"], str) and 0 < len(record["sourcePath"]) <= 4096, "CONFIG_BACKUP_MANIFEST_REJECTED")
        allowed_name(record["sourcePath"])
        need(type(record["size"]) is int and 0 <= record["size"] <= MAX_TOTAL and type(record["mode"]) is int and 0 <= record["mode"] <= 0o777, "CONFIG_BACKUP_MANIFEST_REJECTED")
        need(isinstance(record["sha256"], str) and re.fullmatch(r"[a-f0-9]{64}", record["sha256"]), "CONFIG_BACKUP_MANIFEST_REJECTED")
        need(all(type(record[name]) is int and 0 <= record[name] < 2**64 for name in ("uid", "gid"))
             and all(type(record[name]) is int and -(2**63) <= record[name] < 2**63 for name in ("mtimeNs", "ctimeNs")), "CONFIG_BACKUP_MANIFEST_REJECTED")
        total += record["size"]
        need(total <= MAX_TOTAL, "CONFIG_BACKUP_TOTAL_LIMIT_REJECTED")
        records[member] = record
        casefold.add(member.casefold())
    return records


def parse_archive(authenticated, *, expected_scope=SCOPE):
    scope_profile(expected_scope)
    need(len(authenticated) <= MAX_CIPHER, "CONFIG_BACKUP_ARCHIVE_LIMIT_REJECTED")
    # Authenticate first (the caller), then fully verify gzip CRC with a hard
    # decompression ceiling before tarfile can consume extended-header records.
    try:
        with gzip.GzipFile(fileobj=io.BytesIO(authenticated), mode="rb") as compressed:
            tar_data = compressed.read(MAX_TAR + 1)
    except (OSError, EOFError):
        raise ConfigurationError("CONFIG_BACKUP_GZIP_INTEGRITY_REJECTED") from None
    need(len(tar_data) <= MAX_TAR and len(tar_data) % tarfile.BLOCKSIZE == 0, "CONFIG_BACKUP_ARCHIVE_LIMIT_REJECTED")
    contents, seen, total, records, manifest_data = {}, set(), 0, None, None
    next_offset = 0
    with tarfile.open(fileobj=io.BytesIO(tar_data), mode="r:") as archive:
        for member in archive:
            need(member.isfile() and not member.issym() and not member.islnk() and not member.pax_headers
                 and member.mode == 0o600 and member.uid == 0 and member.gid == 0 and member.mtime == 0
                 and member.offset == next_offset and member.offset_data == member.offset + tarfile.BLOCKSIZE,
                 "CONFIG_BACKUP_MEMBER_TYPE_OR_MODE_REJECTED")
            need(member.name not in seen and len(seen) < MAX_FILES + 1, "CONFIG_BACKUP_DUPLICATE_FILE_REJECTED")
            if records is None:
                need(member.name == "manifest.json" and 0 < member.size <= MAX_MANIFEST, "CONFIG_BACKUP_MANIFEST_REQUIRED_FIRST")
            else:
                archive_path(member.name)
                need(member.name in records and member.size == records[member.name]["size"], "CONFIG_BACKUP_MEMBER_NOT_ALLOWLISTED")
            need(0 <= member.size <= MAX_TOTAL and total + member.size <= MAX_TOTAL, "CONFIG_BACKUP_TOTAL_LIMIT_REJECTED")
            reader = archive.extractfile(member)
            need(reader is not None, "CONFIG_BACKUP_MEMBER_READ_FAILED")
            with reader:
                data = reader.read(member.size + 1)
            need(len(data) == member.size, "CONFIG_BACKUP_MEMBER_SIZE_MISMATCH")
            total += len(data); seen.add(member.name)
            if records is None:
                manifest_data = data
                records = validate_manifest(json.loads(data.decode("utf-8")), expected_scope=expected_scope)
            else:
                need(digest(data) == records[member.name]["sha256"], "CONFIG_BACKUP_MEMBER_HASH_MISMATCH")
                allowed_content(data)
                contents[member.name] = data
            next_offset = member.offset_data + ((member.size + tarfile.BLOCKSIZE - 1) // tarfile.BLOCKSIZE) * tarfile.BLOCKSIZE
    need(len(tar_data) - next_offset >= 2 * tarfile.BLOCKSIZE and not any(tar_data[next_offset:]), "CONFIG_BACKUP_TRAILING_ARCHIVE_DATA_REJECTED")
    need(records is not None and set(contents) == set(records), "CONFIG_BACKUP_REQUIRED_FILE_MISSING")
    return records, contents, manifest_data


def restore(cipher_path, identity_path, executable, destination_path, *, scope=SCOPE):
    production, _version, _limitations = scope_profile(scope)
    destination = new_path(destination_path, directory=True)
    cipher_path = regular_path(cipher_path)
    identity = private_path(identity_path)  # age alone reads the identity's contents.
    info = cipher_path.stat()
    need(200 <= info.st_size <= MAX_CIPHER, "CONFIG_BACKUP_CIPHERTEXT_REJECTED")
    cipher_before = cipher_path.read_bytes()
    check_age(executable)
    # No archive parser or destination creation is reached unless age exits zero.
    # Decrypt the exact captured bytes whose SHA is reported, rather than
    # asking the child to reopen a pathname that could have changed meanwhile.
    authenticated = age_call(executable, ["--decrypt", "--identity", str(identity)], cipher_before)
    records, contents, manifest_data = parse_archive(authenticated, expected_scope=scope)
    need(file_snapshot(info) == file_snapshot(cipher_path.stat()) and digest(cipher_before) == digest(cipher_path.read_bytes()), "CONFIG_BACKUP_CIPHERTEXT_CHANGED")
    created_files, created_directories = [], []
    try:
        destination.mkdir(mode=0o777 if os.name == "nt" else 0o700)
        created_directories.append(destination)
        private_path(destination, directory=True)
        for member, data in {"manifest.json": manifest_data, **contents}.items():
            target = destination.joinpath(*PurePosixPath(member).parts)
            need(target.is_relative_to(destination), "CONFIG_BACKUP_MEMBER_PATH_REJECTED")
            missing = []
            parent = target.parent
            while parent != destination and not parent.exists():
                missing.append(parent); parent = parent.parent
            for parent in reversed(missing):
                parent.mkdir(mode=0o777 if os.name == "nt" else 0o700)
                created_directories.append(parent)
                private_path(parent, directory=True)
            mode = records[member]["mode"] if member in records else 0o600
            write_new(target, data, mode=mode)
            created_files.append(target)
    except BaseException:
        # Only exact paths this invocation created are removed; no recursive
        # scan/delete, glob or pre-existing destination is ever touched.
        for path in reversed(created_files):
            if path.is_relative_to(destination) and path.is_file() and not path.is_symlink():
                path.unlink()
        for path in reversed(created_directories):
            try:
                if path == destination or path.is_relative_to(destination):
                    path.rmdir()
            except OSError:
                pass
        raise
    result = {"passed": True, "cipherSha256": digest(cipher_before), "ciphertextBytes": len(cipher_before), "fileCount": len(records),
              "authenticated": True, "allFilesVerifiedBeforeWrite": True, "posixFileModesApplied": os.name != "nt", "filesActivated": False,
              "productionConfigurationCreated": production}
    if production:
        result.update(scope=scope, configurationState=INACTIVE_PRODUCTION_STATE)
    return result


def main():
    class SafeParser(argparse.ArgumentParser):
        def error(self, _message):
            raise ConfigurationError("CONFIG_BACKUP_ARGUMENTS_REJECTED")
    parser = SafeParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    packing = sub.add_parser("pack")
    for argument in ("config", "recipient", "age", "output"):
        packing.add_argument("--" + argument, required=True)
    recovering = sub.add_parser("restore")
    for argument in ("cipher", "identity", "age", "destination"):
        recovering.add_argument("--" + argument, required=True)
    for action in (packing, recovering):
        action.add_argument("--scope", choices=(SCOPE, INACTIVE_PRODUCTION_SCOPE), default=SCOPE)
    try:
        args = parser.parse_args()
        result = pack(args.config, args.recipient, args.age, args.output, scope=args.scope) if args.action == "pack" else restore(args.cipher, args.identity, args.age, args.destination, scope=args.scope)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except SystemExit as error:
        return int(error.code or 0)
    except BaseException as error:
        code = str(error) if isinstance(error, ConfigurationError) and re.fullmatch(r"CONFIG_BACKUP_[A-Z0-9_]+", str(error)) else "CONFIG_BACKUP_OPERATION_FAILED"
        print(json.dumps({"passed": False, "error": code}, separators=(",", ":")))
        return 1


if __name__ == "__main__":
    sys.exit(main())
