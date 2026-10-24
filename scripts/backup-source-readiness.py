#!/usr/bin/env python3
"""Bounded readiness for one existing synthetic backup source; never creates containers."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import time

sys.dont_write_bytecode = True
WAIT_SECONDS = 120
COMMAND_SECONDS = 10
MAX_INSPECT_BYTES = 1024 * 1024


class ReadinessError(Exception):
    pass


def need(condition, code):
    if not condition:
        raise ReadinessError(code)


def validate_import_path(path):
    """Validate the explicit sibling before executing it, without importing local search paths."""
    path = Path(path)
    need(path.is_absolute() and ".." not in path.parts, "BACKUP_SOURCE_MODULE_PATH_REJECTED")
    for parent in reversed(path.parents):
        info = parent.lstat()
        need(stat.S_ISDIR(info.st_mode) and not parent.is_symlink() and info.st_uid == 0
             and not info.st_mode & 0o022, "BACKUP_SOURCE_MODULE_PARENT_REJECTED")
    info = path.lstat()
    need(stat.S_ISREG(info.st_mode) and not path.is_symlink() and info.st_uid == 0
         and info.st_nlink == 1 and not info.st_mode & 0o022, "BACKUP_SOURCE_MODULE_FILE_REJECTED")
    need(path.name == "production-backup.py" and path.stat().st_size < 128 * 1024,
         "BACKUP_SOURCE_MODULE_FILE_REJECTED")
    return path


def load_backup_module():
    path = validate_import_path(Path(__file__).absolute().with_name("production-backup.py"))
    spec = importlib.util.spec_from_file_location("jeongeum_validated_production_backup", path)
    need(spec is not None and spec.loader is not None, "BACKUP_SOURCE_MODULE_REJECTED")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BoundedDocker:
    """All reused Docker/SQL calls share the same monotonic deadline."""
    def __init__(self, runner, deadline, clock):
        self.runner, self.cfg, self.deadline, self.clock = runner, runner.cfg, deadline, clock

    def remaining(self):
        remaining = self.deadline - self.clock()
        need(remaining > 0, "BACKUP_SOURCE_READY_TIMEOUT")
        return remaining

    def docker(self, *arguments, **kwargs):
        kwargs["timeout"] = min(kwargs.get("timeout", COMMAND_SECONDS), COMMAND_SECONDS, self.remaining())
        return self.runner.docker(*arguments, **kwargs)


def inspect_existing(runner):
    cfg = runner.cfg
    raw = runner.docker("inspect", cfg["container_id"])
    need(isinstance(raw, bytes) and len(raw) <= MAX_INSPECT_BYTES, "BACKUP_SOURCE_INSPECTION_REJECTED")
    try:
        info = json.loads(raw)
    except (ValueError, UnicodeError):
        raise ReadinessError("BACKUP_SOURCE_INSPECTION_REJECTED") from None
    need(isinstance(info, list) and len(info) == 1 and isinstance(info[0], dict),
         "BACKUP_SOURCE_INSPECTION_REJECTED")
    item = info[0]
    need(item.get("Id") == cfg["container_id"] and re.fullmatch(r"[0-9a-f]{64}", cfg["container_id"]),
         "BACKUP_SOURCE_ID_REJECTED")
    config, host, network = item.get("Config"), item.get("HostConfig"), item.get("NetworkSettings")
    need(all(isinstance(value, dict) for value in (config, host, network)), "BACKUP_SOURCE_INSPECTION_REJECTED")
    labels = config.get("Labels", {})
    need(isinstance(labels, dict) and labels.get("com.docker.compose.project") == cfg["project"]
         and labels.get("com.docker.compose.service") == "postgres", "BACKUP_SOURCE_LABELS_REJECTED")
    need(config.get("Image") == cfg["postgres_image"], "BACKUP_SOURCE_IMAGE_REJECTED")
    mounts = item.get("Mounts")
    need(isinstance(mounts, list) and all(isinstance(mount, dict) for mount in mounts), "BACKUP_SOURCE_VOLUME_REJECTED")
    data = [mount for mount in mounts if mount.get("Destination") == "/var/lib/postgresql/data"]
    named = [mount for mount in mounts if mount.get("Type") == "volume"]
    need(len(data) == 1 and len(named) == 1 and data[0] == named[0]
         and data[0].get("Type") == "volume" and data[0].get("Name") == cfg["data_volume"],
         "BACKUP_SOURCE_VOLUME_REJECTED")
    bindings, ports, network_mode = host.get("PortBindings"), network.get("Ports"), host.get("NetworkMode")
    need(bindings is None or isinstance(bindings, dict), "BACKUP_SOURCE_PORTS_REJECTED")
    need(ports is None or isinstance(ports, dict), "BACKUP_SOURCE_PORTS_REJECTED")
    need(not bindings and not any((ports or {}).values())
         and host.get("PublishAllPorts") is False
         and isinstance(network_mode, str) and bool(network_mode) and network_mode not in ("host", "none")
         and not network_mode.startswith("container:"), "BACKUP_SOURCE_PORTS_REJECTED")
    state = item.get("State")
    need(isinstance(state, dict) and all(state.get(flag) is False for flag in ("Paused", "Restarting", "Dead")),
         "BACKUP_SOURCE_STATE_REJECTED")
    status = state.get("Status")
    need(status in ("created", "exited", "running") and state.get("Running") is (status == "running"),
         "BACKUP_SOURCE_STATE_REJECTED")
    return state


def ensure_ready(backup, cfg, runner=None, *, clock=time.monotonic, sleep=time.sleep):
    runner = BoundedDocker(runner or backup.Runner(cfg), clock() + WAIT_SECONDS, clock)
    state = inspect_existing(runner)
    started = state["Status"] in ("created", "exited")
    if started:
        # The complete identity, volume, image, and port guard precedes this sole mutation.
        runner.docker("start", cfg["container_id"])
    while True:
        state = inspect_existing(runner)
        need(state["Status"] == "running", "BACKUP_SOURCE_STOPPED_DURING_READINESS")
        health = state.get("Health")
        need(isinstance(health, dict) and health.get("Status") in ("starting", "healthy", "unhealthy"),
             "BACKUP_SOURCE_HEALTHCHECK_REJECTED")
        if health["Status"] == "healthy":
            # Reuse the original binding guard, including the READ ONLY database/owner query.
            backup.inspect_source(runner)
            runner.remaining()
            return {"ok": True, "status": "source-ready", "syntheticOnly": True, "existingSourceStarted": started}
        sleep(min(1.0, runner.remaining()))


class SafeParser(argparse.ArgumentParser):
    def error(self, _message):
        raise ReadinessError("BACKUP_SOURCE_ARGUMENTS_REJECTED")


def main(argv=None):
    backup = None
    try:
        parser = SafeParser(description=__doc__)
        parser.add_argument("--config", default="/etc/jeongeum-backup/config.json")
        args = parser.parse_args(argv)
        need(sys.platform == "linux" and os.geteuid() == 0, "BACKUP_SOURCE_LINUX_ROOT_REQUIRED")
        os.umask(0o077)

        def interrupted(_signum, _frame):
            raise ReadinessError("BACKUP_SOURCE_INTERRUPTED")

        signal.signal(signal.SIGTERM, interrupted)
        backup = load_backup_module()
        cfg = backup.load_config(args.config)
        result = ensure_ready(backup, cfg)
        print(json.dumps(result, separators=(",", ":")))
        return 0
    except SystemExit as error:
        # argparse's successful --help exit is not an operational failure.
        return 0 if error.code == 0 else 1
    except BaseException as error:
        known = isinstance(error, ReadinessError) or (backup is not None and isinstance(error, backup.BackupError))
        code = str(error) if known and re.fullmatch(r"BACKUP_[A-Z0-9_]+", str(error)) else "BACKUP_SOURCE_OPERATION_FAILED"
        # Never emit config values, subprocess arguments/stdout/stderr, SQL, or traceback.
        print(json.dumps({"ok": False, "error": code}, separators=(",", ":")))
        return 1


if __name__ == "__main__":
    sys.exit(main())
