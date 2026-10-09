# REST API 설계

작성일: 2026-10-09 / 기준 시간대: `Asia/Seoul` / 기본 경로: `/api/v1`

이 문서는 업무 기능의 설계 계약이다. 인증·초대에 가입 전 명단·계정 연결과 회원·팀·팀장·곡 관리를 추가한다. 현재 인증 계약·검증은 [AUTHENTICATION](AUTHENTICATION.md), 이번 단계의 실제 DTO·검증은 [MEMBER_TEAM_MANAGEMENT](MEMBER_TEAM_MANAGEMENT.md)을 따른다. 예약·알림 API와 OpenAPI 산출물은 후속 단계다. 사용자 정책은 [PROJECT_CONTEXT](PROJECT_CONTEXT.md)와 [RESERVATION_RULES](RESERVATION_RULES.md), 저장 모델은 [ERD](ERD.md)를 따른다.

Health는 실제 DB 상태에 따라 `200`/`503`과 `data.status=UP`/`DOWN`을 반환한다. CSRF는 `data.headerName=X-CSRF-TOKEN`, `data.token`을 반환한다. 두 응답은 공통 `meta`와 `Cache-Control: no-store`를 사용한다. 초기 보안 계층의 CSRF 누락·위조는 `403 CSRF_INVALID`, 미인증 보호 API 접근은 `401 AUTHENTICATION_REQUIRED`다.

## 1. 공통 계약

- JSON 요청·응답을 사용한다. 날짜는 `YYYY-MM-DD`, 시각은 offset이 있는 ISO 8601 문자열이다.
- 외부 식별자는 불투명한 문자열로 취급한다. DB bigint ID는 JSON 문자열로 전달하는 안이며, 예제의 이름형 ID는 실제 값이 아닌 자리표시자다.
- 날짜·한도·개방 범위는 서버의 서울 날짜로 계산한다. timestamp의 UTC 날짜를 예약일로 사용하지 않는다.
- 블록 코드는 B01~B10이다. 시작·종료·기간을 클라이언트 입력으로 받지 않는다.
- 악기 코드는 `VOCAL`, `KEYBOARD`, `ELECTRIC_GUITAR`, `BASS`, `DRUMS`, `ACOUSTIC_GUITAR`를 제안한다. 표시명은 각각 보컬·건반·일렉기타·베이스·드럼·통기타다.
- 인증은 서버 세션 쿠키다. 프론트와 API는 같은 origin의 `/api` 경로로 연결한다.
- 로그인은 마지막 접속 후 90일 동안 유지하고 인증 요청 때 서버 세션·쿠키 만료를 연장한다. `SESSION`은 영속 HttpOnly 쿠키이며 로그아웃·비활성·탈퇴와 만료 시 접근을 해제한다. 요청·응답 JSON 형식이나 별도 갱신 API는 추가하지 않는다. 자세한 계약은 [AUTHENTICATION](AUTHENTICATION.md)을 따른다.
- 상태 변경 요청은 CSRF token을 요구한다. 공개 가입·로그인에도 Spring Security 설정과 일치하는 CSRF 흐름을 적용한다.
- 실행자·개인 주체·알림함 소유자·구독 소유자는 인증 정보에서 결정한다. 요청의 임의 `userId`를 신뢰하지 않는다.
- 예약 변경·취소에는 예약의 `expectedVersion`, 교환 승인·거절·악기 배분에는 요청의 `expectedVersion`을 사용한다. 교환 항목에는 원본 예약 버전도 따로 저장한다. 서버가 잠금 후 현재 값과 비교한다.
- 상태 변경의 기술 권고안은 `Idempotency-Key`다. 사용자·경로·본문 fingerprint를 함께 검증하여 같은 재시도는 같은 결과를 반환한다. 다른 본문으로 같은 키를 재사용하면 거절한다. 이것이 업무 충돌 검사를 대체하지 않는다.
- 목록은 cursor pagination을 제안한다. 프론트의 편의용 `allowedActions`도 서버 권한 검증을 대체하지 않는다.

