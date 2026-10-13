# 악기 고장 관리 구현과 검증

기준일: 2026-10-13 / Asia/Seoul / 브랜치: `codex/instrument-incidents` / 시작 main: `1797576`(예약 교환 PR #12 병합).

사용자가 악기 고장 관리 개발을 요청했다. 확정 정책은 [PROJECT_CONTEXT](PROJECT_CONTEXT.md)와 [RESERVATION_RULES](RESERVATION_RULES.md)를 따른다. 최초 단계는 관리자 화면·API·이력·V8·격리 PostgreSQL 검증이며 기존 계정·예약·Docker 볼륨·다이어리 UI를 보존했다. 이후 사용자가 백업 후 개발 DB V8 적용·최신 서버 기동·GitHub 반영을 승인했다. 실제 적용 결과는 마지막 절을 따른다. 실제 개발 DB에서 검증용 고장을 등록하거나 예약을 변경하지 않는다.

## 확정 정책

- 여섯 악기의 고장은 전체 관리자만 등록·해제한다. 악기 종류 관리나 방 전체 폐쇄를 추가하지 않는다.
- 고장 등록으로 기존 개인·팀 예약, 점유, 한도, 고정 회차와 대기 교환을 자동 취소하거나 삭제하지 않는다.
- 개인 신규 예약과 변경·교환의 실제 새 배치는 현재 고장을 검사한다. 기존 위치·악기의 no-op 변경은 보존한다.
- 정규조·추가조의 일반 예약·변경·교환·반복 배정은 고장 안내와 함께 허용한다.
- 고장·복구 알림함/Push는 아직 확정 필수 이벤트가 아니며 이번에 발송 기능을 추가하지 않는다.

## 구현 계약

아래 길이·버전·재시도·페이지 크기는 정책을 구현하는 기술 설정이다.

- 기존 `GET /api/v1/instruments`의 여섯 악기 `code/name/status/version` 계약을 유지한다. 활성 회원의 예약 화면은 현재 상태를 확인할 수 있다.
- `GET /api/v1/instrument-faults`는 전체 관리자만 이력을 조회한다. 선택적 `instrumentCode`, `beforeId`와 `limit`(기본 20, 최대 50)을 받고 `{serverTime, incidents, nextBeforeId}`를 반환한다. ID 내림차순으로 등록·복구 이력을 보존한다.
- `POST /api/v1/instrument-faults`는 `{instrumentCode, expectedAssetVersion, description}`을 받는다. 사유는 trim 후 1~1000자 일반 텍스트다. 현재 악기가 NORMAL일 때 등록하고 악기 버전을 올린다.
- `POST /api/v1/instrument-faults/{id}/resolve`는 `{expectedVersion, expectedAssetVersion, resolutionNote?}`를 받는다. 복구 메모는 최대 1000자 일반 텍스트다. 아직 열린 해당 고장과 현재 악기 버전을 확인해 함께 복구하고 두 버전을 올린다.
- 등록·해제는 `Idempotency-Key`를 요구한다. 같은 실행자의 같은 경로·정규화 본문·키는 같은 이력을 재확인하고, 다른 본문/경로 재사용은 거절한다. 재확인 시에도 현재 전체 관리자 권한을 검사한다.
- 이력 응답은 `id/instrumentCode/instrumentName/status/description/origin/reportedByUserId/reportedAt/resolvedByUserId/resolvedAt/resolutionNote/version/assetVersion`을 제공한다. 상태는 OPEN/RESOLVED, origin은 ADMIN/LEGACY다. 실행자는 인증 계정에서 결정하며 로그인 ID·비밀 값은 포함하지 않는다.
- 이미 고장인 악기의 새 등록은 `INSTRUMENT_ALREADY_FAULTY`, 이미 복구한 이력의 새 해제는 `INSTRUMENT_FAULT_RESOLVED`, 오래된 버전은 `VERSION_CONFLICT` 409로 처리한다. 상태를 바꾼 이전 키의 성공 재확인은 허용한다.
- 불확실한 성공 응답은 동일 본문·키를 유지해 명시적으로 재확인하며, 그 전 다른 쓰기를 차단한다. 오프라인 자동 제출은 하지 않는다. 계정·역할·소속 변경 시 Query와 쓰기 상태를 분리한다.

## 저장과 잠금

V1~V7 파일을 수정하지 않고 V8에 `asset_incidents`와 `instrument_fault_idempotency_requests`를 추가한다. 한 악기당 열린 이력은 최대 한 건이며 고장·복구 내용과 실행자·시각을 보존한다. V8 전부터 FAULTY인 악기는 상태·버전을 바꾸지 않고 LEGACY 이력으로 연결한다. 원래 등록자·시각을 알 수 없으므로 등록자는 null이며 시각은 V8에서 기존 상태를 확인한 시각으로 구분해 표시한다.

쓰기의 기존 공통 순서는 `directory_mutation_guard → 현재 관리자 users → 해당 assets FOR UPDATE`이며 트랜잭션은 10초로 제한한다. 고장과 개인 예약의 assets SHARE 잠금이 commit 순서를 정한다. 고장 등록·해제와 이력·재시도 기록을 한 트랜잭션에서 확정한다. 예약·점유·한도·계보·회차·교환 상태는 고장 쓰기의 변경 대상이 아니다.

## 화면

`/admin/instrument-faults`에서 여섯 악기 현재 상태, 사유 입력·등록 확인, 열린 고장 복구 확인·선택 메모, 등록·복구 이력과 이전 이력을 제공한다. 데스크톱 관리 메뉴와 모바일 더보기에서 진입한다. 비관리자는 직접 URL과 API에서도 관리 작업을 할 수 없다. 기존 따뜻한 다이어리 디자인과 `JEONGEUM · SENTIMENTAL MUSIC` 문구를 유지한다.

## 검증 결과

| 항목 | 실제 결과 |
| --- | --- |
| Backend 컴파일·테스트·JAR | compileJava·bootJar 성공. 10 suites·212건 통과, 실패/오류/skip 0. 신규 악기 고장 27건, 기존 교환 39건·변경 32건·반복 31건·기본 예약 31건 포함 |
| Frontend | 15개 파일·144건 통과(고장 관리 16건), 타입 검사·lint 경고 0·production build 15개 경로 성공 |
| 관리자·입력·이력 | 실제 HTTP로 등록·복구·현재 역할/계정·CSRF·계약 외 필드·소수 버전 거절, 사유/메모 경계·버전·커서·재시도와 이력 보존 확인 |
| PostgreSQL 경합·롤백 | 별도 연결의 실제 guard/asset/day 잠금 대기, 두 commit 순서의 개인 예약↔고장, 동시 등록/복구, 권한 상실 재검사, 마지막 재시도 INSERT trigger 실패 및 실제 10초 table SHARE 잠금 timeout 후 전체 롤백·동일 키 재시도 통과 |
| 예약 정책·보존 | 고장 등록·해제 전후 예약·점유·quota·회차·대기 교환의 전체 값 보존. 개인 신규/실질 변경/교환 차단과 복구 후 동일 키 승인, no-op·팀·고정 배정 허용 확인 |
| V7→V8 | 모든 기존 30개 업무/세션 테이블의 합성 값을 채운 별도 스키마에서 기존 컬럼·assets 상태/버전 보존. FAULTY의 LEGACY 연결·단회 적용·재실행 0건·새 Flyway validate 통과 |
| 실제 격리 브라우저 | production Next 3003→Spring Boot 8083→임시 PostgreSQL 55435, 별도 QA 쿠키·합성 관리자/회원으로 메뉴·등록/복구·사유/메모·이력 필터·일반 회원 관리 접근 차단·회원 시간표 고장 표시와 기존 예약 확인 |
| 성공 응답 유실 | 실제 등록 201·복구 200 응답만 QA proxy에서 502로 유실. 다른 고장 쓰기 차단, 같은 본문·키·이력 ID 재확인. 중복 이력·버전 증가 없음 |
| 실제 격리 서버 재시작 | 같은 임시 DB/JAR로 새 JVM 기동 후 열린 고장·복구 이력·악기 상태/버전·재시도 키·기존 업무 테이블 유지 |
| 반응형 | 320/390/768/1440px 가로 넘침 없음. 390px에서 등록 확인·응답 유실 후 재확인 조작, 데스크톱 관리 메뉴·모바일 더보기 진입 확인 |

실제 브라우저에서 자신의 성공 응답이 유실된 뒤 최신 악기 버전이 조회되면 외부 변경 경고까지 함께 표시되는 문제를 발견했다. 불확실한 요청에서는 같은 요청 재확인을 안내하고 해당 경고를 숨기도록 수정했다. 쓰기 차단과 실제 외부 변경 경고는 유지하며 실제 render 회귀 검증과 최종 브라우저 재확인을 통과했다. 비활성 계정의 관리자 캐시를 제거하는 기존 공통 정리도 보완했다. 추가한 회귀 테스트의 객체 타입 오류는 수정 후 최종 타입 검사·focused 16건·빌드를 통과했다.

격리 QA 최종 상태는 보컬 복구 1건·건반 열린 고장 1건, 재시도 성공 기록 3건이다. 한 악기 열린 이력 중복·현재 상태 불일치는 0건이며 기존 22개 회원/팀/예약/한도/회차/교환 테이블의 건수·전체 값 해시가 최초 QA fixture와 동일했다. `.local/instrument-qa-*.json`, `instrument-ui-qa-mutation-trace.jsonl`, `instrument-responsive-proof.json`, `instrument-desktop-final.jpg`, `instrument-mobile-final.jpg` 등에 증빙을 보관하고 Git에서 제외한다. 검증 종료 후 자체 생성한 임시 프론트·JVM·PostgreSQL만 종료한다.

개발 DB는 읽기 전용으로 V1~V7 성공·활성 관리자 1명·예약/교환 요청 0건·public 31개 테이블·기존 볼륨을 확인했다. V8은 실제 개발 DB에 적용하지 않았고 기존 개발 3000/8080 서버를 새 코드로 기동하지 않았다. 실제 iOS/Android·가상 키보드·운영 배포·고장 Push·운영 외부 백업 복원은 이번 완료 주장에 포함하지 않는다. 개발 DB 적용과 GitHub 반영은 별도 승인 후 진행한다.

테스트 JVM 종료 시 기존 Spring Session 정리 작업의 connection acquisition 중단 경고가 출력됐지만 테스트 실패·오류는 0이고 Gradle 종료 코드는 0이다. 임시 컨테이너와 Spring 테스트 컨텍스트의 종료 과정 로그를 서비스 작업 실패와 구분한다.

## 사용자 승인 후 개발 DB 적용·서버 기동·GitHub 반영

2026-10-13 사용자가 백업 후 개발 DB V8 적용·최신 서버 기동·GitHub 반영을 승인했다. 커밋·Push·main 대상 Draft PR과 원격 CI 확인을 진행하며 정식 전환·main 병합은 별도 승인 대상이다. 앞 절의 V7·앱 중지 상태는 최초 격리 구현 시점의 기록이다.

- 적용 전 읽기 전용 집계에서 V1~V7 성공·활성 관리자 1명·예약/교환 요청 0건·public 31개 테이블·기존 볼륨을 확인했다. `local-v7-before-v8-20261013-011804-2860053.dump`는 116,974 bytes·`pg_restore --list` 243개 항목과 SHA-256을 확인했다. 이 실제 백업 자체를 복원하지는 않았으며 덤프·메타데이터·집계는 Git에서 제외한다.
- 기존 `scripts/Start-Backend.ps1`로 01:18 KST 최신 8080 백엔드를 기동했다. 정상 Flyway 경로에서 8개 migration validate, V7→V8 한 건 성공과 Spring Boot 구동을 확인했다. 기존 migration 강제 재적용·DB/볼륨 초기화를 하지 않았다.
- 기동 직후 기존 회원·팀·예약·점유·한도·회차·교환 등 27개 업무 테이블의 전체 값 집계와 V1~V7 이력이 동일했다. 기존 booking_days 행도 그대로이고 현재 예약 가능 범위의 새 날짜 1개가 기동 worker에서 개방됐다. 기존 세션 두 테이블의 행 수·해시가 동일했고 새 고장/재시도 테이블은 0건이다. public 테이블은 33개다.
- 기존 `scripts/Start-Frontend.ps1`로 3000 프론트엔드를 기동했다. `Test-ApplicationConnection.ps1`의 Next API 전달·DB health·no-store·HttpOnly 세션·CSRF/인증 보호가 통과했다. `/admin/instrument-faults` 200과 익명 고장 이력 API의 401 AUTHENTICATION_REQUIRED·no-store를 확인했다.
- 최종 대조에서도 기존 업무 데이터·관리자·예약·고장 상태·V1~V7 이력·볼륨은 동일했다. 접속 검사로 익명 세션 1건이 추가돼 세션 9→10건으로 기록하며 기동 직후 보존된 기존 세션과 구분한다. 실제 회원 계정으로 고장·복구·예약 쓰기를 검증하지 않았다. 결과는 Git 제외 `.local/instrument-v8-before.json`, `instrument-v8-after.json`, `instrument-v8-final.json`에 보관한다.

실제 접근 경로는 `http://127.0.0.1:3000/admin/instrument-faults`다. 로그인 후 데스크톱 **관리 → 악기 고장 관리**, 모바일 **더보기 → 악기 고장 관리**로 진입한다. 3000/8080 서버는 실행 상태로 유지한다. 코드 변경이 없는 기존 212/144건·타입/lint·양쪽 빌드·격리 브라우저 증빙을 재사용하며 새 원격 CI는 생성한 초안 PR의 현재 커밋에서 확인한다. README와 정식 전환·main 병합은 이번 반영 범위에 포함하지 않는다.

### 사용 한도 중단 후 최종 게시 재개

2026-10-13 사용자가 기존 구현·검증·백업·V8 적용 결과를 보존하며 남은 커밋·Push·Draft PR·CI 확인을 재승인했다. 시작 시 `codex/instrument-incidents`·HEAD `1797576`, 기존 staged 33개 파일과 unstaged 0건을 확인했다. 로컬/원격 main도 `1797576`이며 해당 기능 원격 브랜치와 PR은 아직 없었다. 기존 backend/frontend 최종 리뷰에서 blocker·금지 경로·자격증명 패턴은 없었으며 완료된 검증을 재사용한다.

V8 직전 백업 원본의 116,974 bytes·SHA-256이 메타데이터와 일치했다. 백업을 새로 만들거나 덮어쓰기·복원하지 않았다. 현재 Docker·앱 서버가 중지돼 있어 기존 Docker와 PostgreSQL 볼륨을 유지하며 시작했다. 앱 기동 전 읽기 전용 비교에서 V1~V8 성공·public 33개 테이블·관리자/회원 계정 1명·예약/교환/고장 이력 0건·모든 업무/기존 날짜/Flyway 값이 이전 최종 결과와 일치했다.

세션은 10건과 속성 해시가 동일했지만 세션 값 해시는 달랐다. 이전 최종 점검 이후의 접속 시각과 90일 만료 갱신이 관측됐으며 비정상 시각·만료 세션은 0건이었다. 이를 정상 접속 갱신으로 해석하며 전체 세션 행의 해시 일치로 보고하지 않는다.

중지된 앱만 기존 실행 스크립트로 14:12 KST 재기동했다. Spring Boot는 V8과 8개 migration validate를 확인하고 `No migration necessary`로 추가 적용 없이 시작했다. 3000 프론트·8080 백엔드 health와 API 전달·HttpOnly 세션·no-store·CSRF/인증 보호, 관리 페이지 200·익명 고장 API 401 검사를 다시 확인했다. 이번 재기동에서는 새 날짜 추가가 없었고 접속 검사 익명 세션 한 건만 10→11건으로 추가됐다. 실제 고장·예약·교환 쓰기는 하지 않았다. 기존 212건 JUnit XML(실패/오류/skip 0), 144건 프론트 기록·최종 소스/빌드·격리 브라우저 결과가 보존되어 전체 로컬 테스트와 브라우저 검증을 반복하지 않았다. 새 원격 CI는 게시한 커밋의 검사로 확인한다.

기존 스냅샷은 그대로 두고 재개 집계는 Git 제외 `.local/instrument-resume-*.json`에 추가 보관한다. 구현/배포 범위·실기기/Push/운영 백업 복원의 제한은 앞 절과 같다.
