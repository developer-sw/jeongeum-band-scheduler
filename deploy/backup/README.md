# 합성 DB 정기 백업 실행 자산

이 자산은 `synthetic/` 경로와 `jeongeum-prod-qa-<12자리>`의 **합성 DB만** 허용한다. 운영 DB 활성화나 실제 데이터 이전을 허용하는 설정은 구현하지 않았다. 파일이 존재하는 것과 서버에 설치·타이머 활성화·S3 복구·경보 수신을 완료한 것은 구분한다.

## 실제 재부팅 후속 결과 — 2026-10-24

source readiness helper·source unit·백업 drop-in을 실제 설치해 `systemd-analyze verify`·enabled/active를 확인하고 VM을 재부팅했다. 새 boot ID와 source journal의 `existingSourceStarted=true`, 합성 PG·공개 준비 Caddy healthy, QA 앱 3개 중지를 확인했다. 기존 컨테이너 5개·볼륨·restart 정책·원본 설정 30파일·39개 테이블·29개 시퀀스·Flyway V1~V12 전체 이력·역할/인증/권한 지문을 보존했고 비공개 부팅 후 **16항목**을 통과했다. readiness의 별도 fake 회귀는 **21개 통과**다.

부팅 후 수동 백업이나 축약 trigger 없이 원래 hourly 타이머가 **16:00:18 KST**에 실행되어 **16:00:24 KST** 새 S3 시간별 객체의 exact-version Head/Get·크기/SHA 검증을 완료했다. 같은 주 주간본은 유지됐다. 현재 부팅 metric 서비스 success/종료 0·새 CloudWatch datapoint·원래 경보 설정 보존/최종 OK, 기본 인증서 검증 HTTPS200 준비 화면과 FREE·ACTIVE를 확인했다. 추가 3파일의 별도 age 암호문 **4,356bytes**는 원본 불변·서버/PC 전체 SHA·실제 Windows 인증 복원·disk SHA/native ACL을 통과했다. 기존 30파일 archive와 함께 쓰는 수동 복구이며 단일 33파일 archive·자동 활성화·S3 설정 업로드는 아니다. AWS Basic 계정·결제 문의는 접수했고 답변 대기다. Free Plan·유료 Support 미가입을 유지한다. [OPERATIONS_REBOOT_VALIDATION](../../docs/OPERATIONS_REBOOT_VALIDATION.md)에 실제 결과와 한계를 기록한다.

후속 설정 복구(2026-10-24): 별도 `scripts/configuration-backup.py`로 서버 준비·합성 QA 설정의 allowlist 30파일을 수동 암호화·PC 복원했다. fake 26개·통합 21개, Windows native ACL·disk SHA/크기와 격리 Linux 파일 도구의 metadata 재현을 확인했다. 실제 기술 자격증명이 포함된 비공개 설정 백업이며 이 DB 타이머가 자동으로 수집/업로드하는 기능은 아니다. helper 자체 Linux restore·실제 DB 시작·설정 활성화·S3 새 업로드·실회원 이전은 하지 않았다. 같은 날 **PC 밖 복구 개인키 직접 보관·사본 확인 완료를 사용자에게 확인**했다. 이는 에이전트의 외부 사본 직접 읽기 검증이 아니다. 제외 키·QA env 대상 차이와 사용자 확인 범위는 [설정 복구 기록](../../docs/OPERATIONS_CONFIGURATION_RECOVERY.md)을 따른다.

## 이전 정기 백업 검증 결과 — 2026-10-24

사용자 승인 후 기존 서버의 Linux에서 이 실행자산을 설치해 단발 백업·metric 서비스를 실행하고 실제 S3 Put/Head/Get의 정확한 VersionId·크기/SHA 일치를 확인했다. 중복 Put 412, 버전 삭제·보존 변경·합성 prefix 밖 업로드·다른 namespace 발행 거부도 확인했다. 오프라인 fake 회귀는 **27개 통과**다.