일반 성공은 `200`, 생성은 `201`, 본문이 필요 없는 삭제·해제는 `204`다.

```json
{
  "data": {},
  "meta": {
    "serverTime": "2026-10-30T14:10:00+09:00",
    "timeZone": "Asia/Seoul"
  }
}
```

기존 초대 목록은 `data.items`, `meta.nextCursor`를 사용한다. 이번 명단·회원·팀·곡 목록은 약 45명 규모에 맞춰 `data` 배열 전체를 반환하며 임의 개수로 자르지 않는다. 다른 후속 목록은 cursor 계약을 제안한다. version·곡 순서·숫자 ID에 소수가 전달되면 정수로 변환하지 않고 `400 INVALID_INPUT`으로 거절한다. 오류 계약은 다음을 제안한다.

```json
{
  "error": {
    "code": "RESERVATION_CONFLICT",
    "message": "선택한 시간에 예약이 있습니다.",
    "requestId": "request-identifier",
    "fieldErrors": [],
    "conflicts": [
      {"date": "2026-10-30", "blockCode": "B03", "instrumentCodes": ["DRUMS"]}
    ]
  }
}
```

권한이 없는 상대 회원의 정보나 민감한 운영 로그를 오류 응답에 포함하지 않는다.

| HTTP | 대표 code | 의미 |
|---|---|---|
| 400 | INVALID_INPUT, INVALID_BLOCK, INVALID_EXCHANGE_SHAPE | 입력이나 허용된 블록·교환 구조 오류 |
| 401 | AUTHENTICATION_REQUIRED, INVALID_CREDENTIALS | 미인증 또는 로그인 실패 |
| 403 | FORBIDDEN, MEMBERSHIP_REQUIRED, INACTIVE_USER, CSRF_INVALID | 현재 권한·소속·회원 상태 불충족 또는 CSRF 검증 실패 |
| 404 | RESOURCE_NOT_FOUND | 없거나 해당 사용자가 접근할 수 없는 대상 |
| 409 | RESERVATION_CONFLICT, PERSONAL_OVERLAP | 예약·악기·개인 동시간 충돌 |
| 409 | DAILY_LIMIT_EXCEEDED, ASSET_FAULTY | 최종 한도 초과, 신규 개인 배치의 고장 악기 |
| 409 | VERSION_CONFLICT, RESERVATION_STARTED, RESERVATION_FINISHED | 다른 변경 또는 처리 가능 시점 초과 |
| 409 | EXCHANGE_EXPIRED, EXCHANGE_INVALIDATED, ASSIGNMENT_CONFLICT | 교환 만료·무효화, 고정 배정 충돌 |
| 409 | DATE_NOT_OPEN, IDEMPOTENCY_KEY_REUSED, LOGIN_ID_TAKEN, INVITATION_ALREADY_USED | 현재 예약 범위 밖, 재시도 키 오용, 로그인 ID 중복, 사용한 초대 해지·재발급 거부 |
| 410 | INVITATION_UNAVAILABLE | 사용·해지·만료된 초대 |
| 429 | TOO_MANY_REQUESTS | 인증·초대 등 보안 목적 요청 제한 |
| 409 | ROSTER_ALREADY_LINKED, ACCOUNT_ALREADY_LINKED | 명단 또는 계정이 이미 연결됨 |
| 409 | MEMBERSHIP_ALREADY_EXISTS, INVITATION_ALREADY_EXISTS | 같은 팀의 활성 소속 또는 같은 명단의 미해지 초대 중복 |
| 409 | LAST_GLOBAL_ADMIN | 마지막 활성 전체 관리자 강등 거절 |

보안 목적 요청 제한은 하루 예약 한도를 새로 추가하는 정책이 아니다.

