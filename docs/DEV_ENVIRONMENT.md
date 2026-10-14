# 로컬 개발환경

확인일: 2026-10-09 / 실행 환경: Windows PowerShell, 프로젝트 루트 `C:\Users\swj12\Desktop\jeongeum-band-scheduler`

Docker Desktop의 WSL 2 Linux 엔진과 개발용 PostgreSQL을 사용한다. 이후 생성한 Spring·Next.js 앱의 실행·검증은 [INITIAL_SETUP.md](INITIAL_SETUP.md)를 따른다. `tools/environment-check`는 Java·Docker·PostgreSQL 실행을 확인하는 독립 도구이며 서비스의 업무 코드나 migration은 포함하지 않는다.

## 같은 네트워크의 스마트폰에서 접속

기본 프론트엔드 실행은 기존처럼 `127.0.0.1:3000`으로 제한한다. 스마트폰 확인 때는 PC와 스마트폰을 같은 공유기 네트워크에 연결하고 기존 프론트엔드만 종료한 뒤 프로젝트 루트에서 실행한다.

```powershell
# PC의 Wi-Fi IPv4 주소로 바꿔 입력한다.
./scripts/Start-Frontend.ps1 -Hostname 0.0.0.0 -AllowedDevOrigins '192.168.0.10'
```

스마트폰 브라우저에서 `http://PC의-Wi-Fi-IP:3000`으로 접속한다. `AllowedDevOrigins`에는 URL이나 포트가 아닌 현재 PC의 IP만 입력하며, Next.js 개발 화면과 자동 갱신 요청을 허용한다. IP가 바뀌면 새 IP로 다시 실행한다. PC의 `http://127.0.0.1:3000`도 함께 사용할 수 있다. 스마트폰은 별도 쿠키를 사용하므로 기존 계정으로 로그인한다.

Windows 방화벽에는 관리자 권한으로 현재 Wi-Fi 인터페이스·PC IPv4 주소·TCP 3000·같은 네트워크 대역만 허용하는 인바운드 규칙을 추가한다. 네트워크의 Public/Private 분류는 변경하지 않는다. 백엔드와 PostgreSQL은 localhost 바인딩을 유지하며 `/api`는 기존 프론트엔드 프록시로 전달한다. 공유기 포트 포워딩은 설정하지 않는다. 기본 PC 전용 실행으로 돌아갈 때는 프론트엔드 종료 후 `./scripts/Start-Frontend.ps1`을 실행한다.

## 설치 및 확인 상태

| 항목 | 확인 결과 |
|---|---|
| JDK | Temurin 21.0.11, `java`·`javac`·`JAVA_HOME`의 설치 경로 일치 |
| Node.js / npm | 24.18.0 / 11.16.0, `.node-version`에 Node 버전 기록 |
| IDE | IntelliJ IDEA와 VS Code 실행 파일 설치 확인 |
| Docker | WSL 2 Linux 엔진 연결 성공, Engine 29.8.2 |
| Compose | 5.5.1 |
| PostgreSQL | 17.11-bookworm, 이미지 digest 고정, healthy |
| Gradle | 9.8.1 Wrapper 공식 SHA-256 검증 및 Java 컴파일 성공; 전역 설치 불필요 |
| Testcontainers | 2.0.5, 임시 PostgreSQL 실제 기동·JDBC 접속·commit·rollback 검증 1건 통과 |

시작 전 Docker Desktop이 실행되지 않아 엔진 pipe가 없었다. 설치된 Desktop을 시작해 연결을 복구했다. 검증 중 한 차례 연결 중단 후 엔진이 다시 running인 것을 확인하고 데이터 유지 검사를 재실행해 통과했다. WSL 배포판 재설치나 기존 Docker 데이터 초기화는 수행하지 않았다.

## 실행과 중지

프로젝트 루트에서 실행한다.

