# 실서비스용 비활성 운영 설정·암호화 복구 준비

## 승인 범위와 현재 상태

2026-10-24 사용자는 AWS 답변을 기다리는 동안 **실서비스용 운영 설정과 암호화 복구 준비**를 진행하도록 요청했다. AWS Basic 계정·결제 지원에 재문의를 전송했고 사람의 답변은 기다리는 중이다. 이 준비가 AWS Free Plan의 실회원 운영 적합성을 확정하지 않는다.

이번 준비 범위는 새 비공개 로컬 작업 공간의 **미사용 설정·비밀값 생성, 정적 참조 검사, 암호화와 독립 폴더 복원 검증**이다. 실제 서버 설치, DB 역할·계정 생성, 기존 데이터 접근·변경, 볼륨 생성, 컨테이너 기동, DNS·HTTPS 경로 변경, 유료 자원 생성, 실제 회원 데이터 이전과 예약 앱 공개는 포함하지 않는다. 후속 사용자 승인으로 도구·테스트·문서만 GitHub 게시 대상으로 삼고 실제 설정·키·암호문은 게시하지 않는다. 정식 PR 전환·main 병합은 별도 승인 대상이다.

새 설정 16파일의 로컬 생성·실제 age 암호화·새 Windows 폴더 복원과 정적 참조 검사를 완료했다. 서버에 설치하거나 활성화하지 않았다. 기술 권고는 사용자 확정 업무 정책과 구분한다.

## 설정 묶음과 비밀값

기존 [운영 Compose](../deploy/compose.production.yaml)와 [환경 예시](../deploy/.env.production.example)를 재사용한다. 새 운영 묶음은 기존 합성 QA 설정을 수정하거나 복사 후 그대로 활성화하지 않고 별도 식별자로 관리한다.

| 파일 종류 | 준비 내용과 확인 기준 |
|---|---|
| 운영 환경 설정 | 고유 Compose 프로젝트·DB·앱 역할, 실제 도메인, 인증서 연락처, 이미지 digest, 서브넷·Caddy IP·프록시 신뢰, secret 파일 참조 |
| PostgreSQL 관리자 비밀번호 | 새 무작위 값. 기존 개발·QA 자격증명과 분리하고 앱 비밀번호와 다르게 생성한다. 아직 어떤 DB에도 설정하지 않는다. |
| 앱 DB 비밀번호 | 별도 새 무작위 값. PostgreSQL 초기화 설정과 Backend가 같은 파일을 참조하도록 준비한다. 역할 생성·권한 부여는 하지 않는다. |
| VAPID 공개키·개인키 | 새로운 한 쌍을 준비하고 공개키와 개인키의 대응을 검사한다. 기존 구독·발송 환경을 바꾸지 않는다. |
| VAPID subject | 운영 도메인의 HTTPS URI를 사용하고 앱이 허용하는 형식인지 검사했다. 인증서 연락처 이메일과 별도로 관리한다. |

secret 파일은 **DB 비밀번호 2개 + VAPID 공개키·개인키·subject 3개, 총 5개**다. 공개키도 참조 일관성을 위해 같은 비공개 묶음에서 관리한다. 실제 값, 개별 비밀값의 SHA, 수신 이메일, 서버 주소, 저장소 식별자와 비공개 절대 경로는 Git·콘솔 출력·공개 보고서에 포함하지 않는다.

운영 env에는 비밀값 대신 `POSTGRES_ADMIN_PASSWORD_FILE`, `APP_DB_PASSWORD_FILE`, `VAPID_PUBLIC_KEY_FILE`, `VAPID_PRIVATE_KEY_FILE`, `VAPID_SUBJECT_FILE`을 둔다. 원본 Windows 보관 경로와 향후 Linux 설치 경로를 명시적으로 대응시킨다. 로컬 검사용 경로를 실제 서버에 존재하는 경로로 간주하지 않는다.

## 재사용할 이미지와 런타임 계약

[발행 결과](PRODUCTION_IMAGE_RELEASE.md)의 **`v1.0.0-rc.3` / 소스 `a00a791`**을 재사용한다. 현재 백업 도구의 main 소스와 앱 이미지의 출처는 서로 다를 수 있으며 이를 같은 SHA로 보고하지 않는다. 이번 설정 준비로 새 이미지를 발행하거나 기존 태그를 덮어쓰지 않는다.

