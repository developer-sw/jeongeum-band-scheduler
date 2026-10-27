# 운영 DB 자동 백업·독립 복원·감시

## 2026-10-27 후속 — 준비 대기 로컬 보완

운영 백업 내부의 120초 monotonic 준비 대기와 Docker/SQL별 최대 10초 제한을 추가하고, 직접 CLI도 같은 guard를 적용한다. 시간 후퇴 3개를 포함한 최종 회귀 **Windows/Linux 각각 57개**와 native PG 지연·timeout·독립 복원 **외부 4·원본 37·복원 7항목**은 통과했다. 시간 창 helper **Windows/Linux 각각 7개**와 보완 뒤 격리 guest의 실제 systemd `Persistent` **18항목도 최종 통과**했다. [준비 대기 계약·현재 검증 범위](OPERATIONAL_READINESS.md)를 따른다. 실제 운영 서버는 기존 실행자를 유지하고 새 guard를 설치하지 않았다. 아래 실제 서버 적용 기록은 이전 버전의 실행 결과이며 이번 로컬 보완과 구분한다.

## 2026-10-27 실제 적용 후속

사용자 승인 후 기존 서버의 실회원 없는 새 운영 스키마에서 실제 암호화 S3 exact-version 백업·서버 밖 PC 독립 PostgreSQL 복원·systemd service·ALARM/OK 실수신을 확인하고 운영 timer를 활성화했다. 선택 34파일의 설정 복구본도 별도로 검증했다. [실제 서버 실행 기록](OPERATIONAL_SERVER_VALIDATION.md)의 빈 원본·Windows bridge·PC 재구성 archive와 자연 timer 관측 범위를 따른다. 아래 “미실행”은 구현·로컬 합성 검증 당시의 상태이며 실제 회원 데이터 이전·공개는 여전히 수행하지 않았다.

## 2026-10-26 구현 당시 범위

2026-10-26 사용자는 **운영 DB용 자동 백업·독립 복원·감시 구현**을 요청했다. 기존 synthetic-only 백업을 운영 DB로 재지정하지 않고 새 실행자·설정·상태·unit·prefix·지표를 분리한다. 코드와 로컬 합성 검증을 완료한 뒤 사용자가 변경 검토·커밋·Push·main 대상 초안 PR·원격 CI 확인을 승인했다. 게시 후 CI 판정은 해당 초안 PR의 최신 HEAD 검사로 확인한다. 정식 전환·main 병합은 별도 승인 대상이다. 실제 운영 DB 연결, 기존 회원·팀·예약 데이터 이전, AWS 권한/저장소/경보 변경, 운영 서버 설치·systemd 활성화는 진행하지 않았다.

기존 개발 DB·Docker 볼륨·합성 S3 백업과 source unit·timer를 유지한다. 앞서 검증한 [권한 설정 25파일 복구본](RUNTIME_SETTINGS_RECOVERY.md)과 원본 16파일·각 암호문도 보존한다. 실제 운영 비밀파일·복구 개인키를 합성 시험에 사용하지 않는다. 시험은 새 합성 DB와 시험용 값으로 수행한다.

## 실행자와 활성화 경계

| 자산 | 책임 |
|---|---|
| [operational-backup.py](../scripts/operational-backup.py) | 보호된 설정 검증, 원본 식별, snapshot 공유, 암호화·exact-version 저장 검증, 성공 상태와 지표 |
| [operational-restore.py](../scripts/operational-restore.py) | 암호문 인증·archive/source 검증, 새 빈 검증 DB만 복원, 내용·소유권 대조 |
| [배포 자산](../deploy/operational-backup/README.md) | 기본 비활성 config, 별도 service/timer·IAM·경보 예시와 설치 조건 |
| [기존 합성 실행자](../scripts/production-backup.py) | 기존 synthetic-only 계약 유지. 새 실행자가 검토된 파일/프로세스/snapshot/암호화 primitive만 재사용 |

새 도구는 formatVersion/schema version 2의 명시적인 계약을 사용한다. 공통 모듈은 정확한 sibling 파일과 부모의 root 소유권·비쓰기·비링크·단일 파일 조건을 확인한 뒤 불러온다. 기존 합성 도구의 mode·prefix·DB 이름 검증을 우회하거나 완화하지 않는다.

