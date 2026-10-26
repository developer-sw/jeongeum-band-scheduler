# 컨테이너별 비밀파일 권한과 합성 Linux 검증

## 승인 범위와 보존

2026-10-25 사용자는 **비밀파일 권한 구성 구현·합성 Linux 검증**을 승인했다. 소비자별 파일 참조와 권한을 구현하고 합성 값으로 읽기 허용·거부·재시작을 확인한다. 실제 운영 자격증명 설치나 기존 개발·운영 DB, 컨테이너, 볼륨, AWS 설정 변경은 포함하지 않는다.

기존 비활성 운영 설정 **16파일과 암호문은 그대로 보존**한다. 후속 사용자 승인으로 새 런타임 파일·override·권한표를 포함한 **25파일 설정 묶음의 암호화·독립 복원**도 완료했다. [갱신 복구본 기록](RUNTIME_SETTINGS_RECOVERY.md)의 Windows 인증 복원·Linux 권한 재현을 구분하며 기존 16파일 archive가 새 구성을 복구한다고 표현하지 않는다. 운영 DB 백업, 실제 서버 설치·데이터 이전·공개, GitHub 반영은 계속 후속 범위다.

## 원본 5개와 런타임 사본 6개

[운영 Compose](../deploy/compose.production.yaml)는 파일 기반 secrets를 사용한다. Backend의 기본 사용자는 `10001:10001`이고 PG 초기화 스크립트도 앱 비밀번호 파일을 읽는다. 두 소비자는 같은 값에 접근하되 각각 자신의 소유권에 맞춘 파일을 사용한다.

보호된 관리용 원본은 5개로 유지한다. 앱 DB 비밀번호만 값이 같은 런타임 파일 두 개로 나눠 런타임 파일은 총 6개가 된다. 비밀번호 생성·회전 작업이 아니다.

| 관리용 원본 basename | 런타임 basename | 소유자·mode | 소비자 |
|---|---|---|---|
| `postgres-admin-password.txt` | `postgres-admin-password.txt` | `0:0`, `0400` | 고정 PG 이미지의 기본 root entrypoint |
| `app-db-password.txt` | `postgres-app-db-password.txt` | 확인한 PG UID:GID, `0400` | postgres 사용자로 실행되는 앱 역할 초기화 |
| `app-db-password.txt` | `app-db-password.txt` | `10001:10001`, `0400` | Backend |
| `vapid-public-key.txt` | `vapid-public-key.txt` | `10001:10001`, `0400` | Backend |
| `vapid-private-key.txt` | `vapid-private-key.txt` | `10001:10001`, `0400` | Backend |
| `vapid-subject.txt` | `vapid-subject.txt` | `10001:10001`, `0400` | Backend |

관리용 원본 디렉터리는 root 소유 `0700`, 원본 파일은 root 소유 `0600`이다. 런타임 디렉터리도 root 소유 `0700`으로 관리하며 Docker daemon이 각 파일만 읽기 전용으로 mount한다. 컨테이너에 관리용 원본이나 호스트 상위 디렉터리를 공유하지 않는다. Frontend와 Caddy에는 이 secret을 mount하지 않는다.

PG 관리자 파일의 root 소유는 **기본 entrypoint가 root 단계에서 값을 읽는 고정 이미지 계약**에 의존한다. 이미지·entrypoint·`user` 설정 변경 시 다시 확인한다. PG init의 실제 사용자도 고정 이미지에서 확인해 helper에 전달한다. 관측한 프로세스 UID만으로 초기화 전체를 검증했다고 판단하지 않는다.