```text
BACKEND_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/backend@sha256:7dd69db8f16bfb7282b4bed2a2ccf41b58f2e8f59a659e10f9a9ab82510e0a61
FRONTEND_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/frontend@sha256:9957a66e491dfbba8d84a119015ab323c4fbaa95ced16381e42e7692732ca687
CADDY_IMAGE=ghcr.io/developer-sw/jeongeum-band-scheduler/caddy@sha256:9876276ae2e2de72212e1028c5b3cba74a99ee32e7ce17dd7f0a7d4c2000cb5c
POSTGRES_IMAGE=postgres:17.11-bookworm@sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826
```

PostgreSQL digest는 현재 Compose에 고정되어 있다. 위 `POSTGRES_IMAGE` 표기는 대조할 이미지의 기록이며, 현재 Compose가 해당 env 변수를 읽는다는 의미는 아니다.

정적 검사에서 다음 관계를 확인한다.

- `APP_DOMAIN`은 `bandjeongeum.studio`처럼 scheme·경로가 없는 호스트명이다. 인증서 연락처와 VAPID 연락처는 별도 목적의 설정으로 검사한다.
- 새 프로젝트·DB·역할 이름은 기존 개발·QA·백업 원본과 구분한다. 앱 역할은 `postgres`가 아니며 DB 관리자와 분리한다.
- `APP_NETWORK_SUBNET` 안의 `CADDY_INTERNAL_IP`와 `APP_TRUSTED_PROXY_REGEX`가 같은 Caddy 한 개를 가리킨다. 전체 사설망을 신뢰하는 정규식으로 넓히지 않는다. 실제 서버 네트워크와 충돌하지 않는지는 향후 활성화 전 별도로 확인한다.
- Backend는 `production` 프로필과 앱 DB 역할을 사용한다. bootstrap 설정을 넣거나 기존 관리자를 다시 생성하지 않는다.
- `JEONGEUM_RECURRING_WORKER_ENABLED`, `JEONGEUM_EXCHANGE_WORKER_ENABLED`, `JEONGEUM_REMINDER_WORKER_ENABLED`, `JEONGEUM_PUSH_WORKER_ENABLED`, `JEONGEUM_PUSH_ENABLED`는 **모두 `false`**다. 유효한 VAPID 파일이 있어도 발송 허가로 취급하지 않는다.
- Spring Session JDBC와 마지막 접속 후 90일 유지 정책, `SESSION`의 `Secure`·`HttpOnly`·`SameSite=Lax`를 유지한다. localhost의 쿠키는 운영 도메인으로 전달되지 않으므로 기존 계정으로 재로그인한다.
- PostgreSQL과 Backend·Frontend는 호스트 포트를 공개하지 않는다. 운영 Compose의 Caddy는 80/443을 사용하므로 현재 공개 준비 Caddy와 동시에 기동하지 않는다.

현재 DB 초기화 스크립트는 **새 빈 전용 볼륨의 최초 초기화용**이다. 앱 역할은 `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION`이며 DB·schema 소유권을 갖도록 되어 있다. 실제 복원 시 소유권과 Flyway 실행 권한은 별도 검증해야 한다. 이 준비에서는 초기화 스크립트·Flyway·앱을 실행하지 않는다.

## 암호화·복원의 별도 범위

기존 [준비 설정 복구](OPERATIONS_CONFIGURATION_RECOVERY.md)의 `server-preparation-and-synthetic` 범위와 구분하는 **`inactive-production-settings` 범위**를 사용한다. 새 운영 설정을 만들었는데도 기존 범위의 `productionConfigurationCreated=false`로 표시하지 않는다. [설정 백업 도구](../scripts/configuration-backup.py)의 pack·restore 양쪽에 `--scope inactive-production-settings`를 명시해야 한다. 기본 호출은 기존 범위만 허용한다.

신규 config에는 `productionConfigurationCreated:true`와 `configurationState:"staged-inactive"`가 필수다. 신규 manifest는 formatVersion 2이며 기존 v1/false 계약과 혼용할 수 없다. 범위·버전·타입·필드 불일치는 복원 폴더 생성 전에 거부한다. `staged-inactive`는 작성자의 선언이며 도구가 서버의 실제 미활성 상태를 증명하는 것은 아니다. 실제 이번 실행은 로컬 파일 작업으로 제한했다.