## 2. 인증과 초대

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| GET /auth/csrf | 공개 | 현재 세션의 CSRF header 이름과 token 제공 |
| POST /auth/login | 공개 + CSRF | 로그인 ID·비밀번호, 실패는 일관된 오류 |
| POST /auth/logout | 활성 회원 + CSRF | 현재 세션 무효화, 해당 기기 Push 연결 정리는 기술 제안 |
| GET /auth/me | 활성 회원 | 본인 프로필·전역 역할·활성 팀 소속·팀장 정보 |
| POST /auth/invitations/inspect | 공개 + CSRF | 본문의 초대 token 유효성 확인, 다른 회원 정보 제외 |
| POST /auth/register | 공개 + CSRF | 초대 token·로그인 ID·비밀번호·표시 이름, 사용 처리와 회원 생성 원자화 |
| POST /invitations | 전체 관리자 | 명단 고유 ID에 개인 초대 연결, 만료 시각·관리용 이름 기록 |
| GET /invitations | 전체 관리자 | 사용·만료·해지 상태, 원문 token을 다시 노출하지 않음 |
| DELETE /invitations/{id} | 전체 관리자 | 아직 사용하지 않은 초대 해지 |

가입 입력은 기술 제안이다. 승인된 인증 기능 구현에서는 loginId·password·displayName을 사용한다. 초대·입력 설정과 실제 검증 결과는 [AUTHENTICATION](AUTHENTICATION.md)에 기록한다. 필수 이메일, SMTP, SNS 로그인, 별도 가입 승인 단계는 확정 범위에 임의로 추가하지 않는다. 초대 원문은 발급 때 한 번만 전달하고 서버에는 hash를 저장한다. token을 URL query나 접근 로그에 남기지 않는 본문 전달을 사용한다.

이번 명단 연결 확장에서 초대 요청은 `rosterEntryId`를 사용한다. 이름만 받는 기존 요청은 새 명단을 한 항목 생성하는 호환 경로이며 기존 명단의 이름으로 검색·합치지 않는다. 재발급은 기존 초대의 명단 ID를 유지하고 다른 명단으로 변경하는 요청을 거절한다. 이미 계정이 연결된 명단에는 가입용 초대를 만들 수 없다. 공개 초대 확인은 만료 시각만 반환하며 명단 이름·팀 소속·역할은 노출하지 않는다.

재발급은 생성 요청의 선택적 `replacesInvitationId`로 기존 미사용 초대를 지정해 해지와 새 발급을 원자화한다. 대상 이름 일치만으로 다른 초대를 해지하지 않는다. 회원·초대 ID는 문자열로 전달한다. 가입은 `201` 회원 응답이며 자동 로그인하지 않는다. `GET /auth/me`의 `teamMemberships`는 팀 기능 구현 전에는 빈 목록이다. 공개 초대 확인은 본문의 `token`만 받고 유효한 초대의 `expiresAt`만 반환한다.

비밀번호 hash나 세션 secret을 응답하지 않는다. 최초 전체 관리자 계정은 공개 회원가입 API에서 역할을 지정해 만들지 않고 별도의 안전한 운영 초기 절차로 만든다.