| profile | 고정 원본·상태 경계 |
|---|---|
| `production` | `jeongeum-production-v1`·`jeongeum_prod`·`jeongeum_app`, 정확한 PG ID/전용 볼륨, `production/`, 별도 운영 state 디렉터리 |
| `qualification` | `jeongeum-ops-qa-<12hex>`·`opsqa_<suffix>`, 정확한 시험 PG ID/볼륨·추가 QA label, `qualification/`, 운영과 다른 state 디렉터리 |
| `qualification-restore` | 원본과 다른 새 검증용 PostgreSQL·새 빈 DB·볼륨, 예상 source와 일치한 archive만 복원. 운영 target은 허용하지 않음 |

예시 production config는 `activation_approved:false`와 placeholder를 유지한다. `validate`는 로컬 설정만 검사하며 미승인 production의 `backup`과 `metric`은 외부 호출 전에 거부한다. 이 boolean은 보호된 관리 설정이며 사용자 승인 자체를 자동 증명하지 않는다. 실제 대상·권한·정기 작업 활성화는 이후 별도 승인과 검증이 필요하다.

백업은 source의 컨테이너 ID·Compose project/service·고정 PostgreSQL 이미지·정확한 단일 PG 데이터 볼륨·미공개 포트·DB/역할/소유권·health를 확인한다. 예상한 읽기 전용 secret/init bind 이외의 mount를 거부하며 qualification의 `/qa` tmpfs만 시험 예외로 허용한다. 호출자가 주입한 Runner도 config 전체와 일치해야 한다.

**DB 자동 시작·재생성·Compose 기동은 없다.** 기존 synthetic readiness/source unit도 호출하지 않는다. 새 로컬 보완의 `backup`은 백업 lock 안에서 이전 성공 기록을 검증한 뒤 최대 120초 monotonic deadline으로 정확한 기존 PG를 기다린다. 매 poll은 target/ID·이미지·label·포트·mount·볼륨을 health보다 먼저 검사한다. 정확한 `created`·`exited`와 `running`의 `starting`·`unhealthy`는 관찰하되 paused·dead·restarting·대상 불일치·알 수 없는 상태/health는 거부한다. healthy 뒤 읽기 전용 DB/역할/소유권 guard를 같은 예산 안에서 마쳐야 AWS 계정 검사와 capture로 진행한다. capture에서도 원본을 재검사한다.

개별 Docker/SQL은 10초와 남은 전체 시간 중 짧은 제한을 사용하며 최종 SQL까지 같은 deadline을 따른다. timeout child group 정리의 최대 5초 grace는 별도다. 직접 `backup`도 guard를 우회하지 않으며 `ready --wait-seconds 1..120`은 별도 진단에만 허용한다. 설정 schema 2·unit 전체 20분 제한은 유지하고 `ExecStartPre`를 추가하지 않는다. 실패하면 이전 성공·주간 기록·객체는 유지하며, 별도 감시 lock과 DB/Docker 무조회 계약도 유지한다. 실제 운영 서버의 이전 버전에는 이 새 대기를 아직 적용하지 않았다.

## 백업과 저장 계약

하나의 exported read-only MVCC snapshot을 테이블 지문과 `pg_dump`가 공유한다. 준비 완료 후 capture 직전의 시각을 snapshot 시작 기준으로 사용하며 진입·준비 완료·capture 사이의 UTC 후퇴나 이전 성공보다 앞선 시각을 거부한다. 현재 schema는 **39테이블·29시퀀스·성공한 Flyway V1~V12**로 고정한다. migration을 추가하기 전 백업/복원 schema 계약도 검토해야 하며 자동 schema 확대는 하지 않는다.

시퀀스는 MVCC snapshot 대상이 아니므로 dump 전후 값과 `is_called`가 바뀌면 실패한다. 허용한 일시 명령 실패·snapshot timeout·시퀀스 변경만 최대 **2회** 시도하며 재시도마다 새 snapshot과 object UUID를 사용한다. 권한·식별·schema·version/해시 불일치 등 규약 실패는 재시도하지 않는다. 쓰기가 계속되는 동안 항상 성공하는 백업으로 표현하지 않으며 실패 시 이전 성공 시각을 유지한다.

archive에는 `database.dump`, `database.private.manifest`, `backup-manifest.json`의 정확한 3개 regular file만 담는다. 새 manifest는 `kind=operational-database`와 mode·source project/DB/앱 역할·snapshot 시작시각·소스 SHA·이미지 pins·파일 크기/SHA를 기록한다. 표 지문·계정 데이터·설정/자격증명은 공개 로그에 출력하지 않는다.

