# 운영 컨테이너 구현과 합성 통합 검증

기준일: 2026-10-17, Asia/Seoul. 기반 main `bf2a474`, 작업 브랜치 `codex/production-containers`. 앞선 미커밋 사전 점검 문서와 사용자 UI·DB를 보존한다.

후속 상태: [PR #23](https://github.com/developer-sw/jeongeum-band-scheduler/pull/23)을 사용자 승인으로 main `911c181`에 병합했으며 [main CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39006934855)의 Backend 368건·Frontend 334건·합성 검사 57개가 성공했다. 다음 [운영 이미지 발행 준비](PRODUCTION_IMAGE_RELEASE.md)는 별도 브랜치에서 진행하며 이 문서의 registry 미발행·실배포 미실행 경계는 유지한다.

## 범위

사용자가 운영 Docker·Compose·Caddy 구현과 합성 DB 통합 검증을 승인했다. 이 작업은 로컬 Docker의 격리 환경을 대상으로 하며 서버 구매·공개 도메인 연결·실제 개발 DB 이전·외부 Push 발송·GitHub 반영을 수행하는 승인이 아니다. 서울 4GB는 [사전 구성안](DEPLOYMENT_PREFLIGHT.md)의 기술 권고이며 VM의 실측 용량 보장이 아니다.

후속으로 사용자가 검증된 변경사항의 커밋·Push·main 대상 초안 PR·원격 CI 확인을 승인했다. 기존 개발 DB와 서버를 유지한 채 GitHub에 반영하며 정식 PR 전환·main 병합·구매·실배포는 별도 단계다. 원격 CI 결과는 해당 PR과 실행의 실제 상태를 기준으로 확인한다.

## 구현 파일과 실행 경계

| 파일 | 역할 |
|---|---|
| [Backend Dockerfile](../backend/Dockerfile)·[entrypoint](../backend/docker-entrypoint.sh) | Java 21의 Linux JAR 빌드, 비root UID 10001, curl health, runtime secret 파일 읽기, production/bootstrap-admin 프로필 구분 |
| [운영 프로필](../backend/src/main/resources/application-production.yaml) | Secure 쿠키·graceful shutdown·정확한 Caddy IP의 native forwarded 신뢰·복원 전 worker/Push OFF 기본값 |
| [Frontend Dockerfile](../frontend/Dockerfile)·[Next 설정](../frontend/next.config.ts) | Node 24 Linux 빌드와 standalone, 비root UID 1000, 내부 bind·Node HTTP health, public/static 복사 |
| [운영 Compose](../deploy/compose.production.yaml) | Caddy·Frontend·Backend·PostgreSQL, 비공개 앱/DB 네트워크, 독립 볼륨·서비스 health·메모리 제한·로그 회전 |
| [Caddy Dockerfile](../deploy/caddy/Dockerfile)·[운영 설정](../deploy/caddy/Caddyfile)·[공통 설정](../deploy/caddy/common.caddy) | 비root 프록시, 인증서 볼륨, HTTPS·API 경로 보존·외부 forwarded 제거·민감 응답 no-store·정적 해시 캐시 |
| [DB 초기화](../deploy/postgres/init-app.sh) | 빈 새 볼륨에서만 전용 앱 역할을 생성. 앱은 DB/schema 소유자이나 superuser·CREATEDB·CREATEROLE·REPLICATION 권한은 없음 |
| [운영 설정 예시](../deploy/.env.production.example) | 실제 secret 없는 호스트명·이미지·네트워크·secret 파일 경로와 안전한 OFF 기본값 |
| [QA override](../deploy/compose.qa.yaml)·[QA Caddy](../deploy/caddy/Caddyfile.qa) | localhost 전용 18080/18443와 Caddy 내부 CA. 실제 운영에 사용하지 않는 시험 설정 |
| [통합 검증 실행기](../scripts/Test-ProductionContainers.ps1)·[HTTP 검증](../scripts/production-container-qa.mjs) | 무작위 합성 계정·고유 QA project/DB/볼륨·CA 신뢰·API·재시작·별도 DB 복원·소유권 확인 후 정리 |

개발 `compose.yaml`과 Start-Backend/Frontend는 바꾸지 않는다. 기반 이미지는 버전과 공개 manifest digest를 고정하고 실제 비밀값 없이 빌드한다. 앱에는 실행 시 `/run/secrets` 파일만 선택적으로 제공하며 Frontend/Caddy에는 DB·VAPID 비밀 파일을 주지 않는다. Backend entrypoint가 secret 파일을 프로세스 환경으로 읽지만 값은 argv·이미지·로그에 쓰지 않는다.

Backend의 Linux builder는 고정 Temurin JRE 기반에서 Ubuntu 서명 검증 저장소의 `openjdk-21-jdk-headless=21.0.12.1+1-1~22.04.4`를 설치한다. 공식 JDK 이미지의 대용량 레이어 전송이 반복 정체되어 선택한 경로이며, 최종 runtime은 고정 Temurin JRE다. 고정 APT revision이 저장소에서 사라지면 빌드는 실패하므로 갱신 때 새 revision과 이미지의 재검증이 필요하다. Gradle 다운로드 시간 제한·재시도 조정은 Docker builder 안의 복사본에만 적용하며 저장소 wrapper의 배포 URL·checksum은 유지한다.

PostgreSQL 관리 암호와 앱 암호를 분리한다. init 스크립트는 이미 초기화된 볼륨에서는 재실행되지 않으므로 파일 변경만으로 기존 DB 암호가 회전하지 않는다. Java·Next·Caddy root filesystem은 read-only이며 필요한 `/tmp`, Next cache와 DB/인증서 볼륨만 쓰기를 허용한다. health 명령의 curl/Node/wget은 각 이미지에 실제로 포함한다.

## 합성 검증 실행

필요한 환경은 Linux Docker engine, Compose의 `!override` 지원, PowerShell 7.2 이상, Node 24다. 기존 3000/8080/5432 대신 localhost 18080/18443을 사용한다. 다른 포트가 필요하면 두 시험 포트 인자를 명시한다.

```powershell
# 저장소 루트에서. 새 합성 환경만 생성하고 검증 후 그 환경을 정리한다.
pwsh -NoProfile -File ./scripts/Test-ProductionContainers.ps1

# 동일 소스의 세 이미지가 이미 빌드됐을 때만 빌드를 생략한다.
pwsh -NoProfile -File ./scripts/Test-ProductionContainers.ps1 -SkipBuild
```

발행 준비 도구는 선택적 `-ResultPath`로 `.local/production-image-release-qa-<UUID>.json`의 새 결과 파일을 받는다. 이는 비민감 검사 결과만 내보내는 인터페이스이며 경로 이탈·symlink·기존 파일 덮어쓰기를 거부한다. 기본 QA 명령과 57개 검사는 유지한다. dump·세션·CA가 있는 원래 QA 디렉터리는 발행 artifact에 포함하지 않는다.

검증기는 `jeongeum-prod-qa-<무작위 ID>`만 사용하고 기존 개발 `.env`·비밀 파일·DB를 읽지 않는다. inherited 앱/DB/Compose 변수로 다른 대상으로 바뀌지 않게 분리한다. 컨테이너·볼륨·네트워크의 프로젝트 이름과 라벨을 확인하며 개발 이름/외부 볼륨을 정리 대상으로 받지 않는다. CA 인증서는 해당 QA Caddy에서 복사해 Node HTTPS 검증기에만 전달한다. 시스템 신뢰 저장소 변경·TLS 검증 생략·공개 ACME 발급은 하지 않는다.

비공개 시험 산출물은 Git 제외 `.local/production-qa-*`에 남는다. 합성 비밀번호·세션 쿠키·DB dump가 포함되므로 공개하지 않는다. Linux에서는 상위 디렉터리를 0700으로 제한하고 명시적으로 mount하는 secret 파일만 컨테이너 사용자들이 읽을 수 있게 한다. Docker Compose의 파일 기반 secret은 호스트 파일 권한을 자동으로 재매핑하지 않으므로 실제 운영에서도 비root 사용자 읽기 권한과 호스트 상위 디렉터리 보호를 함께 확인해야 한다.

검증 범위는 다음과 같다.

- 운영 이미지 기동, 비root 앱/프록시·비superuser DB 앱 역할, V1~V12·worker OFF·호스트 비공개 포트.
- 실제 Caddy HTTPS의 CA 신뢰, HTTP 리다이렉트, API prefix·보안/캐시 헤더, Secure·HttpOnly·SameSite=Lax·90일 쿠키, CSRF·익명/회원/관리자 권한.
- 합성 명단의 관리자 사전 지정→초대 가입, 가입 계정 역할 변경, 팀/팀장과 예약 생성·재확인·충돌·변경·취소.
- 앱 컨테이너 종료·재시작 전후 전체 테이블 행·세션·시퀀스·Flyway 대조와 기존 로그인/API 동작.
- 두 종료 시점에서 앱/프록시 3개의 정지 상태·OOM 없음·종료 코드 0/143을 확인하며 강제 종료 137은 실패로 처리.
- 합성 DB의 논리 dump를 같은 **격리 QA PostgreSQL 클러스터의 별도 DB**로 복원해 앱 역할 소유권/행·시퀀스 대조 후 실제 Backend를 복원 DB에 연결해 세션·API 확인.

마지막 항목은 서버 외부 암호화 백업, 독립 엔진 재구성이나 실제 운영 재해 복구 시험이 아니다. 기존 실기기 Push 결과와도 구분하며, 이 시험의 외부 Push와 모든 자동 worker는 OFF다. 브라우저·스마트폰 UI 시험을 HTTP 클라이언트 결과로 대체하지 않는다.

## 실제 운영 적용 전에

1. 사전 구성안의 구매·이전·공개 승인 범위를 확인한다. 검증한 이미지에 release tag/digest를 지정하고 운영 설정 예시를 Git 밖에 복사한다. `example.invalid`와 `REPLACE_WITH_TESTED_RELEASE`는 실제 설정이 아니다.
2. 운영 hostname·인증서 연락처·비밀 파일·독립 프로젝트/DB/볼륨을 준비한다. 네트워크 CIDR이 기존 네트워크와 겹치지 않게 하고 `CADDY_INTERNAL_IP`와 `APP_TRUSTED_PROXY_REGEX`를 정확히 일치시킨다.
3. 첫 구성은 DNS-only의 직접 Caddy HTTPS다. Cloudflare Proxy를 켜는 경우 별도의 신뢰 IP/원본 TLS/캐시 검증이 필요하며 현재 설정을 CDN 신뢰 구성으로 간주하지 않는다.
4. 개발 데이터 이전은 별도 새 V12 백업·원본 보존·운영 DB 복원·행/권한 대조가 필요하다. 기존 관리자를 다시 bootstrap하지 않는다. 포함된 bootstrap 지원은 새 빈 DB 초기화와 합성 검증용이다.
5. worker/Push OFF 상태로 기동하고 이전 origin 구독·대기 작업을 처리하는 승인된 절차를 준비한다. 현재 Docker 자산 구현은 실제 데이터의 구독 정리 도구나 자동 VAPID 키 회전을 제공하지 않는다.
6. 유효한 운영 VAPID와 Push 기능 ON·Push worker OFF의 재기동 후 새 origin의 로그인/설치/사용자 구독을 확인한다. 상태 파일 수정만으로 live flags가 바뀌지 않는다. 대기 outbox를 검토하고 데이터 worker와 발송 worker를 단계별로 활성화한다.
7. 공개 CA·실제 도메인·PC/iPhone/Android·재시작·외부 백업/복원·메모리/지연·rollback·운영 관측 게이트를 실제 환경에서 통과한 뒤 운영 URL과 개인 초대 코드를 전달한다.

세션·CSRF·예약 규칙이나 운영 Flyway 파일을 바꾸지 않는다. 컨테이너 단독 구성으로 외부 백업/알림 관측·이미지 registry 발행·원클릭 배포/롤백을 완료한 것으로 주장하지 않는다.

## 실행 결과

2026-10-17 로컬 Linux/amd64 Docker에서 `Test-ProductionContainers.ps1 -SkipBuild`를 실제 실행해 **57개 검사 통과, 종료 코드 0**을 확인했다. 별도 project는 `jeongeum-prod-qa-2857a561fdc8`다.

| 검증 | 실제 결과 |
|---|---|
| Backend 이미지 | Java 21 컴파일·bootJar 성공. 이미지 ID `39357c661b63`·UID 10001. 빌드 입력 180개 SHA 재대조 변경 0, 기존 Java/wrapper 변경 0 |
| Frontend 이미지 | Linux standalone 빌드 20 route·타입·lint·23개 파일 334건 통과. 이미지 ID `319953f5e7d5`·UID 1000. 입력 166개 SHA 일치 |
| Caddy 이미지/설정 | 이미지 ID `4b59e97e16ba`·UID 10001, 운영/QA 설정 validate 성공, 내부 CA의 실제 HTTPS·리다이렉트·정적 파일·보안/캐시 헤더 확인 |
| HTTPS API | 24개 검사 통과. 90일 Secure 쿠키·CSRF·권한·관리자 가입 전/후 지정·팀/팀장·예약 중복/충돌/변경/취소·Push OFF 포함 |
| 종료·재시작 | 앱/프록시 3개 모두 OOM/강제 종료 없음. 39개 테이블 전체 행·29개 시퀀스·세션·Flyway V1~V12 일치, 로그인/권한/예약 조회 6개 검사 통과 |
| 별도 DB 복원 | 합성 custom dump 166,041bytes를 같은 QA 클러스터의 별도 DB에 복원. 전체 행·시퀀스·세션·Flyway·앱 소유권 일치. 실제 Backend를 복원 DB에 연결한 로그인/예약 검사 6개 통과 |
| 정리 | 소유 라벨 검사 후 해당 QA 자원만 제거. 종료 후 독립 조회에서도 QA 컨테이너·볼륨·네트워크 각 0개 |
| 기존 개발 환경 | DB에 접근하지 않음. 개발 PostgreSQL ID·시작 시각·기존 데이터 볼륨 동일·healthy. 기존 8080/3000 리스너의 PID 8844/6592 유지. 기존 업무 코드/UI·개발 Compose·기동 스크립트 변경 없음 |

비공개 증빙은 `.local/production-qa-2857a561fdc8/result.json`과 해당 폴더의 manifest/합성 dump, `.local/backend-production-image-build-final.json`, `.local/frontend-production-image-qa-886982f2/validation-final.json`에 보관하며 Git에 포함하지 않는다. 초기 네트워크 전송 정체는 공개 캐시를 보존한 재시도로 해결했고 TLS/서명/checksum 검증을 우회하지 않았다. 독립 소스 검토에서 발견한 QA phase 파일 경로 제한과 PowerShell CIDR 산술 괄호 오류는 실행 전에 수정하고 잘못된 phase의 파일 미생성·구문/산술 검사를 확인했다.

CI에는 기존 Backend/Frontend 검사를 유지하고 동일 합성 검증 실행 job을 추가했다. **위 표는 커밋 전 로컬 검증 결과**다. Backend 전체 368건은 직전 main CI의 결과이며 이 표의 새 Backend 실행은 Linux JAR 빌드와 57개 통합 검사다. 후속 승인으로 게시하는 초안 PR에서 현재 커밋의 원격 Backend/Frontend/운영 컨테이너 결과를 별도로 확인한다. 실제 VPS·공개 CA·실기기 Push·외부 백업/독립 엔진 복구·부하 시험·registry 발행·운영 배포는 미실행이다.