Docker 공식 문서는 [file source의 uid/gid/mode 제한](https://docs.docker.com/reference/compose-file/services/#secrets)과 [단일 파일 bind mount·파일 권한](https://docs.docker.com/compose/how-tos/use-secrets/)을 설명한다. Compose 선언으로 호스트 소유권이 바뀐다고 가정하거나 모든 파일을 `0444`로 만들지 않는다.

## 준비·검사 도구

[runtime-secrets.py](../scripts/runtime-secrets.py)는 Linux root 관리 작업으로 사용한다. `prepare`는 새 목적지만 만들고 원본을 수정하지 않는다. `check`는 원본과 런타임의 값 대응·소유권·mode를 검사한다. 비밀값·개별 SHA·실제 경로는 로그로 출력하지 않는다.

```sh
python3 scripts/runtime-secrets.py prepare \
  --source-directory /approved/private/master \
  --destination-directory /approved/private/runtime \
  --postgres-uid "$verified_postgres_uid" \
  --postgres-gid "$verified_postgres_gid"

python3 scripts/runtime-secrets.py check \
  --source-directory /approved/private/master \
  --destination-directory /approved/private/runtime \
  --postgres-uid "$verified_postgres_uid" \
  --postgres-gid "$verified_postgres_gid"
```

위 경로는 설명용 예시이며 운영 서버에서 실행한 명령이 아니다. 실값 복사·서버 설치 승인 전에 실제 운영 원본으로 실행하지 않는다. symlink·hardlink, 통제되지 않는 부모, 잘못된 owner/mode, 원본과 목적지의 겹침, 기존 목적지를 거부한다. Windows ACL·Windows bind source의 `chmod`를 Linux POSIX 권한 검증으로 취급하지 않는다.

## Compose 적용 계약

[compose.runtime-secrets.yaml](../deploy/compose.runtime-secrets.yaml)을 기본 운영 Compose **뒤에** 적용한다. `!override`를 사용하므로 **Docker Compose 2.24.4 이상**이 필요하다. [env 예시](../deploy/.env.production.example)는 관리용 원본 대신 런타임 사본 6개를 가리킨다.

```sh
docker compose --env-file /approved/private/runtime.env \
  -f deploy/compose.production.yaml \
  -f deploy/compose.runtime-secrets.yaml config --quiet
```

위 명령은 구문 점검 예시다. 실제 적용 때는 렌더링 결과의 이미지·참조·mount를 비공개로 대조해야 한다. `config --quiet` 성공이 가독성을 보장하지 않으며 전체 Compose를 `up`하는 절차가 아니다.

| 서비스 | source → 컨테이너 target | 개수 |
|---|---|---|
| PostgreSQL | `postgres_admin_password` → `postgres_admin_password`, `postgres_app_db_password` → `app_db_password` | 2 |
| Backend | 기존 `app_db_password`·VAPID 3개, target 유지 | 4 |
| Frontend·Caddy | 위 비밀파일 mount 없음 | 각각 0 |

`POSTGRES_APP_DB_PASSWORD_FILE`은 PG 전용 사본, `APP_DB_PASSWORD_FILE`은 Backend 전용 사본을 가리킨다. PG 내부 `APP_DB_PASSWORD_FILE=/run/secrets/app_db_password`와 기존 init 스크립트는 유지한다. 기존 PG secret 목록을 대체하므로 예전 공유 source가 PG에 추가로 남거나 target이 중복되지 않아야 한다. 앱을 재빌드하지 않고 rc.3·고정 PG 이미지를 재사용한다.

## 합성 검증과 실제 한계

[합성 하네스](../scripts/test-runtime-secrets.mjs)는 새 식별자와 label로 제한한 Docker Linux 시험 자원만 사용한다. 파일은 daemon의 Linux 파일시스템에 만들고 개별 파일 bind mount를 확인한다. 기존 볼륨·실제 비밀파일·Docker socket·privileged·호스트 네트워크를 제공하지 않는다. 종료 시 이번에 만든 정확한 대상과 label을 확인해 정리한다.

검증 대상은 고정 PG의 새 합성 DB 초기화·파일 읽기·인증·재시작과 rc.3 Backend의 실제 entrypoint 파일 로딩·사용자 권한·재시작이다. Backend는 Java 실행을 검사 프로그램으로 대체하므로 **Spring Boot·Flyway·운영 앱 연결을 실행한 검증이 아니다.** 서버 전체 기능·용량·실회원 이전 완료로 확대하지 않는다.

| 항목 | 결과 |
|---|---|
| 원본·목적지/링크/owner/mode 등 helper 회귀 | 공식 고정 Python 3.13 Linux 컨테이너에서 **18개 통과·skip 0**. Windows 별도 실행은 portable 2개 통과·POSIX 16개 skip으로 구분 |
| Compose source/target 2·4·0·0 계약 | 실제 config 렌더링에서 6개 파일 참조·PG/Backend source 분리·중복 target 부재 통과 |
| 실제 Linux 소유권·읽기 허용/거부·쓰기 거부 | daemon-native 파일 6개·소비자별 0400 통과. 다른 UID 읽기/쓰기와 실제 append 거부, Backend에 root 전용 파일을 준 실패 원인 확인 |
| 고정 PG 초기화·합성 인증·재시작 | 고정 이미지의 root→gosu 순서·postgres UID/GID 999:999 확인. 올바른 사본으로 초기화·앱 역할 인증·합성 행 재시작 보존 통과. 잘못된 사본은 init의 정확한 권한 오류로 실패 |
| rc.3 Backend entrypoint 로딩·재시작 | UID 10001에서 DB/VAPID 4파일 로딩·재로딩 통과. 실제 Java 서버는 실행하지 않음 |
| 시험 자원 정리·기존 환경 보존 | 시험 컨테이너/볼륨 잔존 0. 기존 로컬 컨테이너 11개 메타데이터·볼륨/네트워크 목록 동일. 원본 16파일 SHA와 기존 암호문의 SHA/크기 보존 |

실행일은 **2026-10-25**, Windows 호스트의 Docker Desktop Linux Engine **29.9.2**·Compose **5.6.1**이다. **통합 검사 18개**를 통과했고 위 helper 18개와 별도로 기록한다. Compose는 Windows CLI에서 정적으로 렌더링했으며, 권한 검사는 Windows 공유 파일 대신 새 Linux 볼륨의 개별 파일을 실제 bind mount하여 수행했다. 전체 Compose로 앱을 기동한 결과나 운영 VM의 런타임 검증으로 확대하지 않는다.

첫 두 실행은 하네스가 Compose의 기본 target을 basename으로만 예상해 중단됐다. Compose가 `/run/secrets/…`로 렌더링하는 표기도 같은 target으로 검사하도록 보완했고 각 실패 실행의 시험 자원 정리도 성공했다. 권한 거부 검사는 단순 exit 1에 그치지 않고 Backend·PG init의 고정 오류 문구를 메모리에서 대조하여 다른 오류를 통과로 오인하지 않도록 했다. 비밀값·DB 로그 본문은 출력하지 않는다.

기존 이미지 발행 계약 회귀는 38개 중 **37개 통과·Windows 링크 권한 1개 skip**, `actionlint`도 통과했다. 새 CI job과 경로 필터를 추가했지만 **원격 CI는 실행하지 않았다.** 기존 Backend/Frontend 기능 테스트·실제 Push·운영 서버 설치도 이번 검증에 포함하지 않는다.

비공개 증빙을 근거로 결과를 기록한다. 후속 승인으로 runtime 파일·override·참조를 포함한 **새 설정 암호화·독립 복원**을 완료했다. [갱신 기록](RUNTIME_SETTINGS_RECOVERY.md)의 Windows 인증 복원·별도 Linux 권한 재현은 실제 운영 설치를 대신하지 않는다. 합성 백업과 운영 DB 보호는 [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md)의 별도 조건을 유지한다.

관련 기준: [비활성 운영 설정](INACTIVE_PRODUCTION_SETTINGS.md), [운영 설정 적용 계획](PRODUCTION_INSTALL_PLAN.md), [시험 계획](TEST_PLAN.md).