```powershell
docker desktop start --detach
./scripts/Initialize-DevEnvironment.ps1
docker compose up -d --wait --wait-timeout 90 postgres
./scripts/Test-DevEnvironment.ps1
```

초기화 스크립트는 `.env.example`을 바탕으로 없는 `.env`만 생성하고 `secrets/postgres-password.txt`에 무작위 비밀번호를 만든다. 다시 실행해도 기존 파일과 비밀번호를 유지한다. 실제 `.env`와 `secrets`는 Git에서 제외된다. 예시 파일에는 비밀번호가 없다.

작업을 마치면 컨테이너를 중지한다.

```powershell
docker compose stop postgres
```

다음 작업 때 `docker compose up -d --wait --wait-timeout 90 postgres`로 다시 시작한다. `docker compose down`으로 컨테이너와 네트워크를 정리해도 named volume의 DB 데이터는 유지된다.

## DB 연결

| 값 | 기본 설정 |
|---|---|
| Host / Port | `127.0.0.1:5432` |
| Database / User | `jeongeum` / `jeongeum` |
| Password | 로컬 `secrets/postgres-password.txt`에서 본인이 확인해 IDE에 입력 |
| JDBC URL | `jdbc:postgresql://127.0.0.1:5432/jeongeum` |
| 데이터 볼륨 | `jeongeum-dev_postgres_data` |
| DB 시간대 | UTC; 애플리케이션 예약 정책은 Asia/Seoul 기준으로 구현 |

포트는 Windows localhost에만 바인딩한다. 다른 PC나 운영 서버의 접속 설정으로 사용하지 않는다. 포트 충돌 시 `.env`의 `POSTGRES_PORT`를 빈 포트로 변경하고 컨테이너를 다시 생성한다. 이미 초기화된 DB의 사용자·DB 이름·비밀번호는 `.env`나 비밀번호 파일을 바꾸는 것만으로 갱신되지 않으므로 처음 생성한 값을 유지한다.