## 3. 회원·팀·곡

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| GET /roster | 전체 관리자 | 가입 전·연결된 명단 목록, 연결 계정 정보 |
| POST /roster | 전체 관리자 | 이름으로 새 명단 생성; 이름 중복으로 기존 사람을 자동 판정하지 않음 |
| PATCH /roster/{id} | 전체 관리자 | 명단 이름 변경, version 검사 |
| POST /roster/{id}/link-account | 전체 관리자 | 기존 활성 계정 명시적 연결, 양쪽 일대일 제약, 남은 초대 해지 |
| GET /users | 전체 관리자 | 운영 회원 목록 |
| PATCH /users/{id}/role | 전체 관리자 | 전역 역할 변경·version·감사 기록, 마지막 활성 관리자 강등 거절 |
| POST /users/{id}/withdraw | 전체 관리자 | 탈퇴 처리, 미래·진행 중 개인 예약만 취소, 팀 예약 유지 |
| GET /teams | 활성 회원 | 팀 목록, 공개 필드는 최소화하는 제안 |
| POST /teams | 전체 관리자 | REGULAR/ADDITIONAL 팀 생성 |
| GET /teams/{id} | 활성 회원 | 팀 정보·곡 목록·권한별 명단 필드 |
| PATCH /teams/{id} | 전체 관리자 | 팀 이름 등 기본 정보 변경; 기존 팀 종류 전환은 범위 밖 |
| POST /teams/{id}/members | 전체 관리자 | `rosterEntryId`로 가입 전·가입 후 명단 추가, 복수 소속 허용, 같은 팀의 활성 소속 중복 거절 |
| DELETE /teams/{id}/members/{rosterEntryId} | 전체 관리자 | 명단의 소속 제거, 팀장 제거 시 후임 자동 지정 없음 |
| PUT /teams/{id}/leader | 전체 관리자 | 활성 소속 명단 중 팀장 지정, null로 지정 해제, 자동 전역 권한 부여 금지 |
| POST /teams/{id}/disband | 전체 관리자 | 팀 해체, 남은 팀 예약·반복 배정·관련 교환과 예정 알림 종료 |
| GET /teams/{id}/songs | 활성 회원 | 팀별 곡 목록; 비소속 회원 조회 범위는 기술 제안 |
| POST /teams/{id}/songs | 전체 관리자 또는 해당 팀장 | 곡명 필수, 아티스트 선택, 표시 순서 |
| PATCH /teams/{id}/songs/{songId} | 전체 관리자 또는 해당 팀장 | 곡 내용·표시 순서 변경 |
| DELETE /teams/{id}/songs/{songId}?version=N | 전체 관리자 또는 해당 팀장 | 해당 팀의 곡 항목 삭제, version 검사 |

명단·소속·곡·역할·초대·가입 변경은 `directory_mutation_guard`를 먼저 잠그고 현재 실행자 권한·대상 명단·팀을 다시 검사한다. 팀장 지정은 연결된 활성 계정에 자기 팀 권한만 부여한다. 삭제 대상 곡이 요청 경로의 팀에 속하는지도 검증한다. 팀 상세 명단은 전체 관리자 또는 해당 팀의 활성 계정만 조회하며 다른 회원에게는 `members=[]`, `canViewMembers=false`를 반환하는 읽기 기술안을 적용한다.

이번 단계의 명단·회원·팀·곡 GET 목록은 약 45명 규모에 맞춰 `ApiResponse`의 `data` 배열로 반환하고 임의 개수로 잘라 누락하지 않는다. 기존 초대 목록만 cursor 계약을 유지한다. 탈퇴·팀 해체 API는 예약·교환·알림 정리까지 필요한 후속 구현이며 이번 단계에 제공하지 않는다.

탈퇴 후에도 과거 예약의 주체·최초 예약자 이력을 보존한다. 탈퇴자가 만들었던 존속 팀 예약을 함께 취소하거나 새 예약자로 덮어쓰지 않는다. 본인 서비스 탈퇴 버튼은 확정된 관리 범위에 임의로 추가하지 않는다.

## 4. 일정과 악기 상태

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| GET /schedule?from={date}&to={date} | 활성 회원 | 블록별 점유·고장·개방 상태·예약 가능 행동 |
| GET /instruments | 활성 회원 | 여섯 악기의 코드·현재 고장 정보 |
| POST /instrument-faults | 전체 관리자 | 악기 고장 등록, 기존 예약 유지 |
| POST /instrument-faults/{id}/resolve | 전체 관리자 | 고장 해제와 실행자 기록 |

일정 응답은 최소한 다음을 제공한다.

- `today`, `bookableThrough`(오늘+14), `serverTime`, `timeZone`
- 날짜별 `openingStatus`: 고정 배정 반영 전에는 빈 시간으로 공개하지 않는다.
- 블록별 `blockCode`, 정해진 `startsAt`·`endsAt`, `phase`, 남은 사용 가능 시간
- 팀 독점이면 팀명과 예약 상태; 개인 공유이면 악기별 점유 상태
- 악기별 고장 정보와 신규 개인 선택 가능 여부
- 본인·팀장·전체 관리자 등 현재 권한에 따른 행동 표시

