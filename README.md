<div align="center">
  <img src="frontend/public/jeongeum-logo.jpg" alt="정음 JEONGEUM 로고" width="140" />

  # 정음 | JEONGEUM

  **JEONGEUM · SENTIMENTAL MUSIC**

  정음 밴드 동아리를 위한 합주팀 관리 및 합주실 예약 웹 서비스

  **Java · Spring Boot · PostgreSQL · Next.js · TypeScript**

  [기능 현황](#-구현-현황) · [기술 스택](#-기술-스택) · [프로젝트 구조](#-프로젝트-구조) · [로컬 실행](#-로컬-실행) · [문서](#-기술-문서)
</div>

---

## 🎸 프로젝트 소개

**JEONGEUM Band Scheduler**는 약 45명이 활동하는 밴드 동아리 **정음(JEONGEUM)**의 회원·합주팀·연습곡과 합주실 이용 일정을 하나의 서비스에서 관리하기 위한 프로젝트입니다.

동아리에서 실제로 사용할 수 있는 서비스를 구축하는 동시에, **Java/Spring 기반 백엔드 설계, 데이터 무결성, 동시성 제어, 인증·인가, 테스트 및 운영**을 검증하는 것을 목표로 합니다.

- **사용 대상:** 정음 동아리의 전체 관리자, 팀장, 일반 회원
- **개발 형태:** Spring Boot 백엔드와 Next.js 프론트엔드를 분리한 단일 Git 저장소
- **UI 방향:** 동일한 코드베이스에서 모바일·데스크톱에 적합한 반응형 화면 제공
- **현재 단계:** 인증·회원 명단·팀·곡 관리 구현 완료 / 예약 및 운영 기능 개발 예정
- **배포 상태:** 로컬 개발·테스트 단계. 운영 도메인과 서버는 아직 연결하지 않음

> **구현 상태 안내:** 현재 저장소에는 회원·팀 관리 기능이 구현되어 있지만, 핵심 합주실 예약 기능과 PWA 설치·Web Push는 아직 구현 완료 상태가 아닙니다. 아래의 **구현 완료**와 **개발 예정**을 구분해 주세요.

## ✅ 구현 현황

| 영역 | 상태 | 주요 기능 |
| --- | --- | --- |
| 개발 기반 | ✅ 구현 완료 | Docker Compose 개발용 PostgreSQL, Flyway, 프로젝트 실행 스크립트, GitHub Actions CI |
| 초대·인증 | ✅ 구현 완료 | 관리자 발급 개인별 일회용 초대, 초대 가입, 로그인·로그아웃, 세션·CSRF 보호 |
| 로그인 유지 | ✅ 구현 완료 | 마지막 인증 요청 후 **90일** 유지, 유효한 인증 요청 때 세션·쿠키 만료 연장 |
| 회원·명단 | ✅ 구현 완료 | 가입 전 명단, 초대를 통한 계정 연결, 기존 계정 수동 연결, 회원·관리자 역할 관리 |
| 합주팀·곡 | ✅ 구현 완료 | 정규조·추가조, 복수 팀 소속, 팀장 지정, 팀별 연습곡 등록·조회·수정·삭제 |
| 권한·무결성 | ✅ 구현 완료 | 전역 관리자와 팀별 팀장 권한 분리, 트랜잭션·잠금, DB 제약 및 버전 충돌 처리 |
| 웹 화면 | ✅ 현재 기능 구현 | 로그인·가입·회원 홈·관리자 명단/초대·팀 목록/상세, 기본 반응형 레이아웃 |
| 합주실 예약 | 📋 개발 예정 | 고정 시간 블록, 팀 독점 예약·악기별 개인 예약, 예약 충돌·일일 한도 처리 |
| 일정 변경·교환 | 📋 개발 예정 | 예약 취소·변경, 반복 고정 배정, 예약 간 교환 및 승인 |
| 공지·알림 | 📋 개발 예정 | 전체 공지, 사이트 내 알림함, 시작 전 알림, Web Push |
| PWA·운영 | 📋 개발 예정 | 홈 화면 설치, 기본 오프라인 안내, HTTPS 배포·백업·복원·실기기 검증 |

### 권한 모델

- **전체 관리자(`GLOBAL_ADMIN`)**: 동아리 명단, 회원·역할, 팀 구성·팀장, 초대와 팀별 곡 관리
- **팀장(`LEADER`)**: 자신이 담당하는 팀의 연습곡 관리. 다른 팀이나 전체 관리자 권한은 부여하지 않음
- **일반 회원(`MEMBER`)**: 본인 계정과 팀 정보 조회, 권한 범위에서 팀·곡 목록 확인

한 회원은 여러 정규조와 추가조에 동시에 속할 수 있습니다. **가입 전 명단과 로그인 계정은 별도 데이터**로 관리하며, 이름이 같다는 이유만으로 계정을 자동 연결하지 않습니다.

## 🛠 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.1, Gradle 9.8.1 |
| Backend 주요 구성 | Spring MVC, Spring Security, Spring Session JDBC, Spring Data JPA, Bean Validation |
| Database | PostgreSQL 17.11, Flyway V1~V3 |
| Frontend | Next.js 16.4.0 (App Router), React 19.3.0, TypeScript 6.0.3 |
| UI·데이터 | Tailwind CSS 4.3.3, TanStack Query 5.104.1 |
| 테스트 | JUnit 5, Testcontainers(PostgreSQL), Vitest |
| 개발·CI | Docker Compose, PowerShell, GitHub Actions |

백엔드는 Spring Boot BOM·`gradle.lockfile`, 프론트엔드는 `package-lock.json`을 이용해 의존성을 고정합니다. 로컬 데이터베이스는 Docker에서 실행하며, 애플리케이션은 개발 서버 또는 IDE에서 실행합니다.

## 🗂 프로젝트 구조

```text
jeongeum-band-scheduler/
├── .github/
│   └── workflows/ci.yaml               # Backend·Frontend CI
├── backend/
│   ├── src/main/java/kr/jeongeum/scheduler/
│   │   ├── auth/                        # 로그인·가입·세션 인증
│   │   ├── common/                      # 공통 응답·보안·동시성 제어
│   │   ├── health/                      # DB 연결 상태 확인
│   │   ├── invitation/                  # 개인 초대 발급·소비·해지
│   │   ├── member/                      # 회원 조회·역할
│   │   ├── roster/                      # 가입 전 동아리 명단
│   │   └── team/                        # 합주팀·소속·연습곡
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_spring_session.sql
│   │   ├── V2__create_members_and_invitations.sql
│   │   └── V3__create_roster_teams_and_songs.sql
│   ├── src/test/                        # 통합 테스트
│   └── build.gradle.kts
├── frontend/
│   ├── public/jeongeum-logo.jpg
│   ├── src/app/                         # 메인·로그인·가입·관리자·팀 화면
│   ├── src/components/                  # 공통 UI
│   ├── src/features/                    # 인증·명단·팀·상태 관련 로직
│   ├── src/lib/                         # API 클라이언트·Query 설정
│   ├── src/providers/
│   └── package.json
├── scripts/                              # 로컬 환경·앱 실행·검증
├── tools/environment-check/             # Java·Docker·DB 환경 검증
├── docs/                                # 정책·API·ERD·보안·테스트·배포 문서
├── compose.yaml                         # 로컬 PostgreSQL 17.11
├── AGENTS.md                            # 프로젝트 작업 규칙
└── README.md
```

## 🚀 로컬 실행

**요구 환경:** Windows PowerShell, Java 21, Node.js 24.x / npm, Docker Desktop(WSL 2 Linux 컨테이너). 아래 명령은 **저장소 루트**에서 실행합니다.

### 1. 저장소 및 DB 준비

```powershell
git clone https://github.com/developer-sw/jeongeum-band-scheduler.git
cd jeongeum-band-scheduler

# 최초 실행 시 .env와 Git에서 제외되는 DB 비밀번호 파일 생성
.\scripts\Initialize-DevEnvironment.ps1

# 로컬 PostgreSQL 시작
docker compose up -d --wait postgres
```

기존 개발 환경이 있다면 `.env`, `secrets/postgres-password.txt`, Docker 데이터 볼륨을 보존해야 합니다. **비밀번호 파일·실제 회원 정보는 Git에 추가하지 마세요.**

### 2. 프론트엔드 의존성 설치

```powershell
cd frontend
npm.cmd ci
cd ..
```

### 3. 백엔드 실행 — 터미널 A

```powershell
.\scripts\Start-Backend.ps1
```

기본 백엔드 주소는 `http://127.0.0.1:8080`입니다. 이 스크립트는 개발 DB 접속 정보를 프로세스 환경변수로 전달하고 `local` 프로필로 `bootRun`을 실행합니다. **최초 실행 시 Flyway가 아직 적용되지 않은 마이그레이션을 개발 DB에 적용할 수 있습니다.**

### 4. 프론트엔드 실행 — 터미널 B

```powershell
.\scripts\Start-Frontend.ps1
```

웹 화면: **http://127.0.0.1:3000**

Next.js는 브라우저의 동일 출처 `/api/*` 요청을 로컬 Spring Boot의 `/api/*`로 전달합니다. 이미 개발 서버가 실행 중이면 중복 실행하지 마세요.

### 5. 연결 확인

```powershell
.\scripts\Test-ApplicationConnection.ps1
```

이 스크립트는 DB Health, Next.js API 전달, 세션 쿠키 및 CSRF/미인증 응답을 검사합니다. 각 앱은 해당 터미널에서 `Ctrl + C`로 종료할 수 있습니다.

### 최초 관리자 계정

공용 기본 계정은 제공하지 않습니다. **회원 테이블이 비어 있을 때만** 최초 전체 관리자를 명시적으로 생성할 수 있습니다. DB 준비와 마이그레이션을 확인한 뒤 다음 절차를 따르세요.

```powershell
cd backend
.\gradlew.bat --gradle-user-home .\.gradle\user-home --no-daemon bootJar
cd ..

.\scripts\Initialize-Admin.ps1
```

관리자 비밀번호는 실행 중 숨김 입력으로 받습니다. 자세한 제한과 절차는 [인증 구현 문서](docs/AUTHENTICATION.md)를 확인하세요.

## 🔐 설계에서 중점적으로 다룬 문제

**1. 초대와 계정 연결의 원자성**  
개인별 초대를 발급·소비하고, 가입 전 명단과 생성된 계정을 연결하는 과정을 트랜잭션으로 처리합니다. 기존 회원은 관리자가 명시적으로 연결하며, 이름 유사성으로 자동 병합하지 않습니다.

**2. 현재 상태 기준 인가**  
로그인 세션에는 회원 식별자를 저장하고, 회원의 활성 상태와 관리자·팀장 권한은 요청 시 현재 DB 상태를 바탕으로 검증합니다. 팀장은 해당 팀에만 곡 관리 권한을 가집니다.

**3. 데이터 무결성과 경합 제어**  
PostgreSQL 제약·행 잠금·공통 변경 잠금과 버전 충돌 검증을 사용해 명단 중복 연결, 권한 변경 경합, 마지막 활성 관리자 강등, 초대 중복 소비 등을 방지합니다.

**4. 세션 보안과 지속 로그인**  
Spring Session JDBC를 사용하며 `HttpOnly`·`SameSite=Lax` 세션 쿠키와 CSRF 보호를 적용합니다. 로그인 상태는 **마지막 유효 인증 요청부터 90일간 유지**되며 요청 시 만료 시각을 연장합니다. 운영 HTTPS에서는 `Secure` 쿠키를 사용하도록 설계했습니다.

## 🧪 테스트 및 CI

- **Backend:** PostgreSQL Testcontainers 기반 인증·명단·팀·권한·경합 통합 테스트. PR #5 기준 **총 52개 테스트 검증** 및 JAR 빌드 성공
- **Frontend:** Vitest **29개 테스트**, TypeScript 검사, ESLint, Next.js 프로덕션 빌드 통과
- **GitHub Actions:** Ubuntu에서 백엔드 테스트·빌드와 프론트엔드 의존성 감사·타입·lint·테스트·빌드를 자동 실행
- **최신 `main` CI:** [2026-10-10 기준 성공한 실행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/37962146279)

검증 수치는 해당 커밋의 기록입니다. **예약 경합·실서비스 트래픽·실기기 PWA/Push·운영 백업 복원**은 아직 검증 완료 범위가 아닙니다.

로컬 재검증:

```powershell
# backend 폴더에서 (Docker 실행 필요)
.\gradlew.bat --gradle-user-home .\.gradle\user-home --no-daemon test bootJar

# frontend 폴더에서
npm.cmd run typecheck
npm.cmd run lint
npm.cmd run test
npm.cmd run build
```

## 📅 예약 정책 및 향후 개발

설계가 확정된 예약 정책 중 주요 내용은 다음과 같습니다. **아래 정책은 아직 구현 완료를 뜻하지 않습니다.**

- **운영 시간:** 매일 12:00~24:00. 12:00~18:00은 60분 단위 6개, 18:00~24:00은 90분 단위 4개 블록
- **예약 가능 날짜:** 한국 시간 기준 오늘부터 14일 뒤까지(양 끝 포함)
- **팀 예약:** 정규조·추가조 모두 합주실 전체를 독점 사용
- **개인 예약:** 보컬·건반·일렉기타·베이스·드럼·통기타의 악기별 공유 이용
- **추가 예정:** 60분 예약 한도, 반복 고정 배정, 취소·변경·교환, 악기 고장 관리, 공지·알림함·Web Push, 모바일 일별·PC 주간 시간표

자세한 제약, 예외, 동시성 모델은 [예약 규칙](docs/RESERVATION_RULES.md)과 [데이터 모델](docs/ERD.md)에 정리했습니다.

## 📚 기술 문서

| 문서 | 내용 |
| --- | --- |
| [PROJECT_CONTEXT](docs/PROJECT_CONTEXT.md) | 요구사항·역할·운영 정책 및 설계 방향 |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | 시스템 구성, 인증, 동시성 및 알림 구조 |
| [API](docs/API.md) | API 계약과 권한·오류 응답 |
| [ERD](docs/ERD.md) | 현재·계획 데이터 관계와 제약 |
| [AUTHENTICATION](docs/AUTHENTICATION.md) | 초대·가입·로그인, 90일 세션, 최초 관리자 절차 |
| [MEMBER_TEAM_MANAGEMENT](docs/MEMBER_TEAM_MANAGEMENT.md) | 명단·계정 연결, 복수 팀·팀장·곡 관리 및 테스트 |
| [RESERVATION_RULES](docs/RESERVATION_RULES.md) | 예약 블록, 한도, 반복·변경·교환 정책 |
| [UX](docs/UX.md) | 데스크톱/모바일 화면 계획과 브랜드 |
| [TEST_PLAN](docs/TEST_PLAN.md) | 기능·보안·경합·운영 검증 항목 |
| [INITIAL_SETUP](docs/INITIAL_SETUP.md) | 백엔드·프론트엔드 초기 설정과 검증 |
| [DEV_ENVIRONMENT](docs/DEV_ENVIRONMENT.md) | 로컬 DB·Docker·JDK 환경 안내 |
| [DEPLOYMENT](docs/DEPLOYMENT.md) | HTTPS 배포·백업·운영 비용 계획 |
| [AGENTS.md](AGENTS.md) | 개발 에이전트 작업 및 변경 보존 규칙 |

## 🔖 개발 이력

| PR | 주요 변경 |
| --- | --- |
| [#1](https://github.com/developer-sw/jeongeum-band-scheduler/pull/1) | Docker·PostgreSQL 개발환경 구성 |
| [#2](https://github.com/developer-sw/jeongeum-band-scheduler/pull/2) | Spring Boot·Next.js 초기 기반 및 CI |
| [#3](https://github.com/developer-sw/jeongeum-band-scheduler/pull/3) | 일회용 초대 가입·로그인·인가 기반 |
| [#4](https://github.com/developer-sw/jeongeum-band-scheduler/pull/4) | 회원 명단과 계정 연결, 팀·팀장·곡 관리 |
| [#5](https://github.com/developer-sw/jeongeum-band-scheduler/pull/5) | 접속 시 갱신되는 90일 로그인 유지 |

현재는 **로컬 개발 및 기능 확장 단계**이며, 동아리 대상 운영 배포·실사용 지표는 아직 없습니다. 기능과 운영 검증을 거쳐 실제 사용자에게 제공할 예정입니다.

---

<div align="center">
  <sub>Built for JEONGEUM · SENTIMENTAL MUSIC</sub>
</div>