기존 범위의 승인 조건·복원 방어는 유지한다. 허용 파일은 검토한 목록만 지정하며 디렉터리 전체·wildcard 수집을 하지 않는다. 기존 archive는 기본 범위로 복원하며 신규 archive를 자동 감지하여 수락하지 않는다.

복구 개인키는 기존 보호 위치에서만 사용하고 서버·archive에 넣지 않는다. 사용자에게 PC 밖 사본 보관·확인 완료를 전달받은 기록은 유지하되, 에이전트가 그 외부 사본을 직접 검증했다고 주장하지 않는다. SSH 개인키·root API 키·TLS 개인키도 새 설정 묶음에서 제외한다.

이번 검증 경계는 다음과 같다.

1. 원본 폴더의 Windows native ACL을 현재 사용자와 SYSTEM으로 제한하고 실제 적용 상태를 확인한다. 파일을 쓰기 전에 비공개 경계를 만든다.
2. age 암호화 전후 원본의 동일성, 명시적 allowlist, 전체 암호문 크기·SHA를 검사한다. 평문 원본을 로그로 출력하지 않는다.
3. 기존 폴더를 덮어쓰지 않는 새 독립 복원 폴더에서 age 인증, archive 경계·크기·파일 SHA, 경로 안전성을 검사한다. 실패한 archive로 부분 복원 파일을 남기지 않는다.
4. 복원된 env와 5개 secret의 대응, DB 비밀번호의 분리, VAPID 키 쌍, worker/Push OFF, 이미지 digest, 원본 불변을 다시 확인한다. 복원된 파일을 실행 중 경로에 설치하지 않는다.
5. Windows ACL과 Linux mode/uid/gid는 서로 다른 검증으로 기록한다. 향후 Linux 설치 시 비root Backend UID가 secret 파일을 읽을 수 있는지 별도로 확인한다. Windows 복원만으로 서버의 소유권·가독성까지 완료했다고 보고하지 않는다.

정적 Compose 렌더링은 비밀값 대신 파일 경로를 사용하고 출력도 비공개로 취급한다. 검증 과정에서 `up`, `create`, `start`, DB 초기화, 볼륨 생성, ACME 발급이나 공개 경로 교체를 실행하지 않는다.

## 기존 백업과의 관계

기존 **준비·합성 설정 30파일 archive**와 **추가 자동 복구 자산 3파일 archive**는 별도 복원 자료로 유지한다. 새 운영 설정 archive는 이 둘을 대체하거나 단일 archive로 합쳤다는 의미가 아니다. 기존 자료를 포함한 복구 순서와 출처를 함께 관리해야 한다.

기존 QA env는 과거 복원 대상 DB를 참조하며 정기 합성 백업 원본 DB와 다르다. 이 차이를 조용히 수정하거나 새 운영 DB로 재지정하지 않는다. 기존 정기 백업의 합성 전용 검증·IAM 경로를 실제 운영 DB로 바꾸는 작업도 포함하지 않는다.

새 archive는 **설정 복구 자료**이며 DB dump·회원 데이터·세션·예약 상태를 복원하는 백업이 아니다. 실제 운영의 DB 백업·정기 설정 백업·S3 업로드·전체 RPO/RTO는 별도 계획과 검증이 필요하다.

## 실제 이전·활성화 전 확인 조건

다음 단계는 사용자 승인과 검증을 거쳐 순서대로 진행한다.

1. AWS Free Plan의 실회원 운영 조건에 대한 사람의 답변과 계정·비용 조건을 확인한다. 필요한 유료 전환·추가 자원은 먼저 보고한다.
2. 새 설정 암호문의 독립 복원 결과와 복구키 접근 가능 여부를 확인한다. 설치 경로·Linux 소유권, 기존 볼륨/네트워크/80·443 충돌을 실제 대상에서 다시 점검한다.
3. 쓰기 동결 시점, 최종 개발 DB 백업, 별도 복원과 권한·행·Flyway·시퀀스 대조, 전환 중단 조건을 검토한 뒤 **실제 데이터 이전 승인을 받는다**. 기존 DB·관리자·회원·팀·예약·볼륨을 보존한다.
4. 승인된 대상에 worker/Push OFF로 복원하고 앱 연결 대상·권한·쿠키를 확인한다. 공개 경로 전환은 기존 준비 화면으로 돌아갈 절차까지 갖춘 뒤 별도로 진행한다.
5. 새 origin의 로그인·홈 화면 설치·사용자별 Push 재구독과 기존 origin의 구독·대기 outbox 처리 방안을 확인한다. worker/발송은 검토 후 단계적으로 활성화한다. env 파일 수정만으로 이미 실행 중인 컨테이너의 설정이 바뀐다고 가정하지 않는다.