조회 응답을 받은 뒤에도 실제 mutation은 현재 상태를 다시 검사한다. 예약하지 않은 미래 날짜를 조회할 수 있더라도 개방 범위 밖의 생성은 거절한다. 무인증 공개 일정은 현재 설계에 포함하지 않는다. 개인 이름 등 공개 범위는 정책 확정사항과 구분한다.

고장 등록은 여섯 점유 행에 가짜 예약을 생성하는 동작이 아니다. 팀 예약은 고장 안내와 함께 허용한다. 고장 등록과 신규 예약의 경합은 동일 악기 잠금으로 선후를 결정한다.

## 5. 예약 생성과 조회

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| POST /reservations | 활성 회원 | 본인 개인 또는 활성 소속 팀의 예약 한 건 생성 |
| POST /reservations/batches | 활성 회원 | 연속 60분 두 건의 원자적 생성 제안 |
| GET /reservations?scope=mine | 활성 회원 | 본인·소속 팀의 예약과 이력 |
| GET /reservations/{id} | 본인·해당 팀원·전체 관리자 | 현재 권한별 상세·후속 예약 안내 |
| PATCH /reservations/{id} | 예약자·해당 팀장·전체 관리자 | 시작 전 동일 주체의 날짜·블록·개인 악기 변경 |
| POST /reservations/{id}/cancel | 예약자·해당 팀장·전체 관리자 | 시작 전·진행 중 예약 전체 취소 |

개인 생성 예시:

```json
{
  "subjectType": "PERSONAL",
  "date": "2026-10-30",
  "blockCode": "B03",
  "instrumentCodes": ["DRUMS"]
}
```

팀 생성 예시:

```json
{
  "subjectType": "TEAM",
  "teamId": "team-id",
  "date": "2026-10-30",
  "blockCode": "B07"
}
```

팀 요청에서 악기를 받아 독점 범위를 줄이지 않는다. 서버가 여섯 자원을 모두 점유한다. 개인 생성은 90분이라도 악기 한 종류만 허용한다. 교환으로 생긴 2악기 예외를 일반 생성으로 만들 수 없다.

생성 응답은 예약 ID·버전·주체·최초 예약자·블록·효력 시작·종료·악기·한도 소비·고장 경고를 제공한다. 진행 중 예약의 효력 시작은 확정 당시 서버 시각이다. 자유 시작 시각 입력은 받지 않는다.

다른 개인 예약의 전체 상세나 다른 팀의 내부 명단을 일반 상세 API에서 조회하는 것은 기본안에 포함하지 않는다. 일정과 교환에는 상대 예약을 고르는 데 필요한 ID·버전·시간·주체 종류·악기 등 최소 필드를 별도 조회 계약으로 제공한다. 이 읽기 범위는 개인정보를 최소화하는 기술 제안이다.

두 시간 선택 UI는 두 개의 독립 예약을 만든다. batch 권고안은 같은 날짜·인접 60분 블록 두 건을 한 요청에서 전부 성공 또는 전부 실패하도록 처리한다. 개인은 각 항목에 한 악기를 선택할 수 있고 두 항목의 악기는 달라도 된다. 이 기술 계약을 120분 예약 한 건으로 저장하지 않는다. 90분 여러 건은 일반 단건 API를 사용한다.

### 5.1 변경 계약

```json
{
  "expectedVersion": 3,
  "date": "2026-10-31",
  "blockCode": "B08",
  "instrumentCodes": ["DRUMS"]
}
```