두 타이머 enabled/active·재시작 후 상태 보존을 확인했고, 시험용 runtime `OnActiveSec=10s`로 systemd가 새 시간별 S3 백업을 만든 것을 검증했다. 주간본은 유지됐고 시험 drop-in 삭제 후 원래 hourly 설정·활성 상태·다음 실행 시각을 복구했다. 실제 hourly 경계 대기는 하지 않았다. 합성 DB 정지 시 백업 실패와 이전 성공 보존, 동일 manifest로 재기동 후 복구도 확인했다.

격리한 빈 성공 상태의 stale ALARM과 정상 OK 복귀, 양쪽 SNS action 및 사용자 메일 수신을 확인했다. 같은 경보를 데이터 없는 시험 차원으로 잠시 전환한 미수신 평가는 약 50.35초 뒤 breaching ALARM을 확인하고 원래 설정을 모두 복원한 뒤 최종 OK·SNS action 성공을 확인했다. 실제 VM 중단·90분 대기 시험과 구분한다.

S3 암호문의 PC 독립 PG 복원은 **10개·19.477초**로 39개 테이블·29개 시퀀스·Flyway V1~V12·제한 역할/소유권·PG 재시작 후 지문 보존을 확인했다. 복원기는 PG만 실행했고 기존 개발 DB에 접속하지 않았다. QA 원본은 **rc.2 / `8b24285`**이고 새 배포용 **rc.3**와 다르다. 앞선 수동 복원 20개·약 83초, 앱/API 검증, 전체 서비스 RTO와 이 결과를 혼동하지 않는다.

이 초기 검증에서 QA Compose는 `restart: no`였고 당시 VM 재부팅 자동 복구는 미검증이었다. 후속은 원래 restart 정책을 유지하면서 별도 source unit으로 기존 PG만 자동 시작하여 위 실제 재부팅 결과를 확인했다. 자동 PC 복사·운영 비밀 설정 복구·실제 요금 상계·전체 RPO/RTO·실회원 이전/앱 공개는 후속이다. 정책·승인·상세 검증 범위는 [OPERATIONS_BACKUP_PLAN](../../docs/OPERATIONS_BACKUP_PLAN.md)을 따른다. 아래는 재설치를 위한 일반 절차이며 최초 실행 전 설명을 현재 미완료 상태로 해석하지 않는다.

## 기존 합성 PG의 부팅 준비와 점검

`scripts/backup-source-readiness.py`는 명시적으로 검증한 sibling `production-backup.py`의 설정 검증·Runner·읽기 전용 `inspect_source`를 재사용한다. Python `-I`로 실행하며 모듈은 root 소유·상위 경로 비쓰기·비링크를 확인한 뒤 불러온다. 정확한 기존 컨테이너 ID·QA 프로젝트/postgres label·pin·단일 지정 데이터 볼륨·포트 미공개를 검사하고, 정지된 대상만 한 번 시작해 healthy를 기다린다. 120초 전체/명령별 최대 10초 제한이며 비밀값·외부 명령 출력·traceback을 출력하지 않는다. 컨테이너 재생성·Compose 기동·restart policy 변경·다른 앱 시작은 없다.

설치 위치는 `/opt/jeongeum/backup/backup-source-readiness.py`, `/etc/systemd/system/jeongeum-backup-source.service`, `/etc/systemd/system/jeongeum-backup.service.d/source.conf`다. source unit은 Docker 이후 부팅에 기동하는 oneshot/`RemainAfterExit=true`이며, 백업 drop-in은 이를 `Requires`/`After`로 연결하고 매 실행의 `ExecStartPre`에서 readiness를 재확인한다. 실제 systemd 검증 후 source unit과 기존 두 타이머를 활성화한다. helper·모듈과 모든 상위 경로의 root 소유권/권한을 먼저 확인한다.