PostgreSQL 17 데이터 경로는 `/var/lib/postgresql/data`다. image는 tag와 digest를 함께 고정한다. 메이저 버전 변경은 볼륨 경로와 데이터 migration을 검토하는 별도 작업으로 처리한다. [공식 이미지 안내](https://hub.docker.com/_/postgres), [PostgreSQL 지원 정책](https://www.postgresql.org/support/versioning/)

## 환경 검증

`Test-DevEnvironment.ps1`은 Linux 엔진, Compose 설정, DB healthy, 비밀번호를 이용한 TCP 인증과 실제 SQL 트랜잭션을 검사한다. 비밀번호는 컨테이너 내부의 secret 파일에서 읽으며 명령 인수나 검사 출력에 넣지 않는다.

이번 설정에서는 임시 schema와 표식을 만든 뒤 컨테이너를 재생성하고 같은 표식이 남아 있는 것을 확인했다. 검증 schema는 제거했으며 애플리케이션 테이블은 생성하지 않았다. Windows에서 `127.0.0.1:5432` TCP 연결도 확인했다.

Java Testcontainers 검증 명령은 다음과 같다.

```powershell
Push-Location ./tools/environment-check
try {
    ./gradlew.bat --gradle-user-home ./.gradle/user-home --no-daemon test --rerun-tasks
} finally {
    Pop-Location
}
```

이 도구는 별도의 임시 PostgreSQL, 무작위 비밀번호와 임의 포트를 사용한다. 개발 DB 비밀번호나 개발 볼륨을 사용하지 않는다. Docker Desktop을 기본 탐지하고 Ryuk의 자동 정리를 유지한다. [Testcontainers 실행 환경](https://java.testcontainers.org/supported_docker_environment/)

2026-10-09 실제 실행에서 Java 21 컴파일과 환경 검증 1건이 성공했고 건너뛴 검증은 없었다. 최초 실행의 tag+digest 이름 해석 오류는 Testcontainers 참조를 같은 공식 이미지의 digest 형식으로 수정한 뒤 재실행하여 해결했다. Compose와 테스트는 같은 이미지 내용을 사용한다.

Wrapper는 공식 배포 SHA-256을 확인한다. 의존성 버전은 도구의 build와 lock 파일에 고정한다. 실제 업무 테스트는 앱·Flyway를 생성한 후 [TEST_PLAN.md](TEST_PLAN.md)의 PostgreSQL 경합·권한·롤백 기준에 따라 작성한다. [Gradle Java 호환성](https://docs.gradle.org/current/userguide/compatibility.html)

## IDE 및 다음 단계

IntelliJ에서 Java 프로젝트를 열 때 Project SDK와 Gradle JVM을 기존 JDK 21로 지정하고 Gradle 실행은 Wrapper를 사용한다. VS Code와 IntelliJ 모두 `.editorconfig`의 UTF-8·들여쓰기·줄바꿈 규칙을 따른다. 별도 전역 Gradle·Windows PostgreSQL·Ubuntu 배포판 설치는 필요하지 않다.

`backend`와 `frontend` 초기 구성 이후 버전·API 연결·앱 실행 및 CI 검증은 [INITIAL_SETUP.md](INITIAL_SETUP.md)에 기록한다. 도메인·운영 서버·배포는 아직 준비하지 않았다.

## 2026-10-10 실제 실행환경 재점검

사용자가 실제 로컬 환경 점검 후 예약 기본 구현을 요청했다. Docker Desktop을 시작하자 기존 PostgreSQL 컨테이너가 healthy로 복구됐으며 `jeongeum-dev_postgres_data` 볼륨을 유지했다. 점검 당시 개발 DB에는 Flyway V1만 적용돼 있었다.

개발 DB를 변경하기 전에 Git에서 제외되는 `backups/local-before-reservations-20261010-110833.dump`에 custom-format 논리 백업을 생성했다. `pg_restore --list`로 형식을 확인하고 SHA-256을 기록했다. Windows 텍스트 리다이렉션 대신 컨테이너 내부 파일 생성과 `docker cp`를 사용했다. 외부 백업·복원 검증 완료를 주장하지 않는다.

기존 [Start-Backend.ps1](../scripts/Start-Backend.ps1) 실행으로 V2·V3를 적용했고, [Start-Frontend.ps1](../scripts/Start-Frontend.ps1) 실행 후 [Test-ApplicationConnection.ps1](../scripts/Test-ApplicationConnection.ps1)이 통과했다. 실제 Next.js → 백엔드 → DB health, no-store, HttpOnly 세션, CSRF와 익명 보호 API 거절을 확인했다. 실제 개발 DB의 회원과 관리자는 0명이므로 최초 관리자 생성은 [Initialize-Admin.ps1](../scripts/Initialize-Admin.ps1)의 숨김 비밀번호 입력 절차로 진행한다. 자동 기본 관리자·실제 회원 fixture를 추가하지 않았다.

V4 직전 V3 DB를 `backups/local-v3-before-v4-20261010-112854.dump`에 추가 백업하고 형식·SHA-256·Git 제외를 확인했다. 실제 백엔드 재시작으로 V4를 적용했으며 Flyway V1~V4는 모두 성공 상태다. 개발 DB는 사용자·예약 각각 0건, 기본 블록 10개·악기 6개다. 합성 QA 계정과 예약을 개발 DB에 넣지 않았다.

최종 프론트 `127.0.0.1:3000`·백엔드 `127.0.0.1:8080`·기존 PostgreSQL `127.0.0.1:5432`를 실행하고 앱 연결 검사를 다시 통과했다. 임시 QA 서버와 컨테이너만 중지했다. 예약 PostgreSQL 통합 테스트·최종 빌드·화면 검증 결과와 실제 제한은 [RESERVATION_FOUNDATION.md](RESERVATION_FOUNDATION.md)에 구분해 기록한다. 사용자 GitHub README, 기존 소스·설정·비밀번호 파일과 Docker 볼륨을 보존했다.
