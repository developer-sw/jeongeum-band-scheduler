# 운영 설정 적용 계획과 실행 전 조건

## 2026-10-27 실제 적용 후속

후속 사용자 승인으로 새 설정 설치·실회원 없는 운영 DB 초기화·rc.3 Backend 정상 기동 후 중지·실제 백업/PC 독립 복원·감시 실수신·운영 timer 활성화를 진행했다. 실제 설정의 서브넷으로 충돌 검사를 재수행하고 기존 준비 edge·합성 자원·원본 데이터를 유지했다. 선택 34파일 설정 복구본의 실제 서버 대조와 Windows 복원을 포함한 [실행 결과](OPERATIONAL_SERVER_VALIDATION.md)를 따른다. 아래 계획의 이전 승인 경계·미적용 설명은 당시 이력이며 실제 회원 이전·공개 전환은 별도 승인 대상이다.

## 2026-10-26 후속 구현 상태

소비자별 권한·25파일 암호화 복구본에 이어 **운영 DB용 자동 백업·독립 복원·감시 구현과 로컬 합성 검증**을 진행했다. [운영 DB 백업 검증](OPERATIONAL_DATABASE_BACKUPS.md)과 [기본 비활성 배포 자산](../deploy/operational-backup/README.md)의 완료 범위를 따른다. 기존 합성 백업의 guard·timer·IAM을 바꾸지 않고, 새 합성 PostgreSQL 두 엔진과 별도 볼륨에서 실제 dump·age·복원을 검사했다. 클라우드 저장/지표는 simulator이며 실제 운영 DB·AWS·systemd 설치/활성화는 진행하지 않았다.

사용자 승인으로 변경 검토·커밋·Push·main 대상 초안 PR·원격 CI 확인을 진행하며 최신 원격 결과는 해당 초안 PR의 동일 HEAD 검사를 따른다. 실제 적용 전에는 승인된 운영 대상의 식별·권한/보존·단발 백업·독립 복원·감시 수신을 완료해야 한다. 새 백업 config/credentials/unit을 실제 설치한 뒤에는 해당 설정의 암호화 복구본도 다시 갱신·검증한다. 기존 16파일·25파일 복구본을 덮어쓰지 않으며 정식 전환·main 병합과 실제 데이터 이전·공개는 별도 승인 경계를 유지한다.

## 현재 승인과 계정 상태

2026-10-25 사용자는 **운영 서버의 읽기 전용 사전 점검과 운영 설정 적용 계획·문서 정리**를 승인했다. 현재 계정은 **Paid Plan으로 전환 완료**, 지원은 **Basic**이며 기존 적격 크레딧을 유지한다. 계정별 금액·잔액·식별자는 공개 문서에 기록하지 않는다. Paid Plan과 유료 Support 가입은 서로 다르며 추가 자원·초과 요금의 자동 승인을 뜻하지 않는다.

이전 문서의 Free Plan 유지·종료 관련 기록은 당시 상태다. AWS 재문의에 대한 사람의 답변은 별도로 확인하며 이번 기술 사전 점검으로 계정·청구 관련 답변을 대체하지 않는다.

앞선 사전 점검 단계에서는 실제 secret 복사·서버 설치, Compose 변경, DB 역할/계정/볼륨 생성, 컨테이너 기동과 테스트를 하지 않았다. **후속 사용자 요청으로 비밀파일 소비자별 권한 구성 구현과 합성 Linux 검증을 승인받았다.** [구현 계약과 실행 결과](RUNTIME_SECRET_PERMISSIONS.md)를 별도로 기록하며, 실제 운영 자격증명 설치·원본 16파일/암호문 갱신·기존 DB 변경·실제 데이터 이전·앱 공개는 이번 범위에 포함하지 않는다. 계획·구현·실제 실행 결과를 구분한다.

그 후 승인된 **권한 설정 복구본 갱신·독립 복원**도 완료했다. 새 25파일 묶음의 실제 age 인증 복원·정적 참조·Linux 소비자 권한 재현은 [별도 기록](RUNTIME_SETTINGS_RECOVERY.md)을 따른다. 기존 16파일/암호문을 덮어쓰지 않았으며 실제 운영 자격증명 설치·DB 생성·앱 실행·이전·공개는 여전히 후속이다.