**수동으로 정지한 해당 PG도 다음 백업 전처리가 다시 시작할 수 있다.** 정지 상태를 유지하는 점검은 백업 타이머 중지 → 진행 중 백업 완료 확인 → source unit 중지 → 정확한 합성 PG 정상 정지 순서로 한다. source unit 정지만으로 DB를 정지시키지는 않는다. 점검 중 백업을 수동 시작하지 않으며 감시를 유지하면 장기 중단은 경보 대상이다. 복귀 시 source unit의 healthy를 확인하고 백업 타이머를 다시 시작해 다음 원격 성공을 확인한다. 원래 `restart: no`를 운영 전체의 자동 복구 정책으로 오해하지 않는다.

## 설치 전제

- Linux root, Python 3.12 이상 표준 라이브러리, 기존 Docker Engine, 공식 검증한 age **v1.3.2**, AWS CLI v2가 필요하다. boto3·psycopg 설치는 필요 없다.
- 실행 파일은 `/opt/jeongeum/backup/production-backup.py`, age는 `/opt/jeongeum/backup/bin/age`에 둔다. 파일/상위 디렉터리는 root 소유이며 그룹·다른 사용자에게 쓰기 권한을 주지 않는다. 공식 CLI 설치 symlink는 최종 root 소유 실행 파일까지 검증한다.
- `/etc/jeongeum-backup`·`/var/lib/jeongeum-backup`은 root 소유 `0700`으로 준비한다. config와 자격증명은 `0600`, symlink 없이 생성한다. 실제 config·자격증명·상태·덤프는 Git에 넣지 않는다.
- 예시 JSON의 모든 `REPLACE`는 검토한 합성 대상/이미지로 직접 치환해야 한다. 예시는 실행 가능한 기본 설정이 아니다. 개인키가 아닌 `age1…` 공개 수신자만 넣는다.
- credentials 파일은 `[backup]` 섹션의 `aws_access_key_id`·`aws_secret_access_key` 및 필요 시 `aws_session_token`만 허용한다. `credential_process`·다른 profile·환경변수 자격증명·endpoint override는 사용하지 않는다.

서버 IAM은 승인된 버킷의 `synthetic/hourly/*`, `synthetic/weekly/*`에 대한 `s3:PutObject`, `s3:GetObject`, **`s3:GetObjectVersion`**만 필요하다. `--expected-bucket-owner`와 STS 계정 대조로 다른 계정을 거부한다. Lifecycle·Versioning·Governance 7일·공개 차단·정책은 별도 관리자 측에서 설정/검증하며 이 실행자는 만들거나 변경하지 않는다. 삭제·잠금 우회·보존기간 변경 권한을 주지 않는다.

## 실행과 성공 기준

```sh
sudo /usr/bin/python3 -I /opt/jeongeum/backup/production-backup.py validate --config /etc/jeongeum-backup/config.json
sudo /usr/bin/python3 -I /opt/jeongeum/backup/production-backup.py backup --config /etc/jeongeum-backup/config.json
sudo /usr/bin/python3 -I /opt/jeongeum/backup/production-backup.py metric --config /etc/jeongeum-backup/config.json
```

`validate`는 로컬 설정·권한·실행 파일만 확인하며 Docker·DB·AWS 명령을 실행하지 않는다. 실제 `backup`은 컨테이너 ID, 프로젝트/서비스 label, DB 볼륨, 이미지 pin, DB 이름/role/테이블 소유권을 확인한다. 개발 DB나 임의 컨테이너를 이름으로 찾아 사용하지 않는다.

하나의 read-only exported snapshot을 pg_dump custom과 테이블 지문 manifest가 공유한다. 시퀀스는 MVCC 대상이 아니므로 dump 전후 값이 달라지면 실패하고 업로드하지 않는다. `is_called`는 SQL `CASE`로 `t/f`를 명시해 문자열 연결의 `true/false` 변환과 구분한다. 이 보수적 정책은 서비스 쓰기 중 백업 재시도가 필요할 수 있다. 39개 테이블·29개 시퀀스·성공한 Flyway V1~V12만 현재 합성 schema로 허용한다. schema 변경은 별도 검토가 필요하다.

