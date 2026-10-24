<div align="center">
  <img src="frontend/public/jeongeum-logo.jpg" alt="정음 JEONGEUM 로고" width="140" />

  # 정음 | JEONGEUM

  **JEONGEUM · SENTIMENTAL MUSIC**

  정음 밴드 동아리를 위한 합주팀 관리 및 합주실 예약 웹 서비스

  **Java · Spring Boot · PostgreSQL · Next.js · TypeScript**

  [기능 현황](#-구현-현황) · [기술 스택](#-기술-스택) · [프로젝트 구조](#-프로젝트-구조) · [운영 준비](#운영-배포-준비) · [로컬 실행](#-로컬-실행) · [문서](#-기술-문서)
</div>

---

## 🎸 프로젝트 소개

**JEONGEUM Band Scheduler**는 약 40명이 활동하는 밴드 동아리 **정음(JEONGEUM)**의 회원·합주팀·연습곡과 합주실 이용 일정을 하나의 서비스에서 관리하기 위한 프로젝트입니다.

동아리에서 실제로 사용할 수 있는 서비스를 구축하는 동시에, **Java/Spring 기반 백엔드 설계, 데이터 무결성, 동시성 제어, 인증·인가, 테스트 및 운영**을 검증하는 것을 목표로 합니다.

- **사용 대상:** 정음 동아리의 전체 관리자, 팀장, 일반 회원
- **개발 형태:** Spring Boot 백엔드와 Next.js 프론트엔드를 분리한 단일 Git 저장소
- **UI 방향:** 동일한 코드베이스에서 모바일·데스크톱에 적합한 반응형 화면 제공
- **현재 단계(2026-10-24):** 인증·회원·팀·곡·예약·반복 배정·날짜 개방·변경·교환·악기 고장·공지·알림함·Web Push·시작 1시간 전 알림·회원 탈퇴·팀 해체·가입 전후 관리자 지정과 다이어리 UI를 `main`에 반영. 개발 DB **Flyway V1~V12**와 기존 데이터를 보존했으며, 홈 화면 설치 이름 `정음`·따뜻한 로고를 포함한 **`v1.0.0-rc.3` 공개 이미지 3개 발행·검증**을 완료
- **현재 UI:** 다이어리 홈·달력·주간 시간표·모바일 예약 바텀시트·계정 인사말·예약 변경/교환·관리자 악기 고장·공지·개인 알림함·기기별 알림 설정/클릭 진단·회원 탈퇴/팀 해체 확인 화면. 회원 관리는 전체 관리자·팀장·일반 회원의 세 영역과 카드 내 지정·해제, 다이어리는 PC 소개 정렬·모바일 로고/더보기 크기·제목 아이콘·응원 문구 보완. PC·모바일에서 같은 API와 예약 정책 사용
- **배포 상태:** 기존 서울 Lightsail·도메인/DNS·공개 HTTPS 준비 화면을 구성하고, 합성 DB의 **서버 밖 암호화 백업·PC 독립 복원·VM 재부팅 자동 복구**를 검증. 백업·자동 복구는 [PR #28](https://github.com/developer-sw/jeongeum-band-scheduler/pull/28), **새 비활성 운영 설정 16파일의 암호화·Windows 독립 복원 검증 기록과 도구 보완**은 [PR #29](https://github.com/developer-sw/jeongeum-band-scheduler/pull/29)로 `main`에 반영. 새 설정은 서버에 설치하지 않았으며 실제 회원 데이터 이전과 예약 앱 공개도 아직 진행하지 않음

> **구현 상태 안내:** 기본 예약·취소는 [PR #6](https://github.com/developer-sw/jeongeum-band-scheduler/pull/6), 반복 고정 배정·날짜 개방·내 공연조 일정은 [PR #7](https://github.com/developer-sw/jeongeum-band-scheduler/pull/7), 다이어리 UI는 [PR #9](https://github.com/developer-sw/jeongeum-band-scheduler/pull/9), 단건 예약 변경은 [PR #10](https://github.com/developer-sw/jeongeum-band-scheduler/pull/10), 예약 교환은 [PR #12](https://github.com/developer-sw/jeongeum-band-scheduler/pull/12), 악기 고장 관리는 [PR #13](https://github.com/developer-sw/jeongeum-band-scheduler/pull/13), 전체 공지·개인 알림함은 [PR #15](https://github.com/developer-sw/jeongeum-band-scheduler/pull/15), Web Push·시작 1시간 전 알림·CSRF 보완은 [PR #17](https://github.com/developer-sw/jeongeum-band-scheduler/pull/17)로 `main`에 반영했습니다. [PR #18](https://github.com/developer-sw/jeongeum-band-scheduler/pull/18)은 당시 README 갱신이며, **회원 탈퇴·팀 해체·후속 관리자 UI·Push 클릭 복원 및 실기기 검증은 [PR #19](https://github.com/developer-sw/jeongeum-band-scheduler/pull/19), 병합 커밋 `0d660ae`로 반영했습니다.** **가입 전 관리자 지정·역할별 명단·카드 내 확인과 다이어리 UI 보완은 [PR #21](https://github.com/developer-sw/jeongeum-band-scheduler/pull/21), 홈 화면 이름·로고와 Next.js 보안 패치는 [PR #27](https://github.com/developer-sw/jeongeum-band-scheduler/pull/27)로 `main`에 반영했습니다.** 기존 앱 기능 검증은 Backend **368건**·Frontend **334건**, 총 **702건**이며 이번 백업 도구 검증과 구분합니다. 실제 예약 앱 전환·운영 origin Push·Android 실기기는 후속 검증 범위입니다.

## ✅ 구현 현황

| 영역 | 상태 | 주요 기능 |
| --- | --- | --- |
| 개발 기반 | ✅ 구현 완료 | Docker Compose 개발용 PostgreSQL, Flyway, 프로젝트 실행 스크립트, GitHub Actions CI |
| 초대·인증 | ✅ 구현 완료 | 관리자 발급 개인별 일회용 초대, 초대 가입, 로그인·로그아웃, 세션·CSRF 보호 |
| 로그인 유지 | ✅ 구현 완료 | 마지막 인증 요청 후 **90일** 유지, 유효한 인증 요청 때 세션·쿠키 만료 연장 |
| 회원·명단 | ✅ 구현·main 반영 | 가입 전 명단·개인 초대 계정 연결·기존 계정 수동 연결, 전체 관리자→팀장→일반 회원의 중복 없는 분류 |
| 관리자 지정 | ✅ 구현·main 반영 | 가입 전 명단의 관리자 사전 지정·개인 초대 가입 시 자동 적용, 가입 계정 사후 지정·해제, 카드 내 확인/오류/완료·마지막 활성 관리자 보호 |
| 합주팀·곡 | ✅ 구현 완료 | 정규조·추가조, 복수 팀 소속, 팀장 지정, 팀별 연습곡 등록·조회·수정·삭제 |
| 권한·무결성 | ✅ 구현 완료 | 전역 관리자와 팀별 팀장 권한 분리, 트랜잭션·잠금, DB 제약 및 버전 충돌 처리 |
| 웹 화면 | ✅ 현재 기능 구현 | 로그인·가입·다이어리 홈·관리자 명단/초대·팀 목록/상세·합주실 시간표·고정 배정·내 공연조 일정·예약 변경/이력·교환·악기 고장 관리·공지·개인 알림함·기기별 알림 설정, 반응형 레이아웃 |
| 합주실 예약 | ✅ 구현 완료 | 고정 시간 블록, 팀 독점·악기별 개인 예약, 충돌·일일 한도·취소·이력 |
| 반복 고정 배정 | ✅ 구현·main 반영 | 관리자 정규조·추가조 배정, 적용일별 패턴 수정·종료, 회차 취소 예외, 고정 반영 후 날짜 개방·실패 재시도·재시작 복구 |
| 내 공연조 일정 | ✅ 구현·main 반영 | 현재 소속 정규조·추가조의 여러 날짜 일반/고정 확정 예약 통합 조회, 팀 필터, 날짜·시간순 목록·해당 날짜 시간표 연결 |
| 다이어리 UI | ✅ 구현·main 반영 | 따뜻한 달력·월~일 시간표·내 공연조 요약·공유 빠른 예약, 모바일 하루 카드·바텀시트·4탭, PC 소개 정렬·모바일 로고/더보기 조정·달력/합주/예약 아이콘·기타 옆 응원 문구 |
| 단건 예약 변경 | ✅ 구현·main 반영 | 시작 전 날짜·시간 블록·개인 악기 변경, 원본/후속 이력, 권한·충돌·한도 재검사, 실패 시 원본 보존, 고정 회차 단건 예외 |
| 예약 교환 | ✅ 구현·main 반영 | 1↔1·연속 60분 두 건↔90분 한 건의 요청·승인·거절·철회·만료·무효화, 개인 악기 배분, 실패 시 양쪽 원본 보존 |
| 악기 고장 관리 | ✅ 구현·main 반영 | 관리자 고장 등록·복구·사유/처리 이력, 현재 상태 표시, 개인 신규/실질 변경/교환의 고장 검사, 팀 합주 경고·기존 예약 보존 |
| 전체 공지 | ✅ 구현·main 반영 | 관리자 작성·수정·삭제·중요 고정, 활성 회원 목록/상세·이전 항목 조회, 최초 중요 작성 때만 알림 |
| 개인 알림함 | ✅ 구현·main 반영 | 본인 알림·미읽음 수/필터·개별/전체 읽음·이전 항목, 예약/반복 회차/교환 사건 연동·중복 방지·관련 화면 연결 |
| Web Push | ✅ 구현·PC/iPhone 실수신 확인 | 기기 구독·해제·계정 binding, VAPID/AES128GCM 발송·재시도·만료·재시작 복구, 클릭 목적지 120초·일회 복원, 로그아웃 시 해당 기기만 중단 |
| 시작 1시간 전 알림 | ✅ 구현·iPhone 실수신 확인 | 예약·변경·취소·교환·반복 회차와 원자적 계획 연동, 현재 수신자/버전·계보 중복 검사, 제어된 시험 시계와 실제 스케줄러로 잠금 화면 수신·예약 열기 확인 |
| 탈퇴·팀 해체 예약 정리 | ✅ 구현·main 반영 | 관리자 회원 탈퇴·팀 해체, 개인/팀 예약·대기 교환·반복 배정·예정 알림 정리, 회원 탈퇴 시 전체 기기/세션 종료, 존속 팀 예약·완료/작성자 이력 보존 |
| 운영 컨테이너 | ✅ 구현·main 반영 | Docker·Compose·Caddy, 전용 DB 네트워크/볼륨·secret 파일, 격리 HTTPS·권한/예약·재시작·별도 DB 복원 57개 검사 |
| 운영 이미지 | ✅ rc.3 발행·검증 | Linux amd64 Backend·Frontend·Caddy 공개 이미지, 홈 화면 이름·로고 포함, 출처/버전·SHA tag·digest 검증. 기존 rc.2 보존 |
| 운영 서버 준비 | 🧪 HTTPS·합성 복구 확인 | 서울 Lightsail 고정 IP·Docker/Compose·제한 SSH·도메인/DNS·Caddy 공개 인증서·준비 화면. 실제 VM 재부팅 후 PG/HTTPS 자동 복구 확인 |
| 암호화 백업·감시 | 🧪 합성 DB 구현·검증 | 서울 비공개 S3·최소 IAM·시간별/주간 보존·Governance, exact-version 검증, CloudWatch/SNS·PC 독립 복원·설정 복구 |
| 비활성 운영 설정 | 🧪 로컬 생성·복구 검증 | 미사용 DB 비밀번호·VAPID를 포함한 새 설정 16파일의 실제 age 암호화·Windows 독립 복원·정적 검사 완료. 서버 설치·DB 역할 생성·활성화 없음 |
| PWA·실서비스 전환 | 🧪 설치 설정·운영 준비 중 | iPhone·Android 설치 이름 `정음`·따뜻한 로고, 기존 iPhone 설치/Push 수신 확인. 실제 회원 데이터 이전·예약 앱 공개·운영 origin Push·Android 실기기는 후속 |

### 권한 모델

- **전체 관리자(`GLOBAL_ADMIN`)**: 동아리 명단, 회원·역할·탈퇴, 팀 구성·팀장·해체, 초대·팀별 곡·반복 고정 배정·악기 고장·전체 공지 관리와 예약 변경·취소·교환
- **팀장(`LEADER`)**: 자신이 담당하는 팀의 연습곡 관리와 다른 팀원이 만든 해당 팀 예약 변경·취소·관련 교환 처리. 다른 팀이나 전체 관리자 권한은 부여하지 않음
- **일반 회원(`MEMBER`)**: 본인 개인·소속 팀 예약 생성, 본인 개인 예약과 본인이 생성한 소속 팀 예약 변경·취소·관련 교환 처리, 현재 소속 공연조 일정과 팀·곡 목록 조회

한 회원은 여러 정규조와 추가조에 동시에 속할 수 있습니다. **가입 전 명단과 로그인 계정은 별도 데이터**로 관리하며, 이름이 같다는 이유만으로 계정을 자동 연결하지 않습니다. 회원 관리에서는 **전체 관리자 → 팀장 → 일반 회원** 순서로 한 번만 표시합니다. 관리자가 팀장을 겸하면 전체 관리자 영역에만 표시하고 담당 팀 정보는 유지합니다.

**관리자 지정은 가입 전·후 모두 가능합니다.** 가입 전에는 명단에서 미리 지정하면 **해당 명단의 개인 초대로 가입할 때** 관리자 권한을 적용합니다. 이미 가입한 계정은 카드의 **전체 관리자 지정**으로 승격할 수 있습니다. 기존 계정을 명단에 수동 연결하는 동작 자체는 그 계정의 현재 권한을 바꾸지 않으므로, 필요하면 연결 후 별도로 지정합니다. 지정·해제의 확인·오류·상태 재조회·완료는 같은 카드 안에서 처리하고 마지막 활성 관리자의 해제는 차단합니다.

교환 요청에는 본인 측 전체 예약의 현재 변경 권한이 필요하며, 승인·거절에는 상대 측 예약의 현재 권한이 필요합니다. 팀원이 교환 목록을 조회할 수 있다는 이유만으로 다른 팀원이 작성한 예약의 승인 권한을 갖지는 않습니다. 교환 후 예약 주체·최초 예약자는 승인자로 바뀌지 않으며, 두 악기 개인 90분 예약을 두 60분으로 나눌 때 악기 순서는 해당 개인 예약자가 직접 선택합니다.

서로 다른 악기를 사용하는 두 개인 60분 예약을 90분 한 건과 교환하면, 결과 개인 예약은 두 악기를 90분 전체 동안 점유합니다. 이 교환 예외에서도 악기는 최대 두 종류이며 일반 신규 개인 예약은 한 종류만 선택합니다.

활성 회원은 전체 공지를 조회하고 **본인 알림만** 조회·읽음 처리합니다. 공지 작성·수정·삭제는 전체 관리자만 가능합니다. 알림을 받았다는 이유로 다른 예약의 변경·교환 승인 권한이 생기지는 않습니다. 팀 예약 알림은 현재 활성 조원, 개인 예약 알림은 본인에게 전달하며 전체 관리자라는 이유로 모든 예약 알림을 받지는 않습니다.

Push 구독은 인증된 본인과 해당 브라우저의 로그인 세션에 연결합니다. 본인 기기만 조회·해제할 수 있고, 로그아웃하면 **해당 기기의 Push만 중단하며 다른 기기는 유지**합니다. 계정 전환 때 binding을 바꿔 이전 계정 내용 표시를 차단합니다. 회원 탈퇴 시에는 모든 로그인 세션과 모든 기기 Push 연결을 종료하고 이후 알림 수신자에서 제외합니다. 로그인 만료 자체를 구독 해제로 처리하는 별도 정책은 확정하지 않았습니다.

## 🛠 기술 스택

| 구분 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1.1, Gradle 9.8.1 |
| Backend 주요 구성 | Spring MVC, Spring Security, Spring Session JDBC, Spring Data JPA, Bean Validation |
| Database | PostgreSQL 17.11, Flyway V1~V12 |
| Push | Web Push·VAPID·AES128GCM, PostgreSQL 대기열·계획 worker |
| Frontend | Next.js 16.4.2 (App Router), React 19.3.0, TypeScript 6.0.3 |
| UI·데이터 | Tailwind CSS 4.3.3, TanStack Query 5.104.1 |
| 테스트 | JUnit(Jupiter), Testcontainers(PostgreSQL), Vitest |
| 개발·CI | Docker Compose, PowerShell, GitHub Actions |
| 운영 구성 | Ubuntu 24.04 LTS, Docker Engine/Compose, Caddy, GHCR 공개 이미지 |
| 백업·감시 | Python 3.12+, age 1.3.2, systemd, 서울 S3, 최소 IAM, CloudWatch·SNS |

백엔드는 Spring Boot BOM·`gradle.lockfile`, 프론트엔드는 `package-lock.json`을 이용해 의존성을 고정합니다. 로컬 데이터베이스는 Docker에서 실행하며, 애플리케이션은 개발 서버 또는 IDE에서 실행합니다.

## 🗂 프로젝트 구조

```text
jeongeum-band-scheduler/
├── .github/
│   ├── workflows/ci.yaml               # Backend·Frontend·합성 컨테이너·발행 계약·백업 회귀 CI
│   └── workflows/release-images.yaml   # 명시적 입력의 이미지 준비/발행
├── backend/
│   ├── src/main/java/kr/jeongeum/scheduler/
│   │   ├── auth/                        # 로그인·가입·세션 인증·원자적 CSRF 초기화
│   │   ├── common/                      # 공통 응답·보안·동시성 제어
│   │   ├── exchange/                    # 교환 요청·승인·악기 배분·만료 복구
│   │   ├── health/                      # DB 연결 상태 확인
│   │   ├── instrument/                  # 관리자 악기 고장·복구·이력
│   │   ├── invitation/                  # 개인 초대 발급·소비·해지
│   │   ├── lifecycle/                   # 회원 탈퇴·팀 해체·예약/알림/기기/세션 정리
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
│   │   ├── V10__web_push_and_reminders.sql
│   │   ├── V11__member_team_lifecycle.sql
│   │   └── V12__roster_signup_global_role.sql
│   ├── src/test/                        # 통합 테스트
│   └── build.gradle.kts
├── frontend/
│   ├── public/jeongeum-logo.jpg
│   ├── public/push-sw.js                # Push 표시·기기 binding·클릭 일회 복원, 인증 캐시 없음
│   ├── public/icons/                    # 홈 화면 앱 아이콘
│   ├── src/app/                         # 기존 화면·기기 알림 설정·manifest.ts
│   ├── src/components/                  # 공통 UI
│   ├── src/features/                    # 인증·예약·팀·종료 처리·공지·알림·Push/클릭 복원
│   ├── src/lib/                         # API 클라이언트·Query 설정
│   ├── src/providers/
│   └── package.json
├── deploy/
│   ├── compose.production.yaml          # 운영 네트워크·볼륨·자원 제한·고정 이미지
│   ├── compose.qa.yaml                  # 격리 합성 검증 설정
│   ├── .env.production.example          # 비밀값 없는 운영 설정 예시
│   ├── backup/                          # 합성 DB 백업·감시·PG 준비 systemd unit/예시
│   ├── caddy/                           # 이미지·HTTPS/내부 프록시 설정
│   └── postgres/init-app.sh             # 앱 전용 DB 계정 초기화
├── scripts/                             # 로컬 실행·컨테이너/이미지·암호화 백업/복구 검증
├── tools/environment-check/             # Java·Docker·DB 환경 검증
├── docs/                                # 정책·API·ERD·보안·테스트·배포 문서
├── compose.yaml                         # 로컬 PostgreSQL 17.11
├── AGENTS.md                            # 프로젝트 작업 규칙
└── README.md
```

## 운영 배포 준비

**2026-10-24 기준, [bandjeongeum.studio](https://bandjeongeum.studio/)에는 HTTPS 준비 화면만 공개되어 있습니다.** 실제 회원 데이터 이전과 예약 앱 공개 전환은 별도 승인 단계입니다. 합성 백업·자동 복구는 [PR #28](https://github.com/developer-sw/jeongeum-band-scheduler/pull/28), 비활성 운영 설정의 암호화 복구와 도구 보완은 [PR #29](https://github.com/developer-sw/jeongeum-band-scheduler/pull/29), 병합 커밋 `51bbd69`로 `main`에 반영했습니다.

| 구분 | 확인한 상태 |
| --- | --- |
| 구현·이미지 | 운영 컨테이너 [PR #23](https://github.com/developer-sw/jeongeum-band-scheduler/pull/23), 발행/공개 범위 [PR #24](https://github.com/developer-sw/jeongeum-band-scheduler/pull/24)·[#25](https://github.com/developer-sw/jeongeum-band-scheduler/pull/25), 홈 화면 이름·로고/보안 패치 [PR #27](https://github.com/developer-sw/jeongeum-band-scheduler/pull/27) main 병합 |
| 재사용할 릴리스 | `v1.0.0-rc.3`, main `a00a791`의 Linux amd64 Backend·Frontend·Caddy 공개 이미지. [실제 발행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39953900056)과 [출처/digest 기록](docs/PRODUCTION_IMAGE_RELEASE.md)을 따르고 기존 rc.2를 덮어쓰지 않음 |
| 기존 서버 | AWS Lightsail 서울, Ubuntu 24.04 LTS·2 vCPU·4GB RAM·80GB SSD. 별도 EC2·RDS·로드 밸런서 없이 기존 VM 활용 |
| 기본 구성 | 고정 IPv4 연결, Docker Engine **29.9.1**·Compose **5.6.0**, docker/containerd active·enabled, 네트워크/포트/볼륨 없는 `hello-world` 실행 확인 |
| 접근 제한 | SSH TCP22는 **Lightsail 브라우저 SSH IPv4 전용**, 제한 후 새 연결 성공. 기존 HTTP80 유지·HTTPS443 IPv4/IPv6 허용, 앱/DB·원격 Docker API 포트는 미공개 |
| 도메인·HTTPS | `bandjeongeum.studio` 등록·DNS·Caddy 공개 인증서와 준비 화면 HTTPS200 확인. 공개 예약 앱으로 전환하지 않음 |
| 정기 DB 백업 | 합성 DB만 서버에서 age 암호화해 서울 비공개 S3에 저장. 시간별 **7일**·주간별 **28일**, Versioning·기본 Governance 잠금 **7일**·SSE-S3·공개 차단·서버 최소 IAM 적용 |
| 원격 검증·감시 | 업로드가 반환한 정확한 VersionId로 Head/Get·크기/SHA 대조 후 성공 기록. 별도 5분 지표·CloudWatch 경보·SNS 이메일, ALARM/OK 실제 수신 및 미수신 평가 확인 |
| 독립 복원·설정 보관 | S3 암호문의 별도 PC PostgreSQL 복원 **10개·19.477초**. 원래 설정 **30파일 + 별도 추가 3파일**을 각각 암호화·PC 실제 복원. 복구키 PC 밖 보관·사본 확인은 사용자 확인으로 기록 |
| 비활성 운영 설정 | 새 미사용 운영 설정 **16파일**의 실제 age 암호화·Windows 독립 복원, 원본·복원 사본 각각 **51항목** 정적 검사 통과. 기존 30+3파일과 별도 자료이며 DB 복원·서버 설치·활성화 없음 |
| 재부팅·자동 복구 | 정확한 기존 합성 PG만 시작하는 source unit과 DB 준비 대기. 실제 VM 재부팅 후 **16항목** 보존, **16:00:18 KST** 원래 hourly 실행 → **16:00:24 KST** 새 S3 백업 검증, 부팅 metric·새 CloudWatch 지표·경보 OK 확인 |
| 계정·남은 작업 | Free Plan·Basic 지원 유지. 운영 조건·크레딧을 AWS에 재문의한 뒤 **사람의 답변 대기**. 새 설정의 서버 설치·Linux 권한/네트워크 충돌 확인, 운영 백업 전환·실제 회원 데이터 이전·앱 공개와 운영 origin 로그인/Push 검증은 후속 |

현재 합성 백업 원본은 **rc.2 / `8b24285`**로 생성한 DB이고, 향후 새 앱 배포 기준은 **rc.3 / `a00a791`**입니다. QA 앱·worker는 중지 상태이며 DB만 합성 백업 대상으로 유지합니다. QA Compose의 `restart: no`를 바꾸거나 전체 Compose를 켜지 않고 기존 PG 하나의 ID·이미지·볼륨·미공개 포트를 확인한 뒤 자동 시작합니다. 점검 중 PG를 계속 정지해 둘 때의 타이머/source unit 중단 순서는 [재부팅 검증 기록](docs/OPERATIONS_REBOOT_VALIDATION.md)을 따릅니다.

기존 준비 설정 복구는 **원래 30파일 암호문과 추가 3파일 암호문을 함께 사용하는 수동 복구**이며, 단일 33파일 백업·자동 설정 활성화·S3 설정 업로드가 아닙니다. 새 **비활성 운영 설정 16파일**은 별도 `inactive-production-settings` 범위를 명시한 v2 archive로 관리하며 기존 v1 범위와 구분합니다. 30+3+16을 하나의 전체 서비스 복구 결과로 합산하지 않습니다. 기존 설정에는 실제 서버 기술 자격증명이, 새 설정에는 아직 설치하지 않은 DB 비밀번호·VAPID가 포함되므로 모두 보호합니다. 복구 개인키·비밀값·덤프·계정별 정보는 Git이나 공개 로그에 넣지 않습니다. 주간 28일 보존이 28일 전체 WORM 잠금을 뜻하지 않으며 전체 RPO/RTO 달성도 아직 검증하지 않았습니다.

Free Plan 종료와 개별 크레딧의 표시 만료일은 구분합니다. AWS에 재문의했고 사람의 답변을 기다리고 있으며, 실제 회원 데이터 처리 조건과 서비스별 크레딧 적용이 확정됐다고 표현하지 않습니다. 추가 유료 자원·플랜 전환·실제 데이터 이전/서비스 전환은 별도 확인 후 진행합니다. 일반 요금·계정 조건·승인 경계는 [Lightsail 배포 계획](docs/AWS_LIGHTSAIL_DEPLOYMENT.md)과 [백업 운영 계획](docs/OPERATIONS_BACKUP_PLAN.md)을 따릅니다.

기존 개발 DB·관리자·회원·팀·예약·Docker 볼륨을 보존하고, 최종 백업·독립 복원·쓰기 동결·이전 후 대조·롤백 계획을 준비한 뒤 승인받습니다. 새 쓰기를 허용하기 전에는 준비 화면으로 돌아갈 수 있지만, **운영의 새 쓰기를 허용한 뒤에는 예전 로컬 dump를 덮어쓰지 않고 최신 운영 데이터를 보존한 별도 복구 계획이 필요**합니다. 검증한 이미지 digest와 [운영 Compose](deploy/compose.production.yaml)를 재사용하고 실제 운영 VM에서 다시 빌드하지 않습니다. 상세 실행 범위는 [운영 컨테이너](docs/PRODUCTION_CONTAINERS.md), [기존 설정 복구](docs/OPERATIONS_CONFIGURATION_RECOVERY.md), [새 비활성 설정·전환 조건](docs/INACTIVE_PRODUCTION_SETTINGS.md), [사전 점검](docs/DEPLOYMENT_PREFLIGHT.md)에 기록했습니다.

## 🚀 로컬 실행

**요구 환경:** Windows PowerShell, Java 21, Node.js 24.x / npm, Docker Desktop(WSL 2 Linux 컨테이너). 아래 명령은 **저장소 루트**에서 실행합니다. 서버를 기동한 뒤 아래 기본 주소로 접속하며, 프로세스 상태는 연결 확인 스크립트로 점검합니다.

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

기본 백엔드 주소는 `http://127.0.0.1:8080`입니다. 이 스크립트는 개발 DB 접속 정보를 프로세스 환경변수로 전달하고 `local` 프로필로 `bootRun`을 실행합니다. **기동 시 Flyway가 V1~V12 중 아직 적용되지 않은 마이그레이션을 개발 DB에 적용합니다.** 기존 DB를 업데이트할 때는 먼저 백업과 격리 복원 검증을 확보하고 기존 비밀번호 파일·계정·데이터 볼륨을 유지하세요. 이미 적용된 마이그레이션을 강제로 재적용하거나 데이터를 초기화하지 않습니다.

Push·시작 알림 worker와 외부 발송은 기본 비활성입니다. **외부 Push 발송을 끈 채** 로컬 worker를 검증하려면 해당 터미널에서 아래 설정으로 실행합니다. 기존 프로세스가 있는지 확인하고 중복 기동하지 마세요.

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

웹 화면: **[http://127.0.0.1:3000](http://127.0.0.1:3000)**

스마트폰 화면은 PC와 같은 네트워크에서 LAN 실행 옵션으로 확인할 수 있습니다. 예시 IP를 현재 PC의 Wi-Fi IPv4 주소로 바꾸고, 스마트폰에서 `http://PC의-IP:3000`을 엽니다. 백엔드·DB는 localhost를 유지합니다. 네트워크/방화벽 조건은 [개발 환경 안내](docs/DEV_ENVIRONMENT.md)를 따릅니다.

```powershell
.\scripts\Start-Frontend.ps1 -Hostname 0.0.0.0 -AllowedDevOrigins '192.168.0.10'
```

LAN HTTP는 화면 확인용이며 Push·service worker의 보안 컨텍스트 조건을 충족하지 않습니다. 실제 스마트폰 Push는 신뢰된 HTTPS 주소가 필요합니다.

로그인 후 [홈](http://127.0.0.1:3000/)에서 달력·합주실 시간표·빠른 예약을 사용합니다. [내 공연조 일정](http://127.0.0.1:3000/my-schedule) 메뉴에서는 현재 소속 공연조의 오늘~서버 예약 가능 마지막 날짜까지 실제 확정 예약을 확인합니다. 일반 예약과 실제 생성된 고정 회차를 함께 표시하며, 팀 필터와 날짜·시간순 목록을 제공합니다. 개인 예약·취소 회차·미생성 미래 패턴은 확정 목록에서 제외합니다. 변경·취소·상세·과거 이력은 [예약·이력](http://127.0.0.1:3000/reservations), 계정·로그아웃은 [내 계정](http://127.0.0.1:3000/account), 전체 기능 진입은 [더보기](http://127.0.0.1:3000/more)에서 확인합니다. 전체 관리자는 [고정 배정](http://127.0.0.1:3000/admin/recurring-assignments) 메뉴에서 배정을 관리합니다.

예약 변경은 예약 목록의 **예약 변경** 또는 다이어리 상세에서 진입합니다. 현재 권한으로 변경할 수 있는 시작 전 예약을 골라 새 날짜·시간·개인 악기를 선택하고, 변경 전후를 확인한 뒤 확정합니다. 성공 후 시간표·내 예약·내 공연조 일정을 갱신하며 원본 이력에서 후속 예약을 조회할 수 있습니다.

예약 교환은 [예약 교환](http://127.0.0.1:3000/exchanges), **더보기 → 예약 교환** 또는 예약 상세의 **이 예약으로 교환 요청**에서 진입합니다. 양측 후보를 선택해 요청하고 상대 측 권한자가 승인합니다. 대기 중 원래 예약을 유지하며, 개인 악기 배분이 필요한 경우 해당 예약자가 순서를 저장한 뒤 승인할 수 있습니다. 거절·철회·만료·무효화 상태와 완료 후속 예약도 확인합니다.

전체 관리자는 [악기 고장 관리](http://127.0.0.1:3000/admin/instrument-faults)에서 여섯 악기의 현재 상태, 고장 등록·복구와 처리 이력을 관리합니다. 데스크톱 **관리 → 악기 고장 관리**, 모바일 **더보기 → 악기 고장 관리**로 진입합니다. 고장 등록은 기존 예약을 자동 취소하지 않으며, 고장 악기를 새로 배치하는 개인 예약·변경·교환을 제한하고 팀 합주는 경고와 함께 허용합니다.

전체 관리자는 [회원·가입 전 명단 관리](http://127.0.0.1:3000/admin/members)에서 세 역할 영역별 명단과 계정을 확인하고 각 카드에서 관리자 지정·해제를 완료합니다. 가입 전 지정 후에는 [명단별 초대 발급](http://127.0.0.1:3000/admin/invitations)에서 해당 명단의 개인 초대를 발급합니다. 사전 지정과 가입한 계정의 사후 지정 모두 지원하며, 다른 관리자가 상태를 바꾸면 해당 카드에서 최신 정보를 다시 확인합니다.

회원 탈퇴는 전체 관리자의 [회원 관리](http://127.0.0.1:3000/admin/members), 팀 해체는 [팀 목록](http://127.0.0.1:3000/teams)에서 해당 팀 상세의 관리자 영역으로 진입합니다. 대상·사유와 정리/보존 범위를 확인한 뒤 확정합니다. 회원 탈퇴는 개인 예약과 전체 세션/기기를 종료하고 존속 팀 예약을 유지하며, 팀 해체는 해당 팀 예약·반복 배정을 종료합니다. 마지막 활성 전체 관리자는 탈퇴할 수 없습니다. 성공 응답을 잃었을 때는 같은 요청으로 결과를 재확인합니다.

[전체 공지](http://127.0.0.1:3000/notices)와 [내 알림함](http://127.0.0.1:3000/notifications)은 로그인 후 데스크톱 **공지·알림**, 모바일 **더보기**에서 진입합니다. 전체 관리자는 공지를 작성·수정·삭제하고 중요 공지를 고정할 수 있습니다. **처음 중요 공지로 작성할 때만** 전체 활성 회원에게 알림을 만들며, 일반 공지 작성·수정·중요 전환·고정 해제·재고정은 재알림하지 않습니다.

알림함은 본인의 공지·예약·교환 알림과 미읽음 수, 미읽음 필터·이전 항목을 제공합니다. 개별 읽음 또는 목록을 확인한 **서버 시각까지 모두 읽음**을 적용할 수 있으며 이후 도착한 알림은 미읽음으로 남습니다. 관련 화면에서 최신 상태와 현재 권한을 다시 검사합니다. 성공 응답을 잃은 쓰기는 원래 요청으로 직접 재확인하며 오프라인 변경을 대기열에 넣거나 자동 실행하지 않습니다.

[알림 설정](http://127.0.0.1:3000/notifications/settings)은 데스크톱 **공지·알림 → 알림 설정**, 모바일 **더보기 → 기기 알림·홈 화면 설치**, **내 계정 → 기기 알림 설정**에서 진입합니다. 기기별 알림 켜기/끄기, 서버 발송 중단·HTTP·권한 거절·미지원 안내를 제공합니다. 페이지 진입만으로 권한을 요청하지 않으며 알림을 허용하지 않아도 예약과 내 알림함을 사용할 수 있습니다. iPhone/iPad는 iOS/iPadOS 16.4 이상에서 Safari의 **홈 화면에 추가**로 설치한 웹 앱을 열고 사용자 버튼으로 알림을 허용해야 합니다. [WebKit 공식 조건](https://webkit.org/blog/13878/web-push-for-web-apps-on-ios-and-ipados/)

시작 알림은 예약 블록 시작 1시간 전을 기준으로 합니다. 생성·변경·교환 때 예정 시각이 이미 지났거나 현재와 같으면 별도의 시작 계획을 만들지 않고, 변경·취소 시 이전 계획과 대기 발송을 정리합니다. 외부 발송을 끈 개발 설정에서는 서버 중단 안내를 표시합니다.

알림 클릭이 마지막 화면에 머무를 때는 **알림 설정 → 알림 열기 문제 확인 → 진단 확인**에서 worker 버전·최근 클릭 감지·고정 상태 코드·창 수·목적지 일치 여부를 확인할 수 있습니다. 실제 클릭에서만 저장한 내부 목적지를 120초 이내 한 번 복원하고, 현재 계정·기기·binding을 다시 확인합니다. Push 도착만으로 화면을 이동시키지 않으며 진단에 계정·본문·endpoint·인증 정보·목적지 원문을 넣지 않습니다.

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

**13. 회원·팀 종료와 연결 데이터의 원자성**

회원 탈퇴·팀 해체와 남은 예약 취소·점유/한도·교환·반복 배정·예정 알림·탈퇴에 따른 기기/세션 정리·관리 이력을 한 트랜잭션으로 처리합니다. 탈퇴자의 존속 팀 예약과 완료/작성자/계보 이력은 보존합니다. 현재 관리자·마지막 관리자·version을 잠금 후 검사하고 실패 시 전체를 롤백합니다. 같은 키·본문의 재확인은 같은 결과를 반환하며 탈퇴와 경합한 늦은 로그인이 세션을 다시 만들지 못하게 합니다.

**14. 가입 전 역할 지정과 기존 계정 권한의 구분**

가입 시 역할은 잠근 개인 초대의 명단 ID와 DB에 저장된 예정 권한으로 정하며, 공개 가입 입력으로 관리자 권한을 지정할 수 없습니다. 예정 권한 변경은 현재 관리자·미연결 상태·version을 검사하며, 가입·수동 연결과 공통 잠금 순서를 따릅니다. 기존 계정의 수동 연결은 현재 권한을 보존하고 사후 역할 변경은 별도 관리 작업으로 처리합니다. 미가입 예정 관리자는 마지막 활성 관리자 수에 포함하지 않습니다.

## 🧪 테스트 및 CI

2026-10-24 갱신. 기존 앱 소스 `main` `8b24285`의 [CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39024812366)에서 **Backend 368건·Frontend 334건, 총 702건**과 운영 합성 검사·발행 계약을 확인했고, 이후 PR #27의 홈 화면 이름·로고와 Next.js 보안 패치를 main에 반영했습니다. 아래 앱 수치는 기존 실행 기록이며 백업 도구 검사와 합산하거나 README 갱신 중 새 앱 테스트를 실행한 것으로 표시하지 않습니다. PR #29의 최종 HEAD `3bd6e2b`의 [CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/40094359615)에서 5개 job·오프라인 회귀 89개·합성 운영 검사 57개의 성공을 확인했으며 병합 소스의 main CI 결과는 별도로 확인합니다.

- **Backend:** JUnit/PostgreSQL Testcontainers **20개 suite·368건 통과**, 실패·오류·skip 0, `compileJava`·`bootJar` 통과. 명단 통합 25건·가입 예정 권한 13건과 탈퇴/해체·인증·90일 세션·CSRF·예약·변경·교환·반복·고장·공지·알림·Push/worker 회귀 포함
- **Frontend:** **23개 파일·334건 통과**, worker 35건·앱 클릭 복원 21건 포함. TypeScript·ESLint(경고 0)·Next.js production build 20개 route 확인. 로컬 격리 webpack 빌드와 원격 CI의 기본 Turbopack 빌드를 구분
- **GitHub Actions:** PR #25 병합 소스 `8b24285`의 [main CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39024812366)에서 기존 4개 job 성공을 확인한 기록을 보존. 현재는 Backend·Frontend·합성 운영 컨테이너·이미지 발행 계약에 **오프라인 백업 회귀**를 더한 5개 job으로 구성하며 [PR #29 최종 HEAD CI](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/40094359615)의 5개 job 성공을 확인. PR CI와 병합 후 main CI는 별도 결과로 구분
- **운영 컨테이너·발행:** 격리 합성 HTTPS의 권한·예약·재시작·별도 DB 복원 **57개 검사**, 이미지 발행 Node 계약 **38건**·PowerShell export **15항목** 통과. rc.3을 새 배포 기준으로 사용하며 [이미지 발행 기록](docs/PRODUCTION_IMAGE_RELEASE.md)의 출처·최종 digest를 확인. 기존 [rc.2 발행](https://github.com/developer-sw/jeongeum-band-scheduler/actions/runs/39025711724)과 rc.1 최초 실패 이력도 보존
- **백업 도구 오프라인 회귀:** fake Docker/AWS/age/DB 기반 정기 백업 **27개**, 설정 백업 **41개**, 기존 합성 PG 준비 **21개**, 총 **89개**를 [PR #29](https://github.com/developer-sw/jeongeum-band-scheduler/pull/29)의 Linux CI에서 모두 통과(실패·건너뜀 0). Windows의 설정 백업 회귀는 **40개 통과·hardlink 1개 건너뜀**. 기존 v1 archive 호환·신규 범위의 명시적 opt-in·정확한 타입·키/경로 보호를 검사하며, PR #28의 이전 75개 및 실제 AWS·DB·age 검증과 구분
- **서버 밖 DB 백업·독립 복원:** 실제 S3 exact-version Put/Head/Get·크기/SHA, 중복 업로드·삭제·보존 변경·범위 밖 요청 거부와 실패 시 이전 성공 보존 확인. S3 암호문을 별도 PC PostgreSQL에 복원해 **10개·19.477초**, 39테이블·29시퀀스·V1~V12·역할/소유권·PG 재시작 보존 확인. 앞선 수동 복원 **20개·약 83초**와 다른 실행이며 전체 서비스 RTO가 아님
- **설정 암호화·복구:** 원래 명시적 30파일의 실제 Windows 복원·비공개 통합 **21개**, disk SHA·native ACL과 별도 Linux 파일 도구의 metadata 재현 확인. 추가 부팅 설정 3파일도 별도 암호문으로 Windows 인증 복원·SHA/ACL 확인. 두 archive는 수동으로 함께 사용하며 helper 자체 Linux restore·설정 활성화는 실행하지 않음
- **비활성 운영 설정 복구:** 새 미사용 설정 **16파일**을 실제 age v1.3.2로 암호화·Windows 인증 복원하고 전체 SHA·원본 불변·현재 사용자/SYSTEM native ACL 확인. 원본·복원 사본 각각 **51항목** 정적 설정 검사와 실제 실패 거부 **6종**을 확인. Compose는 정적 렌더링만 실행했으며 실제 Linux 설치·앱 기동·DB 복원·Push 발송 없음. 기존 준비 설정 30+3파일 복원 및 Linux fake 회귀와 구분
- **실제 VM 재부팅·감시:** 컨테이너 5개·모든 볼륨·원본 설정·39테이블/29시퀀스·Flyway 전체 이력·역할/인증/권한 지문 보존 **16항목** 통과. PG·HTTPS 준비 Caddy auto healthy, QA 앱 중지 유지. 원래 hourly의 **16:00:18→16:00:24 KST** 새 S3 백업 검증, 부팅 metric·새 CloudWatch datapoint·원래 설정/최종 OK 확인. 별도 stale ALARM/OK와 SNS 양쪽 메일 실제 수신은 선행 시험 기록
- **관리자 지정·분류:** 가입 전 지정/해제·개인 초대 권한 적용·공개 역할/명단 ID 위조 차단·수동 연결의 기존 권한 보존·가입/연결/지정 경합·후반 실패 rollback·마지막 활성 관리자 보호 확인. 관리자 우선 단일 표시·동명이인 분리·카드 내 오류/재조회/완료·그룹 이동 후 포커스·중복 실행 차단도 검증
- **예약·교환·고장·공지 경합:** 실제 PostgreSQL 잠금 timeout·후반 저장 실패의 전체 rollback, 동일 키 재확인·중복 방지, 현재 권한·점유·한도·계보 보존 확인. 최초 중요 작성만 알림·수신자 격리·읽음 시각 경계 포함
- **실제 스케줄러·재시작:** Spring Boot 종료·새 JVM 재시작과 데이터 보존, 자정 cron 등록·누락 날짜 복구·자동 재시도, 독립 JVM의 시작 계획·발송 lease 복구·중복 방지 확인
- **V11·개발 DB 보존:** populated V10→V11 업그레이드와 기존 컬럼/행 보존·재적용 0건·Flyway validate 확인. 사용자 승인으로 실제 개발 DB에 V11 한 건만 적용하고 기존 계정·예약·세션·Flyway·Docker 볼륨 보존. 최근 실기기 시험 전후에도 기존 **39개 테이블 전체 행 지문·세션·시퀀스·볼륨 일치** 확인
- **V12·개발 DB 보존:** 새 비공개 V11 백업을 네트워크 없는 임시 PostgreSQL에 복원해 원래 행·세션·시퀀스 일치 확인. 앱·worker 중지 상태에서 표준 Flyway로 V12 한 건만 적용하고 새 컬럼/이력 행을 제외한 원래 모든 행 필드·세션·시퀀스·V1~V11 이력·Docker 볼륨 보존 확인. 관리자 지정/해제의 쓰기 시험은 합성 계정에서 수행
- **로컬 백업 복원:** V10 적용 직전 V9 백업 **139,986bytes**, V11 적용 직전 V10 백업 **153,172bytes**의 checksum·archive를 확인하고 각각 네트워크/호스트 포트가 없는 임시 PostgreSQL에 실제 복원. 당시 36개/38개 테이블의 행·세션·시퀀스 일치 확인. V12 적용 전 새 V11 백업의 격리 복원까지 확인했으며 운영 외부 백업 복원과 구분
- **탈퇴·해체 원자성:** 마지막 관리자·동시 탈퇴·로그인/예약/Push 재등록 경합, 실제 10초 잠금 timeout과 후반 INSERT 실패의 전체 rollback, 개인/팀 예약·교환·반복·시작 계획 정리와 존속 팀·곡·작성자/완료 이력 보존 확인. 같은 DB의 새 JVM 재시작 후 39개 테이블·세션·시퀀스 일치
- **실제 브라우저:** 격리 DB·합성 계정으로 기존 다이어리·예약/변경/교환·고장·공지/알림을 검증하고, 이어 **탈퇴·해체 후속 28항목**을 별도로 확인. 관리자/회원 권한, 마지막 관리자 보호, 확인 체크·사유 제한, Tab/Shift+Tab·Escape·초점 복원, 성공 응답 유실·동일 요청 재확인과 자동 제출 차단 확인
- **Push·시작 worker·CSRF:** VAPID 서명·AES128GCM 복호화, 재시도/만료/재등록·수신자/계보·트랜잭션 rollback, scheduler 응답성·독립 JVM·동시 CSRF 초기화/ID 회전·이전 토큰 거절 검증. CSRF 보호와 90일 로그인 유지 보존
- **클릭 복원:** 실제 클릭에만 120초·일회 목적지 저장, 현재 기기/계정·허용 URL 재검사, 임시 binding 해제 보존·로그아웃/계정 전환 차단, 만료·오염 값 차단. 두 native 창이 6초 멈춰도 기기 binding 복원과 일회 소비가 즉시 끝나는 회귀 확인
- **실기기 중요 공지:** 승인된 임시 HTTPS·합성 계정에서 iPhone 홈 화면 설치와 두 기기 알림 권한, **PC Chrome·iPhone 홈 화면 앱**의 중요 공지 수신·클릭 후 해당 공지 열기 확인. 제공자 `SENT`와 사용자 OS 수신/클릭 확인을 별도 근거로 기록
- **실기기 시작 알림:** 별도 시험 DB와 흐르는 제어 Clock·실제 스케줄러로 **17:00 예약의 16:00 알림**을 생성. iPhone 잠금 화면 수신·클릭 후 해당 예약 열기와 중복 없음 확인. PC 시작 알림이나 실제 시계를 한 시간 기다린 검증으로 확대하지 않음
- **기기별 로그아웃:** iPhone만 로그아웃한 뒤 해당 구독/세션 종료·추가 Push 작업 0건, **PC 로그인·구독·실수신/클릭 유지**를 확인. 기기 이름/등록 순서 대신 같은 구독의 전후 상태로 식별
- **회원 탈퇴 실기기:** 관리자 탈퇴 API가 구독 **2개**·인증 세션 **3개**(두 실기기+별도 API 검증용 1개)를 종료. 이전 쿠키의 보호 API 401·재로그인 거부·동일 요청의 단일 작업과 관리자/존속 팀 보존 확인. 새 중요 공지의 해당 회원 알림함·Push 작업 **0건**, 사용자도 두 기기 로그인 종료·재로그인 거부·새 알림 없음을 확인. 이 실기기 환경의 예약은 0건이며 예약 정리는 별도 PostgreSQL·브라우저 검증 결과를 따름

다이어리 UI는 375~1920px의 8개 viewport에서 레이아웃·모바일 바텀시트를 확인했고 인사말은 320/375/390/768/1440px에서 확인했습니다. 예약 변경·교환·고장·공지·알림함·기기 설정·탈퇴/해체 화면은 **320/390/768/1440px**에서 가로 넘침이 없음을 확인했으며 320×568px 확인창 내부 스크롤도 유지했습니다. 역할별 명단은 320/390/768/1440px, 카드 내 확인과 최신 메인·헤더 보완은 320/390/1440px에서 확인했습니다. 모바일 제목·소개 시작점과 로고 중앙 정렬, 작아진 더보기 표시 영역과 44px 터치 영역, PC 인사말·공연조 선택 상단 정렬을 확인했습니다. 추가 달력 아이콘은 DB 없는 현재 컴포넌트 SSR·1360px 브라우저에서 월 이동 버튼과 겹치지 않음을 확인했습니다. 로고·기타 마스코트·작은 `JEONGEUM · SENTIMENTAL MUSIC`·닉네임 인사말·연한 로그인 ID/역할을 보존했으며 이번 화면 크기 확인을 새 스마트폰 실기기 검증으로 간주하지 않습니다.

검증 범위와 재현 절차는 [명단·관리자 지정](docs/MEMBER_TEAM_MANAGEMENT.md), [다이어리](docs/DIARY_UI_REDESIGN.md), [예약 변경](docs/RESERVATION_CHANGES.md), [예약 교환](docs/RESERVATION_EXCHANGES.md), [악기 고장](docs/INSTRUMENT_FAULTS.md), [공지·알림함](docs/NOTICES_NOTIFICATIONS.md), [Web Push·시작 알림](docs/WEB_PUSH_REMINDERS.md), [회원 탈퇴·팀 해체](docs/MEMBER_TEAM_LIFECYCLE.md)에 기록했습니다.

**미완료 범위:** AWS 사람의 답변에 따른 Free Plan의 실제 회원 운영 조건 검토, 새 비활성 설정의 서버 설치·Linux 권한/네트워크 충돌 확인, 운영 백업 전환·최종 쓰기 동결·실제 데이터 이전·예약 앱 공개, 자동 PC 복사·장시간 VM 장애/90분 경보 전달 지연·전체 RPO/RTO·실제 백업 비용 상계·VM 부하/실서비스 관측입니다. 새 설정의 로컬 생성·암호화 복원 완료가 이 운영 검증을 대신하지 않습니다. 운영 origin Push·Android 실기기 설치/수신/클릭과 실제 OS의 오프라인·가상 키보드·스크린리더·200% 확대 검증도 남아 있습니다. 기기별 로그아웃은 iPhone 중단→PC 유지 방향을 확인했으며 모든 OS/실행 상태를 검증한 것은 아닙니다. 이전 V7~V9 적용 전 백업 파일 자체의 복원은 미검증이며 V10/V11/V12 적용 전 로컬 복원·새 합성 S3 복원과 구분합니다.

앞선 실기기 Push 시험의 계정·임시 HTTPS 환경은 종료했습니다. 현재 서버에서는 합성 PostgreSQL을 정기 백업 대상으로 유지하고 QA 앱·worker는 중지 상태입니다. 실제 개발 계정·예약·Docker 영속 볼륨을 보존하며 공개 시험 계정은 제공하지 않습니다. iPhone 클릭 문제의 재발과 후속 복원을 확인했지만 단말의 상세 native 이벤트 기록은 수집하지 않아 모든 iOS 상태의 해결을 보장하지 않습니다. 탈퇴 후 미수신은 사용자 관찰이며 이미 제공자가 수락한 이전 알림을 철회할 수 없습니다. 네트워크·OS 지연·발송 후 기록 전 중단의 외부 중복 가능성도 남습니다.

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

저장소 루트에서 백업 도구의 오프라인 회귀만 실행하려면 다음을 사용합니다. 실제 DB·AWS 자격증명·네트워크를 사용하지 않습니다.

```powershell
python -I -B scripts/production-backup.test.py
python -I -B scripts/configuration-backup.test.py
python -I -B scripts/backup-source-readiness.test.py
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
- **기기 Push:** 사용자 버튼으로 권한/구독 연결, 로그아웃 시 해당 기기만 중단·다른 기기 유지. 회원 탈퇴 시 전체 기기/세션 종료·새 알림 수신 제외. 미설치·권한 거절·미지원이어도 알림함과 예약은 사용 가능
- **탈퇴·팀 해체:** 전체 관리자가 처리. 탈퇴는 개인 예약·전체 기기/세션을 정리하고 존속 팀 예약 유지, 해체는 팀 예약·반복 배정 종료. 과거/완료 이력·최초 예약자·곡 보존, 마지막 활성 관리자 보호

운영 후속 범위는 무료 플랜 운영 조건 확인·새 비활성 설정의 서버 설치와 운영 검증·운영 백업 전환·승인된 실제 데이터 이전과 예약 앱 공개·실서비스 관측·Android 등 추가 실기기 검증입니다. 고장 등록/복구 알림은 확정 필수 이벤트가 아닌 별도 제안입니다.

자세한 제약, 예외, 동시성 모델은 [예약 규칙](docs/RESERVATION_RULES.md)과 [데이터 모델](docs/ERD.md)에 정리했습니다.

## 📚 기술 문서

| 문서 | 내용 |
| --- | --- |
| [PROJECT_CONTEXT](docs/PROJECT_CONTEXT.md) | 요구사항·역할·운영 정책 및 설계 방향 |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | 시스템 구성, 인증, 동시성 및 알림 구조 |
| [API](docs/API.md) | API 계약과 권한·오류 응답 |
| [ERD](docs/ERD.md) | 현재·계획 데이터 관계와 제약 |
| [AUTHENTICATION](docs/AUTHENTICATION.md) | 초대·가입·로그인, 90일 세션, 최초 관리자 절차 |
| [MEMBER_TEAM_MANAGEMENT](docs/MEMBER_TEAM_MANAGEMENT.md) | 가입 전/후 관리자 지정·V12·역할별 명단·카드 내 확인, 계정 연결·복수 팀·팀장·곡 관리 및 검증 |
| [RESERVATION_RULES](docs/RESERVATION_RULES.md) | 예약 블록, 한도, 반복·변경·교환 정책 |
| [RESERVATION_FOUNDATION](docs/RESERVATION_FOUNDATION.md) | 기본 예약·취소·점유·한도 구현 및 검증 |
| [RECURRING_ASSIGNMENTS](docs/RECURRING_ASSIGNMENTS.md) | 반복 배정·회차 예외·날짜 개방·내 공연조 일정·V5 적용 및 검증 |
| [RESERVATION_CHANGES](docs/RESERVATION_CHANGES.md) | 단건 변경 API·V6 계보·권한/한도/점유·원본 보존·경합·실제 브라우저 검증 |
| [RESERVATION_EXCHANGES](docs/RESERVATION_EXCHANGES.md) | 교환 요청·승인·악기 배분·V7·양측 원본 보존·경합·재시작·브라우저 검증 |
| [INSTRUMENT_FAULTS](docs/INSTRUMENT_FAULTS.md) | 관리자 고장 등록·복구·이력·V8·예약 연동·응답 유실 재확인·데이터 보존 검증 |
| [NOTICES_NOTIFICATIONS](docs/NOTICES_NOTIFICATIONS.md) | 전체 공지·개인 알림함·예약/교환 사건 연동·V9·권한/중복/롤백·재시작·브라우저·개발 DB 적용 검증 |
| [WEB_PUSH_REMINDERS](docs/WEB_PUSH_REMINDERS.md) | 기기별 Push·시작 알림·CSRF·V10·서명/암호화·worker 복구·PC/iPhone 실수신·클릭 복원·로그아웃/탈퇴·운영 검증 한계 |
| [MEMBER_TEAM_LIFECYCLE](docs/MEMBER_TEAM_LIFECYCLE.md) | 관리자 탈퇴·팀 해체·V11·예약/교환/반복/알림/세션 정리·응답 유실 재확인·브라우저/실기기·백업/데이터 보존 검증 |
| [DIARY_UI_REDESIGN](docs/DIARY_UI_REDESIGN.md) | 다이어리 UI 구현 단계·기존 기능 보존·반응형·실제 브라우저 검증·재개 방법 |
| [UX](docs/UX.md) | 데스크톱/모바일 화면 계획과 브랜드 |
| [TEST_PLAN](docs/TEST_PLAN.md) | 기능·보안·경합·운영 검증 항목 |
| [NOTIFICATIONS](docs/NOTIFICATIONS.md) | 알림함·Web Push·시작 전 알림 정책과 구현/운영 검증 구분 |
| [INITIAL_SETUP](docs/INITIAL_SETUP.md) | 백엔드·프론트엔드 초기 설정과 검증 |
| [DEV_ENVIRONMENT](docs/DEV_ENVIRONMENT.md) | 로컬 DB·Docker·JDK와 선택적 스마트폰 LAN 접속 안내 |
| [DEPLOYMENT](docs/DEPLOYMENT.md) | 현재 배포 상태·데이터/origin 전환·운영 비용과 과거 비교 |
| [DEPLOYMENT_PREFLIGHT](docs/DEPLOYMENT_PREFLIGHT.md) | 소스 사전 점검·환경변수·배포 전 검증·기존 데이터 보존 |
| [PRODUCTION_CONTAINERS](docs/PRODUCTION_CONTAINERS.md) | 운영 Docker·Compose·Caddy·secret/네트워크·합성 HTTPS/재시작/복원 57개 검사 |
| [PRODUCTION_IMAGE_RELEASE](docs/PRODUCTION_IMAGE_RELEASE.md) | 공개 이미지 발행·출처/버전·digest·홈 화면 이름/로고·수동 발행과 권한 경계 |
| [AWS_LIGHTSAIL_DEPLOYMENT](docs/AWS_LIGHTSAIL_DEPLOYMENT.md) | 서울 Lightsail·도메인/HTTPS 준비·실제 재부팅·Free Plan 조건·남은 배포 순서 |
| [OPERATIONS_BACKUP_PLAN](docs/OPERATIONS_BACKUP_PLAN.md) | 합성 DB의 S3·최소 IAM·보존/잠금·정기 백업/감시·PC 독립 복원·비용/승인 경계 |
| [OPERATIONS_CONFIGURATION_RECOVERY](docs/OPERATIONS_CONFIGURATION_RECOVERY.md) | 서버 준비 설정 30파일·추가 3파일의 별도 암호문·PC 인증 복원·키/권한·복구 한계 |
| [INACTIVE_PRODUCTION_SETTINGS](docs/INACTIVE_PRODUCTION_SETTINGS.md) | 새 비활성 운영 설정 16파일·미사용 비밀값·명시적 v2 범위·실제 age 복원·51항목 정적 검사·활성화/이전 승인 조건 |
| [OPERATIONS_REBOOT_VALIDATION](docs/OPERATIONS_REBOOT_VALIDATION.md) | 실제 VM 재부팅 16항목·기존 PG 자동 시작·정규 hourly·감시/HTTPS 복구·유지보수 절차 |
| [백업 실행 자산](deploy/backup/README.md) | 합성 DB 전용 실행기·systemd 서비스/타이머·예시 설정·운영 전제 |
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
| [#17](https://github.com/developer-sw/jeongeum-band-scheduler/pull/17) | 기기별 Web Push·시작 1시간 전 알림·CSRF 동시성 보완·V10·325/214건·다중 JVM·개발 백업 복원, 기존 LAN 변경 별도 커밋 |
| [#18](https://github.com/developer-sw/jeongeum-band-scheduler/pull/18) | Web Push·V10·검증 범위를 반영한 README 갱신 |
| [#19](https://github.com/developer-sw/jeongeum-band-scheduler/pull/19) | 관리자 회원 탈퇴·팀 해체·V11·예약/알림/기기/세션 정리·Push 클릭 120초 일회 복원·352/289건·브라우저 28항목·PC/iPhone 실기기 확인 |
| [#20](https://github.com/developer-sw/jeongeum-band-scheduler/pull/20) | 탈퇴·팀 해체·실기기 Push 확인·V11·검증 상태를 반영한 README 갱신 |
| [#21](https://github.com/developer-sw/jeongeum-band-scheduler/pull/21) | 가입 전 관리자 지정·개인 초대 권한 적용·역할별 명단·카드 내 권한 확인·V12·다이어리/모바일 헤더 보완·368/334건 |
| [#22](https://github.com/developer-sw/jeongeum-band-scheduler/pull/22) | 관리자 지정·다이어리 UI·V12·검증 기록을 반영한 README 갱신 |
| [#23](https://github.com/developer-sw/jeongeum-band-scheduler/pull/23) | 운영 Docker·Compose·Caddy·secret 파일·격리 합성 HTTPS/재시작/별도 DB 복원 57개 검사 |
| [#24](https://github.com/developer-sw/jeongeum-band-scheduler/pull/24) | 검증 이미지 전달·수동 준비/발행 workflow·SHA/version tag·출처와 digest 검증 |
| [#25](https://github.com/developer-sw/jeongeum-band-scheduler/pull/25) | 명시적 이미지 공개 범위·조기 검사·계약 보완. 이후 rc.2 공개 이미지 3개 발행 성공 |
| [#26](https://github.com/developer-sw/jeongeum-band-scheduler/pull/26) | Lightsail 기본 구성·Free Plan·배포 준비 상태를 반영한 README 갱신 |
| [#27](https://github.com/developer-sw/jeongeum-band-scheduler/pull/27) | 홈 화면 설치 이름 `정음`·따뜻한 로고 PNG·설치 metadata와 Next.js 보안 패치. 이후 rc.3 이미지 발행 |
| [#28](https://github.com/developer-sw/jeongeum-band-scheduler/pull/28) | 합성 DB 암호화 백업·S3 검증·설정 복구·기존 PG 부팅 준비·75개 회귀 CI와 운영 README 최신화 |
| [#29](https://github.com/developer-sw/jeongeum-band-scheduler/pull/29) | 비활성 운영 설정의 명시적 opt-in·기존 v1 호환·새 16파일 암호화 복구 기록·89개 오프라인 회귀 |

현재는 **핵심 기능 구현 후 운영 준비 검증 단계**입니다. 공개 HTTPS 준비 화면과 합성 DB의 서버 밖 백업·독립 복원·실제 VM 재부팅, 새 비활성 설정의 로컬 암호화 복원을 확인했습니다. AWS 사람의 답변 검토·새 설정의 서버 설치 및 운영 검증·최종 데이터 이전 검증과 별도 승인을 거쳐 예약 앱을 공개할 예정입니다. 동아리 대상 실제 서비스 사용 지표는 아직 없습니다.

---

<div align="center">
  <sub>Built for JEONGEUM · SENTIMENTAL MUSIC</sub>
</div>
