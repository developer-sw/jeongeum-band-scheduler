#!/usr/bin/env python3
"""Qualification fixture only; runs inside a new isolated PostgreSQL container.

The Docker transport is a strict self-container shim and AWS is a local versioned
object-store simulator. Real PostgreSQL tools, snapshots, locks and age are used.
"""
import base64
import datetime as dt
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sched
import subprocess
import sys
import time

ROOT=Path('/qa')
CHECKS=[]
STAGE='read-binding'
LAST_PROCESS={}

def need(value,code):
    if not value:raise RuntimeError(code)
    CHECKS.append(code)

def write(path,data):
    path=Path(path)
    with path.open('x',encoding='utf-8') as f:json.dump(data,f)
    path.chmod(0o600)

def load(name):
    spec=importlib.util.spec_from_file_location(name.replace('-','_'),ROOT/'scripts'/f'{name}.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def run(command,data=None):
    global LAST_PROCESS
    LAST_PROCESS={'program':Path(command[0]).name}
    p=subprocess.run(command,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=60,check=False)
    LAST_PROCESS['returnCode']=p.returncode
    if p.returncode:
        path=ROOT/'qualification-subprocess-stderr.private.log'
        path.write_bytes(p.stderr);path.chmod(0o600)
    need(p.returncode==0,'qualification_process_success');return p.stdout

DOCKER_SHIM=r'''#!/usr/local/bin/python3.13
import json,os,sys,pathlib
try:
 a=sys.argv[1:]; b=json.loads(pathlib.Path('/qa/binding.json').read_text())
 if a and a[0]=='--host=unix:///var/run/docker.sock':a=a[1:]
 if a==['inspect',b['container_id']]:
  pathlib.Path('/qa/docker-events').open('a').write('inspect\n');print(json.dumps([b['inspect']]));sys.exit(0)
 if not a or a.pop(0)!='exec':raise ValueError()
 if a and a[0]=='-i':a.pop(0)
 if a[:2]!=['--user','postgres']:raise ValueError()
 a=a[2:]
 if not a or a.pop(0)!=b['container_id'] or not a or a[0] not in ['psql','pg_dump','pg_restore']:raise ValueError()
 command=a[0]
 pathlib.Path('/qa/docker-events').open('a').write(command+'\n')
 if command=='pg_dump' and pathlib.Path('/qa/fail-dump').exists():sys.exit(42)
 os.execv('/usr/local/bin/gosu',['gosu','postgres','/usr/lib/postgresql/17/bin/'+command,*a[1:]])
except Exception:sys.exit(99)
'''

AWS_SHIM=r'''#!/usr/local/bin/python3.13
import hashlib,json,pathlib,re,shutil,sys,uuid
try:
 a=sys.argv[1:]
 if a[:3]!=['--region','ap-northeast-2','--no-cli-pager']:raise ValueError()
 a=a[3:];service,action=a[:2];a=a[2:]
 def option(k):return a[a.index(k)+1]
 root=pathlib.Path('/qa/cloud');cfg=json.loads(pathlib.Path('/qa/config.json').read_text())
 with (root/'events.jsonl').open('a') as f:f.write(json.dumps([service,action])+'\n')
 if (service,action)==('sts','get-caller-identity'):print(json.dumps({'Account':cfg['expected_account']}));sys.exit(0)
 if (service,action)==('cloudwatch','put-metric-data'):
  if option('--namespace')!='JEONGEUM/OperationalBackups':raise ValueError()
  source=pathlib.Path(option('--metric-data').removeprefix('file://'));payload=json.loads(source.read_text())
  (root/'metric.json').write_text(json.dumps(payload));print('{}');sys.exit(0)
 if service!='s3api' or action not in ['put-object','head-object','get-object']:raise ValueError()
 bucket,key=option('--bucket'),option('--key')
 if bucket!=cfg['bucket'] or option('--expected-bucket-owner')!=cfg['expected_account'] or not re.fullmatch(r'qualification/(hourly|weekly)/[0-9/]+/[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}\.tar\.gz\.age',key):raise ValueError()
 record=root/(hashlib.sha256((bucket+'/'+key).encode()).hexdigest()+'.json')
 if action=='put-object':
  failures=pathlib.Path('/qa/fail-put-count')
  if failures.exists() and int(failures.read_text())>0:failures.write_text(str(int(failures.read_text())-1));sys.exit(42)
  if record.exists() or option('--if-none-match')!='*' or option('--server-side-encryption')!='AES256':raise ValueError()
  src=pathlib.Path(option('--body'));raw=src.read_bytes();sha=hashlib.sha256(raw).hexdigest()
  if option('--metadata')!='sha256='+sha:raise ValueError()
  version=uuid.uuid4().hex;body=root/(version+'.age');body.write_bytes(raw);body.chmod(0o600)
  meta={'VersionId':version,'ContentLength':len(raw),'Metadata':{'sha256':sha},'ServerSideEncryption':'AES256','body':str(body),'key':key,'bucket':bucket}
  record.write_text(json.dumps(meta));record.chmod(0o600);print(json.dumps({'VersionId':version}));sys.exit(0)
 meta=json.loads(record.read_text())
 if option('--version-id')!=meta['VersionId']:raise ValueError()
 if action=='head-object':print(json.dumps({k:v for k,v in meta.items() if k not in ['body','key','bucket']}));sys.exit(0)
 index=a.index('--version-id')+2;destination=pathlib.Path(a[index])
 if not destination.is_absolute() or '..' in destination.parts or not destination.is_relative_to('/qa') or destination.exists():raise ValueError()
 destination.write_bytes(pathlib.Path(meta['body']).read_bytes());destination.chmod(0o600)
 print(json.dumps({'VersionId':meta['VersionId']}))
except Exception:sys.exit(99)
'''

def setup(binding):
    global STAGE
    STAGE='setup-directories'
    for directory in [ROOT/'bin',ROOT/'state',ROOT/'cloud']:
        directory.mkdir(mode=0o700,exist_ok=True);directory.chmod(0o700)
    STAGE='write-self-container-and-cloud-shims'
    for name,value in [('docker',DOCKER_SHIM),('aws',AWS_SHIM)]:
        path=ROOT/'bin'/name;path.write_text(value);path.chmod(0o555)
    STAGE='write-synthetic-credentials'
    credentials=ROOT/'credentials';credentials.write_text('[backup]\naws_access_key_id=QUALIFICATION_ONLY\naws_secret_access_key=QUALIFICATION_ONLY\n');credentials.chmod(0o600)
    STAGE='protect-public-script-copies'
    for f in (ROOT/'scripts').iterdir():f.chmod(0o600)
    STAGE='import-operational-backup'
    op=load('operational-backup')
    if binding['kind']=='source':
        STAGE='generate-synthetic-age-identity'
        run([str(ROOT/'bin/age-keygen'),'-o',str(ROOT/'identity.txt')]);(ROOT/'identity.txt').chmod(0o600)
        STAGE='derive-synthetic-age-recipient'
        recipient=run([str(ROOT/'bin/age-keygen'),'-y',str(ROOT/'identity.txt')]).decode().strip()
        cfg={k:binding[k] for k in ['project','container_id','data_volume','database','application_owner','postgres_image']}
        cfg.update(schema_version=2,mode='qualification',activation_approved=False,database_user='postgres',release_images=binding['images'],source_sha=binding['source_sha'],recipient=recipient,
                   bucket='jeongeum-qualification-'+binding['suffix'],expected_account='111111111111',region='ap-northeast-2',prefix='qualification/',aws_credentials_file='/qa/credentials',state_directory='/qa/state',docker_binary='/qa/bin/docker',age_binary='/qa/bin/age',aws_binary='/qa/bin/aws',maximum_ciphertext_bytes=128*1024*1024)
    else:
        STAGE='read-qualified-source-envelope'
        envelope=json.loads((ROOT/'source-envelope.json').read_text())
        cfg={k:binding[k] for k in ['project','container_id','data_volume','database','application_owner','postgres_image']}
        cfg.update(schema_version=2,mode='qualification-restore',database_user='postgres',docker_binary='/qa/bin/docker',state_directory='/qa/state',expected_source=envelope['expected_source'])
    STAGE='write-protected-qualification-config'
    write(ROOT/'config.json',cfg)
    return op,cfg

def source(op,cfg):
    global STAGE
    STAGE='load-source-config'
    cfg=op.load_config('/qa/config.json')
    need(cfg['mode']=='qualification','qualified_source_configuration')
    STAGE='periodic-source-backup'
    callbacks=[];scheduler=sched.scheduler(time.monotonic,time.sleep)
    def periodic():callbacks.append(op.backup(cfg))
    scheduler.enter(0.05,1,periodic);scheduler.enter(0.2,1,periodic);scheduler.run()
    need(len(callbacks)==2 and all(r['status']=='verified' for r in callbacks),'bounded_periodic_backup_callbacks')
    need(callbacks[0]['weeklyCreated'] is True and callbacks[1]['weeklyCreated'] is False,'weekly_object_reused_for_same_week')
    state=op.load_success(cfg);need(state['schemaVersion']==2,'versioned_success_state')
    event=lambda:[json.loads(line) for line in (ROOT/'cloud/events.jsonl').read_text().splitlines()]
    need(sum(e==['s3api','put-object'] for e in event())==3,'two_hourly_one_weekly_verified_uploads')
    STAGE='transient-upload-retry'
    (ROOT/'fail-put-count').write_text('1')
    transient=op.backup(cfg)
    need(transient['status']=='verified' and transient['attempts']==2,'transient_upload_failure_retried_once')
    STAGE='permanent-upload-failure'
    before=(ROOT/'state/last-success.json').read_bytes();calls=sum(e==['s3api','put-object'] for e in event())
    (ROOT/'fail-put-count').write_text('10')
    try:op.backup(cfg)
    except Exception:pass
    else:raise RuntimeError('permanent_failure_accepted')
    need((ROOT/'state/last-success.json').read_bytes()==before,'failed_backup_preserves_last_success')
    need(sum(e==['s3api','put-object'] for e in event())-calls==2,'permanent_failure_retry_bounded_to_two_attempts')
    (ROOT/'fail-put-count').unlink()
    need(not list((ROOT/'state/temporary').iterdir()),'temporary_plaintext_removed_after_success_and_failure')
    STAGE='snapshot-age-and-invalid-success-monitoring'
    state=op.load_success(cfg)
    when=dt.datetime.fromisoformat(state['snapshotStartedAtUtc'].replace('Z','+00:00'))
    docker_events=(ROOT/'docker-events').read_bytes()
    healthy=op.metric(cfg,now=op.core().utc_now());need(healthy['stale'] is False,'fresh_snapshot_metric')
    aged=op.metric(cfg,now=when+dt.timedelta(minutes=100));need(aged['stale'] is True,'old_snapshot_metric_stale_without_os_clock_change')
    metric=json.loads((ROOT/'cloud/metric.json').read_text())[0]
    need(metric['MetricName']=='RecoverableSnapshotAgeMinutes' and metric['Value']>=100,'metric_measures_snapshot_age')
    success=ROOT/'state/last-success.json'
    for kind in ['missing','corrupt','future']:
        if kind=='missing':success.unlink()
        elif kind=='corrupt':success.write_text('{broken')
        else:
            modified=json.loads(before);modified['verifiedAtUtc']=modified['snapshotStartedAtUtc']='2099-01-01T00:00:00Z';success.write_text(json.dumps(modified))
        need(op.metric(cfg)['stale'] is True,'invalid_success_'+kind+'_fails_closed')
        success.write_bytes(before);success.chmod(0o600)
    need((ROOT/'docker-events').read_bytes()==docker_events,'monitoring_never_queries_docker_or_database')
    STAGE='overlap-lock'
    with op.core().locked(ROOT/'state/backup.lock'):
        need(op.backup(cfg)['status']=='locked','overlapping_backup_lock_skips_safely')
    STAGE='exact-version-download'
    saved=op.load_success(cfg);record=saved['hourly'];runner=op.Runner(cfg)
    common=['--bucket',record['bucket'],'--key',record['key'],'--expected-bucket-owner',cfg['expected_account']]
    try:runner.aws('s3api','get-object',*common,'--version-id','wrong-version','/qa/wrong-version.age','--output','json')
    except Exception:need(not (ROOT/'wrong-version.age').exists(),'wrong_remote_version_rejected')
    else:raise RuntimeError('wrong_version_accepted')
    fetched=json.loads(runner.aws('s3api','get-object',*common,'--version-id',record['versionId'],'/qa/export.age','--output','json'))
    raw=(ROOT/'export.age').read_bytes()
    need(fetched['VersionId']==record['versionId'] and len(raw)==record['bytes'] and hashlib.sha256(raw).hexdigest()==record['sha256'],'exact_version_download_sha_verified')
    expected={'mode':cfg['mode'],'sourceProject':cfg['project'],'sourceDatabase':cfg['database'],'applicationOwner':cfg['application_owner'],'sourceSha':cfg['source_sha'],'images':cfg['release_images']}
    write(ROOT/'source-envelope.json',{'expected_source':expected,'cipherSha256':record['sha256'],'cipherBytes':record['bytes'],'versionId':record['versionId']})

def target(op,cfg):
    global STAGE
    STAGE='load-target-config'
    restore=load('operational-restore');cfg=restore.load_config('/qa/config.json');runner=restore.Runner(cfg)
    cipher=ROOT/'incoming.age';identity=ROOT/'identity.txt';age=ROOT/'bin/age'
    raw=cipher.read_bytes();envelope=json.loads((ROOT/'source-envelope.json').read_text())
    need(len(raw)==envelope['cipherBytes'] and hashlib.sha256(raw).hexdigest()==envelope['cipherSha256'],'independent_engine_received_verified_cipher')
    STAGE='tampered-cipher-rejection'
    bad=ROOT/'tampered.age';mutated=bytearray(raw);mutated[-1]^=1;bad.write_bytes(mutated);bad.chmod(0o600)
    before=(ROOT/'docker-events').read_bytes() if (ROOT/'docker-events').exists() else b''
    try:restore.restore(cfg,bad,identity,age,runner)
    except Exception:pass
    else:raise RuntimeError('tampered_cipher_accepted')
    need(((ROOT/'docker-events').read_bytes() if (ROOT/'docker-events').exists() else b'')==before,'tampered_cipher_rejected_before_target_query')
    STAGE='wrong-synthetic-identity-rejection'
    wrong=ROOT/'wrong-identity.txt';run([str(ROOT/'bin/age-keygen'),'-o',str(wrong)]);wrong.chmod(0o600)
    try:restore.restore(cfg,cipher,wrong,age,runner)
    except Exception:pass
    else:raise RuntimeError('wrong_key_accepted')
    need(((ROOT/'docker-events').read_bytes() if (ROOT/'docker-events').exists() else b'')==before,'wrong_fixture_key_rejected_before_target_query')
    STAGE='independent-database-restore'
    result=restore.restore(cfg,cipher,identity,age,runner)
    need(result['status']=='independent-restore-verified' and result['restoredTables']==39 and result['restoredSequences']==29,'independent_engine_rows_sequences_flyway_ownership_verified')
    STAGE='nonempty-target-rejection'
    try:restore.restore(cfg,cipher,identity,age,runner)
    except Exception as e:need(str(e)=='OPRESTORE_TARGET_NOT_EMPTY','nonempty_target_rejected_without_drop')
    else:raise RuntimeError('nonempty_target_accepted')
    restore.validate_target(cfg,runner,empty=False);need(True,'restored_database_preserved_after_rejected_overwrite')

def main():
    os.umask(0o077)
    binding=json.loads((ROOT/'binding.json').read_text());op,cfg=setup(binding)
    source(op,cfg) if binding['kind']=='source' else target(op,cfg)
    result={'passed':True,'phase':binding['kind'],'checks':CHECKS,'checkCount':len(CHECKS),'cloudTransport':'local-versioned-simulator','dockerTransport':'self-container-gosu-shim','actualAwsAccessed':False,'developmentDatabaseAccessed':False,'actualRecoveryIdentityUsed':False,'fixtureSchema':'39 tables,29 sequences,manual Flyway V1-V12 history; not an application migration execution'}
    write(ROOT/'result.json',result);print(json.dumps({k:v for k,v in result.items() if k!='checks'}))

if __name__=='__main__':
    try:main()
    except Exception as error:
        code=str(error) if re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,119}',str(error)) else 'UNEXPECTED_QUALIFICATION_FAILURE'
        kind=type(error).__name__
        if kind not in ['OSError','PermissionError','FileNotFoundError','ImportError','ModuleNotFoundError','RuntimeError','ValueError','TypeError','BackupError','RestoreError','TimeoutExpired','JSONDecodeError']:kind='UnexpectedException'
        print(json.dumps({'passed':False,'error':'OPERATIONAL_BACKUP_QUALIFICATION_FAILED','failureCode':code,'failureStage':STAGE,'exceptionType':kind,'errno':error.errno if isinstance(error,OSError) else None,'process':LAST_PROCESS,'completedChecks':len(CHECKS)}));sys.exit(1)