- 주체와 최초 예약자는 요청으로 바꿀 수 없다.
- 잠금 후 시작 전 여부, 새 날짜, 최종 한도, 제3자·개인·악기 점유, 고장을 재검증한다.
- 기술안: 원본은 `REPLACED`로 보존하고 후속 예약을 생성한다. 새 ID를 반환하며 계보를 연결한다.
- 기존 2악기 개인 90분은 90분으로 이동·악기 변경할 때 최대 두 종류 조건을 유지할 수 있다. 일반 한 악기 예약을 PATCH로 두 악기 예약으로 확대하지 않는다.
- 2악기 개인 90분에서 단일 60분으로 변경하려면 한 종류를 선택한다.
- 날짜·블록·악기 집합이 완전히 같은 입력은 no-op으로 제안한다. 원본 ID·면제·고장 보존 예외·교환 요청·예정 알림을 그대로 유지한다.
- 실제 변경에는 `CHANGED` 업무 사건만 만든다. 후속 DB 행 생성에 대한 별도 `CREATED` 알림을 중복 발송하지 않는다.

변경과 교환 성공 응답의 공통 기술안:

```json
{
  "data": {
    "replacedReservationIds": ["old-reservation-id"],
    "reservations": [{"id": "successor-id", "status": "CONFIRMED", "version": 1}]
  }
}
```

원본 상세를 조회하면 `successorIds`와 이력 상태를 제공한다. 프론트는 현재 예약으로 이동하거나 여러 후속 예약을 안내한다. 실패 시 원본을 `REPLACED`로 남겨서는 안 된다.

### 5.2 취소 계약

본문은 `expectedVersion`, 선택적 관리용 사유를 받는다. `CANCELED` 상태·점유 해제·시작 전 한도 복구·교환 무효화·예정 알림 종료를 한 트랜잭션으로 처리한다. 시작 후 60분은 한도를 복구하지 않는다.

종료된 예약은 `RESERVATION_FINISHED`로 거절한다. 동일한 완료 취소 재시도는 멱등 성공으로 제안하며 한도를 다시 복구하지 않는다. 두 악기 90분도 예약 전체가 취소되므로 두 악기를 모두 해제한다.

## 6. 교환 요청과 승인

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| POST /exchange-requests | 본인 측 모든 예약에 변경 권한 보유 | 양측 예약 ID·버전, 필요한 악기 배분을 기록 |
| GET /exchange-requests | 관련 활성 회원 또는 전체 관리자 | 본인·소속 팀 관련 요청과 상태 |
| GET /exchange-requests/{id} | 관련 활성 회원 또는 전체 관리자 | 원본·예상 결과·현재 승인 가능 여부 |
| PATCH /exchange-requests/{id}/instrument-allocation | 해당 개인 예약자 | 자신의 2악기 90분을 분배할 순서 기록 |
| POST /exchange-requests/{id}/approve | 상대 예약자·상대 팀장·전체 관리자 | 권한과 최종 결과 재검증 후 원자적 적용 |
| POST /exchange-requests/{id}/reject | 상대 예약자·상대 팀장·전체 관리자 | 원래 예약 유지, 요청 REJECTED |
| POST /exchange-requests/{id}/cancel | 활성 요청자 또는 전체 관리자 | 원래 예약 유지, 요청 CANCELED |

일반 팀원은 다른 팀원의 예약을 교환 요청·승인할 수 없다. 상대 개인 예약에는 팀장 권한을 적용하지 않는다. 관리자 승인도 주체나 최초 예약자를 바꾸지 않는다.

허용 구조는 `1↔1`, `2↔1`, `1↔2`이며 두 건 묶음은 연속 60분, 한 건 상대는 90분이어야 한다. `2↔2`, 임의 세 건 이상, 같은 예약을 양측에 넣는 구조는 확정 범위에 포함하지 않는다.

요청 예시:

```json
{
  "offered": [
    {"reservationId": "a-60-first", "expectedVersion": 1},
    {"reservationId": "a-60-second", "expectedVersion": 1}
  ],
  "requested": [{"reservationId": "b-90", "expectedVersion": 2}]
}
```

두 건은 같은 날짜·인접 블록·동일 주체·동일 최초 예약자여야 한다. 요청 생성 시에도 현재 구조와 시작 전 여부를 검사한다. 자원과 한도는 기존 그대로 유지하며 새로운 배치를 확보하지 않는다.