암호화된 archive는 정확히 `database.dump`, `database.private.manifest`, `backup-manifest.json` 3개 regular file이다. manifest에는 파일 SHA/크기, 원본 QA 프로젝트·DB·소스 SHA·이미지 pins와 snapshot 시각이 들어 있다. 이전 수동 백업의 4파일 복원 계약이나 별도 시험 세션 상태 파일과 동일한 형식이 아니다.

private 임시 폴더의 dump/manifest를 tar.gz 스트림으로 age에 직접 넣고 **평문 tar는 저장하지 않는다**. SQL·행·계정 정보·CLI stdout/stderr·자격증명을 로그에 출력하지 않는다. 성공·실패 뒤 임시 폴더를 정리한다. 강제 전원 차단/SIGKILL로 남은 root-private 임시 파일은 관리자가 해당 작업 소유를 확인한 후 처리한다.

암호문은 UTC timestamp+UUID 키로 저장하고 `If-None-Match:*`를 사용한다. Put이 돌려준 정확한 VersionId로 Head와 Get을 수행하여 크기·메타 SHA·다운로드 SHA를 대조한다. 모든 필수 업로드가 검증된 뒤에만 `last-success.json`을 atomic replace한다. 실패나 중복 실행은 이전 성공 시각을 앞으로 밀지 않고 원격 객체도 삭제하지 않는다.

매번 시간별 객체를 만들며 주간 객체는 ISO 주가 바뀐 첫 성공 작업에 추가한다. 재부팅 시 상태가 보존되면 같은 주의 기존 주간본을 재사용한다. 실패/강제 종료 후 재시도는 새로운 UUID를 사용해 중복 암호문이 생길 수 있으므로 보관 개수·비용은 정확한 고정 상한이 아니다. Lifecycle는 시간별 7일·주간 28일, noncurrent 정리와 잠금 만료·처리 지연을 함께 고려한다. 주간본 전체 28일 WORM 보장은 아니다.

## 지표·타이머

5분 감시는 백업과 **서로 다른 flock**을 사용하며 DB를 조회하지 않는다. namespace `JEONGEUM/Backups`, 지표 `VerifiedBackupAgeMinutes`, 차원 `Environment=synthetic`, `Instance=jeongeum-prod-01`, **Unit=None**, 분 단위 값을 발행한다. CloudWatch에는 `Minutes` Unit이 없다. 없는/손상/다른 대상/미래 성공 시각은 0 대신 stale 값으로 보고한다. 마지막 성공 시각은 원격 암호문 검증 완료 시각이며 snapshot 시작 시각도 상태에 별도로 남긴다.

서버는 해당 namespace의 `cloudwatch:PutMetricData`만 추가로 사용한다. 경보·SNS 관리나 직접 Publish는 하지 않는다. 별도 관리자가 `Maximum`, 300초, `>90`, missing=breaching과 동일 차원을 맞추고 실제 실패/중단/복구·수신 시험 후 활성화한다. 설정만으로 정확한 90분 수신이나 전체 복구 시간을 보장하지 않는다.

제공한 service/timer는 읽기 전용 시스템·private tmp·메모리/시간 제한을 지정한다. 서버에서 `systemd-analyze verify`와 실제 합성 단발 성공/실패·중복/metric·S3 독립 복원을 확인한 뒤 관리자가 unit을 설치/활성화한다. 이 저장소 자산에는 자동 설치·자동 enable 스크립트를 넣지 않는다. `Persistent=true`의 시간 타이머는 부팅 중 놓친 실행을 한 번 수행하며 과거 시간별 백업을 만들어냈다고 주장하지 않는다.

## 오프라인 검증

```sh
python3 scripts/production-backup.test.py
```

Docker/AWS/age 프로세스는 가짜 명령으로 대체하며 실제 DB나 외부 네트워크에 접근하지 않는다. Windows 실행의 파일 권한 검사는 POSIX 메타데이터 모형이며 실제 Linux ownership/flock·systemd 제한·age 암호화·AWS IAM/삭제 거부·복원은 원격 합성 통합 검증에서 별도로 확인해야 한다.