age **v1.3.2**에는 공개 수신자만 전달한다. tar.gz를 암호화 스트림으로 보내 평문 tar를 저장하지 않으며 private 임시 dump와 지문은 작업 후 정리한다. 복구 개인키는 백업 서버에 두지 않는다.

S3 Put은 새 UTC timestamp/UUID 키와 `If-None-Match:*`를 사용한다. 반환된 정확한 VersionId의 Head/Get에서 버전·크기·메타 SHA·다운로드 SHA·암호화를 대조한 뒤에만 `last-success.json`을 원자적으로 갱신한다. 시간별 객체는 매 성공 작업에 만들고 주간 객체는 ISO 주의 첫 성공 시 추가한다. 실패나 중복 lock은 성공 기록을 앞으로 밀지 않으며 원격 객체 삭제도 하지 않는다. 부분 업로드 후 실패하면 추가 암호문이 남을 수 있다.

상태는 mode·원본·볼륨·앱 역할·이미지/소스·공개 수신자·버킷/prefix/계정에 바인딩한다. 원본이나 수신자가 바뀌면 이전 주간본과 성공 상태를 조용히 재사용하지 않는다.

## 자동 실행과 감시

별도 unit은 시간별 백업과 5분 감시를 정의한다. 기존 synthetic 서비스·timer·state와 연결하거나 덮어쓰지 않는다. unit을 작성한 것과 실제 서버에 설치·enable한 것은 구분한다.

| 항목 | 계약 |
|---|---|
| Namespace | `JEONGEUM/OperationalBackups` |
| Metric | `RecoverableSnapshotAgeMinutes` |
| Dimensions | `Environment=<mode>`, `Instance=jeongeum-prod-01` |
| 시간 기준 | 검증 완료 시각이 아닌 **성공한 snapshot 시작시각**부터의 분 |
| 손상/부재/다른 대상/미래/역전된 시각 | fresh 0으로 처리하지 않고 stale 값 |
| 감시의 DB 의존성 | DB/Docker 조회 없이 보호된 성공 기록을 사용. 백업과 별도 lock |
| 경보 예시 | `Maximum`, 300초, `>90`, missing=breaching, ALARM/OK action |

느린 업로드의 완료시각만 보고 오래된 DB snapshot을 fresh로 보이지 않게 한다. 경보·SNS topic/구독·이메일 수신은 실제 대상에서 별도 연결·시험해야 한다. 로컬 cloud simulator는 명령·버전·지표 계약의 검증이며 실제 AWS/SNS 발송 성공이나 정확한 알림 전달 시간을 증명하지 않는다.

## 독립 복원과 크기 한계

복원은 정확한 원격 version·크기/SHA로 확보한 암호문을 사용한다. age 인증, gzip/tar 경계·CRC, 정확한 3파일·SHA·source 대조가 끝나기 전에는 target DB에 접근하지 않는다. target은 production을 허용하지 않으며 새 빈 검증용 PostgreSQL 프로세스와 데이터 볼륨으로 고정한다. source와 같은 컨테이너/볼륨을 대상에 지정하지 않고 `NetworkMode=none`·호스트 포트 없음·전용 label·최소 권한 앱 역할을 검사한다.

빈 DB는 테이블·시퀀스 수뿐 아니라 뷰·함수·타입 등 public 객체, 추가 스키마·확장·이벤트 트리거와 DB 수준 사용자 객체가 없는지도 읽기 전용으로 검사한다. 빈 DB에 `--single-transaction --no-owner --no-acl --role`로 복원한 뒤 테이블 지문·시퀀스·Flyway·소유권을 대조한다. `DROP`, `--clean`, 임의의 기존 데이터 덮어쓰기와 자동 target 삭제는 없다. 복원 또는 사후 검사가 실패해도 대상은 조사용으로 보존한다. 복원 후 앱·worker·Push는 시작하지 않는다.

이때 독립성은 **별도의 PostgreSQL 인스턴스·새 데이터 볼륨**이다. 같은 PC/Docker daemon에서 검증한 것을 다른 물리 서버·별도 리전의 재난 복구 성공으로 확대하지 않는다.

초기 크기 상한은 암호문과 dump 각각 **128MiB**, 복원 tar **256MiB**다. dump 크기는 명령 종료 후 검사하며 systemd의 파일 크기·메모리·시간 제한은 별도 보호다. 큰 DB·직접 CLI 실행의 임시 디스크 소비·실서비스 부하는 추가 검증이 필요하다. DB 암호문은 역할 자격증명·Caddy/TLS·앱 설정 복구본을 대신하지 않으며 전체 서비스 RPO/RTO 달성을 주장하지 않는다.

