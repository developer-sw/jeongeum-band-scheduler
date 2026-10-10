# 예약 기본 구현과 로컬 실행 검증

기준일: 2026-10-10 / 시간대: `Asia/Seoul` / 작업 브랜치: `codex/reservation-foundation`

사용자가 요청한 순서는 실제 로컬 실행환경 점검 → 예약 기본 기능 구현이다. 사용자 확정 정책은 [PROJECT_CONTEXT](PROJECT_CONTEXT.md)와 [RESERVATION_RULES](RESERVATION_RULES.md)를 따른다. 아래의 조회 범위·재시도 키·잠금·화면 구성은 정책을 구현하기 위한 기술 설정이다. 2026-10-10 검증 결과 보고 후 사용자가 커밋·Push·main 대상 초안 PR 생성을 승인했다. 정식 PR 전환·main 병합은 별도 승인 후 진행한다.

## 1. 이번 단계와 다음 단계

이번 단계는 인증된 회원의 일정·악기 조회, 개인 또는 소속 팀의 단건 예약 생성, 권한별 취소, 이력 조회를 제공한다. 서버가 10개 고정 블록, 서울 날짜 오늘~오늘+14일, 현재 회원·팀·소속, 개인 한 악기, 팀 여섯 자원 독점, 동일 회원 개인 중복, 60분 블록 하루 120분을 검증한다. 진행 중 예약은 남은 시간만 이용하지만 60분 한 칸으로 계산한다. 시작 전 취소는 한도를 복원하고 시작 후 취소는 사용량을 유지한다.

2시간은 연속 60분 예약 두 건이며 각각 생성·취소한다. 이번 화면은 두 건을 묶어 전부 성공하는 동작으로 표시하지 않는다. 원자적 batch 생성, 반복 고정 배정과 날짜 개방 worker, 예약 변경·교환, 고장 등록·복구, 탈퇴·팀 해체 정리, 공지·알림함·Push는 다음 단계다. 첫 운영 필수 범위에서 제외한 것이 아니다. 현재 `OPEN`은 고정 배정이 없는 기본 단계의 개방 상태이며 고정 회차 우선 반영을 검증한 결과가 아니다.

## 2. 실제 개발환경 점검

- Docker Desktop Linux 엔진을 시작하고 기존 `jeongeum-dev-postgres-1`의 healthy 상태를 확인했다. 기존 `jeongeum-dev_postgres_data` 볼륨과 localhost DB 포트를 유지했다.
- 개발 DB는 V1만 적용된 상태였다. `pg_dump --format=custom`으로 `backups/local-before-reservations-20261010-110833.dump`를 생성하고 `pg_restore --list`와 SHA-256을 확인했다. 바이너리 백업은 `docker cp`로 복사했고 Git ignore를 확인했다. 이는 백업 형식 확인이며 외부 백업 복원 검증은 아니다.
- 기존 앱을 실행해 Flyway V2·V3를 적용했다. 사용자 계정·관리자 수는 모두 0이었다. 임의 관리자나 실제 명단을 만들지 않았다. 최초 관리자는 기존 [Initialize-Admin.ps1](../scripts/Initialize-Admin.ps1)에서 비밀번호를 숨김 입력해 생성한다.
- 기존 Next.js `127.0.0.1:3000`, 백엔드 `127.0.0.1:8080`, PostgreSQL `127.0.0.1:5432` 연결을 확인했다. [Test-ApplicationConnection.ps1](../scripts/Test-ApplicationConnection.ps1)의 DB health·API 전달·no-store·HttpOnly 세션·CSRF·익명 보호 API 검사가 통과했다.
- V4 적용 직전 V3 DB를 `backups/local-v3-before-v4-20261010-112854.dump`에 추가 백업하고 형식·SHA-256·Git ignore를 확인했다. 기존 백업을 보존했다. 실제 앱 재시작으로 V4가 적용됐고 Flyway V1~V4의 성공 상태를 확인했다.
- 최종 개발 DB에는 사용자·예약이 각각 0건이며 고정 블록 10개·악기 6개만 초기화돼 있다. 테스트와 화면 검증에는 합성 데이터와 격리된 PostgreSQL을 사용했다. 임시 화면 검증 서버·컨테이너만 중지했고 개발 볼륨을 유지했다.
- 최종 프론트 `127.0.0.1:3000`과 백엔드 `127.0.0.1:8080`를 실행 중이며 V4 상태에서 앱 연결 검사를 다시 통과했다. 실제 로그인 화면과 기존 문구도 확인했다.

