# 운영 DB 백업·독립 복원 배포 자산

이 자산은 [기존 합성 백업](../backup/README.md)과 분리한다. 새 운영 실행자·설정·state·prefix·unit·지표를 제공하며 기존 synthetic-only 제한이나 source unit을 바꾸지 않는다. 현재는 코드와 합성 검증 단계이며 실제 운영 DB·AWS·systemd 활성화는 수행하지 않는다. 실제 결과와 제한은 [운영 DB 백업 검증](../../docs/OPERATIONAL_DATABASE_BACKUPS.md)을 따른다.

## 설치와 승인 경계

- Linux root, Python 3.12 이상, Docker, 공식 age **v1.3.2**, AWS CLI v2가 필요하다. [age 공식 릴리스](https://github.com/FiloSottile/age/releases/tag/v1.3.2)를 확인하고 공급자 digest를 대조한다.
- 별도 `/opt/jeongeum/operational-backup` 아래 `operational-backup.py`와 이 실행자가 참조할 `production-backup.py`를 함께 둔다. 공통 모듈·실행 파일·부모 경로는 root 소유이고 그룹/타인에게 쓰기를 허용하지 않는다. 기존 합성 실행자의 설치 경로를 덮어쓰지 않는다.
- `/etc/jeongeum-operational-backup`과 `/var/lib/jeongeum-operational-backup`은 root `0700`, 실제 config·credentials·state는 `0600`으로 보호한다. symlink·hardlink와 예상 밖 source/target을 거부한다.
- [운영 config 예시](config.production.example.json)는 placeholder와 `activation_approved:false` 때문에 바로 실행할 설정이 아니다. 검토된 실제 컨테이너 ID·볼륨·DB·앱 역할·이미지·계정·버킷·공개 age 수신자로 채운다. 실제 연결·설치 승인 전에는 활성화 표시를 바꾸거나 unit을 설치/enable하지 않는다.
- `validate`는 로컬 설정만 검사한다. 승인 표시가 없는 production `backup`/`metric`은 외부 명령을 시작하기 전에 거부한다. 이 boolean은 보호된 관리자 설정이며 자동으로 사용자 승인을 증명하는 token이 아니다.
- 운영 백업 실행자는 DB를 시작하거나 재생성하지 않는다. 기존 합성 readiness/source unit도 호출하지 않는다. 정확히 바인딩된 PG가 healthy가 아니면 실패하고 이전 성공을 보존한다.

```sh
sudo /usr/bin/python3 -I -B /opt/jeongeum/operational-backup/operational-backup.py validate \
  --config /etc/jeongeum-operational-backup/config.json
```

위는 승인 후 준비할 로컬 검증 명령 예시다. 설치·권한·실제 단발 백업/복원·감시를 검토한 뒤 별도 승인 범위에서 `systemd-analyze verify`와 service/timer 활성화를 진행한다. 자동 설치나 자동 enable 스크립트는 제공하지 않는다.

## 백업·감시 계약

`production/`에 시간별·주간별 암호문을 저장하고 반환된 정확한 VersionId로 Head/Get·크기/SHA를 확인한다. 성공 기록은 모든 필수 업로드를 검증한 뒤에만 원자적으로 바꾼다. 실패·잠금 중복은 이전 성공을 유지하며 원격 삭제 권한이나 동작을 추가하지 않는다. 최대 두 번 시도하고 재시도는 새 snapshot·새 object UUID를 사용한다. 일부 업로드 후 실패하면 원격 객체가 더 남을 수 있다.

하나의 exported read-only MVCC snapshot을 표 지문과 pg_dump가 공유한다. sequence는 MVCC 대상이 아니므로 전후 값이 달라지면 실패/제한된 재시도로 처리한다. 쓰기 중 항상 백업이 성공한다고 보장하지 않는다. 현재 schema 경계는 39테이블·29시퀀스·성공한 Flyway V1~V12이며 새 migration을 배포하기 전에 이 계약도 갱신해야 한다.

새 unit은 hourly 백업과 별도 5분 감시를 정의한다. 지표는 `JEONGEUM/OperationalBackups` / `RecoverableSnapshotAgeMinutes`, 차원은 `Environment=production`, `Instance=jeongeum-prod-01`, Unit은 `None`이다. 업로드 완료 시각이 아닌 성공한 **snapshot 시작 시각**으로 나이를 계산한다. 없는/손상/다른 원본/미래/뒤집힌 시각의 성공 기록은 stale이며 감시에는 DB/Docker 조회가 필요하지 않다.

[경보 예시](alarm.example.json)는 `Maximum`, 300초, `>90`, missing=breaching, ALARM/OK action을 정의한다. 실제 SNS topic·구독과 지표·경보는 별도 검토/승인 후 연결한다. 이번 자산 작성과 local cloud simulator 결과를 AWS/SNS 발송 성공으로 보고하지 않는다.

## IAM·보존·비용

[IAM 예시](iam-policy.example.json)는 승인된 버킷의 `production/hourly/*`·`production/weekly/*`에 Put/Get/GetVersion과 새 namespace의 PutMetricData만 부여한다. 삭제·보존 우회·정책 관리·SNS Publish 권한을 주지 않는다. credentials는 root-private `[backup]` profile의 access key/secret 및 필요한 session token만 허용하고 다른 profile·endpoint·credential process·상속 환경 설정은 사용하지 않는다. root API 키를 만들지 않는다.

기존 합성 IAM·prefix·timer·경보를 운영용으로 재지정하지 않는다. 운영 경로의 시간별 7일·주간별 28일·기본 Governance 7일 보존은 이전 정책을 기준으로 **실제 대상의 별도 검토/적용 확인이 필요**하다. 새 prefix 권한·Lifecycle·지표/경보는 코드만으로 설치되지 않는다. 추가 AWS 자원이나 비용 변경은 먼저 보고·승인받으며 이번 단계에서 생성하지 않는다.

## 독립 복원

[복원기](../../scripts/operational-restore.py)는 production target을 허용하지 않는다. 별도의 PostgreSQL 엔진을 실행할 새 컨테이너·새 빈 DB·새 볼륨, `NetworkMode=none`, 호스트 포트 없음, 정확한 검증용 ID·label·이미지·최소 권한 앱 역할이 필요하다. 여기서 엔진은 별도의 PostgreSQL 프로세스/데이터 볼륨이며 물리적 호스트까지 독립된 것을 뜻하지 않는다. [target 예시](config.restore.example.json)의 placeholder를 검토한 새 대상으로 채우고 예상 source를 고정한다.

암호문은 업로드 기록의 정확한 version·크기/SHA로 받은 뒤 복원한다. 복구 개인키는 백업 서버에 두지 않고 별도 복원 환경에서만 사용한다. age 인증과 정확한 3파일·경계·CRC·SHA·source 검증이 끝나야 target DB 조회를 시작한다. 빈 DB에 `--single-transaction --no-owner --no-acl --role`로 복원하고 39테이블·29시퀀스·Flyway·표 지문·소유권을 대조한다. `DROP`, `--clean`, 자동 target 삭제는 없다. 복원 뒤 지문 검사 실패도 대상은 보존한다.

초기 범위는 암호문·dump 각각 최대 128MiB, 복원 tar 최대 256MiB다. dump 크기는 종료 후 검사하며 service의 `LimitFSIZE=300M`·시간·메모리 제한과 별도로 고려한다. 큰 DB나 CLI 직접 실행의 임시 디스크 소비는 이 범위를 벗어나므로 별도 스트리밍/자원 검증 없이 용량 보장을 주장하지 않는다. 설정·역할 자격증명 복구는 [별도 25파일 복구본](../../docs/RUNTIME_SETTINGS_RECOVERY.md)을 함께 관리하며 DB 암호문 하나로 전체 서비스 RPO/RTO를 달성했다고 보고하지 않는다.
