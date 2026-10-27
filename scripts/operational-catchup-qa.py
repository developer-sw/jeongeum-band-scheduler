#!/usr/bin/env python3
"""Disposable, offline TCG guest: real PID 1 systemd + PG, synthetic data only.

Docker binding and AWS transport use the existing strict qualification shims.
They do not validate a real Docker daemon or S3; native PG QA covers that source
binding separately. No host clock, host cgroup or production state is changed.
"""
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import traceback

ROOT = Path('/qa')
CHECKS = []
STAGE = 'initial'
RESULT_MARKER = 'JEONGEUM_CATCHUP_RESULT='
SERVICE = 'jeongeum-catchup-backup.service'
TIMER = 'jeongeum-catchup-backup.timer'
TIMEOUT_TIMER = 'jeongeum-catchup-timeout.timer'
PG = '/usr/lib/postgresql/17/bin/'
DATA = '/var/lib/postgresql/catchup-data'


def need(condition, code):
    if not condition:
        raise RuntimeError(code)


def check(code, condition=True):
    need(condition, code)
    CHECKS.append(code)


def run(command, data=None, timeout=60, allow_failure=False):
    result = subprocess.run(command, input=data, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, timeout=timeout, check=False)
    if result.returncode and not allow_failure:
        diagnostic_root = ROOT if ROOT.exists() else Path('/vmwork')
        if diagnostic_root.exists():
            (diagnostic_root / 'last-command.private.log').write_bytes(result.stderr)
        raise RuntimeError('GUEST_COMMAND_FAILED_' + Path(command[0]).name.replace('-', '_'))
    return result


def write(path, content, mode=0o600):
    path = Path(path)
    path.write_text(content, encoding='utf-8')
    path.chmod(mode)