| 백업 | 크기 | SHA-256 |
|---|---:|---|
| V1 / `local-before-reservations-20261010-110833.dump` | 6,728 bytes | `4B3C31C8BB4DCEDC03F08BD390102E15A3952E1A48FCB64E2502FE8EEA44AE02` |
| V3 / `local-v3-before-v4-20261010-112854.dump` | 31,779 bytes | `2445049F05CF6D030BA2ED6E55C4BF61A8C20BB09759831AAF368C45B13555A0` |

## 3. API와 재시도 계약

기본 경로는 `/api/v1`이며 기존 `ApiResponse`, `meta.serverTime/timeZone`, `Cache-Control: no-store`, 세션·CSRF를 유지한다.

| API | 계약 |
|---|---|
| `GET /schedule?from=YYYY-MM-DD&to=YYYY-MM-DD` | 활성 회원, 기본 오늘~+14일, 개방 범위 안 최대 15일 |
| `GET /instruments` | 여섯 코드·표시명·현재 고장 상태 |
| `POST /reservations` | 개인 본인 한 악기 또는 현재 소속 팀 여섯 자원, 생성 `201` |
| `GET /reservations?scope=mine&from=YYYY-MM-DD&to=YYYY-MM-DD` | 본인 개인·현재 소속 팀의 예약과 이력, 과거 구간 허용, 한 조회 최대 31일 |
| `GET /reservations/{id}` | 본인·현재 해당 팀원·전체 관리자만 상세 접근 |
| `POST /reservations/{id}/cancel` | 현재 권한과 `expectedVersion`, 미래·진행 중 취소, 성공 `200` |

생성 본문은 `subjectType`, `date`, `blockCode`, 개인의 `instrumentCodes` 한 종류 또는 팀의 `teamId`다. 현재 회원 ID·원래 예약자·시각·길이·면제·두 악기 예외는 서버가 결정하며 임의 생성 필드는 `INVALID_INPUT`으로 거절한다. 개인 이름·로그인 ID·owner/creator ID는 다른 회원에게 제공하는 일정 요약에 넣지 않는다. 상세 권한과 `canCancel`은 서버가 현재 소속·역할로 계산한다. 전체 관리자도 미소속 팀의 일반 예약을 대리 생성할 수 없다.

생성·취소에는 `Idempotency-Key` 16~128자 공백 없는 출력 가능한 ASCII를 필수로 사용한다. 실행자·키 해시 unique와 method·route·정규화 본문 fingerprint로 같은 작업을 식별한다. 성공 후 재시도는 현재 권한을 재검증하고 새 예약·감사·한도 쓰기 없이 기존 예약 ID의 현재 상태를 반환한다. 취소된 예약의 생성 재시도도 새 예약을 만들지 않는다. 다른 경로·본문으로 키를 재사용하면 `IDEMPOTENCY_KEY_REUSED`다. 실패는 성공 키를 남기지 않는다. 이번 단계에서는 만료 시각을 기준으로 키를 삭제하거나 재사용하지 않는다.

프론트는 네트워크 응답이 불명확하면 같은 본문·키를 보존하여 사용자가 재시도하도록 한다. 그 재시도의 CSRF 준비 실패·요청 전 실패도 기존 불확실 결과를 확정하지 못하므로 키를 유지한다. 자동 재제출·오프라인 큐·서버 성공 전 완료 표시는 없다. 확인된 성공·업무 거절 이후 다음 작업은 새 키를 사용한다. 키 보존은 현재 화면 메모리 범위이며 페이지 이탈·새로고침 후 복원은 제공하지 않는다.

## 4. V4와 트랜잭션

V1~V3를 수정하지 않고 V4를 추가한다. `time_blocks`, `assets`, `booking_days`, `reservations`, `reservation_assets`, `quota_entries`, `reservation_operations`, `idempotency_requests`가 기본 모델이다. 미래 경로를 위한 상태·출처 필드는 일반 생성 요청에서 선택할 수 없다.

쓰기 순서는 `directory_mutation_guard` → 현재 실행자/권한과 재시도 검사 → 관련 회원·팀 → 악기 → 날짜 → 예약이다. 회원·팀은 ID 순서의 `FOR NO KEY UPDATE`, 악기는 신규 고장 검사 시 `FOR SHARE`, 날짜·예약은 `FOR UPDATE`다. 없는 날짜는 guard 안에서 생성한 뒤 잠근다. 잠금을 기다린 뒤 서버 시각·예약 가능 날짜·블록 종료·취소 시작 경계를 다시 검사한다. 기존 명단·소속·역할 변경과 같은 guard를 사용하여 권한 회수와 예약 확정 순서를 일치시킨다.