### 6.1 악기 배분과 결과

| 원본 개인 예약 | 받는 블록 | 결과 |
|---|---|---|
| 같은 악기 60분 두 건 | 90분 한 건 | 원래 악기 한 종류 |
| 서로 다른 악기 60분 두 건 | 90분 한 건 | 두 종류를 90분 전체에 점유 |
| 한 악기 90분 한 건 | 60분 두 건 | 두 블록에 같은 악기 |
| 두 악기 90분 한 건 | 60분 두 건 | 각 블록에 한 종류, 예약자 선택 순서 |
| 두 악기 90분 한 건 | 60분 한 건 | 악기 유지와 60분 한 종류 조건을 동시에 충족하지 못하므로 직접 교환 불가 |

팀은 어느 길이로 교환해도 각 결과 예약에서 방 전체를 독점한다. 교환에서 필요한 개인 순서는 해당 예약자가 선택해 요청에 저장하는 기술안이다. 전체 관리자가 대신 승인해도 저장된 선택을 적용하며 승인자로 주체·예약자를 바꾸지 않는다. 필요한 선택이 없으면 원본을 유지하고 `INVALID_EXCHANGE_SHAPE` 또는 배분 입력 오류를 반환한다.

### 6.2 승인과 실패

- 승인·거절·악기 배분은 교환 요청의 `expectedVersion`을 검증한다. 화면을 연 뒤 배분·상태가 바뀌었으면 최신 미리보기를 다시 확인한다.
- 양측 전체 원본과 현재 회원·팀·악기·날짜·예약을 공통 순서로 잠근다.
- 가장 이른 블록 시작 시각 이상이면 `EXPIRED`, 관련 원본이 이미 변경·취소됐으면 `INVALIDATED`다.
- 현재 승인 권한이 없으면 403이다. 다른 적법한 승인자가 남아 있는 요청을 역할 변경 한 건만으로 무조건 무효화하지 않는다.
- 교환 당사자의 원래 점유만 제외한 최종 제3자 충돌을 검사한다.
- 옮기는 모든 개인 악기의 현재 고장을 검사한다. 팀은 고장 경고와 함께 허용한다.
- 해제와 후속 예약을 함께 반영한 주체·서울 날짜별 최종 60분 한도를 검사한다.
- 전부 성공할 때 원본 REPLACED, 후속 예약·점유·한도, `COMPLETED`, 이력·알림을 한 트랜잭션으로 commit한다.
- 실패 시 양측 원본 예약·점유·한도는 그대로다. 한도·고장·제3자 충돌 같은 재검증 실패는 요청을 PENDING으로 유지하는 기술안이다. 만료·원본 변경은 EXPIRED/INVALIDATED로 구분한다.
- 성공한 원본을 참조하는 다른 대기 교환은 무효화한다. 생성 시각만 보고 오래된 요청을 승인하지 않는다.
- Push 실패는 이미 성공한 교환을 rollback하지 않는다.

## 7. 반복 고정 배정

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| GET /recurring-assignments | 전체 관리자 | 시리즈·주차별 회차·단건 예외 조회 |
| POST /recurring-assignments/preview | 전체 관리자 | 신규/수정안, 적용 시작일, 충돌 목록을 계산 |
| POST /recurring-assignments | 전체 관리자 | 팀·요일·블록·적용 시작일로 신규 배정 |
| PATCH /recurring-assignments/{id} | 전체 관리자 | expectedVersion과 적용 시작일로 패턴 변경 |
| POST /recurring-assignments/{id}/end | 전체 관리자 | 종료일 이후 새 회차 생성 중단, 개별 예외와 과거 이력 보존 |

preview는 자원 확보가 아니다. 실제 적용 시 잠금과 충돌 검사를 반복한다. 미래 패턴끼리의 요일·시간 충돌과 이미 개방된 날짜의 실제 예약 충돌을 검사한다. 충돌이면 `ASSIGNMENT_CONFLICT`와 목록을 반환하고 패턴·기존 예약·예외를 모두 유지한다.

