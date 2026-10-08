# 정음 합주실 예약 서비스

밴드 동아리 정음(JEONGEUM)의 약 45명이 사용할 웹/PWA 서비스다. 프로젝트명은 `jeongeum-band-scheduler`이며, Java/Spring 백엔드 포트폴리오와 실제 동아리 운영을 함께 목표로 한다.

## 현재 단계

- 2026-10-09: 사용자가 설계 문서 정리 작업을 승인했다.
- 초기 설계 문서 11개를 작성하고 정책·권한·ERD·API·알림의 정합성을 검토했다. 내부 파일 링크와 JSON 예제 검사도 수행했다.
- GitHub 재인증, 로컬 main 초기화, [비공개 저장소](https://github.com/developer-sw/jeongeum-band-scheduler) 생성, origin 연결과 첫 commit·push를 완료했다. 원격 기본 브랜치와 커밋 일치도 확인했으며 결과는 [GitHub 설정 기록](docs/GITHUB_SETUP.md)에 남겼다.
- 설계 문서와 Git 기본 설정을 준비했다. 애플리케이션, migration, Docker/CI 실행 설정은 아직 없다.
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

세부 버전은 구현 시작 시 지원 상태와 호환성을 확인하고 고정한다. Redis, Kafka, Kubernetes, 별도 모바일 프로젝트는 기본 구성에 포함하지 않는다.

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

## 운영 목표

- 개발 시작: 2026-10-23
- 개발 완료 목표: 2026-10-30
- 운영 종료 예정: 약 2026-12-11
- 10월 30일 오픈 시 운영 기간은 약 6주다. 비용 계획은 준비 기간을 포함해 두 달로 잡는다.
- 도메인: `bandjeongeum.cloud` 사용 예정, 아직 구매하지 않았다.
- 브랜드: 첨부 정음 로고, 남색과 노란색. 세부 시각 스타일은 1차 설계안이다.

완료 목표는 기능·검증 상태로 관리한다. 사용자의 하루 개발 가능 시간을 추가 조건으로 요구하지 않는다.

## 구현 전 준비

2026-10-09 읽기 전용 점검 결과, Git·JDK 21·Node.js·npm·Docker CLI·GitHub CLI는 설치되어 있다. GitHub 재인증은 이번 단계에서 완료했다. 초기 점검에서 Docker Linux 엔진 연결은 실패했으며 Docker 실행 확인이 남아 있다.

GitHub와 독립 저장소 연결을 완료했다. 다음 단계에서 개발환경·프로젝트 생성·로컬 실행과 CI를 차례로 준비한다. 아직 실행 가능한 애플리케이션 명령이나 배포 명령은 제공하지 않는다.

## 참고 프로젝트 원칙

`Reormo/rehearsal-pwa`는 기능과 UX 아이디어의 참고 대상이다. 해당 저장소의 수정·commit·push·PR·fork를 수행하거나 코드를 복사해 개발 기반으로 삼지 않는다. 이 프로젝트의 요구사항, DB, API, 코드, Git 저장소와 배포 환경은 독립적으로 만든다.