def load_fixture():
    spec = importlib.util.spec_from_file_location('catchup_fixture', ROOT / 'scripts/operational-backup-qa.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def pg(*arguments, data=None):
    return run(['/usr/local/bin/gosu', 'postgres', PG + arguments[0], *arguments[1:]], data=data).stdout


def start_pg():
    pg('pg_ctl', '-D', DATA, 'start', '-w', '-t', '15', '-l', DATA + '/guest-pg.log')


def stop_pg():
    pg('pg_ctl', '-D', DATA, 'stop', '-m', 'fast', '-w', '-t', '15')


def seed(database):
    pg('psql', '-X', '-qAt', '--set', 'ON_ERROR_STOP=1', '--dbname', 'postgres',
       data=(f'CREATE ROLE {database} LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;\n'
             f'CREATE DATABASE {database} OWNER {database};\n').encode())
    sql = f'ALTER SCHEMA public OWNER TO {database};\nSET ROLE {database};\n'
    sql += ('CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY,version varchar(50),'
            'description text,type text,script text,checksum integer,installed_by text,'
            'installed_on timestamp DEFAULT CURRENT_TIMESTAMP,execution_time integer,success boolean);\n'
            "INSERT INTO flyway_schema_history SELECT n,n::text,'qualification fixture','SQL','fixture',n,"
            "'qualification',CURRENT_TIMESTAMP,1,true FROM generate_series(1,12) n;\n")
    for i in range(1, 39):
        sql += f"CREATE TABLE qa_table_{i}(id bigint PRIMARY KEY,payload text); INSERT INTO qa_table_{i} VALUES ({i},'synthetic-row-{i}');\n"
    for i in range(1, 30):
        sql += f"CREATE SEQUENCE qa_sequence_{i}; SELECT setval('qa_sequence_{i}',{i+3},{'true' if i%2 == 0 else 'false'});\n"
    pg('psql', '-X', '-qAt', '--set', 'ON_ERROR_STOP=1', '--dbname', database, data=sql.encode())


def systemctl(*arguments):
    return run(['/bin/systemctl', *arguments]).stdout.decode().strip()


def properties(unit):
    names = ['InvocationID', 'ActiveState', 'SubState', 'Result', 'ExecMainStatus',
             'ExecMainStartTimestampMonotonic', 'ExecMainExitTimestampMonotonic', 'LastTriggerUSec']
    raw = systemctl('show', unit, *['--property=' + name for name in names])
    return dict(line.split('=', 1) for line in raw.splitlines() if '=' in line)


def verified_invocations():
    """Journal survives inactive oneshot unit GC; transient show properties do not."""
    raw = run(['/bin/journalctl', '-b', '-u', SERVICE, '--no-pager', '-o', 'json']).stdout.decode()
    result = []
    for line in raw.splitlines():
        row = json.loads(line)
        try:
            message = json.loads(row.get('MESSAGE', ''))
        except (ValueError, TypeError):
            continue
        if isinstance(message, dict) and message.get('status') == 'verified':
            result.append(row.get('_SYSTEMD_INVOCATION_ID'))
    return result


def poll(predicate, seconds, code):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(0.2)
    raise RuntimeError(code)


def install_units():
    """Keep production hardening; substitute only qualification locations/transport."""
    unit = (ROOT / 'production.service').read_text()
    replacements = {
        'ExecStart=/usr/bin/python3 -I -B': 'ExecStart=/usr/local/bin/python3.13 -I -B',
        '/etc/jeongeum-operational-backup/config.json': '/qa/config.json',
        '/opt/jeongeum/operational-backup/operational-backup.py': '/qa/scripts/operational-backup.py',
        'StateDirectory=jeongeum-operational-backup': 'StateDirectory=jeongeum-catchup',
        'ReadWritePaths=/var/lib/jeongeum-operational-backup': 'ReadWritePaths=/qa/state /qa/cloud /qa/docker-events',
    }
    for old, new in replacements.items():
        need(old in unit, 'PRODUCTION_SERVICE_SHAPE_CHANGED')
        unit = unit.replace(old, new)
    write('/etc/systemd/system/' + SERVICE, unit, 0o644)
    timer = (ROOT / 'production.timer').read_text()
    need('OnCalendar=hourly' in timer and 'Persistent=true' in timer, 'PRODUCTION_TIMER_SHAPE_CHANGED')
    timer = timer.replace('Unit=jeongeum-operational-backup.service', 'Unit=' + SERVICE)
    write('/etc/systemd/system/' + TIMER, timer, 0o644)
    # A second dedicated missed stamp bounds test duration without waiting for an
    # hour. The first timer retains the production accuracy/random delay exactly.
    write('/etc/systemd/system/' + TIMEOUT_TIMER,
          timer.replace('AccuracySec=30s', 'AccuracySec=1us').replace('RandomizedDelaySec=20s', 'RandomizedDelaySec=0'), 0o644)
    write('/etc/systemd/system/docker.service',
          '[Unit]\nDescription=Qualification transport sentinel; no Docker daemon\n'
          '[Service]\nType=oneshot\nExecStart=/bin/true\nRemainAfterExit=yes\n', 0o644)
    systemctl('daemon-reload')


def stamp(timer):
    directory = Path('/var/lib/systemd/timers')
    directory.mkdir(mode=0o755, exist_ok=True)
    path = directory / ('stamp-' + timer)
    path.touch()
    past = time.time() - 7200
    os.utime(path, (past, past))  # Dedicated guest timer state only, never OS time.


def fingerprint(op, cfg):
    statement = ('BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n' + op.core().MANIFEST_SQL
                 + op.core().SEQUENCE_SQL + op.core().FLYWAY_SQL + '\nROLLBACK;\n')
    return op.sql(op.Runner(cfg), statement)


def cloud_files():
    return {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (ROOT / 'cloud').iterdir() if p.is_file()}


def wait_calendar_window(*, clock=time.time, monotonic=time.monotonic, sleep=time.sleep):
    """Wait naturally at the point of use; slow image preparation cannot stale it."""
    seconds_to_hour = 3600 - (clock() % 3600)
    need(0 < seconds_to_hour <= 3600, 'CALENDAR_CLOCK_REJECTED')
    if seconds_to_hour > 240:
        return 0.0
    started = monotonic()
    deadline = started + seconds_to_hour + 1
    while (remaining := deadline - monotonic()) > 0:
        sleep(min(30.0, remaining))
    need(3600 - (clock() % 3600) > 240, 'CALENDAR_WINDOW_NOT_READY')
    return round(monotonic() - started, 3)


def guest_main():
    global STAGE
    os.umask(0o077)
    check('real_systemd_pid_1', Path('/proc/1/comm').read_text().strip() == 'systemd')
    check('guest_has_no_network_interface', set(p.name for p in Path('/sys/class/net').iterdir()) <= {'lo'})
    STAGE = 'prepare-real-postgresql-and-qualification-config'
    os.makedirs(DATA, mode=0o700, exist_ok=True)
    os.chown(DATA, 999, 999)
    os.makedirs('/run/postgresql', mode=0o755, exist_ok=True)
    os.chown('/run/postgresql', 999, 999)
    pg('initdb', '-D', DATA, '-A', 'trust', '--no-locale', '--encoding=UTF8')
    with Path(DATA + '/postgresql.conf').open('a') as f:
        f.write("\nlisten_addresses=''\nunix_socket_directories='/run/postgresql'\n")
    start_pg()
    binding = json.loads((ROOT / 'binding.json').read_text())
    seed(binding['database'])
    fixture = load_fixture()
    # Reuse the strict existing self-target/versioned-store transports, adapting
    # only the socket path to this guest's Unix-socket-only PostgreSQL.
    fixture.DOCKER_SHIM = fixture.DOCKER_SHIM.replace("'127.0.0.1'", "'/run/postgresql'")
    op, cfg = fixture.setup(binding)
    write('/qa/docker-events', '')
    before_manifest = fingerprint(op, cfg)
    stop_pg()
    install_units()
    STAGE = 'wait-for-natural-calendar-test-window'
    # This check belongs after rootfs preparation, boot and fixture setup.
    # Only a natural wait (at most 241 seconds requested) changes the hour.
    calendar_wait = wait_calendar_window()
    STAGE = 'activate-production-calendar-with-missed-stamp'
    stamp(TIMER)
    activated = time.monotonic()
    systemctl('start', TIMER)
    active = poll(lambda: (p if (p := properties(SERVICE)).get('ActiveState') == 'activating' else None),
                  65, 'MISSED_HOUR_DID_NOT_START_SERVICE')
    first_id = active['InvocationID']
    poll(lambda: (ROOT / 'docker-events').stat().st_size > 0, 12, 'READINESS_NOT_OBSERVED')
    check('persistent_timer_automatically_invoked_service', bool(first_id))
    check('actual_timer_last_trigger_recorded', properties(TIMER).get('LastTriggerUSec') not in ('', 'n/a', None))
    check('waiting_before_postgresql_start', not (ROOT / 'state/last-success.json').exists()
          and not (ROOT / 'cloud/events.jsonl').exists())
    time.sleep(3)
    pg_start_at = dt.datetime.now(dt.timezone.utc).replace(microsecond=0)
    start_pg()
    first = poll(lambda: (p if (p := properties(SERVICE)).get('ActiveState') in ('inactive', 'failed') else None),
                 70, 'DELAYED_BACKUP_DID_NOT_FINISH')
    check('delayed_pg_catchup_backup_exit_zero', first.get('Result') == 'success' and first.get('ExecMainStatus') == '0')
    catchup_elapsed = time.monotonic() - activated
    check('catchup_completed_within_bounded_window', catchup_elapsed < 140)
    state_path = ROOT / 'state/last-success.json'
    success = state_path.read_bytes()
    state = json.loads(success)
    check('snapshot_after_real_pg_delay', dt.datetime.fromisoformat(state['snapshotStartedAtUtc'].replace('Z', '+00:00')) >= pg_start_at)
    check('actual_snapshot_dump_age_and_exact_versions', len(list((ROOT / 'cloud').glob('*.age'))) == 2
          and state['hourly']['versionId'] != state['weekly']['versionId'])
    check('source_rows_sequences_and_flyway_preserved', fingerprint(op, cfg) == before_manifest)
    successful_invocations = verified_invocations()
    check('journal_binds_success_to_actual_timer_invocation', successful_invocations == [first_id])
    objects = cloud_files()
    STAGE = 'restart-timer-with-fresh-persistent-state'
    systemctl('stop', TIMER)
    systemctl('start', TIMER)
    # Cover the full production RandomizedDelaySec20 + AccuracySec30 window.
    time.sleep(55)
    check('timer_restart_does_not_repeat_caught_up_invocation', properties(SERVICE)['ActiveState'] == 'inactive'
          and verified_invocations() == successful_invocations and cloud_files() == objects
          and state_path.read_bytes() == success)
    systemctl('stop', TIMER)
    STAGE = 'actual-service-readiness-timeout-and-overlap-lock'
    stop_pg()
    (ROOT / 'docker-events').write_text('')
    stamp(TIMEOUT_TIMER)
    systemctl('start', TIMEOUT_TIMER)
    poll(lambda: properties(SERVICE).get('ActiveState') == 'activating', 10, 'TIMEOUT_TIMER_DID_NOT_START_SERVICE')
    poll(lambda: (ROOT / 'docker-events').stat().st_size > 0, 10, 'TIMEOUT_READINESS_NOT_OBSERVED')
    duplicate = run(['/usr/local/bin/python3.13', '-I', '-B', '/qa/scripts/operational-backup.py',
                     'backup', '--config', '/qa/config.json'])
    check('overlap_backup_uses_existing_lock', json.loads(duplicate.stdout)['status'] == 'locked')
    timeout_started = time.monotonic()
    failed = poll(lambda: (p if (p := properties(SERVICE)).get('ActiveState') == 'failed' else None),
                  135, 'READINESS_TIMEOUT_NOT_BOUNDED')
    check('actual_service_readiness_timeout_exit_one', failed.get('Result') == 'exit-code' and failed.get('ExecMainStatus') == '1')
    journal = run(['/bin/journalctl', '-b', '-u', SERVICE, '--no-pager', '-o', 'cat']).stdout.decode()
    check('timeout_reports_specific_readiness_error', 'OPERATIONAL_SOURCE_READY_TIMEOUT' in journal)
    check('timeout_keeps_previous_verified_success_and_objects', state_path.read_bytes() == success and cloud_files() == objects)
    timeout_observed = time.monotonic() - timeout_started
    check('timeout_waits_real_120_second_budget', 100 <= timeout_observed < 135)
    systemctl('stop', TIMEOUT_TIMER)
    start_pg()
    check('source_preserved_after_timeout_restart', fingerprint(op, cfg) == before_manifest)
    stop_pg()
    return {'passed': True, 'checks': CHECKS, 'checkCount': len(CHECKS),
            'systemdVersion': run(['/bin/systemctl', '--version']).stdout.decode().splitlines()[0],
            'pythonVersion': sys.version.split()[0],
            'calendarWaitSeconds': calendar_wait,
            'catchupElapsedSeconds': round(catchup_elapsed, 3),
            'timeoutObservedSeconds': round(timeout_observed, 3),
            'kernelVersion': os.uname().release, 'firstInvocationIdObserved': bool(first_id),
            'timerSchedule': 'hourly/Persistent=true; first timer keeps production AccuracySec=30s/RandomizedDelaySec=20s',
            'timeoutTimerSubstitution': 'Dedicated second timer uses AccuracySec=1us/RandomizedDelaySec=0; same service/default 120s readiness',
            'serviceSubstitutions': ['qualification config/script/state/write paths', 'official digest-pinned Python3.13 binary path (production requires >=3.12)', 'docker.service transport sentinel, not a Docker daemon'],
            'transportScope': 'strict self-target Docker shim and local versioned object store; actual PG17 snapshot/dump and age',
            'fixtureSchema': '39 tables/29 sequences/manual successful V1-V12 history; not application migration execution',
            'actualAwsAccessed': False, 'developmentDatabaseAccessed': False,
            'actualRecoveryIdentityUsed': False, 'hostClockChanged': False}


def prepare_guest():
    root = Path('/vmwork/root')
    root.mkdir(mode=0o755)
    # Copy only this freshly built, synthetic container's filesystem. No host
    # filesystem, device, cgroup, Docker socket or credential is mounted.
    for name in ('usr', 'etc', 'var', 'opt', 'root', 'home'):
        run(['/bin/cp', '-a', '/' + name, str(root / name)], timeout=90)
    for name in ('bin', 'sbin', 'lib', 'lib64'):
        source = Path('/' + name)
        if source.is_symlink():
            (root / name).symlink_to(os.readlink(source))
        elif source.exists():
            run(['/bin/cp', '-a', str(source), str(root / name)], timeout=90)
    for name in ('proc', 'sys', 'dev', 'run', 'tmp'):
        (root / name).mkdir(mode=0o1777 if name == 'tmp' else 0o755)
    # Copy public scripts, official public age tools and synthetic binding only.
    shutil.copytree('/qa-payload', root / 'qa')
    os.chmod(root / 'qa', 0o700)
    for item in (root / 'qa').rglob('*'):
        if item.is_dir():
            item.chmod(0o700)
        elif item.is_file():
            item.chmod(0o555 if item.name in ('age', 'age-keygen') else 0o600)
    write(root / 'etc/machine-id', '', 0o444)
    # Do not boot distro PostgreSQL units; the guest orchestrator owns one child.
    for unit in ('postgresql.service', 'systemd-timesyncd.service', 'apt-daily.timer', 'apt-daily-upgrade.timer'):
        item = root / 'etc/systemd/system' / unit
        if item.exists() or item.is_symlink():
            item.unlink()
        item.symlink_to('/dev/null')
    write(root / 'etc/systemd/system/jeongeum-catchup-probe.target',
          '[Unit]\nDescription=Offline synthetic catch-up QA\nRequires=multi-user.target jeongeum-catchup-probe.service\nAfter=multi-user.target\n', 0o644)
    write(root / 'etc/systemd/system/jeongeum-catchup-probe.service',
          '[Unit]\nDescription=Synthetic systemd/PG orchestration\nAfter=multi-user.target\n'
          '[Service]\nType=oneshot\nExecStart=/usr/local/bin/python3.13 -I -B /opt/operational-catchup-qa.py --guest\n'
          'TimeoutStartSec=600\nStandardOutput=journal+console\nStandardError=journal+console\n', 0o644)
    disk = Path('/vmwork/guest.ext4')
    with disk.open('xb') as f:
        f.truncate(2 * 1024**3)
    run(['/usr/sbin/mke2fs', '-q', '-t', 'ext4', '-F', '-d', str(root), str(disk)], timeout=120)
    return disk


def host_main():
    os.umask(0o077)
    disk = prepare_guest()
    kernel = sorted(Path('/boot').glob('vmlinuz-*'))[-1]
    initrd = Path('/boot') / kernel.name.replace('vmlinuz-', 'initrd.img-')
    need(initrd.is_file(), 'OFFICIAL_KERNEL_INITRD_MISSING')
    command = ['/usr/bin/qemu-system-x86_64', '-accel', 'tcg,thread=single', '-smp', '1', '-m', '512',
               '-kernel', str(kernel), '-initrd', str(initrd), '-append',
               'root=/dev/vda rw console=ttyS0 systemd.unit=jeongeum-catchup-probe.target panic=-1',
               '-drive', 'file=' + str(disk) + ',format=raw,if=virtio', '-nic', 'none',
               '-nographic', '-monitor', 'none', '-no-reboot']
    serial_path = Path('/vmwork/serial.private.log')
    with serial_path.open('wb') as serial:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=serial, stderr=subprocess.STDOUT)
        try:
            process.wait(timeout=660)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
            raise RuntimeError('GUEST_EXCEEDED_660_SECOND_BOUND') from None
    matches = re.findall(RESULT_MARKER + r'([A-Za-z0-9+/=]+)', serial_path.read_text(errors='replace'))
    need(len(matches) == 1, 'GUEST_RESULT_NOT_OBSERVED')
    report = json.loads(base64.b64decode(matches[0]))
    report['qemuVersion'] = run(['/usr/bin/qemu-system-x86_64', '--version']).stdout.decode().splitlines()[0]
    print(json.dumps(report), flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    if sys.argv[1:] == ['--guest']:
        try:
            result = guest_main()
        except BaseException as error:
            (ROOT / 'failure.private.log').write_text(traceback.format_exc())
            journal = run(['/bin/journalctl', '-b', '-u', SERVICE, '--no-pager', '-o', 'cat'], allow_failure=True)
            (ROOT / 'backup-unit.private.log').write_bytes(journal.stdout)
            result = {'passed': False, 'failureStage': STAGE, 'failureCode': str(error),
                      'exceptionType': type(error).__name__, 'completedChecks': CHECKS,
                      'serviceProperties': properties(SERVICE),
                      'backupErrorCodes': sorted(set(re.findall(r'(?:OPERATIONAL|BACKUP)_[A-Z0-9_]+', journal.stdout.decode(errors='replace'))))}
        payload = RESULT_MARKER + base64.b64encode(json.dumps(result).encode()).decode() + '\n'
        with open('/dev/ttyS0', 'w') as console:
            console.write(payload)
            console.flush()
        run(['/bin/systemctl', 'poweroff', '--no-block'], allow_failure=True)
        sys.exit(0 if result['passed'] else 1)
    elif sys.argv[1:] == ['--host']:
        try:
            sys.exit(host_main())
        except Exception as error:
            print(json.dumps({'passed': False, 'failureStage': 'outer-guest-boot',
                              'failureCode': str(error), 'exceptionType': type(error).__name__}), flush=True)
            sys.exit(1)
    else:
        raise SystemExit('ARGUMENTS_REJECTED')