회차는 안정적인 `assignmentId + weekStartDate`로 식별한다. 변경·교환으로 실제 날짜가 다른 주차로 이동해도 원래 회차와 후속 예약 계보를 유지한다. 단건 예외를 새 패턴이나 새 날짜의 다른 예약으로 오인해 원래 위치에 재생성하지 않는다.

적용 시작일 경계 주차와 일반 종료 시 기존 미래 회차 정리는 `RESERVATION_RULES`의 기술 기준을 따른다. 종료 API만으로 개별 변경·교환 예외를 조용히 취소하지 않는다. 관리자가 필요하면 해당 예약 취소를 명시적으로 처리한다. 팀 해체는 별도 정책에 따라 모든 미래·진행 중 팀 예약을 취소한다.

## 8. 공지·알림함·Push

| Method / 경로 | 권한 | 계약 |
|---|---|---|
| GET /notices, GET /notices/{id} | 활성 회원 | 전체 공지, 중요 고정 표시 |
| POST /notices | 전체 관리자 | 제목·본문·중요 여부 |
| PATCH /notices/{id} | 전체 관리자 | 공지 수정·고정 여부 변경 |
| DELETE /notices/{id} | 전체 관리자 | 공지 삭제, 이력 보관 방식은 기술안 |
| GET /notifications | 활성 회원 | 본인의 알림함, cursor·읽지 않은 항목 필터 |
| PATCH /notifications/{id}/read | 해당 수신자 | 멱등 읽음 처리 |
| POST /notifications/read-all | 활성 회원 | 본인 목록을 기준 시각까지 읽음 처리 |
| GET /push/public-key | 활성 회원 | VAPID 공개 키, 비밀키 제외 |
| GET /push-subscriptions | 활성 회원 | 본인 기기 목록, endpoint 원문을 최소화 |
| POST /push-subscriptions | 활성 회원 | 현재 브라우저의 구독 등록·갱신 |
| DELETE /push-subscriptions/{id} | 해당 소유자 | 해당 기기 구독 해제 |

구독 본문은 브라우저 PushSubscription의 endpoint와 keys를 받는다. 사용자 ID는 서버가 정하고 HTTPS 발송 대상·길이·키 형태를 검증한다. 임의 내부 URL로 서버가 발송하지 않도록 한다.

동일 기기 재등록, 다른 계정으로 전환, 만료 구독 정리, 제한된 재시도를 정의한다. 실제 OS 알림 허용은 사용자 선택이다. Push가 미지원·거절·실패해도 알림함과 예약 기능을 사용할 수 있다.

사건별 알림 수신자와 1시간 전 재설정은 [NOTIFICATIONS](NOTIFICATIONS.md)를 따른다. 일반 공지 전체 Push나 모든 예약의 전체 관리자 수신을 임의로 추가하지 않는다.

## 9. 구현 계약 검증

- 같은 요청의 재시도에서 중복 예약·이중 한도 복구·중복 업무 알림이 없는지 확인한다.
- 직접 API 호출로 임의 시각, 미소속 팀, 다른 개인 주체, 90분 일반 두 악기 생성이 불가능해야 한다.
- 일반 팀원에게 팀 전체 편집 권한을 주거나 상대 팀장이 다른 팀·개인을 승인하게 해서는 안 된다.
- 교환 승인과 취소·변경·탈퇴·해체·고장·만료가 경합할 때 현재 상태를 재검증한다.
- 변경·교환 rollback은 예약·점유·한도·회차·알림 작업을 함께 보존한다.
- schema 예시는 합성 데이터다. 실제 회원 정보나 비밀번호·token을 문서와 fixture에 넣지 않는다.
- 향후 OpenAPI와 구현 응답을 이 문서에 맞추고, 계약 변경 시 관련 ERD·UX·테스트 문서를 함께 갱신한다.