**전환 전 또는 운영의 새 쓰기를 허용하기 전**에는 준비 화면으로 경로를 돌리고 새 앱을 중지하는 중단 절차를 사용할 수 있다. 쓰기 동결 이후 어느 DB가 최신 원본인지 확인하고 동결 해제도 명시적으로 결정한다.

**운영에서 새 쓰기를 허용한 뒤**에는 예전 로컬 dump를 덮어써 되돌리면 새 예약·회원 변경을 잃을 수 있다. 먼저 운영 쓰기를 멈추고 최신 운영 DB를 보존·백업한 다음, 변경분과 복구 대상을 대조하는 별도 복구 계획과 승인을 받아야 한다. 이 준비 작업은 자동 rollback이나 양방향 동기화를 제공하지 않는다.

## 검증 결과

실행일: **2026-10-24, Windows**. 비공개 증빙은 Git 제외 폴더에 보관한다.

| 검증 | 실제 결과 |
|---|---|
| 새 설정 | 운영 env·5개 secret·Compose/Caddy/init·런타임 참조·release/binding/활성화 조건 문서 **16파일**. DB 역할 생성·서버 설치 없음 |
| 암호화·독립 복원 | 실제 **age v1.3.2**, 암호문 **10,915bytes**, 신규 v2 범위로 인증 복원. 16파일 전체 SHA·원본 불변·현재 사용자/SYSTEM native ACL 확인 |
| 정적 설정 검사 | 원본과 복원 사본에 각각 **51항목 통과**. rc.3 pin·5개 파일 참조·DB 구분·VAPID P-256 대응/ES256 서명·worker OFF·90일 세션·Compose 렌더링 검사 |
| 실제 실패 거부 | 기본 pack·기본 restore의 신규 범위 거부, 변조·절단·다른 복구키·기존 destination 거부 **6종**. 실패 대상 폴더·덮어쓰기 없음 |
| 도구 회귀 | fake age 기반 **41개 중 40개 통과·hardlink 1개 건너뜀**. 추가 14개는 opt-in·v1 호환·정확한 타입·키/경로 보호 등을 검사 |
| 활성화 경계 | `docker compose config`만 실행. DB·컨테이너·네트워크·볼륨 생성, 앱/worker 시작, 외부 Push·AWS 변경·이미지 발행 없음 |

서브넷은 후보이며 실제 호스트의 충돌 검사는 남아 있다. 최초 로컬 검증 하네스의 Docker 경로 및 Compose JSON 숫자 표현 처리 실패는 하네스만 수정하여 다시 확인했으며 제품·Compose 설정은 바꾸지 않았다. Windows ACL 검증을 Linux UID/GID 적용·helper의 실제 Linux restore로 확대하지 않는다. 이 로컬 검증 당시에는 원격 CI를 실행하지 않았으며 후속 게시의 현재 HEAD 결과는 해당 초안 PR에서 확인한다. 임시 복원 평문 사본은 검증 후 정리하고 비공개 원본·암호문·검증 기록을 보관한다. 기존 PC 밖 복구키 사본은 사용자의 이전 확인 기록이며 새 암호문까지 PC 밖으로 복사한 결과는 아니다.

기존 30파일/3파일 복원은 준비 서버 설정, 이번 16파일은 새 미사용 운영 설정이다. 서로 대체하거나 합산한 단일 전체 복구 결과로 표현하지 않는다. 실제 DB 복구·운영 백업 전환·전체 RPO/RTO·실제 회원 서비스 검증은 별도로 남아 있다.

관련 기준: [배포](DEPLOYMENT.md), [운영 컨테이너](PRODUCTION_CONTAINERS.md), [운영 백업 계획](OPERATIONS_BACKUP_PLAN.md), [시험 계획](TEST_PLAN.md).