## 재사용할 자산과 보존 기준

| 자산 | 기준 |
|---|---|
| 앱 이미지 | [발행 기록](PRODUCTION_IMAGE_RELEASE.md)의 `v1.0.0-rc.3`, 소스 `a00a791`의 Backend·Frontend·Caddy **exact manifest digest** 3개를 재사용한다. 서버에서 재빌드하거나 가변 태그로 바꾸지 않는다. |
| PostgreSQL | [Compose](../deploy/compose.production.yaml)에 고정한 공식 PostgreSQL 17.11 digest를 그대로 대조한다. 이미지 버전 변경·기존 볼륨 초기화는 별도 작업이다. |
| 새 운영 설정 | [비활성 설정](INACTIVE_PRODUCTION_SETTINGS.md)의 원본 16파일·암호문·검증 기록을 보존한다. 미사용 DB 비밀번호 2개와 VAPID 공개키·개인키·subject의 **5개 논리값**은 아직 설치하지 않았다. |
| 기존 준비 설정 | 원래 30파일 archive와 추가 3파일 archive를 별도로 보존한다. 새 16파일을 더해 단일 전체 복구가 완료됐다고 보고하지 않는다. |
| 기존 서버 | 공개 HTTPS 준비 Caddy, 합성 QA PG·앱의 현재 상태, 네트워크·볼륨·컨테이너 식별자와 자동 백업/감시 설정을 보존한다. |
| 개발 원본 | 관리자·회원·팀·예약·세션·Flyway V1~V12·Docker 볼륨을 유지한다. 이번 단계에서 실제 개발 DB를 조회하거나 수정하지 않는다. |

공개 소스의 [Backend Dockerfile](../backend/Dockerfile), [entrypoint](../backend/docker-entrypoint.sh), [PG init](../deploy/postgres/init-app.sh), [운영 Caddy](../deploy/caddy/Caddyfile)와 [공통 라우팅](../deploy/caddy/common.caddy)을 기준으로 아래 경계를 검토했다. 실제 서버의 현재 자원·UID·권한·충돌 여부는 별도 읽기 전용 점검 결과로 기록한다.

## 적용 전에 해결할 secret 권한

**모든 secret을 `root:root 0600`으로 설치하는 것만으로는 현재 구성을 실행할 수 있다고 판단할 수 없다.** Docker daemon의 root 권한은 mount 대상 파일의 읽기 권한을 비root 앱에 자동 부여하지 않는다. file-backed Compose secrets는 bind mount의 소스 권한 영향을 받으므로 선언상의 `uid`, `gid`, `mode`만으로 해결됐다고 가정하지 않는다.