## 검증 상태

2026-10-26 최종 실행 결과다. 상세 증빙은 Git에서 제외한 로컬 비공개 결과 파일에 보관한다.

| 검증 | 현재 결과 |
|---|---|
| 백업 fake 회귀 | **Windows·Linux 각각 36개 통과·skip 0**. 설정/profile·활성화·원본/port/mount·version/hash·snapshot/재시도·성공 보존·감시·Runner 주입 검사 |
| 복원 fake 회귀 | **Windows·Linux 각각 28개 통과·skip 0**. 인증/경계/source/target·새 빈 DB·사용자 객체 존재·복원/사후 대조의 실패 경계 검사 |
| 기존 합성 백업 회귀 | **Linux 27개 통과·skip 0**. 기존 synthetic-only 실행자 소스는 수정하지 않음 |
| 실제 합성 PostgreSQL·age·독립 target·주기 호출·감시 통합 | **외부 4항목·원본 21항목·복원 7항목 통과**. 새 두 PostgreSQL 프로세스/볼륨과 실제 snapshot·pg_dump/pg_restore·age 암호화/인증 사용. 시험 자원 정리와 기존 Docker 자원 메타데이터 동일 확인 |
| workflow 정적 검사 | `actionlint`·`git diff --check` 통과. 로컬 검증 종료 시점에 원격 CI는 미실행이었으며, 후속 게시 결과는 초안 PR의 최신 HEAD 검사로 구분 |
| 실제 AWS/S3·SNS 이메일·서버 systemd | 미실행 |
| 실제 운영/개발 DB·회원 데이터 이전·서비스 공개 | 미실행 |

통합 fixture는 일반 합성 테이블 39개·시퀀스 29개·수동 Flyway V1~V12 이력으로 구성한다. 실제 애플리케이션 migration 실행이나 실제 회원 데이터 검증을 대신하지 않는다. Docker 전달 계층은 정확한 시험 컨테이너에 한정된 self-container adapter, AWS는 로컬 version simulator다. 주기 검사는 짧게 제한한 scheduler callback이며 실제 hourly systemd 발화 검증은 아니다. 원본 21항목은 정확한 version 다운로드·일시 실패 재시도·영구 실패 시 성공 기록 보존·임시 평문 정리·stale/손상 기록 감시·잠금 중복을 포함한다. 복원 7항목은 암호문 변조/다른 시험키의 조기 거부와 복원 후 덮어쓰기 거부를 포함한다.

초기 통합 실행의 health 대기·tmpfs 파일 전송·시험 도구 실행 문제는 하네스를 보완한 뒤 재검증했다. Windows fake 실행의 OS TEMP/디렉터리 fsync 제한은 workspace fixture와 해당 primitive의 fake로 분리했다. 중단 후 Docker 엔진 미실행은 기존 Desktop 시작 후 해결했으며 최종 통합은 통과했다. 기존 코드·synthetic-only 계약·준비 서버·원본 설정 16파일과 후속 25파일·각 암호문을 보존한다. 실제 운영·개발 DB 내용이나 실제 복구 개인키는 이번 시험에 사용하지 않았다.

## 2026-10-26 구현 당시 실제 적용 전 남은 단계

기존 합성 정책의 시간별 **7일**·주간별 **28일**·기본 Governance **7일**은 출발점이다. 새 production prefix의 IAM·Lifecycle·Versioning/Object Lock·경보에 실제 적용됐다는 뜻이 아니며 운영 대상의 별도 검토·승인·적용 확인이 필요하다. 새 권한은 승인한 prefix의 Put/Get/GetVersion과 새 namespace의 PutMetricData로 제한하고 삭제·보존 우회·관리·SNS Publish 권한을 주지 않는다.

로컬 통합 검증과 변경 검토 후 승인된 범위에서 GitHub·CI를 확인한다. 실제 운영 자격증명/원본 ID·스토리지·단발 백업·독립 복원·감시 수신을 검증하고 사용자 승인을 받은 뒤에만 unit을 활성화한다. 실데이터 이전·쓰기 동결·공개 전환은 [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md)의 별도 조건을 따른다.

관련 기준: [백업 운영 계획](OPERATIONS_BACKUP_PLAN.md), [권한 설정 복구본](RUNTIME_SETTINGS_RECOVERY.md), [배포](DEPLOYMENT.md), [검증 계획](TEST_PLAN.md).
