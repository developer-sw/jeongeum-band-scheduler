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
- **현재 단계(2026-10-14):** 인증·회원 명단·팀·곡 관리·예약·반복 고정 배정·날짜 개방·변경·교환·악기 고장·공지·알림함에 Web Push·시작 1시간 전 알림·CSRF 동시성 보완을 구현·`main` 반영. 개발 DB V10 적용과 최신 로컬 서버 기동 완료, 실기기 Push 수신은 미검증
- **현재 UI:** 다이어리 홈·달력·주간 시간표·모바일 예약 바텀시트·계정 인사말·예약 변경/교환·관리자 악기 고장·공지·개인 알림함·기기별 알림 설정. PC·모바일에서 같은 API와 예약 정책 사용
- **배포 상태:** 로컬 개발·테스트 단계. 운영 도메인과 서버는 아직 연결하지 않음

> **구현 상태 안내:** 기본 예약·취소는 [PR #6](https://github.com/developer-sw/jeongeum-band-scheduler/pull/6), 반복 고정 배정·날짜 개방·내 공연조 일정은 [PR #7](https://github.com/developer-sw/jeongeum-band-scheduler/pull/7), 다이어리 UI는 [PR #9](https://github.com/developer-sw/jeongeum-band-scheduler/pull/9), 단건 예약 변경은 [PR #10](https://github.com/developer-sw/jeongeum-band-scheduler/pull/10), 예약 교환은 [PR #12](https://github.com/developer-sw/jeongeum-band-scheduler/pull/12), 악기 고장 관리는 [PR #13](https://github.com/developer-sw/jeongeum-band-scheduler/pull/13), 전체 공지·개인 알림함은 [PR #15](https://github.com/developer-sw/jeongeum-band-scheduler/pull/15)로 `main`에 반영했습니다. Web Push·시작 1시간 전 알림·CSRF 보완은 [PR #17](https://github.com/developer-sw/jeongeum-band-scheduler/pull/17), 병합 커밋 `f39969a`로 반영했습니다. 해당 기능 커밋 `210d504`의 Push/PR CI에서 백엔드 325건·프론트엔드 214건과 양쪽 검사를 통과했습니다. 남은 범위는 탈퇴·팀 해체 예약 정리와 HTTPS·실기기 수신·운영 검증입니다.

## ✅ 구현 현황

| 영역 | 상태 | 주요 기능 |
| --- | --- | --- |
| 개발 기반 | ✅ 구현 완료 | Docker Compose 개발용 PostgreSQL, Flyway, 프로젝트 실행 스크립트, GitHub Actions CI |
| 초대·인증 | ✅ 구현 완료 | 관리자 발급 개인별 일회용 초대, 초대 가입, 로그인·로그아웃, 세션·CSRF 보호 |
| 로그인 유지 | ✅ 구현 완료 | 마지막 인증 요청 후 **90일** 유지, 유효한 인증 요청 때 세션·쿠키 만료 연장 |
| 회원·명단 | ✅ 구현 완료 | 가입 전 명단, 초대를 통한 계정 연결, 기존 계정 수동 연결, 회원·관리자 역할 관리 |
| 합주팀·곡 | ✅ 구현 완료 | 정규조·추가조, 복수 팀 소속, 팀장 지정, 팀별 연습곡 등록·조회·수정·삭제 |
| 권한·무결성 | ✅ 구현 완료 | 전역 관리자와 팀별 팀장 권한 분리, 트랜잭션·잠금, DB 제약 및 버전 충돌 처리 |
| 웹 화면 | ✅ 현재 기능 구현 | 로그인·가입·다이어리 홈·관리자 명단/초대·팀 목록/상세·합주실 시간표·고정 배정·내 공연조 일정·예약 변경/이력·교환·악기 고장 관리·공지·개인 알림함·기기별 알림 설정, 반응형 레이아웃 |
| 합주실 예약 | ✅ 구현 완료 | 고정 시간 블록, 팀 독점·악기별 개인 예약, 충돌·일일 한도·취소·이력 |
| 반복 고정 배정 | ✅ 구현·main 반영 | 관리자 정규조·추가조 배정, 적용일별 패턴 수정·종료, 회차 취소 예외, 고정 반영 후 날짜 개방·실패 재시도·재시작 복구 |
| 내 공연조 일정 | ✅ 구현·main 반영 | 현재 소속 정규조·추가조의 여러 날짜 일반/고정 확정 예약 통합 조회, 팀 필터, 날짜·시간순 목록·해당 날짜 시간표 연결 |
| 다이어리 UI | ✅ 구현·main 반영 | 따뜻한 달력·월~일 시간표·내 공연조 요약·공유 빠른 예약, 모바일 하루 카드·바텀시트·4탭, 계정 인사말·더보기 |
| 단건 예약 변경 | ✅ 구현·main 반영 | 시작 전 날짜·시간 블록·개인 악기 변경, 원본/후속 이력, 권한·충돌·한도 재검사, 실패 시 원본 보존, 고정 회차 단건 예외 |
| 예약 교환 | ✅ 구현·main 반영 | 1↔1·연속 60분 두 건↔90분 한 건의 요청·승인·거절·철회·만료·무효화, 개인 악기 배분, 실패 시 양쪽 원본 보존 |
| 악기 고장 관리 | ✅ 구현·main 반영 | 관리자 고장 등록·복구·사유/처리 이력, 현재 상태 표시, 개인 신규/실질 변경/교환의 고장 검사, 팀 합주 경고·기존 예약 보존 |
| 전체 공지 | ✅ 구현·main 반영 | 관리자 작성·수정·삭제·중요 고정, 활성 회원 목록/상세·이전 항목 조회, 최초 중요 작성 때만 알림 |
| 개인 알림함 | ✅ 구현·main 반영 | 본인 알림·미읽음 수/필터·개별/전체 읽음·이전 항목, 예약/반복 회차/교환 사건 연동·중복 방지·관련 화면 연결 |
| Web Push | ✅ 구현·실수신 미검증 | 기기 구독·해제·계정 binding, VAPID/AES128GCM 발송 요청, 대기열·재시도·만료·재시작 복구, 로그아웃 시 해당 기기만 중단 |
| 시작 1시간 전 알림 | ✅ 구현·격리 검증 | 예약·변경·취소·교환·반복 회차와 원자적 계획 연동, 현재 수신자/버전 재검사, 시간 경계·계보 중복 방지·재시작 복구 |
| 탈퇴·팀 해체 예약 정리 | 📋 개발 예정 | 개인/팀 예약·대기 교환·반복 배정·예정 알림 정리, 존속 팀 예약과 완료 이력 보존 |
| PWA·운영 | 📋 운영 검증 예정 | manifest·아이콘·Push service worker·설치 안내 구현, HTTPS 배포·실기기 설치/수신·운영 외부 백업 복원은 미검증 |

### 권한 모델

- **전체 관리자(`GLOBAL_ADMIN`)**: 동아리 명단, 회원·역할, 팀 구성·팀장, 초대·팀별 곡·반복 고정 배정·악기 고장·전체 공지 관리와 예약 변경·취소·교환
- **팀장(`LEADER`)**: 자신이 담당하는 팀의 연습곡 관리와 다른 팀원이 만든 해당 팀 예약 변경·취소·관련 교환 처리. 다른 팀이나 전체 관리자 권한은 부여하지 않음
- **일반 회원(`MEMBER`)**: 본인 개인·소속 팀 예약 생성, 본인 개인 예약과 본인이 생성한 소속 팀 예약 변경·취소·관련 교환 처리, 현재 소속 공연조 일정과 팀·곡 목록 조회

한 회원은 여러 정규조와 추가조에 동시에 속할 수 있습니다. **가입 전 명단과 로그인 계정은 별도 데이터**로 관리하며, 이름이 같다는 이유만으로 계정을 자동 연결하지 않습니다.

교환 요청에는 본인 측 전체 예약의 현재 변경 권한이 필요하며, 승인·거절에는 상대 측 예약의 현재 권한이 필요합니다. 팀원이 교환 목록을 조회할 수 있다는 이유만으로 다른 팀원이 작성한 예약의 승인 권한을 갖지는 않습니다. 교환 후 예약 주체·최초 예약자는 승인자로 바뀌지 않으며, 두 악기 개인 90분 예약을 두 60분으로 나눌 때 악기 순서는 해당 개인 예약자가 직접 선택합니다.

서로 다른 악기를 사용하는 두 개인 60분 예약을 90분 한 건과 교환하면, 결과 개인 예약은 두 악기를 90분 전체 동안 점유합니다. 이 교환 예외에서도 악기는 최대 두 종류이며 일반 신규 개인 예약은 한 종류만 선택합니다.

활성 회원은 전체 공지를 조회하고 **본인 알림만** 조회·읽음 처리합니다. 공지 작성·수정·삭제는 전체 관리자만 가능합니다. 알림을 받았다는 이유로 다른 예약의 변경·교환 승인 권한이 생기지는 않습니다. 팀 예약 알림은 현재 활성 조원, 개인 예약 알림은 본인에게 전달하며 전체 관리자라는 이유로 모든 예약 알림을 받지는 않습니다.

Push 구독은 인증된 본인과 해당 브라우저의 로그인 세션에 연결합니다. 본인 기기만 조회·해제할 수 있고, 로그아웃하면 **해당 기기의 Push만 중단하며 다른 기기는 유지**합니다. 계정 전환 때 binding을 바꿔 이전 계정 내용 표시를 차단합니다. 로그인 만료 자체를 구독 해제로 처리하는 별도 정책은 확정하지 않았습니다.

## 🛠 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.1, Gradle 9.8.1 |
| Backend 주요 구성 | Spring MVC, Spring Security, Spring Session JDBC, Spring Data JPA, Bean Validation |
| Database | PostgreSQL 17.11, Flyway V1~V10 |
| Push | Web Push·VAPID·AES128GCM, PostgreSQL 대기열·계획 worker |
| Frontend | Next.js 16.4.0 (App Router), React 19.3.0, TypeScript 6.0.3 |
| UI·데이터 | Tailwind CSS 4.3.3, TanStack Query 5.104.1 |
| 테스트 | JUnit(Jupiter), Testcontainers(PostgreSQL), Vitest |
| 개발·CI | Docker Compose, PowerShell, GitHub Actions |

백엔드는 Spring Boot BOM·`gradle.lockfile`, 프론트엔드는 `package-lock.json`을 이용해 의존성을 고정합니다. 로컬 데이터베이스는 Docker에서 실행하며, 애플리케이션은 개발 서버 또는 IDE에서 실행합니다.

## 🗂 프로젝트 구조

```text
jeongeum-band-scheduler/
├── .github/
│   └── workflows/ci.yaml               # Backend·Frontend CI
├── backend/
│   ├── src/main/java/kr/jeongeum/scheduler/
│   │   ├── auth/                        # 로그인·가입·세션 인증·원자적 CSRF 초기화
│   │   ├── common/                      # 공통 응답·보안·동시성 제어
│   │   ├── exchange/                    # 교환 요청·승인·악기 배분·만료 복구
│   │   ├── health/                      # DB 연결 상태 확인
│   │   ├── instrument/                  # 관리자 악기 고장·복구·이력
│   │   ├── invitation/                  # 개인 초대 발급·소비·해지
│   │   ├── member/                      # 회원 조회·역할
│   │   ├── notice/                      # 전체 공지·중요 고정·관리자 CRUD
│   │   ├── notification/                # 본인 알림함·업무 사건·시작 알림 계획/worker
│   │   ├── push/                        # 기기 구독·VAPID·발송 대기열/worker
│   │   ├── reservation/                 # 시간표·예약·변경·취소·계보·한도·점유
│   │   ├── recurring/                   # 반복 배정·회차·날짜 개방
│   │   ├── roster/                      # 가입 전 동아리 명단
│   │   └── team/                        # 합주팀·소속·연습곡
│   ├── src/main/resources/db/migration/
│   │   ├── V1__create_spring_session.sql
│   │   ├── V2__create_members_and_invitations.sql
│   │   ├── V3__create_roster_teams_and_songs.sql
│   │   ├── V4__create_basic_reservations.sql
│   │   ├── V5__create_recurring_assignments.sql
│   │   ├── V6__create_reservation_lineage.sql
│   │   ├── V7__reservation_exchanges.sql
│   │   ├── V8__instrument_faults.sql
│   │   ├── V9__notices_notifications.sql
│   │   └── V10__web_push_and_reminders.sql
│   ├── src/test/                        # 통합 테스트
│   └── build.gradle.kts
├── frontend/
│   ├── public/jeongeum-logo.jpg
│   ├── public/push-sw.js                # Push 표시·기기 binding·내부 이동, 인증 캐시 없음
│   ├── public/icons/                    # 홈 화면 앱 아이콘
│   ├── src/app/                         # 기존 화면·기기 알림 설정·manifest.ts
│   ├── src/components/                  # 공통 UI
│   ├── src/features/                    # 인증·예약·팀·공지·알림·Push 권한/구독 로직
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

기본 백엔드 주소는 `http://127.0.0.1:8080`입니다. 이 스크립트는 개발 DB 접속 정보를 프로세스 환경변수로 전달하고 `local` 프로필로 `bootRun`을 실행합니다. **기동 시 Flyway가 V1~V10 중 아직 적용되지 않은 마이그레이션을 개발 DB에 적용합니다.** 기존 DB를 업데이트할 때는 먼저 백업과 격리 복원 검증을 확보하고 기존 비밀번호 파일·계정·데이터 볼륨을 유지하세요. 이미 적용된 마이그레이션을 강제로 재적용하거나 데이터를 초기화하지 않습니다.

Push·시작 알림 worker와 외부 발송은 기본 비활성입니다. 2026-10-14 기동한 개발 프로세스는 worker를 켜고 **외부 Push 발송을 껐습니다**. 종료 후 같은 상태로 다시 실행하려면 해당 터미널에서 아래 설정을 적용합니다. 이미 실행 중인 서버와 중복 기동하지 마세요.

```powershell
$env:JEONGEUM_REMINDER_WORKER_ENABLED = 'true'
$env:JEONGEUM_PUSH_WORKER_ENABLED = 'true'
$env:JEONGEUM_PUSH_ENABLED = 'false'
.\scripts\Start-Backend.ps1
```

이 환경변수는 해당 프로세스/터미널에만 적용됩니다. 실제 외부 발송에는 `JEONGEUM_PUSH_ENABLED`와 서버 환경의 `APP_VAPID_PUBLIC_KEY`, `APP_VAPID_PRIVATE_KEY`, `APP_VAPID_SUBJECT`를 별도로 설정해야 합니다. VAPID **비밀키**와 실제 `.env`·인증 토큰은 Git·브라우저·로그에 넣지 않습니다. 공개키는 구독을 위해 인증된 브라우저에 전달합니다. 실제 수신은 HTTPS 시험 환경과 승인된 시험 계정/기기로 확인하며 로컬 worker 기동을 단말 수신 완료로 간주하지 않습니다.

### 4. 프론트엔드 실행 — 터미널 B

```powershell
.\scripts\Start-Frontend.ps1
```

웹 화면: **http://127.0.0.1:3000**

스마트폰 화면은 PC와 같은 네트워크에서 LAN 실행 옵션으로 확인할 수 있습니다. 예시 IP를 현재 PC의 Wi-Fi IPv4 주소로 바꾸고, 스마트폰에서 `http://PC의-IP:3000`을 엽니다. 백엔드·DB는 localhost를 유지합니다. 네트워크/방화벽 조건은 [개발 환경 안내](docs/DEV_ENVIRONMENT.md)를 따릅니다.

```powershell
.\scripts\Start-Frontend.ps1 -Hostname 0.0.0.0 -AllowedDevOrigins '192.168.0.10'
```

LAN HTTP는 화면 확인용이며 Push·service worker의 보안 컨텍스트 조건을 충족하지 않습니다. 실제 스마트폰 Push는 신뢰된 HTTPS 주소가 필요합니다.

로그인 후 [홈](http://127.0.0.1:3000/)에서 달력·합주실 시간표·빠른 예약을 사용합니다. [내 공연조 일정](http://127.0.0.1:3000/my-schedule) 메뉴에서는 현재 소속 공연조의 오늘~서버 예약 가능 마지막 날짜까지 실제 확정 예약을 확인합니다. 일반 예약과 실제 생성된 고정 회차를 함께 표시하며, 팀 필터와 날짜·시간순 목록을 제공합니다. 개인 예약·취소 회차·미생성 미래 패턴은 확정 목록에서 제외합니다. 변경·취소·상세·과거 이력은 [예약·이력](http://127.0.0.1:3000/reservations), 계정·로그아웃은 [내 계정](http://127.0.0.1:3000/account), 전체 기능 진입은 [더보기](http://127.0.0.1:3000/more)에서 확인합니다. 전체 관리자는 [고정 배정](http://127.0.0.1:3000/admin/recurring-assignments) 메뉴에서 배정을 관리합니다.

예약 변경은 예약 목록의 **예약 변경** 또는 다이어리 상세에서 진입합니다. 현재 권한으로 변경할 수 있는 시작 전 예약을 골라 새 날짜·시간·개인 악기를 선택하고, 변경 전후를 확인한 뒤 확정합니다. 성공 후 시간표·내 예약·내 공연조 일정을 갱신하며 원본 이력에서 후속 예약을 조회할 수 있습니다.

예약 교환은 [예약 교환](http://127.0.0.1:3000/exchanges), **더보기 → 예약 교환** 또는 예약 상세의 **이 예약으로 교환 요청**에서 진입합니다. 양측 후보를 선택해 요청하고 상대 측 권한자가 승인합니다. 대기 중 원래 예약을 유지하며, 개인 악기 배분이 필요한 경우 해당 예약자가 순서를 저장한 뒤 승인할 수 있습니다. 거절·철회·만료·무효화 상태와 완료 후속 예약도 확인합니다.

전체 관리자는 [악기 고장 관리](http://127.0.0.1:3000/admin/instrument-faults)에서 여섯 악기의 현재 상태, 고장 등록·복구와 처리 이력을 관리합니다. 데스크톱 **관리 → 악기 고장 관리**, 모바일 **더보기 → 악기 고장 관리**로 진입합니다. 고장 등록은 기존 예약을 자동 취소하지 않으며, 고장 악기를 새로 배치하는 개인 예약·변경·교환을 제한하고 팀 합주는 경고와 함께 허용합니다.

[전체 공지](http://127.0.0.1:3000/notices)와 [내 알림함](http://127.0.0.1:3000/notifications)은 로그인 후 데스크톱 **공지·알림**, 모바일 **더보기**에서 진입합니다. 전체 관리자는 공지를 작성·수정·삭제하고 중요 공지를 고정할 수 있습니다. **처음 중요 공지로 작성할 때만** 전체 활성 회원에게 알림을 만들며, 일반 공지 작성·수정·중요 전환·고정 해제·재고정은 재알림하지 않습니다.

알림함은 본인의 공지·예약·교환 알림과 미읽음 수, 미읽음 필터·이전 항목을 제공합니다. 개별 읽음 또는 목록을 확인한 **서버 시각까지 모두 읽음**을 적용할 수 있으며 이후 도착한 알림은 미읽음으로 남습니다. 관련 화면에서 최신 상태와 현재 권한을 다시 검사합니다. 성공 응답을 잃은 쓰기는 원래 요청으로 직접 재확인하며 오프라인 변경을 대기열에 넣거나 자동 실행하지 않습니다.

[알림 설정](http://127.0.0.1:3000/notifications/settings)은 데스크톱 **공지·알림 → 알림 설정**, 모바일 **더보기 → 기기 알림·홈 화면 설치**, **내 계정 → 기기 알림 설정**에서 진입합니다. 기기별 알림 켜기/끄기, 서버 발송 중단·HTTP·권한 거절·미지원 안내를 제공합니다. 페이지 진입만으로 권한을 요청하지 않으며 알림을 허용하지 않아도 예약과 내 알림함을 사용할 수 있습니다. iPhone/iPad는 iOS/iPadOS 16.4 이상에서 Safari의 **홈 화면에 추가**로 설치한 웹 앱을 열고 사용자 버튼으로 알림을 허용해야 합니다. [WebKit 공식 조건](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

시작 알림은 예약 블록 시작 1시간 전을 기준으로 합니다. 생성·변경·교환 때 예정 시각이 이미 지났거나 현재와 같으면 별도의 시작 계획을 만들지 않고, 변경·취소 시 이전 계획과 대기 발송을 정리합니다. 현재 로컬 서버는 외부 발송이 꺼져 있으므로 설정 화면의 서버 중단 안내가 정상입니다.

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
PostgreSQL 제약·행 잠금·공통 변경 잠금과 버전 충돌 검증을 사용해 명단 중복 연결, 권한 변경 경합, 마지막 활성 관리자 강등, 초대 중복 소비, 예약 점유·한도 경합을 처리합니다.

**4. 세션 보안과 지속 로그인**  
Spring Session JDBC를 사용하며 `HttpOnly`·`SameSite=Lax` 세션 쿠키와 CSRF 보호를 적용합니다. 로그인 상태는 **마지막 유효 인증 요청부터 90일간 유지**되며 요청 시 만료 시각을 연장합니다. 원자적 CSRF attribute 초기화와 JDBC 즉시 저장으로 로그인 직후 첫 동시 요청·세션 ID 회전 경합을 보완했습니다. 기존 XOR 처리·이전 토큰 거절·로그아웃·권한 검사를 유지하며 운영 HTTPS에서는 `Secure` 쿠키를 사용하도록 설계했습니다.

**5. 고정 배정과 날짜 개방의 원자성**

날짜별 독립 트랜잭션에서 고정 회차를 먼저 생성한 뒤 예약 가능 상태로 공개합니다. 충돌·잠금 timeout이 발생한 날짜는 공개하지 않고 후속 실행에서 재시도하며, 서버 재시작 때 누락 날짜를 복구합니다. 회차별 취소 예외와 완료 이력은 보존합니다.

**6. 현재 소속 기준 일정 조회**

기존 기간별 예약 API의 현재 회원·팀 소속 검증을 재사용합니다. 내 공연조 일정은 실제 확정된 일반/고정 예약과 열린 날짜만 표시하며, 새로운 API·테이블이나 예약 권한을 추가하지 않습니다.

**7. 예약 변경 실패 시 원본 보존**

`PATCH /api/v1/reservations/{id}`는 원본 교체·후속 예약·점유·한도·V6 계보·고정 회차 예외를 한 트랜잭션에서 저장합니다. 최종 잠금 후 현재 권한·시각·점유·한도를 재검사하고 충돌이나 timeout이면 전체를 롤백합니다. 같은 키·본문의 재시도는 같은 결과를 반환하며 예약 주체와 최초 작성자를 유지합니다. 고정 배정은 해당 회차만 변경하고 다음 주 패턴을 보존합니다.

**8. 교환 전체의 원자성과 결과 재확인**

승인 시 양측 예약·점유·한도·N:M 계보·고정 회차 예외를 한 트랜잭션에서 확정하고, 현재 권한·시각·원본 버전·충돌·고장을 재검사합니다. 실패하면 양쪽 원본을 유지합니다. 성공 응답을 잃었을 때 같은 키·본문으로 결과를 재확인하며 중복 교환을 막고, 재시작 후 누락된 만료 처리를 복구합니다.

**9. 고장 상태와 예약의 일관성**

현재 관리자 권한과 악기·이력 버전을 검사한 뒤 고장 상태·처리 이력·재시도 기록을 함께 저장합니다. 예약과 같은 PostgreSQL 잠금 규약을 사용해 개인 예약과 고장 등록의 경합을 처리하며, 기존 예약·점유·한도·반복 회차·대기 교환은 고장 등록만으로 변경하지 않습니다.

**10. 업무와 알림 기록의 원자성**

예약 생성·변경·취소, 실제 반복 고정 회차, 교환 요청·완료·거절·철회·만료·무효화 사건의 알림과 기기별 Push 대기열을 해당 업무 트랜잭션에서 함께 저장합니다. 알림 저장이 실패하면 업무·알림·재시도 기록을 함께 롤백합니다. `(event_key, user_id)` unique 제약과 구독별 작업 키로 중복을 막으며 no-op·동일 요청 재시도·회차 재개방과 변경/교환 후속 예약은 별도 생성 알림을 만들지 않습니다.

**11. 시작 알림의 시간·계보 정합성**

예약 트랜잭션에서 미래 계획만 저장하고 due 시점에 현재 예약·버전·활성 수신자를 검사하여 알림함과 기기 작업을 만듭니다. 변경·취소·교환은 이전 계획/대기 발송을 무효화합니다. 논리 계보 root 집합과 실제 시작 시각으로 같은 회차의 중복을 억제하고, 재시작 후 누락된 미래 계획과 대기 작업을 복구합니다. 시작이 지난 알림은 발송하지 않습니다.

**12. 기기별 발송과 개인정보 보호**

발송 worker는 PostgreSQL claim/lease와 제한 재시도를 사용하고 네트워크 호출은 예약 잠금 밖의 별도 실행 스레드에서 처리합니다. HTTPS 제공자 allowlist·공개 DNS 주소·redirect 금지·timeout으로 발송 대상을 제한합니다. 구독 재연결·로그아웃은 binding과 대기 작업을 함께 정리하고, SW는 이전 계정 내용 표시와 외부 이동을 차단합니다. Push 서비스 수락은 OS 표시를 보장하지 않으며 발송 후 기록 전 중단의 외부 중복 가능성은 tag로 완화합니다. end-to-end exactly-once를 약속하지 않습니다.

## 🧪 테스트 및 CI

2026-10-14 최종 회귀와 PR #17 병합 후 main CI에서 백엔드 325건·프론트엔드 214건, **총 539건**을 통과했습니다. 아래 수치는 해당 실행 기록이며 문서 갱신만을 위해 테스트를 다시 실행하지 않았습니다.

- **Backend:** 2026-10-14 전체 JUnit/PostgreSQL Testcontainers **18개 suite·325건 통과**. 기기 구독/발송 30건·시작 알림 17건·CSRF 동시성 5건·별도 JVM worker 2건·VAPID/암호화 7건·worker 실행 분리 6건과 기존 예약·변경·교환·반복·고장·공지·알림 회귀 포함. 실패·오류·skip 0, compileJava·bootJar 통과
- **Frontend:** 전체 **19개 파일·214개 Vitest 테스트 통과**, TypeScript·ESLint(경고 0)·Next.js production build 통과(manifest·404 포함 20개 route). 로컬은 격리 복사본의 webpack, 원격 CI는 기본 Turbopack 빌드로 각각 확인
- **실제 실행·복구:** 기존 Spring Boot 종료·새 JVM 재시작, 데이터 보존 확인. 격리 PostgreSQL에서 등록된 자정 cron, 10초 잠금 timeout의 전체 rollback, 60초 자동 재시도와 누락 날짜 복구·중복 방지 확인
- **GitHub Actions:** Ubuntu에서 백엔드 테스트·빌드와 프론트엔드 의존성 감사·타입·lint·테스트·빌드를 자동 실행
- **최신 원격 CI:** [PR #17 병합 커밋 `f39969a`의 main 실행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38533584617)에서 Backend·Frontend 양쪽 job 성공. 기능 커밋 `210d504`의 [PR 실행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38528767465)과 [Push 실행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/38528743841)도 총 4개 job 성공했으며 백엔드 325건·프론트엔드 214건, 양쪽 빌드·타입·lint·생산 의존성 감사(취약점 0)를 확인
- **V7~V10·데이터 보존:** 합성 데이터의 V6→V7·V7→V8·V8→V9·V9→V10 업그레이드에서 기존 컬럼·상태·데이터 보존·재실행 0건·Flyway validate 확인. 실제 개발 DB도 사용자 승인과 백업 후 정상 경로로 적용했으며 업무 데이터·계정·기존 Flyway 이력·Docker 볼륨을 보존. V10은 기기 구독 `push_subscriptions`·발송/시작 계획 `notification_jobs` 두 테이블과 START_REMINDER 유형만 추가하고 과거 알림을 소급 발송하지 않음
- **새 V9 백업·실제 복원·V10 기동:** V10 적용 직전 새 V9 백업 **139,986bytes**의 checksum·archive 목록을 확인하고 네트워크/호스트 포트가 없는 임시 PostgreSQL에 실제 복원. 기존 36개 테이블의 행·세션·시퀀스 지문 일치, 원본 DB 보존·임시 컨테이너 정리 확인. 실제 V10 한 건 적용 직후 기존 32개 업무 테이블·세션·날짜·V1~V9·계정/예약·볼륨을 보존. localhost/LAN API·CSRF/인증 연결 검사에서 익명 세션 한 건 추가만 별도 기록; 외부 Push는 강제 비활성
- **교환·고장 경합:** 동시 승인·동시 고장 등록/복구·현재 권한 재검사, 마지막 쓰기 실패와 실제 10초 잠금 timeout의 전체 롤백·동일 키 재시도, 양측 원본 및 기존 예약·점유·한도·회차 보존 확인
- **공지·알림 정합성:** 현재 관리자·수신자 권한, 최초 중요 작성만 알림, 버전·동일 키 경합, 잠금 timeout·후반 INSERT 실패의 전체 롤백, 수신자 격리·중복 방지, 전체 읽음 시각의 microsecond 경계 확인
- **실제 브라우저:** 격리 PostgreSQL·합성 계정으로 기존 다이어리·변경·반복 배정과 교환 요청/승인/거절/철회·개인 악기 배분·고정 팀 회차 교환, 고장 등록/복구/이력·회원 고장 표시·비관리자 접근 차단 확인. 실제 성공 응답 유실 뒤 동일 요청 재확인·중복 방지와 새 JVM 재시작 후 상태·기록·기존 업무 데이터 보존 확인
- **공지·알림 브라우저·재시작:** 관리자 작성/수정/삭제·회원 조회·계정 전환 후 본인 알림 격리·읽음/필터·페이지네이션·삭제된 공지 안내, 성공 응답 유실 후 동일 요청·기준 시각 재확인, 예약/교환 링크와 실제 교환 승인 확인. 동일 격리 DB로 JVM 재시작 후 업무 25개·신규 3개 테이블의 전체 지문, 알림 52건·중복 0건과 기존 로그인 보존 확인
- **Push·시작 worker·CSRF:** 실제 VAPID 서명과 AES128GCM 암호문 복호화, 실패 재시도/만료/로그아웃/늦은 재등록 경합, 예약 트랜잭션 실패 시 계획/대기열 롤백·현재 수신자·같은 계보 중복 방지, 별도 JVM에서 실제 스케줄러·lease·재시작 복구 확인. 동시 CSRF 초기화·로그인 직후 준비 없는 요청·ID 회전·이전 토큰 거절·독립 JVM을 검사하며 보호를 끄지 않음
- **Push 설정 브라우저:** 합성 계정의 격리 production 화면에서 PC/모바일 진입, 기기별 해제·다른 기기 보존, 서버 중단 안내·알림함 읽음, 로그아웃/다른 계정 로그인과 기존 예약 화면을 확인. OS 권한 요청·실제 브라우저 구독/외부 발송은 수행하지 않았으며 브라우저에서는 예약 mutation을 제출하지 않음. SW 저장 실패·5초 응답 timeout·서버 로그아웃 실패·최초 버전 0은 자동 회귀로 검증

다이어리 UI는 375~1920px의 8개 viewport에서 레이아웃 전환·모바일 바텀시트를 확인했고, 최종 인사말은 320/375/390/768/1440px에서 확인했습니다. 예약 변경·교환·고장 관리·공지·알림함·Push 설정 화면은 320/390/768/1440px에서 페이지 가로 넘침이 없었습니다. 공지 작성 폼은 320px에서 확인했고, 각 기능의 실제 쓰기·확인·재시도는 별도 합성 환경에서 조작했습니다. 메인에는 작은 `JEONGEUM · SENTIMENTAL MUSIC`, 굵은 닉네임 인사말과 연한 로그인 ID·역할을 표시합니다. 상세 결과는 [다이어리 검증 기록](docs/DIARY_UI_REDESIGN.md), [예약 변경 검증 기록](docs/RESERVATION_CHANGES.md), [예약 교환 검증 기록](docs/RESERVATION_EXCHANGES.md), [악기 고장 검증 기록](docs/INSTRUMENT_FAULTS.md), [공지·알림함 검증 기록](docs/NOTICES_NOTIFICATIONS.md), [Web Push·시작 알림 검증 기록](docs/WEB_PUSH_REMINDERS.md)을 따릅니다.

**실기기 iOS/Android의 설치·권한 허용·외부 Push 수신·클릭, 가상 키보드·스크린리더·200% 확대·실서비스 트래픽·운영 HTTPS·운영 외부 백업 복원**은 미검증입니다. HTTP LAN의 같은 PC 응답 확인은 다른 스마트폰의 접속이나 Push 수신 확인과 다릅니다. 이전 V7~V9 적용 직전 개발 DB 백업 파일 자체의 복원도 미검증이며, 이번 **V10 적용 직전 새 V9 백업의 격리 복원 성공**과 구분합니다.

기존 동일 세션의 최초 CSRF 동시 초기화 경합은 PR #17에서 보완하고 전체 회귀를 통과했습니다. 오프라인 쓰기 차단·역할 오류 안내는 프론트 테스트와 코드 검토로 확인했으며 실제 네트워크 차단·실기기 검증과 구분합니다. 외부 Push 전달은 네트워크·OS·발송 후 기록 전 중단의 영향을 받으므로 단말 표시 시각이나 완전한 중복 없는 전달을 보장하지 않습니다.

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

설계가 확정된 예약 정책 중 주요 내용은 다음과 같습니다. 기본 예약·반복 배정·변경·교환·악기 고장의 구현 범위는 위 현황과 각 구현 문서에서 구분합니다.

- **운영 시간:** 매일 12:00~24:00. 12:00~18:00은 60분 단위 6개, 18:00~24:00은 90분 단위 4개 블록
- **예약 가능 날짜:** 한국 시간 기준 오늘부터 14일 뒤까지(양 끝 포함)
- **팀 예약:** 정규조·추가조 모두 합주실 전체를 독점 사용
- **개인 예약:** 보컬·건반·일렉기타·베이스·드럼·통기타의 악기별 공유 이용
- **60분 예약 한도:** 개인별·팀별 하루 120분, 시작 후 취소한 사용량 유지, 원래 관리자 배정 60분만 면제
- **반복 배정:** 정규조·추가조의 매주 고정 시간, 적용 시작일별 수정, 개별 취소 예외 보존, 고정 반영 후 날짜 개방
- **단건 변경:** 시작 전 날짜·블록·개인 악기 변경, 실패 시 원본·점유·한도 보존, 고정 회차만 이동. 이동한 60분에는 일반 한도 적용
- **예약 교환:** 시작 전 1↔1(60/90분), 연속 60분 두 건↔90분 한 건. 각자의 주체·최초 예약자·악기 유지, 승인 때 양측 조건 재검사·실패 시 원본 보존. 이동한 고정 60분에는 일반 한도 적용
- **악기 고장:** 전체 관리자만 등록·복구. 기존 예약 유지, 고장 악기를 새로 배치하는 개인 예약·변경·교환 제한, 정규조·추가조는 경고와 함께 허용
- **중요 공지 알림:** 최초 작성부터 중요 공지인 경우에만 활성 회원 전체에 알림함·기기별 Push 발송 대상 생성. 일반 작성·수정·중요 전환·재고정은 재알림 없음
- **개인 알림함:** 본인이 받은 공지·예약·반복 회차·교환 알림만 조회·읽음 처리하며 관련 화면에서는 현재 권한을 재검사
- **시작 전 알림:** 예약 블록 시작 1시간 전. 계획 시 예정 시각이 엄격히 미래인 경우만 등록하며 변경·취소·교환은 이전 계획/대기 발송 무효화. 개인 본인·현재 활성 팀원 대상, 계보·시각 중복 방지
- **기기 Push:** 사용자 버튼으로 권한/구독 연결, 로그아웃 시 해당 기기만 중단·다른 기기 유지. 미설치·권한 거절·미지원이어도 알림함과 예약은 사용 가능
- **추가 예정:** 탈퇴·팀 해체의 예약/교환/반복/알림 정리, HTTPS·실기기 설치/Push 수신, 운영 배포·외부 백업 복원·운영 검증. 고장 등록/복구 알림은 아직 확정 필수 이벤트가 아닌 별도 제안

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
| [RESERVATION_FOUNDATION](docs/RESERVATION_FOUNDATION.md) | 기본 예약·취소·점유·한도 구현 및 검증 |
| [RECURRING_ASSIGNMENTS](docs/RECURRING_ASSIGNMENTS.md) | 반복 배정·회차 예외·날짜 개방·내 공연조 일정·V5 적용 및 검증 |
| [RESERVATION_CHANGES](docs/RESERVATION_CHANGES.md) | 단건 변경 API·V6 계보·권한/한도/점유·원본 보존·경합·실제 브라우저 검증 |
| [RESERVATION_EXCHANGES](docs/RESERVATION_EXCHANGES.md) | 교환 요청·승인·악기 배분·V7·양측 원본 보존·경합·재시작·브라우저 검증 |
| [INSTRUMENT_FAULTS](docs/INSTRUMENT_FAULTS.md) | 관리자 고장 등록·복구·이력·V8·예약 연동·응답 유실 재확인·데이터 보존 검증 |
| [NOTICES_NOTIFICATIONS](docs/NOTICES_NOTIFICATIONS.md) | 전체 공지·개인 알림함·예약/교환 사건 연동·V9·권한/중복/롤백·재시작·브라우저·개발 DB 적용 검증 |
| [WEB_PUSH_REMINDERS](docs/WEB_PUSH_REMINDERS.md) | 기기별 Push·시작 알림·CSRF 보완·V10·암호화/서명·다중 JVM·격리 브라우저·새 백업 복원·개발 DB 기동·실수신 제한 |
| [DIARY_UI_REDESIGN](docs/DIARY_UI_REDESIGN.md) | 다이어리 UI 구현 단계·기존 기능 보존·반응형·실제 브라우저 검증·재개 방법 |
| [UX](docs/UX.md) | 데스크톱/모바일 화면 계획과 브랜드 |
| [TEST_PLAN](docs/TEST_PLAN.md) | 기능·보안·경합·운영 검증 항목 |
| [NOTIFICATIONS](docs/NOTIFICATIONS.md) | 알림함·Web Push·시작 전 알림 정책과 구현/운영 검증 구분 |
| [INITIAL_SETUP](docs/INITIAL_SETUP.md) | 백엔드·프론트엔드 초기 설정과 검증 |
| [DEV_ENVIRONMENT](docs/DEV_ENVIRONMENT.md) | 로컬 DB·Docker·JDK와 선택적 스마트폰 LAN 접속 안내 |
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
| [#6](https://github.com/developer-sw/jeongeum-band-scheduler/pull/6) | 기본 시간표·팀/개인 예약·한도·취소 및 PostgreSQL 경합 검증 |
| [#7](https://github.com/developer-sw/jeongeum-band-scheduler/pull/7) | 반복 고정 배정·날짜 개방·재시작 복구·내 공연조 일정, 관리자 UI 및 실제 브라우저 검증 |
| [#9](https://github.com/developer-sw/jeongeum-band-scheduler/pull/9) | 다이어리형 PC·모바일 UI, 달력·시간표·빠른 예약·계정 인사말 및 브라우저 검증 |
| [#10](https://github.com/developer-sw/jeongeum-band-scheduler/pull/10) | 단건 예약 변경·V6 계보·원본 보존·고정 회차 예외·PostgreSQL 경합 및 PC·모바일 검증 |
| [#11](https://github.com/developer-sw/jeongeum-band-scheduler/pull/11) | 예약 변경 완료 상태·V6·검증 기록을 반영한 README 갱신 |
| [#12](https://github.com/developer-sw/jeongeum-band-scheduler/pull/12) | 예약 교환·승인·개인 악기 배분·V7·실패 시 양측 보존·만료 복구 및 PC·모바일 검증 |
| [#13](https://github.com/developer-sw/jeongeum-band-scheduler/pull/13) | 관리자 악기 고장·복구·이력·V8·예약 연동·기존 예약 보존 및 PC·모바일 검증 |
| [#14](https://github.com/developer-sw/jeongeum-band-scheduler/pull/14) | 예약 교환·악기 고장·V8·검증 기록을 반영한 README 갱신 |
| [#15](https://github.com/developer-sw/jeongeum-band-scheduler/pull/15) | 전체 공지·개인 알림함·V9·예약/반복/교환 사건 원자적 기록·중복/롤백·PC/모바일·재시작 검증 |
| [#16](https://github.com/developer-sw/jeongeum-band-scheduler/pull/16) | 전체 공지·알림함·V9·검증 상태를 반영한 README 갱신 |
| [#17](https://github.com/developer-sw/jeongeum-band-scheduler/pull/17) | 기기별 Web Push·시작 1시간 전 알림·CSRF 동시성 보완·V10·325/214건·다중 JVM·백업 복원·최신 로컬 기동, 기존 LAN 변경 별도 커밋 |

현재는 **로컬 개발 및 기능 확장 단계**이며, 동아리 대상 운영 배포·실사용 지표는 아직 없습니다. 기능과 운영 검증을 거쳐 실제 사용자에게 제공할 예정입니다.

---

<div align="center">
  <sub>Built for JEONGEUM · SENTIMENTAL MUSIC</sub>
</div>
