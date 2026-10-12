# 예약 교환 구현과 검증

기준일: 2026-10-12 / Asia/Seoul / 브랜치: `codex/reservation-exchange` / 시작 main: `018a79c`(README PR #11 병합).

사용자가 예약 교환 개발을 요청했다. 확정 정책은 [PROJECT_CONTEXT](PROJECT_CONTEXT.md)와 [RESERVATION_RULES](RESERVATION_RULES.md)를 따른다. 기존 다이어리 UI·단건 변경·반복 배정·회원·인증을 보존한다. 아래 API 응답·재시도·화면·저장 구조는 정책을 구현하기 위한 기술 계약이다. 최초 로컬 개발·쓰기·시간 제어·실패 주입은 Testcontainers와 임시 PostgreSQL의 합성 데이터에서 검증했다. 이후 사용자 승인으로 실제 개발 DB를 백업하고 V7 적용·서버 기동·기존 데이터 보존을 확인했다. 실제 개발 DB에 검증용 계정·예약·교환 요청을 만들지 않았다.

## 확정 교환 정책

- 개인·정규조·추가조 간 1↔1(60/90분), 연속 60분 두 건↔90분 한 건을 지원한다. 2↔2·같은 예약의 양측 사용·세 건 이상은 허용하지 않는다.
- 두 건 묶음은 같은 날짜·인접 60분 블록·같은 주체·같은 최초 작성자다. 개인의 두 악기는 달라도 된다.
- 시간만 교환하며 각자의 개인/팀 주체·purpose·최초 작성자·악기는 유지한다. 관리자 승인자를 후속 예약의 작성자로 바꾸지 않는다.
- 두 개인 60분의 악기가 다르면 결과 90분 한 건이 두 악기를 전체 시간 동안 점유한다. 두 악기 90분을 두 60분으로 분할할 때는 해당 개인 예약자가 직접 각 블록의 악기 순서를 저장한다. 전체 관리자의 승인 권한은 악기 분배 대리 선택 권한이 아니다.
- 두 악기 90분↔단일 60분의 직접 교환은 거절한다. 일반 신규 예약의 악기 한 종류 규칙은 유지한다.
- 요청 대기 중 원래 예약·점유·한도를 유지한다. 양측에서 가장 이른 예약 시작 시각에 만료되며 시작 시각부터 승인할 수 없다.
- 요청자는 본인 측 전체 예약의 현재 변경 권한을 가져야 한다. 승인은 상대 개인 예약자/전체 관리자 또는 상대 팀 예약 작성자/현재 팀장/전체 관리자다. 일반 팀원이 목록을 읽는다고 승인 권한을 얻지 않는다.
- 요청자 권한 상실·원본 변경/취소·유효 주체 종료는 요청을 무효화한다. 특정 상대 승인 후보 한 명의 권한 상실만으로 다른 적법한 승인 경로까지 없애지 않는다.
- 승인 시 현재 권한·시각·원본 버전·날짜 개방·제3자 점유·개인 동시간 중복·고장·양측 날짜별 최종 한도를 다시 검사한다. 실패하면 양쪽 기존 예약 전체를 보존한다.
- 원래 고정 60분의 면제는 교환 후 전달하지 않는다. 해당 고정 회차만 예외로 남기며 다음 주 반복 패턴은 유지한다.

## API와 화면 계약

공통 `/api/v1`, `ApiResponse`, 세션·CSRF·no-store 계약을 사용한다.

| 경로 | 책임 |
| --- | --- |
| `GET /exchange-candidates?from=&to=` | 활성 회원의 열린 예약 가능 기간 내 시작 전 확정 후보. 기존 일정 요약·날짜/블록·길이/악기·불투명 bundleKey로 묶음 선택을 제공하며 unrelated 개인 이름/로그인 ID/메모/취소 이력을 제공하지 않음 |
| `POST /exchange-requests` | offered/requested 각각 reservationId·expectedVersion을 저장. 요청만 생성하고 기존 점유를 유지 |
| `GET /exchange-requests` | 요청자·관련 개인/현재 조원·전체 관리자에게 관련 요청과 최신 상태 제공 |
| `GET /exchange-requests/{id}` | 원본·예상 결과·현재 권한·필요한 악기 배분·완료 후속 예약 제공 |
| `PATCH /exchange-requests/{id}/instrument-allocation` | expectedVersion·reservationId·instrumentCodesOrder를 해당 개인 예약자가 저장 |
| `POST /exchange-requests/{id}/approve` | expectedVersion·현재 상대 측 승인 권한 확인 후 전체 교환 원자적 확정 |
| `POST /exchange-requests/{id}/reject` | 상대 측 권한으로 요청 거절, 기존 예약 유지 |
| `POST /exchange-requests/{id}/cancel` | 활성 요청자 또는 전체 관리자가 요청 철회, 기존 예약 유지 |

목록 응답은 `{serverTime,requests}`이며 후보 응답은 `{serverTime,reservations}`다. 상세는 status·version·expiresAt·stateReason, offered/requested 원본, preview 결과, allocationReservationIds·canAllocateReservationIds, canApprove/canReject/canCancel, replacedReservationIds·완료 reservations를 제공한다. 단순 UI 권한 표시를 서버 쓰기 검사의 대체로 사용하지 않는다.

`/exchanges`에서 교환할 예약 한 건 또는 연속 두 건과 상대 예약을 선택하고 시간·길이·악기·면제 변화를 비교한 뒤 요청한다. 관련 요청에서 개인 악기 순서를 저장하고 상대 권한자가 승인/거절한다. 완료 후 다이어리·예약/이력·내 공연조 일정과 교환 목록을 다시 조회하고 후속 예약을 연결한다. 기존 예약 화면·다이어리 상세·더보기에서 진입하며 브랜드 `JEONGEUM · SENTIMENTAL MUSIC`을 유지한다.

모든 쓰기는 `Idempotency-Key`와 정규화 본문 fingerprint를 사용한다. 같은 키·본문의 성공 재확인은 추가 요청/후속 예약/계보/한도를 만들지 않는다. 키를 다른 경로·본문에 재사용하면 거절한다. 불확실한 네트워크 응답에는 기존 본문·키를 화면 메모리에 유지하여 명시적으로 같은 결과를 재확인하며 오프라인 자동 제출을 하지 않는다. 새로고침·이탈 이후에는 현재 목록·이력을 먼저 확인한다.

## 저장·잠금·복구

V1~V6를 수정하지 않고 `V7__reservation_exchanges.sql`에 `exchange_requests`, `exchange_request_items`, `exchange_instrument_allocations`, `exchange_idempotency_requests`를 추가한다. 기존 `reservation_operations`에는 nullable `exchange_request_id` FK와 교환당 작업 한 건 unique를 추가한다. 기존 컬럼 값은 유지하고 새 컬럼은 기존 행에서 null이다. 원본은 REPLACED 이력으로 남기고 후속 EXCHANGE 예약과 V6 N:M lineage를 같은 주체/작성자 사이에 연결한다. 한 번의 EXCHANGE operation 안에 원본 release·후속 claim·quota·회차 예외·요청 COMPLETED·성공 재시도를 함께 확정한다.

`directory_mutation_guard → 관련 사용자 ID → 팀 ID → 악기 ID → 관련 날짜 → 예약 ID → 교환 업무 행`의 정렬된 기존 PostgreSQL 잠금 규약과 10초 쓰기 제한을 사용한다. 권한·버전·시각은 잠금 대기 이후 다시 검사한다. 제3자 점유·고장·한도 실패는 전체 롤백하고 PENDING을 유지한다. 만료/원본 변경/요청자 권한 상실은 EXPIRED/INVALIDATED로 보존한다. 동일 원본에 대한 다른 대기 요청도 성공 후 무효화한다.

단건 변경의 실질 변경·취소와 회원/팀 권한 변경은 관련 요청 정합성 검사에 연결한다. 날짜·블록·악기가 같은 no-op 변경은 대기 교환을 유지한다. 조회·승인과 `ExchangeExpiryWorker`의 기동 직후·60초 주기 복구에서도 만료/무효화 상태를 갱신해 서버 중단 이후의 대기 요청을 안전하게 처리한다. 테스트에서는 `jeongeum.exchange.worker.enabled=false`로 주기 실행을 분리해 Clock과 직접 복구 실행을 제어할 수 있다. 상태 종료를 DB에 확정한 뒤 서비스 밖에서 EXCHANGE_EXPIRED/EXCHANGE_INVALIDATED 409를 반환해 오류 응답이 종료 상태를 롤백하지 않게 한다.

알림함·outbox·Push는 후속 구현이다. 이번 요청 상태와 단일 EXCHANGE operation은 향후 교환 이벤트의 연결 지점이며 실제 알림 발송 완료로 보고하지 않는다. 후속 예약 행마다 CREATED 알림을 추가하지 않는 정책은 [NOTIFICATIONS](NOTIFICATIONS.md)를 따른다. 탈퇴·팀 해체의 예약 자동 정리는 별도 단계이며 이번 교환은 현재 주체/권한 상태를 검증한다.

## 검증 기록

개발 시작 시 Docker Desktop 엔진이 중지돼 있어 기존 환경을 보존하며 시작했다. 기존 `jeongeum-dev-postgres-1`은 healthy, 개발 DB V1~V6 성공·활성 전체 관리자 1명·예약 0건·기존 `jeongeum-dev_postgres_data`를 확인했다. 27개 테이블의 행 수·집계 해시를 읽기 전용으로 `.local/exchange-live-before.json`에 보관했다. 기존 3000/8080 서버는 중지 상태이며 새 개발 백엔드 기동은 V7을 자동 적용할 수 있어 이 단계에서는 격리 QA 서버만 실행한다.

| 항목 | 상태 |
| --- | --- |
| Backend 컴파일·PostgreSQL 통합 테스트·bootJar | 전체 9 suites·185건 통과, 실패/오류/skip 0. 교환 39건·기존 변경 32건·반복 31건·기본 예약 31건 포함. compileJava·bootJar 통과 |
| V6→V7 보존·재기동·Flyway validate | 합성 V1~V6 업무 테이블 26개 전체 기존 컬럼 값·세션·계보 보존, V7 적용·재실행 0건·새 Flyway 인스턴스 validate 통과 |
| 실패·경합·보존 | PostgreSQL 마지막 분할 점유 trigger 실패, 이미 원본/후속/한도 쓰기 후 실제 10초 lineage 잠금 timeout의 전체 롤백 및 동일 키 재시도 통과. 동시 동일 키 승인·공유 원본의 경쟁 교환·취소/변경·권한/시각 잠금 대기 재검사 통과 |
| Frontend 타입·lint·Vitest·production build | 14개 파일·128건 통과(교환 22건), TypeScript·lint 경고 0·Next production build 14개 경로 통과 |
| 격리 DB 실제 브라우저 | 실제 Spring Boot·production Next·합성 회원으로 로그인/계정 전환·후보·2↔1 생성/승인·90분 두 악기→두 60분 owner 배분/승인·고정 팀 회차↔개인 교환·요청 철회·거절 확인 |
| 충돌·응답 유실 | 실제 제3자 개인 점유에 팀 도착이 충돌해 409, 요청 PENDING·양쪽 원본 유지. 실제 승인 성공 응답만 QA proxy에서 502로 유실시킨 뒤 다른 쓰기 차단·같은 키/본문 재확인·같은 결과 및 EXCHANGE 작업 한 건 확인 |
| 재시작·만료 복구 | 소유 PID/JAR/8082를 확인해 QA JVM만 종료. 중지된 QA DB의 합성 요청 저장 만료 시각을 과거로 설정한 뒤 같은 DB/JAR로 실제 새 JVM 기동. 기동 worker가 PENDING→EXPIRED 복구, 정상 미래 PENDING·완료/거절/철회 상태·기존 결과 유지 확인. 실제 시작 시각 경계는 통합 테스트의 가변 Clock으로 별도 검증 |
| 반응형 | 실제 320/390/768/1440px viewport에서 교환 페이지 가로 넘침 없음. 390px에서 요청·owner 악기 순서 저장·승인·거절·철회·응답 유실 재확인 조작 |
| 실제 개발 DB 전후 보존 | V1~V6 성공·활성 관리자 1명·예약 0건·기존 볼륨 유지. 세션을 포함한 27개 테이블 행 수·집계 해시 및 테이블 목록 전후 일치. V7 미적용 |

초기 API 통합 테스트는 새 교환 경로의 Security 허용 목록 누락으로 403이 발생해 인증 경로 등록과 서비스 권한 검사를 함께 확인했다. 기존 최신-schema Bootstrap 테스트의 기대 버전을 V7로 갱신했고 V3~V6의 버전 한정 업그레이드 테스트는 유지했다. 화면 검토에서 저장된 악기 순서가 version 갱신 후 초기 순서로 보이던 문제와 자체 승인 완료를 외부 상태 변경처럼 안내하던 경고를 수정하고 해당 회귀 테스트·최신 브라우저 검증을 통과했다.

QA 최종 상태는 교환 완료 4건·거절 1건·철회 1건, 복구 만료 1건·정상 대기 1건이다. EXCHANGE 작업 4건·N:M lineage 12건·고정 회차 EXCHANGED_EXCEPTION 1건을 확인했으며 중복 작업/활성 점유, 한도 0~120분 위반, 원본/후속 주체·작성자 불일치는 모두 0건이었다. 최초 다음 주 직접 고정 회차는 유지했다. QA 증빙은 Git에서 제외한 `.local/exchange-*-evidence.json`, `.local/exchange-qa-*.json`, `.local/exchange-responsive-proof.json`, `.local/exchange-ui-qa-mutation-trace.jsonl`, `.local/exchange-desktop-final.jpg` 등에 보존하며 합성 자격증명·DB 파일은 Git에 넣지 않는다.

검증 후 자체 생성한 임시 QA 프론트·8082 JVM·`jeongeum-exchange-ui-qa-*` 컨테이너만 종료했다. 개발 PostgreSQL은 유지하며 개발 3000/8080 서버는 이 단계에서 기동하지 않았다. 따라서 현재 실제 개발 DB로 교환 화면을 사용하려면 백업·V7 적용·최신 서버 기동이 먼저 필요하다.

실제 iOS/Android·가상 키보드·Push·운영 배포·실제 회원 트래픽·운영 외부 백업 복원은 이번 완료 주장에 포함하지 않는다. 앞 절은 최초 로컬 구현 단계의 기록이며, 이후 승인된 개발 DB 적용 결과는 다음 절을 따른다.

## 사용자 승인 후 개발 DB 적용·서버 기동·GitHub 반영

2026-10-12 사용자가 개발 DB V7 적용·서버 기동·커밋·Push·PR 생성을 승인했다. 정식 PR 전환·main 병합은 별도 승인 대상이다.

- V7 적용 직전 27개 테이블·계정·예약·세션·V1~V6 이력·볼륨을 읽기 전용으로 `.local/exchange-v7-before.json`에 저장했다. `local-v6-before-v7-20261012-195819.dump` 백업은 99,466 bytes·`pg_restore --list` 208개 항목과 SHA-256을 확인했다. 해당 실제 백업 자체의 복원은 실행하지 않았으며 덤프·메타데이터·로컬 집계는 Git에서 제외한다.
- 기존 `scripts/Start-Backend.ps1`로 19:59 KST 최신 백엔드를 실행했다. 정상 Flyway 경로에서 `Successfully validated 7 migrations`, V6→V7 한 건 적용 성공·8080 Spring Boot 구동을 확인했다. 기존 migration 강제 재적용·변경과 DB/볼륨 초기화를 하지 않았다.
- 기동 직후 기존 업무 테이블 24개의 행 수·기존 컬럼 집계 해시와 기존 V1~V6 이력이 일치했다. 관리자 1명·기존 예약·볼륨과 세션 두 테이블의 행 수/해시도 동일했다. 새 교환 테이블 4개는 모두 0건이며 public 테이블은 31개다. `reservation_operations`의 신규 nullable 컬럼만 보존 비교에서 제외하고 기존 컬럼 값을 대조했다.
- 기존 `scripts/Start-Frontend.ps1`로 3000 서버를 시작했다. `Test-ApplicationConnection.ps1`의 Next API 전달·DB health·no-store·HttpOnly 세션·CSRF/인증 보호가 통과했다. `/exchanges` 페이지 200, 익명 교환 후보/요청 API는 401 AUTHENTICATION_REQUIRED를 확인했다. 데이터 변경 사용자 흐름의 실제 브라우저 검증은 앞 절의 격리 결과이며 실제 개발 DB에서 예약 교환을 실행한 결과로 주장하지 않는다.
- 최종 대조에서도 기존 업무 데이터·관리자·예약·V1~V6 이력·볼륨은 동일하고 교환 테이블은 모두 비어 있다. 접속 검사로 익명 Spring Session 한 건이 추가돼 세션 8→9건·세션 속성 9→10건이며 기동 직후 동일했던 기존 세션과 구분해 기록한다. 확인 결과는 `.local/exchange-v7-after.json`과 `.local/exchange-v7-final.json`에 보존했다.

사용 가능한 개발 페이지는 `http://127.0.0.1:3000/exchanges`이며 로그인 후 상단 **예약 교환**, **더보기 → 예약 교환**, 기존 예약 상세의 **교환**으로 진입한다. 서버는 이 단계 이후 실행 상태로 유지한다. 기존 전체 로컬 테스트·빌드·격리 브라우저 검증 결과를 커밋 준비의 근거로 사용하며 원격 CI는 생성된 초안 PR 검사에서 확인한다.

### 2026-10-13 사용 한도 중단 후 GitHub 반영 재개

사용자가 중단된 작업의 최종 정리·커밋·Push·main 대상 Draft PR·CI 확인을 재승인했다. 시작 시 브랜치 `codex/reservation-exchange`·HEAD `018a79c`와 기존 미커밋 구현을 보존했으며 원격 기능 브랜치와 해당 PR은 아직 없었다. 기존 publish review 결과와 PR 본문을 재사용한다. 정식 전환·main 병합과 README 전체 갱신은 이번 범위에 포함하지 않는다.

현재 3000/8080 앱 서버와 Docker 엔진은 중지 상태였다. DB 조회에 필요한 Docker와 기존 PostgreSQL만 시작했으며 앱 서버 기동·V7 재적용·데이터 복원은 반복하지 않았다. 읽기 전용 점검에서 V1~V7 성공·활성 관리자 1명·예약/교환 요청 0건·기존 볼륨을 확인했다. V7 적용 전 public 27개에서 교환 테이블 4개가 추가된 현재 31개 테이블 모두가 이전 최종 점검의 행 수·집계 해시와 일치했다.

기존 V7 직전 백업 99,466 bytes·208개 항목의 메타데이터와 파일 SHA-256이 일치한다. 해당 백업의 새 복원은 수행하지 않았다. 기존 Backend JUnit XML 9 suites·185건·실패/오류/skip 0, Frontend 128건·타입/lint·14경로 production build 기록을 확인했으며 이후 관련 코드 변경이 없어 전체 테스트·브라우저 검증을 반복하지 않는다. 새 원격 CI는 Draft PR의 현재 커밋 검사에서 확인한다. 재개 점검과 GitHub 반영 상태는 Git 제외 `.local/` 기록에 보존한다.
