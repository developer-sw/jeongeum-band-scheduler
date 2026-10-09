# 정음 합주실 예약 서비스

밴드 동아리 정음(JEONGEUM)의 약 45명이 사용할 웹/PWA 서비스다. 프로젝트명은 `jeongeum-band-scheduler`이며, Java/Spring 백엔드 포트폴리오와 실제 동아리 운영을 함께 목표로 한다.

## 현재 단계

- 2026-10-09: 사용자가 설계 문서 정리 작업을 승인했다.
- 초기 설계 문서 11개를 작성하고 정책·권한·ERD·API·알림의 정합성을 검토했다. 내부 파일 링크와 JSON 예제 검사도 수행했다.
- GitHub 재인증, 로컬 main 초기화, [비공개 저장소](https://github.com/developer-sw/jeongeum-band-scheduler) 생성, origin 연결과 첫 commit·push를 완료했다. 원격 기본 브랜치와 커밋 일치도 확인했으며 결과는 [GitHub 설정 기록](docs/GITHUB_SETUP.md)에 남겼다.
- Docker Linux 엔진을 정상화하고 localhost 전용 PostgreSQL Compose와 독립 Java 환경 검증 도구를 준비했다. 실행 및 확인 결과는 [로컬 개발환경](docs/DEV_ENVIRONMENT.md)에 기록한다.
- backend/frontend 초기 구성, JDBC 세션 Flyway migration, 같은 origin의 API 연결과 기본 CI를 준비했다. 실행 명령과 검증 결과는 [초기 구성 안내](docs/INITIAL_SETUP.md)에 기록한다.
- 승인된 초대·가입·로그인·권한 기반의 구현·초기 관리자 절차와 검증은 [인증 안내](docs/AUTHENTICATION.md)에 기록한다.
- 가입 전 명단·계정 연결, 회원·팀·팀장·팀별 곡 관리를 구현하고 2026-10-10 로컬 검증을 완료했다. 결과와 남은 범위는 [회원·팀 관리 안내](docs/MEMBER_TEAM_MANAGEMENT.md)를 따른다. 사용자가 `codex/team-management`의 커밋·Push·main 대상 PR 생성을 승인했다. main 병합은 별도 승인을 받는다.
- 도메인 구매와 서버 생성은 아직 수행하지 않았다.
- 문서의 **사용자 확정 정책**과 **기술 설계 권고안**을 구분한다. 문서 작성 완료를 구현 승인으로 취급하지 않는다.

## 첫 운영 범위

- 관리자 발급 개인별 일회용 초대, 회원 인증, 전체 관리자와 팀장 권한
- 정규조·추가조·팀원 관리, 팀별 곡 목록
- 팀 합주실 독점 예약과 악기별 개인 공유 예약
- 고정 블록, 2주 개방, 60분 블록의 주체별 일일 한도
- 매주 고정 배정과 회차별 취소·변경·교환
- 60분 두 건과 90분 한 건의 묶음 교환 및 개인 2악기 예외
- 악기 고장 안내와 신규 개인 예약 제한
- 전체 공지, 사이트 내 알림함, Web Push, 시작 1시간 전 알림
- 모바일 하루 목록, PC 주간 시간표, PWA 설치와 기본 오프라인 안내
- 예약 동시성·권한·원자적 변경 테스트, HTTPS, DB 백업과 복원

예약 변경·교환·공지·Push는 첫 운영 필수 기능이다. 일정이 촉박하다는 이유로 임의로 후순위로 이동하지 않는다.

## 기술 방향

| 영역 | 설계 권고안 |
|---|---|
| Frontend | Next.js, React, TypeScript, Tailwind CSS, TanStack Query |
| Backend | Java 21, Spring Boot, Gradle Wrapper, JPA, Spring Security, Bean Validation |
| 데이터 | PostgreSQL, Flyway |
| 인증 | 같은 origin의 `/api` 경로, 서버 세션과 HttpOnly 쿠키, CSRF 보호 |
| 로컬 환경 | 앱은 개발 서버/IDE, PostgreSQL은 Docker, 핵심 테스트는 Testcontainers |
| 운영 | 단일 VPS, 분리된 앱/DB 컨테이너, DB 지속 볼륨, Caddy, Cloudflare |
| 갱신 | 활성 화면 주기 조회와 포커스 복귀 갱신, 자체 변경 후 Query 무효화 |

초기 구성에서는 Spring Boot 4.1.1·Gradle 9.8.1, Next.js 16.4.0·React 19.3.0을 고정했다. 나머지 의존성은 각 앱의 버전·lock 파일을 따른다. Redis, Kafka, Kubernetes, 별도 모바일 프로젝트는 기본 구성에 포함하지 않는다.

## 설계 문서

| 문서 | 내용 |
|---|---|
| [AGENTS.md](AGENTS.md) | 이 프로젝트에서 작업하는 에이전트의 규칙 |
| [PROJECT_CONTEXT](docs/PROJECT_CONTEXT.md) | 확정 요구사항, 역할, 일정, 설계 권고안 |
| [RESERVATION_RULES](docs/RESERVATION_RULES.md) | 예약·한도·반복·변경·교환의 상세 규칙 |
| [ERD](docs/ERD.md) | 데이터 관계, 제약, 이력과 점유 모델 |
| [API](docs/API.md) | REST 계약, 권한, 오류, 변경·교환 예시 |
| [ARCHITECTURE](docs/ARCHITECTURE.md) | 시스템, 인증, 동시성, 알림 처리 구조 |
| [UX](docs/UX.md) | 화면과 반응형 동선, 브랜드 방향 |
| [NOTIFICATIONS](docs/NOTIFICATIONS.md) | 알림 사건, 수신자, Push와 예정 알림 |
| [TEST_PLAN](docs/TEST_PLAN.md) | 기능·동시성·실기기·운영 검증 기준 |
| [DEPLOYMENT](docs/DEPLOYMENT.md) | 개발 준비, 배포, 비용, 백업과 종료 |
| [GITHUB_SETUP](docs/GITHUB_SETUP.md) | 비공개 저장소, 인증, 브랜치·PR와 초기 설정 기록 |
| [DEV_ENVIRONMENT](docs/DEV_ENVIRONMENT.md) | Docker·PostgreSQL·JDK·Node, 실행 명령과 환경 검증 결과 |
| [INITIAL_SETUP](docs/INITIAL_SETUP.md) | backend/frontend 실행, DB·API·CSRF 연결과 CI 검증 |
| [AUTHENTICATION](docs/AUTHENTICATION.md) | 초대·가입·로그인·권한 기반, 최초 관리자와 검증 |
| [MEMBER_TEAM_MANAGEMENT](docs/MEMBER_TEAM_MANAGEMENT.md) | 가입 전 명단·계정 연결, 복수 팀 소속·팀장·곡 관리와 검증 |

## 운영 목표

- 개발 시작: 2026-10-23
- 개발 완료 목표: 2026-10-30
- 운영 종료 예정: 약 2026-12-11
- 10월 30일 오픈 시 운영 기간은 약 6주다. 비용 계획은 준비 기간을 포함해 두 달로 잡는다.
- 도메인: `bandjeongeum.cloud` 사용 예정, 아직 구매하지 않았다.
- 브랜드: 첨부 정음 로고, 남색과 노란색. 세부 시각 스타일은 1차 설계안이다.

완료 목표는 기능·검증 상태로 관리한다. 사용자의 하루 개발 가능 시간을 추가 조건으로 요구하지 않는다.

## 로컬 실행

GitHub 재인증·독립 저장소 연결을 완료했다. JDK 21과 Node.js/npm 실행을 확인했고 Docker Desktop의 WSL 2 Linux 엔진을 정상화했다. 개발용 PostgreSQL 인증·SQL 트랜잭션·컨테이너 재생성 후 데이터 유지와 Java Testcontainers 환경 검증 1건도 통과했다.

개발 DB 실행은 [DEV_ENVIRONMENT](docs/DEV_ENVIRONMENT.md), 앱 실행은 [INITIAL_SETUP](docs/INITIAL_SETUP.md)의 명령을 사용한다. 두 터미널에서 `./scripts/Start-Backend.ps1`, `./scripts/Start-Frontend.ps1`을 실행하고 `http://127.0.0.1:3000`을 연다. 첫 프론트 실행 전 `frontend`에서 `npm.cmd ci`로 의존성을 설치한다.

초대·가입·로그인 기반은 [PR #3](https://github.com/developer-sw/jeongeum-band-scheduler/pull/3)으로 main에 반영했다. 이번 명단·팀·곡 단계의 로컬 검증은 백엔드 41건·프론트 29건, 타입 검사·lint·양쪽 빌드를 통과했다. 실제 브라우저에서 명단 초대 가입·복수 소속·팀장 곡 관리·권한 차단도 확인했다. 상세 결과는 [MEMBER_TEAM_MANAGEMENT](docs/MEMBER_TEAM_MANAGEMENT.md), 최초 관리자 절차는 [AUTHENTICATION](docs/AUTHENTICATION.md)을 따른다. 로컬 검증 보고 시점에는 이번 변경의 원격 CI를 실행하지 않았으며 게시 후 결과는 해당 PR에서 확인한다. 공용 기본 계정은 없으며 예약·공지·Push와 운영 배포는 후속 단계다.

## 참고 프로젝트 원칙

`Reormo/rehearsal-pwa`는 기능과 UX 아이디어의 참고 대상이다. 해당 저장소의 수정·commit·push·PR·fork를 수행하거나 코드를 복사해 개발 기반으로 삼지 않는다. 이 프로젝트의 요구사항, DB, API, 코드, Git 저장소와 배포 환경은 독립적으로 만든다.