활성 악기 점유 partial unique, 개인과 팀의 같은 블록 활성 예약 unique, 부모 예약 날짜·블록과 점유의 복합 FK, 예약별 quota 소비·복구 unique를 최종 방어선으로 둔다. 60분 소비와 시작 전 복구를 ledger로 남기고 진행 후 취소에는 복구 항목을 만들지 않는다. 예약·점유·한도·작업 이력·재시도 성공 기록은 한 트랜잭션에서 확정한다.

고장 상태를 읽어 새 개인 예약을 막고 팀 예약은 경고와 함께 허용한다. 고장 관리 API는 후속 단계지만 새 배치 검증은 악기 잠금에 참여한다. 정상 종료는 DB 상태를 취소로 바꾸지 않고 조회 시 `ENDED`로 계산한다. 취소는 점유 release 시각을 남기며 예약·이력·ledger를 삭제하지 않는다. 상세의 `quotaMinutes`는 원래 소비량이며 당일 실제 사용량은 소비·복구 ledger의 합계다.

예약 쓰기와 일정 개방 트랜잭션에 10초 제한을 설정한다. 해당 경로의 DB 경합·접속·제약 오류는 원문 SQL 없이 `503 RESERVATION_TEMPORARILY_UNAVAILABLE`로 응답한다. 프론트는 처리 결과가 불명확한 요청의 같은 키를 유지한다. 10초 timeout 자체를 의도적으로 발생시키는 실행 검증은 이번에 수행하지 않았다.

## 5. 화면

`/reservations`에서 공통 API·Query로 모바일 하루 카드와 PC 주간 시간표를 제공한다. 서울 날짜 선택, 블록 길이·잔여분, 여섯 자원 점유·고장, 개인/소속 팀 선택과 주체별 사용량을 표시한다. 취소 확인에서 시작 후 사용량 유지 규칙을 안내한다. 내 예약·소속 팀 이력과 현재 권한으로 취소 가능한 일정 예약을 구분한다. 서버 충돌·한도·권한 오류에는 최신 상태를 조회한다.

활성 화면 polling·포커스·재연결 갱신과 쓰기 후 invalidate를 사용한다. 인증·권한 변경 시 예약 Query도 정리한다. 기존 로고·남색·노란색과 `JEONGEUM · SENTIMENTAL MUSIC` 문구, 사용자 작성 README를 보존한다.

## 6. 검증 상태

2026-10-10 실행 결과:

| 검증 | 결과 |
|---|---|
| Backend `test bootJar` | 6 suites / 81 tests 통과, 실패·오류·skip 0건. 예약 29건과 기존 인증·명단·로그인 유지 52건 |
| PostgreSQL 통합 테스트 | 악기·팀 독점·개인 중복·한도 경합, 권한 회수 후 대기, 취소 경계, 멱등성 경합·롤백·현재 권한, DB 제약 통과 |
| V3→V4 | 합성 회원·명단·팀·곡·초대·세션 보존 및 seed 재실행 중복 방지 통과 |
| Frontend | typecheck·lint·7개 파일 42 tests 통과. 최종 코드의 production build 통과 |
| 브라우저 | 격리 QA DB에서 개인 생성·취소·한도 복원 이력, 추가조 전체 점유, 다른 팀원의 팀 예약 취소·점유 해제, 90분 한도 안내 확인 |
| 반응형 | 기본 PC 주간 시간표와 390×844 모바일 하루 카드, 개방 마지막 날짜·종료 24:00·기존 문구 확인. 임시 viewport 복원 |
| 실제 로컬 연결 | 기존 PostgreSQL healthy, V4 적용 후 Next→Backend→DB 및 CSRF·세션·익명 접근 보호 재검사 통과 |
| Git·민감정보 | 변경 후보의 secret 형식·실제 로컬 secret 노출 검사, tracked diff 및 신규 파일 공백 검사 통과. README·V1~V3 미변경, `.env`·백업·QA·산출물 Git 제외 |

화면 증거는 Git에서 제외되는 `.local/reservation-ui-final.png`, `.local/reservation-ui-desktop.png`, `.local/reservation-ui-mobile.png`에 보존했다. QA 로그에 합성 관리자 비밀번호·임시 DB 비밀번호가 포함되지 않았음을 검사했고 QA 컨테이너를 정리했다. 실제 회원 데이터는 만들지 않았다.

브랜치 `codex/reservation-foundation`은 `57fc3fd`에서 시작했고 구현 검증 완료 시점의 변경은 미커밋 상태였다. 검증 결과 보고 후 사용자가 커밋·Push·초안 PR 생성을 승인했다. 전체 관리자 최초 생성은 별도 숨김 입력이 필요하다. 반복 배정·변경·교환·고장 관리·탈퇴 및 팀 해체 정리·공지·Push는 후속 구현이며 실제 모바일 기기, HTTPS 운영, 45명 부하, 외부 백업 복원은 이번 검증 범위가 아니다.