Docker 공식 문서는 [file source의 uid/gid/mode 제한](https://docs.docker.com/reference/compose-file/services/#secrets)과 [서비스별 단일 파일 bind mount·파일 권한](https://docs.docker.com/compose/how-tos/use-secrets/)을 설명한다. 실제 적용 방식은 고정 이미지와 서버의 Docker/Compose 버전에서 검증한다.

- Backend 소스는 `USER 10001:10001`이며 entrypoint가 DB 비밀번호와 VAPID 파일을 직접 읽는다. 고정 rc.3 이미지의 실제 실행 UID/GID와 이 설정의 일치를 확인해야 한다.
- PG init도 앱 DB 비밀번호 파일을 읽는다. 이 시점의 실제 실행 사용자와 공식 PG entrypoint의 관리자 비밀번호 읽기 시점은 **고정 이미지에서 확인해야 하며 UID/GID를 추측하지 않는다.**
- 현재 Compose는 앱 DB 비밀번호 **한 source 파일**을 PostgreSQL과 Backend에 공유한다. Backend만 읽을 수 있게 owner를 바꾸면 PG init이, PG만 읽을 수 있게 바꾸면 Backend가 실패할 수 있다.
- root 또는 승인된 관리 주체는 보호된 원본과 설치 경로·Docker mount를 관리한다. 앱을 root로 실행하거나 Docker socket 접근권을 넓히는 방식으로 해결하지 않는다.

### 소비자별 런타임 파일 분리 구현

원본 5개 논리값을 보호된 관리용 원본으로 보존하고, 소비자별로 필요한 값만 별도 런타임 파일에 제공하는 방식으로 구현한다. 관리용 원본은 root 전용 디렉터리·관리자만 읽는 권한으로 보관한다. 런타임 파일은 **실제로 확인한 소비자 UID가 소유하고 `0400`**, source 상위 경로는 root가 통제한다. 다른 사용자에게 읽기 권한을 넓히는 안은 사용하지 않는다.

| 논리값 | 분리안의 소비자 |
|---|---|
| PostgreSQL 관리자 비밀번호 | 고정 PG 이미지의 entrypoint가 값을 읽는 실제 실행 사용자에 맞춘 파일 |
| 앱 DB 비밀번호 | **같은 값의 PG init용 사본과 Backend용 사본**을 분리하고 각각의 소비자에 맞춘 파일 |
| VAPID 공개키·개인키·subject | Backend용 파일 3개 |

이는 논리값을 추가하거나 비밀번호를 두 개로 바꾸는 것이 아니다. 물리적 파일 수는 늘어나므로 관리용 원본·런타임 사본·Compose target의 대응을 별도로 관리해야 한다. 값의 일치 여부는 비공개로 검사하고 비밀값이나 개별 비밀값 SHA를 출력하지 않는다.

분리 구현은 [전용 Compose override](../deploy/compose.runtime-secrets.yaml)와 [Linux 준비·검사 helper](../scripts/runtime-secrets.py)로 관리한다. PG만 별도 앱 DB 비밀번호 source를 사용하되 내부 target을 유지하며 Backend의 기존 source/target은 유지한다. `!override`를 위해 Compose 2.24.4 이상을 요구하고 렌더링에서 PG 2개·Backend 4개·Frontend/Caddy 0개와 중복/잔존 source 부재를 검사한다. **합성 Linux의 실제 실행 결과는 [검증 문서](RUNTIME_SECRET_PERMISSIONS.md)에 기록하며 코드 작성만으로 통과를 주장하지 않는다.** 기존 v1/v2 archive와 원본 16파일은 덮어쓰지 않는다. 변경 자산의 새 버전·암호화 복구 기록은 후속 단계다.

검증 항목은 소비자별 파일 가독성, 다른 서비스에 대한 불필요한 mount 부재, 파일·상위 경로의 소유권/쓰기 제한, symlink·hardlink 등 예상 밖 참조 부재, 재시작 후 동일 동작이다. 승인받은 권한 검증용 컨테이너는 **새 합성 자원만 사용**하며 기존 PG·DB·볼륨을 mount하거나 secret 본문을 출력하지 않는다. Backend entrypoint 검사는 Java 실행을 검사 프로그램으로 대체하므로 Spring/Flyway 검증과 구분한다. Windows native ACL·정적 Compose 렌더링 결과가 Linux 런타임 검증을 대신하지 않는다.

## 준비 Caddy와 운영 Compose의 기동 경계

현재 공개 준비 Caddy는 80/443을 사용한다. 운영 Compose의 Caddy도 같은 포트를 publish하며, 의존 순서는 **PG healthy → Backend healthy → Frontend healthy → Caddy**다. 따라서 전체 Compose를 한 번에 기동하면 Backend·Frontend가 먼저 시작된 뒤 마지막 Caddy의 포트 충돌로 실패할 수 있다. 이때 앱이 시작되지 않았다고 가정해서는 안 된다.

**일괄 `up`을 적용 절차로 사용하지 않는다.** 준비 Caddy를 계속 유지하면서 내부 앱 검증과 최종 공개 edge 전환을 구분한다. 신규 운영 Caddy의 설정·TLS 저장소·볼륨과 기존 준비 Caddy의 자산은 별개이며 기존 인증서 자산을 덮어쓰거나 자동으로 공유하지 않는다.

운영 Compose의 Backend는 Flyway가 활성화되어 있다. worker/Push 5개 flag가 모두 OFF여도 앱 시작이 DB 무변경 작업이라는 의미는 아니다. PG의 새 빈 볼륨 초기화는 DB·역할 생성이며 Backend 시작은 migration·세션 등 쓰기 가능성을 동반한다. 각각 이후 실행 승인 범위에 포함해야 한다.

## 합성 QA와 운영 백업의 구분

현재 QA env의 과거 복원 대상 `_restore` DB와 정기 백업의 원본 DB는 다르다. 기존 설정·대상 차이를 유지하고 실제 값을 공개하지 않는다. 기존 source guard는 정확한 합성 PG 컨테이너·프로젝트·이미지·단일 볼륨·미공개 포트를 확인한 뒤 그 PG만 시작하도록 구현되어 있다.

[기존 백업 자산](../deploy/backup/README.md)은 **synthetic-only**이며 실제 운영 DB를 보호하는 구현이 아니다. 기존 timer·config·IAM prefix를 새 운영 DB로 바꾸거나 guard를 완화하는 방식으로 운영 백업을 만들지 않는다. 새 [운영 DB 백업 도구](OPERATIONAL_DATABASE_BACKUPS.md)는 별도 구현·로컬 합성 검증을 마쳤으며 운영 대상·권한·보존·실제 암호화 백업/독립 복원·감시 수신을 승인된 환경에서 확인한 뒤 활성화해야 한다. 기존 합성 백업의 성공이나 경보 OK로 운영 DB의 보호를 주장하지 않는다.

기존 합성 PG를 수동 정지해도 다음 백업의 readiness가 다시 시작할 수 있다. 이번 점검에서 이를 정지하거나 타이머를 바꾸지 않는다. 향후 점검 중단·복귀가 필요하면 [기존 절차](OPERATIONS_REBOOT_VALIDATION.md)를 따르고 운영 앱의 자동 복구 정책과 혼용하지 않는다.

## 이후 적용 순서와 승인 조건

아래는 단계별 승인 경계다. 후속 승인으로 권한 구현·합성 검증을 진행하지만 실제 운영 자격증명 설치·운영 DB/앱 기동까지 승인된 것으로 해석하지 않는다.

| 단계 | 실행 전 조건·검증 | 현재 경계 |
|---|---|---|
| 1. 기준선 확정 | 실행 중/중지 컨테이너·볼륨·restart policy, 공개 포트, 가용 디스크/메모리, Docker 네트워크, 파일 소유권과 secret 소비자 확인 | 현재는 읽기 전용 확인 결과만 기록 |
| 2. 권한 방식 구현·합성 검증 | 소비자별 secret 계약 결정, override/소스 변경 검토, fake 값으로 실제 Linux 가독성·거부·재시작 검증 | 후속 사용자 요청으로 승인됨. [구현·실행 결과](RUNTIME_SECRET_PERMISSIONS.md)를 따르며 실값 복사·서버 설치 없음 |
| 3. 새 설정 설치 | 전용 프로젝트·경로·DB·역할·볼륨·서브넷의 충돌 부재, rc.3/PG digest와 원본 출처, 변경 자산의 암호화 독립 복원 확인 | 실제 secret 복사·서버 설치 승인 후. 기존 경로 덮어쓰기 없음 |
| 4. 최종 원본 백업·별도 복원 | 쓰기 동결 시각·최신 원본·최종 dump·원본 보존, 행/시퀀스/역할/소유권/세션/Flyway 대조, 운영 백업·복구 방안 준비 | 실제 개발 DB 백업·복원·이전의 승인 범위를 먼저 확정 |
| 5. 운영 DB·내부 앱 검증 | 전용 DB 복원·앱 역할 최소 권한, worker/Push 모두 OFF, Backend·Frontend만 순서대로 기동, 건강 상태·권한·예약 조회/대조, 별도로 준비한 운영 DB 백업의 exact-version 대조·독립 복원·감시 확인 | 기존 준비 Caddy·공개 경로 유지. bootstrap으로 관리자 재생성하지 않음 |
| 6. 공개 edge 전환 | 운영 DB 백업·독립 복원과 내부 앱 검증 성공, 쓰기 허용 여부·전환 시간·중단 조건 확인, 준비 Caddy 복구 절차·TLS 계획, 기존 edge의 80/443 해제와 새 edge 기동 순서 승인 | 별도 공개 전환 승인 후. 두 edge를 같은 포트에 동시에 기동하지 않음 |
| 7. 서비스 검증·단계별 활성화 | 운영 origin 로그인·90일 Secure/HttpOnly/SameSite=Lax 쿠키·권한·예약, 새 설치/Push 재구독·대기 outbox 확인, 운영 백업·감시의 지속 성공 확인 | 데이터 worker·발송 worker를 별도 순서로 활성화. 과거 실기기 결과로 대체하지 않음 |

고정 subnet 후보와 Caddy IP·정확한 프록시 정규식은 **실제 서버의 호스트/Docker 네트워크와 대조**한 뒤 확정한다. DB·Backend·Frontend·Docker API의 포트를 새로 인터넷에 공개하지 않는다. 기존 4GB VM의 자원 사용량과 디스크 여유를 확인하며, Compose memory limit 합계나 문서만으로 실서비스 용량이 충분하다고 결론내리지 않는다.

공개 앱에 별도 쓰기 차단 장치가 없다면 **실제 계정으로 로그인할 수 있게 공개하는 시점부터 새 쓰기가 발생할 수 있다**고 본다. worker OFF를 사용자 예약 변경 차단으로 취급하지 않으며, 백업 검증과 쓰기 허용 승인을 공개 이후로 미루지 않는다.

## 중단 조건과 복구

다음 상황에서는 다음 단계로 진행하지 않는다.

- 이미지 digest·출처, secret 참조/값의 대응, 소비자 권한 또는 실제 mount가 계획과 다름.
- 기존 볼륨·DB·네트워크·80/443과 충돌하거나 충분한 디스크/메모리를 확인하지 못함.
- 최종 백업·독립 복원·행/권한/Flyway 대조가 실패하거나 운영 DB 백업/감시의 보호 범위가 미확정임.
- Backend 건강 상태·세션/권한·필수 예약 흐름이 실패하거나 worker/Push가 계획보다 먼저 켜짐.
- 승인한 범위 밖의 데이터 쓰기·공개 경로·요금 또는 추가 자원이 필요함.

**운영 새 쓰기 허용 전:** 새 앱의 정확한 대상만 정상 중지하고 준비 화면을 유지하거나 복구한다. 기존 edge·컨테이너·설정·볼륨을 삭제하지 않는다. 신규 DB도 원인 확인용으로 보존하며 `down -v`, volume prune, 기존 DB 초기화를 복구 수단으로 쓰지 않는다. 로컬 쓰기 동결 해제는 어느 DB가 최신 원본인지 확인한 뒤 명시적으로 결정한다.

**운영 새 쓰기 허용 후:** 먼저 운영 쓰기와 새 작업 발송을 통제하고 **최신 운영 DB·설정·변경 이력을 보존·백업**한다. 예전 로컬 dump를 운영 DB에 덮어쓰거나 이전 원본에서 다시 서비스를 열면 새 예약·회원 변경을 잃을 수 있다. 이후 변경분·복구 대상·허용 손실·재개 조건을 검토한 별도 복구 계획과 승인을 받아야 한다. 이 문서는 자동 rollback이나 양방향 동기화를 제공하지 않는다.

## 실제 점검 결과와 남은 검증

**2026-10-25 18:38~18:43 KST**, 기존 Lightsail 브라우저 SSH로 읽기 전용 점검을 수행했다. Docker inspect 결과는 허용한 상태·이미지 출처·마운트 메타데이터만 출력했고 환경변수·비밀파일 본문·DB를 읽지 않았다. 비공개 증빙은 Git 제외 폴더에 보관한다. 소스 검토에서 Backend의 명시적 UID/GID·FILE 로딩, 공유 앱 DB secret, Caddy 의존 순서·80/443, synthetic-only 백업 경계를 함께 확인했다.

| 확인 대상 | 기록 상태 |
|---|---|
| 서버 자원·디스크/메모리·Docker 서비스 | Ubuntu 24.04.4/x86_64, RAM 3832MiB 중 가용 3212MiB, swap 없음, 디스크 가용 70.93GiB. Docker 29.9.1·Compose 5.6.0, Docker active/enabled, 재부팅 요구 파일 없음. 순간 관측이며 부하·용량 보장은 아님 |
| 기존 컨테이너·볼륨·restart policy·포트 기준선 | 기존 5컨테이너·5볼륨. 준비 Caddy와 합성 PG만 running/healthy, QA 앱 3개는 exited. Caddy `unless-stopped`, 합성 PG·QA 앱 `no` 유지. 중지 앱에 남은 health 상태는 실행 중 장애로 집계하지 않음. 80/443은 준비 Caddy 소유, 3000/8080/5432/2375/2376 리스너 없음. Lightsail 방화벽은 80/443·브라우저 SSH만 허용 |
| 호스트/Docker 네트워크와 신규 후보의 충돌 여부 | 기존 Docker subnet과 호스트 IPv4 전체 routing table의 명시적 경로에 신규 후보의 겹침 없음. 아직 네트워크를 생성하지 않았으므로 실제 설치 직전에 다시 대조 |
| 이미지·사용자·소스 권한 | 기존 서버 앱 이미지 출처는 rc.2/`8b24285`, Backend Config.User는 `10001:10001`. 합성 PG의 현재 postgres 프로세스 UID/GID `999:999` 관측. PG init의 secret 읽기 시점·rc.3 실제 마운트 가독성은 미검증. 준비/백업 상위 경로 `root:root 0700`, 백업 config·성공 상태 파일 `0600`, 확인 경로 symlink 없음 |
| 백업·자동 복구 | source unit active/enabled, backup·metric timer active/enabled, 마지막 oneshot 두 service success/exit 0. 합성 백업 snapshot 18:00:06 → 검증 18:00:11 KST, 관측 당시 age 약 39.5분. hourly 27,206bytes·보존 주간본 27,213bytes. source/backup/metric을 수동 실행하거나 AWS 경보 상태를 새로 조회한 결과는 아님 |
| 공개 준비 화면·미설치 경계 | 공개 HTTPS GET 200·준비 문구 확인. 새 운영 설정 대상 디렉터리 없음. rc.3 설치·이미지 pull·컨테이너/네트워크/볼륨 생성 없음 |
| 로컬 설정 원본 보존 | 원본 16파일 모두 baseline SHA 일치·추가/누락 0개. 23경로의 허용 ACL은 사용자+SYSTEM, 암호문 10,915bytes 존재. baseline 숫자 크기 필드는 없어 별도 크기 대조로 표현하지 않으며 복원 재실행 없음 |
| 소비자별 secret 방식의 구현·Linux 합성 실행 | 후속 사용자 승인 후 로컬 Linux helper 18개·통합 검사 18개 통과, 시험 자원 정리와 원본 보존 확인. [별도 기록](RUNTIME_SECRET_PERMISSIONS.md)의 mock Java·정적 Compose 범위를 따름. 운영 VM 설치 결과가 아님 |
| 실값 복사·서버 설치·운영 DB 생성/복원·앱 기동 | 미실행 |
| 운영 백업 전환·실제 데이터 이전·공개 전환 | 미실행 |

기존 로컬 16파일의 정적 51항목 검사·age 복원이나 Linux fake 회귀를 위 실행 결과로 재사용하지 않는다. 계정번호·서버 IP·수신 이메일·bucket·실제 비밀값/키·개별 secret SHA·비공개 절대 경로는 공개 문서와 로그에 싣지 않는다.

**secret 소비자별 권한 구현·합성 Linux 검증과 새 25파일 암호화 복구본의 독립 복원을 완료**했고, 후속으로 별도 운영 DB 백업·독립 복원·감시 구현과 로컬 합성 검증을 마쳤다. 다음은 변경 검토·GitHub/CI 확인 후 승인된 실제 설치·백업/복원·감시 검증과 이전 단계다. 현재 합성 PG/준비 edge와 새 운영 Compose를 동시에 모두 기동할 자원 여유를 이 순간의 수치만으로 보장하지 않는다. 로그인 배너의 업데이트 개수는 캐시 안내이므로 최신 보안 패치 점검 결과로 취급하지 않았고 패키지 갱신·설치·재부팅도 하지 않았다.

관련 기준: [배포](DEPLOYMENT.md), [아키텍처](ARCHITECTURE.md), [운영 컨테이너](PRODUCTION_CONTAINERS.md), [비활성 설정](INACTIVE_PRODUCTION_SETTINGS.md), [백업 계획](OPERATIONS_BACKUP_PLAN.md), [시험 계획](TEST_PLAN.md).
